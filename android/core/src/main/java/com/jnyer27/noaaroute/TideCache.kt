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
    /** Saved predictions only: nothing is fetched. */
    private val offline: Boolean = false,
) {
    companion object {
        const val BASE = "https://api.tidesandcurrents.noaa.gov/"
        /** A year of highs and lows per request: about 1,400, 70 KB from NOAA, 10 KB saved. */
        const val YEAR_DAYS = 365
        /** Fetch a station again when its saved highs and lows end within this many days. */
        const val REFRESH_DAYS = 30
        /** The station list changes rarely (new or retired stations). */
        const val STATIONS_DAYS = 30
        /** Requests at a time in [ensureAll]. */
        const val PARALLEL = 2
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
     * [ensure] each station, in the order given, [parallel] requests at a time (NOAA takes
     * about a second per year; it refused 8 at a time from GitHub's servers). Returns the
     * ids that could not be fetched (and have nothing saved). [progress] gets (done, total)
     * after each, from the fetching threads.
     */
    fun ensureAll(
        ids: List<String>, now: Long = System.currentTimeMillis(), parallel: Int = PARALLEL,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): List<String> {
        val failed = java.util.Collections.synchronizedList(ArrayList<String>())
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val workers = List(parallel.coerceIn(1, ids.size.coerceAtLeast(1))) {
            Thread {
                while (true) {
                    val k = next.getAndIncrement()
                    if (k >= ids.size) break
                    try { ensure(ids[k], now) } catch (_: Exception) { failed.add(ids[k]) }
                    progress(done.incrementAndGet(), ids.size)
                }
            }.apply { start() }
        }
        workers.forEach { it.join() }
        return ids.filter { it in failed }
    }

    /**
     * The nearest station to each of [points] (within 30 km) with its highs and lows, fetched
     * if needed, at most [max] stations, in the points' order; stations that cannot be had are
     * skipped. The station list is fetched first if none is saved.
     */
    fun near(points: List<LatLon>, now: Long = System.currentTimeMillis(), max: Int = 8): List<TideAt> {
        val stations = try { refreshStations(now) } catch (_: Exception) { return emptyList() }
        return points.flatMap { Tides.nearest(stations, it) }.distinctBy { it.id }.take(max)
            .mapNotNull { s -> try { TideAt(s, ensure(s.id, now)) } catch (_: Exception) { null } }
    }

    /**
     * Tides for a planned route on [date] in [zone]: the report lines for the stations nearest
     * the start and the destination (with [Tides.CAVEAT]), and the route with the day's range
     * added to legs flagged for shallow water ([Tides.annotate]). Saved predictions work
     * offline; missing ones are fetched (about a second each).
     */
    fun forRoute(route: Route, date: java.time.LocalDate, zone: java.time.ZoneId, now: Long = System.currentTimeMillis()): RouteTides {
        if (route.points.size < 2) return RouteTides(route, emptyList())
        val ats = near(Tides.routePoints(route), now)
        val stations = stations()
        val endIds = listOf(route.points.first(), route.points.last())
            .mapNotNull { Tides.nearest(stations, it).firstOrNull()?.id }.distinct()
        if (endIds.isEmpty() && ats.isEmpty()) {
            val why = if (stations.isEmpty()) "the NOAA station list could not be fetched" else "no NOAA tide station within 30 km of the start or destination"
            return RouteTides(route, listOf("tides: none ($why)"))
        }
        val ends = endIds.mapNotNull { id -> ats.firstOrNull { it.station.id == id } }
        val lines = Tides.reportLines(ends, date, zone).toMutableList()
        if (ends.size < endIds.size) lines.add("tides: predictions for " + endIds.filter { id -> ends.none { it.station.id == id } }
            .joinToString(", ") { id -> stations.first { it.id == id }.name } + " are not saved and NOAA could not be reached")
        if (lines.isNotEmpty()) lines.add("tides: ${Tides.CAVEAT}")
        return RouteTides(Tides.annotate(route, ats, date, zone), lines)
    }

    /** Stations inside [bounds] (west, south, east, north), e.g. a region's box. */
    fun stationsIn(bounds: DoubleArray): List<TideStation> = stations().filter {
        it.lon in bounds[0]..bounds[2] && it.lat in bounds[1]..bounds[3]
    }

    /** GET [url] as text. 403, 429 and 5xx wait and retry: 4, 8, 16, 32 s (five tries in all). */
    private fun get(url: String): String {
        if (offline) throw Exception("offline: NOAA tides not fetched")
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

/** A route with tide notes on its shallow legs, and the report's tide lines. */
class RouteTides(val route: Route, val lines: List<String>)
