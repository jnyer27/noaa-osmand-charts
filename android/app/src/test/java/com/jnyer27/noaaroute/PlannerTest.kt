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

class PlannerTest {
    private val res = 20.0
    private val north = 39.3
    private val west = -76.5
    private val dlat = res / Pack.M_PER_DEG
    private val dlon = res / (Pack.M_PER_DEG * Math.cos(Math.toRadians(39.29)))
    private val size = 512

    /**
     * Synthetic 512 x 512 pack: land everywhere except a 12-cell wide channel
     * (rows 100-411, cols 250-261) at 2.0-3.6 m. A bridge deck crosses it at
     * row 256 with a 2-cell span (cols 255-256, 10 m clearance). A rock (0.5 m)
     * sits in the channel at row 180, col 252.
     */
    private fun syntheticPack(): File {
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
        val header = """{"format":"NRPK","version":1,"created":"2026-01-01T00:00:00Z","states":["TEST"],
            "west":$west,"north":$north,"dlat":$dlat,"dlon":$dlon,"res":$res,"width":$size,"height":$size,
            "tile":256,"planes":6,"depth_table":[null,[2.0,3.6,0]],
            "notes":[{"kind":"bridge","band":5,"bbox":[${west + 255 * dlon},${bridgeLat - dlat},${west + 257 * dlon},${bridgeLat + dlat}],
            "type":"Bbox","coords":null,"name":"Test Bridge","clr":10.0}]}""".trimIndent().replace("\n", "")
        val hb = header.toByteArray()
        val tiles = ArrayList<ByteArray?>()
        for (ty in 0 until 2) for (tx in 0 until 2) {
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
        out.write("NRPK1\n".toByteArray())
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
            val r = Planner(pack).plan("t", listOf(cellCenter(120, 255), cellCenter(390, 255)), 3.0, null, bufM = 40.0)
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
            try {
                Planner(pack).plan("t", listOf(cellCenter(120, 255), cellCenter(390, 255)), 3.0, 40.0, bufM = 40.0)
                fail("expected no route")
            } catch (e: PlanException) {
                assertTrue(e.message, e.message!!.contains("bridge or overhead cable"))
            }
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
