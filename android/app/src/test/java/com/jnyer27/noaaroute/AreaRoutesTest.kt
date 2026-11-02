package com.jnyer27.noaaroute

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import org.junit.Test
import java.io.File
import java.util.ArrayDeque

/**
 * Reference routes from tools/areas.json, run against freshly built area
 * packs: each area's tests on its own pack, top-level tests on the packs they
 * list (NCDS regions 04 + 05 together, across their seam). The route-pack workflow sets PACK_DIR (packs/) and AREAS_JSON; in
 * a normal app build the test is skipped.
 */
class AreaRoutesTest {
    @Test
    fun referenceRoutes() {
        val dir = System.getenv("PACK_DIR")
        val areasJson = System.getenv("AREAS_JSON")
        assumeTrue("PACK_DIR/AREAS_JSON not set", dir != null && areasJson != null)
        val root = MiniJson.parse(File(areasJson!!).readText()) as Map<*, *>
        val areas = root["areas"] as List<*>
        // Packs are opened per group and closed after it: with every region open at once
        // (34 packs, tens of thousands of notes) the test ran out of its 512 MB heap
        val known = areas.map { a -> (a as Map<*, *>)["id"] as String }.toSet()
        val failures = ArrayList<String>()
        // Each area's own tests run on its pack alone (an old area and the regions that
        // replace it overlap); top-level tests run on the packs they list
        val groups = areas.map { a -> val area = a as Map<*, *>; listOf(area["id"] as String) to (area["tests"] as List<*>?).orEmpty() } +
            (root["tests"] as List<*>?).orEmpty().map { t -> ((t as Map<*, *>)["areas"] as List<*>).map { it as String } to listOf(t) }
        for ((ids, tests) in groups) {
            if (tests.isEmpty()) continue
            val open = ids.map { id -> require(id in known) { "No pack $id" }; Pack(File(dir, "$id.bin")) }
            val charts = ChartSet(open)
            for (t in tests) {
                val test = t as Map<*, *>
                val name = "${ids.joinToString("+")}: ${test["name"]}"
                val pts = (test["points"] as List<*>).map { Coords.parse(it as String)!! }
                val depth = test["depth"] as Double
                val air = test["air"] as Double?
                val mustFail = test["fail"] == true
                val inside = test["inside"] == true // Stay inside: open sea is a wall
                val moveEnds = test["moveEnds"] == true // Move ends to navigable water
                val t0 = System.currentTimeMillis()
                try {
                    // Progress (overview, guide points, each stretch's grid) goes to the log too
                    val r = Planner(charts).plan("test", pts, depth, air, stayInside = inside, moveEnds = moveEnds) { msg -> println("PROGRESS $name: $msg") }
                    val sec = (System.currentTimeMillis() - t0) / 1000.0
                    val nm = test["nm"] as List<*>?
                    val straight = pts.zipWithNext { p, q -> Planner.distanceM(p, q) }.sum() / Planner.NM
                    // legs under 40 m: a staircase of grid steps where a narrow canal should be one line
                    val short = r.legs.count { it.distM < 40.0 }
                    println("PASS? $name: %.1f nm (straight %.1f), %d points, %d legs under 40 m, %.0f m grid, %.1f s".format(r.totalNm, straight, r.points.size, short, r.resM, sec))
                    // The route itself, so a surprising length can be looked at from the log
                    println("POINTS $name: " + r.points.joinToString(";") { "%.5f,%.5f".format(it.lat, it.lon) })
                    for (m in r.messages) println("MESSAGE $name: $m")
                    // "avoid": [[lat, lon, meters], ...]: places the route must keep away from
                    // (where an earlier version strayed out of a channel)
                    val strayed = (test["avoid"] as List<*>?).orEmpty().mapNotNull { av ->
                        val (lat, lon, m) = (av as List<*>).map { it as Double }
                        val c = LatLon(lat, lon)
                        val hit = r.points.zipWithNext().any { (p, q) ->
                            val n = max(1, (Planner.distanceM(p, q) / 20).toInt())
                            (0..n).any { k -> Planner.distanceM(c, LatLon(p.lat + (q.lat - p.lat) * k / n, p.lon + (q.lon - p.lon) * k / n)) < m }
                        }
                        if (hit) "%.5f, %.5f".format(lat, lon) else null
                    }
                    when {
                        mustFail -> failures.add("$name: expected no route, got %.1f nm".format(r.totalNm))
                        strayed.isNotEmpty() -> failures.add("$name: passes within the avoid radius of ${strayed.joinToString("; ")}")
                        nm != null && r.totalNm !in (nm[0] as Double)..(nm[1] as Double) ->
                            failures.add("$name: %.1f nm outside %s".format(r.totalNm, nm))
                        test["maxShort"] != null && short > (test["maxShort"] as Double) ->
                            failures.add("$name: $short legs under 40 m, more than ${(test["maxShort"] as Double).toInt()} (grid staircase?)")
                        r.totalNm < straight * 0.99 -> failures.add("$name: shorter than a straight line?")
                        // "message": text one of the route's messages must contain
                        test["message"] != null && r.messages.none { it.contains(test["message"] as String) } ->
                            failures.add("$name: no message containing \"${test["message"]}\"")
                        // "cleanEnd": the leg before the last (the one to your point) has no shallow water
                        test["cleanEnd"] == true && r.legs.size > 1 && r.legs[r.legs.size - 2].shoalM > 0 ->
                            failures.add("$name: the leg before the destination has %.0f m of shallow water".format(r.legs[r.legs.size - 2].shoalM))
                    }
                    // "dredgedEdge": [lat0, lat1, meters]. Where a dredged row has a cell at
                    // least that far from the edge, the route there must be too. A wide
                    // basin has no such row in a narrow cut, and a 2-cell pinch is exempt.
                    (test["dredgedEdge"] as List<*>?)?.let { spec ->
                        val lat0 = spec[0] as Double
                        val lat1 = spec[1] as Double
                        val minM = spec[2] as Double
                        val edge = dredgedMiddleClearance(open, r, lat0, lat1, minM)
                        println("EDGE $name: %.0f m minimum, %.0f%% of samples at least %.0f m, %d samples where the channel has a middle".format(
                            edge.minM, edge.share * 100.0, minM, edge.samples))
                        if (edge.samples == 0) failures.add("$name: route misses the dredged channel between $lat0 and $lat1")
                        else if (edge.share < 0.6) failures.add("$name: only %.0f%% of the dredged channel is at least %.0f m from the edge".format(edge.share * 100.0, minM))
                    }
                    // "resolve": run Try to avoid checks; a number is the fewest stretches it must clear
                    test["resolve"]?.let { want ->
                        val t1 = System.currentTimeMillis()
                        val res = Planner(charts).resolveChecks(r, pts, stayInside = inside)
                        val a = res.route
                        println("RESOLVE $name: " + res.summary + " %.1f nm, %.1f s".format(a.totalNm, (System.currentTimeMillis() - t1) / 1000.0))
                        println("POINTS $name (adjusted): " + a.points.joinToString(";") { "%.5f,%.5f".format(it.lat, it.lon) })
                        if (want is Double && res.resolved < want) failures.add("$name: avoid checks resolved ${res.resolved}, expected at least ${want.toInt()}")
                        if (a.points.first() != r.points.first() || a.points.last() != r.points.last()) failures.add("$name: avoid checks moved an end")
                    }
                } catch (e: PlanException) {
                    println("REFUSED $name: ${e.message!!.lines().first()}")
                    if (!mustFail) failures.add("$name: ${e.message}")
                }
            }
            open.forEach { it.close() }
        }
        assertTrue("Reference route failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * How close the route comes to the edge of a dredged area between [lat0] and [lat1],
     * counting only samples on a row that also has a cell at least [minM] from that edge.
     * Returns that distance in meters, and how many samples were counted. The distance
     * is 0 when the sample is outside the dredged area. (-1, 0) when nothing qualified.
     */
    private class EdgeStat(val minM: Double, val share: Double, val samples: Int)

    private fun dredgedMiddleClearance(packs: List<Pack>, route: Route, lat0: Double, lat1: Double, minM: Double): EdgeStat {
        val samples = ArrayList<LatLon>()
        for ((p, q) in route.points.zipWithNext()) {
            val n = max(1, (Planner.distanceM(p, q) / 10.0).toInt())
            for (k in 0..n) {
                val t = k / n.toDouble()
                val lat = p.lat + (q.lat - p.lat) * t
                if (lat in lat0..lat1) samples.add(LatLon(lat, p.lon + (q.lon - p.lon) * t))
            }
        }
        if (samples.isEmpty()) return EdgeStat(-1.0, 0.0, 0)
        val pack = packs.firstOrNull { pk -> samples.any { pk.covers(it.lat, it.lon) } } ?: return EdgeStat(-1.0, 0.0, 0)
        val pad = 0.004
        val lon0 = samples.minOf { it.lon } - pad
        val lon1 = samples.maxOf { it.lon } + pad
        val r0 = max(0, floor((pack.north - (lat1 + pad)) / pack.dlat).toInt())
        val r1 = min(pack.height, ceil((pack.north - (lat0 - pad)) / pack.dlat).toInt())
        val c0 = max(0, floor((lon0 - pack.west) / pack.dlon).toInt())
        val c1 = min(pack.width, ceil((lon1 - pack.west) / pack.dlon).toInt())
        val hh = r1 - r0
        val ww = c1 - c0
        if (hh <= 0 || ww <= 0 || hh.toLong() * ww > 2_000_000L) return EdgeStat(-1.0, 0.0, 0)
        val tile = pack.tile
        val tt = tile * tile
        val cache = HashMap<Long, ByteArray?>()
        fun dredged(gr: Int, gc: Int): Boolean {
            if (gr !in 0 until pack.height || gc !in 0 until pack.width) return false
            val tx = gc / tile
            val ty = gr / tile
            val key = ty.toLong().shl(32) or tx.toLong()
            val data = cache.getOrPut(key) { pack.tileData(tx, ty) } ?: return false
            val v = data[Pack.P_DEPTH * tt + (gr % tile) * tile + (gc % tile)].toInt() and 0xff
            return pack.depthTable.getOrNull(v)?.dredged == true
        }
        val drg = BooleanArray(hh * ww) { i -> dredged(r0 + i / ww, c0 + i % ww) }
        val dist = IntArray(hh * ww) { -1 }
        val q = ArrayDeque<Int>()
        for (i in drg.indices) if (drg[i]) {
            val r = i / ww
            val c = i % ww
            val edge = (r > 0 && !drg[i - ww]) || (r < hh - 1 && !drg[i + ww]) ||
                (c > 0 && !drg[i - 1]) || (c < ww - 1 && !drg[i + 1])
            if (edge) { dist[i] = 1; q.add(i) }
        }
        while (q.isNotEmpty()) {
            val i = q.removeFirst()
            val r = i / ww
            val c = i % ww
            val nd = dist[i] + 1
            fun see(j: Int) { if (drg[j] && dist[j] < 0) { dist[j] = nd; q.add(j) } }
            if (r > 0) see(i - ww)
            if (r < hh - 1) see(i + ww)
            if (c > 0) see(i - 1)
            if (c < ww - 1) see(i + 1)
        }
        val rowOk = BooleanArray(hh)
        for (r in 0 until hh) for (c in 0 until ww) {
            val d = dist[r * ww + c]
            if (d > 0 && (d - 0.5) * pack.res + 1e-6 >= minM) { rowOk[r] = true; break }
        }
        var best = Double.POSITIVE_INFINITY
        var n = 0
        var far = 0
        for (s in samples) {
            val r = floor((pack.north - s.lat) / pack.dlat).toInt() - r0
            val c = floor((s.lon - pack.west) / pack.dlon).toInt() - c0
            if (r !in 0 until hh || c !in 0 until ww || !rowOk[r]) continue
            val d = dist[r * ww + c]
            val meters = if (d <= 0) 0.0 else (d - 0.5) * pack.res
            n++
            if (meters + 1e-6 >= minM) far++
            if (meters < best) best = meters
        }
        if (n == 0) return EdgeStat(-1.0, 0.0, 0)
        return EdgeStat(best, far.toDouble() / n, n)
    }
}
