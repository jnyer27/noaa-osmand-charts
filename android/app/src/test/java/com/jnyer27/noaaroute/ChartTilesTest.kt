package com.jnyer27.noaaroute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(7, features.size)
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
}
