package com.jnyer27.noaaroute

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/** A NOAA tide prediction station (reference = harmonic station; else subordinate of [refId]). */
class TideStation(
    val id: String, val name: String, val state: String, val lat: Double, val lon: Double,
    val reference: Boolean, val refId: String?,
) {
    val point get() = LatLon(lat, lon)
}

/** One predicted high or low: [minute] since 1970 UTC, [feet] above MLLW. */
class TideExtreme(val minute: Long, val feet: Double, val high: Boolean)

/**
 * NOAA's predicted highs and lows for one station, in feet above chart datum (MLLW). The
 * height between them follows a half cosine from one to the next, which is how NOAA itself
 * draws subordinate stations; at Baltimore it is within 0.05 ft of NOAA's 6-minute heights.
 */
class TideSeries(val station: String, val extremes: List<TideExtreme>) {
    val first: Long? get() = extremes.firstOrNull()?.minute
    val last: Long? get() = extremes.lastOrNull()?.minute

    /** Predicted feet above MLLW at [minute] since 1970 UTC, or null outside the saved highs and lows. */
    fun height(minute: Long): Double? {
        if (extremes.size < 2 || minute < extremes.first().minute || minute > extremes.last().minute) return null
        var lo = 0
        var hi = extremes.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (extremes[mid].minute <= minute) lo = mid else hi = mid
        }
        val a = extremes[lo]
        val b = extremes[hi]
        if (b.minute == a.minute) return a.feet
        val f = (minute - a.minute).toDouble() / (b.minute - a.minute)
        return a.feet + (b.feet - a.feet) * (1 - cos(PI * f)) / 2
    }

    /** The highs and lows on [date] in [zone] (the boater's clock). */
    fun day(date: LocalDate, zone: ZoneId): List<TideExtreme> {
        val from = date.atStartOfDay(zone).toEpochSecond() / 60
        val to = date.plusDays(1).atStartOfDay(zone).toEpochSecond() / 60
        return extremes.filter { it.minute in from until to }
    }

    /** Lowest and highest predicted height on [date] in [zone]: its highs and lows, and the curve at midnight either end. */
    fun range(date: LocalDate, zone: ZoneId): Pair<Double, Double>? {
        val from = date.atStartOfDay(zone).toEpochSecond() / 60
        val to = date.plusDays(1).atStartOfDay(zone).toEpochSecond() / 60
        val hs = listOfNotNull(height(from), height(to)) + day(date, zone).map { it.feet }
        return if (hs.isEmpty()) null else hs.min() to hs.max()
    }

    /** Compact file: "NRTD1", count, then per extreme minute (int), hundredths of a foot (short), high (byte). */
    fun write(f: File) {
        val tmp = File(f.parentFile, "${f.name}.${System.nanoTime()}.part")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeBytes(MAGIC)
            out.writeUTF(station)
            out.writeInt(extremes.size)
            for (e in extremes) {
                out.writeInt(e.minute.toInt())
                out.writeShort((e.feet * 100).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
                out.writeByte(if (e.high) 1 else 0)
            }
        }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }

    companion object {
        private const val MAGIC = "NRTD1"

        fun read(f: File): TideSeries = DataInputStream(f.inputStream().buffered()).use { inp ->
            val magic = ByteArray(MAGIC.length).also { inp.readFully(it) }
            if (String(magic) != MAGIC) throw Exception("${f.name} is not a tide file")
            val id = inp.readUTF()
            val n = inp.readInt()
            TideSeries(id, List(n) {
                TideExtreme(inp.readInt().toLong(), inp.readShort() / 100.0, inp.readByte().toInt() != 0)
            })
        }
    }
}

/** NOAA CO-OPS tide predictions: parsing their JSON, and the nearest stations to a point. */
object Tides {
    private val GMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** The stations from mdapi stations.json?type=tidepredictions. */
    fun parseStations(json: String): List<TideStation> {
        val root = MiniJson.parse(json) as? Map<*, *> ?: throw Exception("tide station list is not JSON")
        val list = root["stations"] as? List<*> ?: throw Exception("tide station list has no stations")
        return list.mapNotNull { s ->
            val m = s as? Map<*, *> ?: return@mapNotNull null
            val id = m["id"] as? String ?: return@mapNotNull null
            val lat = (m["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
            val lon = (m["lng"] as? Number)?.toDouble() ?: return@mapNotNull null
            TideStation(id, (m["name"] as? String).orEmpty(), (m["state"] as? String).orEmpty(), lat, lon,
                m["type"] == "R", (m["reference_id"] as? String)?.ifEmpty { null })
        }
    }

    /**
     * Highs and lows from the datagetter (product=predictions, interval=hilo, datum=MLLW,
     * units=english, time_zone=gmt). NOAA's error reply becomes an exception with its message.
     */
    fun parseHiLo(station: String, json: String): TideSeries {
        val root = MiniJson.parse(json) as? Map<*, *> ?: throw Exception("tide predictions for $station are not JSON")
        (root["error"] as? Map<*, *>)?.let { throw Exception("NOAA: ${it["message"] ?: "error"} ($station)") }
        val list = root["predictions"] as? List<*> ?: throw Exception("no tide predictions for $station")
        val out = list.mapNotNull { p ->
            val m = p as? Map<*, *> ?: return@mapNotNull null
            val t = m["t"] as? String ?: return@mapNotNull null
            val v = (m["v"] as? String)?.toDoubleOrNull() ?: (m["v"] as? Number)?.toDouble() ?: return@mapNotNull null
            val minute = LocalDateTime.parse(t, GMT).toEpochSecond(ZoneOffset.UTC) / 60
            TideExtreme(minute, v, (m["type"] as? String)?.startsWith("H") == true)
        }.sortedBy { it.minute }
        if (out.isEmpty()) throw Exception("no tide predictions for $station")
        return TideSeries(station, out)
    }

    /** Up to [n] stations nearest [p], within [maxM] meters, nearest first. */
    fun nearest(stations: List<TideStation>, p: LatLon, n: Int = 1, maxM: Double = 30_000.0): List<TideStation> =
        stations.map { it to Planner.distanceM(p, it.point) }
            .filter { it.second <= maxM }
            .sortedBy { it.second }
            .take(n)
            .map { it.first }

    fun minuteOf(i: Instant) = i.epochSecond / 60
}
