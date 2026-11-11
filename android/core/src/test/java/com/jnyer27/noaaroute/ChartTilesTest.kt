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
        assertEquals(14, features.size) // 3 legs, 3 leg numbers, 2 check pins per check run, 4 points
        // The GPX check markers: legs 1 and 3 are separate runs, each with a start and an end pin
        val pins = features.map { obj(it) }.filter { obj(it["properties"])["kind"].toString().startsWith("check") }
        assertEquals(listOf("checkstart", "checkend", "checkstart", "checkend"), pins.map { obj(it["properties"])["kind"] })
        assertEquals(listOf("1", "1", "3", "3"), pins.map { obj(it["properties"])["legs"] })
        val lastEnd = arr(obj(pins[3]["geometry"])["coordinates"])
        assertEquals(-76.34, lastEnd[0] as Double, 1e-6) // the end of leg 3
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
    fun aTappedLegSaysWhetherItIsACheckAndSplitsItsNotes() {
        val json = ChartTiles.routeGeoJson(route)
        val bridge = ChartTiles.legPickAt(json, 39.305, -76.37)!!
        assertEquals(2, bridge.leg)
        assertFalse(bridge.flagged)
        val piles = ChartTiles.legPickAt(json, 39.295, -76.35)!!
        assertEquals(3, piles.leg)
        assertTrue(piles.flagged)
        // The leg's shallow water and nearest sounding, for the tide graph
        assertFalse(piles.shoal)
        assertNull(piles.lowFt)
        val sounded = Route("s", route.points.take(2), listOf(Leg(0.4 * Planner.NM, 90.0, 80.0, 0.55, emptyList())), 3.0, 10.0, 20.0, emptyList())
        val shallow = ChartTiles.legPickAt(ChartTiles.routeGeoJson(sounded), 39.315, -76.39)!!
        assertTrue(shallow.shoal && shallow.flagged)
        assertEquals(1.8, shallow.lowFt!!, 1e-9) // 0.55 m, printed 1.8 ft in the report
        // A fingertip at high zoom is a few meters: a tap 500 m off the leg is not on it
        assertNull(ChartTiles.legPickAt(json, 39.31, -76.37, maxM = 20.0))

        val (head, lines) = ChartTiles.legDetail("Next: 274 T, 0.17 nm, 257 m where charted depth may be under 2 ft (shallowest sounding nearby 1.0 ft, under your 2 ft); passes 5-30 m from a charted obstruction (depth unknown or 0 ft): check; bridge span (vertical clearance 65 ft)")
        assertEquals("274 T, 0.17 nm", head)
        assertEquals(3, lines.size)
        assertEquals("257 m where charted depth may be under 2 ft (shallowest sounding nearby 1.0 ft, under your 2 ft)", lines[0])
        assertTrue(lines[1].endsWith(": check"))
        val (h2, l2) = ChartTiles.legDetail("Next: 081 T, 1.20 nm")
        assertEquals("081 T, 1.20 nm", h2)
        assertTrue(l2.isEmpty())
    }

    @Test
    fun aViaFromTheChartGoesWhereItAddsTheLeastDistance() {
        val start = LatLon(39.0, -76.0)
        val via1 = LatLon(39.1, -76.0)
        val dest = LatLon(39.3, -76.0)
        // Between start and the first via
        assertEquals(1, ChartTiles.viaIndex(listOf(start, via1, dest), LatLon(39.05, -76.01)))
        // Between the first via and the destination
        assertEquals(2, ChartTiles.viaIndex(listOf(start, via1, dest), LatLon(39.2, -76.01)))
        // No vias yet: between start and destination
        assertEquals(1, ChartTiles.viaIndex(listOf(start, dest), LatLon(39.15, -76.2)))
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

    @Test
    fun restrictedAreasFromThePacksAreDrawnOnceEach() {
        val box = doubleArrayOf(-76.5, 37.0, -76.0, 37.5)
        val ring = listOf(listOf(listOf(-76.5, 37.0), listOf(-76.0, 37.0), listOf(-76.0, 37.5), listOf(-76.5, 37.5), listOf(-76.5, 37.0)))
        fun resare(name: String, rules: List<String>, cfr: String?, type: String = "Polygon", coords: Any? = ring) =
            Note("resare", 4, box, type, coords, name, rules, cfr, null, null, null)
        val notes = listOf(
            resare("Naval Research Laboratory Restricted Area C", listOf("entry restricted"), "33 CFR 334.170"),
            resare("Naval Research Laboratory Restricted Area C", listOf("entry restricted"), "33 CFR 334.170"), // the other pack at a seam
            resare("restricted area", listOf("anchoring prohibited", "fishing prohibited"), "33 CFR 334.360"),
            resare("Cove Point", listOf("security zone, entry may be prohibited: check"), null, "Bbox", null),
            resare("a line", listOf("entry restricted"), null, "LineString", listOf(listOf(-76.5, 37.0), listOf(-76.0, 37.0))),
            Note("cable", 4, box, "Bbox", null, "overhead cable", emptyList(), null, 16.7, null, null),
        )
        val features = arr(obj(MiniJson.parse(ChartTiles.restrictedGeoJson(notes)))["features"]).map { obj(it) }
        assertEquals(3, features.size)
        val props = features.map { obj(it["properties"]) }
        assertEquals("Naval Research Laboratory Restricted Area C (33 CFR 334.170)", props[0]["label"])
        assertEquals(1.0, props[0]["limit"] as Double, 0.0)
        assertEquals(0.0, props[1]["limit"] as Double, 0.0)
        assertEquals(1.0, props[2]["limit"] as Double, 0.0) // security zone: a check
        val bbox = obj(features[2]["geometry"])
        assertEquals("Polygon", bbox["type"])
        assertEquals(5, arr(arr(bbox["coordinates"])[0]).size)
        val style = ChartTiles.style(3.0, "mbtiles:///x.mbtiles", "file:///g/{fontstack}/{range}.pbf")
        val ids = arr(obj(MiniJson.parse(style))["layers"]).map { obj(it)["id"] as String }
        assertTrue(ids.indexOf("restricted-limit") in 0 until ids.indexOf("route-casing"))
    }

    @Test
    fun cablesAreDrawnAndATapNamesTheAreaOrCable() {
        val box = doubleArrayOf(-76.5, 37.0, -76.0, 37.5)
        val ring = listOf(listOf(listOf(-76.5, 37.0), listOf(-76.0, 37.0), listOf(-76.0, 37.5), listOf(-76.5, 37.5), listOf(-76.5, 37.0)))
        val line = listOf(listOf(-76.40, 37.20), listOf(-76.38, 37.20))
        val cbox = doubleArrayOf(-76.40, 37.20, -76.38, 37.20)
        val notes = listOf(
            Note("resare", 4, box, "Polygon", ring, "Chesapeake Bay", listOf("entry restricted"), "33 CFR 165.501", null, null, null),
            Note("cable", 4, cbox, "LineString", line, "overhead cable", emptyList(), null, 16.7, null, null),
            Note("cable", 4, cbox, "LineString", line, "overhead cable", emptyList(), null, 2.0, null, null),
            Note("cable", 4, cbox, "Bbox", null, "overhead cable", emptyList(), null, null, null, null),
        )
        val cables = arr(obj(MiniJson.parse(ChartTiles.cablesGeoJson(notes, 10.0)))["features"]).map { obj(obj(it)["properties"]) }
        assertEquals(2, cables.size) // no line kept: not drawn
        assertEquals("cable 55 ft", cables[0]["label"])
        assertEquals(0.0, cables[0]["low"] as Double, 0.0)
        assertEquals(1.0, cables[1]["low"] as Double, 0.0) // 7 ft under a 10 ft air draft
        val noAir = arr(obj(MiniJson.parse(ChartTiles.cablesGeoJson(notes, null)))["features"]).map { obj(obj(it)["properties"]) }
        assertTrue(noAir.all { (it["low"] as Double) == 1.0 })
        // Inside the area and by the cable: both. Inside only, away from the cable: the area.
        val both = ChartTiles.notesAt(notes, 37.2001, -76.39, 30.0, 10.0)
        assertTrue(both.toString(), both.first().startsWith("Chesapeake Bay: regulated navigation area"))
        assertTrue(both.toString(), both.any { it.contains("cable") })
        assertEquals(1, ChartTiles.notesAt(notes, 37.10, -76.30, 30.0, 10.0).size)
        assertTrue(ChartTiles.notesAt(notes, 38.0, -75.0, 30.0, 10.0).isEmpty())
        val style = ChartTiles.style(3.0, "mbtiles:///x.mbtiles", "file:///g/{fontstack}/{range}.pbf")
        val ids = arr(obj(MiniJson.parse(style))["layers"]).map { obj(it)["id"] as String }
        assertTrue(ids.indexOf("cable") in 0 until ids.indexOf("route-casing"))
        assertTrue(ids.indexOf("check-start") > ids.indexOf("route-check"))
    }

    @Test
    fun theBaseMapIsItsOwnCatalogEntryAndDrawsUnderTheChartMarks() {
        val catalog = """{"version":1,"regions":[{"ncds":"04","name":"Region 04: Upper Chesapeake",
            "tiles":{"file":"Ncds_04.mbtiles","size":63000000,"sha256":"aa","bounds":[-77.8,38.23,-73.4,39.73],"minzoom":8,"maxzoom":15},
            "basemap":{"file":"Ncds_04_basemap.pmtiles","size":40000000,"sha256":"bb","bounds":[-77.8,38.23,-73.4,39.73],"minzoom":0,"maxzoom":14}},
            {"ncds":"05","name":"Region 05","tiles":{"file":"Ncds_05.mbtiles","size":1,"sha256":"cc","bounds":[-77.8,35.5,-74.6,38.23],"minzoom":8,"maxzoom":15}}]}"""
        assertEquals(listOf("Ncds_04.mbtiles", "Ncds_05.mbtiles"), ChartTiles.tileSets(catalog).map { it.file })
        val base = ChartTiles.tileSets(catalog, "basemap")
        assertEquals(listOf("Ncds_04_basemap.pmtiles"), base.map { it.file })
        assertEquals(14, base[0].maxZoom)
        val style = ChartTiles.style(3.0, listOf("mbtiles:///a/Ncds_04.mbtiles", "mbtiles:///a/Ncds_05.mbtiles"),
            "file:///g/{fontstack}/{range}.pbf", listOf("pmtiles://file:///b/Ncds_04_basemap.pmtiles", "pmtiles://file:///b/Ncds_05_basemap.pmtiles"))
        val json = obj(MiniJson.parse(style))
        val sources = obj(json["sources"])
        assertEquals("pmtiles://file:///b/Ncds_04_basemap.pmtiles", obj(sources["osm0"])["url"])
        assertEquals("pmtiles://file:///b/Ncds_05_basemap.pmtiles", obj(sources["osm1"])["url"])
        val layers = arr(json["layers"]).map { obj(it) }
        val ids = layers.map { it["id"] as String }
        assertEquals("ids unique", ids.size, ids.toSet().size)
        assertEquals("osm1", layers.first { it["id"] == "osm-highway~1" }["source"])
        // County names on the county lines, one name each side (tools/county_labels.py)
        val county = layers.first { it["id"] == "osm-county-label~1" }
        assertEquals("counties", county["source-layer"])
        assertEquals(true, obj(county["layout"])["text-allow-overlap"])
        // Closer labels as you zoom in, so a line keeps a name on screen to the map's zoom 18
        // and drawn even where they overlap the chart's soundings (which stay on top)
        assertEquals(listOf(11.0, 12.3, 13.3, 14.3, 15.3, 16.3, 17.3), (0..6).map { r ->
            (layers.first { it["id"] == (if (r == 0) "osm-county-label~0" else "osm-county-label-$r~0") }["minzoom"] as Number).toDouble() })
        assertEquals(listOf("concat", listOf("get", "a"), "\n", listOf("get", "b")), obj(county["layout"])["text-field"])
        assertTrue(ids.indexOf("osm-county-label-6~0") > ids.indexOf("osm-county-line~1"))
        // County and state lines come from the same Census lines as the labels, not OpenStreetMap's
        assertEquals("counties", layers.first { it["id"] == "osm-county-line~0" }["source-layer"])
        assertEquals(listOf("==", listOf("get", "kind"), "country"), layers.first { it["id"] == "osm-boundary~0" }["filter"])
        // Above every region's land fill, under contours, soundings, marks and the route
        assertTrue(ids.indexOf("lndare~1") < ids.indexOf("osm-boundary~0"))
        assertTrue(ids.indexOf("osm-place~1") < ids.indexOf("depcnt"))
        assertTrue(ids.indexOf("osm-place~1") < ids.indexOf("soundg"))
        assertFalse(style.contains("__"))
        // Without a base map the style has no OpenStreetMap layer
        assertFalse(ChartTiles.style(3.0, "mbtiles:///x.mbtiles", "file:///g/{fontstack}/{range}.pbf").contains("osm-"))
    }

    @Test
    fun coarserBandsAreEnlargedFromACopyOfTheFileThatStopsAtTheirZoom() {
        val style = ChartTiles.style(3.0, listOf("mbtiles:///a/Ncds_04.mbtiles", "mbtiles:///a/Ncds_16.mbtiles"),
            "file:///g/{fontstack}/{range}.pbf", maxZooms = listOf(15, 16))
        val json = obj(MiniJson.parse(style))
        val sources = obj(json["sources"])
        assertEquals("mbtiles:///a/Ncds_04.mbtiles", obj(sources["enc"])["url"])
        val z13 = obj(sources["enc-z13"])
        assertEquals(listOf("mbtiles:///a/Ncds_04.mbtiles?file={x}/{y}/{z}.pbf"), z13["tiles"])
        assertEquals(13, (z13["maxzoom"] as Number).toInt())
        // Copies only below the file's own max zoom: 04 stops at 15, 16 has berthing charts
        assertTrue("enc-z11" in sources && "enc1-z11" in sources && "enc1-z15" in sources)
        assertFalse("enc-z15" in sources)
        val layers = arr(json["layers"]).map { obj(it) }
        val ids = layers.map { it["id"] as String }
        assertEquals("ids unique", ids.size, ids.toSet().size)
        val lo = layers.first { it["id"] == "depare@13" }
        assertEquals("enc-z13", lo["source"])
        assertEquals(14, (lo["minzoom"] as Number).toInt())
        assertEquals(listOf("==", listOf("get", "z"), 13), (lo["filter"] as List<*>).map { v -> if (v is Number) v.toInt() else v }
            .map { v -> if (v is List<*>) v.map { if (it is Number) it.toInt() else it } else v })
        // A layer that starts higher keeps its own start; an enlarged copy draws under the file's own
        assertEquals(15, (layers.first { it["id"] == "buoy-name@11" }["minzoom"] as Number).toInt())
        assertEquals(14, (layers.first { it["id"] == "soundg@13" }["minzoom"] as Number).toInt())
        assertEquals("enc1-z15", layers.first { it["id"] == "soundg~1@15" }["source"])
        assertTrue(ids.indexOf("depare@13") < ids.indexOf("depare"))
        assertTrue(ids.indexOf("depare~1") < ids.indexOf("soundg@11"))
        assertFalse(style.contains("__"))
        // Without max zooms (the Middle River chart) there are no copies
        assertFalse(ChartTiles.style(3.0, "mbtiles:///x.mbtiles", "file:///g/{fontstack}/{range}.pbf").contains("-z1"))
    }

    @Test
    fun severalRegionChartsAreOneStyleWithASourceEach() {
        val style = ChartTiles.style(3.0, listOf("mbtiles:///a/Ncds_04.mbtiles", "mbtiles:///a/Ncds_05.mbtiles"),
            "file:///g/{fontstack}/{range}.pbf")
        val json = obj(MiniJson.parse(style))
        val sources = obj(json["sources"])
        assertEquals("mbtiles:///a/Ncds_04.mbtiles", obj(sources["enc"])["url"])
        assertEquals("mbtiles:///a/Ncds_05.mbtiles", obj(sources["enc1"])["url"])
        val layers = arr(json["layers"]).map { obj(it) }
        val ids = layers.map { it["id"] as String }
        assertEquals("ids unique", ids.size, ids.toSet().size)
        // Each chart layer once per region, the copies together, in the one-region order
        val one = arr(obj(MiniJson.parse(ChartTiles.style(3.0, "mbtiles:///x.mbtiles", "file:///g/{fontstack}/{range}.pbf")))["layers"])
            .map { obj(it)["id"] as String }
        val expected = one.flatMap { id ->
            val l = arr(obj(MiniJson.parse(ChartTiles.style(3.0, "mbtiles:///x.mbtiles", "file:///g/{fontstack}/{range}.pbf")))["layers"])
                .map { obj(it) }.first { it["id"] == id }
            if (l["source"] == "enc") listOf(id, "$id~1") else listOf(id)
        }
        assertEquals(expected, ids)
        assertEquals("enc1", layers.first { it["id"] == "soundg~1" }["source"])
        assertEquals("enc", layers.first { it["id"] == "soundg" }["source"])
        // Shading of both regions comes before either region's soundings
        assertTrue(ids.indexOf("depare~1") < ids.indexOf("soundg"))
        assertTrue(style.contains("[\"<\",[\"get\",\"d2\"],3]"))
        assertFalse(style.contains("__"))
        assertEquals(1, layers.count { it["id"] == "route-ok" })
    }

    @Test
    fun theChartTitleNamesEveryRegionDrawn() {
        val sets = ChartTiles.tileSets(catalog)
        assertEquals("Region 04", ChartTiles.regionsHeading(sets.drop(1)))
        assertEquals("Regions 03 and 04", ChartTiles.regionsHeading(sets))
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
    fun theScaleBarIsARoundDistanceThatFits() {
        // 20 m per dp: 110 dp is 2200 m (1.19 nm), so 1 nm in 92.6 dp
        val mile = ChartTiles.scaleBar(20.0)!!
        assertEquals("1 nm", mile.label)
        assertEquals(1852.0 / 20.0, mile.dp, 1e-9)
        assertEquals("5 nm", ChartTiles.scaleBar(100.0)!!.label)
        assertEquals("20 nm", ChartTiles.scaleBar(400.0)!!.label)
        assertEquals("0.5 nm", ChartTiles.scaleBar(9.0)!!.label)
        assertEquals("0.2 nm", ChartTiles.scaleBar(4.0)!!.label)
        // Under a tenth of a mile: feet
        assertEquals("500 ft", ChartTiles.scaleBar(1.5)!!.label)
        assertEquals("50 ft", ChartTiles.scaleBar(0.2)!!.label)
        for (m in doubleArrayOf(0.05, 0.3, 1.0, 3.0, 7.0, 15.0, 60.0, 900.0)) {
            val s = ChartTiles.scaleBar(m)!!
            assertTrue("$m ${s.label} ${s.dp}", s.dp <= 110.0 + 1e-9 && s.dp >= 110.0 / 5 - 1e-9)
        }
        assertNull(ChartTiles.scaleBar(0.0))
        assertNull(ChartTiles.scaleBar(Double.NaN))
    }

    @Test
    fun headingLineRunsAlongTheCourseOnlyWhileFollowingAndMoving() {
        assertNull(ChartTiles.headingCourse(false, 90f, 3f))
        assertNull(ChartTiles.headingCourse(true, 90f, 0.2f))
        assertNull(ChartTiles.headingCourse(true, null, 3f))
        assertEquals(350.0, ChartTiles.headingCourse(true, -10f, 3f)!!, 1e-9)
        // 1 nm due east of Maryland Marina: same latitude, 1852 m of longitude
        val e = ChartTiles.rhumbEnd(39.32371, -76.39998, 90.0, 1852.0)
        assertEquals(39.32371, e.lat, 1e-9)
        assertEquals(1852.0, (e.lon + 76.39998) * 111194.93 * Math.cos(Math.toRadians(39.32371)), 1.0)
        val n = ChartTiles.rhumbEnd(39.0, -76.0, 0.0, 1852.0)
        assertEquals(-76.0, n.lon, 1e-9)
        assertEquals(1.0 / 60, n.lat - 39.0, 1e-4)
        // NE: the line keeps 45 degrees on the chart (Mercator), so equal stretched offsets
        val ne = ChartTiles.rhumbEnd(39.0, -76.0, 45.0, 20000.0)
        fun y(lat: Double) = Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2))
        assertEquals(Math.toRadians(ne.lon + 76.0), y(ne.lat) - y(39.0), 1e-9)
        val json = obj(MiniJson.parse(ChartTiles.gpsGeoJson(39.0, -76.0, 45.0, 20000.0)))
        val kinds = arr(json["features"]).map { obj(obj(it)["geometry"])["type"] }
        assertEquals(listOf("LineString", "Point"), kinds)
        assertEquals(listOf("Point"), arr(obj(MiniJson.parse(ChartTiles.gpsGeoJson(39.0, -76.0)))["features"]).map { obj(obj(it)["geometry"])["type"] })
    }

    @Test
    fun speedInMph() {
        assertEquals("-- mph", ChartTiles.speedText(null))
        assertEquals("-- mph", ChartTiles.speedText(Float.NaN))
        assertEquals("0.0 mph", ChartTiles.speedText(0f))
        // 6 knots = 3.0867 m/s = 6.9 mph
        assertEquals("6.9 mph", ChartTiles.speedText(3.0867f))
        assertEquals("22.4 mph", ChartTiles.speedText(10f))
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
