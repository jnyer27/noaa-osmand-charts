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
     *
     * Overview (f > 1): a block with a clear dredged or fairway cell (usable depth, no
     * hazard, structure or prohibition) is that channel's water, without hazards,
     * structures, piers, snags or channel markers. Sampled at its center, a 1-2 cell ICW channel south of
     * Currituck Sound Light 111 fell apart at 320 m and a 450 nm trip was guided down
     * the open sound instead.
     *
     * [optimistic] (overview retry, f > 1): the same for a block with any clear cell. A
     * canal 2-3 blocks wide (C&D at 60 m) otherwise broke up on the overview. A block
     * whose center is already usable water keeps its center's depth class: at 320 m one
     * deep-enough cell made a block of 0-6 ft Currituck Sound safe water, and a 5 ft trip
     * was guided down the sound instead of the ICW. Each stretch is still planned at full
     * detail.
     */
    fun window(r0: Int, c0: Int, h: Int, w: Int, f: Int, depthM: Double, optimistic: Boolean = false): Array<ByteArray> {
        val out = Array(Pack.NPLANES) { ByteArray(h * w) }
        val best = if (f > 1) ByteArray(h * w) else null // best clear class per block (channel first), 0 = none
        val hzLim = depthM + Pack.FT
        val sdLim = depthM - Planner.ROUNDING_M
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
            // Depth class per depth-table entry; a dredged area or a charted fairway deep
            // enough for depthM also gets Planner.DREDGED (0x40), so the planner can tell a
            // maintained channel (the ICW is often charted only as a fairway). So does 0-6 ft
            // water in one: a shoaled stretch of the channel, not a marsh gut
            val lut = luts.getOrPut(pack) { ByteArray(256) { k ->
                val r = pack.depthTable.getOrNull(k)
                val cls = Planner.classify(r, depthM - Planner.ROUNDING_M)
                val chan = r?.dredged == true || r?.fairway == true
                (if (chan && (cls == Planner.SAFE || cls == Planner.MARGINAL)) cls or Planner.DREDGED else cls).toByte()
            } }
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
                    val center = centerRow && (pc - c0) % f == half
                    for (p in minPlanes) {
                        val v = d[p * tt + ti].toInt() and 0xff
                        // Open sea only from the center cell, like depth: from any cell, the
                        // overview would close inside channels beside a barrier island
                        if (p == Pack.P_SPANCLR && v == Pack.SEA_MARK && !center) continue
                        val cur = out[p][i].toInt() and 0xff
                        if (v != 0 && (cur == 0 || v < cur)) out[p][i] = v.toByte()
                    }
                    out[Pack.P_FLAGS][i] = (out[Pack.P_FLAGS][i].toInt() or d[Pack.P_FLAGS * tt + ti].toInt()).toByte()
                    if (best != null) {
                        val v = lut[d[Pack.P_DEPTH * tt + ti].toInt() and 0xff].toInt()
                        val cls = v and Planner.DREDGED.inv()
                        val hv = d[Pack.P_HAZARD * tt + ti].toInt() and 0xff
                        val sv = d[Pack.P_SOUND * tt + ti].toInt() and 0xff
                        val fl = d[Pack.P_FLAGS * tt + ti].toInt()
                        val clear = (cls == Planner.SAFE || cls == Planner.MARGINAL) &&
                            !(hv != 0 && hv != Pack.HZ_PIER && (hv - 1) / 10.0 < hzLim) && !(sv != 0 && (sv - 1) / 10.0 < sdLim) &&
                            fl and (Pack.F_STRUCT or Pack.F_PROHIB) == 0
                        if (clear && (optimistic || channel(v)) && rank(v) > rank(best[i].toInt())) best[i] = v.toByte()
                    }
                }
            }
        }
        if (best != null) for (i in 0 until h * w) if (best[i].toInt() != 0) {
            val center = out[Pack.P_DEPTH][i].toInt() and Planner.DREDGED.inv()
            val water = center == Planner.SAFE || center == Planner.MARGINAL
            out[Pack.P_DEPTH][i] = if (!channel(best[i].toInt()) && water) center.toByte() else best[i]
            out[Pack.P_HAZARD][i] = 0
            out[Pack.P_SOUND][i] = 0
            out[Pack.P_FLAGS][i] = (out[Pack.P_FLAGS][i].toInt() and (Pack.F_STRUCT or Pack.F_PROHIB).inv()).toByte()
            // and without snags or channel markers (x50 like a pier: the Coinjock cut's blocks
            // cost 94-141 at 320 m, open sound 4)
            if (out[Pack.P_FLAGS][i].toInt() and Pack.F_CABLE == 0) out[Pack.P_CABLECLR][i] = 0
        }
        return out
    }

    private fun channel(v: Int) = v == Planner.SAFE or Planner.DREDGED

    private fun rank(v: Int) = when (v and Planner.DREDGED.inv()) {
        Planner.SAFE -> if (channel(v)) 3 else 2
        Planner.MARGINAL -> 1
        else -> 0
    }
}
