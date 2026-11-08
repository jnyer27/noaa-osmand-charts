package com.jnyer27.noaaroute

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * NOAA tide predictions kept on the phone (or the computer, for the command-line planner):
 * the station list, and a year of highs and lows per station, fetched from NOAA when needed
 * and refreshed when under [REFRESH_DAYS] are left. Predictions never go stale, so saved ones
 * work offline until they run out. Blocking calls: run them off the UI thread.
 *
 * NOAA refuses bursts of requests (HTTP 403 or 429), so requests go one at a time and wait
 * longer after each refusal. [sleep] is replaceable for tests.
 */
class TideCache(
    val dir: File,
    private val base: String = BASE,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    companion object {
        const val BASE = "https://api.tidesandcurrents.noaa.gov/"
        /** A year of highs and lows per request: about 1,400, 70 KB from NOAA, 10 KB saved. */
        const val YEAR_DAYS = 365
        /** Fetch a station again when its saved highs and lows end within this many days. */
        const val REFRESH_DAYS = 30
        /** The station list changes rarely (new or retired stations). */
        const val STATIONS_DAYS = 30
        private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
    }

    private val stationsFile = File(dir, "stations.json")
    private fun seriesFile(id: String) = File(dir, "$id.tide")

    init { dir.mkdirs() }

    /** The saved station list (empty if never fetched). */
    fun stations(): List<TideStation> =
        try { Tides.parseStations(stationsFile.readText()) } catch (_: Exception) { emptyList() }

    /** Fetch the station list if it is missing or over [STATIONS_DAYS] old; the saved one stays on failure. */
    fun refreshStations(now: Long = System.currentTimeMillis(), force: Boolean = false): List<TideStation> {
        if (force || !stationsFile.isFile || now - stationsFile.lastModified() > STATIONS_DAYS * 86_400_000L) {
            try {
                val text = get("${base}mdapi/prod/webapi/stations.json?type=tidepredictions")
                if (Tides.parseStations(text).isEmpty()) throw Exception("NOAA's tide station list is empty")
                val tmp = File(dir, "stations.json.${System.nanoTime()}.part")
                tmp.writeText(text)
                if (!tmp.renameTo(stationsFile)) { tmp.copyTo(stationsFile, overwrite = true); tmp.delete() }
            } catch (e: Exception) {
                if (stations().isEmpty()) throw e
            }
        }
        return stations()
    }

    /** True if station [id] has saved highs and lows (without reading them). */
    fun isSaved(id: String) = seriesFile(id).isFile

    /** The saved highs and lows of station [id], or null. */
    fun series(id: String): TideSeries? =
        seriesFile(id).takeIf { it.isFile }?.let { try { TideSeries.read(it) } catch (_: Exception) { null } }

    /** True if station [id] has no saved highs and lows, or they end within [REFRESH_DAYS] of [now]. */
    fun needsFetch(id: String, now: Long = System.currentTimeMillis()): Boolean {
        val last = series(id)?.last ?: return true
        return last * 60_000L - now < REFRESH_DAYS * 86_400_000L
    }

    /**
     * Station [id]'s highs and lows, fetched (a year from today, UTC) when [needsFetch].
     * On a failed fetch the saved ones are returned if there are any, else it throws.
     */
    fun ensure(id: String, now: Long = System.currentTimeMillis()): TideSeries {
        if (!needsFetch(id, now)) return series(id)!!
        return try {
            val begin = Instant.ofEpochMilli(now)
            val end = begin.plusSeconds(YEAR_DAYS * 86_400L)
            val text = get("${base}api/prod/datagetter?product=predictions&application=noaa_route" +
                "&station=$id&begin_date=${DAY.format(begin)}&end_date=${DAY.format(end)}" +
                "&datum=MLLW&units=english&time_zone=gmt&interval=hilo&format=json")
            Tides.parseHiLo(id, text).also { it.write(seriesFile(id)) }
        } catch (e: Exception) {
            series(id) ?: throw e
        }
    }

    /**
     * [ensure] each station in turn. Returns the ids that could not be fetched (and have
     * nothing saved). [progress] gets (done, total) after each.
     */
    fun ensureAll(ids: List<String>, now: Long = System.currentTimeMillis(), progress: (Int, Int) -> Unit = { _, _ -> }): List<String> {
        val failed = ArrayList<String>()
        for ((k, id) in ids.withIndex()) {
            try { ensure(id, now) } catch (_: Exception) { failed.add(id) }
            progress(k + 1, ids.size)
        }
        return failed
    }

    /** Stations inside [bounds] (west, south, east, north), e.g. a region's box. */
    fun stationsIn(bounds: DoubleArray): List<TideStation> = stations().filter {
        it.lon in bounds[0]..bounds[2] && it.lat in bounds[1]..bounds[3]
    }

    /** GET [url] as text. 403, 429 and 5xx wait and retry: 4, 8, 16, 32 s (five tries in all). */
    private fun get(url: String): String {
        var wait = 4_000L
        for (attempt in 1..5) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.instanceFollowRedirects = true
            try {
                val code = c.responseCode
                if (code == 200) return c.inputStream.bufferedReader().use { it.readText() }
                // NOAA answers some errors (no predictions for a datum) with JSON and 400
                if (code == 400) c.errorStream?.bufferedReader()?.use { it.readText() }?.let { return it }
                if ((code == 403 || code == 429 || code >= 500) && attempt < 5) {
                    sleep(wait)
                    wait *= 2
                    continue
                }
                throw Exception("HTTP $code from NOAA tides")
            } finally {
                c.disconnect()
            }
        }
        throw Exception("NOAA tides did not answer")
    }
}
