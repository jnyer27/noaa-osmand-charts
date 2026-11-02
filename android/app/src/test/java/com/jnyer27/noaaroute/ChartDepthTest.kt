package com.jnyer27.noaaroute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * Chart-area crossings, including the 0-6 ft knoll a 5 ft route clipped on the
 * west edge north of the Bay Bridge (US5MD1ND, 39.012 N, 76.386 W).
 */
class ChartDepthTest {
    private val knoll = listOf(
        LatLon(39.01114, -76.38597), LatLon(39.01115, -76.38602), LatLon(39.01119, -76.38610),
        LatLon(39.01126, -76.38620), LatLon(39.01136, -76.38626), LatLon(39.01145, -76.38629),
        LatLon(39.01154, -76.38632), LatLon(39.01190, -76.38632), LatLon(39.01237, -76.38632),
        LatLon(39.01242, -76.38631), LatLon(39.01254, -76.38623), LatLon(39.01262, -76.38613),
        LatLon(39.01268, -76.38606), LatLon(39.01271, -76.38597), LatLon(39.01272, -76.38590),
        LatLon(39.01273, -76.38580), LatLon(39.01270, -76.38569), LatLon(39.01264, -76.38554),
        LatLon(39.01259, -76.38548), LatLon(39.01253, -76.38543), LatLon(39.01235, -76.38536),
        LatLon(39.01220, -76.38531), LatLon(39.01209, -76.38530), LatLon(39.01200, -76.38533),
        LatLon(39.01180, -76.38544), LatLon(39.01143, -76.38564), LatLon(39.01124, -76.38576),
        LatLon(39.01119, -76.38580), LatLon(39.01117, -76.38585), LatLon(39.01115, -76.38592),
        LatLon(39.01114, -76.38597),
    )

    private fun cover(areas: List<ChartDepth.Area>) =
        listOf(ChartDepth.Cover(doubleArrayOf(-77.0, 38.0, -76.0, 40.0), 1.0, areas))

    private fun meters(note: String): Double = note.substringAfter("crosses ").substringBefore(" m").toDouble()

    @Test
    fun westEdgeOfTheBayBridgeKnollIsAStraddleNotEntirelyUnder() {
        // 4 m inside the west edge of the 0-6 ft knoll. The 20 m grid's cell centers
        // there are the surrounding 6-12 ft water, so the planner's own shallow note
        // stays quiet. The chart polygon is still crossed for about 120 m.
        val lon = -76.38632 + 4.0 / (111320.0 * cos(Math.toRadians(39.012)))
        val a = LatLon(39.0132, lon)
        val b = LatLon(39.0105, lon)
        val notes = ChartDepth.notes(a, b, 5.0, cover(listOf(ChartDepth.Area(listOf(knoll), 0, 6))))
        assertEquals(1, notes.size)
        assertTrue(notes[0], notes[0].contains("straddles your 5 ft: check"))
        assertFalse(notes[0], notes[0].contains("entirely under"))
        val crossed = meters(notes[0])
        assertTrue("crossed %.0f m".format(crossed), crossed in 80.0..160.0)
        assertTrue(Gpx.flagged(Leg(Planner.distanceM(a, b), 180.0, 0.0, null, notes)))
    }

    @Test
    fun anAreaEntirelyUnderTheSafeDepthIsNamedSeparately() {
        val square = listOf(
            LatLon(39.00, -76.40), LatLon(39.00, -76.39), LatLon(39.01, -76.39),
            LatLon(39.01, -76.40), LatLon(39.00, -76.40),
        )
        val a = LatLon(39.005, -76.405)
        val b = LatLon(39.005, -76.385)
        val notes = ChartDepth.notes(a, b, 5.0, cover(listOf(
            ChartDepth.Area(listOf(square), 0, 3),
            ChartDepth.Area(listOf(knoll), 0, 6),
        )))
        assertTrue(notes.toString(), notes.any { it.contains("entirely under your 5 ft: check") })
        assertTrue(notes.first().startsWith("crosses"))
        assertTrue(notes.first().contains("entirely under"))
    }

    @Test
    fun waterAtOrOverTheSafeDepthAndAnEmptyChartAddNothing() {
        val deep = listOf(
            LatLon(39.00, -76.40), LatLon(39.00, -76.38), LatLon(39.02, -76.38),
            LatLon(39.02, -76.40), LatLon(39.00, -76.40),
        )
        val a = LatLon(39.01, -76.395)
        val b = LatLon(39.01, -76.385)
        assertTrue(ChartDepth.notes(a, b, 5.0, cover(listOf(ChartDepth.Area(listOf(deep), 6, 12)))).isEmpty())
        assertTrue(ChartDepth.notes(a, b, 5.0, emptyList()).isEmpty())
        val route = Route("t", listOf(a, b), listOf(Leg(100.0, 90.0, 0.0, null, emptyList())), 5.0, null, 20.0, emptyList())
        assertTrue(ChartDepth.annotate(route, emptyList()).legs[0].notes.isEmpty())
    }

    @Test
    fun printedFeetMatchTheChartLabels() {
        assertEquals(5, Planner.printedFeet(1.5))
        assertEquals(5, Planner.printedFeet(1.4)) // 4.6 ft, still the chart's 5
        assertEquals(4, Planner.printedFeet(1.3)) // 4.3 ft
        assertEquals(4, Planner.printedFeet(1.2)) // 3.9 ft
        assertEquals(6, Planner.printedFeet(1.8))
        assertEquals(0, Planner.printedFeet(0.1))
        // Equal to the safe depth is passable, whether stored as 1.5 m or as 5 * 0.3048 m.
        assertFalse(Planner.soundingShallow(1.5, 5.0))
        assertFalse(Planner.soundingShallow(1.4, 5.0))
        assertFalse(Planner.soundingShallow(5.0 * Pack.FT, 5.0))
        assertFalse(Planner.soundingShallow(5.0 * Pack.FT, Planner.depthFeet(5.0 * Pack.FT)))
        // One chart label under the safe depth is a hazard. No 5 cm slack: 1.3 m is
        // only 0.22 m under 5 ft, inside that old band, and it still blocks.
        assertTrue(Planner.soundingShallow(1.3, 5.0))
        assertTrue(Planner.soundingShallow(1.2, 5.0))
        assertFalse(Planner.soundingShallow(1.8, 5.0))
        assertFalse(Planner.soundingShallow(1.5, 4.0))
        // 1.2 m prints as 4. At 4 ft that label is not shallower. At 3 ft it is over.
        assertFalse(Planner.soundingShallow(1.2, 4.0))
        assertFalse(Planner.soundingShallow(1.2, Planner.depthFeet(4.0 * Pack.FT)))
        assertFalse(Planner.soundingShallow(1.2, 3.0))
        // 0.9 m prints as 3, so it is a hazard at 4 ft and not at 3 ft.
        assertEquals(3, Planner.printedFeet(0.9))
        assertTrue(Planner.soundingShallow(0.9, 4.0))
        assertFalse(Planner.soundingShallow(0.9, 3.0))
        assertTrue(Planner.soundingShallow(0.7, 3.0)) // 2.3 ft, chart label 2
    }

    @Test
    fun theKnollTileDecodesTheZeroToSixAreaAroundTheFiveFootSounding() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("knoll-z15.pbf.gz")!!.readBytes()
        val areas = ChartDepth.areasInTile(bytes, 15, 9431, 12521)
        val shoal = areas.filter { it.d1 == 0 && it.d2 == 6 }
        assertTrue("0-6 areas ${areas.map { it.d1 to it.d2 }}", shoal.isNotEmpty())
        val sounding = LatLon(39.01210, -76.38587)
        assertTrue(shoal.any { it.contains(sounding) })
        val lon = -76.38632 + 4.0 / (111320.0 * cos(Math.toRadians(39.012)))
        val notes = ChartDepth.notes(LatLon(39.0132, lon), LatLon(39.0105, lon), 5.0,
            listOf(ChartDepth.Cover(doubleArrayOf(-77.8, 38.23, -73.4, 39.73), 4.0, areas)))
        assertTrue(notes.toString(), notes.any { it.contains("straddles your 5 ft") && meters(it) >= 50.0 })
    }

}
