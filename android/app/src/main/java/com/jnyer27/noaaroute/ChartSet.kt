package com.jnyer27.noaaroute

import kotlin.math.max
import kotlin.math.min

/**
 * The downloaded area packs combined into one grid. All packs share the
 * global grid (same dlat/dlon, tiles numbered from 90 N, 180 W), so a tile
 * is simply taken from whichever pack has data for it.
 */
class ChartSet(val packs: List<Pack>) {
    init {
        require(packs.isNotEmpty()) { "No chart areas" }
        val p0 = packs[0]
        for (p in packs) require(p.tile == p0.tile && p.dlat == p0.dlat && p.dlon == p0.dlon) {
            "Chart area ${p.areaName} uses a different grid; download it again"
        }
    }

    val tile = packs[0].tile
    val dlat = packs[0].dlat
    val dlon = packs[0].dlon
    val res = packs[0].res
    private val gtx0 = packs.minOf { it.tx0 }
    private val gty0 = packs.minOf { it.ty0 }
    private val gtx1 = packs.maxOf { it.tx0 + it.width / it.tile }
    private val gty1 = packs.maxOf { it.ty0 + it.height / it.tile }
    val width = (gtx1 - gtx0) * tile
    val height = (gty1 - gty0) * tile
    val west = packs[0].west - (packs[0].tx0 - gtx0) * tile * dlon
    val north = packs[0].north + (packs[0].ty0 - gty0) * tile * dlat
    val notes: List<Note> = packs.flatMap { it.notes }
    val names get() = packs.joinToString { it.areaName }

    fun covers(lat: Double, lon: Double) = packs.any { it.covers(lat, lon) }

    private fun tileData(tx: Int, ty: Int): Pair<Pack, ByteArray>? {
        for (p in packs) {
            val d = p.tileGlobal(gtx0 + tx, gty0 + ty) ?: continue
            return p to d
        }
        return null
    }

    /**
     * Copy rows r0 until r0 + h*f, cols c0 until c0 + w*f into per-cell
     * planes, combining f x f blocks. The depth plane comes back as a
     * Planner class (BLOCKED/SAFE/MARGINAL/SHALLOW) for [depthM], sampled at
     * the block center; hazards, soundings and clearances keep the minimum;
     * flags are OR'ed.
     */
    fun window(r0: Int, c0: Int, h: Int, w: Int, f: Int, depthM: Double): Array<ByteArray> {
        val out = Array(Pack.NPLANES) { ByteArray(h * w) }
        val minPlanes = intArrayOf(Pack.P_HAZARD, Pack.P_SOUND, Pack.P_SPANCLR, Pack.P_CABLECLR)
        val luts = HashMap<Pack, ByteArray>()
        val t = tile
        val tt = t * t
        val half = f / 2
        val ty0 = max(0, r0 / t)
        val ty1 = min(height / t - 1, (r0 + h * f - 1) / t)
        val tx0 = max(0, c0 / t)
        val tx1 = min(width / t - 1, (c0 + w * f - 1) / t)
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val (pack, d) = tileData(tx, ty) ?: continue
            val lut = luts.getOrPut(pack) { ByteArray(256) { k -> Planner.classify(pack.depthTable.getOrNull(k), depthM - Planner.ROUNDING_M).toByte() } }
            val prA = max(ty * t, r0)
            val prB = min((ty + 1) * t, r0 + h * f)
            val pcA = max(tx * t, c0)
            val pcB = min((tx + 1) * t, c0 + w * f)
            for (pr in prA until prB) {
                val wr = (pr - r0) / f
                val centerRow = (pr - r0) % f == half
                val rowBase = (pr - ty * t) * t
                for (pc in pcA until pcB) {
                    val i = wr * w + (pc - c0) / f
                    val ti = rowBase + (pc - tx * t)
                    if (centerRow && (pc - c0) % f == half) out[Pack.P_DEPTH][i] = lut[d[Pack.P_DEPTH * tt + ti].toInt() and 0xff]
                    for (p in minPlanes) {
                        val v = d[p * tt + ti].toInt() and 0xff
                        val cur = out[p][i].toInt() and 0xff
                        if (v != 0 && (cur == 0 || v < cur)) out[p][i] = v.toByte()
                    }
                    out[Pack.P_FLAGS][i] = (out[Pack.P_FLAGS][i].toInt() or d[Pack.P_FLAGS * tt + ti].toInt()).toByte()
                }
            }
        }
        return out
    }
}
