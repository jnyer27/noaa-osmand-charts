package com.jnyer27.noaaroute

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
        val packs = areas.associate { a -> val id = (a as Map<*, *>)["id"] as String; id to Pack(File(dir, "$id.bin")) }
        val failures = ArrayList<String>()
        // Each area's own tests run on its pack alone (an old area and the regions that
        // replace it overlap); top-level tests run on the packs they list
        val groups = areas.map { a -> val area = a as Map<*, *>; listOf(area["id"] as String) to (area["tests"] as List<*>?).orEmpty() } +
            (root["tests"] as List<*>?).orEmpty().map { t -> ((t as Map<*, *>)["areas"] as List<*>).map { it as String } to listOf(t) }
        for ((ids, tests) in groups) {
            if (tests.isEmpty()) continue
            val charts = ChartSet(ids.map { packs[it] ?: error("No pack $it") })
            for (t in tests) {
                val test = t as Map<*, *>
                val name = "${ids.joinToString("+")}: ${test["name"]}"
                val pts = (test["points"] as List<*>).map { Coords.parse(it as String)!! }
                val depth = test["depth"] as Double
                val air = test["air"] as Double?
                val mustFail = test["fail"] == true
                val t0 = System.currentTimeMillis()
                try {
                    val r = Planner(charts).plan("test", pts, depth, air)
                    val sec = (System.currentTimeMillis() - t0) / 1000.0
                    val nm = test["nm"] as List<*>?
                    val straight = pts.zipWithNext { p, q -> Planner.distanceM(p, q) }.sum() / Planner.NM
                    println("PASS? $name: %.1f nm (straight %.1f), %d points, %.0f m grid, %.1f s".format(r.totalNm, straight, r.points.size, r.resM, sec))
                    when {
                        mustFail -> failures.add("$name: expected no route, got %.1f nm".format(r.totalNm))
                        nm != null && r.totalNm !in (nm[0] as Double)..(nm[1] as Double) ->
                            failures.add("$name: %.1f nm outside %s".format(r.totalNm, nm))
                        r.totalNm < straight * 0.99 -> failures.add("$name: shorter than a straight line?")
                    }
                } catch (e: PlanException) {
                    println("REFUSED $name: ${e.message!!.lines().first()}")
                    if (!mustFail) failures.add("$name: ${e.message}")
                }
            }
        }
        packs.values.forEach { it.close() }
        assertTrue("Reference route failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
