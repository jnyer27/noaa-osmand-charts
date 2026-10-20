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
            assertTrue("bridge span reported", r.legs.any { l -> l.notes.any { it.startsWith("Test Bridge span (clearance 33 ft)") } })
            val gpx = Gpx.write(r)
            assertTrue(gpx.contains("<rtept"))
            // The summary names the trip's ends and settings
            val text = Gpx.report(r, listOf("start: Home (1, 2)", "destination: 3, 4"), listOf("charts: Test"), 40.0)
            val lines = text.lines()
            assertTrue(text, lines[0].startsWith("t: "))
            assertTrue(text, lines[1] == "start: Home (1, 2)" && lines[2] == "destination: 3, 4")
            assertTrue(text, lines[3].startsWith("depth 3 ft (MLLW), air draft not checked, hazard buffer 40 m, grid"))
            assertTrue(text, lines[4] == "charts: Test")
            // Great Lakes packs: depths are charted to Low Water Datum, not MLLW
            val lake = Gpx.report(r, emptyList(), emptyList(), null, Pack.datumName(listOf(25)))
            assertTrue(lake, lake.lines()[1].startsWith("depth 3 ft (Low Water Datum), "))
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
    fun moveEndsStartsInWaterDeepEnough() {
        // Open water (2.0-3.6 m) with a cove of 0-1.8 m water (rows 100-160, cols 100-160),
        // open to the east: usable but flagged at 3 ft, like a marina in a 0-6 ft creek
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
            assertTrue("first leg flagged", r.legs[0].notes.firstOrNull()?.startsWith("connects your point") == true)
            assertTrue("no shallow legs after it", r.legs.drop(1).none { it.shoalM > 0 })
            val moved = Planner.distanceM(r.points[0], r.points[1])
            assertTrue("moved %.0f m".format(moved), moved in 180.0..260.0)
            assertTrue("message: ${r.messages}", r.messages.any { it.startsWith("Point 1 is in water charted under your 3 ft") })
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
            assertTrue("crosses the zone", r.legs.any { zone in it.notes })
            val res = planner.resolveChecks(r, pts)
            assertEquals(res.summary, 1, res.runs)
            assertEquals(res.summary, 1, res.resolved)
            val a = res.route
            assertTrue("no checks left", a.legs.none { Gpx.flagged(it) })
            assertEquals("same ends", pts.first(), a.points.first())
            assertEquals("same ends", pts.last(), a.points.last())
            val extra = (a.totalNm - r.totalNm) * Planner.NM
            assertTrue("small detour: $extra m", extra > 0 && extra <= max(Planner.RESOLVE_EXTRA_M, r.totalNm * Planner.NM * Planner.RESOLVE_EXTRA_FRAC))
            assertTrue(a.messages.last(), a.messages.last().startsWith("Avoid checks: 1 of 1 check stretches resolved (+"))
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
}
