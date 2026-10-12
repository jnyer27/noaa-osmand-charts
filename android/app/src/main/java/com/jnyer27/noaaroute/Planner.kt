package com.jnyer27.noaaroute

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
    companion object {
        const val NM = 1852.0
        const val ROUNDING_M = 0.05
        const val MAX_CELLS = 4_000_000
        const val CUT_OFF_M = 250.0 // how far an end may move to escape a cut-off pocket
        const val BLOCKED = 0
        const val SAFE = 1
        const val MARGINAL = 2
        const val HAZARD = 3
        const val STRUCT = 4
        const val SHALLOW = 5
        private const val K = 5 // proximity reach in cells

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

    private class Grid(val nav: ByteArray, val ok: BooleanArray, val cost: FloatArray, val prox: ByteArray,
                       val planes: Array<ByteArray>, val nopass: BooleanArray)

    private fun buildGrid(a: Area, depthM: Double, airM: Double?, bufM: Double): Grid {
        val planes = pack.window(a.r0, a.c0, a.h, a.w, a.f, depthM)
        val n = a.n
        val w = a.w
        val h = a.h
        val d = depthM - ROUNDING_M
        val hz = depthM + Pack.FT
        val air = airM ?: 0.0
        val nav = ByteArray(n)
        val nopass = BooleanArray(n)
        val spanok = BooleanArray(n)
        for (i in 0 until n) {
            val fl = planes[Pack.P_FLAGS][i].toInt() and 0xff
            val hzv = planes[Pack.P_HAZARD][i].toInt() and 0xff
            val sv = planes[Pack.P_SOUND][i].toInt() and 0xff
            nav[i] = when {
                fl and Pack.F_STRUCT != 0 -> STRUCT
                hzv != 0 && (hzv - 1) / 10.0 < hz -> HAZARD
                sv != 0 && (sv - 1) / 10.0 < d -> HAZARD
                fl and Pack.F_PROHIB != 0 -> BLOCKED
                else -> planes[Pack.P_DEPTH][i].toInt()
            }.toByte()
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

        // Hazard buffer
        // Round down so a coarser grid never inflates the buffer (60 m on a 40 m grid
        // is 1 cell, not 2: rounding up closed the C&D Canal)
        val rad = max(1, floor(bufM / a.res + 0.25).toInt())
        val disc = ArrayList<IntArray>()
        for (dr in -rad..rad) for (dc in -rad..rad) if (dr * dr + dc * dc <= rad * rad) disc.add(intArrayOf(dr, dc))
        for (i in 0 until n) if (nav[i].toInt() == HAZARD) {
            val r = i / w
            val c = i % w
            for (o in disc) {
                val rr = r + o[0]
                val cc = c + o[1]
                if (rr in 0 until h && cc in 0 until w) ok[rr * w + cc] = false
            }
        }

        // Distance (cells, capped at K) to the nearest unusable cell
        val dist = ByteArray(n) { K.toByte() }
        var frontier = IntArray(n)
        var next = IntArray(n)
        var fn = 0
        for (i in 0 until n) if (!ok[i]) dist[i] = 0
        for (i in 0 until n) if (!ok[i]) {
            // only unusable cells next to usable water start the spread
            val r = i / w
            val c = i % w
            if ((r > 0 && ok[i - w]) || (r < h - 1 && ok[i + w]) || (c > 0 && ok[i - 1]) || (c < w - 1 && ok[i + 1])) frontier[fn++] = i
        }
        for (step in 1 until K) {
            var nn = 0
            for (k in 0 until fn) {
                val i = frontier[k]
                val r = i / w
                val c = i % w
                if (r > 0 && dist[i - w] > step) { dist[i - w] = step.toByte(); next[nn++] = i - w }
                if (r < h - 1 && dist[i + w] > step) { dist[i + w] = step.toByte(); next[nn++] = i + w }
                if (c > 0 && dist[i - 1] > step) { dist[i - 1] = step.toByte(); next[nn++] = i - 1 }
                if (c < w - 1 && dist[i + 1] > step) { dist[i + 1] = step.toByte(); next[nn++] = i + 1 }
            }
            val t = frontier; frontier = next; next = t
            fn = nn
        }
        val cost = FloatArray(n) { Float.POSITIVE_INFINITY }
        val prox = ByteArray(n)
        for (i in 0 until n) if (ok[i]) {
            val p = K - dist[i]
            prox[i] = p.toByte()
            var c = 1f + 0.6f * p
            if (nav[i].toInt() == MARGINAL) c *= 4f
            cost[i] = c
        }
        return Grid(nav, ok, cost, prox, planes, nopass)
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

    private fun astar(a: Area, cost: FloatArray, start: Int, goal: Int): IntArray? {
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
                if (k >= 4 && (cost[r * w + cc] == Float.POSITIVE_INFINITY || cost[rr * w + c] == Float.POSITIVE_INFINITY)) continue
                val ng = gi + step[k] * res * (ci + cj) * 0.5f
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
    )

    /** Water reachable from start: cells, touches window edge, reason -> (edge cells, example). */
    private fun enclosure(a: Area, g: Grid, start: Int): Triple<Int, Boolean, Map<String, Pair<Int, Int>>> {
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
                    else -> "land"
                }
                val cur = walls[why]
                walls[why] = if (cur == null) 1 to j else cur.first + 1 to cur.second
            }
        }
        return Triple(cnt, edge, walls)
    }

    private fun lineCells(a: Area, p: Int, q: Int): IntArray {
        val w = a.w
        val r0 = p / w
        val c0 = p % w
        val r1 = q / w
        val c1 = q % w
        val n = max(abs(r1 - r0), abs(c1 - c0)) * 2 + 1
        val out = LinkedHashSet<Int>()
        for (k in 0..n) {
            val t = k.toDouble() / n
            out.add((r0 + (r1 - r0) * t).roundToInt() * w + (c0 + (c1 - c0) * t).roundToInt())
        }
        return out.toIntArray()
    }

    private fun simplify(a: Area, g: Grid, path: IntArray): IntArray {
        fun marg(i: Int) = g.nav[i].toInt() == MARGINAL
        fun fits(p: Int, q: Int, maxp: Int, nmarg: Int): Boolean {
            var m = 0
            for (i in lineCells(a, path[p], path[q])) {
                if (!g.ok[i] || g.prox[i] > maxp + 1) return false
                if (marg(i)) { m++; if (m > nmarg + 1) return false }
            }
            return true
        }
        val keep = ArrayList<Int>()
        keep.add(0)
        var s = 0
        var maxp = g.prox[path[0]].toInt()
        var nmarg = if (marg(path[0])) 1 else 0
        var j = 1
        while (j < path.size) {
            val p2 = max(maxp, g.prox[path[j]].toInt())
            val m2 = nmarg + if (marg(path[j])) 1 else 0
            if (j == s + 1 || fits(s, j, p2, m2)) {
                maxp = p2; nmarg = m2; j++
            } else {
                s = j - 1
                keep.add(s)
                maxp = max(g.prox[path[s]].toInt(), g.prox[path[j]].toInt())
                nmarg = (if (marg(path[s])) 1 else 0) + (if (marg(path[j])) 1 else 0)
            }
        }
        if (keep.last() != path.size - 1) keep.add(path.size - 1)
        return keep.map { path[it] }.toIntArray()
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

    private fun legs(a: Area, g: Grid, wps: List<LatLon>, idx: IntArray, seen: HashSet<String>): List<Leg> {
        val out = ArrayList<Leg>()
        val kinds = listOf("resare" to Pack.F_RESNOTE, "bridge" to Pack.F_BRIDGE, "cable" to Pack.F_CABLE)
        val every = max(1, (500 / a.res).toInt())
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

    private class Piece(val wps: List<LatLon>, val legs: List<Leg>, val res: Double)

    private val widths = doubleArrayOf(1.0, 2.5, 5.0)

    /** Label for a routing point: user point number, or a guide point placed by the planner. */
    private fun label(n: Int?, p: LatLon) =
        if (n != null) "Point $n (%.5f, %.5f)".format(p.lat, p.lon) else "A guide point the planner placed at %.5f, %.5f".format(p.lat, p.lon)

    private fun noRoute(c: Ctx, a: Area, who: String, enc: Triple<Int, Boolean, Map<String, Pair<Int, Int>>>): PlanException {
        val (cnt, _, walls) = enc
        val sb = StringBuilder()
        sb.append("No route found for ${fmt(c.depthFt)} ft depth")
        sb.append(if (c.airFt != null) " and ${fmt(c.airFt)} ft air draft.\n" else ".\n")
        sb.append("$who is in a pocket of about %.1f ha of usable water, closed off by:\n".format(cnt * a.res * a.res / 1e4))
        for ((why, v) in walls.entries.sortedByDescending { it.value.first }) {
            val ll = a.latlon(v.second)
            sb.append("  - ${wallText[why]} (${(v.first * a.res).roundToInt()} m of edge, near %.5f, %.5f)\n".format(ll.lat, ll.lon))
        }
        sb.append("Move the point into open water, or adjust depth or air draft.")
        return PlanException(sb.toString())
    }

    /** Route a to b on the full-detail grid (its window must fit without coarsening). */
    private fun planPiece(p: LatLon, q: LatLon, np: Int?, nq: Int?, c: Ctx): Piece {
        for ((attempt, widen) in widths.withIndex()) {
            val a = area(listOf(p, q), widen)
            val g = buildGrid(a, c.depthM, c.airM, c.bufM)
            val (sp, mp) = snap(a, g, p)
            val (sq, mq) = snap(a, g, q)
            for ((n, m) in listOf(np to mp, nq to mq)) if (n != null && m > a.res && n !in c.moved) {
                c.moved.add(n)
                c.messages.add("Point $n moved ${m.roundToInt()} m to the nearest usable water")
            }
            var path = astar(a, g.cost, sp, sq)
            if (path == null) {
                // An end in a small pocket walled off only by land or structures (a marina
                // basin behind a charted pier, too thin for the grid): move it to the nearest
                // connected water within CUT_OFF_M. Pockets closed by shallow water, hazards
                // or low clearance are real limits and keep the "no route" explanation.
                val ends = listOf(enclosure(a, g, sp), enclosure(a, g, sq))
                for (e in (0..1).sortedBy { ends[it].first }) {
                    if (ends[e].second) continue // reaches the window edge: not a pocket
                    if (ends[e].third.keys.any { it != "land" && it != "struct" }) continue
                    val other = region(a, g.ok, if (e == 0) sq else sp)
                    val pt = if (e == 0) p else q
                    val (cell, dist) = nearestIn(a, other, pt, CUT_OFF_M) ?: continue
                    val retry = if (e == 0) astar(a, g.cost, cell, sq) else astar(a, g.cost, sp, cell)
                    if (retry != null) {
                        path = retry
                        val walls = ends[e].third.entries.sortedByDescending { it.value.first }.joinToString(" and ") { wallText[it.key]!! }
                        val n = if (e == 0) np else nq
                        c.messages.add((if (n != null) "Point $n" else "A guide point") +
                            ": the water there is cut off by $walls; the route " + (if (e == 0) "starts" else "ends") +
                            " ${dist.roundToInt()} m away at the nearest connected water.")
                        if (n != null) c.moved.add(n)
                        break
                    }
                }
            }
            if (path != null) {
                val idx = simplify(a, g, path)
                val wps = idx.map { a.latlon(it) }.toMutableList()
                wps[0] = p
                wps[wps.size - 1] = q
                return Piece(wps, legs(a, g, wps, idx, c.seen), a.res)
            }
            val ends = listOf(enclosure(a, g, sp), enclosure(a, g, sq))
            val closed = (0..1).filter { !ends[it].second }
            if (closed.isNotEmpty() || attempt == widths.size - 1) {
                val e = closed.firstOrNull() ?: (0..1).minBy { ends[it].first }
                throw noRoute(c, a, if (e == 0) label(np, p) else label(nq, q), ends[e])
            }
            c.progress("No route inside this area, widening the search")
        }
        throw PlanException("No route found")
    }

    /** Full-detail window of +/- halfM meters around p. */
    private fun areaAround(p: LatLon, halfM: Double): Area {
        val dy = halfM / Pack.M_PER_DEG
        val dx = halfM / (Pack.M_PER_DEG * cos(Math.toRadians(p.lat)))
        val r0 = max(0, floor((pack.north - (p.lat + dy)) / pack.dlat).toInt())
        val r1 = min(pack.height, ceil((pack.north - (p.lat - dy)) / pack.dlat).toInt())
        val c0 = max(0, floor((p.lon - dx - pack.west) / pack.dlon).toInt())
        val c1 = min(pack.width, ceil((p.lon + dx - pack.west) / pack.dlon).toInt())
        return Area(r0, c0, r1 - r0, c1 - c0, 1)
    }

    /**
     * Long trips: plan on a coarse overview grid, with the water around both
     * ends worked out at full detail and stamped in (a narrow creek must not
     * disappear at 100 m), then return open-water guide points about every
     * 18 km along that path. Each stretch between them is then routed at full
     * detail by planPiece.
     */
    private fun guidePoints(p: LatLon, q: LatLon, np: Int?, nq: Int?, c: Ctx): Pair<List<LatLon>, Double> {
        for ((attempt, widen) in widths.withIndex()) {
            val a = area(listOf(p, q), widen)
            c.progress("Overview at ${a.res.roundToInt()} m (${a.w} x ${a.h})")
            val g = buildGrid(a, c.depthM, c.airM, c.bufM)
            val ends = IntArray(2)
            for ((k, pt) in listOf(p, q).withIndex()) {
                val fa = areaAround(pt, 4000.0)
                val fg = buildGrid(fa, c.depthM, c.airM, c.bufM)
                var (s, _) = snap(fa, fg, pt)
                val enc0 = enclosure(fa, fg, s)
                if (!enc0.second && enc0.third.keys.all { it == "land" || it == "struct" }) {
                    // Cut off within the detail window: use the nearest water that leads out of it
                    val border = (0 until fa.n).filter { i ->
                        val r = i / fa.w
                        val col = i % fa.w
                        fg.ok[i] && (r == 0 || r == fa.h - 1 || col == 0 || col == fa.w - 1)
                    }.toIntArray()
                    val out = region(fa, fg.ok, border)
                    nearestIn(fa, out, pt, CUT_OFF_M)?.let { s = it.first }
                }
                // Everything reachable from the point within the detail window is usable on the overview
                val seen = BooleanArray(fa.n)
                val stack = IntArray(fa.n)
                var sp = 0
                stack[sp++] = s
                seen[s] = true
                while (sp > 0) {
                    val i = stack[--sp]
                    val ci = a.cell(fa.latlon(i))
                    g.ok[ci] = true
                    if (g.cost[ci] == Float.POSITIVE_INFINITY) g.cost[ci] = 2f
                    val r = i / fa.w
                    val col = i % fa.w
                    for (j in intArrayOf(if (r > 0) i - fa.w else -1, if (r < fa.h - 1) i + fa.w else -1,
                                         if (col > 0) i - 1 else -1, if (col < fa.w - 1) i + 1 else -1)) {
                        if (j >= 0 && !seen[j] && fg.ok[j]) { seen[j] = true; stack[sp++] = j }
                    }
                }
                ends[k] = a.cell(fa.latlon(s))
            }
            val path = astar(a, g.cost, ends[0], ends[1])
            if (path != null) {
                // Cumulative distance along the overview path
                val cum = DoubleArray(path.size)
                for (k in 1 until path.size) cum[k] = cum[k - 1] + distanceM(a.latlon(path[k - 1]), a.latlon(path[k]))
                val total = cum.last()
                val n = max(1, ceil(total / 18000.0).toInt())
                val out = ArrayList<LatLon>()
                var from = 0
                for (k in 1 until n) {
                    val target = total * k / n
                    var j = from
                    while (j < path.size - 1 && cum[j] < target) j++
                    // Prefer a nearby path cell in open, safe water
                    val reach = max(1, (2000 / a.res).toInt())
                    var best = j
                    for (d in 0..reach) {
                        val cand = listOf(j + d, j - d).firstOrNull { it in (from + 1) until path.size - 1 &&
                            g.nav[path[it]].toInt() == SAFE && g.prox[path[it]].toInt() == 0 }
                        if (cand != null) { best = cand; break }
                    }
                    if (cum[best] < 4000 || total - cum[best] < 4000) continue
                    out.add(a.latlon(path[best]))
                    from = best
                }
                return out to a.res
            }
            val enc = listOf(enclosure(a, g, ends[0]), enclosure(a, g, ends[1]))
            val closed = (0..1).filter { !enc[it].second }
            if (closed.isNotEmpty() || attempt == widths.size - 1) {
                val e = closed.firstOrNull() ?: (0..1).minBy { enc[it].first }
                throw noRoute(c, a, (if (e == 0) label(np, p) else label(nq, q)) + " (overview at ${a.res.roundToInt()} m)", enc[e])
            }
            c.progress("No route inside this area, widening the search")
        }
        throw PlanException("No route found")
    }

    // ------------------------------------------------------------ main entry

    fun plan(name: String, pts: List<LatLon>, depthFt: Double, airFt: Double?, bufM: Double = 60.0,
             progress: (String) -> Unit = {}): Route {
        require(pts.size >= 2)
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
                listOf(p) + guide + listOf(q)
            }
            for (j in 0 until chain.size - 1) {
                progress("Routing leg ${k + 1} of ${pts.size - 1}" + if (chain.size > 2) ", stretch ${j + 1} of ${chain.size - 1}" else "")
                val piece = planPiece(chain[j], chain[j + 1], if (j == 0) k + 1 else null,
                                      if (j == chain.size - 2) k + 2 else null, c)
                if (wps.isEmpty()) wps.addAll(piece.wps) else wps.addAll(piece.wps.drop(1))
                legs.addAll(piece.legs)
                res = max(res, piece.res)
            }
        }
        return Route(name, wps, legs, depthFt, airFt, res, c.messages, guideRes)
    }

    private fun fmt(v: Double) = if (v == floor(v)) v.toInt().toString() else v.toString()
}
