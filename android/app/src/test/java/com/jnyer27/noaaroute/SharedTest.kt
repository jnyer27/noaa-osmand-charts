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
    fun googleLongLinks() {
        // A place: the !3d!4d pin, not the @ map center
        val place = "https://www.google.com/maps/place/Annapolis+Yacht+Club/@38.9772,-76.4870,16z/data=!3m1!4b1!4m6!3m5!1s0x89b7f6:0x1!8m2!3d38.97731!4d-76.48559!16s%2Fg%2F1"
        assertEquals(LatLon(38.97731, -76.48559), one(place).pos)
        // A dropped pin
        assertEquals(LatLon(39.32371, -76.39998), one("https://www.google.com/maps/search/39.32371,+-76.39998?entry=tts").pos)
        assertEquals(LatLon(39.32371, -76.39998), one("https://maps.google.com/maps?q=39.32371,+-76.39998").pos)
    }

    @Test
    fun googleShortLinks() {
        val text = "Annapolis Yacht Club\nhttps://maps.app.goo.gl/AbC123xyz"
        assertTrue(Shared.parseText(text).isEmpty())
        assertEquals(listOf("https://maps.app.goo.gl/AbC123xyz"), Shared.shortLinks(text))
        val long = "https://www.google.com/maps/place/Annapolis+Yacht+Club/@38.97,-76.48,16z/data=!3d38.97731!4d-76.48559"
        val hops = mapOf("https://maps.app.goo.gl/AbC123xyz" to "https://goo.gl/maps/x", "https://goo.gl/maps/x" to long)
        val resolved = Shared.resolveShort("https://maps.app.goo.gl/AbC123xyz") { hops[it] }
        assertEquals(long, resolved)
        val p = one(text + "\n" + resolved)
        assertEquals("Annapolis Yacht Club", p.name)
        assertEquals(LatLon(38.97731, -76.48559), p.pos)
        // Relative redirects, consent pages, and no redirect at all
        assertEquals("https://www.google.com/maps?q=39.1,-76.4",
            Shared.resolveShort("https://www.google.com/x") { if (it.endsWith("/x")) "/maps?q=39.1,-76.4" else null })
        assertEquals("https://www.google.com/maps/search/39.1,-76.4",
            Shared.resolveShort("https://maps.app.goo.gl/c") { "https://consent.google.com/ml?continue=https://www.google.com/maps/search/39.1%2C-76.4&gl=US" })
        assertNull(Shared.resolveShort("https://maps.app.goo.gl/none") { null })
        // Never followed off Google, or over plain http
        assertNull(Shared.resolveShort("https://maps.app.goo.gl/evil") { "https://example.com/maps/@39.1,-76.4" })
        assertNull(Shared.resolveShort("http://maps.app.goo.gl/a") { "https://www.google.com/maps/@39.1,-76.4" })
        // A dropped pin's label is not a name
        assertEquals("39.32371, -76.39998".let { Shared.parseText(it)[0].name },
            one("Dropped pin\nhttps://www.google.com/maps/search/39.32371,+-76.39998").name)
        // A place link without coordinates gives its name
        assertEquals("West River Marina", Shared.placeName("https://www.google.com/maps?q=West+River+Marina&ftid=0x89b7:0x2"))
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
