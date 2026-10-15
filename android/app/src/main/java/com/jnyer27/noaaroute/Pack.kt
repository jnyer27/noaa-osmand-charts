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
 * Reader for one NRPK2 area pack written by tools/build_route_pack.py: part of
 * a global constant-degree grid in 256 x 256 tiles of 6 byte planes each.
 * Tiles are numbered globally from 90 N, 180 W, so packs of neighboring areas
 * line up and can be combined (see ChartSet).
 */
class Pack(file: File) : AutoCloseable {
    companion object {
        /**
         * The name depths are charted to, for the report and GPX: MLLW for tidal waters (12,
         * and packs from before the datum was recorded), Low Water Datum for the Great Lakes
         * (25, IGLD 1985), "chart datum" for a mix or anything else.
         */
        fun datumName(codes: Collection<Int>): String = when {
            codes.all { it == 12 } -> "MLLW"
            codes.all { it == 25 } -> "Low Water Datum"
            else -> "chart datum"
        }

        /**
         * Decompressed tiles of every open pack, least recently used dropped first. One
         * budget for all packs (160 tiles, about 63 MB): one cache per pack held up to
         * 63 MB each, and with three or more packs a long trip ran out of memory.
         */
        private const val CACHE_TILES = 160
        private val tileCache = object : LinkedHashMap<Pair<Pack, Int>, ByteArray?>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Pack, Int>, ByteArray?>) = size > CACHE_TILES
        }

        const val M_PER_DEG = 111320.0
        const val FT = 0.3048
        const val NPLANES = 6
        const val P_DEPTH = 0
        const val P_HAZARD = 1
        const val P_SOUND = 2
        const val P_FLAGS = 3
        const val P_SPANCLR = 4
        const val P_CABLECLR = 5
        /** HAZARD plane value for a pier or seawall line without a hazard (older apps read it as 25.4 m deep) */
        const val HZ_PIER = 255
        /** CABLECLR plane value without the CABLE flag: the cell's only hazard is a snag or stump */
        const val SNAG_MARK = 255
        /** CABLECLR plane value without the CABLE flag: a channel marker or its wrong-side line */
        const val GATE_MARK = 254
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
    val areaId: String
    val areaName: String
    val areaBbox: DoubleArray
    val tx0: Int
    val ty0: Int
    val created: String
    val states: List<String>
    /** S-57 sounding datum codes of the pack's charts (12 MLLW, 25 Great Lakes); empty in older packs. */
    val datums: List<Int>
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
        require(String(magic) == "NRPK2\n") { "Not a current route pack; download the chart area again" }
        val hl = Integer.reverseBytes(raf.readInt())
        val hb = ByteArray(hl)
        raf.readFully(hb)
        @Suppress("UNCHECKED_CAST")
        val h = MiniJson.parse(String(hb, Charsets.UTF_8)) as Map<String, Any?>
        val area = h["area"] as Map<*, *>
        areaId = area["id"] as String
        areaName = area["name"] as String
        areaBbox = (area["bbox"] as List<*>).map { it as Double }.toDoubleArray()
        tx0 = (h["tx0"] as Double).toInt()
        ty0 = (h["ty0"] as Double).toInt()
        created = h["created"] as String
        states = (h["states"] as List<*>).map { it as String }
        datums = (h["sounding_datums"] as List<*>?).orEmpty().map { (it as Double).toInt() }
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

    /** True if the point is inside this area's published bbox and its grid. */
    fun covers(lat: Double, lon: Double) = lon in areaBbox[0]..areaBbox[2] && lat in areaBbox[1]..areaBbox[3] &&
        lon in west..east && lat in south..north

    val tilesY get() = height / tile

    /** Tile data by global tile number, or null if this pack has no data there. */
    fun tileGlobal(gtx: Int, gty: Int): ByteArray? {
        val tx = gtx - tx0
        val ty = gty - ty0
        if (tx < 0 || ty < 0 || tx >= tilesX || ty >= tilesY) return null
        return tileData(tx, ty)
    }

    /** All 6 planes of one tile (plane-major), or null for an empty tile. */
    @Synchronized
    fun tileData(tx: Int, ty: Int): ByteArray? {
        val k = ty * tilesX + tx
        val key = this to k
        synchronized(tileCache) { if (tileCache.containsKey(key)) return tileCache[key] }
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
        synchronized(tileCache) { tileCache[key] = data }
        return data
    }

    override fun close() {
        synchronized(tileCache) { tileCache.keys.removeAll { it.first === this } }
        raf.close()
    }
}
