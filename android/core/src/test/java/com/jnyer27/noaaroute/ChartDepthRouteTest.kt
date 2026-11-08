package com.jnyer27.noaaroute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/** ChartDepthRoute on the Bay Bridge knoll tile, read through a ChartTileReader. */
class ChartDepthRouteTest {
    private val knollTile = javaClass.classLoader!!.getResourceAsStream("knoll-z15.pbf.gz")!!.readBytes()
    private var opened = 0

    /** A chart holding only the knoll tile (z15 x9431 y12521, TMS row 20246). */
    private val chart = ChartDepthChart("knoll", doubleArrayOf(-77.8, 38.23, -73.4, 39.73), 6.6, 15) {
        opened++
        object : ChartTileReader {
            override fun tile(zoom: Int, column: Int, row: Int) =
                if (zoom == 15 && column == 9431 && row == 20246) knollTile else null
            override fun close() {}
        }
    }

    /** A 5 ft leg 4 m inside the west edge of the 0-6 ft knoll (ChartDepthTest's case). */
    private fun route(): Route {
        val lon = -76.38632 + 4.0 / (111320.0 * cos(Math.toRadians(39.012)))
        val a = LatLon(39.0132, lon)
        val b = LatLon(39.0105, lon)
        return Route("t", listOf(a, b), listOf(Leg(Planner.distanceM(a, b), 180.0, 0.0, null, listOf("planner note"))),
            5.0, 10.0, 20.0, emptyList())
    }

    @Test
    fun flagsTheLegFromTheChartTile() {
        val notes = ChartDepthRoute.annotate(route(), listOf(chart)).legs.single().notes
        assertEquals("planner note", notes.first())
        assertTrue(notes.toString(), notes.any { it.contains("may be under your 5 ft") })
        assertEquals(1, opened)
    }

    @Test
    fun annotatingTwiceAddsEachNoteOnce() {
        // The app and the CLI annotate the route, then the adjusted route from Try to avoid
        // checks, which keeps the legs it does not replan with their notes
        val once = ChartDepthRoute.annotate(route(), listOf(chart))
        val twice = ChartDepthRoute.annotate(once, listOf(chart))
        assertEquals(once.legs.single().notes, twice.legs.single().notes)
    }

    @Test
    fun chartOutsideTheRouteIsNotOpened() {
        val away = ChartDepthChart("away", doubleArrayOf(-70.0, 30.0, -69.0, 31.0), 1.0, 15) { opened++; error("not opened") }
        val r = route()
        assertTrue(ChartDepthRoute.annotate(r, listOf(away)) === r)
        assertEquals(0, opened)
    }
}
