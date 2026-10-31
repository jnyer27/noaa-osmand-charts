package com.jnyer27.noaaroute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import kotlin.math.cos

/**
 * Plane-to-colour mapping for the Route data overlay. Depth classes go through
 * Planner.classify, and the pack round-trip reads cells with Pack.tileData.
 */
class RouteOverlayTest {
    private val depthM = 3.0 * Pack.FT // 3 ft safe depth
    private val airM = 10.0 * Pack.FT // 10 ft air draft
    private val depare = DepthRange(0.0, 1.8, false) // 0-6 ft, straddles 3 ft
    private val deep = DepthRange(1.8, 5.0, false) // 6 ft and deeper
    private val drying = DepthRange(-0.5, 0.0, false)
    private val tooShallow = DepthRange(0.0, 0.5, false)
    private val dredgedDeep = DepthRange(2.0, 5.0, true)
    private val dredgedShoal = DepthRange(0.3, 1.0, true)
    private val fairwayDeep = DepthRange(2.0, 5.0, false, true)
    private val fairwayShoal = DepthRange(0.0, 1.8, false, true)
    private val fairwayDry = DepthRange(0.0, 0.4, false, true)
    private val table = listOf(null, depare, deep, drying, tooShallow, dredgedDeep, dredgedShoal, fairwayDeep, fairwayShoal, fairwayDry)

    private fun kind(
        depth: Int = 2, hazard: Int = 0, sound: Int = 0, flags: Int = 0, span: Int = 0, cable: Int = 0,
        depthMeters: Double = depthM, air: Double? = airM,
    ) = RouteOverlay.kind(depth, hazard, sound, flags, span, cable, table, depthMeters, air)

    private fun noDash(s: String) = assertFalse(s, s.contains("\u2014"))

    @Test
    fun landAndNoDataStayClear() {
        assertEquals(RouteOverlay.Kind.NONE, kind(depth = 0))
        assertEquals(0, RouteOverlay.argb(RouteOverlay.Kind.NONE, 0, 0))
        assertEquals(RouteOverlay.Kind.NONE, RouteOverlay.depthKind(null, depthM))
        assertEquals(Planner.BLOCKED, Planner.classify(null, depthM - Planner.ROUNDING_M))
    }

    @Test
    fun depthClassesFollowThePlanner() {
        assertEquals(RouteOverlay.Kind.MARGINAL, kind(depth = 1))
        assertEquals(Planner.MARGINAL, Planner.classify(depare, depthM - Planner.ROUNDING_M))
        assertEquals(RouteOverlay.Kind.SAFE, kind(depth = 2))
        assertEquals(RouteOverlay.Kind.SHALLOW, kind(depth = 3)) // drying, d1 < 0
        assertEquals(RouteOverlay.Kind.SHALLOW, kind(depth = 4)) // whole range under the safe depth
        assertEquals(Planner.SHALLOW, Planner.classify(drying, depthM - Planner.ROUNDING_M))
        assertEquals(Planner.SHALLOW, Planner.classify(tooShallow, depthM - Planner.ROUNDING_M))
        // 6-12 ft at 8 ft straddles; at 3 ft it is deep enough.
        val eight = 8.0 * Pack.FT
        assertEquals(RouteOverlay.Kind.MARGINAL, kind(depth = 2, depthMeters = eight))
        assertEquals(Planner.MARGINAL, Planner.classify(deep, eight - Planner.ROUNDING_M))
    }

    @Test
    fun dredgedAndFairwayStayDistinct() {
        assertEquals(RouteOverlay.Kind.DREDGED, kind(depth = 5))
        // A dredged area shallower than the boat is marginal, never a wall. Planner.classify says so.
        assertEquals(RouteOverlay.Kind.DREDGED_MARGINAL, kind(depth = 6))
        assertEquals(Planner.MARGINAL, Planner.classify(dredgedShoal, depthM - Planner.ROUNDING_M))
        assertEquals(RouteOverlay.Kind.FAIRWAY, kind(depth = 7))
        assertEquals(RouteOverlay.Kind.FAIRWAY_MARGINAL, kind(depth = 8))
        // A fairway whose whole range is under the safe depth is shallow water, not a channel.
        assertEquals(RouteOverlay.Kind.SHALLOW, kind(depth = 9))
        val colours = listOf(
            RouteOverlay.Kind.SAFE, RouteOverlay.Kind.DREDGED, RouteOverlay.Kind.FAIRWAY,
            RouteOverlay.Kind.MARGINAL, RouteOverlay.Kind.DREDGED_MARGINAL, RouteOverlay.Kind.FAIRWAY_MARGINAL,
            RouteOverlay.Kind.SHALLOW,
        ).map { RouteOverlay.argb(it, 0, 0) }
        assertEquals(colours.size, colours.toSet().size)
        assertTrue((RouteOverlay.argb(RouteOverlay.Kind.SAFE, 0, 0) ushr 24) < 80)
        assertTrue((RouteOverlay.argb(RouteOverlay.Kind.SHALLOW, 0, 0) ushr 24) > 100)
        assertTrue((RouteOverlay.argb(RouteOverlay.Kind.DREDGED, 0, 0) ushr 24) > 100)
    }

    @Test
    fun hazardsAreRedDotsAndPiersAreDark() {
        // 0.5 m obstruction: stored as 1 + round(dm) = 6. Under 3 ft plus 1 ft.
        assertEquals(RouteOverlay.Kind.HAZARD, kind(hazard = 6))
        // Unknown depth is stored as 1 and is a hazard for any boat.
        assertEquals(RouteOverlay.Kind.HAZARD, kind(hazard = 1))
        // 1.3 m is deeper than 3 ft + 1 ft (1.22 m), so the planner does not treat it as a wall.
        assertEquals(RouteOverlay.Kind.SAFE, kind(depth = 2, hazard = 14))
        assertEquals(RouteOverlay.Kind.HAZARD, kind(depth = 2, hazard = 13))
        // A pier line is not an obstruction, and a structure blocks as a pier.
        assertEquals(RouteOverlay.Kind.PIER, kind(hazard = Pack.HZ_PIER))
        assertEquals(RouteOverlay.Kind.PIER, kind(flags = Pack.F_STRUCT))
        assertNotEquals(RouteOverlay.argb(RouteOverlay.Kind.HAZARD, 1, 1), RouteOverlay.argb(RouteOverlay.Kind.SHALLOW, 1, 1))
        assertNotEquals(RouteOverlay.argb(RouteOverlay.Kind.PIER, 0, 0), RouteOverlay.argb(RouteOverlay.Kind.HAZARD, 0, 0))
        // The hazard wins over the depth fill, so the dot shows on shallow water too.
        assertEquals(RouteOverlay.Kind.HAZARD, kind(depth = 4, hazard = 6))
        assertEquals(RouteOverlay.Kind.PIER, kind(depth = 4, hazard = 6, flags = Pack.F_STRUCT))
    }

    @Test
    fun soundingsUseThePlannersRounding() {
        // 2.5 ft is under 3 ft. 3.0 ft, stored as 1 + round(9.144 dm), is not.
        assertEquals(RouteOverlay.Kind.HAZARD, kind(sound = 9))
        assertEquals(RouteOverlay.Kind.SAFE, kind(depth = 2, sound = 10))
        assertEquals(RouteOverlay.Kind.HAZARD, kind(depth = 1, sound = 9))
    }

    @Test
    fun prohibitedAreasHatch() {
        assertEquals(RouteOverlay.Kind.PROHIBITED, kind(flags = Pack.F_PROHIB))
        assertNotEquals(0, RouteOverlay.argb(RouteOverlay.Kind.PROHIBITED, 0, 0))
        assertEquals(0, RouteOverlay.argb(RouteOverlay.Kind.PROHIBITED, 0, 1))
        assertEquals(0, RouteOverlay.argb(RouteOverlay.Kind.PROHIBITED, 1, 0))
        assertNotEquals(0, RouteOverlay.argb(RouteOverlay.Kind.PROHIBITED, 1, 1))
        assertNotEquals(0, RouteOverlay.argb(RouteOverlay.Kind.PROHIBITED, 4, 6))
        // A rock inside the area stays a red dot. The hatch does not cover it.
        assertEquals(RouteOverlay.Kind.HAZARD, kind(flags = Pack.F_PROHIB, hazard = 6))
        // Shallow water inside the area is still the hatch: entry is prohibited either way.
        assertEquals(RouteOverlay.Kind.PROHIBITED, kind(depth = 4, flags = Pack.F_PROHIB))
    }

    @Test
    fun bridgesAndCablesFollowAirDraft() {
        // A deck with no charted clearance is a wall with or without an air draft.
        assertEquals(RouteOverlay.Kind.LOW_BRIDGE, kind(flags = Pack.F_DECK))
        assertEquals(RouteOverlay.Kind.LOW_BRIDGE, kind(flags = Pack.F_DECK, air = null))
        // 10 m span (20 half-metre units) clears a 10 ft boat. 2 m does not.
        val spanFlags = Pack.F_SPAN or Pack.F_BRIDGE
        assertEquals(RouteOverlay.Kind.SAFE, kind(flags = spanFlags, span = 20))
        assertEquals(RouteOverlay.Kind.LOW_BRIDGE, kind(flags = spanFlags, span = 4))
        // Clearance equal to the air draft is enough, same as the planner (clr >= air).
        assertEquals(RouteOverlay.Kind.SAFE, kind(flags = spanFlags, span = 6, air = 3.0))
        assertEquals(RouteOverlay.Kind.LOW_BRIDGE, kind(flags = spanFlags, span = 6, air = 3.1))
        // The deck flag beside a span that clears is the opening, not a wall.
        assertEquals(RouteOverlay.Kind.SAFE, kind(flags = spanFlags or Pack.F_DECK, span = 20))
        // An opening bridge is passable even with a deck and no clearance.
        assertEquals(RouteOverlay.Kind.SAFE, kind(flags = Pack.F_DECK or Pack.F_OPENING))
        // Cable: unknown clearance (0) is too low when air draft is set, and ignored when it is not.
        assertEquals(RouteOverlay.Kind.LOW_CABLE, kind(flags = Pack.F_CABLE, cable = 0))
        assertEquals(RouteOverlay.Kind.SAFE, kind(flags = Pack.F_CABLE, cable = 0, air = null))
        assertEquals(RouteOverlay.Kind.LOW_CABLE, kind(flags = Pack.F_CABLE, cable = 4))
        assertEquals(RouteOverlay.Kind.SAFE, kind(flags = Pack.F_CABLE, cable = 40))
        assertEquals(RouteOverlay.Kind.SAFE, kind(flags = Pack.F_CABLE, cable = 6, air = 3.0))
        // A low bridge is drawn over a pier on the same cell.
        assertEquals(RouteOverlay.Kind.LOW_BRIDGE, kind(flags = Pack.F_DECK, hazard = Pack.HZ_PIER))
        assertNotEquals(
            RouteOverlay.argb(RouteOverlay.Kind.LOW_BRIDGE, 0, 0),
            RouteOverlay.argb(RouteOverlay.Kind.LOW_CABLE, 0, 0),
        )
    }

    @Test
    fun gatesAndSnagsAreMarkedAndAreNotHazards() {
        assertEquals(RouteOverlay.Kind.GATE, kind(cable = Pack.GATE_MARK))
        assertEquals(RouteOverlay.Kind.SNAG, kind(hazard = 1, cable = Pack.SNAG_MARK))
        // The same cell with the cable flag is a cable, not a mark. 254 half-metres is plenty of clearance.
        assertEquals(RouteOverlay.Kind.SAFE, kind(flags = Pack.F_CABLE, cable = Pack.GATE_MARK))
        assertEquals(RouteOverlay.Kind.GATE, kind(depth = 1, cable = Pack.GATE_MARK))
        assertNotEquals(RouteOverlay.argb(RouteOverlay.Kind.GATE, 2, 2), RouteOverlay.argb(RouteOverlay.Kind.SNAG, 2, 2))
        assertNotEquals(RouteOverlay.argb(RouteOverlay.Kind.SNAG, 2, 2), RouteOverlay.argb(RouteOverlay.Kind.HAZARD, 2, 2))
    }

    @Test
    fun kindInTileReadsPlaneMajorOrder() {
        val tile = 256
        val data = ByteArray(Pack.NPLANES * tile * tile)
        val i = 3 * tile + 4
        data[Pack.P_DEPTH * tile * tile + i] = 5
        data[Pack.P_HAZARD * tile * tile + i] = 6
        assertEquals(RouteOverlay.Kind.HAZARD, RouteOverlay.kindInTile(data, 3, 4, tile, table, depthM, airM))
        assertEquals(RouteOverlay.Kind.NONE, RouteOverlay.kindInTile(data, 3, 5, tile, table, depthM, airM))
    }

    @Test
    fun coarsePixelKeepsTheHazard() {
        val tile = RouteOverlay.PIX
        val data = ByteArray(Pack.NPLANES * tile * tile)
        for (i in 0 until tile * tile) data[i] = 2 // deep enough
        data[Pack.P_HAZARD * tile * tile] = 6 // cell 0,0
        val pixels = IntArray(tile * tile)
        val n = RouteOverlay.paintBlock(2, tile, { tx, ty ->
            if (tx == 0 && ty == 0) RouteOverlay.TilePlanes(data, table) else null
        }, depthM, airM, pixels)
        assertTrue(n > 1000)
        assertEquals(RouteOverlay.argb(RouteOverlay.Kind.HAZARD, 0, 0), pixels[0])
        assertEquals(RouteOverlay.argb(RouteOverlay.Kind.SAFE, 0, 1), pixels[1])
    }

    @Test
    fun oneTileColoursQuickly() {
        val tile = RouteOverlay.PIX
        val data = ByteArray(Pack.NPLANES * tile * tile)
        for (i in 0 until tile * tile) data[Pack.P_DEPTH * tile * tile + i] = 1
        for (i in 0 until 200) data[Pack.P_HAZARD * tile * tile + i * 17] = 6
        val pixels = IntArray(tile * tile)
        val t0 = System.nanoTime()
        val n = RouteOverlay.paintBlock(1, tile, { _, _ -> RouteOverlay.TilePlanes(data, table) }, depthM, airM, pixels)
        val ms = (System.nanoTime() - t0) / 1e6
        println("ROUTE_OVERLAY_TILE_MS %.1f drawn %d".format(ms, n))
        assertTrue(n > 60000)
        assertTrue("one tile took %.0f ms".format(ms), ms < 2000)
        // A zoomed-out block reads every cell of 16 x 16 tiles. The cap keeps this off the common path.
        val t1 = System.nanoTime()
        val coarse = RouteOverlay.paintBlock(16, tile, { _, _ -> RouteOverlay.TilePlanes(data, table) }, depthM, null, pixels)
        val coarseMs = (System.nanoTime() - t1) / 1e6
        println("ROUTE_OVERLAY_COARSE_MS %.1f drawn %d".format(coarseMs, coarse))
        assertTrue(coarse > 0)
        assertTrue("coarse block took %.0f ms".format(coarseMs), coarseMs < 8000)
    }

    @Test
    fun zoomedOutViewStaysUnderTheImageCap() {
        val tiles = ArrayList<Pair<Int, Int>>()
        for (y in 0 until 40) for (x in 0 until 40) tiles.add(x to y)
        val blocks = RouteOverlay.chooseBlocks(tiles)
        assertTrue(blocks.size <= RouteOverlay.MAX_IMAGES)
        assertTrue(blocks.all { it.stride >= 8 })
        assertEquals(0, blocks[0].gtx % blocks[0].stride)
        assertEquals(0, blocks[0].gty % blocks[0].stride)
    }

    @Test
    fun globalTileMatchesThePackGrid() {
        val dlat = 20.0 / Pack.M_PER_DEG
        val dlon = 20.0 / (Pack.M_PER_DEG * cos(Math.toRadians(38.0)))
        val lat = 39.32371
        val lon = -76.39998
        val (gtx, gty) = RouteOverlay.globalTile(lat, lon, dlat, dlon, 256)
        val b = RouteOverlay.tileBounds(gtx, gty, dlat, dlon, 256)
        assertTrue(lon >= b[0] && lon < b[2])
        assertTrue(lat > b[1] && lat <= b[3])
        val inside = RouteOverlay.tilesInView(
            b[0] + dlon, b[1] + dlat, b[2] - dlon, b[3] - dlat, dlat, dlon, 256,
        ) { x, y -> true }
        assertEquals(listOf(gtx to gty), inside)
        assertTrue(RouteOverlay.packCoversTile(gtx, gty, 512, 512, 256, gtx + 1, gty + 1))
        assertFalse(RouteOverlay.packCoversTile(gtx, gty, 512, 512, 256, gtx + 2, gty))
        assertNull(RouteOverlay.tilesInView(-180.0, -80.0, 180.0, 80.0, dlat, dlon, 256) { _, _ -> true })
    }

    @Test
    fun packReaderAgreesWithTheColouring() {
        val dlat = 20.0 / Pack.M_PER_DEG
        val dlon = 20.0 / (Pack.M_PER_DEG * cos(Math.toRadians(38.0)))
        val gtx0 = Math.floor((-76.5 + 180) / (256 * dlon)).toInt()
        val gty0 = Math.floor((90 - 39.3) / (256 * dlat)).toInt()
        val west = -180 + gtx0 * 256 * dlon
        val north = 90 - gty0 * 256 * dlat
        val file = writeOneTile(gtx0, gty0, west, north, dlat, dlon)
        Pack(file).use { pack ->
            val lat = pack.north - 10.5 * pack.dlat
            val lon = pack.west + 20.5 * pack.dlon
            val (gtx, gty) = RouteOverlay.globalTile(lat, lon, pack.dlat, pack.dlon, pack.tile)
            assertEquals(pack.tx0, gtx)
            assertEquals(pack.ty0, gty)
            val b = RouteOverlay.tileBounds(gtx, gty, pack.dlat, pack.dlon, pack.tile)
            assertEquals(pack.west, b[0], 1e-9)
            assertEquals(pack.north, b[3], 1e-9)
            val data = pack.tileData(0, 0)!!
            val depth = 3.0 * Pack.FT
            val air = 10.0 * Pack.FT
            assertEquals(RouteOverlay.Kind.MARGINAL, RouteOverlay.kindInTile(data, 10, 20, 256, pack.depthTable, depth, air))
            assertEquals(RouteOverlay.Kind.DREDGED, RouteOverlay.kindInTile(data, 11, 20, 256, pack.depthTable, depth, air))
            assertEquals(RouteOverlay.Kind.FAIRWAY, RouteOverlay.kindInTile(data, 12, 20, 256, pack.depthTable, depth, air))
            assertEquals(RouteOverlay.Kind.HAZARD, RouteOverlay.kindInTile(data, 13, 20, 256, pack.depthTable, depth, air))
            assertEquals(RouteOverlay.Kind.PIER, RouteOverlay.kindInTile(data, 14, 20, 256, pack.depthTable, depth, air))
            assertEquals(RouteOverlay.Kind.LOW_BRIDGE, RouteOverlay.kindInTile(data, 15, 20, 256, pack.depthTable, depth, air))
            assertEquals(RouteOverlay.Kind.LOW_CABLE, RouteOverlay.kindInTile(data, 16, 20, 256, pack.depthTable, depth, air))
            assertEquals(RouteOverlay.Kind.GATE, RouteOverlay.kindInTile(data, 17, 20, 256, pack.depthTable, depth, air))
            assertEquals(RouteOverlay.Kind.SNAG, RouteOverlay.kindInTile(data, 18, 20, 256, pack.depthTable, depth, air))
            assertEquals(RouteOverlay.Kind.PROHIBITED, RouteOverlay.kindInTile(data, 19, 20, 256, pack.depthTable, depth, air))
            assertEquals(RouteOverlay.Kind.NONE, RouteOverlay.kindInTile(data, 0, 0, 256, pack.depthTable, depth, air))
        }
    }

    @Test
    fun legendNamesTheColoursAndTheMissingPack() {
        val text = RouteOverlay.legend(3.0, 10.0, 8, 0.32, 1)
        noDash(text)
        noDash(RouteOverlay.NO_PACK)
        noDash(RouteOverlay.NO_PACK_NONE)
        noDash(RouteOverlay.NOTHING)
        noDash(RouteOverlay.DRAWING)
        assertTrue(text.contains("3 ft"))
        assertTrue(text.contains("10 ft"))
        assertTrue(text.contains("too shallow or drying"))
        assertTrue(text.contains("dredged"))
        assertTrue(text.contains("fairway"))
        assertTrue(text.contains("hazard"))
        assertTrue(text.contains("pier"))
        assertTrue(text.contains("prohibited"))
        assertTrue(text.contains("bridge too low"))
        assertTrue(text.contains("cable too low"))
        assertTrue(text.contains("wrong side of a mark"))
        assertTrue(text.contains("snag"))
        assertTrue(text.contains("land or no data"))
        assertTrue(RouteOverlay.NO_PACK.contains("no route pack downloaded for this view"))
        assertTrue(RouteOverlay.NO_PACK_NONE.contains("Download a region under Regions"))
        val noAir = RouteOverlay.legend(5.5, null, 1, 0.04, 4)
        assertTrue(noAir.contains("5.50 ft"))
        assertTrue(noAir.contains("Air draft is not set"))
        assertTrue(noAir.contains("4 by 4"))
        assertFalse(RouteOverlay.legend(3.0, null, 1, 0.1, 1).contains("Zoomed out"))
        assertTrue(RouteOverlay.legend(3.0, null, 1, 0.02, 1).contains("under 0.1 s"))
    }

    private fun writeOneTile(gtx0: Int, gty0: Int, west: Double, north: Double, dlat: Double, dlon: Double): File {
        val tile = 256
        val raw = ByteArray(Pack.NPLANES * tile * tile)
        fun put(plane: Int, row: Int, col: Int, value: Int) {
            raw[plane * tile * tile + row * tile + col] = value.toByte()
        }
        // Index 1 is 0-6 ft, 2 is dredged 2-5 m, 3 is a fairway at the same depths.
        put(Pack.P_DEPTH, 10, 20, 1)
        put(Pack.P_DEPTH, 11, 20, 2)
        put(Pack.P_DEPTH, 12, 20, 3)
        put(Pack.P_DEPTH, 13, 20, 2); put(Pack.P_HAZARD, 13, 20, 6)
        put(Pack.P_DEPTH, 14, 20, 2); put(Pack.P_HAZARD, 14, 20, Pack.HZ_PIER)
        put(Pack.P_DEPTH, 15, 20, 2); put(Pack.P_FLAGS, 15, 20, Pack.F_DECK)
        put(Pack.P_DEPTH, 16, 20, 2); put(Pack.P_FLAGS, 16, 20, Pack.F_CABLE); put(Pack.P_CABLECLR, 16, 20, 4)
        put(Pack.P_DEPTH, 17, 20, 2); put(Pack.P_CABLECLR, 17, 20, Pack.GATE_MARK)
        put(Pack.P_DEPTH, 18, 20, 2); put(Pack.P_HAZARD, 18, 20, 1); put(Pack.P_CABLECLR, 18, 20, Pack.SNAG_MARK)
        put(Pack.P_DEPTH, 19, 20, 2); put(Pack.P_FLAGS, 19, 20, Pack.F_PROHIB)
        val header = """{"format":"NRPK","version":2,"created":"2026-01-01T00:00:00Z","states":["TEST"],"area":{"id":"t","name":"Test","bbox":[$west,${north - tile * dlat},${west + tile * dlon},$north]},"tx0":$gtx0,"ty0":$gty0,"west":$west,"north":$north,"dlat":$dlat,"dlon":$dlon,"res":20.0,"width":$tile,"height":$tile,"tile":$tile,"planes":6,"depth_table":[null,[0.0,1.8,0],[2.0,5.0,1],[2.0,5.0,2]],"notes":[]}"""
        val hb = header.toByteArray()
        val comp = deflate(raw)
        val out = ByteArrayOutputStream()
        out.write("NRPK2\n".toByteArray())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(hb.size).array())
        out.write(hb)
        val pos = 6L + 4 + hb.size + 12
        out.write(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(pos).putInt(comp.size).array())
        out.write(comp)
        val f = File.createTempFile("overlay-pack", ".bin")
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
}
