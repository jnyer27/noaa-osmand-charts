package com.jnyer27.noaaroute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GpxTest {
    private fun leg(nm: Double, shoalM: Double = 0.0, lowM: Double? = null, notes: List<String> = emptyList()) =
        Leg(nm * Planner.NM, 90.0, shoalM, lowM, notes)

    // Six legs: 0-6 ft on legs 1-2, a bridge (no check) on leg 3, a planner check on leg 5
    private val route = Route("t",
        List(7) { LatLon(39.0, -76.5 + it * 0.01) },
        listOf(leg(0.5, 95.0, 1.2), leg(0.5, 20.0), leg(0.5, notes = listOf("Bay Bridge span (clearance 186 ft)")),
            leg(0.5), leg(0.5, notes = listOf("passes charted piles or snags: check")), leg(0.5)),
        5.0, 10.0, 20.0, emptyList())

    @Test
    fun checkRuns() {
        assertEquals(listOf(0..1, 4..4), Gpx.checkRuns(route))
        assertFalse(Gpx.flagged(route.legs[2]))
    }

    @Test
    fun checkMarkersAndNotes() {
        val gpx = Gpx.write(route)
        // Markers where each run starts and ends, before the route
        val wpts = Regex("""<wpt lat="([-\d.]+)" lon="([-\d.]+)"><name>([^<]*)</name>""").findAll(gpx).toList()
        assertEquals(listOf("Check: legs 1-2 (1.00 nm)", "Check ends: legs 1-2", "Check: leg 5 (0.50 nm)", "Check ends: leg 5"), wpts.map { it.groupValues[3] })
        assertEquals(listOf(-76.5, -76.48, -76.46, -76.45), wpts.map { it.groupValues[2].toDouble() })
        assertTrue(gpx.contains("<desc>End of the stretch to check (legs 1-2).</desc><sym>Flag, Green</sym><type>Check</type>" +
            "<extensions><osmand:icon>special_symbol_check_mark</osmand:icon><osmand:background>circle</osmand:background>"))
        assertTrue(gpx.indexOf("<wpt") < gpx.indexOf("<rte>"))
        assertTrue(gpx, gpx.contains("leg 1: 95 m where charted depth may be under 5 ft (shallowest sounding nearby 3.9 ft, under your 5 ft); leg 2: 20 m where charted depth may be under 5 ft. Check the chart and the water."))
        assertTrue(gpx.contains("<sym>Danger Area</sym><type>Check</type><extensions><osmand:icon>special_symbol_exclamation_mark</osmand:icon>"))
        // Flagged route points are named, symbol and type after desc (schema order); others unchanged
        assertTrue(gpx, gpx.contains("<name>Start check</name><cmt>Next: 090 T, 0.50 nm, 95 m where charted depth may be under 5 ft"))
        assertTrue(gpx, gpx.contains("<name>WP04 check</name><cmt>Next: 090 T, 0.50 nm; passes charted piles or snags: check</cmt>"))
        assertTrue(gpx, gpx.contains("<name>WP02</name><desc>Next: 090 T, 0.50 nm; Bay Bridge span (clearance 186 ft)</desc></rtept>"))
        assertTrue(gpx.contains("<name>End</name></rtept>"))
        // Markers off: no waypoints or check names, the notes stay in the descriptions
        val plain = Gpx.write(route, checks = false)
        assertFalse(plain.contains("<wpt") || plain.contains(" check</name>") || plain.contains("<sym>"))
        assertTrue(plain, plain.contains("<name>WP04</name><desc>Next: 090 T, 0.50 nm; passes charted piles or snags: check</desc></rtept>"))
        // The report names the safe depth; a sounding at or above it is not called out
        val text = Gpx.report(route)
        assertTrue(text, text.contains("95 m where charted depth may be under 5 ft, shallowest sounding within 100 m 3.9 ft, under your 5 ft"))
        assertTrue(text, text.contains("20 m where charted depth may be under 5 ft, no soundings within 100 m"))
        val eight = Route("t", route.points.take(2), listOf(leg(0.5, 40.0, 2.0)), 8.0, null, 20.0, emptyList())
        assertTrue(Gpx.report(eight).contains("40 m where charted depth may be under 8 ft, shallowest sounding within 100 m 6.6 ft, under your 8 ft"))
        val deep = Route("t", route.points.take(2), listOf(leg(0.5, 40.0, 2.0)), 6.0, null, 20.0, emptyList())
        assertTrue(Gpx.report(deep).contains("40 m where charted depth may be under 6 ft, shallowest sounding within 100 m 6.6 ft\n"))
        // Written out for CI to validate against the GPX 1.1 schema
        File("build").mkdirs()
        File("build/gpx-sample.gpx").writeText(gpx)
    }
}
