package com.jnyer27.noaaroute

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class PlanException(msg: String) : Exception(msg)

data class LatLon(val lat: Double, val lon: Double)

class Leg(val distM: Double, val bearing: Double, val shoalM: Double, val lowM: Double?, val notes: List<String>)

class Route(val name: String, val points: List<LatLon>, val legs: List<Leg>, val depthFt: Double,
            val airFt: Double?, val resM: Double, val messages: List<String>, val guideM: Double? = null) {
    val totalNm get() = legs.sumOf { it.distM } / Planner.NM
}

/**
 * Port of noaa-route.py's planner onto a route pack. Same rules: depth areas
 * at or deeper than the safe depth are free, the 0-6 ft style band is usable
 * but costly and flagged, hazards get a buffer, bridges pass only under spans
 * with clearance (or that open), A* on an 8-connected grid without corner
 * cutting, then line-of-sight simplification.
 */
class Planner(private val pack: ChartSet, private val maxCells: Int = MAX_CELLS) {
    /** Stay inside: open sea is a wall (set by [plan]). */
    private var inside = false
    /** Move ends to navigable water: your points in a pocket move out of it, whatever walls it in (set by [plan]). */
    private var moveEnds = false

    companion object {
        const val NM = 1852.0
        const val ROUNDING_M = 0.05
        const val MAX_CELLS = 4_000_000
        const val CUT_OFF_M = 250.0 // how far an end may move to escape a cut-off pocket
        const val MOVE_ENDS_M = 3000.0 // ...and with Move ends to navigable water, from any pocket
        const val APPROACH_M = 20000.0 // a long trip's ends are worked out at 20 m this far out
        const val MAX_DROP_GUIDES = 2 // guide points one stretch may drop when cut off at full detail
        const val GUIDE_M = 18000.0 // guide point spacing on a long trip's overview path
        const val SKIP_GUIDES = 3 // a full-detail piece may reach this many guide points ahead, skipping those between
        const val REJOIN_M = 2000.0 // route replanned this far either side of each guide point at the end
        const val FINE_GUIDE_M = 8000.0 // and on the 20 m paths near its ends (a stretch's window reaches 6.7 km past its points)
        const val RESOLVE_EXTRA_M = 0.2 * NM // resolveChecks: a detour may add this much to a stretch...
        const val RESOLVE_EXTRA_FRAC = 0.10 // ...or this share of it, whichever is more
        private const val RESOLVE_PAD_M = 500.0 // clear legs either side of a check stretch replanned with it
        const val BLOCKED = 0
        const val SAFE = 1
        const val MARGINAL = 2
        const val HAZARD = 3
        const val STRUCT = 4
        const val SHALLOW = 5
        /** Open sea while "Stay inside" is on (seaward of the territorial sea baseline) */
        const val SEA = 6
        /** Bit ChartSet.window adds to SAFE or MARGINAL water inside a dredged area or fairway (a maintained channel). */
        const val DREDGED = 0x40
        // Inside a dredged channel deep enough for the boat, being near its edge is normal:
        // the proximity cost is cut to this share and the hazard buffer's cost (bank
        // soundings just outside the channel) to DREDGED_SOFT_COST. Otherwise every cell of
        // a narrow channel cost up to 3.4 x 5 and Maryland Marina to Carolina Beach went
        // 20 nm outside to the Cape Fear River instead of down the ICW.
        private const val DREDGED_PROX = 0.25f
        private const val DREDGED_SOFT_COST = 1.5f
        // Shore proximity: cost rises within about SHORE_M of danger, at least K_MIN cells
        private const val SHORE_M = 100.0
        private const val K_MIN = 5
        // Centering: cost rises gently (up to x(1 + FAR_COST)) within FAR_M of danger
        private const val FAR_M = 300.0
        private const val FAR_COST = 0.3f
        // 0-6 ft water this close to danger costs MARG_SHORE_COST times more (a creek or slip
        // is still reachable, never taken as a shortcut)
        private const val MARG_SHORE_M = 40.0
        private const val MARG_SHORE_COST = 10f
        private const val OFF_WATER = "connects your point to usable water: not checked, use your eyes"
        // Piers and seawalls (pack HAZARD plane value Pack.HZ_PIER) are lines over charted
        // water, often between a slip and open water. As walls they sealed marinas and
        // narrow bridge spans (fenders) on a 20 m grid, so they are passable at this cost
        // multiple and count as danger for the shore proximity cost: routes go around
        // them and only cross one to reach a slip.
        private const val PIER_COST = 50f
        private const val PIER_NOTE = "runs along or across charted piers or seawalls: check"
        // The hazard buffer over water charted at least the safe depth is usable at this cost
        // (on top of the proximity cost); elsewhere it blocks. Snags and stumps (the pack's
        // SNAG_MARK: always submerged, unknown depth, old pilings) in such water are passable
        // at SNAG_COST: piles charted inside the 27 m dredged ICW channel in Adams Creek
        // Canal share its single 20 m cell. Wrecks, rocks and other obstructions stay walls.
        private const val SOFT_COST = 5f
        private const val SOFT_NOTE = "passes near charted shoal soundings or obstructions: check"
        // Rounding of the distance range in a hazard note (meters)
        private const val NOTE_STEP_M = 5.0
        internal const val HZ_SOUNDING = 0
        internal const val HZ_OBSTRUCTION = 1
        internal const val HZ_SNAG = 2
        private const val SNAG_COST = 50f
        private const val SNAG_NOTE = "passes charted piles or snags: check"
        // Lateral marks (buoys, daybeacons) and their wrong-side lines (the pack's GATE_MARK):
        // a mark's channel side is the same whichever way a boat goes, so red right
        // returning (green right going out) is never passing between a mark and the water it
        // guards. Crossing costs GATE_COST like a pier and is flagged.
        private const val GATE_COST = 50f
        private const val GATE_NOTE = "passes over or on the wrong side of a channel marker: check"

        /** Proximity reach in cells for grid resolution res. */
        fun proxReach(res: Double) = max(K_MIN, ceil(SHORE_M / res).toInt())

        /** Class of a depth range for depth d (meters, rounding slack already taken off). */
        fun classify(r: DepthRange?, d: Double): Int {
            if (r == null) return BLOCKED
            val d1 = r.d1
            val d2 = r.d2
            if (r.dredged) return if (d1 == null || d1 < d) MARGINAL else SAFE
            return when {
                d1 == null -> MARGINAL
                d1 < 0 -> SHALLOW
                d2 != null && d2 <= d -> SHALLOW
                d1 >= d -> SAFE
                else -> MARGINAL
            }
        }

        fun distanceM(a: LatLon, b: LatLon): Double {
            val la1 = Math.toRadians(a.lat)
            val la2 = Math.toRadians(b.lat)
            val dla = la2 - la1
            val dlo = Math.toRadians(b.lon - a.lon)
            val h = sin(dla / 2) * sin(dla / 2) + cos(la1) * cos(la2) * sin(dlo / 2) * sin(dlo / 2)
            return 2 * 6371000 * asin(sqrt(h))
        }

        fun bearing(a: LatLon, b: LatLon): Double {
            val la1 = Math.toRadians(a.lat)
            val la2 = Math.toRadians(b.lat)
            val dlo = Math.toRadians(b.lon - a.lon)
            val y = sin(dlo) * cos(la2)
            val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dlo)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }
    }

    /** A window of the pack at res = pack.res * f. */
    inner class Area(val r0: Int, val c0: Int, val h: Int, val w: Int, val f: Int) {
        val res = pack.res * f
        val n = h * w
        fun latlon(i: Int): LatLon {
            val r = i / w
            val c = i % w
            return LatLon(pack.north - (r0 + (r + 0.5) * f) * pack.dlat, pack.west + (c0 + (c + 0.5) * f) * pack.dlon)
        }
        fun cell(p: LatLon): Int {
            val r = floor(((pack.north - p.lat) / pack.dlat - r0) / f).toInt().coerceIn(0, h - 1)
            val c = floor(((p.lon - pack.west) / pack.dlon - c0) / f).toInt().coerceIn(0, w - 1)
            return r * w + c
        }
    }

    private fun area(pts: List<LatLon>, widen: Double): Area {
        val lats = pts.map { it.lat }
        val lons = pts.map { it.lon }
        val span = max(lats.max() - lats.min(), lons.max() - lons.min())
        val pad = max(0.06, span * 0.25) * widen
        val latmid = (lats.min() + lats.max()) / 2
        val padx = pad / cos(Math.toRadians(latmid))
        val r0 = max(0, floor((pack.north - (lats.max() + pad)) / pack.dlat).toInt())
        val r1 = min(pack.height, ceil((pack.north - (lats.min() - pad)) / pack.dlat).toInt())
        val c0 = max(0, floor((lons.min() - padx - pack.west) / pack.dlon).toInt())
        val c1 = min(pack.width, ceil((lons.max() + padx - pack.west) / pack.dlon).toInt())
        var f = 1
        while (((r1 - r0) / f).toLong() * ((c1 - c0) / f) > maxCells) f++
        return Area(r0, c0, (r1 - r0) / f, (c1 - c0) / f, f)
    }

    // ------------------------------------------------------------ grid

    /** [pier]: costly cells a leg may not cut across (piers, seawalls, passable snags). */
    private class Grid(val nav: ByteArray, val ok: BooleanArray, val cost: FloatArray, val prox: ByteArray,
                       val planes: Array<ByteArray>, val nopass: BooleanArray, val rad: Int, val k: Int,
                       val pier: BooleanArray, val snag: BooleanArray, val soft: BooleanArray, val gate: BooleanArray) {
        /**
         * A dredged channel cell deep enough. A step between two of them may cut a blocked
         * corner: the ICW's Coinjock cut is charted as a 1-cell dredged staircase along the
         * bank, and going round each land corner through the 0-6 ft cell beside it (10x near
         * shore) made the cut cost more than 12 nm of open Currituck Sound.
         */
        fun channel(i: Int) = nav[i].toInt() == SAFE && planes[Pack.P_DEPTH][i].toInt() and DREDGED != 0
    }

    private fun buildGrid(a: Area, depthM: Double, airM: Double?, bufM: Double, optimistic: Boolean = false): Grid {
        val planes = pack.window(a.r0, a.c0, a.h, a.w, a.f, depthM, optimistic)
        val n = a.n
        val w = a.w
        val h = a.h
        val d = depthM - ROUNDING_M
        val hz = depthM + Pack.FT
        val air = airM ?: 0.0
        val nav = ByteArray(n)
        val nopass = BooleanArray(n)
        val spanok = BooleanArray(n)
        val snag = BooleanArray(n)
        val dredged = BooleanArray(n)
        for (i in 0 until n) {
            val fl = planes[Pack.P_FLAGS][i].toInt() and 0xff
            val hzv = planes[Pack.P_HAZARD][i].toInt() and 0xff
            val sv = planes[Pack.P_SOUND][i].toInt() and 0xff
            snag[i] = hzv == 1 && fl and Pack.F_CABLE == 0 && (planes[Pack.P_CABLECLR][i].toInt() and 0xff) == Pack.SNAG_MARK
            nav[i] = when {
                fl and Pack.F_STRUCT != 0 -> STRUCT
                // before hazards, so a boxed-in point is told it is the ocean, not a buoyed shoal
                inside && planes[Pack.P_DEPTH][i].toInt() != 0 && fl and Pack.F_SPAN == 0 &&
                    (planes[Pack.P_SPANCLR][i].toInt() and 0xff) == Pack.SEA_MARK -> SEA
                hzv != 0 && hzv != Pack.HZ_PIER && !snag[i] && (hzv - 1) / 10.0 < hz -> HAZARD
                sv != 0 && (sv - 1) / 10.0 < d -> HAZARD
                fl and Pack.F_PROHIB != 0 -> BLOCKED
                else -> planes[Pack.P_DEPTH][i].toInt() and DREDGED.inv()
            }.toByte()
            dredged[i] = (planes[Pack.P_DEPTH][i].toInt() and DREDGED) != 0
            val span = fl and Pack.F_SPAN != 0
            val clr = (planes[Pack.P_SPANCLR][i].toInt() and 0xff) / 2.0
            if (fl and Pack.F_DECK != 0 || (span && clr < air)) nopass[i] = true
            if (fl and Pack.F_OPENING != 0 || (span && clr >= air)) spanok[i] = true
            if (airM != null && fl and Pack.F_CABLE != 0) {
                val cc = (planes[Pack.P_CABLECLR][i].toInt() and 0xff) / 2.0
                if (cc == 0.0 || cc < airM) nopass[i] = true
            }
        }
        // Widen passable spans by a cell so they cut through the bridge deck band
        val spanWide = spanok.copyOf()
        for (i in 0 until n) if (spanok[i]) {
            val r = i / w
            val c = i % w
            for (rr in max(r - 1, 0)..min(r + 1, h - 1)) for (cc in max(c - 1, 0)..min(c + 1, w - 1)) spanWide[rr * w + cc] = true
        }
        val ok = BooleanArray(n) { i ->
            val v = nav[i].toInt()
            (v == SAFE || v == MARGINAL) && !(nopass[i] && !spanWide[i])
        }

        // Snags in usable water are buffered like any hazard; one where the water may be
        // too shallow is also a wall, as before
        val snagAll = BooleanArray(n) { snag[it] && ok[it] }
        for (i in 0 until n) if (snagAll[i] && nav[i].toInt() != SAFE) ok[i] = false

        // Hazard buffer: over water charted at least the safe depth only the hazard's own
        // cell is a wall and the buffer costs SOFT_COST; elsewhere the whole buffer blocks
        // (shoal soundings in the 0-6 ft band are real warnings: the 6 ft Maryland Marina
        // reference route must stay refused). Round down so a coarser grid never inflates
        // the buffer (60 m on a 40 m grid is 1 cell, not 2: rounding up closed the C&D Canal).
        val rad = max(1, floor(bufM / a.res + 0.25).toInt())
        val disc = ArrayList<IntArray>()
        for (dr in -rad..rad) for (dc in -rad..rad) if (dr * dr + dc * dc <= rad * rad) disc.add(intArrayOf(dr, dc))
        val full = BooleanArray(n)
        for (i in 0 until n) if (nav[i].toInt() == HAZARD || snagAll[i]) {
            val r = i / w
            val c = i % w
            for (o in disc) {
                val rr = r + o[0]
                val cc = c + o[1]
                if (rr in 0 until h && cc in 0 until w) full[rr * w + cc] = true
            }
        }
        for (i in 0 until n) if (full[i] && nav[i].toInt() != SAFE) ok[i] = false
        for (i in 0 until n) snag[i] = snagAll[i] && ok[i]
        val soft = BooleanArray(n) { ok[it] && full[it] && !snag[it] }

        // Distance (cells, capped at k) to the nearest unusable cell. Steps alternate
        // 8- and 4-neighbor growth (octagonal distance, close to straight-line): with
        // 4-neighbor steps alone, water diagonal off a headland looked 1.4x farther away.
        val k = proxReach(a.res)
        // Costly cells a leg may not cut across, which count as danger for proximity
        val gate = BooleanArray(n) {
            ok[it] && !snag[it] && (planes[Pack.P_FLAGS][it].toInt() and Pack.F_CABLE) == 0 &&
                (planes[Pack.P_CABLECLR][it].toInt() and 0xff) == Pack.GATE_MARK
        }
        // A pier or fender line in a dredged channel under a span you clear is the span's
        // fender: on a 20 m grid the fenders either side of a 40 m channel span filled the
        // channel cells, and routes went under the next span over (North Landing River
        // bridge, 36.6147 N 76.0496 W). Such cells are channel; the fender cells beside
        // them still count as danger, so the route keeps to the middle of the opening.
        val fender = BooleanArray(n) {
            dredged[it] && nav[it].toInt() == SAFE && spanok[it] && (planes[Pack.P_HAZARD][it].toInt() and 0xff) == Pack.HZ_PIER
        }
        // Water under a bridge that also spans a dredged channel, outside the channel: the
        // channel span is where the charted clearance and the opening between the piers are,
        // and piers are often not charted (the Jordan Bridge is one polygon, VERCLR 44.1 m,
        // HORCLR 82.2 m, and a straight leg passed under it a cell west of the channel, by
        // the main pier). Such cells cost like a pier line and count as danger, so routes
        // take the channel span unless the side span is the only way.
        val sideSpan = BooleanArray(n)
        run {
            val seen = BooleanArray(n)
            val stack = IntArray(n)
            val comp = ArrayList<Int>()
            for (s0 in 0 until n) {
                if (seen[s0] || (planes[Pack.P_FLAGS][s0].toInt() and Pack.F_BRIDGE) == 0) continue
                comp.clear()
                var sp = 0
                var chan = false
                stack[sp++] = s0
                seen[s0] = true
                while (sp > 0) {
                    val i = stack[--sp]
                    comp.add(i)
                    if (dredged[i] && nav[i].toInt() == SAFE) chan = true
                    val r = i / w
                    val c = i % w
                    for (rr in max(r - 1, 0)..min(r + 1, h - 1)) for (cc in max(c - 1, 0)..min(c + 1, w - 1)) {
                        val j = rr * w + cc
                        if (!seen[j] && (planes[Pack.P_FLAGS][j].toInt() and Pack.F_BRIDGE) != 0) { seen[j] = true; stack[sp++] = j }
                    }
                }
                if (chan) for (i in comp) if (ok[i] && !(dredged[i] && nav[i].toInt() == SAFE)) sideSpan[i] = true
            }
        }
        val pier = BooleanArray(n) { ok[it] && ((planes[Pack.P_HAZARD][it].toInt() and 0xff) == Pack.HZ_PIER && !fender[it] || snag[it] || gate[it] || sideSpan[it]) }
        // The spread reaches kf cells: k for the shore proximity cost, farther for the
        // centering cost (FAR_M)
        val kf = min(120, max(k, ceil(FAR_M / a.res).toInt()))
        val dist = ByteArray(n) { kf.toByte() }
        var frontier = IntArray(n)
        var next = IntArray(n)
        var fn = 0
        for (i in 0 until n) if (!ok[i] || pier[i]) dist[i] = 0
        for (i in 0 until n) if (!ok[i] || pier[i]) {
            // only unusable cells next to usable water (8 neighbors) start the spread
            val r = i / w
            val c = i % w
            var edge = false
            for (rr in max(r - 1, 0)..min(r + 1, h - 1)) for (cc in max(c - 1, 0)..min(c + 1, w - 1)) if (ok[rr * w + cc] && !pier[rr * w + cc]) edge = true
            if (edge) frontier[fn++] = i
        }
        for (step in 1 until kf) {
            val diag = step % 2 == 1
            var nn = 0
            for (f in 0 until fn) {
                val i = frontier[f]
                val r = i / w
                val c = i % w
                for (rr in max(r - 1, 0)..min(r + 1, h - 1)) for (cc in max(c - 1, 0)..min(c + 1, w - 1)) {
                    if (!diag && rr != r && cc != c) continue
                    val j = rr * w + cc
                    if (dist[j] > step) { dist[j] = step.toByte(); next[nn++] = j }
                }
            }
            val t = frontier; frontier = next; next = t
            fn = nn
        }
        val cost = FloatArray(n) { Float.POSITIVE_INFINITY }
        val prox = ByteArray(n)
        val pc = 2.4f / (k - 1) // up to x3.4 next to danger
        val margRing = max(1, (MARG_SHORE_M / a.res).toInt())
        // Overview only: marginal water next to a dredged channel block is that channel's
        // edge. The ICW's 1-cell channel (Coinjock, south of Masonboro Inlet) breaks up on
        // the overview, and at 10x the gap blocks guided Wrightsville to Southport out to sea.
        // At full detail the 10x stays, or a route steps out of a 1-cell channel (Sneads Ferry).
        val byChannel = BooleanArray(n)
        if (a.f > 1) for (i in 0 until n) if (ok[i] && nav[i].toInt() == MARGINAL) {
            val r = i / w
            val c = i % w
            for (rr in max(r - 1, 0)..min(r + 1, h - 1)) for (cc in max(c - 1, 0)..min(c + 1, w - 1)) {
                val j = rr * w + cc
                if (ok[j] && dredged[j] && nav[j].toInt() == SAFE) byChannel[i] = true
            }
        }
        for (i in 0 until n) if (ok[i]) {
            val p = k - min(dist[i].toInt(), k)
            prox[i] = p.toByte()
            val inChannel = dredged[i] && nav[i].toInt() == SAFE
            var c = 1f + pc * p * (if (inChannel) DREDGED_PROX else 1f)
            // Centering: a gentle rise over FAR_M, so in a gap narrower than twice that (between
            // shoals, in a river) the route keeps to the middle instead of the edge of the
            // steep zone (routes hugged the 100 m line around buffered shoal soundings)
            c *= 1f + FAR_COST * (kf - dist[i]) / kf * (if (inChannel) DREDGED_PROX else 1f)
            if (nav[i].toInt() == MARGINAL) {
                c *= 4f
                // 0-6 ft water within MARG_SHORE_M of land, drying ground or a hazard wall: such
                // water shoals toward the shore, and a 5 ft route took a 200 m marsh gut beside
                // Goosecastle Point (Currituck Sound) 16 m off the bank to stay in the ICW longer
                // (not in a charted dredged area or fairway: a shoaled stretch of the ICW south
                // of Masonboro Inlet cost 141 a cell, and Wrightsville to Southport went to sea)
                if (dist[i] <= margRing && !byChannel[i] && !dredged[i]) c *= MARG_SHORE_COST
            }
            // One of: pier or seawall, passable snag, channel marker or its wrong side
            when {
                (planes[Pack.P_HAZARD][i].toInt() and 0xff) == Pack.HZ_PIER && !fender[i] || sideSpan[i] -> c *= PIER_COST
                snag[i] -> c *= SNAG_COST
                gate[i] -> c *= GATE_COST
            }
            if (soft[i]) c *= if (inChannel) DREDGED_SOFT_COST else SOFT_COST
            cost[i] = c
        }
        return Grid(nav, ok, cost, prox, planes, nopass, rad, k, pier, snag, soft, gate)
    }

    // ------------------------------------------------------------ search

    private fun snap(a: Area, g: Grid, p: LatLon, maxM: Double = 3000.0): Pair<Int, Double> {
        val start = a.cell(p)
        if (g.ok[start]) return start to 0.0
        val r0 = start / a.w
        val c0 = start % a.w
        val lim = (maxM / a.res).toInt()
        var best = -1
        var bestD = Double.MAX_VALUE
        for (rad in 1..lim) {
            for (dr in -rad..rad) {
                val dcs = if (abs(dr) == rad) (-rad..rad).toList() else listOf(-rad, rad)
                for (dc in dcs) {
                    val r = r0 + dr
                    val c = c0 + dc
                    if (r in 0 until a.h && c in 0 until a.w && g.ok[r * a.w + c]) {
                        val dd = hypot(dr.toDouble(), dc.toDouble())
                        if (dd < bestD) { bestD = dd; best = r * a.w + c }
                    }
                }
            }
            if (best >= 0 && bestD <= rad) return best to bestD * a.res
        }
        throw PlanException("No navigable water within ${maxM.toInt()} m of %.5f, %.5f for this depth".format(p.lat, p.lon))
    }

    /**
     * Move ends to navigable water: the nearest cell, along usable water from [start]
     * (so not across a point of land into the next creek), charted at least the safe
     * depth and clear of hazard buffers, piers, snags and channel markers, within maxM
     * of the way there; with its straight distance from p. Null if start already is one
     * or none is that close.
     */
    private fun nearestNavigable(a: Area, g: Grid, p: LatLon, start: Int, maxM: Double = MOVE_ENDS_M): Pair<Int, Double>? {
        fun clear(i: Int) = g.ok[i] && g.nav[i].toInt() == SAFE && !g.soft[i] && !g.pier[i] && !g.snag[i] && !g.gate[i]
        if (clear(start)) return null
        val steps = IntArray(a.n) { -1 }
        val queue = IntArray(a.n)
        var head = 0
        var tail = 0
        steps[start] = 0
        queue[tail++] = start
        val lim = (maxM / a.res).toInt()
        while (head < tail) {
            val i = queue[head++]
            if (clear(i)) {
                val q = a.latlon(i)
                return i to distanceM(p, q)
            }
            if (steps[i] >= lim) continue
            val r = i / a.w
            val c = i % a.w
            for (j in intArrayOf(if (r > 0) i - a.w else -1, if (r < a.h - 1) i + a.w else -1,
                                 if (c > 0) i - 1 else -1, if (c < a.w - 1) i + 1 else -1)) {
                if (j >= 0 && steps[j] < 0 && g.ok[j]) { steps[j] = steps[i] + 1; queue[tail++] = j }
            }
        }
        return null
    }

    /** Usable water reachable from start (4-connected). */
    private fun region(a: Area, ok: BooleanArray, start: Int) = region(a, ok, intArrayOf(start))

    /** Usable water reachable from any of [starts]. */
    private fun region(a: Area, ok: BooleanArray, starts: IntArray): BooleanArray {
        val seen = BooleanArray(a.n)
        val stack = IntArray(a.n)
        var sp = 0
        for (s0 in starts) if (!seen[s0]) { seen[s0] = true; stack[sp++] = s0 }
        while (sp > 0) {
            val i = stack[--sp]
            val r = i / a.w
            val c = i % a.w
            for (j in intArrayOf(if (r > 0) i - a.w else -1, if (r < a.h - 1) i + a.w else -1,
                                 if (c > 0) i - 1 else -1, if (c < a.w - 1) i + 1 else -1)) {
                if (j >= 0 && !seen[j] && ok[j]) { seen[j] = true; stack[sp++] = j }
            }
        }
        return seen
    }

    /** Nearest cell of [mask] to p within maxM, with its distance, or null. */
    private fun nearestIn(a: Area, mask: BooleanArray, p: LatLon, maxM: Double): Pair<Int, Double>? {
        val start = a.cell(p)
        val r0 = start / a.w
        val c0 = start % a.w
        var best = -1
        var bestD = Double.MAX_VALUE
        for (rad in 0..(maxM / a.res).toInt()) {
            for (dr in -rad..rad) for (dc in (if (abs(dr) == rad) (-rad..rad).toList() else listOf(-rad, rad))) {
                val r = r0 + dr
                val c = c0 + dc
                if (r in 0 until a.h && c in 0 until a.w && mask[r * a.w + c]) {
                    val dd = hypot(dr.toDouble(), dc.toDouble())
                    if (dd < bestD) { bestD = dd; best = r * a.w + c }
                }
            }
            if (best >= 0 && bestD <= rad) break
        }
        return if (best >= 0 && bestD * a.res <= maxM) best to bestD * a.res else null
    }

    private class Heap(cap: Int) {
        var keys = FloatArray(cap)
        var vals = IntArray(cap)
        var size = 0
        fun push(k: Float, v: Int) {
            if (size == keys.size) { keys = keys.copyOf(size * 2); vals = vals.copyOf(size * 2) }
            var i = size++
            while (i > 0) {
                val p = (i - 1) / 2
                if (keys[p] <= k) break
                keys[i] = keys[p]; vals[i] = vals[p]; i = p
            }
            keys[i] = k; vals[i] = v
        }
        fun pop(): Int {
            val top = vals[0]
            size--
            if (size > 0) {
                val k = keys[size]
                val v = vals[size]
                var i = 0
                while (true) {
                    var c = 2 * i + 1
                    if (c >= size) break
                    if (c + 1 < size && keys[c + 1] < keys[c]) c++
                    if (keys[c] >= k) break
                    keys[i] = keys[c]; vals[i] = vals[c]; i = c
                }
                keys[i] = k; vals[i] = v
            }
            return top
        }
    }

    private fun astar(a: Area, g0: Grid, start: Int, goal: Int): IntArray? {
        val cost = g0.cost
        val pier = g0.pier
        val w = a.w
        val h = a.h
        val res = a.res.toFloat()
        val gr = goal / w
        val gc = goal % w
        val g = FloatArray(a.n) { Float.POSITIVE_INFINITY }
        val parent = IntArray(a.n) { -1 }
        val closed = BooleanArray(a.n)
        val heap = Heap(1 shl 16)
        g[start] = 0f
        heap.push(0f, start)
        val dr = intArrayOf(-1, 1, 0, 0, -1, -1, 1, 1)
        val dc = intArrayOf(0, 0, -1, 1, -1, 1, -1, 1)
        val step = floatArrayOf(1f, 1f, 1f, 1f, 1.4142135f, 1.4142135f, 1.4142135f, 1.4142135f)
        while (heap.size > 0) {
            val i = heap.pop()
            if (closed[i]) continue
            if (i == goal) break
            closed[i] = true
            val r = i / w
            val c = i % w
            val gi = g[i]
            val ci = cost[i]
            for (k in 0 until 8) {
                val rr = r + dr[k]
                val cc = c + dc[k]
                if (rr < 0 || rr >= h || cc < 0 || cc >= w) continue
                val j = rr * w + cc
                val cj = cost[j]
                if (cj == Float.POSITIVE_INFINITY || closed[j]) continue
                var cs = cj
                if (k >= 4) {
                    val o1 = r * w + cc
                    val o2 = rr * w + c
                    if ((cost[o1] == Float.POSITIVE_INFINITY || cost[o2] == Float.POSITIVE_INFINITY) && !(g0.channel(i) && g0.channel(j))) continue
                    // slipping diagonally between pier cells crosses the pier, except along a
                    // dredged channel: a 1-cell ICW channel under the Coinjock bridge runs
                    // diagonally between its fenders, and paid the pier price twice
                    val along = g0.channel(i) && g0.channel(j)
                    if (pier[o1] && !along) cs = max(cs, cost[o1])
                    if (pier[o2] && !along) cs = max(cs, cost[o2])
                }
                val ng = gi + step[k] * res * (ci + cs) * 0.5f
                if (ng < g[j]) {
                    g[j] = ng
                    parent[j] = i
                    heap.push(ng + hypot((rr - gr).toDouble(), (cc - gc).toDouble()).toFloat() * res, j)
                }
            }
        }
        if (start != goal && parent[goal] == -1) return null
        val path = ArrayList<Int>()
        var x = goal
        path.add(x)
        while (x != start) { x = parent[x]; path.add(x) }
        path.reverse()
        return path.toIntArray()
    }

    private val wallText = mapOf(
        "shallow" to "water shallower than your safe depth",
        "land" to "land or uncharted water",
        "hazard" to "the buffer around shallow soundings, rocks, wrecks or obstructions",
        "struct" to "piers or bridge piers",
        "nopass" to "a bridge or overhead cable without enough charted clearance",
        "sea" to "open ocean (Stay inside is on; turn it off to route outside)",
    )

    /** Water reachable from start: cells, touches window edge, reason -> (edge cells, example). */
    /** Water reachable from a point: cell count, whether it reaches the window edge, and what walls it in. */
    private class Enc(val cells: Int, val edge: Boolean, val walls: Map<String, Pair<Int, Int>>, val hazardWalls: List<Int>)

    private fun enclosure(a: Area, g: Grid, start: Int): Enc {
        val w = a.w
        val h = a.h
        val seen = BooleanArray(a.n)
        val stack = IntArray(a.n)
        var sp = 0
        stack[sp++] = start
        seen[start] = true
        var cnt = 0
        var edge = false
        val walls = HashMap<String, Pair<Int, Int>>()
        val hazardWalls = ArrayList<Int>()
        while (sp > 0) {
            val k = stack[--sp]
            cnt++
            val r = k / w
            val c = k % w
            if (r == 0 || r == h - 1 || c == 0 || c == w - 1) edge = true
            for (j in intArrayOf(if (r > 0) k - w else -1, if (r < h - 1) k + w else -1,
                                 if (c > 0) k - 1 else -1, if (c < w - 1) k + 1 else -1)) {
                if (j < 0 || seen[j]) continue
                seen[j] = true
                if (g.ok[j]) { stack[sp++] = j; continue }
                val v = g.nav[j].toInt()
                val why = when {
                    (v == SAFE || v == MARGINAL) && g.nopass[j] -> "nopass"
                    v == SAFE || v == MARGINAL || v == HAZARD -> "hazard"
                    v == SHALLOW -> "shallow"
                    v == STRUCT -> "struct"
                    v == SEA -> "sea"
                    else -> "land"
                }
                val cur = walls[why]
                walls[why] = if (cur == null) 1 to j else cur.first + 1 to cur.second
                if (why == "hazard") hazardWalls.add(j)
            }
        }
        return Enc(cnt, edge, walls, hazardWalls)
    }

    /**
     * What causes the hazard-buffer walls: the shallowest sounding and the
     * shallowest charted rock/wreck/obstruction within the buffer radius of
     * those wall cells. Returns (depth m, cell) for each, if any.
     */
    private fun hazardSources(a: Area, g: Grid, cells: List<Int>): Pair<Pair<Double, Int>?, Pair<Double, Int>?> {
        var snd = 0
        var sndAt = -1
        var obs = 0
        var obsAt = -1
        val seen = HashSet<Int>()
        for (j in cells) {
            val r = j / a.w
            val c = j % a.w
            for (dr in -g.rad..g.rad) for (dc in -g.rad..g.rad) {
                if (dr * dr + dc * dc > g.rad * g.rad) continue
                val rr = r + dr
                val cc = c + dc
                if (rr !in 0 until a.h || cc !in 0 until a.w) continue
                val i = rr * a.w + cc
                if (g.nav[i].toInt() != HAZARD || !seen.add(i)) continue
                val sv = g.planes[Pack.P_SOUND][i].toInt() and 0xff
                val hv = g.planes[Pack.P_HAZARD][i].toInt() and 0xff
                if (sv != 0 && (snd == 0 || sv < snd)) { snd = sv; sndAt = i }
                if (hv != 0 && hv != Pack.HZ_PIER && (obs == 0 || hv < obs)) { obs = hv; obsAt = i }
            }
        }
        return (if (sndAt >= 0) (snd - 1) / 10.0 to sndAt else null) to (if (obsAt >= 0) (obs - 1) / 10.0 to obsAt else null)
    }

    /**
     * Deepest safe-depth setting (0.5 ft steps, below the one asked for) at
     * which the water around p leads out of a 4 km detail window, or null.
     */
    private fun deepestThatGetsOut(p: LatLon, c: Ctx): Double? {
        fun escapes(ft: Double): Boolean {
            val fa = areaAround(p, 4000.0)
            val fg = buildGrid(fa, ft * Pack.FT, c.airM, c.bufM)
            val s = try { snap(fa, fg, p).first } catch (_: PlanException) { return false }
            return enclosure(fa, fg, s).edge
        }
        var lo = 1                                   // half-feet
        var hi = (ceil(c.depthFt * 2) - 1).toInt()   // just below what failed
        if (hi < lo || !escapes(lo / 2.0)) return null
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (escapes(mid / 2.0)) lo = mid else hi = mid - 1
        }
        return lo / 2.0
    }

    /**
     * Cells along p-q. With [corners], each diagonal step also adds both corner
     * cells to it (A*'s no-corner-cutting rule): otherwise a leg slipped between
     * the two cells of a thin diagonal wall (a pier burned one cell wide). A step
     * between two [channel] cells adds no corners, as in A*.
     */
    private fun lineCells(a: Area, p: Int, q: Int, corners: MutableList<Int>? = null, channel: ((Int) -> Boolean)? = null): IntArray {
        val w = a.w
        val r0 = p / w
        val c0 = p % w
        val r1 = q / w
        val c1 = q % w
        val n = max(abs(r1 - r0), abs(c1 - c0)) * 2 + 1
        val out = LinkedHashSet<Int>()
        var pr = r0
        var pc = c0
        for (k in 0..n) {
            val t = k.toDouble() / n
            val r = (r0 + (r1 - r0) * t).roundToInt()
            val c = (c0 + (c1 - c0) * t).roundToInt()
            out.add(r * w + c)
            if (corners != null && r != pr && c != pc && !(channel != null && channel(pr * w + pc) && channel(r * w + c))) {
                corners.add(pr * w + c); corners.add(r * w + pc)
            }
            pr = r; pc = c
        }
        return out.toIntArray()
    }

    /** Pier crossings along a chain of cells: pier cells entered, plus diagonal steps that slip past a pier corner. */
    private fun pierSteps(a: Area, cells: IntArray, pier: (Int) -> Boolean): Int {
        val w = a.w
        var n = 0
        for (k in cells.indices) {
            val i = cells[k]
            if (pier(i)) n++
            else if (k > 0) {
                val r0 = cells[k - 1] / w
                val c0 = cells[k - 1] % w
                val r1 = i / w
                val c1 = i % w
                if (r0 != r1 && c0 != c1 && (pier(r0 * w + c1) || pier(r1 * w + c0))) n++
            }
        }
        return n
    }

    /**
     * Drop path points while the straight line stays in water no shallower, no
     * closer to danger and over no more pier or seawall lines than the cells
     * the planned path itself used.
     */
    private fun simplify(a: Area, g: Grid, path: IntArray): IntArray {
        fun marg(i: Int) = g.nav[i].toInt() == MARGINAL
        fun px(i: Int) = if (g.pier[i]) 0 else g.prox[i].toInt() // a pier cell is next to danger by definition
        fun fits(p: Int, q: Int, maxp: Int, nmarg: Int, npier: Int, nsoft: Int): Boolean {
            // One level of grid staircase tolerance, but never into the two rings
            // nearest danger unless the path itself went there
            val lim = if (maxp + 1 <= g.k - 3) maxp + 1 else maxp
            var m = 0
            var sf = 0
            val corners = ArrayList<Int>()
            val cells = lineCells(a, path[p], path[q], corners, g::channel)
            if (corners.any { !g.ok[it] }) return false
            // 0-6 ft and buffer cells are compared as shares of the cells crossed, plus one:
            // a diagonal line samples more cells than the 8-direction path over the same
            // water, so counts refused every line across open 0-6 ft water (Currituck Sound)
            val pn = q - p + 1
            val n = cells.size
            for (i in cells) {
                if (!g.ok[i]) return false
                if (g.pier[i]) continue // counted below
                if (g.prox[i] > lim) return false
                if (marg(i)) { m++; if (m * pn > (nmarg + 1) * n) return false }
                if (g.soft[i]) { sf++; if (sf * pn > (nsoft + 1) * n) return false }
            }
            return pierSteps(a, cells) { g.pier[it] } <= npier
        }
        // pier crossings of the planned path up to each point
        val pc = IntArray(path.size)
        for (k in 1 until path.size) pc[k] = pc[k - 1] + pierSteps(a, intArrayOf(path[k - 1], path[k])) { g.pier[it] }
        val keep = ArrayList<Int>()
        keep.add(0)
        var s = 0
        var maxp = px(path[0])
        var nmarg = if (marg(path[0])) 1 else 0
        var nsoft = if (g.soft[path[0]]) 1 else 0
        var j = 1
        while (j < path.size) {
            val p2 = max(maxp, px(path[j]))
            val m2 = nmarg + if (marg(path[j])) 1 else 0
            val s2 = nsoft + if (g.soft[path[j]]) 1 else 0
            if (j == s + 1 || fits(s, j, p2, m2, pc[j] - pc[s] + if (g.pier[path[s]]) 1 else 0, s2)) {
                maxp = p2; nmarg = m2; nsoft = s2; j++
            } else {
                s = j - 1
                keep.add(s)
                maxp = max(px(path[s]), px(path[j]))
                nmarg = (if (marg(path[s])) 1 else 0) + (if (marg(path[j])) 1 else 0)
                nsoft = (if (g.soft[path[s]]) 1 else 0) + (if (g.soft[path[j]]) 1 else 0)
            }
        }
        if (keep.last() != path.size - 1) keep.add(path.size - 1)
        return straighten(a, g, path, keep, pc).map { path[it] }.toIntArray()
    }

    /**
     * Second pass over [simplify]'s points: where water usable on the grid is a thin
     * diagonal band (a narrow canal at 20 m), every straight line clips a corner of
     * the 0-6 ft water or hazard buffer beside it and the legs stayed a staircase of
     * one-cell steps. Join points while the line stays within one cell of the planned
     * path, crosses no more pier lines, and only grazes charted water with nothing
     * charted in the cell itself (never land, hazards, piers, open ocean, too-shallow
     * water or a low bridge).
     */
    private fun straighten(a: Area, g: Grid, path: IntArray, keep: List<Int>, pc: IntArray): List<Int> {
        val w = a.w
        fun graze(i: Int): Boolean {
            if (g.ok[i]) return true
            val v = g.nav[i].toInt()
            return (v == SAFE || v == MARGINAL) && !g.nopass[i] && g.planes[Pack.P_HAZARD][i].toInt() == 0
        }
        // distance in cells from path[m] to the segment path[i0]-path[i1]
        fun off(m: Int, i0: Int, i1: Int): Double {
            val ax = (path[i0] % w).toDouble()
            val ay = (path[i0] / w).toDouble()
            val dx = path[i1] % w - ax
            val dy = path[i1] / w - ay
            val px = path[m] % w - ax
            val py = path[m] / w - ay
            val l2 = (dx * dx + dy * dy).takeIf { it > 0 } ?: 1.0
            val t = ((px * dx + py * dy) / l2).coerceIn(0.0, 1.0)
            return hypot(px - t * dx, py - t * dy)
        }
        val out = ArrayList<Int>()
        out.add(keep[0])
        var s = 0
        while (s < keep.size - 1) {
            var best = s + 1
            for (e in s + 2 until keep.size) {
                val i0 = keep[s]
                val i1 = keep[e]
                if ((i0 + 1 until i1).any { off(it, i0, i1) > 1.0 }) break
                val corners = ArrayList<Int>()
                val cells = lineCells(a, path[i0], path[i1], corners, g::channel)
                if (!cells.all { graze(it) } || !corners.all { graze(it) }) continue
                if (pierSteps(a, cells) { g.pier[it] } > pc[i1] - pc[i0] + (if (g.pier[path[i0]]) 1 else 0)) continue
                best = e
            }
            out.add(keep[best])
            s = best
        }
        return out
    }

    // ------------------------------------------------------------ report

    private fun shallowestNear(a: Area, g: Grid, p: LatLon, q: LatLon, withinM: Double = 100.0): Double? {
        val kx = Pack.M_PER_DEG * cos(Math.toRadians((p.lat + q.lat) / 2))
        val ky = Pack.M_PER_DEG
        val ax = p.lon * kx
        val ay = p.lat * ky
        val dx = q.lon * kx - ax
        val dy = q.lat * ky - ay
        val l2 = (dx * dx + dy * dy).takeIf { it > 0 } ?: 1.0
        val pi = a.cell(p)
        val qi = a.cell(q)
        val m = ceil(withinM / a.res).toInt() + 1
        val rA = max(0, min(pi / a.w, qi / a.w) - m)
        val rB = min(a.h - 1, max(pi / a.w, qi / a.w) + m)
        val cA = max(0, min(pi % a.w, qi % a.w) - m)
        val cB = min(a.w - 1, max(pi % a.w, qi % a.w) + m)
        var best = 0
        for (r in rA..rB) for (c in cA..cB) {
            val i = r * a.w + c
            val v = g.planes[Pack.P_SOUND][i].toInt() and 0xff
            if (v == 0 || (best != 0 && v >= best)) continue
            val ll = a.latlon(i)
            val px = ll.lon * kx
            val py = ll.lat * ky
            val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
            if (hypot(px - ax - t * dx, py - ay - t * dy) <= withinM) best = v
        }
        return if (best == 0) null else (best - 1) / 10.0
    }

    private fun featureNotes(kind: String, p: LatLon, tolM: Double): List<String> {
        val near = pack.notes.filter { it.kind == kind && it.near(p.lat, p.lon, tolM) }
        if (near.isEmpty()) return emptyList()
        val top = near.maxOf { it.band } // most detailed chart with this kind of feature here wins
        return near.filter { it.band == top }.mapNotNull { it.describe() }.distinct()
    }

    /** A charted hazard near a leg: its cell, closest distance to the leg (m), what it is and its depth. */
    internal class NearHazard(val cell: Int, val distM: Double, val kind: Int, val depthM: Double) {
        /** "passes 25-45 m from a charted 3.2 ft sounding: check" (the range is the cell's size around the distance) */
        fun note(res: Double): String {
            val lo = max(0.0, floor((distM - res / 2) / NOTE_STEP_M) * NOTE_STEP_M)
            val hi = ceil((distM + res / 2) / NOTE_STEP_M) * NOTE_STEP_M
            val what = when (kind) {
                HZ_SOUNDING -> String.format(Locale.US, "a charted %.1f ft sounding", depthM / Pack.FT)
                HZ_OBSTRUCTION -> if (depthM <= 0.0) "a charted obstruction (depth unknown or 0 ft)"
                    else String.format(Locale.US, "a charted obstruction (%.1f ft)", depthM / Pack.FT)
                else -> "charted piles or snags"
            }
            return String.format(Locale.US, "passes %.0f-%.0f m from %s: check", lo, hi, what)
        }
    }

    /**
     * The charted hazard (sounding or obstruction under the safe depth, or a snag) closest to
     * the leg p-q within [withinM] plus a cell, or null. Ties go to the shallower one.
     */
    private fun nearestHazard(a: Area, g: Grid, p: LatLon, q: LatLon, withinM: Double): NearHazard? {
        val kx = Pack.M_PER_DEG * cos(Math.toRadians((p.lat + q.lat) / 2))
        val ky = Pack.M_PER_DEG
        val ax = p.lon * kx
        val ay = p.lat * ky
        val dx = q.lon * kx - ax
        val dy = q.lat * ky - ay
        val l2 = (dx * dx + dy * dy).takeIf { it > 0 } ?: 1.0
        val pi = a.cell(p)
        val qi = a.cell(q)
        val m = ceil(withinM / a.res).toInt() + 1
        val rA = max(0, min(pi / a.w, qi / a.w) - m)
        val rB = min(a.h - 1, max(pi / a.w, qi / a.w) + m)
        val cA = max(0, min(pi % a.w, qi % a.w) - m)
        val cB = min(a.w - 1, max(pi % a.w, qi % a.w) + m)
        var best: NearHazard? = null
        for (r in rA..rB) for (c in cA..cB) {
            val i = r * a.w + c
            val hv = g.planes[Pack.P_HAZARD][i].toInt() and 0xff
            val snagged = hv == 1 && (g.planes[Pack.P_FLAGS][i].toInt() and Pack.F_CABLE) == 0 &&
                (g.planes[Pack.P_CABLECLR][i].toInt() and 0xff) == Pack.SNAG_MARK
            if (g.nav[i].toInt() != HAZARD && !snagged) continue
            val sv = g.planes[Pack.P_SOUND][i].toInt() and 0xff
            val obs = if (hv != 0 && hv != Pack.HZ_PIER && !snagged) (hv - 1) / 10.0 else null
            val snd = if (sv != 0) (sv - 1) / 10.0 else null
            val (kind, depth) = when {
                snagged -> HZ_SNAG to 0.0
                obs != null && (snd == null || obs <= snd) -> HZ_OBSTRUCTION to obs
                snd != null -> HZ_SOUNDING to snd
                else -> continue
            }
            val ll = a.latlon(i)
            val px = ll.lon * kx
            val py = ll.lat * ky
            val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
            val d = hypot(px - ax - t * dx, py - ay - t * dy)
            if (d > withinM + a.res) continue
            val b = best
            if (b == null || d < b.distM - 1e-6 || abs(d - b.distM) <= 1e-6 && depth < b.depthM) best = NearHazard(i, d, kind, depth)
        }
        return best
    }

    private fun legs(a: Area, g: Grid, wps: List<LatLon>, idx: IntArray, seen: HashSet<String>): List<Leg> {
        val out = ArrayList<Leg>()
        val kinds = listOf("resare" to Pack.F_RESNOTE, "bridge" to Pack.F_BRIDGE, "cable" to Pack.F_CABLE)
        val every = max(1, (500 / a.res).toInt())
        var prevPier = false
        var prevSnag = false
        var prevGate = false
        var prevSoft = false
        var prevHazard = -1
        for (k in 0 until idx.size - 1) {
            val lc = lineCells(a, idx[k], idx[k + 1])
            var marg = lc.count { g.nav[it].toInt() == MARGINAL }
            if (marg < 2) marg = 0
            val notes = ArrayList<String>()
            for ((kind, bit) in kinds) {
                val hits = ArrayList<Int>()
                var run = 0
                for (i in lc) {
                    if ((g.planes[Pack.P_FLAGS][i].toInt() and bit) != 0) {
                        if (run % every == 0) hits.add(i)
                        run++
                    } else run = 0
                }
                for (i in hits.take(12)) for (s in featureNotes(kind, a.latlon(i), a.res * 1.5)) {
                    if (s !in seen) { seen.add(s); notes.add(s) }
                }
            }
            // pier or seawall over usable-depth water (same test as noaa-route.py's report)
            val onPier = pierSteps(a, lc) { i ->
                (g.planes[Pack.P_HAZARD][i].toInt() and 0xff) == Pack.HZ_PIER && g.nav[i].toInt().let { it == SAFE || it == MARGINAL }
            } > 0
            if (onPier && !prevPier) notes.add(0, PIER_NOTE) // once per stretch of such legs
            prevPier = onPier
            val onSnag = pierSteps(a, lc) { g.snag[it] } > 0
            if (onSnag && !prevSnag) notes.add(0, SNAG_NOTE)
            prevSnag = onSnag
            val onGate = pierSteps(a, lc) { g.gate[it] } > 0
            if (onGate && !prevGate) notes.add(0, GATE_NOTE)
            prevGate = onGate
            val near = lc.count { g.soft[it] } >= 2 // one edge cell is grid noise
            // Name the closest hazard whose buffer the leg uses; once per hazard along a stretch
            val hz = if (near) nearestHazard(a, g, wps[k], wps[k + 1], g.rad * a.res) else null
            if (near && (hz == null && !prevSoft || hz != null && hz.cell != prevHazard)) notes.add(0, hz?.note(a.res) ?: SOFT_NOTE)
            prevSoft = near
            prevHazard = if (near) hz?.cell ?: prevHazard else -1
            val d = distanceM(wps[k], wps[k + 1])
            val low = if (marg > 0) shallowestNear(a, g, wps[k], wps[k + 1]) else null
            out.add(Leg(d, bearing(wps[k], wps[k + 1]), marg * d / max(lc.size, 1), low, notes))
        }
        return out
    }

    // ------------------------------------------------------------ planning

    private class Ctx(val depthFt: Double, val airFt: Double?, val depthM: Double, val airM: Double?, val bufM: Double,
                      val progress: (String) -> Unit) {
        val seen = HashSet<String>()           // notes already reported on an earlier leg
        val messages = ArrayList<String>()
        val moved = HashSet<Int>()             // user points already reported as moved
    }

    /** [cost]: the A* cost of the piece's full-detail path (to compare a shortcut with the stretches it replaces). */
    private class Piece(val wps: List<LatLon>, val legs: List<Leg>, val res: Double, val cost: Double)

    /** Notes, messages and moved points so far, so a trial piece can be undone. */
    private class Snap(val seen: Set<String>, val nMsg: Int, val moved: Set<Int>)
    private fun Ctx.snap() = Snap(HashSet(seen), messages.size, HashSet(moved))
    private fun Ctx.restore(s: Snap) {
        seen.clear(); seen.addAll(s.seen)
        while (messages.size > s.nMsg) messages.removeAt(messages.size - 1)
        moved.clear(); moved.addAll(s.moved)
    }

    /** A* cost of a full-detail path, as astar counts it. */
    private fun pathCost(a: Area, g: Grid, path: IntArray): Double {
        var t = 0.0
        for (k in 1 until path.size) {
            val i = path[k - 1]
            val j = path[k]
            val step = if (i / a.w != j / a.w && i % a.w != j % a.w) 1.4142135 else 1.0
            t += step * a.res * (g.cost[i] + g.cost[j]) * 0.5
        }
        return t
    }

    private val widths = doubleArrayOf(1.0, 2.5, 5.0)

    /** Label for a routing point: user point number, or a guide point placed by the planner. */
    private fun label(n: Int?, p: LatLon) =
        if (n != null) "Point $n (%.5f, %.5f)".format(p.lat, p.lon) else "A guide point the planner placed at %.5f, %.5f".format(p.lat, p.lon)

    private fun noRoute(c: Ctx, a: Area, g: Grid, who: String, pt: LatLon, enc: Enc): PlanException {
        val cnt = enc.cells
        val walls = enc.walls
        val sb = StringBuilder()
        sb.append("No route found for ${fmt(c.depthFt)} ft depth")
        sb.append(if (c.airFt != null) " and ${fmt(c.airFt)} ft air draft.\n" else ".\n")
        sb.append("$who is in a pocket of about %.1f ha of usable water, closed off by:\n".format(cnt * a.res * a.res / 1e4))
        fun at(i: Int) = a.latlon(i).let { "%.5f, %.5f".format(it.lat, it.lon) }
        for ((why, v) in walls.entries.sortedByDescending { it.value.first }) {
            val edge = "${(v.first * a.res).roundToInt()} m of edge"
            if (why == "hazard") {
                val (snd, obs) = hazardSources(a, g, enc.hazardWalls)
                if (snd != null) sb.append("  - soundings as shallow as %.1f ft (near %s), with their safety buffer ($edge)\n".format(snd.first / Pack.FT, at(snd.second)))
                if (obs != null) sb.append(if (obs.first == 0.0) "  - a rock, wreck or obstruction of unknown or near-zero depth (near ${at(obs.second)}), with its safety buffer\n"
                                           else "  - rocks, wrecks or obstructions as shallow as %.1f ft (near %s), with their safety buffer\n".format(obs.first / Pack.FT, at(obs.second)))
                if (snd == null && obs == null) sb.append("  - ${wallText[why]} ($edge, near ${at(v.second)})\n")
            } else sb.append("  - ${wallText[why]} ($edge, near ${at(v.second)})\n")
        }
        val limitedByDepth = walls.keys.any { it == "hazard" || it == "shallow" }
        val best = if (limitedByDepth) deepestThatGetsOut(pt, c) else null
        if (best != null) sb.append("With a safe depth of ${fmt(best)} ft or less the water here leads out of this area; try that (the rest of the trip may still have shallower spots).\n")
        else if (limitedByDepth) sb.append("Even 0.5 ft of depth does not lead out from here.\n")
        if (moveEnds) sb.append("Move ends to navigable water is on, but no water within ${MOVE_ENDS_M.toInt()} m of this point leads out.\n")
        sb.append("Move the point into open water, or adjust depth or air draft.")
        return PlanException(sb.toString())
    }

    /** Route a to b on the full-detail grid (its window must fit without coarsening). */
    private fun planPiece(p: LatLon, q: LatLon, np: Int?, nq: Int?, c: Ctx): Piece {
        for ((attempt, widen) in widths.withIndex()) {
            val a = area(listOf(p, q), widen)
            val g = buildGrid(a, c.depthM, c.airM, c.bufM)
            var (sp, mp) = snap(a, g, p)
            var (sq, mq) = snap(a, g, q)
            if (moveEnds) {
                // Your points in water charted under the safe depth (or in a hazard buffer)
                // start or end at the nearest water that is not: the leg between is not planned
                for (e in 0..1) {
                    val n = (if (e == 0) np else nq) ?: continue
                    val pt = if (e == 0) p else q
                    val (cell, dist) = nearestNavigable(a, g, pt, if (e == 0) sp else sq) ?: continue
                    if (e == 0) { sp = cell; mp = 0.0 } else { sq = cell; mq = 0.0 }
                    val msg = "Point $n is in water charted under your ${fmt(c.depthFt)} ft or near charted hazards; with Move ends " +
                        "to navigable water on, the route uses the nearest water charted at least ${fmt(c.depthFt)} ft, ${dist.roundToInt()} m away. " +
                        "Check the way between on a chart."
                    if (msg !in c.messages) c.messages.add(msg)
                    c.moved.add(n)
                }
            }
            for ((n, m) in listOf(np to mp, nq to mq)) if (n != null && m > a.res && n !in c.moved) {
                c.moved.add(n)
                c.messages.add("Point $n moved ${m.roundToInt()} m to the nearest usable water")
            }
            var path = astar(a, g, sp, sq)
            if (path == null) {
                // An end in a small pocket walled off only by land or structures (a marina
                // basin behind a charted pier, too thin for the grid): move it to the nearest
                // connected water within CUT_OFF_M. Pockets closed by shallow water, hazards
                // or low clearance are real limits and keep the "no route" explanation.
                val ends = listOf(enclosure(a, g, sp), enclosure(a, g, sq))
                for (e in (0..1).sortedBy { ends[it].cells }) {
                    if (ends[e].edge) continue // reaches the window edge: not a pocket
                    if (ends[e].walls.keys.any { it != "land" && it != "struct" }) continue
                    val other = region(a, g.ok, if (e == 0) sq else sp)
                    val pt = if (e == 0) p else q
                    val (cell, dist) = nearestIn(a, other, pt, CUT_OFF_M) ?: continue
                    val retry = if (e == 0) astar(a, g, cell, sq) else astar(a, g, sp, cell)
                    if (retry != null) {
                        path = retry
                        val walls = ends[e].walls.entries.sortedByDescending { it.value.first }.joinToString(" and ") { wallText[it.key]!! }
                        val n = if (e == 0) np else nq
                        c.messages.add((if (n != null) "Point $n" else "A guide point") +
                            ": the water there is cut off by $walls; the route " + (if (e == 0) "starts" else "ends") +
                            " ${dist.roundToInt()} m away at the nearest connected water.")
                        if (n != null) c.moved.add(n)
                        break
                    }
                }
            }
            if (path == null && moveEnds) {
                // Move ends to navigable water: your points in a pocket closed by anything
                // (shallow water, hazard buffers, low clearance, land farther than CUT_OFF_M)
                // move to the nearest water within MOVE_ENDS_M that leads to the other end,
                // or out of this window when the other end is in a pocket too. Guide points
                // are not moved: a stretch drops a cut-off one instead.
                val ends = listOf(enclosure(a, g, sp), enclosure(a, g, sq))
                val cells = intArrayOf(sp, sq)
                val moves = ArrayList<Triple<Int, Int, Double>>() // end, cell, distance
                val border by lazy {
                    region(a, g.ok, (0 until a.n).filter { i ->
                        val r = i / a.w
                        val col = i % a.w
                        g.ok[i] && (r == 0 || r == a.h - 1 || col == 0 || col == a.w - 1)
                    }.toIntArray())
                }
                for (e in 0..1) {
                    if (ends[e].edge || (if (e == 0) np else nq) == null) continue
                    val target = if (ends[1 - e].edge) region(a, g.ok, cells[1 - e]) else border
                    val (cell, dist) = nearestIn(a, target, if (e == 0) p else q, MOVE_ENDS_M) ?: continue
                    moves.add(Triple(e, cell, dist))
                }
                if (moves.isNotEmpty()) {
                    val s0 = moves.firstOrNull { it.first == 0 }?.second ?: sp
                    val s1 = moves.firstOrNull { it.first == 1 }?.second ?: sq
                    val retry = astar(a, g, s0, s1)
                    if (retry != null) {
                        path = retry
                        for ((e, _, dist) in moves) {
                            val n = if (e == 0) np else nq
                            val walls = ends[e].walls.entries.sortedByDescending { it.value.first }.joinToString(" and ") { wallText[it.key]!! }
                            // Worded alike for both legs at a via point, so it is reported once
                            val msg = "Point $n: the water there is cut off by $walls; with Move ends to navigable water on, the route " +
                                "uses the nearest water that leads out, ${dist.roundToInt()} m away. Check the way between on a chart."
                            if (msg !in c.messages) c.messages.add(msg)
                            c.moved.add(n!!)
                        }
                    }
                }
            }
            if (path != null) {
                val cost = pathCost(a, g, path)
                var idx = simplify(a, g, path)
                val wps = idx.map { a.latlon(it) }.toMutableList()
                // An end that was moved to usable water (snapped or cut off) keeps that
                // water as its own waypoint; replacing it by the end point itself made
                // the first or last leg cut across land or piers unchecked.
                val cp = a.cell(p)
                val cq = a.cell(q)
                // A guide point (np/nq null) just starts or ends at the water found for it
                val head = np != null && cp != idx.first()
                val tail = nq != null && cq != idx.last()
                if (idx.size == 1 && !head && !tail) { wps.add(p); idx = intArrayOf(idx[0], idx[0]) }
                if (head) { wps.add(0, p); idx = intArrayOf(cp) + idx } else if (np != null || cp == idx.first()) wps[0] = p
                if (tail) { wps.add(q); idx += cq } else if (nq != null || cq == idx.last()) wps[wps.size - 1] = q
                val ls = legs(a, g, wps, idx, c.seen).toMutableList()
                fun offWater(l: Leg) = Leg(l.distM, l.bearing, l.shoalM, l.lowM, listOf(OFF_WATER) + l.notes)
                if (head) ls[0] = offWater(ls[0])
                if (tail) ls[ls.size - 1] = offWater(ls[ls.size - 1])
                return Piece(wps, ls, a.res, cost)
            }
            val ends = listOf(enclosure(a, g, sp), enclosure(a, g, sq))
            val closed = (0..1).filter { !ends[it].edge }
            if (closed.isNotEmpty() || attempt == widths.size - 1) {
                val e = closed.firstOrNull() ?: (0..1).minBy { ends[it].cells }
                throw noRoute(c, a, g, if (e == 0) label(np, p) else label(nq, q), if (e == 0) p else q, ends[e])
            }
            c.progress("No route inside this area, widening the search")
        }
        throw PlanException("No route found")
    }

    /** Full-detail window of +/- halfM meters around p. */
    private fun areaAround(p: LatLon, halfM: Double, f: Int = 1): Area {
        val dy = halfM / Pack.M_PER_DEG
        val dx = halfM / (Pack.M_PER_DEG * cos(Math.toRadians(p.lat)))
        val r0 = max(0, floor((pack.north - (p.lat + dy)) / pack.dlat).toInt())
        val r1 = min(pack.height, ceil((pack.north - (p.lat - dy)) / pack.dlat).toInt())
        val c0 = max(0, floor((p.lon - dx - pack.west) / pack.dlon).toInt())
        val c1 = min(pack.width, ceil((p.lon + dx - pack.west) / pack.dlon).toInt())
        return Area(r0, c0, (r1 - r0) / f, (c1 - c0) / f, f)
    }

    /**
     * A guide point moved to real channel water: the nearest full-detail cell charted
     * at least the safe depth, usable, not a pier/marker/snag cell; nearness to danger
     * and being in a hazard buffer only tie-breakers. As hard rules they skipped every
     * cell of the narrow ICW behind Masonboro Island (all within the buffers of its bank
     * soundings) and picked the Atlantic across the island, sending the route around
     * Cape Fear. An overview cell (320 m on a long
     * trip) counts as water when a sliver of channel runs through it, so its center
     * can lie in shallow sound beside the ICW (Masonboro Sound: the stretch was sent
     * there, the 20 m search failed, and the 40 m one left the marked channel).
     * Unchanged when there is no such water nearby.
     */
    private fun refineGuide(pt: LatLon, overviewRes: Double, c: Ctx): LatLon {
        val fa = areaAround(pt, max(overviewRes, 300.0))
        val fg = buildGrid(fa, c.depthM, c.airM, c.bufM)
        val at = fa.cell(pt)
        val r0 = at / fa.w
        val c0 = at % fa.w
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in 0 until fa.n) if (fg.ok[i] && fg.nav[i].toInt() == SAFE && !fg.pier[i]) {
            val dd = hypot((i / fa.w - r0).toDouble(), (i % fa.w - c0).toDouble()) + 0.5 * fg.prox[i] + (if (fg.soft[i]) 2.0 else 0.0)
            if (dd < bestD) { bestD = dd; best = i }
        }
        return if (best >= 0) fa.latlon(best) else pt
    }

    /**
     * Cheapest cost from [start] to every cell it can reach, with the moves and costs
     * of astar, and each cell's parent (toward [start]).
     */
    private fun costField(a: Area, g0: Grid, start: Int): Pair<FloatArray, IntArray> {
        val cost = g0.cost
        val pier = g0.pier
        val w = a.w
        val h = a.h
        val res = a.res.toFloat()
        val g = FloatArray(a.n) { Float.POSITIVE_INFINITY }
        val parent = IntArray(a.n) { -1 }
        // Binary heap keyed by g with each cell at most once (pos: its slot, -1 none, -2 done):
        // a lazy heap over a whole 4M-cell window could grow to tens of millions of entries
        val pos = IntArray(a.n) { -1 }
        var heap = IntArray(1 shl 16)
        var size = 0
        fun up(at: Int) {
            var x = at
            val v = heap[x]
            while (x > 0) {
                val pa = (x - 1) / 2
                if (g[heap[pa]] <= g[v]) break
                heap[x] = heap[pa]; pos[heap[x]] = x; x = pa
            }
            heap[x] = v; pos[v] = x
        }
        fun down(at: Int) {
            var x = at
            val v = heap[x]
            while (true) {
                var ch = 2 * x + 1
                if (ch >= size) break
                if (ch + 1 < size && g[heap[ch + 1]] < g[heap[ch]]) ch++
                if (g[heap[ch]] >= g[v]) break
                heap[x] = heap[ch]; pos[heap[x]] = x; x = ch
            }
            heap[x] = v; pos[v] = x
        }
        g[start] = 0f
        heap[size++] = start
        pos[start] = 0
        val dr = intArrayOf(-1, 1, 0, 0, -1, -1, 1, 1)
        val dc = intArrayOf(0, 0, -1, 1, -1, 1, -1, 1)
        val step = floatArrayOf(1f, 1f, 1f, 1f, 1.4142135f, 1.4142135f, 1.4142135f, 1.4142135f)
        while (size > 0) {
            val i = heap[0]
            pos[i] = -2
            size--
            if (size > 0) { heap[0] = heap[size]; down(0) }
            val r = i / w
            val c = i % w
            val gi = g[i]
            val ci = cost[i]
            for (k in 0 until 8) {
                val rr = r + dr[k]
                val cc = c + dc[k]
                if (rr < 0 || rr >= h || cc < 0 || cc >= w) continue
                val j = rr * w + cc
                val cj = cost[j]
                if (cj == Float.POSITIVE_INFINITY || pos[j] == -2) continue
                var cs = cj
                if (k >= 4) {
                    val o1 = r * w + cc
                    val o2 = rr * w + c
                    if ((cost[o1] == Float.POSITIVE_INFINITY || cost[o2] == Float.POSITIVE_INFINITY) && !(g0.channel(i) && g0.channel(j))) continue
                    val along = g0.channel(i) && g0.channel(j)
                    if (pier[o1] && !along) cs = max(cs, cost[o1])
                    if (pier[o2] && !along) cs = max(cs, cost[o2])
                }
                val ng = gi + step[k] * res * (ci + cs) * 0.5f
                if (ng < g[j]) {
                    g[j] = ng
                    parent[j] = i
                    if (pos[j] == -1) {
                        if (size == heap.size) heap = heap.copyOf(size * 2)
                        heap[size] = j
                        pos[j] = size++
                    }
                    up(pos[j])
                }
            }
        }
        return g to parent
    }

    /**
     * One end of a long trip worked out at full detail: everything reachable from it
     * within APPROACH_M, with the cheapest cost and path back to it ([fail]: the
     * explanation to give if the end turns out to be boxed in).
     */
    private class Approach(val fa: Area, val cost: FloatArray, val parent: IntArray, val fail: PlanException?) {
        /** Full-detail path from cell i back to the end. */
        fun trace(i: Int): List<Int> {
            val out = ArrayList<Int>()
            var x = i
            while (x >= 0) { out.add(x); x = parent[x] }
            return out
        }
    }

    private fun approach(pt: LatLon, who: String, c: Ctx): Approach {
        val fa = areaAround(pt, APPROACH_M)
        val fg = buildGrid(fa, c.depthM, c.airM, c.bufM)
        var (s, _) = snap(fa, fg, pt)
        var enc = enclosure(fa, fg, s)
        val landOnly = enc.walls.keys.all { it == "land" || it == "struct" }
        if (!enc.edge && (landOnly || moveEnds)) {
            // Cut off within the window: use the nearest water that leads out of it
            val border = (0 until fa.n).filter { i ->
                val r = i / fa.w
                val col = i % fa.w
                fg.ok[i] && (r == 0 || r == fa.h - 1 || col == 0 || col == fa.w - 1)
            }.toIntArray()
            val out = region(fa, fg.ok, border)
            nearestIn(fa, out, pt, if (moveEnds) MOVE_ENDS_M else CUT_OFF_M)?.let { s = it.first; enc = enclosure(fa, fg, s) }
        }
        if (moveEnds && enc.edge) nearestNavigable(fa, fg, pt, s)?.let { s = it.first }
        val (cost, parent) = costField(fa, fg, s)
        return Approach(fa, cost, parent, if (enc.edge) null else noRoute(c, fa, fg, who, pt, enc))
    }

    /**
     * Long trips: plan on a coarse overview grid, then return guide points along that
     * path; each stretch between them is routed at full detail by planPiece. Around
     * each end (APPROACH_M) the overview does not plan at all: it may only end on an
     * overview cell holding water reachable from the end at 20 m, at that water's real
     * cost, and the guide points there follow the 20 m path. Drawing the 20 m water
     * into the overview instead let a cell of ocean (reachable by Masonboro Inlet) sit
     * next to a cell of sound (reachable down the ICW): the overview walked across
     * Pleasure Island, and the last stretch, at 20 m, could not follow it.
     */
    private fun guidePoints(p: LatLon, q: LatLon, np: Int?, nq: Int?, c: Ctx): Pair<List<LatLon>, Double> {
        val ends = listOf(approach(p, label(np, p), c), approach(q, label(nq, q), c))
        // Both ends inside each other's windows: the cheapest meeting in 20 m water
        val (ap, aq) = ends
        var meet = Float.POSITIVE_INFINITY
        var meetP = -1
        var meetQ = -1
        for (i in 0 until ap.fa.n) {
            if (ap.cost[i] == Float.POSITIVE_INFINITY) continue
            val r = ap.fa.r0 + i / ap.fa.w - aq.fa.r0
            val col = ap.fa.c0 + i % ap.fa.w - aq.fa.c0
            if (r < 0 || r >= aq.fa.h || col < 0 || col >= aq.fa.w) continue
            val j = r * aq.fa.w + col
            val t = ap.cost[i] + aq.cost[j]
            if (t < meet) { meet = t; meetP = i; meetQ = j }
        }
        for ((attempt, widen) in widths.withIndex()) {
            val a = area(listOf(p, q), widen)
            // Overview cells wholly inside an end's window, and per end the cheapest
            // reachable 20 m cell in each of them
            val box = BooleanArray(a.n)
            val best = ends.map { HashMap<Int, Int>() }
            for ((k, e) in ends.withIndex()) {
                val fa = e.fa
                val rA = max(0, ceil((fa.r0 - a.r0).toDouble() / a.f).toInt())
                val rB = min(a.h, floor((fa.r0 + fa.h - a.r0).toDouble() / a.f).toInt())
                val cA = max(0, ceil((fa.c0 - a.c0).toDouble() / a.f).toInt())
                val cB = min(a.w, floor((fa.c0 + fa.w - a.c0).toDouble() / a.f).toInt())
                for (r in rA until rB) for (col in cA until cB) box[r * a.w + col] = true
                for (i in 0 until fa.n) {
                    if (e.cost[i] == Float.POSITIVE_INFINITY) continue
                    val r = (fa.r0 + i / fa.w - a.r0).floorDiv(a.f)
                    val col = (fa.c0 + i % fa.w - a.c0).floorDiv(a.f)
                    if (r !in rA until rB || col !in cA until cB) continue
                    val oc = r * a.w + col
                    val cur = best[k][oc]
                    if (cur == null || e.cost[i] < e.cost[cur]) best[k][oc] = i
                }
            }
            // A coarse overview can break up a narrow canal: before widening (which makes the
            // grid coarser still), retry with any clear 20 m cell keeping its block open
            for (optimistic in if (a.f > 1) listOf(false, true) else listOf(false)) {
                c.progress("Overview at ${a.res.roundToInt()} m (${a.w} x ${a.h})" + if (optimistic) ", keeping narrow channels" else "")
                val g = buildGrid(a, c.depthM, c.airM, c.bufM, optimistic)
                val found = astarEnds(a, g, box, best[0].mapValues { ap.cost[it.value] }, best[1].mapValues { aq.cost[it.value] }, a.cell(q))
                // Points along the whole path, each with its overview cell (-1: a 20 m point)
                val pts = ArrayList<LatLon>()
                val cells = ArrayList<Int>()
                if (found != null && found.second < meet) {
                    val path = found.first
                    for (i in ap.trace(best[0][path.first()]!!).asReversed()) { pts.add(ap.fa.latlon(i)); cells.add(-1) }
                    for (oc in path.slice(1 until path.size - 1)) { pts.add(a.latlon(oc)); cells.add(oc) }
                    for (i in aq.trace(best[1][path.last()]!!)) { pts.add(aq.fa.latlon(i)); cells.add(-1) }
                } else if (meetP >= 0) {
                    for (i in ap.trace(meetP).asReversed()) { pts.add(ap.fa.latlon(i)); cells.add(-1) }
                    for (i in aq.trace(meetQ).drop(1)) { pts.add(aq.fa.latlon(i)); cells.add(-1) }
                }
                if (pts.isNotEmpty()) {
                    val cum = DoubleArray(pts.size)
                    for (k in 1 until pts.size) cum[k] = cum[k - 1] + distanceM(pts[k - 1], pts[k])
                    val total = cum.last()
                    val reach = max(1, (2000 / a.res).toInt())
                    val out = ArrayList<LatLon>()
                    var last = 0.0
                    var k = 1
                    while (k < pts.size - 1) {
                        val fine = cells[k] < 0
                        if (cum[k] - last < (if (fine) FINE_GUIDE_M else GUIDE_M) || cum[k] < 4000 || total - cum[k] < 4000) { k++; continue }
                        if (fine) {
                            // On the 20 m path itself: already usable water reachable from the end
                            out.add(pts[k])
                        } else {
                            // Prefer a nearby overview cell in open, safe water
                            var at = k
                            for (d in 0..reach) {
                                val cand = listOf(k + d, k - d).firstOrNull { it in (k - reach)..(k + reach) && it in 1 until pts.size - 1 &&
                                    cells[it] >= 0 && cum[it] > last && g.nav[cells[it]].toInt() == SAFE && g.prox[cells[it]].toInt() == 0 }
                                if (cand != null) { at = cand; break }
                            }
                            k = at
                            out.add(refineGuide(pts[k], a.res, c))
                        }
                        last = cum[k]
                        k++
                    }
                    return out to a.res
                }
                if (!optimistic && a.f > 1) continue
                ends.firstOrNull { it.fail != null }?.let { throw it.fail!! }
                if (attempt == widths.size - 1) {
                    throw noRoute(c, a, g, label(np, p) + " (overview at ${a.res.roundToInt()} m)", p, enclosure(a, g, a.cell(p)))
                }
            }
            c.progress("No route inside this area, widening the search")
        }
        throw PlanException("No route found")
    }

    /**
     * Overview A* from any of [starts] (cell -> cost of reaching it from the start) to
     * any of [goals] (cell -> cost from it to the end). Inside the ends' windows ([box])
     * the overview only starts or ends: it never crosses them on its own coarse cells.
     * Returns the overview path and its total cost.
     */
    private fun astarEnds(a: Area, g0: Grid, box: BooleanArray, starts: Map<Int, Float>, goals: Map<Int, Float>,
                          target: Int): Pair<IntArray, Float>? {
        val cost = g0.cost.copyOf()
        val depth = g0.planes[Pack.P_DEPTH]
        for (i in starts.keys + goals.keys) if (cost[i] == Float.POSITIVE_INFINITY) cost[i] = 2f
        val w = a.w
        val h = a.h
        val res = a.res.toFloat()
        val gr = target / w
        val gc = target % w
        val done = a.n // the end itself, reached from a goal cell
        val g = FloatArray(a.n + 1) { Float.POSITIVE_INFINITY }
        val parent = IntArray(a.n + 1) { -1 }
        val closed = BooleanArray(a.n + 1)
        val heap = Heap(1 shl 16)
        for ((i, v) in starts) {
            g[i] = v
            heap.push(v + hypot((i / w - gr).toDouble(), (i % w - gc).toDouble()).toFloat() * res, i)
        }
        val dr = intArrayOf(-1, 1, 0, 0, -1, -1, 1, 1)
        val dc = intArrayOf(0, 0, -1, 1, -1, 1, -1, 1)
        val step = floatArrayOf(1f, 1f, 1f, 1f, 1.4142135f, 1.4142135f, 1.4142135f, 1.4142135f)
        while (heap.size > 0) {
            val i = heap.pop()
            if (i == done) break
            if (closed[i]) continue
            closed[i] = true
            val gi = g[i]
            if (i !in starts) goals[i]?.let { v -> if (gi + v < g[done]) { g[done] = gi + v; parent[done] = i; heap.push(gi + v, done) } }
            if (box[i] && i !in starts) continue // a goal cell inside a window: the path ends here
            val r = i / w
            val c = i % w
            val ci = cost[i]
            for (k in 0 until 8) {
                val rr = r + dr[k]
                val cc = c + dc[k]
                if (rr < 0 || rr >= h || cc < 0 || cc >= w) continue
                val j = rr * w + cc
                // Into a window only to end there, and never from one window cell to another
                if (box[j] && (box[i] || j !in goals)) continue
                val cj = cost[j]
                if (cj == Float.POSITIVE_INFINITY || closed[j]) continue
                if (k >= 4) {
                    val o1 = r * w + cc
                    val o2 = rr * w + c
                    // A channel only a cell wide at 20 m crosses an overview cell's corner when it
                    // runs diagonally: between two channel cells the step is taken anyway
                    val channel = depth[i].toInt() == SAFE or DREDGED && depth[j].toInt() == SAFE or DREDGED
                    if (!channel && (cost[o1] == Float.POSITIVE_INFINITY || cost[o2] == Float.POSITIVE_INFINITY)) continue
                }
                val ng = gi + step[k] * res * (ci + cj) * 0.5f
                if (ng < g[j]) {
                    g[j] = ng
                    parent[j] = i
                    heap.push(ng + hypot((rr - gr).toDouble(), (cc - gc).toDouble()).toFloat() * res, j)
                }
            }
        }
        if (parent[done] == -1) return null
        val path = ArrayList<Int>()
        var x = parent[done]
        while (x >= 0) { path.add(x); x = parent[x] }
        path.reverse()
        return path.toIntArray() to g[done]
    }

    // ------------------------------------------------------------ main entry

    fun plan(name: String, pts: List<LatLon>, depthFt: Double, airFt: Double?, bufM: Double = 60.0,
             stayInside: Boolean = false, moveEnds: Boolean = false, progress: (String) -> Unit = {}): Route {
        require(pts.size >= 2)
        inside = stayInside
        this.moveEnds = moveEnds
        for (p in pts) if (!pack.covers(p.lat, p.lon))
            throw PlanException("%.5f, %.5f is outside your downloaded chart areas (%s). Download the area that covers it.".format(p.lat, p.lon, pack.names))
        val c = Ctx(depthFt, airFt, depthFt * Pack.FT, airFt?.let { it * Pack.FT }, bufM, progress)
        val wps = ArrayList<LatLon>()
        val legs = ArrayList<Leg>()
        var res = 0.0
        var guideRes: Double? = null
        for (k in 0 until pts.size - 1) {
            val p = pts[k]
            val q = pts[k + 1]
            // Short enough for one full-detail window, or guided through overview points
            val chain = if (area(listOf(p, q), 1.0).f == 1) listOf(p, q) else {
                val (guide, gr) = guidePoints(p, q, k + 1, k + 2, c)
                guideRes = max(guideRes ?: 0.0, gr)
                progress("Guide points (overview %.0f m): ".format(gr) + guide.joinToString("; ") { "%.5f,%.5f".format(it.lat, it.lon) })
                listOf(p) + guide + listOf(q)
            }.toMutableList()
            fun label(j: Int) = if (j == 0) k + 1 else if (j == chain.size - 1) k + 2 else null
            // atGuide: the piece starts at a guide point (never tidy away one of your points)
            val first = max(wps.size, 1) // wps from here on are this leg's; before it, your point stays
            val joins = ArrayList<LatLon>() // guide points the route passes through, for rejoin
            fun add(piece: Piece, what: String, atGuide: Boolean) {
                val join = wps.size - 1
                if (atGuide && join > 0) joins.add(wps[join])
                if (wps.isEmpty()) wps.addAll(piece.wps) else wps.addAll(piece.wps.drop(1))
                legs.addAll(piece.legs)
                if (atGuide && join > 0) tidyJoin(wps, legs, join, max(res, piece.res), first)
                res = max(res, piece.res)
                progress("$what: %.1f nm at %.0f m".format(piece.legs.sumOf { it.distM } / NM, piece.res))
            }
            // Stretches between guide points, in order. A guide point off the best water (the
            // overview at 200 m lost the ICW's Coinjock land cut and put points in shallow
            // Currituck Sound; one in open water drew a 1.5 nm out-and-back spike) is skipped
            // when a full-detail piece from the last kept point to one up to SKIP_GUIDES
            // further is cheaper than the stretches it replaces. Pieces planned ahead stay
            // valid when no shortcut is taken; a trial piece's notes and messages are undone.
            val ahead = ArrayList<Pair<Piece, Snap>>() // stretches a -> a+1 -> ... with the state after each
            var a = 0
            var atA = c.snap()
            // A guide point cut off at full detail (the 320 m overview joined a pocket inside
            // the territorial sea baseline at Topsail to the sound, and a Stay inside trip was
            // refused) is dropped: the stretch heads for the next one instead
            fun stretch(j: Int): Piece {
                var dropped = 0
                while (true) {
                    progress("Routing leg ${k + 1} of ${pts.size - 1}" + if (chain.size > 2) ", stretch ${j + 1} of ${chain.size - 1}" else "")
                    val before = c.snap()
                    try {
                        return planPiece(chain[j], chain[j + 1], label(j), label(j + 1), c)
                    } catch (e: PlanException) {
                        if (j + 1 >= chain.size - 1 || dropped == MAX_DROP_GUIDES) throw e
                        c.restore(before)
                        chain.removeAt(j + 1)
                        dropped++
                    }
                }
            }
            while (a < chain.size - 1) {
                if (ahead.isEmpty()) ahead.add(stretch(a) to c.snap())
                var took = false
                for (b in min(a + SKIP_GUIDES, chain.size - 1) downTo a + 2) {
                    if (area(listOf(chain[a], chain[b]), 1.0).f != 1) continue
                    while (ahead.size < b - a) ahead.add(stretch(a + ahead.size) to c.snap())
                    if (b > chain.size - 1) continue // a guide point was dropped on the way
                    val viaCost = ahead.take(b - a).sumOf { it.first.cost }
                    val viaRes = ahead.take(b - a).maxOf { it.first.res }
                    val after = c.snap()
                    c.restore(atA)
                    val direct = try { planPiece(chain[a], chain[b], label(a), label(b), c) } catch (_: PlanException) { null }
                    if (direct != null && direct.res <= viaRes && direct.cost < viaCost * 0.97) {
                        add(direct, "Stretches ${a + 1}-$b in one", a > 0)
                        a = b
                        atA = c.snap()
                        ahead.clear()
                        took = true
                        break
                    }
                    c.restore(after)
                }
                if (!took) {
                    val (piece, state) = ahead.removeAt(0)
                    add(piece, "Stretch ${a + 1}", a > 0)
                    a += 1
                    atA = state
                }
            }
            for (at in joins) rejoin(wps, legs, at, first, c)
        }
        return Route(name, wps, legs, depthFt, airFt, res, c.messages, guideRes)
    }

    /**
     * Stretches meet at a guide point, each at its own grid's water cell for it, so the
     * route could overshoot the join by a cell and come back (a 20-40 m out-and-back in
     * Currituck Sound). Drop points within two cells of the join while the line that
     * replaces a point's two legs stays within a cell of them (the canal straightening's
     * tolerance; an out-and-back's line lies on its legs); the two legs merge.
     */
    private fun tidyJoin(wps: MutableList<LatLon>, legs: MutableList<Leg>, join: Int, resM: Double, first: Int) {
        val at = wps[join]
        fun off(p: LatLon, a: LatLon, b: LatLon): Double {
            val kx = Pack.M_PER_DEG * cos(Math.toRadians(p.lat))
            val ky = Pack.M_PER_DEG
            val dx = (b.lon - a.lon) * kx
            val dy = (b.lat - a.lat) * ky
            val px = (p.lon - a.lon) * kx
            val py = (p.lat - a.lat) * ky
            val l2 = (dx * dx + dy * dy).takeIf { it > 0 } ?: 1.0
            val t = ((px * dx + py * dy) / l2).coerceIn(0.0, 1.0)
            return hypot(px - t * dx, py - t * dy)
        }
        var changed = true
        while (changed) {
            changed = false
            for (j in first until wps.size - 1) {
                if (distanceM(wps[j], at) > 2 * resM + 1) continue
                // farthest the replacing line strays from the two legs it replaces
                val a = wps[j - 1]
                val b = wps[j + 1]
                val stray = (0..10).maxOf { t ->
                    val p = LatLon(a.lat + (b.lat - a.lat) * t / 10, a.lon + (b.lon - a.lon) * t / 10)
                    min(off(p, a, wps[j]), off(p, wps[j], b))
                }
                if (stray > resM * 1.01) continue
                val l1 = legs[j - 1]
                val l2 = legs[j]
                val lows = listOfNotNull(l1.lowM, l2.lowM)
                legs[j - 1] = Leg(distanceM(wps[j - 1], wps[j + 1]), bearing(wps[j - 1], wps[j + 1]), l1.shoalM + l2.shoalM,
                    lows.minOrNull(), (l1.notes + l2.notes).distinct())
                legs.removeAt(j)
                wps.removeAt(j)
                changed = true
                break
            }
        }
    }

    /**
     * A guide point is where the overview path crossed a 320 m cell, not always on the best
     * 20 m line: leaving Snows Cut, a 5 ft trip ran 200 m up the Cape Fear River to one and
     * back, too little of an 18 km stretch for a shortcut to skip it. Replan REJOIN_M either
     * side of the guide point at full detail and keep it if cheaper (never past your points).
     */
    private fun rejoin(wps: MutableList<LatLon>, legs: MutableList<Leg>, at: LatLon, first: Int, c: Ctx) {
        if (wps.size - 1 - first < 2) return
        val j = (first until wps.size - 1).minBy { distanceM(wps[it], at) }
        if (distanceM(wps[j], at) > 200.0) return
        var i0 = j
        var d = 0.0
        while (i0 > first && d < REJOIN_M) { d += legs[i0 - 1].distM; i0-- }
        var i1 = j
        d = 0.0
        while (i1 < wps.size - 2 && d < REJOIN_M) { d += legs[i1].distM; i1++ }
        if (i1 - i0 < 2) return
        val old = wps.subList(i0, i1 + 1).toList()
        val a = area(old, 1.0)
        if (a.f != 1) return
        val g = buildGrid(a, c.depthM, c.airM, c.bufM)
        val cells = ArrayList<Int>()
        for (k in 1 until old.size) for (x in lineCells(a, a.cell(old[k - 1]), a.cell(old[k]))) if (cells.isEmpty() || cells.last() != x) cells.add(x)
        val oldCost = pathCost(a, g, cells.toIntArray())
        val path = astar(a, g, cells.first(), cells.last()) ?: return
        if (pathCost(a, g, path) >= oldCost * 0.97) return
        val before = c.snap()
        // the replaced legs' notes are reported again on the new ones
        for (k in i0 until i1) c.seen.removeAll(legs[k].notes.toSet())
        val piece = try { planPiece(old.first(), old.last(), null, null, c) } catch (_: PlanException) { null }
        if (piece == null || piece.res != a.res || piece.legs.sumOf { it.distM } >= old.zipWithNext { p, q -> distanceM(p, q) }.sum()) {
            c.restore(before)
            return
        }
        for (k in i0 until i1) legs.removeAt(i0)
        legs.addAll(i0, piece.legs)
        for (k in i0..i1) wps.removeAt(i0)
        wps.addAll(i0, piece.wps)
        c.progress("Rejoined at guide point %.5f,%.5f: %.2f nm shorter".format(at.lat, at.lon,
            (old.zipWithNext { p, q -> distanceM(p, q) }.sum() - piece.legs.sumOf { it.distM }) / NM))
    }

    private fun fmt(v: Double) = if (v == floor(v)) v.toInt().toString() else v.toString()

    // ------------------------------------------------------------ avoiding checks

    /**
     * What [resolveChecks] did: [runs] check stretches before, [resolved] now clear,
     * [partly] down to shallow water only, [extraM] added to the route.
     */
    class Resolution(val route: Route, val runs: Int, val resolved: Int, val partly: Int, val extraM: Double) {
        val summary = summary(runs, resolved, partly, extraM)

        companion object {
            fun summary(runs: Int, resolved: Int, partly: Int, extraM: Double): String {
                if (runs == 0) return "Avoid checks: the route has no checks."
                val left = runs - resolved - partly
                    return String.format(Locale.US, "Avoid checks: %d of %d check stretches resolved", resolved, runs) +
                    (if (partly > 0) ", $partly down to shallow water only" else "") +
                    (if (resolved + partly > 0) String.format(Locale.US, " (%+.2f nm)", extraM / NM) else "") +
                    (if (left > 0) ", $left unchanged (no way around within %.1f nm or %.0f%% of the stretch)"
                        .format(Locale.US, RESOLVE_EXTRA_M / NM, RESOLVE_EXTRA_FRAC * 100) else "") + "."
            }
        }
    }

    /**
     * Replan each stretch of checked legs (Gpx.checkRuns) with what caused its checks as
     * walls: hazard buffers, piers and seawalls, snags, wrong-side channel marks, security
     * zones and, on a first try, water that may be under the safe depth. A new stretch is
     * kept when its legs have no checks (or, on the second try, only less shallow water)
     * and it is at most RESOLVE_EXTRA_M or RESOLVE_EXTRA_FRAC longer. Only walls are added,
     * so a kept stretch is never less safe; its notes come from the normal grid. [pts] are
     * your points, which stay; "connects your point to usable water" legs stay as they are.
     */
    fun resolveChecks(r: Route, pts: List<LatLon>, bufM: Double = 60.0, stayInside: Boolean = false,
                      progress: (String) -> Unit = {}): Resolution {
        inside = stayInside
        val c = Ctx(r.depthFt, r.airFt, r.depthFt * Pack.FT, r.airFt?.let { it * Pack.FT }, bufM, progress)
        val wps = r.points.toMutableList()
        val legs = r.legs.toMutableList()
        val runs = Gpx.checkRuns(r)
        val fixed = pts.toHashSet()
        fun hasCheck(l: Leg) = l.notes.any { it.contains("check") }
        var resolved = 0
        var partly = 0
        var extra = 0.0
        for ((n, run) in runs.withIndex().reversed()) { // from the end, so earlier indices stay valid
            progress("Avoiding checks: stretch ${runs.size - n} of ${runs.size}")
            var s = run.first
            var e = run.last + 1
            while (s < e && OFF_WATER in legs[s].notes) s++
            while (e > s && OFF_WATER in legs[e - 1].notes) e--
            if (s >= e) continue
            var d = 0.0
            while (s > 0 && d < RESOLVE_PAD_M && wps[s] !in fixed && !Gpx.flagged(legs[s - 1])) { d += legs[s - 1].distM; s-- }
            d = 0.0
            while (e < wps.size - 1 && d < RESOLVE_PAD_M && wps[e] !in fixed && !Gpx.flagged(legs[e])) { d += legs[e].distM; e++ }
            val old = wps.subList(s, e + 1).toList()
            val oldLegs = legs.subList(s, e).toList()
            val oldM = oldLegs.sumOf { it.distM }
            val a = area(old, 1.0)
            if (a.f != 1) continue
            val g = buildGrid(a, c.depthM, c.airM, c.bufM)
            val sec = securityCells(a, g)
            val sp = a.cell(old.first())
            val sq = a.cell(old.last())
            // notes reported elsewhere on the route (bridges, cables, areas) are not repeated
            val seen = HashSet<String>()
            for ((k, l) in legs.withIndex()) if (k < s || k >= e) seen.addAll(l.notes)
            val limit = oldM + max(RESOLVE_EXTRA_M, oldM * RESOLVE_EXTRA_FRAC)
            var best: Pair<List<LatLon>, List<Leg>>? = null
            var clear = false
            for (shallowOk in listOf(false, true)) {
                val strict = strictGrid(a, g, sec, shallowOk, sp, sq)
                val path = astar(a, strict, sp, sq) ?: continue
                if (path.size < 2) continue
                val idx = simplify(a, strict, path)
                val nw = idx.map { a.latlon(it) }.toMutableList()
                nw[0] = old.first()
                nw[nw.size - 1] = old.last()
                val nl = legs(a, g, nw, idx, HashSet(seen))
                if (nl.sumOf { it.distM } > limit) continue
                val left = nl.filter { Gpx.flagged(it) }
                if (left.isEmpty()) { best = nw to nl; clear = true; break }
                if (shallowOk && left.none { hasCheck(it) } && oldLegs.any { hasCheck(it) } &&
                    nl.sumOf { it.shoalM } <= oldLegs.sumOf { it.shoalM } + 1.0) best = nw to nl
            }
            val (nw, nl) = best ?: continue
            for (k in s until e) legs.removeAt(s)
            legs.addAll(s, nl)
            for (k in s..e) wps.removeAt(s)
            wps.addAll(s, nw)
            extra += nl.sumOf { it.distM } - oldM
            if (clear) resolved++ else partly++
        }
        val text = Resolution.summary(runs.size, resolved, partly, extra)
        return Resolution(Route(r.name + "_adjusted", wps, legs, r.depthFt, r.airFt, r.resM, r.messages + text, r.guideM),
            runs.size, resolved, partly, extra)
    }

    /** The grid with what causes checks as walls (shallow water too unless [shallowOk]); the stretch's ends stay open. */
    private fun strictGrid(a: Area, g: Grid, sec: BooleanArray, shallowOk: Boolean, sp: Int, sq: Int): Grid {
        val nav = g.nav.copyOf()
        val ok = g.ok.copyOf()
        val cost = g.cost.copyOf()
        for (i in 0 until a.n) {
            if (!ok[i] || i == sp || i == sq) continue
            if (g.soft[i] || g.pier[i] || sec[i] || (!shallowOk && nav[i].toInt() == MARGINAL)) {
                ok[i] = false
                cost[i] = Float.POSITIVE_INFINITY
                nav[i] = BLOCKED.toByte()
            }
        }
        return Grid(nav, ok, cost, g.prox, g.planes, g.nopass, g.rad, g.k, g.pier, g.snag, g.soft, g.gate)
    }

    /** Cells of charted security zones (reported, never blocked by the planner itself). */
    private fun securityCells(a: Area, g: Grid): BooleanArray {
        val out = BooleanArray(a.n)
        val sw = a.latlon(a.n - a.w)
        val ne = a.latlon(a.w - 1)
        val zones = pack.notes.filter { z ->
            z.kind == "resare" && z.rules.any { it.startsWith("security zone") } &&
                z.bbox[0] <= ne.lon && z.bbox[2] >= sw.lon && z.bbox[1] <= ne.lat && z.bbox[3] >= sw.lat
        }
        if (zones.isEmpty()) return out
        for (i in 0 until a.n) if ((g.planes[Pack.P_FLAGS][i].toInt() and Pack.F_RESNOTE) != 0) {
            val p = a.latlon(i)
            if (zones.any { it.near(p.lat, p.lon, a.res * 0.75) }) out[i] = true
        }
        return out
    }
}
