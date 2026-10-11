package com.jnyer27.noaaroute

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One depth-area range from the pack's depth table (meters, null = not charted). */
class DepthRange(val d1: Double?, val d2: Double?, val dredged: Boolean)

/** A named feature used only for the per-leg report (restricted area, bridge, cable). */
class Note(
    val kind: String, val band: Int, val bbox: DoubleArray, val type: String, val coords: Any?,
    val name: String, val rules: List<String>, val cfr: String?, val clr: Double?,
    val opening: String?, val closed: Double?,
) {
    /** True if (lat, lon) lies inside, or within [tolM] meters of, this feature. */
    fun near(lat: Double, lon: Double, tolM: Double): Boolean {
        val kx = Pack.M_PER_DEG * cos(Math.toRadians(lat))
        val ky = Pack.M_PER_DEG
        val ex = tolM / kx
        val ey = tolM / ky
        if (lon < bbox[0] - ex || lon > bbox[2] + ex || lat < bbox[1] - ey || lat > bbox[3] + ey) return false
        val px = lon * kx
        val py = lat * ky
        fun pt(o: Any?): DoubleArray {
            val l = o as List<*>
            return doubleArrayOf((l[0] as Double) * kx, (l[1] as Double) * ky)
        }
        fun segDist(a: DoubleArray, b: DoubleArray): Double {
            val dx = b[0] - a[0]
            val dy = b[1] - a[1]
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (((px - a[0]) * dx + (py - a[1]) * dy) / l2).coerceIn(0.0, 1.0)
            return hypot(px - a[0] - t * dx, py - a[1] - t * dy)
        }
        fun lineNear(line: List<*>): Boolean {
            if (line.size == 1) return hypot(pt(line[0])[0] - px, pt(line[0])[1] - py) <= tolM
            for (k in 0 until line.size - 1) if (segDist(pt(line[k]), pt(line[k + 1])) <= tolM) return true
            return false
        }
        fun ringInside(ring: List<*>): Boolean {
            var inside = false
            var j = ring.size - 1
            for (k in ring.indices) {
                val a = pt(ring[k])
                val b = pt(ring[j])
                if ((a[1] > py) != (b[1] > py) && px < (b[0] - a[0]) * (py - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside
                j = k
            }
            return inside
        }
        fun polyNear(poly: List<*>): Boolean {
            val outer = poly.firstOrNull() as? List<*> ?: return false
            return ringInside(outer) || poly.any { lineNear(it as List<*>) }
        }
        val c = coords ?: return true // no geometry kept: bbox test only
        return when (type) {
            "Point" -> hypot(pt(c)[0] - px, pt(c)[1] - py) <= tolM
            "MultiPoint", "LineString" -> lineNear(c as List<*>)
            "MultiLineString" -> (c as List<*>).any { lineNear(it as List<*>) }
            "Polygon" -> polyNear(c as List<*>)
            "MultiPolygon" -> (c as List<*>).any { polyNear(it as List<*>) }
            else -> true
        }
    }

    fun describe(): String? {
        fun ft(m: Double) = "%.0f ft".format(m / Pack.FT)
        return when (kind) {
            "resare" -> "$name: ${rules.joinToString(", ")}" + (cfr?.let { " ($it)" } ?: "")
            "bridge" -> when {
                opening != null -> "$name: $opening bridge, may need an opening" + (closed?.let { " (closed clearance ${ft(it)})" } ?: "")
                clr != null -> "$name span (clearance ${ft(clr)})"
                else -> null // bridge part without clearance: blocks, nothing to report
            }
            "cable" -> name + (clr?.let { " (clearance ${ft(it)})" } ?: " (clearance not charted)")
            else -> null
        }
    }
}

/**
 * Reader for the NRPK1 route pack written by tools/build_route_pack.py: a
 * constant-degree grid in 256 x 256 tiles of 6 byte planes each.
 */
class Pack(file: File) : AutoCloseable {
    companion object {
        const val M_PER_DEG = 111320.0
        const val FT = 0.3048
        const val NPLANES = 6
        const val P_DEPTH = 0
        const val P_HAZARD = 1
        const val P_SOUND = 2
        const val P_FLAGS = 3
        const val P_SPANCLR = 4
        const val P_CABLECLR = 5
        const val F_STRUCT = 1
        const val F_PROHIB = 2
        const val F_DECK = 4
        const val F_SPAN = 8
        const val F_OPENING = 16
        const val F_CABLE = 32
        const val F_RESNOTE = 64
        const val F_BRIDGE = 128
    }

    private val raf = RandomAccessFile(file, "r")
    val created: String
    val states: List<String>
    val west: Double
    val north: Double
    val dlat: Double
    val dlon: Double
    val res: Double
    val width: Int
    val height: Int
    val tile: Int
    val depthTable: List<DepthRange?>
    val notes: List<Note>
    private val tileOff: LongArray
    private val tileLen: IntArray
    private val tilesX: Int

    init {
        val magic = ByteArray(6)
        raf.readFully(magic)
        require(String(magic) == "NRPK1\n") { "Not a route pack" }
        val hl = Integer.reverseBytes(raf.readInt())
        val hb = ByteArray(hl)
        raf.readFully(hb)
        @Suppress("UNCHECKED_CAST")
        val h = MiniJson.parse(String(hb, Charsets.UTF_8)) as Map<String, Any?>
        created = h["created"] as String
        states = (h["states"] as List<*>).map { it as String }
        west = h["west"] as Double
        north = h["north"] as Double
        dlat = h["dlat"] as Double
        dlon = h["dlon"] as Double
        res = h["res"] as Double
        width = (h["width"] as Double).toInt()
        height = (h["height"] as Double).toInt()
        tile = (h["tile"] as Double).toInt()
        require((h["planes"] as Double).toInt() == NPLANES) { "Unsupported pack planes" }
        depthTable = (h["depth_table"] as List<*>).map { e ->
            (e as? List<*>)?.let { DepthRange(it[0] as Double?, it[1] as Double?, (it[2] as Double).toInt() == 1) }
        }
        notes = (h["notes"] as List<*>).map { e ->
            val m = e as Map<*, *>
            val bb = (m["bbox"] as List<*>).map { it as Double }.toDoubleArray()
            Note(
                kind = m["kind"] as String, band = (m["band"] as Double).toInt(), bbox = bb,
                type = m["type"] as String, coords = m["coords"],
                name = (m["name"] as String?) ?: "", rules = (m["rules"] as List<*>?)?.map { it as String } ?: emptyList(),
                cfr = m["cfr"] as String?, clr = m["clr"] as Double?, opening = m["opening"] as String?,
                closed = m["closed"] as Double?,
            )
        }
        tilesX = width / tile
        val n = tilesX * (height / tile)
        tileOff = LongArray(n)
        tileLen = IntArray(n)
        val idx = ByteArray(n * 12)
        raf.readFully(idx)
        val bb = java.nio.ByteBuffer.wrap(idx).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (k in 0 until n) {
            tileOff[k] = bb.long
            tileLen[k] = bb.int
        }
    }

    val south get() = north - height * dlat
    val east get() = west + width * dlon

    fun covers(lat: Double, lon: Double) = lat in south..north && lon in west..east

    private val cache = object : LinkedHashMap<Int, ByteArray?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray?>) = size > 160
    }

    /** All 6 planes of one tile (plane-major), or null for an empty tile. */
    @Synchronized
    fun tileData(tx: Int, ty: Int): ByteArray? {
        val k = ty * tilesX + tx
        if (cache.containsKey(k)) return cache[k]
        val data = if (tileLen[k] == 0) null else {
            val comp = ByteArray(tileLen[k])
            raf.seek(tileOff[k])
            raf.readFully(comp)
            val out = ByteArray(NPLANES * tile * tile)
            val inf = Inflater()
            inf.setInput(comp)
            var got = 0
            while (got < out.size) {
                val r = inf.inflate(out, got, out.size - got)
                if (r == 0 && (inf.finished() || inf.needsInput())) break
                got += r
            }
            inf.end()
            require(got == out.size) { "Corrupt tile $tx,$ty" }
            out
        }
        cache[k] = data
        return data
    }

    /**
     * Copy a window of the pack (pack rows r0 until r0 + h*f, cols c0 until
     * c0 + w*f) into per-cell planes, combining f x f blocks: depth sampled at
     * the block center, hazards/soundings/clearances keep the minimum, flags OR.
     */
    fun window(r0: Int, c0: Int, h: Int, w: Int, f: Int): Array<ByteArray> {
        val out = Array(NPLANES) { ByteArray(h * w) }
        val minPlanes = intArrayOf(P_HAZARD, P_SOUND, P_SPANCLR, P_CABLECLR)
        val t = tile
        val half = f / 2
        val ty0 = max(0, r0 / t)
        val ty1 = min(height / t - 1, (r0 + h * f - 1) / t)
        val tx0 = max(0, c0 / t)
        val tx1 = min(tilesX - 1, (c0 + w * f - 1) / t)
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val d = tileData(tx, ty) ?: continue
            val prA = max(ty * t, r0)
            val prB = min((ty + 1) * t, r0 + h * f)
            val pcA = max(tx * t, c0)
            val pcB = min((tx + 1) * t, c0 + w * f)
            for (pr in prA until prB) {
                val wr = (pr - r0) / f
                val centerRow = (pr - r0) % f == half
                val rowBase = (pr - ty * t) * t
                for (pc in pcA until pcB) {
                    val wc = (pc - c0) / f
                    val i = wr * w + wc
                    val ti = rowBase + (pc - tx * t)
                    if (centerRow && (pc - c0) % f == half) out[P_DEPTH][i] = d[P_DEPTH * t * t + ti]
                    for (p in minPlanes) {
                        val v = d[p * t * t + ti].toInt() and 0xff
                        val cur = out[p][i].toInt() and 0xff
                        if (v != 0 && (cur == 0 || v < cur)) out[p][i] = v.toByte()
                    }
                    out[P_FLAGS][i] = (out[P_FLAGS][i].toInt() or d[P_FLAGS * t * t + ti].toInt()).toByte()
                }
            }
        }
        return out
    }

    override fun close() = raf.close()
}
