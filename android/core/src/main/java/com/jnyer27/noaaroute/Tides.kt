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

/** A station with its saved predictions. */
class TideAt(val station: TideStation, val series: TideSeries)

/** NOAA CO-OPS tide predictions: parsing their JSON, the nearest stations, and the report's tide lines. */
object Tides {
    /** Every place a tide shows says this. */
    const val PREDICTED = "predicted, feet above chart datum"
    /** Said with every tide shown: the route's depths stay charted depths at MLLW. */
    const val CAVEAT = "Predicted, not observed. Wind and pressure can move the water feet from this, " +
        "most of all on the Chesapeake in a northwest wind."
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US)
    private val DAY_NAME = DateTimeFormatter.ofPattern("EEE MMM d", java.util.Locale.US)

    fun clock(minute: Long, zone: ZoneId): String =
        CLOCK.format(Instant.ofEpochSecond(minute * 60).atZone(zone)).lowercase(java.util.Locale.US)

    private fun ft(v: Double) = String.format(java.util.Locale.US, "%.1f", v).replace("-0.0", "0.0")

    /** "low 0.4 ft 12:17 am, high 1.1 ft 5:16 am, ..." for [date], or null with none saved. */
    fun dayText(s: TideSeries, date: LocalDate, zone: ZoneId): String? {
        val day = s.day(date, zone)
        if (day.isEmpty()) return null
        return day.joinToString(", ") { (if (it.high) "high " else "low ") + ft(it.feet) + " ft " + clock(it.minute, zone) }
    }

    /** The report's line for each station: "tides at North Point, Sun Nov 8 (predicted, ...): low 0.4 ft 12:17 am, ...". */
    fun reportLines(ats: List<TideAt>, date: LocalDate, zone: ZoneId): List<String> = ats.mapNotNull { t ->
        dayText(t.series, date, zone)?.let { "tides at ${t.station.name}, ${DAY_NAME.format(date)} ($PREDICTED): $it" }
    }

    /** "now 1.2 ft and rising" at [minute], or null outside the saved highs and lows. */
    fun nowText(s: TideSeries, minute: Long): String? {
        val h = s.height(minute) ?: return null
        val next = s.extremes.firstOrNull { it.minute > minute } ?: return "now ${ft(h)} ft"
        return "now ${ft(h)} ft and " + (if (next.high) "rising" else "falling") + " to ${if (next.high) "high" else "low"} " +
            ft(next.feet) + " ft"
    }

    /**
     * When the predicted height on [date] in [zone] is at least [feet]: (from, to) minute
     * pairs, to the minute. Empty when it never gets there that day.
     */
    fun above(s: TideSeries, date: LocalDate, zone: ZoneId, feet: Double): List<Pair<Long, Long>> {
        val from = date.atStartOfDay(zone).toEpochSecond() / 60
        val to = date.plusDays(1).atStartOfDay(zone).toEpochSecond() / 60
        val out = ArrayList<Pair<Long, Long>>()
        var start: Long? = null
        for (m in from..to) {
            val h = s.height(m)
            val up = h != null && h >= feet
            if (up && start == null) start = m
            if ((!up || m == to) && start != null) { out.add(start to (if (up) m else m - 1)); start = null }
        }
        return out
    }

    /** [above]'s times as "4:10 am to 9:30 am, 3:00 pm to midnight", "all day", or null for none. */
    fun aboveText(windows: List<Pair<Long, Long>>, date: LocalDate, zone: ZoneId): String? {
        if (windows.isEmpty()) return null
        val from = date.atStartOfDay(zone).toEpochSecond() / 60
        val to = date.plusDays(1).atStartOfDay(zone).toEpochSecond() / 60
        if (windows.size == 1 && windows[0].first == from && windows[0].second == to) return "all day"
        fun t(m: Long) = if (m == to) "midnight" else clock(m, zone)
        return windows.joinToString(", ") { (a, b) -> "${if (a == from) "midnight" else t(a)} to ${t(b)}" }
    }

    private const val NOTE = "tide today at "

    /** True for the tide note [annotate] adds (information only, whatever the station's name says). */
    fun isNote(note: String) = note.startsWith(NOTE)

    /**
     * Adds the day's predicted tide range at the nearest station (within 30 km of the leg's
     * middle) to every leg flagged for shallow water. Information only: the leg stays flagged
     * and routing is unchanged. A note already there is not added again.
     */
    fun annotate(route: Route, ats: List<TideAt>, date: LocalDate, zone: ZoneId): Route {
        if (ats.isEmpty()) return route
        var changed = false
        val legs = route.legs.mapIndexed { i, leg ->
            if (leg.shoalM <= 0 || i + 1 >= route.points.size) return@mapIndexed leg
            val a = route.points[i]
            val b = route.points[i + 1]
            val mid = LatLon((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
            val t = nearest(ats.map { it.station }, mid).firstOrNull()?.let { s -> ats.first { it.station.id == s.id } }
                ?: return@mapIndexed leg
            val r = t.series.range(date, zone) ?: return@mapIndexed leg
            val note = "$NOTE${t.station.name}: ${ft(r.first)} to ${ft(r.second)} ft above chart datum (predicted)"
            if (note in leg.notes) leg else { changed = true; Leg(leg.distM, leg.bearing, leg.shoalM, leg.lowM, leg.notes + note) }
        }
        return if (!changed) route
        else Route(route.name, route.points, legs, route.depthFt, route.airFt, route.resM, route.messages, route.guideM)
    }

    /** Where to look up tides for a route: its ends and the middle of each leg flagged for shallow water. */
    fun routePoints(route: Route): List<LatLon> {
        val out = ArrayList<LatLon>()
        route.points.firstOrNull()?.let { out.add(it) }
        route.points.lastOrNull()?.let { out.add(it) }
        for ((i, leg) in route.legs.withIndex()) {
            if (leg.shoalM <= 0 || i + 1 >= route.points.size) continue
            val a = route.points[i]; val b = route.points[i + 1]
            out.add(LatLon((a.lat + b.lat) / 2, (a.lon + b.lon) / 2))
        }
        return out
    }

    /** Tide stations for the chart: id, name, and whether predictions are saved. */
    fun stationsGeoJson(stations: List<TideStation>, saved: Set<String>): String = buildString {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        stations.forEachIndexed { k, s ->
            if (k > 0) append(',')
            append("{\"type\":\"Feature\",\"properties\":{\"id\":").append(quote(s.id))
            append(",\"name\":").append(quote(s.name)).append(",\"saved\":").append(s.id in saved)
            append(String.format(java.util.Locale.US, "},\"geometry\":{\"type\":\"Point\",\"coordinates\":[%.5f,%.5f]}}", s.lon, s.lat))
        }
        append("]}")
    }

    private fun quote(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> if (c < ' ') append(' ') else append(c)
        }
        append('"')
    }

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

    /** [stations] nearest [p] first (all of them; for fetching the boater's own waters first). */
    fun byDistance(stations: List<TideStation>, p: LatLon?): List<TideStation> =
        if (p == null) stations else stations.sortedBy { Planner.distanceM(p, it.point) }

    fun minuteOf(i: Instant) = i.epochSecond / 60
}
