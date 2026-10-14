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

    private fun writePack(planes: Array<ByteArray>, notes: String, onlyTileRow: Int? = null): File {
        val rows = if (onlyTileRow == null) 0 until 2 else onlyTileRow..onlyTileRow
        val pNorth = north - rows.first * 256 * dlat
        val header = """{"format":"NRPK","version":2,"created":"2026-01-01T00:00:00Z","states":["TEST"],
            "area":{"id":"t${onlyTileRow ?: ""}","name":"Test ${onlyTileRow ?: ""}","bbox":[$west,${north - size * dlat},${west + size * dlon},$north]},
            "tx0":$gtx0,"ty0":${gty0 + rows.first},
            "west":$west,"north":$pNorth,"dlat":$dlat,"dlon":$dlon,"res":$res,"width":$size,"height":${rows.count() * 256},
            "tile":256,"planes":6,"depth_table":[null,[2.0,3.6,0]],
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
}
