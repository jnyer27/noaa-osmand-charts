package com.jnyer27.noaaroute

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Reference routes from tools/areas.json, run against freshly built area
 * packs. The route-pack workflow sets PACK_DIR (packs/) and AREAS_JSON; in
 * a normal app build the test is skipped.
 */
class AreaRoutesTest {
    @Test
    fun referenceRoutes() {
        val dir = System.getenv("PACK_DIR")
        val areasJson = System.getenv("AREAS_JSON")
        assumeTrue("PACK_DIR/AREAS_JSON not set", dir != null && areasJson != null)
        val areas = (MiniJson.parse(File(areasJson!!).readText()) as Map<*, *>)["areas"] as List<*>
        val packs = areas.map { Pack(File(dir, ((it as Map<*, *>)["id"] as String) + ".bin")) }
        val charts = ChartSet(packs)
        val failures = ArrayList<String>()
        for (a in areas) {
            val area = a as Map<*, *>
            for (t in area["tests"] as List<*>) {
                val test = t as Map<*, *>
                val name = "${area["id"]}: ${test["name"]}"
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
        packs.forEach { it.close() }
        assertTrue("Reference route failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
