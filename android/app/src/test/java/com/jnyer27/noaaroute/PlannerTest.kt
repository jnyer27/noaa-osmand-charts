package com.jnyer27.noaaroute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import kotlin.math.max

class PlannerTest {
    private val res = 20.0
    private val dlat = res / Pack.M_PER_DEG
    private val dlon = res / (Pack.M_PER_DEG * Math.cos(Math.toRadians(38.0))) // global grid
    private val gtx0 = Math.floor((-76.5 + 180) / (256 * dlon)).toInt()
    private val gty0 = Math.floor((90 - 39.3) / (256 * dlat)).toInt()
    private val west = -180 + gtx0 * 256 * dlon
    private val north = 90 - gty0 * 256 * dlat
    private val size = 512

    /**
     * Synthetic 512 x 512 pack: land everywhere except a 12-cell wide channel
     * (rows 100-411, cols 250-261) at 2.0-3.6 m. A bridge deck crosses it at
     * row 256 with a 2-cell span (cols 255-256, 10 m clearance). A rock (0.5 m)
     * sits in the channel at row 180, col 252.
     */
    /** The synthetic area as one pack, or only tile row [onlyTileRow] of it (to test joining). */
    private fun syntheticPack(onlyTileRow: Int? = null): File {
        val n = size * size
        val planes = Array(Pack.NPLANES) { ByteArray(n) }
        for (r in 100..411) for (c in 250..261) planes[Pack.P_DEPTH][r * size + c] = 1
        for (c in 248..263) {
            val i = 256 * size + c
            val span = c == 255 || c == 256
            planes[Pack.P_FLAGS][i] = (Pack.F_BRIDGE or (if (span) Pack.F_SPAN else Pack.F_DECK)).toByte()
            if (span) planes[Pack.P_SPANCLR][i] = 20 // 10 m in 0.5 m units
        }
        planes[Pack.P_HAZARD][180 * size + 252] = 6 // 0.5 m
        val bridgeLat = north - 256.5 * dlat
        val notes = """[{"kind":"bridge","band":5,"bbox":[${west + 255 * dlon},${bridgeLat - dlat},${west + 257 * dlon},${bridgeLat + dlat}],
            "type":"Bbox","coords":null,"name":"Test Bridge","clr":10.0}]"""
        return writePack(planes, notes, onlyTileRow)
    }

    private fun writePack(planes: Array<ByteArray>, notes: String, onlyTileRow: Int? = null,
                          depthTable: String = "[null,[2.0,3.6,0]]"): File {
        val rows = if (onlyTileRow == null) 0 until 2 else onlyTileRow..onlyTileRow
        val pNorth = north - rows.first * 256 * dlat
        val header = """{"format":"NRPK","version":2,"created":"2026-01-01T00:00:00Z","states":["TEST"],
            "area":{"id":"t${onlyTileRow ?: ""}","name":"Test ${onlyTileRow ?: ""}","bbox":[$west,${north - size * dlat},${west + size * dlon},$north]},
            "tx0":$gtx0,"ty0":${gty0 + rows.first},
            "west":$west,"north":$pNorth,"dlat":$dlat,"dlon":$dlon,"res":$res,"width":$size,"height":${rows.count() * 256},
            "tile":256,"planes":6,"depth_table":$depthTable,
            "notes":$notes}""".trimIndent().replace("\n", "")
        val hb = header.toByteArray()
        val tiles = ArrayList<ByteArray?>()
        for (ty in rows) for (tx in 0 until 2) {
            val raw = ByteArray(Pack.NPLANES * 256 * 256)
            var any = false
            for (p in 0 until Pack.NPLANES) for (r in 0 until 256) for (c in 0 until 256) {
                val v = planes[p][(ty * 256 + r) * size + tx * 256 + c]
                raw[p * 65536 + r * 256 + c] = v
                if (v.toInt() != 0) any = true
            }
            tiles.add(if (any) deflate(raw) else null)
        }
        val out = ByteArrayOutputStream()
        out.write("NRPK2\n".toByteArray())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(hb.size).array())
        out.write(hb)
        var pos = 6L + 4 + hb.size + tiles.size * 12
        for (t in tiles) {
            out.write(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(if (t == null) 0 else pos).putInt(t?.size ?: 0).array())
            pos += t?.size ?: 0
        }
        for (t in tiles) if (t != null) out.write(t)
        val f = File.createTempFile("pack", ".bin")
        f.deleteOnExit()
        f.writeBytes(out.toByteArray())
        return f
    }

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(9)
        d.setInput(b)
        d.finish()
        val o = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!d.finished()) o.write(buf, 0, d.deflate(buf))
        d.end()
        return o.toByteArray()
    }

    private fun cellCenter(r: Int, c: Int) = LatLon(north - (r + 0.5) * dlat, west + (c + 0.5) * dlon)

    @Test
    fun routesThroughSpanAndAroundRock() {
        Pack(syntheticPack()).use { pack ->
            val charts = ChartSet(listOf(pack))
            val r = Planner(charts).plan("t", listOf(cellCenter(120, 255), cellCenter(390, 255)), 3.0, null, bufM = 40.0)
            assertTrue("route length plausible", r.totalNm * Planner.NM in 5000.0..7000.0)
            // Every waypoint stays in the channel
            for (p in r.points) {
                val c = ((p.lon - west) / dlon).toInt()
                assertTrue("col $c inside channel", c in 250..261)
            }
            assertTrue("bridge span reported", r.legs.any { l -> l.notes.any { it.startsWith("Test Bridge span (vertical clearance 33 ft)") } })
            // No air draft was given, so the span the route still crosses is a check
            assertTrue("bridge is a check", r.legs.any { l -> l.notes.any { it == "Test Bridge span (vertical clearance 33 ft): check" } })
            assertTrue("bridge leg flagged", r.legs.any { Gpx.flagged(it) && it.notes.any { n -> n.contains("Test Bridge") } })
            val gpx = Gpx.write(r)
            assertTrue(gpx.contains("<rtept"))
            assertTrue(gpx, gpx.contains("Bridges and cables were not checked (no air draft)."))
            // The summary names the trip's ends and settings, and leads with the skipped checks
            val text = Gpx.report(r, listOf("start: Home (1, 2)", "destination: 3, 4"), listOf("charts: Test"), 40.0)
            val lines = text.lines()
            assertTrue(text, lines[0].startsWith("Bridges and cables were not checked."))
            val nameAt = lines.indexOfFirst { it.startsWith("t: ") }
            assertTrue(text, nameAt > 1)
            assertTrue(text, lines[nameAt - 1].isEmpty() || lines.take(nameAt).any { it.contains("to check") })
            assertTrue(text, lines[nameAt + 1] == "start: Home (1, 2)" && lines[nameAt + 2] == "destination: 3, 4")
            assertTrue(text, lines[nameAt + 3].startsWith("depth 3 ft (MLLW), air draft not checked, hazard buffer 40 m, grid"))
            assertTrue(text, lines[nameAt + 4] == "charts: Test")
            assertTrue(text, lines.indexOfFirst { it.startsWith("Not for navigation") } < nameAt)
            // Great Lakes packs: depths are charted to Low Water Datum, not MLLW
            val lake = Gpx.report(r, emptyList(), emptyList(), null, Pack.datumName(listOf(25)))
            val depthLine = lake.lines().first { it.startsWith("depth ") }
            assertTrue(lake, depthLine.startsWith("depth 3 ft (Low Water Datum), "))
            assertTrue(Gpx.write(r, "Low Water Datum").contains("ft depth at Low Water Datum from NOAA ENC"))
        }
    }

    @Test
    fun airDraftTooTallIsExplained() {
        Pack(syntheticPack()).use { pack ->
            val charts = ChartSet(listOf(pack))
            try {
                Planner(charts).plan("t", listOf(cellCenter(120, 255), cellCenter(390, 255)), 3.0, 40.0, bufM = 40.0)
                fail("expected no route")
            } catch (e: PlanException) {
                assertTrue(e.message, e.message!!.contains("bridge or overhead cable"))
            }
        }
    }

    @Test
    fun joinsTwoAreas() {
        Pack(syntheticPack(0)).use { top ->
            Pack(syntheticPack(1)).use { bottom ->
                val r = Planner(ChartSet(listOf(top, bottom))).plan("t", listOf(cellCenter(120, 255), cellCenter(390, 255)), 3.0, null, bufM = 40.0)
                assertTrue("joined route length plausible", r.totalNm * Planner.NM in 5000.0..7000.0)
                // With only the top area the destination is outside the downloaded charts
                try {
                    Planner(ChartSet(listOf(top))).plan("t", listOf(cellCenter(120, 255), cellCenter(390, 255)), 3.0, null, bufM = 40.0)
                    fail("expected no route without the second area")
                } catch (e: PlanException) {
                    assertNotNull(e.message)
                }
            }
        }
    }

    /**
     * Open water (2.0-3.6 m) with a land block in the southwest quarter (rows
     * 256-511, cols 0-255): a bulkhead headland with its corner at row 256, col 255.
     */
    private fun headlandPack(): File {
        val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (r in 0 until size) for (c in 0 until size) if (r < 256 || c > 255) planes[Pack.P_DEPTH][r * size + c] = 1
        return writePack(planes, "[]")
    }

    /** Distance in meters from p to the land block of [headlandPack]. */
    private fun metersToLand(p: LatLon): Double {
        val r = (north - p.lat) / dlat
        val c = (p.lon - west) / dlon
        val dr = max(0.0, 256.0 - r)
        val dc = max(0.0, c - 256.0)
        return Math.hypot(dr, dc) * res
    }

    @Test
    fun roundsHeadlandWithClearance() {
        Pack(headlandPack()).use { pack ->
            // Starts 100 m off the east shore, ends 100 m off the north shore: the route
            // must turn the corner, and must not shave it once simplified.
            val r = Planner(ChartSet(listOf(pack))).plan("t", listOf(cellCenter(400, 260), cellCenter(251, 100)), 3.0, null)
            var closest = Double.MAX_VALUE
            for ((p, q) in r.points.zipWithNext()) for (k in 0..200) {
                val t = k / 200.0
                closest = minOf(closest, metersToLand(LatLon(p.lat + (q.lat - p.lat) * t, p.lon + (q.lon - p.lon) * t)))
            }
            assertTrue("closest approach to the headland %.0f m".format(closest), closest >= 60.0)
        }
    }

    @Test
    fun startOnLandKeepsItsWaterWaypoint() {
        Pack(headlandPack()).use { pack ->
            val start = cellCenter(300, 250) // 5 cells inside the land block
            val r = Planner(ChartSet(listOf(pack))).plan("t", listOf(start, cellCenter(100, 400)), 3.0, null)
            assertEquals(start, r.points[0])
            assertTrue("second point is the nearest water, not a far waypoint", metersToLand(r.points[1]) < 40.0)
            assertTrue("first leg flagged", r.legs[0].notes.firstOrNull()?.startsWith("connects your point") == true)
            assertTrue("other legs not flagged", r.legs.drop(1).none { l -> l.notes.any { it.startsWith("connects") } })
        }
    }

    /**
     * Open water (2.0-3.6 m) with two basins of the same water (11 x 11 cells, centered
     * at rows/cols 150 and 360), each walled in by a 10-cell ring of 0-0.6 m water: too
     * shallow for 3 ft, like a marina in a creek that dries.
     */
    private fun pocketPack(): File {
        val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (i in 0 until size * size) planes[Pack.P_DEPTH][i] = 1
        for (ctr in listOf(150, 360)) for (r in ctr - 15..ctr + 15) for (c in ctr - 15..ctr + 15)
            if (maxOf(Math.abs(r - ctr), Math.abs(c - ctr)) > 5) planes[Pack.P_DEPTH][r * size + c] = 2
        return writePack(planes, "[]", depthTable = "[null,[2.0,3.6,0],[0.0,0.6,0]]")
    }

    @Test
    fun moveEndsLeavesAPocketClosedByShallowWater() {
        Pack(pocketPack()).use { pack ->
            val planner = Planner(ChartSet(listOf(pack)))
            val start = cellCenter(150, 150)
            val open = cellCenter(100, 450)
            try {
                planner.plan("t", listOf(start, open), 3.0, null)
                fail("planned out of a pocket closed by shallow water with Move ends off")
            } catch (e: PlanException) {
                assertTrue(e.message, e.message!!.contains("pocket"))
                // The ring opens at a shallower depth, and a plan at that depth does connect
                assertTrue(e.message, e.message!!.contains("With a safe depth of"))
            }
            val r = planner.plan("t", listOf(start, open), 3.0, null, moveEnds = true)
            assertEquals(start, r.points[0])
            assertTrue("first leg flagged", r.legs[0].notes.firstOrNull()?.startsWith("connects your point") == true)
            assertTrue("message: ${r.messages}", r.messages.any { it.startsWith("Point 1: the water there is cut off by water shallower") })
            // Moved just past the ring: 10 cells from the basin's edge, 16 from its center
            val moved = Planner.distanceM(r.points[0], r.points[1])
            assertTrue("moved %.0f m".format(moved), moved in 300.0..400.0)
            // Both ends in pockets: both move, out to the open water between them
            val both = planner.plan("t", listOf(start, cellCenter(360, 360)), 3.0, null, moveEnds = true)
            assertTrue("last leg flagged", both.legs.last().notes.firstOrNull()?.startsWith("connects your point") == true)
            assertEquals("messages: ${both.messages}", 2, both.messages.count { it.contains("Move ends to navigable water") })
        }
    }

    @Test
    fun moveEndsKeepsAPointInWaterThatMayBeUnderTheSafeDepth() {
        // Open water (2.0-3.6 m) with a cove of 0-1.8 m water (rows 100-160, cols 100-160),
        // open to the east: usable but flagged at 3 ft, like a marina in a 0-6 ft creek.
        // Move ends leaves the point there and the legs stay flagged (issue #27).
        val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (i in 0 until size * size) planes[Pack.P_DEPTH][i] = 1
        for (r in 100..160) for (c in 100..160) planes[Pack.P_DEPTH][r * size + c] = 2
        Pack(writePack(planes, "[]", depthTable = "[null,[2.0,3.6,0],[0.0,1.8,0]]")).use { pack ->
            val planner = Planner(ChartSet(listOf(pack)))
            val start = cellCenter(130, 150) // 10 cells from the cove's east edge
            val end = cellCenter(130, 400)
            val plain = planner.plan("t", listOf(start, end), 3.0, null)
            assertTrue("without the option the route leaves through the cove", plain.legs.any { it.shoalM > 0 })
            val r = planner.plan("t", listOf(start, end), 3.0, null, moveEnds = true)
            assertEquals(start, r.points[0])
            assertTrue("first leg is a planned leg", r.legs[0].notes.none { it.startsWith("connects your point") })
            assertTrue("shallow legs still flagged", r.legs.any { it.shoalM > 0 })
            assertTrue("message: ${r.messages}", r.messages.none { it.startsWith("Point 1") })
            assertEquals(plain.totalNm, r.totalNm, 0.01)
        }
    }

    /** Open water (2.0-3.6 m) with a pier or seawall line (HAZARD = HZ_PIER) along col 300, rows [rows]. */
    private fun pierPack(rows: IntRange): File {
        val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (i in 0 until size * size) planes[Pack.P_DEPTH][i] = 1
        for (r in rows) planes[Pack.P_HAZARD][r * size + 300] = Pack.HZ_PIER.toByte()
        return writePack(planes, "[]")
    }

    @Test
    fun goesAroundPierWhenItCan() {
        Pack(pierPack(200..300)).use { pack ->
            // 400 m apart across a 2 km pier: the route goes around its end, not over it
            val r = Planner(ChartSet(listOf(pack))).plan("t", listOf(cellCenter(250, 290), cellCenter(250, 310)), 3.0, null)
            assertTrue("went around (%.0f m)".format(r.totalNm * Planner.NM), r.totalNm * Planner.NM > 2000.0)
            assertTrue("no pier note", r.legs.none { l -> l.notes.any { it.contains("piers or seawalls") } })
        }
    }

    @Test
    fun crossesPierOnlyWhenItMustAndSaysSo() {
        Pack(pierPack(0 until size)).use { pack ->
            val r = Planner(ChartSet(listOf(pack))).plan("t", listOf(cellCenter(250, 290), cellCenter(250, 310)), 3.0, null)
            assertTrue("crossed directly (%.0f m)".format(r.totalNm * Planner.NM), r.totalNm * Planner.NM < 600.0)
            assertTrue("pier note", r.legs.any { l -> l.notes.any { it.contains("piers or seawalls") } })
        }
    }

    /**
     * Land with a one-cell channel (col 300, rows 100-400), like the 27 m dredged ICW
     * channel in Adams Creek Canal on the 20 m grid, and one obstruction of unknown depth
     * in it at row 250: a snag (pack SNAG_MARK) or not. Depth index 1 = 2.0-3.6 m (deep
     * enough for 3 ft), 2 = 0-1.8 m (spans 3 ft).
     */
    private fun channelPack(depthIndex: Int, snagMark: Boolean): File {
        val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (r in 100..400) planes[Pack.P_DEPTH][r * size + 300] = depthIndex.toByte()
        planes[Pack.P_HAZARD][250 * size + 300] = 1
        if (snagMark) planes[Pack.P_CABLECLR][250 * size + 300] = Pack.SNAG_MARK.toByte()
        return writePack(planes, "[]", depthTable = "[null,[2.0,3.6,0],[0.0,1.8,0]]")
    }

    private fun planChannel(pack: File): Route = Pack(pack).use { p ->
        Planner(ChartSet(listOf(p))).plan("t", listOf(cellCenter(110, 300), cellCenter(390, 300)), 3.0, null)
    }

    @Test
    fun passesSnagInDeepEnoughChannelAndSaysSo() {
        val r = planChannel(channelPack(1, snagMark = true))
        assertTrue("straight up the channel", r.totalNm * Planner.NM in 5500.0..5800.0)
        assertTrue("snag note", r.legs.any { l -> l.notes.any { it.contains("piles or snags") } })
    }

    @Test
    fun otherObstructionsStayWalls() {
        try {
            planChannel(channelPack(1, snagMark = false))
            fail("a wreck or rock of unknown depth must still close the channel")
        } catch (e: PlanException) {
            assertTrue(e.message, e.message!!.contains("No route"))
        }
    }

    @Test
    fun snagWhereWaterMayBeTooShallowIsAWall() {
        try {
            planChannel(channelPack(2, snagMark = true))
            fail("a snag in the 0-6 ft band must still close the channel")
        } catch (e: PlanException) {
            assertTrue(e.message, e.message!!.contains("No route"))
        }
    }

    /** Open water with a channel marker's wrong-side line (GATE_MARK) along row 250, cols [cols]. */
    private fun gatePack(cols: IntRange): File {
        val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (i in 0 until size * size) planes[Pack.P_DEPTH][i] = 1
        for (c in cols) planes[Pack.P_CABLECLR][250 * size + c] = Pack.GATE_MARK.toByte()
        return writePack(planes, "[]")
    }

    @Test
    fun goesAroundTheWrongSideOfAMarker() {
        Pack(gatePack(300..320)).use { pack ->
            // Straight up col 310 would cross the line; going round its end costs little
            val r = Planner(ChartSet(listOf(pack))).plan("t", listOf(cellCenter(270, 310), cellCenter(230, 310)), 3.0, null)
            assertTrue("went around (%.0f m)".format(r.totalNm * Planner.NM), r.totalNm * Planner.NM > 820.0)
            assertTrue("no marker note", r.legs.none { l -> l.notes.any { it.contains("channel marker") } })
        }
    }

    @Test
    fun crossesTheWrongSideOnlyWhenItMustAndSaysSo() {
        Pack(gatePack(0 until size)).use { pack ->
            val r = Planner(ChartSet(listOf(pack))).plan("t", listOf(cellCenter(270, 310), cellCenter(230, 310)), 3.0, null)
            assertTrue("crossed directly (%.0f m)".format(r.totalNm * Planner.NM), r.totalNm * Planner.NM < 900.0)
            assertTrue("marker note", r.legs.any { l -> l.notes.any { it.contains("channel marker") } })
        }
    }

    @Test
    fun parsesCoordinates() {
        assertEquals(LatLon(39.25084, -76.48861), Coords.parse("39.25084° N, 76.48861° W"))
        assertEquals(LatLon(39.2508, -76.4886), Coords.parse("39.2508,-76.4886"))
        val dm = Coords.parse("39 19.4 N, 76 24.0 W")
        assertNotNull(dm)
        assertEquals(39.323333, dm!!.lat, 0.00002)
        assertEquals(-76.4, dm.lon, 0.00002)
        assertNotNull(Coords.parse("39.2508 -76.4886"))
        assertNull(Coords.parse("abc"))
        assertNull(Coords.parse("95,10"))
    }

    @Test
    fun parsesJson() {
        val m = MiniJson.parse("""{"a":[1,2.5,null,true],"b":"x\"y°"}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, null, true), m["a"])
        assertEquals("x\"y°", m["b"])
    }

    @Test
    fun datumNames() {
        assertEquals("MLLW", Pack.datumName(emptyList()))
        assertEquals("MLLW", Pack.datumName(listOf(12, 24)))
        assertEquals("Low Water Datum", Pack.datumName(listOf(25)))
        assertEquals("Low Water Datum", Pack.datumName(listOf(12, 24, 25)))
    }

    @Test
    fun oneMarginalCellIsReported() {
        // A single column of 0-1.8 m across otherwise deep water. The old gate
        // dropped a crossing of one grid cell, so the leg carried no shallow note.
        val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (i in 0 until size * size) planes[Pack.P_DEPTH][i] = 1
        for (r in 0 until size) planes[Pack.P_DEPTH][r * size + 256] = 2
        Pack(writePack(planes, "[]", depthTable = "[null,[2.0,3.6,0],[0.0,1.8,0]]")).use { pack ->
            val r = Planner(ChartSet(listOf(pack))).plan("t", listOf(cellCenter(200, 200), cellCenter(200, 300)), 3.0, null)
            assertTrue("shoal %.0f m".format(r.legs.sumOf { it.shoalM }), r.legs.any { it.shoalM > 0 })
        }
    }

    @Test
    fun smoothedLegDoesNotCrossMarginalWaterThePathAvoided() {
        // Deep water with a 0-1.8 m block the straight line would cut. Going through
        // costs four times as much, so the path goes around. The chord must not
        // come back across the block.
        val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (i in 0 until size * size) planes[Pack.P_DEPTH][i] = 1
        for (r in 180..320) for (c in 240..270) planes[Pack.P_DEPTH][r * size + c] = 2
        Pack(writePack(planes, "[]", depthTable = "[null,[2.0,3.6,0],[0.0,1.8,0]]")).use { pack ->
            val r = Planner(ChartSet(listOf(pack))).plan("t", listOf(cellCenter(250, 180), cellCenter(250, 340)), 3.0, null)
            for ((p, q) in r.points.zipWithNext()) for (k in 0..80) {
                val t = k / 80.0
                val lat = p.lat + (q.lat - p.lat) * t
                val lon = p.lon + (q.lon - p.lon) * t
                val row = (north - lat) / dlat
                val col = (lon - west) / dlon
                val inside = row in 185.0..315.0 && col in 245.0..265.0
                assertTrue("leg through the shoal at row %.0f col %.0f".format(row, col), !inside)
            }
        }
    }

    @Test
    fun straightLegStaysEastOfAShoalItWouldOtherwiseClip() {
        // The straight line from north to south clips the west edge of a small
        // 0-6 ft knoll. Open deep water is east of it, and a wider shoal is west,
        // so the route belongs east of the knoll. A 1.5 m sounding in the knoll
        // prints as 5 ft and is not a hazard at 5 ft; the depth area is what
        // the leg must stay out of. Matches the knoll north of the Bay Bridge.
        val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (r in 40..470) for (c in 40..470) planes[Pack.P_DEPTH][r * size + c] = 1
        // A wide 0-6 ft shoal to the west, with a bulge the straight line clips.
        // Deep water is only to the east of the bulge.
        for (r in 100..360) for (c in 40..240) planes[Pack.P_DEPTH][r * size + c] = 2
        for (r in 200..230) for (c in 241..275) planes[Pack.P_DEPTH][r * size + c] = 2
        planes[Pack.P_SOUND][215 * size + 255] = 16 // 1.5 m, chart label 5
        val table = "[null,[3.6,18.0,0],[0.0,1.8,0]]"
        Pack(writePack(planes, "[]", depthTable = table)).use { pack ->
            val route = Planner(ChartSet(listOf(pack))).plan(
                "t", listOf(cellCenter(80, 255), cellCenter(360, 260)), 5.0, null)
            // East of the bulge: columns past 275. Sample the band the bulge occupies.
            var seen = 0
            var westmost = 9999.0
            for ((p, q) in route.points.zipWithNext()) for (k in 0..160) {
                val t = k / 160.0
                val lat = p.lat + (q.lat - p.lat) * t
                val lon = p.lon + (q.lon - p.lon) * t
                val row = (north - lat) / dlat
                val col = (lon - west) / dlon
                assertTrue("leg in the knoll at row %.0f col %.0f".format(row, col),
                    !(row in 202.0..228.0 && col in 243.0..273.0))
                if (row in 205.0..225.0) {
                    seen++
                    if (col < westmost) westmost = col
                }
            }
            assertTrue("no sample beside the knoll", seen > 0)
            assertTrue("westmost column beside the knoll is %.0f, want east of 276".format(westmost), westmost > 276.0)
            assertTrue("1.5 m prints as 5", Planner.printedFeet(1.5) == 5)
            assertTrue("a printed 5 is not shallow at 5 ft", !Planner.soundingShallow(1.5, 5.0))
        }
    }

    @Test
    fun straightLegDoesNotCrossAHazardBufferThePathAvoided() {
        // A 1.2 m sounding prints as 4 ft and is a hazard at 5 ft. The straight
        // line would pass through its buffer. The leg must stay outside that buffer.
        val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (r in 40..470) for (c in 40..470) planes[Pack.P_DEPTH][r * size + c] = 1
        planes[Pack.P_SOUND][250 * size + 256] = 13 // 1.2 m, chart label 4
        Pack(writePack(planes, "[]", depthTable = "[null,[3.6,18.0,0]]")).use { pack ->
            val route = Planner(ChartSet(listOf(pack))).plan(
                "t", listOf(cellCenter(80, 256), cellCenter(420, 256)), 5.0, null)
            val spot = cellCenter(250, 256)
            var best = Double.MAX_VALUE
            for ((p, q) in route.points.zipWithNext()) for (k in 0..120) {
                val t = k / 120.0
                best = minOf(best, Planner.distanceM(spot, LatLon(
                    p.lat + (q.lat - p.lat) * t, p.lon + (q.lon - p.lon) * t)))
            }
            assertTrue("leg is %.0f m from a printed-4 sounding".format(best), best >= 60.0)
        }
    }

    @Test
    fun oneCellChannelAndDepthBoundaryAreNotStaircases() {
        // A stair of dredged cells in 0-6 ft water, and a route kept to the 6-12 ft
        // side of a diagonal 0-6 ft edge. Both used to stay a run of 20 m legs.
        // Water charted entirely under 3 ft, off to the side, is not crossed.
        val channel = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (r in 80..220) for (c in 80..220) channel[Pack.P_DEPTH][r * size + c] = 2
        for (i in 0 until 30) {
            val r = 100 + i * 2
            val c = 100 + i * 2
            channel[Pack.P_DEPTH][r * size + c] = 1
            channel[Pack.P_DEPTH][r * size + c + 1] = 1
            channel[Pack.P_DEPTH][(r + 1) * size + c + 1] = 1
        }
        for (r in 140..150) for (c in 60..70) channel[Pack.P_DEPTH][r * size + c] = 3
        val table = "[null,[3.6,9.0,1],[0.0,1.8,0],[0.0,0.5,0]]"
        Pack(writePack(channel, "[]", depthTable = table)).use { pack ->
            val route = Planner(ChartSet(listOf(pack))).plan(
                "t", listOf(cellCenter(100, 100), cellCenter(158, 159)), 3.0, null)
            val short = route.legs.count { it.distM < 40.0 }
            assertTrue("channel has $short legs under 40 m", short <= 4)
            assertStaysOutOfShallowBlock(route)
        }
        val edge = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (r in 80..240) for (c in 80..240) edge[Pack.P_DEPTH][r * size + c] = if (c <= r) 1 else 2
        for (r in 140..150) for (c in 60..70) edge[Pack.P_DEPTH][r * size + c] = 3
        Pack(writePack(edge, "[]", depthTable = "[null,[1.8,3.6,0],[0.0,1.8,0],[0.0,0.5,0]]")).use { pack ->
            val route = Planner(ChartSet(listOf(pack))).plan(
                "t", listOf(cellCenter(100, 100), cellCenter(200, 200)), 3.0, null)
            val short = route.legs.count { it.distM < 40.0 }
            assertTrue("boundary has $short legs under 40 m", short <= 4)
            assertStaysOutOfShallowBlock(route)
        }
    }

    @Test
    fun narrowDredgedChannelStaysNearItsCenter() {
        // 0-6 ft water with a dredged channel 5 cells (100 m) wide, bent in an L.
        // Under the 120 m narrow limit. The inside of the bend is shorter, so a
        // flat cost rides that edge. The route has to stay in the channel: outside
        // is the 0-6 ft band.
        val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (i in 0 until size * size) planes[Pack.P_DEPTH][i] = 2
        for (r in 100..250) for (c in 200..204) planes[Pack.P_DEPTH][r * size + c] = 1
        for (r in 246..250) for (c in 200..400) planes[Pack.P_DEPTH][r * size + c] = 1
        val table = "[null,[3.6,9.0,1],[0.0,1.8,0]]"
        Pack(writePack(planes, "[]", depthTable = table)).use { pack ->
            val route = Planner(ChartSet(listOf(pack))).plan(
                "t", listOf(cellCenter(120, 202), cellCenter(248, 380)), 3.0, null)
            for ((p, q) in route.points.zipWithNext()) for (k in 0..40) {
                val t = k / 40.0
                val row = (north - (p.lat + (q.lat - p.lat) * t)) / dlat
                val col = (p.lon + (q.lon - p.lon) * t - west) / dlon
                val ri = kotlin.math.floor(row).toInt()
                val ci = kotlin.math.floor(col).toInt()
                // Away from the bend: the corner itself may clip the inside edge.
                if (ri in 140..200) assertTrue("vertical leg in col $ci (edge is 200 or 204)", ci in 201..203)
                if (ci in 320..380) assertTrue("horizontal leg in row $ri (edge is 246 or 250)", ri in 247..249)
            }
        }
    }

    @Test
    fun wideDredgedAreaIsNotPulledToItsMiddle() {
        // 24 cells (480 m) of dredged water in deep water. Wider than 120 m, so the
        // cost stays flat and a route that starts and ends on the south edge stays there.
        val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (i in 0 until size * size) planes[Pack.P_DEPTH][i] = 1
        for (r in 200..223) for (c in 80..420) planes[Pack.P_DEPTH][r * size + c] = 2
        val table = "[null,[3.6,18.0,0],[3.6,18.0,1]]"
        Pack(writePack(planes, "[]", depthTable = table)).use { pack ->
            val route = Planner(ChartSet(listOf(pack))).plan(
                "t", listOf(cellCenter(222, 100), cellCenter(222, 400)), 3.0, null)
            for ((p, q) in route.points.zipWithNext()) for (k in 0..40) {
                val t = k / 40.0
                val row = (north - (p.lat + (q.lat - p.lat) * t)) / dlat
                val col = (p.lon + (q.lon - p.lon) * t - west) / dlon
                val ri = kotlin.math.floor(row).toInt()
                val ci = kotlin.math.floor(col).toInt()
                if (ci in 150..350) assertTrue("wide channel leg drifted to row $ri", ri >= 220)
            }
        }
    }

    private fun assertStaysOutOfShallowBlock(route: Route) {
        for ((p, q) in route.points.zipWithNext()) for (k in 0..40) {
            val t = k / 40.0
            val lat = p.lat + (q.lat - p.lat) * t
            val lon = p.lon + (q.lon - p.lon) * t
            val row = (north - lat) / dlat
            val col = (lon - west) / dlon
            assertTrue("leg in water charted under the safe depth", !(row in 140.0..150.0 && col in 60.0..70.0))
        }
    }

    @Test
    fun aSoundingPrintedAtTheSafeDepthIsNotAHazard() {
        // 1.5 m prints as 5 ft. At 5 ft the route may run through the cell. The same
        // cell with 1.2 m (prints as 4) is a wall at 5 ft and open at 4 ft.
        val ends = listOf(cellCenter(250, 200), cellCenter(250, 312))
        fun nearest(route: Route, spot: LatLon): Double {
            var best = Double.MAX_VALUE
            for ((p, q) in route.points.zipWithNext()) for (k in 0..100) {
                val t = k / 100.0
                best = minOf(best, Planner.distanceM(spot, LatLon(p.lat + (q.lat - p.lat) * t, p.lon + (q.lon - p.lon) * t)))
            }
            return best
        }
        fun route(dm: Int, depthFt: Double): Double {
            val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
            for (i in 0 until size * size) planes[Pack.P_DEPTH][i] = 1
            val spot = cellCenter(250, 256)
            planes[Pack.P_SOUND][250 * size + 256] = (1 + dm).toByte()
            Pack(writePack(planes, "[]")).use { pack ->
                val planned = Planner(ChartSet(listOf(pack))).plan("t", ends, depthFt, null)
                return nearest(planned, spot)
            }
        }
        val equal = route(15, 5.0) // 1.5 m, chart label 5
        val under = route(12, 5.0) // 1.2 m, chart label 4
        val underAt4 = route(12, 4.0)
        assertTrue("5 ft route through a printed 5 is ${"%.0f".format(equal)} m away", equal <= 5.0)
        assertTrue("5 ft route is ${"%.0f".format(under)} m from a printed 4", under >= 60.0)
        assertTrue("4 ft route through a printed 4 is ${"%.0f".format(underAt4)} m away", underAt4 <= 5.0)
    }

    @Test
    fun hazardNotes() {
        // The range is the 20 m cell around the distance, rounded out to 5 m
        assertEquals("passes 25-45 m from a charted 3.2 ft sounding: check",
            Planner.NearHazard(0, 35.0, Planner.HZ_SOUNDING, 0.98).note(20.0))
        assertEquals("passes 0-15 m from a charted obstruction (depth unknown or 0 ft): check",
            Planner.NearHazard(0, 3.0, Planner.HZ_OBSTRUCTION, 0.0).note(20.0))
        assertEquals("passes 40-60 m from a charted obstruction (2.1 ft): check",
            Planner.NearHazard(0, 50.0, Planner.HZ_OBSTRUCTION, 0.64).note(20.0))
        assertEquals("passes 10-30 m from charted piles or snags: check",
            Planner.NearHazard(0, 20.0, Planner.HZ_SNAG, 0.0).note(20.0))
    }

    /**
     * Open water (rows 40-470, cols 40-470) with a charted security zone (rows 240-270,
     * cols 230-280) across the straight line from row 100 to row 400 at col 255.
     */
    private fun securityZonePack(): File {
        val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (r in 40..470) for (c in 40..470) planes[Pack.P_DEPTH][r * size + c] = 1
        for (r in 240..270) for (c in 230..280) planes[Pack.P_FLAGS][r * size + c] = Pack.F_RESNOTE.toByte()
        val n = north - 240 * dlat
        val s = north - 271 * dlat
        val w = west + 230 * dlon
        val e = west + 281 * dlon
        val notes = """[{"kind":"resare","band":5,"bbox":[$w,$s,$e,$n],"type":"Polygon",""" +
            """"coords":[[[$w,$n],[$e,$n],[$e,$s],[$w,$s],[$w,$n]]],"name":"Test Security Zone",""" +
            """"rules":["security zone, entry may be prohibited: check"],"cfr":"33 CFR 165.999"}]"""
        return writePack(planes, notes)
    }

    @Test
    fun resolveChecksGoesAroundSecurityZone() {
        Pack(securityZonePack()).use { pack ->
            val planner = Planner(ChartSet(listOf(pack)))
            val pts = listOf(cellCenter(100, 255), cellCenter(400, 255))
            val r = planner.plan("t", pts, 5.0, null)
            val zone = "Test Security Zone: security zone, entry may be prohibited: check (33 CFR 165.999)"
            assertTrue("crosses the zone", r.legs.any { l -> l.notes.any { it.startsWith(zone) } })
            assertTrue("names how much of the leg is inside", r.legs.any { l -> l.notes.any { it.contains("m of this") && it.contains("is inside") } })
            val res = planner.resolveChecks(r, pts)
            assertEquals(res.summary, 1, res.runs)
            assertEquals(res.summary, 1, res.resolved)
            val a = res.route
            assertTrue("no checks left", a.legs.none { Gpx.flagged(it) })
            assertEquals("same ends", pts.first(), a.points.first())
            assertEquals("same ends", pts.last(), a.points.last())
            val extra = (a.totalNm - r.totalNm) * Planner.NM
            assertTrue("small detour: $extra m", extra > 0 && extra <= max(Planner.RESOLVE_EXTRA_M, r.totalNm * Planner.NM * Planner.RESOLVE_EXTRA_FRAC))
            assertTrue(a.messages.last(), a.messages.last().startsWith("No check stretch left. Looked for a way around: 1 of 1 cleared (+"))
            assertEquals("t_adjusted", a.name)
            // A route with no checks is left alone
            val clear = planner.resolveChecks(a, pts)
            assertEquals(0, clear.runs)
            assertEquals(a.points, clear.route.points)
        }
    }

    @Test
    fun resolveChecksKeepsStretchWithNoWayAround() {
        // The zone spans the whole water: nothing to gain, the route stays
        val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (r in 40..470) for (c in 250..261) planes[Pack.P_DEPTH][r * size + c] = 1
        for (r in 240..270) for (c in 250..261) planes[Pack.P_FLAGS][r * size + c] = Pack.F_RESNOTE.toByte()
        val n = north - 240 * dlat
        val s = north - 271 * dlat
        val w = west + 250 * dlon
        val e = west + 262 * dlon
        val notes = """[{"kind":"resare","band":5,"bbox":[$w,$s,$e,$n],"type":"Polygon",""" +
            """"coords":[[[$w,$n],[$e,$n],[$e,$s],[$w,$s],[$w,$n]]],"name":"Zone","rules":["security zone, entry may be prohibited: check"]}]"""
        Pack(writePack(planes, notes)).use { pack ->
            val planner = Planner(ChartSet(listOf(pack)))
            val pts = listOf(cellCenter(100, 255), cellCenter(400, 255))
            val r = planner.plan("t", pts, 5.0, null)
            val res = planner.resolveChecks(r, pts)
            assertEquals(res.summary, 1, res.runs)
            assertEquals(res.summary, 0, res.resolved + res.partly)
            assertEquals(r.points, res.route.points)
            assertTrue(res.summary, res.summary.endsWith("1 unchanged (no way around within 0.2 nm or 10% of the stretch)."))
        }
    }

    @Test
    fun blankAirDraftNeedsAnExplicitChoice() {
        assertEquals(10.0, Planner.airDraftFt("10", false)!!, 0.0)
        assertEquals(10.0, Planner.airDraftFt(" 10 ", true)!!, 0.0)
        assertNull(Planner.airDraftFt("", true))
        assertNull(Planner.airDraftFt("  ", true))
        try {
            Planner.airDraftFt("", false)
            fail("blank air draft was accepted")
        } catch (e: PlanException) {
            assertTrue(e.message, e.message!!.contains("Don't check bridges and cables"))
        }
        try {
            Planner.airDraftFt("0", true)
            fail("zero air draft was accepted")
        } catch (e: PlanException) {
            assertTrue(e.message, e.message!!.contains("feet"))
        }
    }

    /**
     * A channel crossed by a 20 ft cable, with 35 ft and 43 ft cables on the same
     * line, and a land pin north of it. South of the cable is open. North is a
     * deep basin in 0-2 ft water, so a lower safe depth reaches the window edge
     * and still cannot pass the cable.
     */
    @Test
    fun moveEndsDoesNotCrossACableTheAirDraftCannotClear() {
        val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
        // North of the cable is 0-2 ft water, so a lower safe depth reaches the window edge.
        // A deep basin sits in it. The cable crosses the whole grid, so nothing goes around it.
        // South of the cable is deep water out to the pack edge.
        for (r in 0..199) for (c in 0 until size) planes[Pack.P_DEPTH][r * size + c] = 2 // 0-0.6 m
        for (r in 150..185) for (c in 220..300) planes[Pack.P_DEPTH][r * size + c] = 1
        for (r in 186..194) for (c in 248..268) planes[Pack.P_DEPTH][r * size + c] = 0 // the pin sits on land
        for (c in 0 until size) {
            val i = 200 * size + c
            planes[Pack.P_DEPTH][i] = 1
            planes[Pack.P_FLAGS][i] = Pack.F_CABLE.toByte()
            planes[Pack.P_CABLECLR][i] = 12 // 6.0 m, charted 20 ft
        }
        for (r in 201 until size) for (c in 0 until size) planes[Pack.P_DEPTH][r * size + c] = 1
        val lat = north - 200.5 * dlat
        val notes = """[{"kind":"cable","band":5,"bbox":[${west},${lat - 2 * dlat},${west + size * dlon},${lat + 2 * dlat}],"type":"Bbox","coords":null,"name":"power line","clr":10.668},""" +
            """{"kind":"cable","band":5,"bbox":[${west},${lat - 2 * dlat},${west + size * dlon},${lat + 2 * dlat}],"type":"Bbox","coords":null,"name":"overhead cable","clr":6.0},""" +
            """{"kind":"cable","band":5,"bbox":[${west},${lat - 2 * dlat},${west + size * dlon},${lat + 2 * dlat}],"type":"Bbox","coords":null,"name":"higher cable","clr":13.1064}]"""
        val pin = cellCenter(190, 258)
        val start = cellCenter(400, 258)
        fun rowOf(p: LatLon) = ((north - p.lat) / dlat).toInt()
        fun crossesCable(r: Route): Boolean {
            for ((p, q) in r.points.zipWithNext()) {
                val n = max(1, (Planner.distanceM(p, q) / 5.0).toInt())
                for (k in 0..n) {
                    val t = k / n.toDouble()
                    if (rowOf(LatLon(p.lat + (q.lat - p.lat) * t, p.lon + (q.lon - p.lon) * t)) == 200) return true
                }
            }
            return false
        }
        Pack(writePack(planes, notes, depthTable = "[null,[2.0,3.6,0],[0.0,0.6,0]]")).use { pack ->
            val planner = Planner(ChartSet(listOf(pack)))
            for ((depth, air) in listOf(6.0 to 55.0, 4.0 to 25.0)) {
                try {
                    planner.plan("t", listOf(start, pin), depth, air)
                    fail("planned across the cable at $depth ft with air $air and Move ends off")
                } catch (e: PlanException) {
                    assertTrue(e.message, e.message!!.contains("No route found"))
                    assertTrue(e.message, !e.message!!.contains("With a safe depth of"))
                }
                val r = planner.plan("t", listOf(start, pin), depth, air, moveEnds = true)
                assertTrue("route crossed the 20 ft cable at $depth ft / $air ft", !crossesCable(r))
                assertTrue("ended on the pin", r.points.last() != pin)
                assertTrue("a point north of the cable", r.points.none { rowOf(it) < 200 })
                val msg = r.messages.joinToString("\n")
                assertTrue(msg, msg.contains("overhead cable (clearance 20 ft"))
                assertTrue(msg, msg.contains("near side"))
                assertTrue(msg, msg.contains("land beyond"))
                assertTrue("leg still names the cable", r.legs.none { l -> l.notes.any { it.contains("clearance 20 ft") } })
            }
            // 10 ft of air clears a charted 20 ft cable. The route uses that water and still ends at the pin.
            for (move in listOf(false, true)) {
                val r = planner.plan("t", listOf(start, pin), 2.0, 10.0, moveEnds = move)
                assertEquals(pin, r.points.last())
                assertTrue("10 ft route did not use the cable crossing (move ends $move)", crossesCable(r))
                assertTrue(r.legs.any { l -> l.notes.any { it.contains("overhead cable (clearance 20 ft") && !it.contains("check") } })
            }
        }
    }

    /**
     * A pin inside entry-prohibited water. The route stops outside that water and
     * does not draw the unchecked leg into the pin. A long channel leg that only
     * clips the boundary is not labeled prohibited for its whole length.
     */
    @Test
    fun pinInsideEntryProhibitedWaterStopsOutside() {
        val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (r in 80..420) for (c in 250..261) planes[Pack.P_DEPTH][r * size + c] = 1
        for (r in 350..390) for (c in 230..256) {
            planes[Pack.P_DEPTH][r * size + c] = 1
            planes[Pack.P_FLAGS][r * size + c] = (Pack.F_PROHIB or Pack.F_RESNOTE).toByte()
        }
        // The note's all-touched halo: one channel cell east of the boundary, not prohibited.
        for (r in 360..370) planes[Pack.P_FLAGS][r * size + 257] =
            (planes[Pack.P_FLAGS][r * size + 257].toInt() or Pack.F_RESNOTE).toByte()
        val pin = cellCenter(365, 248)
        val n = pin.lat + 8 * dlat
        val s = pin.lat - 8 * dlat
        val w = pin.lon - 8 * dlon
        val e = pin.lon + 4 * dlon
        val notes = """[{"kind":"resare","band":5,"bbox":[$w,$s,$e,$n],"type":"Polygon",""" +
            """"coords":[[[$w,$n],[$e,$n],[$e,$s],[$w,$s],[$w,$n]]],"name":"Sunny Point","rules":["entry prohibited"],"cfr":"33 CFR 334.450"}]"""
        Pack(writePack(planes, notes)).use { pack ->
            val r = Planner(ChartSet(listOf(pack))).plan("t", listOf(cellCenter(100, 255), pin), 6.0, 55.0)
            assertTrue("drew the pin", r.points.last() != pin)
            assertTrue("came within 30 m of the pin", Planner.distanceM(r.points.last(), pin) > 30.0)
            val msg = r.messages.joinToString("\n")
            assertTrue(msg, msg.contains("entry is prohibited"))
            assertTrue(msg, msg.contains("does not draw a line"))
            assertTrue(msg, msg.contains("33 CFR 334.450"))
            assertTrue("unchecked leg into the pin", r.legs.none { l -> l.notes.any { it.contains("not checked, use your eyes") } })
            assertTrue("long leg labeled prohibited", r.legs.none { it.distM > 200.0 && it.notes.any { n0 -> n0.contains("entry prohibited") } })
        }
    }

    @Test
    fun moveEndsOnLandStaysAtTheNearestWater() {
        // A marginal ditch (cols 200-205) is the only way south. Deep water is a side
        // basin to the north-east, not on the way. A land pin stays in the ditch.
        val planes = Array(Pack.NPLANES) { ByteArray(size * size) }
        for (r in 0 until size) for (c in 200..205) planes[Pack.P_DEPTH][r * size + c] = 2
        for (r in 0..80) for (c in 206..400) planes[Pack.P_DEPTH][r * size + c] = 1
        Pack(writePack(planes, "[]", depthTable = "[null,[2.0,3.6,0],[0.0,1.8,0]]")).use { pack ->
            val land = cellCenter(250, 198)
            val r = Planner(ChartSet(listOf(pack))).plan("t", listOf(land, cellCenter(400, 202)), 3.0, 10.0, moveEnds = true)
            val landMsg = r.messages.first { it.contains("on land or a dock") }
            assertTrue(landMsg, !landMsg.contains("charted at least"))
            val moved = Planner.distanceM(r.points[0], r.points[1])
            assertTrue("walked %.0f m toward deep water".format(moved), moved < 80.0)
        }
    }

    @Test
    fun canalRuleCableTenthAndOpeningBridge() {
        val box = doubleArrayOf(-1.0, -1.0, 1.0, 1.0)
        val canal = Note("resare", 5, box, "Bbox", null, "restricted area",
            listOf("anchoring restricted", "entry restricted"), "33 CFR 162.40", null, null, null)
        val canalText = canal.describe()!!
        assertTrue(canalText, canalText.contains("canal traffic rule"))
        assertTrue(canalText, !canalText.contains("entry restricted"))
        val apg = Note("resare", 5, box, "Bbox", null, "Aberdeen Proving Ground",
            listOf("entry restricted"), "33 CFR 334.140", null, null, null)
        val apgText = apg.describe()!!
        assertTrue(apgText, apgText.contains("entry restricted"))
        assertTrue(apgText, apgText.contains("Firing danger zone"))
        assertTrue(apgText, apgText.contains("south of Pooles Island"))
        val cable = Note("cable", 5, box, "Bbox", null, "overhead cable", emptyList(), null, 16.7, null, null)
        val cableText = cable.describe()!!
        assertTrue(cableText, cableText.contains("54.8"))
        assertTrue(cableText, !cableText.contains("check"))
        val swing = Note("bridge", 5, box, "Bbox", null, "Bear Creek", emptyList(), null, null, "swing", 2.4)
        val mustOpen = swing.describe(10.0)!!
        assertTrue(mustOpen, mustOpen.contains("has to open"))
        assertTrue(mustOpen, mustOpen.contains("8 ft"))
        val bascule = Note("bridge", 5, box, "Bbox", null, "Bear Creek", emptyList(), null, null, "bascule", 7.6)
        val clears = bascule.describe(10.0)!!
        assertTrue(clears, clears.contains("clears when closed"))
        assertTrue(clears, clears.contains("25 ft"))
    }
}
