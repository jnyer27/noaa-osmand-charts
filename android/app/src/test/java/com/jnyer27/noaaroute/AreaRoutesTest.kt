package com.jnyer27.noaaroute

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import kotlin.math.max
import org.junit.Test
import java.io.File

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
                val t0 = System.currentTimeMillis()
                try {
                    // Progress (overview, guide points, each stretch's grid) goes to the log too
                    val r = Planner(charts).plan("test", pts, depth, air, stayInside = inside) { msg -> println("PROGRESS $name: $msg") }
                    val sec = (System.currentTimeMillis() - t0) / 1000.0
                    val nm = test["nm"] as List<*>?
                    val straight = pts.zipWithNext { p, q -> Planner.distanceM(p, q) }.sum() / Planner.NM
                    // legs under 40 m: a staircase of grid steps where a narrow canal should be one line
                    val short = r.legs.count { it.distM < 40.0 }
                    println("PASS? $name: %.1f nm (straight %.1f), %d points, %d legs under 40 m, %.0f m grid, %.1f s".format(r.totalNm, straight, r.points.size, short, r.resM, sec))
                    // The route itself, so a surprising length can be looked at from the log
                    println("POINTS $name: " + r.points.joinToString(";") { "%.5f,%.5f".format(it.lat, it.lon) })
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
                    // failText: the refusal must give this reason
                    (test["failText"] as String?)?.let { if (!e.message!!.contains(it)) failures.add("$name: refused without \"$it\": ${e.message}") }
                }
            }
            open.forEach { it.close() }
        }
        assertTrue("Reference route failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
