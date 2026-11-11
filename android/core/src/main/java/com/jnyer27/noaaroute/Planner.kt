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
    /** Points of the plan in progress, so a depth suggestion can be tried as a full plan. */
    private var tripPts: List<LatLon> = emptyList()
    /** A trial plan for a depth suggestion is running. It must not start another trial. */
    private var probing = false

    companion object {
        const val NM = 1852.0
        const val ROUNDING_M = 0.05

        /**
         * Whole feet, as the chart labels a depth (tools/build_enc_tiles.py feet).
         * 1.8 m is 6 and 1.5 m is 5. Under half a foot prints as 0. A tie rounds
         * to the even foot, matching Python's round.
         */
        fun printedFeet(meters: Double): Int {
            val f = meters / Pack.FT
            if (abs(f) < 0.5) return 0
            val neg = f < 0
            val a = abs(f)
            val base = floor(a)
            val frac = a - base
            val up = if (abs(frac - 0.5) < 1e-8) (base.toInt() % 2 != 0) else frac > 0.5
            val feet = (if (up) base + 1.0 else base).toInt()
            return if (neg) -feet else feet
        }

        /** Safe depth in feet from the meters [buildGrid] is given, stable for 5 * 0.3048. */
        fun depthFeet(depthM: Double) = Math.rint(depthM / Pack.FT * 100.0) / 100.0

        /**
         * A sounding the chart would label strictly under the safe depth, in whole
         * feet. A label equal to the safe depth is not a hazard: 1.5 m prints as 5,
         * and 1.4 m prints as 5 too, so neither gets the buffer at 5 ft. A label of
         * 4 does. The comparison is those whole feet, not meters minus [ROUNDING_M].
         * That 5 cm slack is less than half a foot, so it never separated one chart
         * label from the next, and it did treat some 5 ft labels (1.4 m is 4.6 ft)
         * as hazards. [depthFt] is the safe depth in feet ([depthFeet] makes
         * 5 * 0.3048 come back as 5.00). The 1e-6 ft only stops a value a hair over
         * 5.00 from counting a printed 5 as shallower; a label one foot under is
         * still a hazard. Depth areas keep [ROUNDING_M].
         */
        fun soundingShallow(meters: Double, depthFt: Double) = printedFeet(meters) < depthFt - 1e-6

        /**
         * Air draft from the form, in feet. A blank field is allowed only when
         * [skipBridges] is on, the explicit choice to not check bridges and cables,
         * and the result is then null. A height that is filled in is used either way.
         */
        fun airDraftFt(text: String, skipBridges: Boolean): Double? {
            val t = text.trim()
            if (t.isEmpty()) {
                if (skipBridges) return null
                throw PlanException("Enter an air draft in feet, or turn on Don't check bridges and cables.")
            }
            val v = t.toDoubleOrNull()
            if (v == null || v <= 0.0 || v >= 300.0) throw PlanException("Air draft must be feet")
            return v
        }
        const val MAX_CELLS = 4_000_000
        const val CUT_OFF_M = 250.0 // how far an end may move to escape a cut-off pocket
        const val MOVE_ENDS_M = 0.5 * NM // ...and with Move ends to navigable water, from any pocket or along the water
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
        /**
         * Dredged areas and fairways no wider than this are narrow channels: the
         * route is pulled to the middle, and a straightened leg must stay off the
         * edge. Wider ones keep a flat cost. There is no narrow flag in the pack.
         * The builder's narrow strip is a different rule (an ordinary depth area
         * at most two cells wide keeps its deeper center) and does not mark
         * dredged areas. 120 m is six 20 m cells. The dredged cut the Ego Alley
         * route follows south of Middle River (about 39.23 N, 76.396 W) is two
         * or three cells wide, about 60 to 80 m, so it counts. A middle more
         * than 60 m from every edge is a wide area and is left alone.
         */
        private const val NARROW_CHANNEL_M = 120.0
        /** Extra cost at the edge of a narrow channel, as a fraction of the way out from the middle. The middle is unchanged. */
        private const val CENTER_EDGE_COST = 1f
        /** A chord in a narrow channel stays this share of the local width off the edge, and never has to stay more than [CENTER_KEEP_M]. */
        private const val CENTER_KEEP_FRAC = 0.25
        private const val CENTER_KEEP_M = 30.0
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
        /** Extra cost on 0-6 ft water beside a channel when a nearby sounding prints at or under the safe depth. */
        private const val BESIDE_CHANNEL_COST = 8f
        /** How far, in cells, a line that tidies a via point may stray from the legs it replaces. */
        private const val VIA_TIDY = 0.75
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
        /** On a leg made by Delete leg: a straight line the planner did not choose. */
        const val JOINED_NOTE = "a straight leg you made with Delete leg, not planned"
        /** Import GPX drops track points within this of the line through their neighbours. */
        const val IMPORT_TOL_M = 10.0
        /** Import GPX checks legs in windows of up to this span (one grid each). */
        private const val IMPORT_SPAN_DEG = 0.1
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

    /**
     * [pier]: costly cells a leg may not cut across (piers, seawalls, passable snags).
     * [narrowDist] and [narrowHalf]: cell steps from the edge of a narrow channel, and
     * the local half-width in the same steps. Both are 0 outside a narrow channel.
     */
    private class Grid(val nav: ByteArray, val ok: BooleanArray, val cost: FloatArray, val prox: ByteArray,
                       val planes: Array<ByteArray>, val nopass: BooleanArray, val rad: Int, val k: Int,
                       val pier: BooleanArray, val snag: BooleanArray, val soft: BooleanArray, val gate: BooleanArray,
                       val narrowDist: ByteArray, val narrowHalf: ByteArray) {
        /**
         * A dredged channel cell deep enough. A step between two of them may cut a blocked
         * corner: the ICW's Coinjock cut is charted as a 1-cell dredged staircase along the
         * bank, and going round each land corner through the 0-6 ft cell beside it (10x near
         * shore) made the cut cost more than 12 nm of open Currituck Sound.
         */
        fun channel(i: Int) = nav[i].toInt() == SAFE && planes[Pack.P_DEPTH][i].toInt() and DREDGED != 0
    }

    /**
     * Cell steps from the edge of a narrow dredged channel or fairway, and the
     * local half-width in the same steps. Both arrays are 0 outside one.
     *
     * The channel is usable water charted at least the safe depth inside a
     * dredged area or fairway. Steps are 4-connected from a cell that touches
     * anything else. The window border is not an edge, so a channel running
     * off the grid is not pushed off a side the grid has not seen. A cell
     * center is (steps - 0.5) cells from the edge.
     *
     * Wide where the middle is more than [NARROW_CHANNEL_M] / 2 from every
     * edge. That middle, and the cells out to the edge around it, stay 0, so
     * a wide basin keeps a flat cost. A narrow cut that opens into the basin
     * stays narrow past the mouth. Half-width spreads only downhill, from the
     * middle of a cross-section to its own edge cells, not along the channel
     * into a thinner pinch.
     */
    private fun narrowChannel(a: Area, nav: ByteArray, ok: BooleanArray, dredged: BooleanArray): Pair<ByteArray, ByteArray> {
        val n = a.n
        val w = a.w
        val h = a.h
        val distOut = ByteArray(n)
        val halfOut = ByteArray(n)
        // (steps - 0.5) * res > NARROW/2 is the middle of something wider than NARROW.
        val cap = floor(NARROW_CHANNEL_M / (2.0 * a.res) + 0.5).toInt()
        if (cap < 1) return distOut to halfOut
        val chan = BooleanArray(n) { i -> ok[i] && dredged[i] && nav[i].toInt() == SAFE }
        val dist = IntArray(n) { -1 }
        var frontier = IntArray(n)
        var next = IntArray(n)
        var fn = 0
        for (i in 0 until n) if (chan[i]) {
            val r = i / w
            val c = i % w
            val edge = (r > 0 && !chan[i - w]) || (r < h - 1 && !chan[i + w]) ||
                (c > 0 && !chan[i - 1]) || (c < w - 1 && !chan[i + 1])
            if (edge) { dist[i] = 1; frontier[fn++] = i }
        }
        var step = 1
        while (fn > 0 && step <= cap) {
            step++
            var nn = 0
            for (f in 0 until fn) {
                val i = frontier[f]
                val r = i / w
                val c = i % w
                val nd = dist[i] + 1
                if (r > 0) { val j = i - w; if (chan[j] && dist[j] < 0) { dist[j] = nd; next[nn++] = j } }
                if (r < h - 1) { val j = i + w; if (chan[j] && dist[j] < 0) { dist[j] = nd; next[nn++] = j } }
                if (c > 0) { val j = i - 1; if (chan[j] && dist[j] < 0) { dist[j] = nd; next[nn++] = j } }
                if (c < w - 1) { val j = i + 1; if (chan[j] && dist[j] < 0) { dist[j] = nd; next[nn++] = j } }
            }
            val t = frontier; frontier = next; next = t
            fn = nn
        }
        val wide = BooleanArray(n)
        fn = 0
        for (i in 0 until n) if (chan[i] && (dist[i] < 0 || dist[i] > cap)) { wide[i] = true; frontier[fn++] = i }
        repeat(cap) {
            var nn = 0
            for (f in 0 until fn) {
                val i = frontier[f]
                val r = i / w
                val c = i % w
                if (r > 0) { val j = i - w; if (chan[j] && !wide[j]) { wide[j] = true; next[nn++] = j } }
                if (r < h - 1) { val j = i + w; if (chan[j] && !wide[j]) { wide[j] = true; next[nn++] = j } }
                if (c > 0) { val j = i - 1; if (chan[j] && !wide[j]) { wide[j] = true; next[nn++] = j } }
                if (c < w - 1) { val j = i + 1; if (chan[j] && !wide[j]) { wide[j] = true; next[nn++] = j } }
            }
            val t = frontier; frontier = next; next = t
            fn = nn
        }
        val buckets = Array(cap + 1) { ArrayList<Int>() }
        val half = IntArray(n)
        for (i in 0 until n) if (chan[i] && !wide[i] && dist[i] in 1..cap) {
            half[i] = dist[i]
            buckets[dist[i]].add(i)
        }
        for (s in cap downTo 1) for (i in buckets[s]) {
            val hgt = half[i]
            val r = i / w
            val c = i % w
            fun give(j: Int) { if (half[j] != 0 && dist[j] < s && half[j] < hgt) half[j] = hgt }
            if (r > 0) give(i - w)
            if (r < h - 1) give(i + w)
            if (c > 0) give(i - 1)
            if (c < w - 1) give(i + 1)
        }
        for (i in 0 until n) if (half[i] > 0) {
            distOut[i] = dist[i].toByte()
            halfOut[i] = half[i].toByte()
        }
        return distOut to halfOut
    }

    /**
     * Maximum of [src] within [steps] 4-connected steps, moving only through [safe]
     * cells. Land and shoal water between two basins stay a barrier, so open sea
     * across a beach does not count as deeper water beside a lagoon.
     */
    private fun dilateMax(src: ByteArray, safe: BooleanArray, w: Int, h: Int, steps: Int): ByteArray {
        var cur = src.copyOf()
        var nxt = ByteArray(src.size)
        val n = w * h
        for (step in 0 until steps) {
            var changed = false
            for (i in 0 until n) {
                if (!safe[i]) continue
                var m = cur[i].toInt() and 0xff
                val r = i / w
                val c = i % w
                if (r > 0 && safe[i - w]) {
                    val v = cur[i - w].toInt() and 0xff
                    if (v > m) m = v
                }
                if (r < h - 1 && safe[i + w]) {
                    val v = cur[i + w].toInt() and 0xff
                    if (v > m) m = v
                }
                if (c > 0 && safe[i - 1]) {
                    val v = cur[i - 1].toInt() and 0xff
                    if (v > m) m = v
                }
                if (c < w - 1 && safe[i + 1]) {
                    val v = cur[i + 1].toInt() and 0xff
                    if (v > m) m = v
                }
                nxt[i] = m.toByte()
                if (m != (cur[i].toInt() and 0xff)) changed = true
            }
            val t = cur
            cur = nxt
            nxt = t
            if (!changed) break
        }
        return cur
    }

    private fun buildGrid(a: Area, depthM: Double, airM: Double?, bufM: Double, optimistic: Boolean = false): Grid {
        val planes = pack.window(a.r0, a.c0, a.h, a.w, a.f, depthM, optimistic)
        val n = a.n
        val w = a.w
        val h = a.h
        val depthFt = depthFeet(depthM)
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
                sv != 0 && soundingShallow((sv - 1) / 10.0, depthFt) -> HAZARD
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
        // A pier cell on the span, next to the dredged opening, is the fender.
        // Leaving it passable put Coinjock's leg on that cell. The opening stays open.
        for (i in 0 until n) if (ok[i] && (planes[Pack.P_HAZARD][i].toInt() and 0xff) == Pack.HZ_PIER &&
            (planes[Pack.P_FLAGS][i].toInt() and (Pack.F_SPAN or Pack.F_BRIDGE)) != 0 &&
            !(dredged[i] && nav[i].toInt() == SAFE)) {
            val r = i / w
            val c = i % w
            var opening = false
            for (rr in max(0, r - 2)..min(h - 1, r + 2)) for (cc in max(0, c - 2)..min(w - 1, c + 2)) {
                val j = rr * w + cc
                if (dredged[j] && nav[j].toInt() == SAFE && spanok[j]) opening = true
            }
            if (opening) ok[i] = false
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
        // Water charted as a band that straddles the safe depth (the 0-6 ft area at 5 ft)
        // is not a wall, but a route that skims its edge in the next deeper band is one
        // cell from the shoal, and a straight leg then cuts the shoal. Count that band as
        // something to keep off, the same way as land, except inside a dredged area or
        // fairway (a shoaled channel, not a shoal to avoid). Only safe cells take the
        // distance: the band's own cost stays the marginal cost, so a creek still leads out.
        // The penalty is how much closer a cell is than the deepest safe water within
        // kf steps through safe water. In open water that cell is a full kf away, so
        // the rise matches the shore rise and the route passes a knoll on its deep side.
        // In a strip that is already the deepest water there, the middle pays nothing
        // extra. The steps do not cross land: a square neighborhood treated the Gulf
        // across Santa Rosa Island as deeper water beside Big Lagoon, and Pensacola to
        // Mobile left through the pass (75.8 nm of Gulf instead of the GIWW).
        val shoal = ByteArray(n) { kf.toByte() }
        fn = 0
        for (i in 0 until n) if (ok[i] && nav[i].toInt() == MARGINAL && !dredged[i]) {
            shoal[i] = 0
            val r = i / w
            val c = i % w
            var edge = false
            for (rr in max(r - 1, 0)..min(r + 1, h - 1)) for (cc in max(c - 1, 0)..min(c + 1, w - 1)) {
                val j = rr * w + cc
                if (ok[j] && nav[j].toInt() == SAFE) edge = true
            }
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
                    if (ok[j] && nav[j].toInt() == SAFE && shoal[j] > step) {
                        shoal[j] = step.toByte(); next[nn++] = j
                    }
                }
            }
            val t = frontier; frontier = next; next = t
            fn = nn
        }
        // Pretended shore distance for the keep-off. A cell as far from the band as
        // any safe water it can reach in kf steps keeps kf (no extra cost). A cell
        // closer than that water is treated as nearer by the difference. Where that
        // water is a full kf away, this is the raw shoal distance, the same rise as
        // beside land.
        val shoalNear = ByteArray(n) { kf.toByte() }
        run {
            val measured = ByteArray(n)
            val safe = BooleanArray(n)
            var any = false
            for (i in 0 until n) if (ok[i] && nav[i].toInt() == SAFE) {
                safe[i] = true
                measured[i] = shoal[i]
                if (shoal[i] != kf.toByte()) any = true
            }
            // Only the overview consults this. Full detail uses the raw distance.
            if (any && a.f > 1) {
                val best = dilateMax(measured, safe, w, h, kf)
                for (i in 0 until n) if (safe[i]) {
                    val s = shoal[i].toInt() and 0xff
                    val b = best[i].toInt() and 0xff
                    val gap = if (b > s) b - s else 0
                    shoalNear[i] = (kf - gap).toByte()
                }
            }
        }
        val (narrowDist, narrowHalf) = narrowChannel(a, nav, ok, dredged)
        val cost = FloatArray(n) { Float.POSITIVE_INFINITY }
        val prox = ByteArray(n)
        // 0-6 ft water within eight cells of a safe dredged channel or fairway.
        // Eight cells is 160 m at 20 m: the step off the channel at Snows Cut and
        // Bogue Sound, not the open sound beyond that.
        val besideChannel = BooleanArray(n)
        if (a.f == 1) for (i in 0 until n) if (ok[i] && dredged[i] && nav[i].toInt() == SAFE) {
            val r = i / w
            val c = i % w
            for (rr in max(0, r - 8)..min(h - 1, r + 8)) for (cc in max(0, c - 8)..min(w - 1, c + 8)) {
                if (max(abs(rr - r), abs(cc - c)) <= 8) besideChannel[rr * w + cc] = true
            }
        }
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
            // Open safe water keeps off the 0-6 ft band. shoalNear is the gap to deeper
            // water reached through safe water, so a strip with no deeper side pays
            // nothing extra. On the 20 m grid the steep shore rise applies too, and the
            // route passes a knoll on its deep side. On a coarse overview only the gentle
            // rise applies: the steep rise at 60 m covers a whole lagoon, and Pensacola
            // to Mobile left through the pass. A dredged channel or fairway does not pay
            // for its own bank: charging for that sent Wrightsville to Southport out
            // Masonboro Inlet. The band's own cost is unchanged. prox records the shoal
            // either way, so a straight leg cannot move closer to it than the path did.
            val inChannel = dredged[i] && nav[i].toInt() == SAFE
            val landNear = dist[i].toInt()
            // Full detail uses the raw distance, the same steep rise as beside land, so
            // the Bay Bridge knoll is passed well clear. The overview uses the relieved
            // distance (no extra charge where the strip is already the deepest water)
            // and only the gentle rise: the steep rise at 60 m covers a whole lagoon.
            val rawShoal = shoal[i].toInt() and 0xff
            val shoalAs = if (nav[i].toInt() == SAFE && !inChannel) {
                if (a.f == 1) rawShoal else shoalNear[i].toInt() and 0xff
            } else kf
            val nearFar = min(landNear, shoalAs)
            val nearSteep = if (a.f == 1) nearFar else landNear
            val p = k - min(nearSteep, k)
            prox[i] = (k - min(nearFar, k)).toByte()
            var c = 1f + pc * p * (if (inChannel) DREDGED_PROX else 1f)
            // Centering: a gentle rise over FAR_M, so in a gap narrower than twice that (between
            // shoals, in a river) the route keeps to the middle instead of the edge of the
            // steep zone (routes hugged the 100 m line around buffered shoal soundings)
            c *= 1f + FAR_COST * (kf - nearFar) / kf * (if (inChannel) DREDGED_PROX else 1f)
            if (nav[i].toInt() == MARGINAL) {
                c *= 4f
                // 0-6 ft water within MARG_SHORE_M of land, drying ground or a hazard wall: such
                // water shoals toward the shore, and a 5 ft route took a 200 m marsh gut beside
                // Goosecastle Point (Currituck Sound) 16 m off the bank to stay in the ICW longer
                // (not in a charted dredged area or fairway: a shoaled stretch of the ICW south
                // of Masonboro Inlet cost 141 a cell, and Wrightsville to Southport went to sea)
                if (dist[i] <= margRing && !byChannel[i] && !dredged[i]) c *= MARG_SHORE_COST
                // Open 0-6 ft water beside a fairway or dredged channel. At 4 ft a 3.9 ft
                // sounding is not a wall, and Bogue Sound left the 6-12 ft fairway for that
                // water. Stepping off the channel costs more than staying in it. A creek
                // with no channel beside it is unchanged.
                // Full detail only. On the overview the same rise covered Big Lagoon and
                // Pensacola to Mobile left through the pass.
                if (a.f == 1 && !dredged[i] && besideChannel[i]) c *= BESIDE_CHANNEL_COST
            }
            // A deck cell that is usable only because a passable span was widened by one
            // cell. The opening itself stays cheap, so the line takes the span that opens
            // instead of the deck beside it. When the opening misses every cell center,
            // the widened cells are the passage and they all cost the same.
            if (nopass[i] && spanWide[i] && !spanok[i]) c *= 8f
            // One of: pier or seawall, passable snag, channel marker or its wrong side
            when {
                (planes[Pack.P_HAZARD][i].toInt() and 0xff) == Pack.HZ_PIER && !fender[i] || sideSpan[i] -> c *= PIER_COST
                snag[i] -> c *= SNAG_COST
                gate[i] -> c *= GATE_COST
            }
            if (soft[i]) c *= if (inChannel) DREDGED_SOFT_COST else SOFT_COST
            // Narrow channel only: the middle keeps today's cost (the ICW stays
            // cheaper than going outside) and the edge costs more, so a bend is
            // not taken on the short side. Wide dredged areas stay flat.
            val nd = narrowDist[i].toInt() and 0xff
            val nh = narrowHalf[i].toInt() and 0xff
            if (nd > 0 && nh > nd) c *= 1f + CENTER_EDGE_COST * (1f - nd.toFloat() / nh.toFloat())
            cost[i] = c
        }
        return Grid(nav, ok, cost, prox, planes, nopass, rad, k, pier, snag, soft, gate, narrowDist, narrowHalf)
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
     * (so not across a point of land into the next creek), that is [clearWater], within
     * maxM of the way there; with its straight distance from p. Null if start already is
     * one or none is that close.
     */
    /**
     * Where Move ends may leave a point: usable water, 0-6 ft style bands included, clear of
     * charted hazards and their buffers, piers, snags and channel-mark lines. Jason chose
     * (2026-11-03, issue #27) to keep a point in water that may be under the safe depth and
     * flag its legs, rather than walk it out to water charted at least that depth.
     */
    private fun clearWater(g: Grid, i: Int) =
        g.ok[i] && g.nav[i].toInt().let { it == SAFE || it == MARGINAL } &&
            !g.soft[i] && !g.pier[i] && !g.snag[i] && !g.gate[i]

    /**
     * Nearest clear water along usable water from start, at most maxM along the way
     * (8-connected, diagonals sqrt 2, not across a corner of land). Null when start is
     * clear already or none is that close. Steps counted 4-connected made a diagonal
     * creek 40% longer: Podickery Point's 3 ft water, 430 m away, was out of reach of 400 m.
     */
    private fun nearestNavigable(a: Area, g: Grid, p: LatLon, start: Int, maxM: Double = MOVE_ENDS_M): Pair<Int, Double>? {
        if (clearWater(g, start)) return null
        val dist = FloatArray(a.n) { Float.MAX_VALUE }
        val heap = Heap(1024)
        val lim = (maxM / a.res).toFloat()
        val diag = sqrt(2.0).toFloat()
        dist[start] = 0f
        heap.push(0f, start)
        while (heap.size > 0) {
            val d = heap.keys[0]
            val i = heap.pop()
            if (d > dist[i]) continue
            if (clearWater(g, i)) return i to distanceM(p, a.latlon(i))
            val r = i / a.w
            val c = i % a.w
            for (dr in -1..1) for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val r2 = r + dr
                val c2 = c + dc
                if (r2 !in 0 until a.h || c2 !in 0 until a.w) continue
                val j = r2 * a.w + c2
                if (!g.ok[j]) continue
                if (dr != 0 && dc != 0 && (!g.ok[r2 * a.w + c] || !g.ok[r * a.w + c2])) continue
                val nd = d + if (dr != 0 && dc != 0) diag else 1f
                if (nd <= lim && nd < dist[j]) { dist[j] = nd; heap.push(nd, j) }
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
    private class Enc(val cells: Int, val edge: Boolean, val walls: Map<String, Pair<Int, Int>>, val hazardWalls: List<Int>, val nopassAt: List<Int>)

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
        val nopassAt = ArrayList<Int>()
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
                if (why == "nopass") recordLimit(g, j, nopassAt)
            }
        }
        return Enc(cnt, edge, walls, hazardWalls, nopassAt)
    }

    /**
     * Keep the bridge and cable cells that actually limit the boat. An opening
     * span is not that limit (Bear Creek's 8 ft swing opens). The flood meets the
     * inner bridges first; a fixed cap of the first cells never reached the
     * entrance cable.
     */
    private fun recordLimit(g: Grid, j: Int, list: ArrayList<Int>) {
        fun flags(i: Int) = g.planes[Pack.P_FLAGS][i].toInt()
        val fl = flags(j)
        val cable = (fl and Pack.F_CABLE) != 0
        if (!cable && (fl and Pack.F_OPENING) != 0) return
        fun units(i: Int): Int {
            val f = flags(i)
            val u = if ((f and Pack.F_CABLE) != 0) g.planes[Pack.P_CABLECLR][i].toInt() and 0xff
                else g.planes[Pack.P_SPANCLR][i].toInt() and 0xff
            return if (u == 0) 999 else u
        }
        fun same(i: Int) = ((flags(i) and Pack.F_CABLE) != 0) == cable
        val have = list.count { same(it) }
        if (have < if (cable) 24 else 40) { list.add(j); return }
        val worst = list.filter { same(it) }.maxBy { units(it) }
        if (units(j) < units(worst)) {
            list.remove(worst)
            list.add(j)
        }
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

    /** A straight line from usable water back to your point crosses a bridge or cable the air draft cannot clear. */
    private class Crossing(val note: String, val land: Boolean)

    /** [feet] to suggest, and whether any shallower depth even leaves the local window. */
    private class DepthHint(val feet: Double?, val localEscape: Boolean)

    private fun isLand(g: Grid, i: Int): Boolean {
        val v = g.nav[i].toInt()
        return v != SAFE && v != MARGINAL && v != SHALLOW && v != HAZARD && v != STRUCT && v != SEA
    }

    /**
     * A cell the route cannot enter because of a bridge or cable. An opening span
     * the route may use is ok water and is not this.
     */
    private fun structureWall(g: Grid, i: Int): Boolean {
        if (g.ok[i] || !g.nopass[i]) return false
        val fl = g.planes[Pack.P_FLAGS][i].toInt() and 0xff
        return fl and (Pack.F_CABLE or Pack.F_DECK or Pack.F_SPAN or Pack.F_BRIDGE) != 0
    }

    /** Charted feet in a bridge or cable note. Unknown clearance sorts as the lowest. */
    private fun clearanceFt(note: String): Double {
        if (note.contains("not charted") || note.contains("no charted")) return -1.0
        val m = Regex("""clearance (\d+(?:\.\d+)?) ft""").find(note) ?: return Double.MAX_VALUE
        return m.groupValues[1].toDouble()
    }

    private fun planePhrase(g: Grid, i: Int): String {
        val fl = g.planes[Pack.P_FLAGS][i].toInt() and 0xff
        fun ft(units: Int): String {
            if (units == 0) return "clearance not charted"
            return "clearance %.0f ft".format((units / 2.0) / Pack.FT)
        }
        if (fl and Pack.F_CABLE != 0)
            return "overhead cable (${ft(g.planes[Pack.P_CABLECLR][i].toInt() and 0xff)})"
        if (fl and Pack.F_DECK != 0 && fl and Pack.F_SPAN == 0) return "a bridge with no charted clearance"
        return "a bridge span (${ft(g.planes[Pack.P_SPANCLR][i].toInt() and 0xff)})"
    }

    /** The lowest charted bridge or cable on these cells, named when the pack has a note. */
    private fun structurePhrase(a: Area, g: Grid, cells: List<Int>, airFt: Double? = null): String {
        val notes = ArrayList<String>()
        val step = max(1, cells.size / 40)
        var k = 0
        while (k < cells.size) {
            val i = cells[k]
            val fl = g.planes[Pack.P_FLAGS][i].toInt() and 0xff
            if (fl and Pack.F_CABLE != 0) notes.addAll(featureNotes("cable", a.latlon(i), a.res * 0.6, airFt))
            if (fl and (Pack.F_BRIDGE or Pack.F_DECK or Pack.F_SPAN) != 0)
                notes.addAll(featureNotes("bridge", a.latlon(i), a.res * 1.5, airFt))
            k += step
        }
        return lowestStructure(notes.distinct(), airFt).ifEmpty {
            if (cells.isEmpty()) "" else planePhrase(g, cells.minBy { clearanceFt(planePhrase(g, it)) })
        }
    }

    /** An opening bridge is not the wall that stops a boat: the fixed span and the cable are. */
    private fun openingNote(s: String) =
        s.contains("may need an opening") || s.contains("has to open") || s.contains("clears when closed")

    /**
     * The fixed span or cable under the air draft, lowest first. An opening bridge's
     * closed clearance is left out when a fixed span or cable is on the same wall, and
     * a "clearance not charted" note does not outrank one that has a number.
     */
    private fun lowestStructure(notes: List<String>, airFt: Double?): String {
        if (notes.isEmpty()) return ""
        val known = notes.filter { !openingNote(it) && !it.contains("not charted") }
        val under = if (airFt != null) known.filter { clearanceFt(it) <= airFt + 0.05 } else known
        val pick = when {
            under.isNotEmpty() -> under
            known.isNotEmpty() -> known
            else -> notes
        }
        return pick.sortedBy { clearanceFt(it) }.distinct().take(3).joinToString("; ")
    }

    /**
     * The straight line from [water] (usable water the route reached) to [user]
     * crosses a bridge or cable the air draft cannot clear. The note names the
     * lowest charted clearance on that line. Land on the line, including the
     * point itself, is recorded so the stop can say the line does not cross the
     * shore. A dock a few cells inland, with no such structure, is not a crossing.
     */
    private fun connectorCrossing(a: Area, g: Grid, water: Int, user: LatLon): Crossing? {
        val userCell = a.cell(user)
        if (userCell == water) return null
        val blocked = ArrayList<Int>()
        var land = isLand(g, userCell)
        for (i in lineCells(a, water, userCell)) {
            if (i == water) continue
            if (structureWall(g, i)) blocked.add(i)
            else if (isLand(g, i)) land = true
        }
        if (blocked.isEmpty()) return null
        val note = structurePhrase(a, g, blocked).ifEmpty { "a bridge or overhead cable without enough charted clearance" }
        return Crossing(note, land)
    }

    /** The user's point is inside charted entry-prohibited water, or null when it is not. */
    private fun entryProhibited(a: Area, g: Grid, p: LatLon): String? {
        val i = a.cell(p)
        if (g.planes[Pack.P_FLAGS][i].toInt() and Pack.F_PROHIB == 0) return null
        return featureNotes("resare", p, a.res * 2.0).firstOrNull {
            it.contains("entry prohibited") && !it.contains("may be prohibited")
        } ?: "restricted area: entry prohibited"
    }

    /** The pin is inside an entry-prohibited area. The route stops outside it and draws no line in. */
    private fun noteProhibited(c: Ctx, n: Int, user: LatLon, water: LatLon, note: String) {
        c.messages.removeAll { it.startsWith("Point $n:") || it.startsWith("Point $n ") }
        val dist = distanceM(user, water).roundToInt()
        val msg = "Point $n is in charted water where entry is prohibited: $note. The route ends on the last water outside it, " +
            "$dist m away, and does not draw a line into it."
        if (msg !in c.messages) c.messages.add(msg)
        c.moved.add(n)
    }

    /**
     * Move ends reached the pin by a long stretch of water charted under the safe depth,
     * and a sounding in that stretch prints under it. Drop that stretch, including the
     * line back to the pin, and say so once. Rock Hall at 6 ft was crossing a 1 ft
     * sounding after about 750 m in the 0-6 ft band.
     */
    private fun stopForShoalBar(c: Ctx, n: Int, user: LatLon, wps: MutableList<LatLon>, ls: MutableList<Leg>) {
        // Only a point that was moved out of a pocket. A point that merely started in
        // shallow water and still leads out (Ego Alley at 6 ft) is left as planned.
        if (c.messages.none { it.startsWith("Point $n:") && it.contains("leads out") }) return
        var window = 0.0
        var shoal = 0.0
        var shallow = Double.MAX_VALUE
        var cut = ls.size
        var k = ls.size - 1
        while (k >= 0 && window < 4000.0) {
            val l = ls[k]
            window += l.distM
            shoal += l.shoalM
            val low = l.lowM
            if (low != null && soundingShallow(low, c.depthFt)) {
                val ft = low / Pack.FT
                if (ft < shallow) shallow = ft
            }
            if (l.shoalM > 100.0) cut = k
            k--
        }
        if (shoal < 400.0 || shallow == Double.MAX_VALUE || cut <= 0 || cut >= ls.size) return
        while (ls.size > cut) ls.removeAt(ls.size - 1)
        while (wps.size > cut + 1) wps.removeAt(wps.size - 1)
        c.messages.removeAll { it.startsWith("Point $n:") || it.startsWith("Point $n ") || it.startsWith("Point $n is ") }
        val dist = distanceM(user, wps.last()).roundToInt()
        c.messages.add("Point $n: the only charted way in is water charted under your ${fmt(c.depthFt)} ft, " +
            "including a charted %.1f ft sounding. The route stops on the near side, %d m away, and does not draw a line across it.".format(shallow, dist))
        c.moved.add(n)
    }

    /** Replace the Move ends "check the way between" lines: the connector is not drawn. */
    private fun noteStopped(c: Ctx, n: Int, user: LatLon, water: LatLon, cross: Crossing) {
        c.messages.removeAll { it.startsWith("Point $n:") || it.startsWith("Point $n ") }
        val shore = if (cross.land) " or the land beyond" else ""
        val dist = distanceM(user, water).roundToInt()
        val lead = if (moveEnds) "With Move ends to navigable water on, the route stops" else "The route stops"
        val msg = "Point $n: ${cross.note} blocks the way to your point. $lead on the near side, $dist m away, and does not cross it$shore."
        if (msg !in c.messages) c.messages.add(msg)
        c.moved.add(n)
    }

    /**
     * Deepest safe depth, in 0.5 ft steps below the one that failed, at which a
     * full plan joins the trip's ends. Reaching the edge of a 4 km window is
     * only a filter: that water can still be cut off from the other end by a
     * bridge or cable, and then no depth is suggested. A guide point's refusal
     * keeps the local answer. Those messages are dropped when the stretch is
     * retried, and a full plan there would replan the whole trip.
     */
    private fun depthHint(p: LatLon, c: Ctx, who: String): DepthHint {
        if (probing) return DepthHint(null, false)
        fun local(ft: Double): Boolean {
            val fa = areaAround(p, 4000.0)
            val fg = buildGrid(fa, ft * Pack.FT, c.airM, c.bufM)
            val s = try { snap(fa, fg, p).first } catch (_: PlanException) { return false }
            return enclosure(fa, fg, s).edge
        }
        var lo = 1                                   // half-feet
        var hi = (ceil(c.depthFt * 2) - 1).toInt()   // just below what failed
        if (hi < lo || !local(0.5)) return DepthHint(null, false)
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (local(mid / 2.0)) lo = mid else hi = mid - 1
        }
        val localBest = lo / 2.0
        // Checking the suggestion with a second full plan, while this plan still holds
        // its grids, only fits when the trip is one detail window. That is also the
        // case a cable can fake: the 4 km box opens, and the other end stays walled
        // off. A long trip keeps the local answer.
        if (!who.startsWith("Point") || tripPts.size < 2 || area(tripPts, 1.0).f != 1) return DepthHint(localBest, true)
        if (connectsAt(localBest, c)) return DepthHint(localBest, true)
        if (localBest <= 0.5 + 1e-9) return DepthHint(null, true)
        if (!connectsAt(0.5, c)) return DepthHint(null, true)
        lo = 1
        hi = (floor(localBest * 2) - 1).toInt()
        if (hi < lo) return DepthHint(0.5, true)
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (connectsAt(mid / 2.0, c)) lo = mid else hi = mid - 1
        }
        return DepthHint(lo / 2.0, true)
    }

    /**
     * True when planning the same trip at [ft] joins the ends. Runs on this
     * planner while an outer plan is still on the stack, so it restores the
     * flags and does not start its own depth trial.
     */
    private fun connectsAt(ft: Double, c: Ctx): Boolean {
        probing = true
        val in0 = inside
        val mv = moveEnds
        return try {
            plan("probe", tripPts, ft, c.airFt, c.bufM, in0, mv) {}
            true
        } catch (_: PlanException) {
            false
        } finally {
            probing = false
            inside = in0
            moveEnds = mv
        }
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
    /**
     * Pier (or snag, gate) steps along [cells]: a cell itself, or the corner of a diagonal
     * step. A diagonal step between two channel cells does not count its corners: A* takes
     * it without the corner price. Counted, the Coinjock cut's staircase south of the span
     * gave the path one pier step, and simplify spent it on a line through the real pier
     * cell beside the channel (issue #34).
     */
    private fun pierSteps(a: Area, g: Grid, cells: IntArray, pier: (Int) -> Boolean): Int {
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
                if (r0 != r1 && c0 != c1 && !(g.channel(cells[k - 1]) && g.channel(i)) &&
                    (pier(r0 * w + c1) || pier(r1 * w + c0))) n++
            }
        }
        return n
    }

    /**
     * Drop path points while the straight line stays in water no shallower, no
     * closer to danger and over no more pier or seawall lines than the cells
     * the planned path itself used. A 0-6 ft cell the path did not already use
     * refuses the line, unless it touches 0-6 ft the path did use: a shoal the
     * path went around stays off the leg, including one the path only skimmed
     * in safe water. Touching that safe water is not enough here (a long chord
     * would cut the shoal). The one-cell pass [straighten] may still graze the
     * edge. A hazard buffer the path did not use refuses the line too. Water
     * charted entirely under the safe depth stays forbidden.
     */
    private fun simplify(a: Area, g: Grid, path: IntArray): IntArray {
        fun px(i: Int) = if (g.pier[i]) 0 else g.prox[i].toInt() // a pier cell is next to danger by definition
        fun fits(p: Int, q: Int, maxp: Int, npier: Int, nsoft: Int): Boolean {
            // One level of grid staircase tolerance, but never into the two rings
            // nearest danger unless the path itself went there
            val lim = if (maxp + 1 <= g.k - 3) maxp + 1 else maxp
            var sf = 0
            val corners = ArrayList<Int>()
            val cells = lineCells(a, path[p], path[q], corners, g::channel)
            val used = HashSet<Int>(q - p + 1)
            for (k in p..q) used.add(path[k])
            if (corners.any { !g.ok[it] || marginalUnvisited(g, it, used, a.w, false) || bufferUnvisited(g, it, used, a.w) }) return false
            if (shoalBesideChannel(a, g, cells, used) || shoalBesideChannel(a, g, corners.toIntArray(), used)) return false
            if (leavesSpanOpening(a, g, path[p], path[q])) return false
            // Hazard-buffer cells are compared as shares, plus one: a diagonal line
            // samples more cells than the 8-direction path over the same water.
            val pn = q - p + 1
            val n = cells.size
            for (i in cells) {
                if (!g.ok[i] || marginalUnvisited(g, i, used, a.w, false) || bufferUnvisited(g, i, used, a.w)) return false
                if (g.pier[i]) continue // counted below
                if (g.prox[i] > lim) return false
                if (g.soft[i]) { sf++; if (sf * pn > (nsoft + 1) * n) return false }
            }
            if (!keepsCenter(a, g, path, p, q)) return false
            return pierSteps(a, g, cells) { g.pier[it] } <= npier
        }
        // pier crossings of the planned path up to each point
        val pc = IntArray(path.size)
        for (k in 1 until path.size) pc[k] = pc[k - 1] + pierSteps(a, g, intArrayOf(path[k - 1], path[k])) { g.pier[it] }
        val keep = ArrayList<Int>()
        keep.add(0)
        var s = 0
        var maxp = px(path[0])
        var nsoft = if (g.soft[path[0]]) 1 else 0
        var j = 1
        while (j < path.size) {
            val p2 = max(maxp, px(path[j]))
            val s2 = nsoft + if (g.soft[path[j]]) 1 else 0
            if (j == s + 1 || fits(s, j, p2, pc[j] - pc[s] + if (g.pier[path[s]]) 1 else 0, s2)) {
                maxp = p2; nsoft = s2; j++
            } else {
                s = j - 1
                keep.add(s)
                maxp = max(px(path[s]), px(path[j]))
                nsoft = (if (g.soft[path[s]]) 1 else 0) + (if (g.soft[path[j]]) 1 else 0)
            }
        }
        if (keep.last() != path.size - 1) keep.add(path.size - 1)
        return straighten(a, g, path, keep, pc).map { path[it] }.toIntArray()
    }

    /** True when a neighbor of [cell] is on the path and [accept] says it counts. */
    private fun touchesUsed(g: Grid, cell: Int, used: Set<Int>, w: Int, accept: (Int) -> Boolean): Boolean {
        val r = cell / w
        val c = cell % w
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val rr = r + dr
            val cc = c + dc
            if (cc < 0 || cc >= w || rr < 0) continue
            val j = rr * w + cc
            if (j in used && j in g.nav.indices && accept(j)) return true
        }
        return false
    }

    /**
     * True when [cell] is 0-6 ft water (MARGINAL) the line would cut through.
     * Allowed when it touches 0-6 ft the path already used. With [besideSafe],
     * also when it touches safe water the path used: the one-cell pass only,
     * for the corner of a dredged channel and the shoal side of an edge the
     * path is already following. A long chord does not get that (see [simplify]).
     * Water charted entirely under the safe depth is [SHALLOW], not this.
     */
    private fun marginalUnvisited(g: Grid, cell: Int, used: Set<Int>, w: Int, besideSafe: Boolean): Boolean {
        if (cell !in g.nav.indices || g.nav[cell].toInt() != MARGINAL || cell in used) return false
        if (touchesUsed(g, cell, used, w) { g.nav[it].toInt() == MARGINAL }) return false
        if (besideSafe && touchesUsed(g, cell, used, w) { g.nav[it].toInt() == SAFE }) return false
        return true
    }

    /**
     * True when [cell] is in a hazard buffer the path did not use and does not
     * touch a buffer cell the path did use. A leg must not cut through that
     * buffer, or pass closer to the sounding or obstruction than the path did.
     */
    /**
     * A straight line that spends a run of 0-6 ft cells off a dredged channel or fairway,
     * beside a sounding the chart prints under the safe depth. The channel is within a
     * couple of cells, so the line is a jog out of the cut (Snows Cut) or one cell off
     * the fairway (Bogue Sound), not the one-cell corner of a narrow canal.
     */
    private fun shoalBesideChannel(a: Area, g: Grid, cells: IntArray, used: Set<Int>): Boolean {
        val w = a.w
        val h = a.h
        var run = 0
        for (i in cells) {
            val depth = g.planes[Pack.P_DEPTH][i].toInt()
            val off = i !in used && g.nav[i].toInt() == MARGINAL && depth and DREDGED == 0
            if (!off) { run = 0; continue }
            run++
            if (run < 3) continue
            val r = i / w
            val c = i % w
            var channel = false
            var shoal = false
            for (rr in max(0, r - 5)..min(h - 1, r + 5)) for (cc in max(0, c - 5)..min(w - 1, c + 5)) {
                val j = rr * w + cc
                if (g.ok[j] && g.nav[j].toInt() == SAFE && g.planes[Pack.P_DEPTH][j].toInt() and DREDGED != 0) channel = true
                val sv = g.planes[Pack.P_SOUND][j].toInt() and 0xff
                if (g.nav[j].toInt() == HAZARD && sv != 0) shoal = true
            }
            if (channel && shoal) return true
        }
        return false
    }

    /**
     * A straight line through a bridge that has a dredged span, and also a pier or
     * deck cell beside that opening. Coinjock's chord crossed the fender cell next
     * to the dredged span. The opening cell stays; the neighbor does not.
     */
    /** Cell-center distance, in cells, from [i] to the segment [p]-[q]. */
    private fun cellLineDist(w: Int, i: Int, p: Int, q: Int): Double {
        val ax = (p % w).toDouble()
        val ay = (p / w).toDouble()
        val dx = (q % w) - ax
        val dy = (q / w) - ay
        val px = (i % w) - ax
        val py = (i / w) - ay
        val l2 = (dx * dx + dy * dy).takeIf { it > 0 } ?: 1.0
        val t = ((px * dx + py * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - t * dx, py - t * dy)
    }

    /**
     * The straight line comes within about 12 m of a pier cell on a span, and also
     * within that distance of the dredged opening. Coinjock's one chord did that.
     * A line down the opening is a cell away from the fender and is kept.
     */
    private fun leavesSpanOpening(a: Area, g: Grid, p: Int, q: Int): Boolean {
        val w = a.w
        if (max(abs(p / w - q / w), abs(p % w - q % w)) > 150) return false
        fun dredgedSpan(i: Int): Boolean {
            if (i !in g.ok.indices || !g.ok[i]) return false
            val depth = g.planes[Pack.P_DEPTH][i].toInt()
            val fl = g.planes[Pack.P_FLAGS][i].toInt()
            return (depth and DREDGED) != 0 && g.nav[i].toInt() == SAFE &&
                ((fl and Pack.F_SPAN) != 0 || (fl and Pack.F_OPENING) != 0)
        }
        fun fender(i: Int): Boolean {
            if (i !in g.nav.indices) return false
            val fl = g.planes[Pack.P_FLAGS][i].toInt()
            if ((fl and (Pack.F_SPAN or Pack.F_BRIDGE)) == 0) return false
            val hz = g.planes[Pack.P_HAZARD][i].toInt() and 0xff
            if (hz != Pack.HZ_PIER && !g.pier[i]) return false
            return !dredgedSpan(i)
        }
        val r0 = min(p, q) / w
        val r1 = max(p, q) / w
        val c0 = min(p % w, q % w)
        val c1 = max(p % w, q % w)
        var span = false
        var side = false
        for (r in max(0, r0 - 2)..min(a.h - 1, r1 + 2)) for (c in max(0, c0 - 2)..min(w - 1, c1 + 2)) {
            val i = r * w + c
            if (cellLineDist(w, i, p, q) > 0.7) continue
            if (dredgedSpan(i)) span = true
            if (fender(i)) side = true
            if (span && side) return true
        }
        return false
    }

    private fun bufferUnvisited(g: Grid, cell: Int, used: Set<Int>, w: Int): Boolean {
        if (cell !in g.soft.indices || !g.soft[cell] || cell in used) return false
        return !touchesUsed(g, cell, used, w) { g.soft[it] }
    }

    /**
     * Second pass over [simplify]'s points: where usable water on the grid is a thin
     * diagonal band (a narrow canal at 20 m, or the 6-12 ft side of a 0-6 ft edge),
     * join points while the line stays within one cell of the planned path and
     * crosses no more pier lines. The line may use charted water the path already
     * used, and 0-6 ft water touching that safe water (the channel's corner, or
     * the shoal side of the edge the path is on). It may not cut across a shoal
     * the path went around, through a hazard buffer the path did not use, or
     * through water charted entirely under the safe depth. In a narrow channel
     * the line also stays off the edge (see [keepsCenter]). Land, hazards,
     * piers, open ocean and a low bridge stay forbidden.
     */
    private fun straighten(a: Area, g: Grid, path: IntArray, keep: List<Int>, pc: IntArray): List<Int> {
        val w = a.w
        fun graze(i: Int, used: Set<Int>): Boolean {
            if (marginalUnvisited(g, i, used, w, true) || bufferUnvisited(g, i, used, w)) return false
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
                val used = HashSet<Int>(i1 - i0 + 1)
                for (k in i0..i1) used.add(path[k])
                if (!cells.all { graze(it, used) } || !corners.all { graze(it, used) }) continue
                if (shoalBesideChannel(a, g, cells, used) || shoalBesideChannel(a, g, corners.toIntArray(), used)) continue
                if (leavesSpanOpening(a, g, path[i0], path[i1])) continue
                if (!keepsCenter(a, g, path, i0, i1)) continue
                if (pierSteps(a, g, cells) { g.pier[it] } > pc[i1] - pc[i0] + (if (g.pier[path[i0]]) 1 else 0)) continue
                best = e
            }
            out.add(keep[best])
            s = best
        }
        return out
    }

    // ------------------------------------------------------------ report

    /**
     * Shallowest sounding within [withinM] of the leg p-q. A sounding outside fairway and
     * dredged cells counts only within [offChannelM]: on a leg mostly in a fairway that is
     * the hazard buffer, the distance the planner already passes a shoal sounding at. The
     * Oak Island fairway leg at 2 ft said "under your 2 ft" for a 1.0 ft sounding 93 m off
     * the line, outside the fairway (issue #41).
     */
    private fun shallowestNear(a: Area, g: Grid, p: LatLon, q: LatLon, withinM: Double = 100.0, offChannelM: Double = withinM): Double? {
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
            val lim = if (g.planes[Pack.P_DEPTH][i].toInt() and DREDGED != 0) withinM else offChannelM
            if (hypot(px - ax - t * dx, py - ay - t * dy) <= lim) best = v
        }
        return if (best == 0) null else (best - 1) / 10.0
    }

    private fun featureNotes(kind: String, p: LatLon, tolM: Double, airFt: Double? = null): List<String> {
        val near = pack.notes.filter { it.kind == kind && it.near(p.lat, p.lon, tolM) }
        if (near.isEmpty()) return emptyList()
        val top = near.maxOf { it.band } // most detailed chart with this kind of feature here wins
        return near.filter { it.band == top }.mapNotNull { it.describe(airFt) }.distinct()
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

    /**
     * Cables the leg actually crosses, plus a cable beside the leg whose clearance
     * is under the air draft. A 54.8 ft cable on the next cell used to be named on
     * the channel leg and rounded to 55. Beside it is said so, and it is not a check.
     */
    private fun cableNotes(a: Area, g: Grid, lc: IntArray, airFt: Double?): List<String> {
        val cables = pack.notes.filter { it.kind == "cable" }
        if (cables.isEmpty() || lc.isEmpty()) return emptyList()
        val near = ArrayList<Note>()
        for (note in cables) {
            val hit = lc.any { i ->
                val ll = a.latlon(i)
                note.near(ll.lat, ll.lon, a.res * 1.5)
            }
            if (hit) near.add(note)
        }
        if (near.isEmpty()) return emptyList()
        val top = near.maxOf { it.band }
        val out = ArrayList<String>()
        for (note in near) {
            if (note.band != top) continue
            val clr = note.clr
            var on = false
            var beside = false
            for (i in lc) {
                val ll = a.latlon(i)
                val fl = g.planes[Pack.P_FLAGS][i].toInt()
                val cellClr = (g.planes[Pack.P_CABLECLR][i].toInt() and 0xff) / 2.0
                val same = clr == null || cellClr == 0.0 || abs(cellClr - clr) < 0.5
                // On the cable: the leg's cell is that cable. A note with only a bbox
                // (no line) matches the whole box, so the cell's own clearance has to agree.
                if (fl and Pack.F_CABLE != 0 && same && note.near(ll.lat, ll.lon, a.res * 0.6)) on = true
                else if (note.coords != null && note.near(ll.lat, ll.lon, a.res * 1.5) &&
                    !note.near(ll.lat, ll.lon, a.res * 0.55)) beside = true
            }
            val text = note.describe(airFt) ?: continue
            val under = airFt != null && (clr == null || clr < airFt * Pack.FT)
            when {
                on && under && !text.contains("check") -> out.add("$text: check")
                on -> out.add(text)
                beside && under -> out.add("$text; the route passes beside it and is not on it")
            }
        }
        return out.distinct()
    }

    private fun legs(a: Area, g: Grid, wps: List<LatLon>, idx: IntArray, seen: HashSet<String>, airFt: Double?): List<Leg> {
        val out = ArrayList<Leg>()
        val airChecked = airFt != null
        val kinds = listOf("resare" to Pack.F_RESNOTE, "bridge" to Pack.F_BRIDGE)
        val every = max(1, (500 / a.res).toInt())
        var prevPier = false
        var prevSnag = false
        var prevGate = false
        var prevSoft = false
        var prevHazard = -1
        for (k in 0 until idx.size - 1) {
            val lc = lineCells(a, idx[k], idx[k + 1])
            val marg = lc.count { g.nav[it].toInt() == MARGINAL }
            val legM = distanceM(wps[k], wps[k + 1])
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
                for (i in hits.take(12)) for (s0 in featureNotes(kind, a.latlon(i), a.res * 1.5, airFt)) {
                    // With no air draft, a span the route still crosses was not tested.
                    var s = s0
                    if (!airChecked && kind == "bridge" && !s.contains("check")) s = "$s: check"
                    if (kind == "resare") {
                        // Kept on every leg still inside the area. One note on the entry
                        // leg left the next 10 nm of Aberdeen Proving Ground silent.
                        if (notes.any { it == s || it.startsWith("$s (about") }) continue
                        val inside = lc.count { cell ->
                            (g.planes[Pack.P_FLAGS][cell].toInt() and bit) != 0 &&
                                s0 in featureNotes(kind, a.latlon(cell), a.res * 1.5, airFt)
                        }
                        val insideM = inside * legM / max(lc.size, 1)
                        if (insideM > 0 && insideM + a.res < legM)
                            s = "$s (about ${insideM.roundToInt()} m of this %.2f nm leg is inside)".format(Locale.US, legM / NM)
                        notes.add(s)
                    } else if (s !in seen) {
                        seen.add(s); notes.add(s)
                    }
                }
            }
            for (s in cableNotes(a, g, lc, airFt)) {
                val text = if (!airChecked && !s.contains("check") && !s.contains("passes beside")) "$s: check" else s
                if (text !in seen) { seen.add(text); notes.add(text) }
            }
            // A long channel leg that only clips an entry-prohibited area at the end is not
            // "prohibited" for its whole length. The cells inside the area keep the note.
            if (notes.any { it.contains("entry prohibited") && !it.contains("may be prohibited") }) {
                var first = -1
                var last = -1
                for (t in lc.indices) if (g.planes[Pack.P_FLAGS][lc[t]].toInt() and Pack.F_RESNOTE != 0) {
                    if (first < 0) first = t
                    last = t
                }
                val run = if (first < 0) 0 else last - first + 1
                val atEnd = first <= 2 || last >= lc.size - 3
                if (atEnd && run * a.res < 150.0 && run * 2 < lc.size) {
                    val drop = notes.filter { it.contains("entry prohibited") && !it.contains("may be prohibited") }
                    notes.removeAll(drop)
                    seen.removeAll(drop.toSet())
                }
            }
            // pier or seawall over usable-depth water (same test as noaa-route.py's report)
            // A fender cell in a dredged span is channel (buildGrid), and the bridge note names the span
            val onPier = pierSteps(a, g, lc) { i ->
                (g.planes[Pack.P_HAZARD][i].toInt() and 0xff) == Pack.HZ_PIER && !g.channel(i) &&
                    g.nav[i].toInt().let { it == SAFE || it == MARGINAL }
            } > 0
            if (onPier && !prevPier) notes.add(0, PIER_NOTE) // once per stretch of such legs
            prevPier = onPier
            val onSnag = pierSteps(a, g, lc) { g.snag[it] } > 0
            if (onSnag && !prevSnag) notes.add(0, SNAG_NOTE)
            prevSnag = onSnag
            val onGate = pierSteps(a, g, lc) { g.gate[it] } > 0
            if (onGate && !prevGate) notes.add(0, GATE_NOTE)
            prevGate = onGate
            val near = lc.any { g.soft[it] }
            // Name the closest hazard whose buffer the leg uses; once per hazard along a stretch
            val hz = if (near) nearestHazard(a, g, wps[k], wps[k + 1], g.rad * a.res) else null
            if (near && (hz == null && !prevSoft || hz != null && hz.cell != prevHazard)) notes.add(0, hz?.note(a.res) ?: SOFT_NOTE)
            prevSoft = near
            prevHazard = if (near) hz?.cell ?: prevHazard else -1
            // A shoal leg that never steps into the hazard buffer still names how far it
            // passes a sounding under the safe depth. The depth-area sentence alone did not.
            // A leg mostly in a fairway or dredged channel: a shoal sounding outside the
            // channel and beyond the hazard buffer is not on the leg (issue #41).
            val chan = lc.count { g.planes[Pack.P_DEPTH][it].toInt() and DREDGED != 0 }
            val bufM = g.rad * a.res
            val offM = if (lc.isNotEmpty() && chan * 2 >= lc.size) bufM else 100.0
            if (marg > 0 && notes.none { it.startsWith("passes ") }) {
                val wider = nearestHazard(a, g, wps[k], wps[k + 1], 100.0)
                if (wider != null && wider.kind == HZ_SOUNDING &&
                    (g.planes[Pack.P_DEPTH][wider.cell].toInt() and DREDGED != 0 || wider.distM <= offM)) notes.add(0, wider.note(a.res))
            }
            val d = distanceM(wps[k], wps[k + 1])
            val low = if (marg > 0) shallowestNear(a, g, wps[k], wps[k + 1], offChannelM = offM) else null
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
        // A bridge or cable the air draft cannot clear is the limit, even when a
        // longer edge of shoal water is also on the pocket. Opening bridges are
        // not that limit: Bear Creek's 8 ft swing and 25 ft bascule open.
        val order = walls.entries.sortedWith(compareBy<Map.Entry<String, Pair<Int, Int>>> { if (it.key == "nopass") 0 else 1 }.thenByDescending { it.value.first })
        for ((why, v) in order) {
            val edge = "${(v.first * a.res).roundToInt()} m of edge"
            if (why == "hazard") {
                val (snd, obs) = hazardSources(a, g, enc.hazardWalls)
                if (snd != null) sb.append("  - soundings as shallow as %.1f ft (near %s), with their safety buffer ($edge)\n".format(snd.first / Pack.FT, at(snd.second)))
                if (obs != null) sb.append(if (obs.first == 0.0) "  - a rock, wreck or obstruction of unknown or near-zero depth (near ${at(obs.second)}), with its safety buffer\n"
                                           else "  - rocks, wrecks or obstructions as shallow as %.1f ft (near %s), with their safety buffer\n".format(obs.first / Pack.FT, at(obs.second)))
                if (snd == null && obs == null) sb.append("  - ${wallText[why]} ($edge, near ${at(v.second)})\n")
            } else if (why == "nopass") {
                val phrase = structurePhrase(a, g, enc.nopassAt, c.airFt)
                sb.append("  - ${wallText[why]}" + (if (phrase.isNotEmpty()) ": $phrase" else "") + " ($edge, near ${at(v.second)})\n")
            } else sb.append("  - ${wallText[why]} ($edge, near ${at(v.second)})\n")
        }
        // Do not offer a shallower depth when a bridge or cable is what stops the boat.
        val blockedByStructure = "nopass" in walls
        val limitedByDepth = !blockedByStructure && walls.keys.any { it == "hazard" || it == "shallow" }
        val hint = if (limitedByDepth) depthHint(pt, c, who) else DepthHint(null, false)
        if (hint.feet != null) sb.append("With a safe depth of ${fmt(hint.feet)} ft or less the water here leads out of this area; try that (the rest of the trip may still have shallower spots).\n")
        else if (limitedByDepth && !hint.localEscape) sb.append("Even 0.5 ft of depth does not lead out from here.\n")
        if (moveEnds) sb.append("Move ends to navigable water is on, but no water within ${fmt(MOVE_ENDS_M / NM)} nm of this point leads out.\n")
        sb.append("Move the point into open water, or adjust depth or air draft.")
        return PlanException(sb.toString())
    }

    /** Charted water, including a band under the safe depth. Land, a dock and a pier are not. */
    private fun chartedWater(g: Grid, i: Int): Boolean {
        val v = g.nav[i].toInt()
        return v == SAFE || v == MARGINAL || v == SHALLOW || v == HAZARD || v == SEA
    }

    private fun landMessage(n: Int, distM: Double) =
        "Point $n is on land or a dock. The route uses the nearest usable water, ${distM.roundToInt()} m away. Check the way between on a chart."

    /** Why Move ends is moving this point to usable water clear of hazards. */
    private fun moveWhy(g: Grid, userCell: Int, depthFt: Double): String {
        val v = g.nav[userCell].toInt()
        val d = fmt(depthFt)
        val hazards = g.soft[userCell] || g.pier[userCell] || g.snag[userCell] || g.gate[userCell] || v == HAZARD
        val depth = when (v) {
            SHALLOW -> "in water charted entirely under your $d ft"
            MARGINAL -> "in water that may be under your $d ft"
            else -> null
        }
        return when {
            depth != null && hazards -> "$depth and near charted hazards"
            depth != null -> depth
            else -> "near charted hazards"
        }
    }

    private fun moveMessage(n: Int, why: String, depthFt: Double, distM: Double) =
        "Point $n is $why; with Move ends to navigable water on, the route uses the nearest usable water clear of charted hazards, " +
            "${distM.roundToInt()} m away. Check the way between on a chart."

    private fun moveLeftMessage(n: Int, why: String, depthFt: Double) =
        "Point $n is $why, and no usable water clear of charted hazards is within ${fmt(MOVE_ENDS_M / NM)} nm along the water, " +
            "so Move ends to navigable water left it; the legs there are flagged."

    /** Route a to b on the full-detail grid (its window must fit without coarsening). */
    private fun planPiece(p: LatLon, q: LatLon, np: Int?, nq: Int?, c: Ctx): Piece {
        for ((attempt, widen) in widths.withIndex()) {
            val attemptSnap = c.snap()
            val a = area(listOf(p, q), widen)
            val g = buildGrid(a, c.depthM, c.airM, c.bufM)
            var (sp, mp) = snap(a, g, p)
            var (sq, mq) = snap(a, g, q)
            if (moveEnds) {
                // A point in water charted entirely under the safe depth, or in a hazard
                // buffer, starts or ends at the nearest usable water clear of hazards. Water
                // that may be under the safe depth (a 0-6 ft creek) is kept, its legs flagged.
                for (e in 0..1) {
                    val n = (if (e == 0) np else nq) ?: continue
                    if (n in c.moved) continue
                    val pt = if (e == 0) p else q
                    val s0 = if (e == 0) sp else sq
                    val userCell = a.cell(pt)
                    if (!chartedWater(g, userCell)) {
                        // A pin on land or a dock ends at the nearest usable water: the snap,
                        // or, when that cell is in a hazard buffer, the nearest clear water
                        // from it. Walking on to deeper water skipped water the boat can use
                        // (Ego Alley at 2 ft: 158 m out instead of 45 m up the alley).
                        var dist = if (e == 0) mp else mq
                        nearestNavigable(a, g, pt, s0)?.let { (cell, d) ->
                            if (e == 0) { sp = cell; mp = 0.0 } else { sq = cell; mq = 0.0 }
                            dist = d
                        }
                        if (dist + 1e-6 >= a.res) {
                            val msg = landMessage(n, dist)
                            if (msg !in c.messages) c.messages.add(msg)
                            c.moved.add(n)
                        }
                        continue
                    }
                    val found = nearestNavigable(a, g, pt, s0)
                    if (found == null) {
                        if (!clearWater(g, s0)) {
                            val msg = moveLeftMessage(n, moveWhy(g, userCell, c.depthFt), c.depthFt)
                            if (msg !in c.messages) c.messages.add(msg)
                        }
                        continue
                    }
                    val (cell, dist) = found
                    if (e == 0) { sp = cell; mp = 0.0 } else { sq = cell; mq = 0.0 }
                    val msg = moveMessage(n, moveWhy(g, userCell, c.depthFt), c.depthFt, dist)
                    if (msg !in c.messages) c.messages.add(msg)
                    c.moved.add(n)
                }
            }
            for ((n, pt, m) in listOf(Triple(np, p, mp), Triple(nq, q, mq))) {
                if (n == null || n in c.moved) continue
                val onLand = !chartedWater(g, a.cell(pt))
                if (onLand && m + 1e-6 < a.res) continue
                if (!onLand && m <= a.res) continue
                c.moved.add(n)
                c.messages.add(if (onLand) landMessage(n, m) else "Point $n moved ${m.roundToInt()} m to the nearest usable water")
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
            if (path != null && moveEnds) {
                // One distance. The "nearest usable water clear of charted hazards" line is an earlier
                // try that did not lead out; the move above is the one that did.
                for (n in listOfNotNull(np, nq)) {
                    val lead = c.messages.filter { it.startsWith("Point $n:") && it.contains("leads out") }
                    if (lead.isNotEmpty()) c.messages.removeAll {
                        it.startsWith("Point $n is ") || (it.startsWith("Point $n:") && it !in lead)
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
                // The straight stub back to your point is not a planned leg. It may cross a
                // dock or shallow water. It must not cross a bridge or cable the air draft
                // cannot clear, or the land on the far side of one: stop on the near side.
                val headCross = if (np != null && cp != idx.first()) connectorCrossing(a, g, idx.first(), p) else null
                val tailCross = if (nq != null && cq != idx.last()) connectorCrossing(a, g, idx.last(), q) else null
                val headProhib = if (np != null && headCross == null) entryProhibited(a, g, p) else null
                val tailProhib = if (nq != null && tailCross == null) entryProhibited(a, g, q) else null
                if (headCross != null && np != null) noteStopped(c, np, p, a.latlon(idx.first()), headCross)
                if (tailCross != null && nq != null) noteStopped(c, nq, q, a.latlon(idx.last()), tailCross)
                if (headProhib != null && np != null) noteProhibited(c, np, p, a.latlon(idx.first()), headProhib)
                if (tailProhib != null && nq != null) noteProhibited(c, nq, q, a.latlon(idx.last()), tailProhib)
                // A guide point (np/nq null) just starts or ends at the water found for it
                val head = np != null && cp != idx.first() && headCross == null && headProhib == null
                val tail = nq != null && cq != idx.last() && tailCross == null && tailProhib == null
                if (idx.size == 1 && !head && !tail && headCross == null && tailCross == null && headProhib == null && tailProhib == null) {
                    wps.add(p); idx = intArrayOf(idx[0], idx[0])
                }
                if (head) { wps.add(0, p); idx = intArrayOf(cp) + idx }
                else if (headCross == null && headProhib == null && (np != null || cp == idx.first())) wps[0] = p
                if (tail) { wps.add(q); idx += cq }
                else if (tailCross == null && tailProhib == null && (nq != null || cq == idx.last())) wps[wps.size - 1] = q
                val ls = legs(a, g, wps, idx, c.seen, c.airFt).toMutableList()
                fun offWater(l: Leg) = Leg(l.distM, l.bearing, l.shoalM, l.lowM, listOf(OFF_WATER) + l.notes)
                if (head) ls[0] = offWater(ls[0])
                if (tail) ls[ls.size - 1] = offWater(ls[ls.size - 1])
                if (moveEnds && nq != null) stopForShoalBar(c, nq, q, wps, ls)
                // The connector is drawn. Say the pin is on land when nothing else
                // already explained the move. A long trip's earlier try can drop
                // the sentence added at the snap.
                fun ensureLand(n: Int?, user: LatLon, drawn: Boolean) {
                    if (n == null || !drawn) return
                    val cell = a.cell(user)
                    if (chartedWater(g, cell)) return
                    if (c.messages.any { it.startsWith("Point $n is on land") || it.startsWith("Point $n:") }) return
                    val water = if (n == np) wps[min(1, wps.size - 1)] else wps[max(0, wps.size - 2)]
                    c.messages.add(landMessage(n, distanceM(user, water)))
                    c.moved.add(n)
                }
                ensureLand(np, p, head)
                ensureLand(nq, q, tail)
                // A 40 m grid will draw a narrow marked cut on the wrong side of the marks
                // (Kent Narrows). Full detail is the grid that knows the cut. If this piece
                // had to be coarsened and it still crosses a mark, do not hand that line over.
                if (a.res >= 30.0 && ls.any { l -> l.notes.any { it.contains("wrong side of a channel marker") } }) {
                    throw PlanException(
                        "No route at full chart detail through this narrow channel without crossing the wrong side of a mark. " +
                            "A coarser grid is not used for that cut. Move a point, or add a via point in the marked channel.")
                }
                return Piece(wps, ls, a.res, cost)
            }
            // This wider window did not produce a route. Drop the distances it
            // reported so the next try, or the refusal, is not a second number
            // for the same point.
            c.restore(attemptSnap)
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
        val onLand = !chartedWater(fg, fa.cell(pt))
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
        // A land pin stays on the snap. Walking to the nearest deep cell is what
        // pulled a long trip's first guide points out of the ditch. The sentence
        // is written by planPiece, which draws the leg.
        if (moveEnds && enc.edge && !onLand) nearestNavigable(fa, fg, pt, s)?.let { s = it.first }
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
        if (!probing) tripPts = pts
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
                else if (!atGuide && join > 0) tidyVia(wps, legs, join, max(res, piece.res), pts)
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
        // One Move ends sentence per point: the last one, from the attempt that
        // was kept. A widened window used to leave an earlier distance in the report.
        val lastFor = HashMap<Int, Int>()
        for (i in c.messages.indices) {
            val m = c.messages[i]
            val n = Regex("""^Point (\d+)""").find(m)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            lastFor[n] = i
        }
        if (lastFor.isNotEmpty()) {
            val drop = HashSet<Int>()
            for (i in c.messages.indices) {
                val m = c.messages[i]
                val n = Regex("""^Point (\d+)""").find(m)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (lastFor[n] != i && (m.startsWith("Point $n is ") || m.startsWith("Point $n moved") || m.startsWith("Point $n:")))
                    drop.add(i)
            }
            for (i in drop.sortedDescending()) c.messages.removeAt(i)
        }
        // A pin on land whose connector survived every restore still needs one sentence.
        for ((idx, pt) in pts.withIndex()) {
            val n = idx + 1
            if (c.messages.any { it.startsWith("Point $n") }) continue
            val at = if (idx == 0) wps.firstOrNull() else if (idx == pts.lastIndex) wps.lastOrNull() else null
            val nbr = if (idx == 0) wps.getOrNull(1) else wps.getOrNull(wps.size - 2)
            if (at == null || nbr == null || distanceM(at, pt) > 1.0) continue
            val dist = distanceM(pt, nbr)
            if (dist < 15.0) continue
            val fa = areaAround(pt, 80.0)
            val fg = buildGrid(fa, c.depthM, c.airM, c.bufM)
            if (chartedWater(fg, fa.cell(pt))) continue
            c.messages.add(landMessage(n, dist))
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
     * The legs either side of a via point are planned on their own grids, so each snaps the
     * via to a different 20 m cell and keeps cell centers by it: a via added from the chart
     * at 39.30342,-76.40564 drew five legs of 0.01 nm (two in line with the leg before, an
     * out-and-back between the two snaps). Drop points within two cells of the via while
     * the line replacing a point's two legs stays within [VIA_TIDY] of a cell of them. Your
     * own points stay, and a leg that connects your point to water is never merged.
     */
    private fun tidyVia(wps: MutableList<LatLon>, legs: MutableList<Leg>, join: Int, resM: Double, pts: List<LatLon>) {
        val at = wps[join]
        val mine = pts.toHashSet()
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
            for (j in 1 until wps.size - 1) {
                if (wps[j] in mine || distanceM(wps[j], at) > 2 * resM + 1) continue
                val l1 = legs[j - 1]
                val l2 = legs[j]
                if (OFF_WATER in l1.notes || OFF_WATER in l2.notes) continue
                val a = wps[j - 1]
                val b = wps[j + 1]
                val stray = (0..10).maxOf { t ->
                    val p = LatLon(a.lat + (b.lat - a.lat) * t / 10, a.lon + (b.lon - a.lon) * t / 10)
                    min(off(p, a, wps[j]), off(p, wps[j], b))
                }
                if (stray > resM * VIA_TIDY) continue
                val lows = listOfNotNull(l1.lowM, l2.lowM)
                legs[j - 1] = Leg(distanceM(a, b), bearing(a, b), l1.shoalM + l2.shoalM,
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
                if (runs == 0) return "No check stretch on this route."
                val left = runs - resolved - partly
                val still = left + partly
                val lead = if (still == 0) "No check stretch left."
                    else "$still ${if (still == 1) "check stretch is" else "check stretches are"} still on the route."
                return lead + String.format(Locale.US, " Looked for a way around: %d of %d cleared", resolved, runs) +
                    (if (partly > 0) ", $partly still in water that may be under your depth" else "") +
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
                val nl = legs(a, g, nw, idx, HashSet(seen), c.airFt)
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

    /**
     * Delete leg on the chart: leg [leg] (1-based) loses its end waypoint (the last leg its
     * start, since its end is the destination), and the legs on either side become one
     * straight leg. That leg is checked like a planned one (depth, hazards, piers, bridges,
     * cables, restricted areas) and also for what the planner never lets a leg cross: land,
     * water charted under the safe depth, structures. Those get a check note. Your via points
     * are not removed this way.
     */
    fun deleteLeg(r: Route, leg: Int, userPts: List<LatLon>, bufM: Double = 60.0, stayInside: Boolean = false): Route {
        if (r.legs.size < 2) throw PlanException("This route has one leg: there is no waypoint to remove.")
        if (leg !in 1..r.legs.size) throw PlanException("There is no leg $leg on this route.")
        val j = if (leg < r.legs.size) leg else leg - 1
        val wp = r.points[j]
        if (userPts.any { abs(it.lat - wp.lat) < 1e-7 && abs(it.lon - wp.lon) < 1e-7 })
            throw PlanException("Leg $leg ${if (leg < r.legs.size) "ends" else "starts"} at one of your via points. " +
                "To drop it, remove it from Via points and plan again.")
        inside = stayInside
        val p = r.points[j - 1]
        val q = r.points[j + 1]
        val a = area(listOf(p, q), 1.0)
        val g = buildGrid(a, r.depthFt * Pack.FT, r.airFt?.let { it * Pack.FT }, bufM)
        val i0 = a.cell(p)
        val i1 = a.cell(q)
        val seen = HashSet<String>()
        for ((k, l) in r.legs.withIndex()) if (k != j - 1 && k != j) seen.addAll(l.notes)
        val made = legs(a, g, listOf(p, q), intArrayOf(i0, i1), seen, r.airFt).single()
        // Cells a planned leg never crosses; a point you put on land keeps its own way to the water
        val wall = wallNote(a, g, i0, i1, made.distM, r.depthFt)
        val walls = if (wall != null) 1 else 0
        val notes = ArrayList<String>()
        if (wall != null) notes.add(wall)
        val old = r.legs[j - 1].notes + r.legs[j].notes
        if (OFF_WATER in old) notes.add(OFF_WATER)
        notes.addAll(made.notes)
        notes.add(JOINED_NOTE)
        val joined = Leg(made.distM, made.bearing, made.shoalM, made.lowM, notes.distinct())
        val legs = r.legs.toMutableList()
        legs[j - 1] = joined
        legs.removeAt(j)
        val pts = r.points.toMutableList()
        pts.removeAt(j)
        val name = if (r.name.endsWith("_edited")) r.name else r.name + "_edited"
        val msg = String.format(Locale.US, "Leg %d deleted on the chart: legs %d and %d joined as one straight leg (%.2f nm, was %.2f nm)%s.",
            leg, j, j + 1, joined.distM / NM, (r.legs[j - 1].distM + r.legs[j].distM) / NM,
            if (walls > 0) ", which crosses water or land the planner keeps off: check it" else "")
        return Route(name, pts, legs, r.depthFt, r.airFt, r.resM, r.messages + msg, r.guideM)
    }

    /**
     * The check note for a straight line the planner did not draw: the cells it crosses that a
     * planned leg never would (land, water charted under the safe depth, a structure). Cells at
     * either end are skipped while they are not usable: a point you put on land keeps its own
     * way to the water. Null when the line stays on usable water.
     */
    private fun wallNote(a: Area, g: Grid, i0: Int, i1: Int, distM: Double, depthFt: Double): String? {
        val lc = lineCells(a, i0, i1)
        var s0 = 0
        while (s0 < lc.size && !g.ok[lc[s0]]) s0++
        var e0 = lc.size - 1
        while (e0 >= s0 && !g.ok[lc[e0]]) e0--
        val walls = (s0..e0).count { !g.ok[lc[it]] }
        if (walls == 0) return null
        val m = max(a.res, walls * distM / max(lc.size, 1))
        val ft = if (depthFt % 1.0 == 0.0) "%.0f".format(Locale.US, depthFt) else "%.1f".format(Locale.US, depthFt)
        return "crosses about ${(m / 10).roundToInt() * 10} m of land, water charted under your $ft ft, or a structure the planner keeps off: check"
    }

    /**
     * Import GPX: check a line from a file leg by leg, as Delete leg checks its straight leg.
     * Nothing is replanned. Track points closer than [IMPORT_TOL_M] to the line through their
     * neighbours are dropped first; the legs are checked in windows of up to [IMPORT_SPAN_DEG]
     * so one grid serves many legs. Each leg gets the planner's notes (bridges, cables,
     * restricted areas, shoal water, hazards) and a wall note where it crosses what a planned
     * route keeps off.
     */
    fun checkRoute(name: String, line: List<LatLon>, depthFt: Double, airFt: Double?, bufM: Double = 60.0,
                   from: String = "a GPX file", progress: (String) -> Unit = {}): Route {
        val pts = simplify(line.filterIndexed { k, p -> k == 0 || distanceM(line[k - 1], p) > 1.0 }, IMPORT_TOL_M)
        if (pts.size < 2) throw PlanException("The GPX file has no line to check: it needs a route or track with 2 or more points.")
        for (p in pts) if (!pack.covers(p.lat, p.lon))
            throw PlanException("%.5f, %.5f in the file is outside your downloaded chart areas (%s). Download the area that covers it.".format(p.lat, p.lon, pack.names))
        inside = false
        val seen = HashSet<String>()
        val legs = ArrayList<Leg>()
        var res = 0.0
        var walled = 0
        var s0 = 0
        while (s0 < pts.size - 1) {
            var e = s0 + 1
            var la0 = min(pts[s0].lat, pts[e].lat); var la1 = max(pts[s0].lat, pts[e].lat)
            var lo0 = min(pts[s0].lon, pts[e].lon); var lo1 = max(pts[s0].lon, pts[e].lon)
            while (e + 1 < pts.size) {
                val q = pts[e + 1]
                if (max(max(la1, q.lat) - min(la0, q.lat), max(lo1, q.lon) - min(lo0, q.lon)) > IMPORT_SPAN_DEG) break
                la0 = min(la0, q.lat); la1 = max(la1, q.lat); lo0 = min(lo0, q.lon); lo1 = max(lo1, q.lon)
                e++
            }
            progress("Checking legs ${s0 + 1}-$e of ${pts.size - 1}")
            val wps = pts.subList(s0, e + 1)
            val a = area(wps, 1.0)
            val g = buildGrid(a, depthFt * Pack.FT, airFt?.let { it * Pack.FT }, bufM)
            val idx = IntArray(wps.size) { a.cell(wps[it]) }
            val made = legs(a, g, wps, idx, seen, airFt)
            for ((k, l) in made.withIndex()) {
                val wall = wallNote(a, g, idx[k], idx[k + 1], l.distM, depthFt)
                if (wall != null) walled++
                legs.add(Leg(l.distM, l.bearing, l.shoalM, l.lowM, (listOfNotNull(wall) + l.notes).distinct()))
            }
            res = max(res, a.res)
            s0 = e
        }
        val base = name.removeSuffix(".gpx").ifEmpty { "imported" }
        val msg = String.format(Locale.US, "Imported from %s and checked, not planned: %d legs, %.1f nm (%d points in the file)%s.",
            from, legs.size, legs.sumOf { it.distM } / NM, line.size,
            if (walled > 0) ", $walled ${if (walled == 1) "leg crosses" else "legs cross"} water or land the planner keeps off: check" else "")
        return Route(if (base.endsWith("_checked")) base else base + "_checked", pts, legs, depthFt, airFt, res, listOf(msg))
    }

    /** Douglas-Peucker in local meters: keeps the ends and every point more than [tolM] off the line. */
    private fun simplify(p: List<LatLon>, tolM: Double): List<LatLon> {
        if (p.size < 3) return p
        val keep = BooleanArray(p.size)
        keep[0] = true; keep[p.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(0 to p.size - 1)
        while (stack.isNotEmpty()) {
            val (i, j) = stack.removeLast()
            if (j - i < 2) continue
            val kx = 111_320.0 * cos(Math.toRadians(p[i].lat))
            val bx = (p[j].lon - p[i].lon) * kx; val by = (p[j].lat - p[i].lat) * 111_320.0
            val len2 = bx * bx + by * by
            var far = -1.0; var at = -1
            for (k in i + 1 until j) {
                val x = (p[k].lon - p[i].lon) * kx; val y = (p[k].lat - p[i].lat) * 111_320.0
                val t = if (len2 == 0.0) 0.0 else (x * bx + y * by) / len2
                val tc = t.coerceIn(0.0, 1.0)
                val d = hypot(x - tc * bx, y - tc * by)
                if (d > far) { far = d; at = k }
            }
            if (far > tolM) { keep[at] = true; stack.add(i to at); stack.add(at to j) }
        }
        return p.filterIndexed { k, _ -> keep[k] }
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
        return Grid(nav, ok, cost, g.prox, g.planes, g.nopass, g.rad, g.k, g.pier, g.snag, g.soft, g.gate,
            g.narrowDist, g.narrowHalf)
    }

    /**
     * A chord through a narrow channel must not move closer to the edge than
     * the planned path beside it. The keep-out is a quarter of the local width,
     * and never more than [CENTER_KEEP_M]. A one-cell channel is its own middle,
     * so that chord still straightens. Wide channels and open water are not checked.
     */
    private fun keepsCenter(a: Area, g: Grid, path: IntArray, i0: Int, i1: Int): Boolean {
        val w = a.w
        val h = a.h
        val res = a.res
        val p = path[i0]
        val q = path[i1]
        val r0 = p / w
        val c0 = p % w
        val r1 = q / w
        val c1 = q % w
        // Path cells that have a real middle beside them (2 or more steps from the
        // edge). A one-cell canal is all 1s: nothing to protect, and the chord
        // straightens as before. Ties keep the more central cell, so a pinch does
        // not excuse the cells beside the middle.
        val sr = ArrayList<Int>()
        val sc = ArrayList<Int>()
        val sd = ArrayList<Int>()
        for (k in i0..i1) {
            val cell = path[k]
            val d = g.narrowDist[cell].toInt() and 0xff
            if (d >= 2) {
                sr.add(cell / w)
                sc.add(cell % w)
                sd.add(d)
            }
        }
        if (sr.isEmpty()) return true
        val n = max(abs(r1 - r0), abs(c1 - c0)) * 4 + 1
        var checked = 0
        var close = 0
        for (k in 0..n) {
            val t = k.toDouble() / n
            val rf = r0 + (r1 - r0) * t
            val cf = c0 + (c1 - c0) * t
            val r = rf.roundToInt()
            val c = cf.roundToInt()
            if (r !in 0 until h || c !in 0 until w) continue
            val i = r * w + c
            val dist = g.narrowDist[i].toInt() and 0xff
            val half = g.narrowHalf[i].toInt() and 0xff
            if (dist == 0 || half == 0) continue
            checked++
            var bestHop = 99
            var limit = 255
            for (s in sr.indices) {
                val hopHere = max(abs(sr[s] - r), abs(sc[s] - c))
                if (hopHere > 2 || hopHere > bestHop) continue
                if (hopHere < bestHop || sd[s] > limit) {
                    bestHop = hopHere
                    limit = sd[s]
                }
            }
            if (limit == 255 || dist >= limit) continue
            // Closer to the edge than the path beside it. A one-cell channel never
            // gets here. Count it when the cell center is inside the keep-out,
            // min(a quarter of the local width, 30 m).
            val haveM = (dist - 0.5) * res
            val widthM = 2.0 * (half - 0.5) * res
            val needM = min(CENTER_KEEP_FRAC * widthM, CENTER_KEEP_M)
            if (haveM + 1e-6 < needM) close++
        }
        // A corner sample may dip toward the edge. Half the chord doing it means the line moved there.
        return close * 2 <= max(checked, 1)
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
