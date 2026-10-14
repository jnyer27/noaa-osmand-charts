package com.jnyer27.noaaroute

import kotlin.math.ceil
import kotlin.math.max

/** A NOAA Chart Display Service (NCDS) region: its code ("04") and rectangle. */
class NcdsRegion(val code: String, val west: Double, val south: Double, val east: Double, val north: Double) {
    fun contains(p: LatLon) = p.lon in west..east && p.lat in south..north
}

/** A published route pack, as catalog.json lists it. */
class AreaInfo(
    val id: String, val name: String, val description: String, val file: String, val size: Long,
    val sha256: String, val created: String, val bbox: DoubleArray,
    val ncds: String?, val deprecated: Boolean, val replacedBy: List<String>,
) {
    fun covers(p: LatLon) = p.lon in bbox[0]..bbox[2] && p.lat in bbox[1]..bbox[3]
    /** "Region 04" from "Region 04: Upper Chesapeake and Delaware Bay". */
    val shortName get() = name.substringBefore(':')
}

/** What a trip needs: packs to download, and whether part of it is outside every published pack. */
class TripNeed(val download: List<AreaInfo>, val outside: Boolean)

object Regions {
    /** NOAA's NCDS regions (codes and rectangles from its ncds_tilecache_metadata layer, Oct 2026). */
    val ALL = listOf(
            NcdsRegion("01a", -76.68, 42.80, -69.15, 45.50),
            NcdsRegion("01b", -69.15, 42.80, -67.30, 45.50),
            NcdsRegion("01c", -67.30, 42.80, -64.95, 45.50),
            NcdsRegion("02a", -76.68, 41.10, -71.08, 42.80),
            NcdsRegion("02b", -71.08, 41.10, -64.95, 42.80),
            NcdsRegion("03", -76.68, 39.73, -64.95, 41.10),
            NcdsRegion("04", -77.81, 38.23, -64.95, 39.73),
            NcdsRegion("05", -77.81, 35.50, -64.95, 38.23),
            NcdsRegion("06", -82.20, 32.54, -64.95, 35.50),
            NcdsRegion("07", -82.20, 28.10, -66.70, 32.54),
            NcdsRegion("08", -80.60, 23.10, -66.70, 28.10),
            NcdsRegion("09", -80.60, 8.64, -64.20, 23.10),
            NcdsRegion("10", -89.07, 17.60, -80.60, 28.10),
            NcdsRegion("11", -89.07, 28.10, -82.20, 33.93),
            NcdsRegion("12", -92.46, 29.33, -89.07, 33.93),
            NcdsRegion("13", -92.46, 17.60, -89.07, 29.33),
            NcdsRegion("14", -98.29, 17.60, -92.46, 33.93),
            NcdsRegion("15", -83.90, 41.10, -76.68, 42.80),
            NcdsRegion("16", -83.93, 42.80, -76.68, 47.20),
            NcdsRegion("17a", -88.93, 43.80, -83.90, 46.00),
            NcdsRegion("17b", -88.93, 41.38, -83.90, 43.80),
            NcdsRegion("18", -92.70, 46.00, -83.90, 49.46),
            NcdsRegion("19a", -123.50, 15.00, -116.17, 33.78),
            NcdsRegion("19b", -123.50, 33.78, -113.91, 37.17),
            NcdsRegion("19c", -123.50, 37.17, -116.17, 39.05),
            NcdsRegion("19d", -144.00, 15.00, -123.50, 39.05),
            NcdsRegion("20a", -144.00, 39.05, -116.17, 44.70),
            NcdsRegion("20b", -129.92, 44.70, -116.20, 47.01),
            NcdsRegion("20c", -129.92, 47.01, -116.33, 60.33),
            NcdsRegion("21", -138.09, 44.70, -129.92, 52.49),
            NcdsRegion("22a", -138.09, 52.49, -129.92, 55.78),
            NcdsRegion("22b", -138.09, 55.78, -129.92, 56.83),
            NcdsRegion("23a", -138.09, 56.83, -129.92, 57.73),
            NcdsRegion("23b", -138.09, 57.73, -129.92, 61.25),
            NcdsRegion("24a", -148.80, 44.70, -138.09, 61.58),
            NcdsRegion("24b", -152.76, 44.70, -148.80, 61.58),
            NcdsRegion("25a", -159.00, 44.70, -152.76, 61.58),
            NcdsRegion("25b", -165.75, 44.70, -159.00, 61.58),
            NcdsRegion("26a", -173.40, 44.70, -165.75, 61.58),
            NcdsRegion("26b", -180.00, 44.70, -173.40, 61.58),
            NcdsRegion("27", -180.00, 61.58, -134.00, 81.60),
            NcdsRegion("28a", -180.00, 15.00, -162.68, 44.70),
            NcdsRegion("28b", -162.68, 15.00, -144.00, 44.70),
            NcdsRegion("29", -180.00, -17.56, -154.00, 15.00),
            NcdsRegion("30", 165.67, 47.87, 180.00, 68.00),
            NcdsRegion("31a", 131.05, 0.00, 154.08, 26.00),
            NcdsRegion("31b", 154.08, 0.00, 173.53, 26.00),
    )

    /**
     * Retired areas and the regions that replace them, for phones that still hold one
     * after the catalog dropped it (the catalog's own replaced_by is used while listed).
     */
    val RETIRED = mapOf("chesapeake" to listOf("ncds_04", "ncds_05"))

    fun byCode(code: String) = ALL.firstOrNull { it.code == code }

    /** catalog.json areas (fields added for NCDS regions are optional, so older catalogs parse too). */
    fun parseCatalog(text: String): List<AreaInfo> {
        val root = MiniJson.parse(text) as Map<*, *>
        return (root["areas"] as List<*>).map { e ->
            val m = e as Map<*, *>
            AreaInfo(
                id = m["id"] as String, name = m["name"] as String, description = (m["description"] as String?) ?: "",
                file = m["file"] as String, size = (m["size"] as Double).toLong(), sha256 = m["sha256"] as String,
                created = m["created"] as String, bbox = (m["bbox"] as List<*>).map { it as Double }.toDoubleArray(),
                ncds = m["ncds"] as String?, deprecated = m["deprecated"] == true,
                replacedBy = (m["replaced_by"] as List<*>?)?.map { it as String } ?: emptyList(),
            )
        }
    }

    /** Each trip point, plus points every [stepM] meters along each leg. */
    fun samples(pts: List<LatLon>, stepM: Double = 2000.0): List<LatLon> {
        if (pts.size < 2) return pts
        val out = ArrayList<LatLon>()
        for ((a, b) in pts.zipWithNext()) {
            val n = max(1, ceil(Planner.distanceM(a, b) / stepM).toInt())
            for (k in 0 until n) out.add(LatLon(a.lat + (b.lat - a.lat) * k / n, a.lon + (b.lon - a.lon) * k / n))
        }
        out.add(pts.last())
        return out
    }

    /**
     * The packs a trip still needs: for every sample of the trip that no installed pack
     * covers, the published pack (not a replaced one) that does. [covered] tells whether
     * the installed packs cover a point. A sample no published pack covers sets [TripNeed.outside].
     */
    fun need(pts: List<LatLon>, catalog: List<AreaInfo>, installedIds: Set<String>, covered: (LatLon) -> Boolean): TripNeed {
        val current = catalog.filter { !it.deprecated }
        val need = LinkedHashMap<String, AreaInfo>()
        var outside = false
        for (p in samples(pts)) {
            if (covered(p)) continue
            val a = current.firstOrNull { it.covers(p) && it.id !in installedIds } ?: current.firstOrNull { it.covers(p) }
            if (a == null) outside = true else if (a.id !in installedIds) need[a.id] = a
        }
        return TripNeed(need.values.toList(), outside)
    }

    /** Published, current pack covering p (for "Where do you boat?"). */
    fun areaAt(p: LatLon, catalog: List<AreaInfo>) = catalog.firstOrNull { !it.deprecated && it.covers(p) }

    /**
     * Installed packs the catalog marks as replaced, with the replacements still to
     * download (empty = all there, the old pack can go). Replacements missing from the
     * catalog keep the old pack.
     */
    fun replacements(catalog: List<AreaInfo>, installedIds: Set<String>): Map<String, List<AreaInfo>> {
        val out = LinkedHashMap<String, List<AreaInfo>>()
        val replaced = catalog.filter { it.deprecated && it.replacedBy.isNotEmpty() }.associate { it.id to it.replacedBy } +
            RETIRED.filterKeys { id -> catalog.none { it.id == id } }
        for ((old, by) in replaced) {
            if (old !in installedIds) continue
            val repl = by.map { id -> catalog.firstOrNull { it.id == id } }
            if (repl.any { it == null }) continue
            out[old] = repl.filterNotNull().filter { it.id !in installedIds }
        }
        return out
    }
}
