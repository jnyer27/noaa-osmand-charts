package com.jnyer27.noaaroute

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Tides and TideCache on NOAA's own predictions (fixtures fetched 2026-11-08, in tides/). */
class TidesTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream("tides/$name")!!.bufferedReader().readText()
    private val gmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private fun minute(t: String) = LocalDateTime.parse(t, gmt).toEpochSecond(ZoneOffset.UTC) / 60
    private val ny = ZoneId.of("America/New_York")

    @Test
    fun parsesHighsAndLows() {
        val s = Tides.parseHiLo("8574680", res("baltimore-hilo-gmt.json"))
        assertEquals(19, s.extremes.size)
        // NOAA's first: 2026-11-07 04:23 GMT, low 0.381 ft
        val first = s.extremes.first()
        assertEquals(minute("2026-11-07 04:23"), first.minute)
        assertEquals(0.381, first.feet, 1e-9)
        assertFalse(first.high)
        assertTrue(s.extremes.zipWithNext().all { (a, b) -> a.minute < b.minute && a.high != b.high })
        assertEquals(first.feet, s.height(first.minute)!!, 1e-9)
    }

    @Test
    fun curveFollowsNoaasSixMinuteHeights() {
        // NOAA draws subordinate stations with a half cosine between highs and lows; at a
        // reference station the cosine is within 0.05 ft of the harmonic heights
        val s = Tides.parseHiLo("8574680", res("baltimore-hilo-gmt.json"))
        val six = (MiniJson.parse(res("baltimore-6min-gmt.json")) as Map<*, *>)["predictions"] as List<*>
        var worst = 0.0
        for (p in six) {
            val m = p as Map<*, *>
            val h = s.height(minute(m["t"] as String)) ?: continue
            worst = maxOf(worst, abs(h - (m["v"] as String).toDouble()))
        }
        assertEquals(480, six.size)
        assertTrue("worst $worst ft", worst < 0.1)
        assertNull(s.height(s.first!! - 1))
        assertNull(s.height(s.last!! + 1))
    }

    @Test
    fun localTimesAcrossTheClockChange() {
        // The same highs and lows in GMT and in NOAA's local time (EST, then EDT from
        // 2027-03-14 02:00): converting ours to America/New_York gives NOAA's local times
        val s = Tides.parseHiLo("8574680", res("baltimore-dst-gmt.json"))
        val local = (MiniJson.parse(res("baltimore-dst-local.json")) as Map<*, *>)["predictions"] as List<*>
        val ours = s.extremes.associateBy { java.time.Instant.ofEpochSecond(it.minute * 60).atZone(ny).toLocalDateTime().format(gmt) }
        var compared = 0
        for (p in local) {
            val m = p as Map<*, *>
            // NOAA's local list runs to local midnight, past the GMT one's end
            val utc = LocalDateTime.parse(m["t"] as String, gmt).atZone(ny).toEpochSecond() / 60
            if (utc > s.last!!) continue
            compared++
            val e = ours[m["t"] as String]
            assertNotNull("no high or low at local ${m["t"]}", e)
            assertEquals((m["v"] as String).toDouble(), e!!.feet, 1e-9)
        }
        assertTrue("compared $compared", compared >= 17)
        // 2027-03-14 is 23 hours long: NOAA's local list has L 04:59 (EDT), H 11:53, L 19:22
        val day = s.day(LocalDate.of(2027, 3, 14), ny)
        assertEquals(listOf(false, true, false), day.map { it.high })
        assertEquals(minute("2027-03-14 08:59"), day.first().minute)
    }

    @Test
    fun dayRange() {
        val s = Tides.parseHiLo("8574857", res("northpoint-hilo-gmt.json"))
        val r = s.range(LocalDate.of(2026, 11, 8), ny)!!
        val day = s.day(LocalDate.of(2026, 11, 8), ny)
        assertTrue(r.first <= day.minOf { it.feet } + 1e-9)
        assertTrue(r.second >= day.maxOf { it.feet } - 0.01)
        assertTrue(r.second - r.first > 0.5)
    }

    @Test
    fun noaaErrorBecomesAMessage() {
        val e = runCatching { Tides.parseHiLo("8574857", res("error.json")) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().startsWith("NOAA: No Predictions data was found"))
    }

    @Test
    fun stationsAndNearest() {
        val st = Tides.parseStations(res("stations.json"))
        assertEquals(6, st.size)
        val np = st.first { it.id == "8574857" }
        assertFalse(np.reference)
        assertEquals("8574680", np.refId)
        assertTrue(st.first { it.id == "8574680" }.reference)
        // Maryland Marina: North Point 14.6 km, then Baltimore 16.6 km (Tolchester 18.1 km)
        val near = Tides.nearest(st, LatLon(39.32371, -76.39998), n = 2)
        assertEquals(listOf("8574857", "8574680"), near.map { it.id })
        assertTrue(Tides.nearest(st, LatLon(30.0, -60.0)).isEmpty())
    }

    @Test
    fun savedFileRoundTrip() {
        val s = Tides.parseHiLo("8574680", res("baltimore-hilo-gmt.json"))
        val f = File.createTempFile("tide", ".tide")
        try {
            s.write(f)
            val back = TideSeries.read(f)
            assertEquals("8574680", back.station)
            assertEquals(s.extremes.map { it.minute }, back.extremes.map { it.minute })
            assertEquals(s.extremes.map { it.high }, back.extremes.map { it.high })
            for ((a, b) in s.extremes.zip(back.extremes)) assertEquals(a.feet, b.feet, 0.005)
        } finally { f.delete() }
    }

    // TideCache against a local server standing in for NOAA

    private lateinit var server: HttpServer
    private lateinit var root: File
    private val hits = ArrayList<String>()
    private var refuse = 0
    private val slept = ArrayList<Long>()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val q = ex.requestURI.toString()
            hits.add(q)
            val body = when {
                refuse > 0 -> { refuse--; null }
                q.startsWith("/mdapi/prod/webapi/stations.json") -> res("stations.json")
                q.contains("station=8574680") -> res("baltimore-hilo-gmt.json")
                else -> res("error.json")
            }
            if (body == null) ex.sendResponseHeaders(403, -1)
            else { val b = body.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            ex.close()
        }
        server.start()
        root = Files.createTempDirectory("tides").toFile()
    }

    @After
    fun stop() {
        server.stop(0)
        root.deleteRecursively()
    }

    private fun cache() = TideCache(root, "http://127.0.0.1:${server.address.port}/", sleep = { slept.add(it) })

    /** 2026-11-08 12:00 UTC, inside the fixture's highs and lows. */
    private val now = minute("2026-11-08 12:00") * 60_000

    @Test
    fun cacheFetchesOnceAndKeepsItOffline() {
        val c = cache()
        assertEquals(6, c.refreshStations(now).size)
        assertEquals(19, c.ensure("8574680", now).extremes.size)
        val url = hits.last()
        assertTrue(url, url.contains("begin_date=20261108") && url.contains("end_date=20271108") &&
            url.contains("datum=MLLW") && url.contains("time_zone=gmt") && url.contains("interval=hilo"))
        // The fixture ends 2026-11-11: within 30 days, so it is fetched again; offline the saved one is used
        val before = hits.size
        server.stop(0)
        assertEquals(19, c.ensure("8574680", now).extremes.size)
        assertEquals(6, c.refreshStations(now + 40L * 86_400_000).size)
        assertEquals(before, hits.size)
        assertNotNull(TideCache(root).series("8574680"))
        assertTrue(c.isSaved("8574680"))
        assertFalse(c.isSaved("8574857"))
    }

    @Test
    fun freshSeriesIsNotFetchedAgain() {
        val c = cache()
        c.ensure("8574680", now)
        val n = hits.size
        // Pretend it is 2026-10-01: the saved highs and lows run past 30 days from then
        assertFalse(c.needsFetch("8574680", minute("2026-10-01 00:00") * 60_000))
        c.ensure("8574680", minute("2026-10-01 00:00") * 60_000)
        assertEquals(n, hits.size)
        assertTrue(c.needsFetch("8574680", now))
    }

    @Test
    fun refusedRequestsWaitAndRetry() {
        refuse = 2
        val c = cache()
        assertEquals(19, c.ensure("8574680", now).extremes.size)
        assertEquals(listOf(4_000L, 8_000L), slept)
        assertEquals(3, hits.size)
    }

    @Test
    fun ensureAllReportsStationsItCouldNotGet() {
        val c = cache()
        val done = ArrayList<Int>()
        val failed = c.ensureAll(listOf("8574680", "8574857"), now) { k, _ -> done.add(k) }
        assertEquals(listOf("8574857"), failed)
        assertEquals(listOf(1, 2), done)
        assertNotNull(c.series("8574680"))
        assertNull(c.series("8574857"))
    }
}
