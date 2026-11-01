package com.jnyer27.noaaroute

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh

/**
 * Warnings for a finished route against the depth areas drawn on the in-app chart
 * (DEPARE and DRGARE in the region's vector tiles). The planner's grid stores the
 * depth at each 20 m cell center, so a leg can run through a charted shoal that
 * sits in the fringe of a cell whose center is deeper water. This check follows
 * the leg line itself.
 *
 * Pure Kotlin. [ChartDepthSource] reads the mbtiles on the phone and calls [annotate].
 * No chart, or a leg outside every chart, adds nothing.
 */
object ChartDepth {
    /** Sample step along a leg, in meters. */
    private const val STEP_M = 5.0
    /** Shorter than this is a shared edge or one sample, not a crossing to report. */
    private const val MIN_M = 10.0
    private val LAYERS = setOf("depare", "drgare")

    /** One depth area, rings in longitude/latitude. Even-odd fill, so a hole ring counts. */
    class Area(val rings: List<List<LatLon>>, val d1: Int, val d2: Int) {
        val west: Double
        val south: Double
        val east: Double
        val north: Double

        init {
            var w = Double.POSITIVE_INFINITY
            var s = Double.POSITIVE_INFINITY
            var e = Double.NEGATIVE_INFINITY
            var n = Double.NEGATIVE_INFINITY
            for (ring in rings) for (p in ring) {
                w = min(w, p.lon); e = max(e, p.lon)
                s = min(s, p.lat); n = max(n, p.lat)
            }
            west = w; south = s; east = e; north = n
        }

        fun contains(p: LatLon): Boolean {
            if (p.lon < west || p.lon > east || p.lat < south || p.lat > north) return false
            var odd = false
            for (ring in rings) {
                if (ring.size < 3) continue
                var j = ring.size - 1
                for (i in ring.indices) {
                    val a = ring[i]
                    val b = ring[j]
                    if ((a.lat > p.lat) != (b.lat > p.lat)) {
                        val x = (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon
                        if (p.lon < x) odd = !odd
                    }
                    j = i
                }
            }
            return odd
        }
    }

    /**
     * Depth areas from one chart file. [bounds] is west, south, east, north.
     * [span] is the bounds area, so a tighter chart wins where two overlap.
     */
    class Cover(val bounds: DoubleArray, val span: Double, val areas: List<Area>) {
        fun contains(p: LatLon) = p.lon in bounds[0]..bounds[2] && p.lat in bounds[1]..bounds[3]
    }

    /** The route with a chart-crossing note on each leg that needs one. */
    fun annotate(route: Route, covers: List<Cover>): Route {
        if (covers.isEmpty() || route.points.size < 2) return route
        val n = min(route.legs.size, route.points.size - 1)
        if (n == 0) return route
        var changed = false
        val legs = ArrayList<Leg>(route.legs.size)
        for (i in route.legs.indices) {
            val leg = route.legs[i]
            if (i >= n) { legs.add(leg); continue }
            val extra = notes(route.points[i], route.points[i + 1], route.depthFt, covers)
            if (extra.isEmpty()) legs.add(leg)
            else { changed = true; legs.add(Leg(leg.distM, leg.bearing, leg.shoalM, leg.lowM, leg.notes + extra)) }
        }
        if (!changed) return route
        return Route(route.name, route.points, legs, route.depthFt, route.airFt, route.resM, route.messages, route.guideM)
    }

    /**
     * Notes for the straight leg from [a] to [b]. Empty when no chart covers it,
     * or the line stays in water whose shoal limit is at or over [depthFt].
     * An area whose deep limit is also under [depthFt] is named on its own,
     * ahead of an area that only straddles it.
     */
    fun notes(a: LatLon, b: LatLon, depthFt: Double, covers: List<Cover>): List<String> {
        if (covers.isEmpty()) return emptyList()
        val dist = Planner.distanceM(a, b)
        if (dist < 1.0) return emptyList()
        val steps = max(1, ceil(dist / STEP_M).toInt())
        var under = 0
        var straddle = 0
        for (k in 0..steps) {
            val t = k.toDouble() / steps
            val p = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            when (kindAt(p, depthFt, covers)) {
                UNDER -> under++
                STRADDLE -> straddle++
            }
        }
        val out = ArrayList<String>(2)
        val depth = ChartTiles.depthText(depthFt)
        val underM = under * dist / (steps + 1)
        val straddleM = straddle * dist / (steps + 1)
        if (underM >= MIN_M)
            out.add("crosses %.0f m charted entirely under your %s ft: check".format(underM, depth))
        if (straddleM >= MIN_M)
            out.add("crosses %.0f m of a depth area that straddles your %s ft: check".format(straddleM, depth))
        return out
    }

    private const val UNDER = 2
    private const val STRADDLE = 1

    /** Strongest charted class at [p]: entirely under the safe depth, else straddling it. */
    private fun kindAt(p: LatLon, depthFt: Double, covers: List<Cover>): Int {
        var bestSpan = Double.POSITIVE_INFINITY
        var cover: Cover? = null
        for (c in covers) if (c.contains(p) && c.span < bestSpan) { bestSpan = c.span; cover = c }
        val areas = cover?.areas ?: return 0
        var kind = 0
        for (area in areas) {
            if (p.lon < area.west || p.lon > area.east || p.lat < area.south || p.lat > area.north) continue
            val k = when {
                area.d2 < depthFt -> UNDER
                area.d1 < depthFt -> STRADDLE
                else -> continue
            }
            if (k > kind && area.contains(p)) kind = k
            if (kind == UNDER) return UNDER
        }
        return kind
    }

    /**
     * Depth areas in one Mapbox vector tile. [x] and [y] are XYZ (y grows south).
     * The bytes may be gzipped, as MBTiles stores them. Unknown layers are ignored.
     * A tile that does not decode yields an empty list.
     */
    fun areasInTile(tile: ByteArray, z: Int, x: Int, y: Int): List<Area> {
        val raw = try { ungzip(tile) } catch (_: Exception) { return emptyList() }
        return try { decode(raw, z, x, y) } catch (_: Exception) { emptyList() }
    }

    private fun ungzip(b: ByteArray): ByteArray {
        if (b.size >= 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte())
            return GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
        return b
    }

    private fun decode(raw: ByteArray, z: Int, x: Int, y: Int): List<Area> {
        val out = ArrayList<Area>()
        val tile = Proto(raw)
        while (tile.more()) {
            val (field, wire) = tile.key()
            if (field == 3 && wire == 2) decodeLayer(tile.message(), z, x, y, out)
            else tile.skip(wire)
        }
        return out
    }

    private fun decodeLayer(layer: Proto, z: Int, x: Int, y: Int, out: MutableList<Area>) {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Double?>()
        val features = ArrayList<ByteArray>()
        while (layer.more()) {
            val (field, wire) = layer.key()
            when {
                field == 1 && wire == 2 -> name = layer.text()
                field == 2 && wire == 2 -> features.add(layer.bytes())
                field == 3 && wire == 2 -> keys.add(layer.text())
                field == 4 && wire == 2 -> values.add(value(Proto(layer.bytes())))
                field == 5 && wire == 0 -> extent = layer.varint().toInt().coerceAtLeast(1)
                else -> layer.skip(wire)
            }
        }
        if (name !in LAYERS || extent <= 0) return
        for (bytes in features) {
            val feat = Proto(bytes)
            var type = 0
            var tags = IntArray(0)
            var geom = IntArray(0)
            while (feat.more()) {
                val (field, wire) = feat.key()
                when {
                    field == 2 && wire == 2 -> tags = packed(feat.bytes())
                    field == 3 && wire == 0 -> type = feat.varint().toInt()
                    field == 4 && wire == 2 -> geom = packed(feat.bytes())
                    else -> feat.skip(wire)
                }
            }
            if (type != 3 || geom.isEmpty()) continue // 3 = polygon
            var d1: Int? = null
            var d2: Int? = null
            var t = 0
            while (t + 1 < tags.size) {
                val key = keys.getOrNull(tags[t])
                val value = values.getOrNull(tags[t + 1])
                if (key == "d1" && value != null) d1 = value.toInt()
                if (key == "d2" && value != null) d2 = value.toInt()
                t += 2
            }
            if (d1 == null || d2 == null) continue
            val rings = rings(geom, z, x, y, extent)
            if (rings.any { it.size >= 3 }) out.add(Area(rings, d1, d2))
        }
    }

    /** Polygon rings of one feature, tile pixels converted to longitude and latitude. */
    private fun rings(geom: IntArray, z: Int, x: Int, y: Int, extent: Int): List<List<LatLon>> {
        val rings = ArrayList<ArrayList<LatLon>>()
        var cx = 0
        var cy = 0
        var i = 0
        while (i < geom.size) {
            val cmd = geom[i++]
            val op = cmd and 7
            val count = cmd ushr 3
            when (op) {
                1 -> for (n in 0 until count) { // MoveTo
                    if (i + 1 >= geom.size) return rings
                    cx += zig(geom[i++]); cy += zig(geom[i++])
                    rings.add(arrayListOf(latlon(z, x, y, cx, cy, extent)))
                }
                2 -> { // LineTo
                    val ring = rings.lastOrNull() ?: return rings
                    for (n in 0 until count) {
                        if (i + 1 >= geom.size) return rings
                        cx += zig(geom[i++]); cy += zig(geom[i++])
                        ring.add(latlon(z, x, y, cx, cy, extent))
                    }
                }
                7 -> {} // ClosePath
                else -> return rings
            }
        }
        return rings
    }

    private fun zig(n: Int): Int = (n ushr 1) xor -(n and 1)

    private fun latlon(z: Int, x: Int, y: Int, px: Int, py: Int, extent: Int): LatLon {
        val n = (1L shl z).toDouble()
        val xf = x + px.toDouble() / extent
        val yf = y + py.toDouble() / extent
        val lon = xf / n * 360.0 - 180.0
        val merc = Math.PI * (1.0 - 2.0 * yf / n)
        val lat = Math.toDegrees(atan(sinh(merc)))
        return LatLon(lat, lon)
    }

    private fun value(msg: Proto): Double? {
        var out: Double? = null
        while (msg.more()) {
            val (field, wire) = msg.key()
            out = when {
                field == 4 && wire == 0 -> msg.varint().toDouble() // int64
                field == 5 && wire == 0 -> msg.varint().toDouble() // uint64, what the tile builder writes
                field == 6 && wire == 0 -> { // sint64, zigzag
                    val n = msg.varint()
                    ((n ushr 1) xor -(n and 1)).toDouble()
                }
                field == 3 && wire == 1 -> msg.f64()
                field == 2 && wire == 5 -> msg.f32().toDouble()
                else -> { msg.skip(wire); out }
            }
        }
        return out
    }

    private fun packed(bytes: ByteArray): IntArray {
        val msg = Proto(bytes)
        val out = ArrayList<Int>()
        while (msg.more()) out.add(msg.varint().toInt())
        return out.toIntArray()
    }

    /** A cursor over one protobuf message. */
    private class Proto(val b: ByteArray) {
        var i = 0
        fun more() = i < b.size
        fun key(): Pair<Int, Int> {
            val k = varint().toInt()
            return (k ushr 3) to (k and 7)
        }
        fun varint(): Long {
            var s = 0
            var x = 0L
            var guard = 0
            while (guard++ < 10 && i < b.size) {
                val v = b[i++].toInt() and 0xff
                x = x or ((v and 0x7f).toLong() shl s)
                if (v and 0x80 == 0) return x
                s += 7
            }
            return x
        }
        fun bytes(): ByteArray {
            val n = varint().toInt()
            val start = i
            i = min(b.size, i + n.coerceAtLeast(0))
            return b.copyOfRange(start, i)
        }
        fun message() = Proto(bytes())
        fun text(): String = bytes().toString(Charsets.UTF_8)
        fun f64(): Double {
            val bits = java.nio.ByteBuffer.wrap(b, i, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
            i += 8
            return Double.fromBits(bits)
        }
        fun f32(): Float {
            val bits = java.nio.ByteBuffer.wrap(b, i, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            i += 4
            return Float.fromBits(bits)
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> i += 8
                2 -> bytes()
                5 -> i += 4
                else -> i = b.size
            }
        }
    }
}
