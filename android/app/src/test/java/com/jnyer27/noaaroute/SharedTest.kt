package com.jnyer27.noaaroute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTest {
    private fun one(text: String): SharedPoint {
        val p = Shared.parseText(text)
        assertEquals("points in: $text", 1, p.size)
        return p[0]
    }

    @Test
    fun osmandShareLocation() {
        val p = one("Maryland Marina\ngeo:39.32371,-76.39998?z=16\nhttps://osmand.net/go?lat=39.32371&lon=-76.39998&z=16")
        assertEquals(LatLon(39.32371, -76.39998), p.pos)
        assertEquals("Maryland Marina", p.name)
    }

    @Test
    fun linksAndPairs() {
        assertEquals(LatLon(38.97731, -76.48559), one("geo:38.97731,-76.48559").pos)
        assertEquals(LatLon(39.13276, -76.24449), one("https://maps.google.com/?q=39.13276,-76.24449").pos)
        assertEquals(LatLon(39.13276, -76.24449), one("https://www.google.com/maps/@39.13276,-76.24449,15z").pos)
        assertEquals(LatLon(37.06055, -76.28595), one("Dock is at 37.06055° N, 76.28595° W thanks").pos)
        assertEquals(LatLon(39.25084, -76.48861), one("39.25084, -76.48861").pos)
        assertTrue(Shared.parseText("call me at 410-555-1212 tomorrow").isEmpty())
    }

    @Test
    fun gpxPoints() {
        val gpx = """<?xml version="1.0"?><gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
            <wpt lat="39.32371" lon="-76.39998"><name>Maryland Marina</name></wpt>
            <wpt lon="-76.24449" lat="39.13276"><name><![CDATA[Rock Hall & dock]]></name></wpt>
            <rte><name>r</name><rtept lat="39.0" lon="-76.4"/><rtept lat="38.9" lon="-76.45"><name>WP01</name></rtept></rte>
            <trk><name>Morning run</name><trkseg>
              <trkpt lat="39.30" lon="-76.40"><ele>0</ele></trkpt><trkpt lat="39.29" lon="-76.41"/><trkpt lat="39.28" lon="-76.42"/>
            </trkseg></trk></gpx>"""
        val g = Shared.parseGpx(gpx)!!
        assertEquals(listOf("Maryland Marina", "Rock Hall & dock"), g.waypoints.map { it.name })
        assertEquals(LatLon(39.13276, -76.24449), g.waypoints[1].pos)
        assertEquals(listOf("Route point 1", "WP01"), g.route.map { it.name })
        assertEquals(listOf("Morning run (start)", "Morning run (end)"), g.trackEnds.map { it.name })
        assertEquals(LatLon(39.28, -76.42), g.trackEnds[1].pos)
        assertNull(Shared.parseGpx("just text"))
    }
}
