package com.jnyer27.noaaroute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartTilesTest {
    private fun leg(nm: Double, shoalM: Double = 0.0, notes: List<String> = emptyList()) =
        Leg(nm * Planner.NM, 90.0, shoalM, null, notes)

    // Shallow water on leg 1, a bridge note that is not a check on leg 2, a check note on leg 3.
    private val route = Route("middle",
        listOf(LatLon(39.32, -76.40), LatLon(39.31, -76.38), LatLon(39.30, -76.36), LatLon(39.29, -76.34)),
        listOf(leg(0.4, shoalM = 80.0), leg(0.5, notes = listOf("span (clearance 24 ft)")),
            leg(0.4, notes = listOf("passes charted piles or snags: check"))),
        3.0, 10.0, 20.0, emptyList())

    @Suppress("UNCHECKED_CAST")
    private fun obj(v: Any?) = v as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun arr(v: Any?) = v as List<Any?>

    @Test
    fun routeGeoJsonFlagsTheSameLegsAsTheReport() {
        val features = arr(obj(MiniJson.parse(ChartTiles.routeGeoJson(route)))["features"])
        assertEquals(10, features.size) // 3 legs, 3 leg numbers, 4 points
        val first = obj(obj(features[0])["properties"])
        assertEquals(1.0, first["leg"] as Double, 0.0)
        assertTrue((first["note"] as String).contains("under 3 ft") || (first["note"] as String).isNotEmpty())
        val flagged = features.map { obj(obj(it)["properties"]) }
            .filter { it["kind"] == "leg" }
            .map { (it["flagged"] as Double).toInt() }
        assertEquals(listOf(1, 0, 1), flagged)
        val line = arr(obj(obj(features[0])["geometry"])["coordinates"])
        val start = arr(line[0])
        assertEquals(-76.4, start[0] as Double, 1e-6)
        assertEquals(39.32, start[1] as Double, 1e-6)
        assertFalse(Gpx.flagged(route.legs[1]))
        assertTrue(Gpx.flagged(route.legs[0]))
        val mid = ChartTiles.legAt(ChartTiles.routeGeoJson(route), 39.315, -76.39)
        assertTrue(mid, mid!!.startsWith("Leg 1."))
        assertNull(ChartTiles.legAt(ChartTiles.routeGeoJson(route), 40.0, -70.0))
    }

    @Test
    fun styleBakesInTheSafeDepthAndParses() {
        val style = ChartTiles.style(5.0, "mbtiles:///tmp/middle_river.mbtiles",
            "file:///tmp/glyphs/{fontstack}/{range}.pbf")
        val json = obj(MiniJson.parse(style))
        assertEquals(8.0, json["version"] as Double, 0.0)
        val enc = obj(obj(json["sources"])["enc"])
        assertEquals("mbtiles:///tmp/middle_river.mbtiles", enc["url"])
        assertTrue(style.contains("[\"<\",[\"get\",\"d2\"],5]"))
        assertTrue(style.contains("[\"<\", [\"get\", \"ft\"], 5]"))
        assertTrue(style.contains("NotoSansRegular"))
        assertTrue(style.contains("route-check"))
        assertTrue(style.contains("route-label"))
        assertTrue(style.contains(""""text-field": ["to-string", ["get", "leg"]]"""))
        // A green light is not the same dot as a red one. col comes from S-57 COLOUR.
        assertTrue(style.contains("""["match",["get","col"],"G","#1b8a34","R","#d01818"""))
        assertFalse(style.contains("#e91e63"))
        assertFalse(style.contains("__DEPTH__"))
        assertFalse(style.contains("__MBTILES__"))
        // A 3 ft boat shades a different line than this 5 ft example.
        val three = ChartTiles.style(3.0, "mbtiles:///x.mbtiles", "file:///g/{fontstack}/{range}.pbf")
        assertTrue(three.contains("[\"<\",[\"get\",\"d2\"],3]"))
        assertFalse(three.contains("[\"<\",[\"get\",\"d2\"],5]"))
    }

    private val catalog = """{"version":1,"regions":[
      {"ncds":"03","name":"Region 03: New York Harbor","files":[],
       "tiles":{"file":"Ncds_03.mbtiles","size":1000,"sha256":"aaa","bounds":[-76.68,39.73,-73.4,41.1],"minzoom":8,"maxzoom":15}},
      {"ncds":"04","name":"Region 04: Upper Chesapeake and Delaware Bay","files":[
         {"kind":"depth","file":"Ncds_04_depth.depth.obf","size":1,"sha256":"x"}],
       "tiles":{"file":"Ncds_04.mbtiles","size":32210944,"sha256":"dac4","bounds":[-77.8,38.23,-73.4,39.73],"minzoom":8,"maxzoom":15}},
      {"ncds":"05","name":"Region 05: Lower Chesapeake","files":[]}
    ]}"""

    @Test
    fun catalogTilesSkipRegionsWithoutATilesEntry() {
        val sets = ChartTiles.tileSets(catalog)
        assertEquals(listOf("03", "04"), sets.map { it.ncds })
        val r04 = sets[1]
        assertEquals("Ncds_04.mbtiles", r04.file)
        assertEquals(32210944L, r04.size)
        assertEquals("dac4", r04.sha256)
        assertEquals(8, r04.minZoom)
        assertEquals(15, r04.maxZoom)
        assertEquals("https://example/vector-maps-beta/Ncds_04.mbtiles",
            r04.url("https://example/vector-maps-beta/"))
        assertTrue(r04.contains(LatLon(39.32371, -76.39998))) // Maryland Marina
        assertFalse(r04.contains(LatLon(36.85, -76.29))) // Norfolk, region 05
    }

    @Test
    fun pickUsesTheRegionThatCoversTheView() {
        val sets = ChartTiles.tileSets(catalog)
        val marina = LatLon(39.32, -76.40)
        val annapolis = LatLon(38.97, -76.48)
        val norfolk = LatLon(36.85, -76.29)
        assertEquals("04", ChartTiles.pick(sets, listOf(marina, annapolis))!!.ncds)
        assertEquals("04", ChartTiles.pick(sets, ChartTiles.focus(emptyList(), null))!!.ncds)
        assertNull(ChartTiles.pick(sets, listOf(norfolk)))
        assertNull(ChartTiles.pick(sets, emptyList()))
        // Most of the view wins when two charts both touch the trip.
        val trip = listOf(marina, LatLon(40.5, -74.0), LatLon(40.6, -74.0))
        assertEquals("03", ChartTiles.pick(sets, trip)!!.ncds)
    }

    @Test
    fun offerIsTheCatalogChartThatIsNotDownloadedYet() {
        val sets = ChartTiles.tileSets(catalog)
        val r04 = sets.first { it.ncds == "04" }
        val home = ChartTiles.focus(emptyList(), null)
        assertEquals("04", ChartTiles.offer(sets, emptyList(), home)!!.ncds)
        assertNull(ChartTiles.offer(sets, listOf(r04), home))
        assertEquals("04", ChartTiles.offer(sets, emptyList(), listOf(LatLon(39.32, -76.40)))!!.ncds)
        assertEquals("03", ChartTiles.offer(sets, listOf(r04), listOf(LatLon(40.5, -74.0)))!!.ncds)
        assertNull(ChartTiles.offer(sets, emptyList(), listOf(LatLon(36.85, -76.29))))
    }

    @Test
    fun theCameraPicksTheChartAndATighterBoundsWins() {
        val route = listOf(LatLon(39.32, -76.40))
        val elsewhere = LatLon(40.7, -74.0)
        assertEquals(elsewhere, ChartTiles.focus(route, elsewhere).single())
        assertEquals(route, ChartTiles.focus(route, null))
        val wide = ChartTiles.TileSet("aa", "Region aa: wide", "a.mbtiles", 1, "a",
            doubleArrayOf(-78.0, 38.0, -74.0, 40.0), 8, 15)
        val tight = ChartTiles.TileSet("bb", "Region bb: tight", "b.mbtiles", 1, "b",
            doubleArrayOf(-76.5, 39.2, -76.3, 39.4), 8, 15)
        assertEquals("bb", ChartTiles.pick(listOf(wide, tight), listOf(LatLon(39.32, -76.40)))!!.ncds)
    }

    @Test
    fun depthLegendNamesTheThreeFills() {
        val text = ChartTiles.depthLegend(5.0)
        assertTrue(text, text.contains("Safe operating depth 5 ft"))
        assertTrue(text, text.contains("strong blue is charted entirely under it"))
        assertTrue(text, text.contains("lighter blue may be under your 5 ft"))
        assertTrue(text, text.contains("sounding drawn in red"))
        assertTrue(ChartTiles.spanText(-76.5, -76.3, 39.3).contains("km"))
        assertTrue(text, text.contains("pale blue is 5 ft or more"))
        assertFalse(text, text.contains("\u2014"))
    }

    @Test
    fun chartKeyHidesToOneLine() {
        val full = "Not for navigation. NOAA ENC of Region 04, depths in feet."
        val open = ChartTiles.keyLine(full, true)
        assertTrue(open, open.startsWith(full))
        assertTrue(open, open.contains("Tap to hide the key."))
        assertEquals(ChartTiles.KEY_BAR, ChartTiles.keyLine(full, false))
        assertTrue(ChartTiles.KEY_BAR.startsWith("Not for navigation."))
        assertTrue(ChartTiles.KEY_BAR.contains("Show key"))
        assertFalse(ChartTiles.keyLine(full, false).contains("Region 04"))
    }

    @Test
    fun followAndCompass() {
        assertEquals(0.0, ChartTiles.mapBearing(false, 90f, 3f, 40.0), 0.001)
        assertEquals(90.0, ChartTiles.mapBearing(true, 90f, 3f, 0.0), 0.001)
        assertEquals(350.0, ChartTiles.mapBearing(true, -10f, 1f, 0.0), 0.001)
        // Drifting or a fix with no bearing keeps the last heading.
        assertEquals(40.0, ChartTiles.mapBearing(true, 90f, 0.1f, 40.0), 0.001)
        assertEquals(40.0, ChartTiles.mapBearing(true, null, 5f, 40.0), 0.001)
        assertEquals("N 0°", ChartTiles.compassText(0.0))
        assertEquals("N 0°", ChartTiles.compassText(360.0))
        assertEquals("E 90°", ChartTiles.compassText(90.0))
        assertEquals("NE 45°", ChartTiles.compassText(45.0))
        assertEquals("N 350°", ChartTiles.compassText(-10.0))
        assertFalse(ChartTiles.stillFollowing(true, true))
        assertTrue(ChartTiles.stillFollowing(true, false))
        assertTrue(ChartTiles.orientSentence(false).startsWith("North is up."))
        assertTrue(ChartTiles.orientSentence(true).contains("turns with your course"))
        assertFalse(ChartTiles.orientSentence(true).startsWith("North is up."))
        val day = ChartTiles.chartChrome(false)
        val night = ChartTiles.chartChrome(true)
        assertTrue(day.readable())
        assertTrue(night.readable())
        assertTrue(ChartTiles.contrasts(day.text, day.keyBg))
        assertTrue(ChartTiles.contrasts(night.text, night.panel))
        assertTrue((day.text and 0xffffff) < 0x808080)
        assertTrue((night.text and 0xffffff) > 0x808080)
    }
}
