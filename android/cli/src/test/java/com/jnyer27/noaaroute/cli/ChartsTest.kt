package com.jnyer27.noaaroute.cli

import com.jnyer27.noaaroute.ChartDepthChart
import com.jnyer27.noaaroute.ChartDepthRoute
import com.jnyer27.noaaroute.LatLon
import com.jnyer27.noaaroute.Leg
import com.jnyer27.noaaroute.Planner
import com.jnyer27.noaaroute.Route
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.DriverManager
import kotlin.math.cos

/** The command line's chart files: sqlite-jdbc reading an MBTiles chart, and the chart cache. */
class ChartsTest {
    private lateinit var root: File
    private val files = HashMap<String, ByteArray>()
    private lateinit var server: HttpServer

    @Before
    fun start() {
        root = Files.createTempDirectory("charts").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = files[ex.requestURI.path.removePrefix("/rel/")]
            if (body == null) ex.sendResponseHeaders(404, -1)
            else { ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) } }
            ex.close()
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        root.deleteRecursively()
    }

    private val knollTile = javaClass.classLoader!!.getResourceAsStream("knoll-z15.pbf.gz")!!.readBytes()

    /** An MBTiles file holding the Bay Bridge knoll tile (z15 x9431 y12521, TMS row 20246). */
    private fun mbtiles(f: File): File {
        DriverManager.getConnection("jdbc:sqlite:" + f.path).use { c ->
            c.createStatement().use { it.executeUpdate("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)") }
            c.prepareStatement("INSERT INTO tiles VALUES (15, 9431, 20246, ?)").use { it.setBytes(1, knollTile); it.executeUpdate() }
        }
        return f
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun readerReadsTilesByTmsRow() {
        JdbcTileReader(mbtiles(File(root, "c.mbtiles"))).use { r ->
            assertEquals(knollTile.size, r.tile(15, 9431, 20246)!!.size)
            assertNull(r.tile(15, 9431, 12521))
        }
    }

    @Test
    fun legThroughTheKnollIsFlaggedFromTheChartFile() {
        // The ChartDepthTest case, now read from an MBTiles file through sqlite-jdbc:
        // a 5 ft leg 4 m inside the west edge of the 0-6 ft knoll north of the Bay Bridge
        val f = mbtiles(File(root, "c.mbtiles"))
        val lon = -76.38632 + 4.0 / (111320.0 * cos(Math.toRadians(39.012)))
        val a = LatLon(39.0132, lon)
        val b = LatLon(39.0105, lon)
        val route = Route("t", listOf(a, b), listOf(Leg(Planner.distanceM(a, b), 180.0, 0.0, null, emptyList())), 5.0, 10.0, 20.0, emptyList())
        val chart = ChartDepthChart(f.path, doubleArrayOf(-77.8, 38.23, -73.4, 39.73), 6.6, 15) { JdbcTileReader(f) }
        val notes = ChartDepthRoute.annotate(route, listOf(chart)).legs.single().notes
        assertTrue(notes.toString(), notes.any { it.contains("may be under your 5 ft") })
        // Outside the chart's bounds nothing is read and the route is unchanged
        val away = ChartDepthChart(f.path, doubleArrayOf(-70.0, 30.0, -69.0, 31.0), 1.0, 15) { JdbcTileReader(f) }
        assertTrue(ChartDepthRoute.annotate(route, listOf(away)) === route)
    }

    private fun publish(chart: ByteArray, bounds: String = "[-77.8,38.23,-73.4,39.73]") {
        files["Ncds_04.mbtiles"] = chart
        files["catalog.json"] = """{"regions":[{"ncds":"04","name":"Region 04: Upper Chesapeake and Delaware Bay",
            "tiles":{"file":"Ncds_04.mbtiles","size":${chart.size},"sha256":"${sha(chart)}","bounds":$bounds,"minzoom":8,"maxzoom":15}}]}""".toByteArray()
    }

    @Test
    fun chartCacheDownloadsAndUpdatesByChecksum() {
        val cache = ChartCache(File(root, "charts"), "http://127.0.0.1:${server.address.port}/rel/")
        val v1 = mbtiles(File(root, "v1.mbtiles")).readBytes()
        publish(v1)
        val set = cache.sets(offline = false).single()
        assertEquals("Region 04", set.shortName)
        assertFalse(cache.current(set))
        cache.download(set)
        assertTrue(cache.current(set))
        // A newer build in the catalog: no longer current until downloaded again
        val v2 = v1 + ByteArray(16)
        publish(v2)
        val set2 = cache.sets(offline = false).single()
        assertFalse(cache.current(set2))
        assertTrue(cache.have(set2))
        cache.download(set2)
        assertTrue(cache.current(set2))
        // Offline: the saved catalog
        server.stop(0)
        assertEquals(set2.sha256, cache.sets(offline = false).single().sha256)
        assertEquals(set2.sha256, cache.sets(offline = true).single().sha256)
    }

    @Test
    fun badChecksumKeepsTheOldChart() {
        val cache = ChartCache(File(root, "charts"), "http://127.0.0.1:${server.address.port}/rel/")
        val v1 = mbtiles(File(root, "v1.mbtiles")).readBytes()
        publish(v1)
        cache.download(cache.sets(offline = false).single())
        publish(v1 + ByteArray(16))
        files["Ncds_04.mbtiles"] = v1 + ByteArray(8) // not what the catalog says
        val set = cache.sets(offline = false).single()
        val e = runCatching { cache.download(set) }.exceptionOrNull()
        assertEquals("download check failed, try again", e?.message)
        assertTrue(cache.have(set))
        assertEquals(v1.size.toLong(), cache.file(set).length())
    }
}
