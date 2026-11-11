package com.jnyer27.noaaroute

import java.util.Locale
import kotlin.math.min

/**
 * Nautical style and route overlay for the in-app chart.
 * Plain Kotlin: the map activity supplies the tile file and the user's safe depth.
 * Depth colors follow that depth, so the same tiles serve a 3 ft boat and an 8 ft one.
 *
 * A region chart (Ncds_04.mbtiles and, later, the other regions) is one MapLibre
 * source, same layers as the bundled Middle River file. Every downloaded set is drawn
 * at once, each its own source ([style] with a list). [pick] chooses the region under
 * the view, for the download and update offers.
 */
object ChartTiles {
    const val ASSET = "middle_river.mbtiles"
    const val GLYPH_ASSET = "glyphs/NotoSansRegular/0-255.pbf"
    /** Font directory name. Matches [GLYPH_ASSET] and the style's text-font. */
    const val FONT = "NotoSansRegular"

    /** Fallback box, the builder's default, if the tile file has no bounds metadata. */
    const val WEST = -76.48
    const val SOUTH = 39.27
    const val EAST = -76.32
    const val NORTH = 39.36

    /**
     * Where View map opens when there is no route and the camera is not ready yet:
     * the bundled Middle River chart. Whichever catalog chart contains this point is offered.
     */
    val HOME = LatLon((SOUTH + NORTH) / 2, (WEST + EAST) / 2)

    /**
     * One region's in-app chart, from the vector catalog's `tiles` object.
     * The download URL is the vector release base plus [file] (the same place as the OsmAnd maps).
     * [bounds] is west, south, east, north.
     */
    class TileSet(
        val ncds: String, val name: String, val file: String, val size: Long, val sha256: String,
        val bounds: DoubleArray, val minZoom: Int, val maxZoom: Int,
    ) {
        fun contains(p: LatLon) = p.lon in bounds[0]..bounds[2] && p.lat in bounds[1]..bounds[3]
        fun countInside(points: List<LatLon>) = points.count { contains(it) }
        /** Area of the catalog bounds, so a tighter chart wins when two cover the same point. */
        fun span() = (bounds[2] - bounds[0]) * (bounds[3] - bounds[1])
        /** "Region 04" from "Region 04: Upper Chesapeake and Delaware Bay". */
        val shortName get() = name.substringBefore(':').ifBlank { "Region $ncds" }
        fun url(base: String) = base + file
    }

    /**
     * Region charts listed in a vector-maps catalog. Regions with no `tiles` object are skipped.
     * [key] "basemap" reads the OpenStreetMap base map entries instead.
     */
    fun tileSets(catalogJson: String, key: String = "tiles"): List<TileSet> {
        val root = MiniJson.parse(catalogJson) as? Map<*, *> ?: return emptyList()
        val regions = root["regions"] as? List<*> ?: return emptyList()
        val out = ArrayList<TileSet>()
        for (raw in regions) {
            val reg = raw as? Map<*, *> ?: continue
            val tiles = reg[key] as? Map<*, *> ?: continue
            val file = tiles["file"] as? String ?: continue
            val sha = tiles["sha256"] as? String ?: continue
            if (sha.isEmpty()) continue
            val size = (tiles["size"] as? Number)?.toLong() ?: continue
            val box = tiles["bounds"] as? List<*> ?: continue
            if (box.size != 4 || box.any { it !is Number }) continue
            val ncds = reg["ncds"] as? String ?: continue
            val name = reg["name"] as? String ?: "Region $ncds"
            out.add(TileSet(
                ncds, name, file, size, sha,
                DoubleArray(4) { (box[it] as Number).toDouble() },
                (tiles["minzoom"] as? Number)?.toInt() ?: 8,
                (tiles["maxzoom"] as? Number)?.toInt() ?: 15,
            ))
        }
        return out
    }

    /**
     * Points that choose a chart. A settled camera uses its center, so panning selects
     * the region on screen. Before the camera is ready, the route's points are used,
     * and View map with no route uses [HOME].
     */
    fun focus(route: List<LatLon>, view: LatLon?): List<LatLon> = when {
        view != null -> listOf(view)
        route.isNotEmpty() -> route
        else -> listOf(HOME)
    }

    /**
     * Which chart covers [points]. The one that contains the most of them wins.
     * A tie goes to the smaller bounds, then the region code, so the result does not
     * depend on catalog order. One chart is drawn. Every other catalog chart that
     * also covers the view can be downloaded from Regions.
     */
    fun pick(sets: List<TileSet>, points: List<LatLon>): TileSet? {
        if (points.isEmpty()) return null
        return sets.maxWithOrNull(compareBy<TileSet> { it.countInside(points) }.thenBy { -it.span() }.thenBy { it.ncds })
            ?.takeIf { it.countInside(points) > 0 }
    }

    /** "Region 04", or "Regions 04 and 05", or "Regions 03, 04 and 05" for the charts drawn. */
    fun regionsHeading(sets: List<TileSet>): String {
        if (sets.size == 1) return sets[0].shortName
        val codes = sets.map { it.ncds }
        return "Regions " + codes.dropLast(1).joinToString(", ") + " and " + codes.last()
    }

    /** A catalog chart that covers this view and is not downloaded yet. */
    fun offer(catalog: List<TileSet>, downloaded: List<TileSet>, points: List<LatLon>): TileSet? {
        val have = downloaded.map { it.ncds }.toSet()
        return pick(catalog.filter { it.ncds !in have }, points)
    }

    fun depthText(ft: Double): String =
        if (ft.isFinite() && ft % 1.0 == 0.0) ft.toInt().toString() else String.format(Locale.US, "%.2f", ft)

    /** Preference: the chart key under the map is open. Hidden, it is one line. */
    const val KEY_PREF = "chartKeyOpen"

    /** One line when the key is hidden. The navigation warning stays visible. */
    const val KEY_BAR = "Not for navigation. Show key"

    /**
     * Text under the chart. Open, the full key plus a hint that a tap hides it.
     * Hidden, one line: the warning and "Show key".
     */
    fun keyLine(full: String, open: Boolean): String =
        if (open) "$full Tap to hide the key." else KEY_BAR

    /**
     * Colors for the chart screen. Night is the phone's dark mode. Text and
     * panel are chosen as a pair so a light theme forced by the app, or Android's
     * automatic darkening, cannot leave black text on a black control.
     */
    class ChartChrome(val text: Int, val panel: Int, val keyBg: Int) {
        fun readable(): Boolean = contrasts(text, panel) && contrasts(text, keyBg)
    }

    fun chartChrome(night: Boolean): ChartChrome = if (night)
        ChartChrome(0xFFFFFFFF.toInt(), 0xF2222222.toInt(), 0xFF121212.toInt())
    else
        ChartChrome(0xFF000000.toInt(), 0xF2FFFFFF.toInt(), 0xFFFFFFFF.toInt())

    /** True when [text] and [bg] are far enough apart to read. Alpha on [bg] is ignored. */
    fun contrasts(text: Int, bg: Int): Boolean {
        fun y(c: Int): Int {
            val rgb = c and 0xffffff
            val r = (rgb shr 16) and 0xff
            val g = (rgb shr 8) and 0xff
            val b = rgb and 0xff
            return r * 3 + g * 6 + b
        }
        return kotlin.math.abs(y(text) - y(bg)) > 800
    }

    /** Preference: while Follow is on, turn the map with the boat's course. */
    const val COURSE_PREF = "chartCourseUp"

    /** Below this speed a GPS bearing is noise. Course-up keeps the last heading. */
    const val COURSE_SPEED_MPS = 0.5f

    /** Degrees clockwise from north, in 0..360. */
    fun normalizeBearing(deg: Double): Double {
        val d = deg % 360.0
        return if (d < 0) d + 360.0 else d
    }

    /**
     * Map bearing while following. North-up is 0. Course-up uses the GPS bearing
     * when the boat is moving; otherwise it keeps [heldDeg] so a stop does not spin the chart.
     */
    fun mapBearing(courseUp: Boolean, gpsBearingDeg: Float?, speedMps: Float, heldDeg: Double): Double {
        if (!courseUp) return 0.0
        if (gpsBearingDeg != null && gpsBearingDeg.isFinite() && speedMps >= COURSE_SPEED_MPS)
            return normalizeBearing(gpsBearingDeg.toDouble())
        return normalizeBearing(heldDeg)
    }

    /** Compass label for a map bearing. 0 is north. */
    fun compassText(bearingDeg: Double): String {
        val b = normalizeBearing(bearingDeg)
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val i = ((b + 22.5) / 45.0).toInt() % 8
        return String.format(Locale.US, "%s %.0f°", names[i], b)
    }

    /** A scale bar: its [label] and its length on screen in dp. */
    class Scale(val label: String, val dp: Double)

    /**
     * The longest round distance (1, 2 or 5 times a power of ten) that fits in [maxDp],
     * at [metersPerDp] (MapLibre's meters per pixel at the map center's latitude; its
     * pixels are dp). Nautical miles, or feet when a tenth of a mile does not fit.
     */
    fun scaleBar(metersPerDp: Double, maxDp: Double = 110.0): Scale? {
        if (!(metersPerDp > 0) || metersPerDp.isInfinite()) return null
        val most = metersPerDp * maxDp
        val nmM = 1852.0
        val ftM = 0.3048
        fun nice(v: Double): Double {
            var p = Math.pow(10.0, Math.floor(Math.log10(v)))
            for (k in doubleArrayOf(5.0, 2.0, 1.0)) if (k * p <= v) return k * p
            p /= 10
            return 5 * p
        }
        return if (most >= 0.1 * nmM) {
            val nm = nice(most / nmM)
            val text = if (nm >= 1) String.format(Locale.US, "%.0f nm", nm) else String.format(Locale.US, "%.1f nm", nm)
            Scale(text, nm * nmM / metersPerDp)
        } else {
            val ft = maxOf(nice(most / ftM), 1.0)
            Scale(String.format(Locale.US, "%.0f ft", ft), ft * ftM / metersPerDp)
        }
    }

    /** A finger pan ends Follow. The app's own camera moves do not. */
    fun stillFollowing(following: Boolean, userPanned: Boolean) = following && !userPanned

    /**
     * The key's orientation sentence. It says north is up only while that is the mode.
     */
    fun orientSentence(courseUp: Boolean): String =
        if (courseUp) "The map turns with your course while Follow is on. Tap the compass for north up. "
        else "North is up. Follow can turn the map with your course. Tap the compass for north up. "

    /** About how wide the chart on screen is, from its west and east edges. */
    fun spanText(west: Double, east: Double, lat: Double): String {
        val km = (east - west) * Pack.M_PER_DEG * kotlin.math.cos(Math.toRadians(lat)) / 1000.0
        return "Across this chart is about %.0f km. ".format(kotlin.math.abs(km))
    }

    /**
     * The depth sentence of the chart legend. Strong blue is a depth area whose
     * deep limit is under the safe depth, lighter blue may be under it, pale blue
     * is at or over it.
     */
    fun depthLegend(depthFt: Double): String {
        val depth = depthText(depthFt)
        return "Safe operating depth $depth ft: strong blue is charted entirely under it, " +
            "lighter blue may be under your $depth ft, pale blue is $depth ft or more, olive dries. " +
            "A sounding drawn in red is under your $depth ft. "
    }

    /** One GeoJSON feature per leg (flagged 1 when [Gpx.flagged]) plus a point at each route position. */
    fun routeGeoJson(route: Route): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        fun feature(body: String) {
            if (!first) sb.append(',')
            first = false
            sb.append(body)
        }
        val legs = min(route.legs.size, (route.points.size - 1).coerceAtLeast(0))
        for (i in 0 until legs) {
            val flagged = if (Gpx.flagged(route.legs[i])) 1 else 0
            val note = jsonString(Gpx.legText(route, i))
            // For the tide graph on a check leg: shallow water, and the shallowest sounding near it as the report prints it
            val leg = route.legs[i]
            val low = leg.lowM?.let { String.format(java.util.Locale.US, ",\"lowFt\":%.1f", it / Pack.FT) } ?: ""
            val shoal = if (leg.shoalM > 0) 1 else 0
            feature("{\"type\":\"Feature\",\"properties\":{\"kind\":\"leg\",\"leg\":${i + 1},\"flagged\":$flagged,\"shoal\":$shoal$low,\"note\":$note}," +
                "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[${coord(route.points[i])},${coord(route.points[i + 1])}]}}")
            val a = route.points[i]
            val b = route.points[i + 1]
            val mid = LatLon((a.lat + b.lat) / 2, (a.lon + b.lon) / 2)
            feature("{\"type\":\"Feature\",\"properties\":{\"kind\":\"label\",\"leg\":${i + 1}}," +
                "\"geometry\":{\"type\":\"Point\",\"coordinates\":${coord(mid)}}}")
        }
        // The GPX check markers: one where each run of check legs starts, one where it ends.
        for (run in Gpx.checkRuns(route)) {
            if (run.last + 1 >= route.points.size) continue
            val legsText = if (run.first == run.last) "${run.first + 1}" else "${run.first + 1}-${run.last + 1}"
            feature("{\"type\":\"Feature\",\"properties\":{\"kind\":\"checkstart\",\"legs\":${jsonString(legsText)}}," +
                "\"geometry\":{\"type\":\"Point\",\"coordinates\":${coord(route.points[run.first])}}}")
            feature("{\"type\":\"Feature\",\"properties\":{\"kind\":\"checkend\",\"legs\":${jsonString(legsText)}}," +
                "\"geometry\":{\"type\":\"Point\",\"coordinates\":${coord(route.points[run.last + 1])}}}")
        }
        for (p in route.points) {
            feature("{\"type\":\"Feature\",\"properties\":{\"kind\":\"point\"}," +
                "\"geometry\":{\"type\":\"Point\",\"coordinates\":${coord(p)}}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * A tapped leg: its row in the report, whether it is a check, and its sentence; whether it
     * is in shallow water and its shallowest nearby sounding in feet (null when none, or for a
     * route opened from a GPX file).
     */
    class LegPick(val leg: Int, val flagged: Boolean, val note: String, val shoal: Boolean = false, val lowFt: Double? = null)

    /**
     * The leg nearest a tap, or null when the tap is farther than [maxM] from every leg.
     * [geoJson] is [routeGeoJson]. The chart passes a distance of about a fingertip at
     * the current zoom.
     */
    fun legPickAt(geoJson: String, lat: Double, lon: Double, maxM: Double = 300.0): LegPick? {
        val root = MiniJson.parse(geoJson) as? Map<*, *> ?: return null
        val features = root["features"] as? List<*> ?: return null
        val here = LatLon(lat, lon)
        var best = maxM
        var pick: LegPick? = null
        for (f in features) {
            val feat = f as? Map<*, *> ?: continue
            val props = feat["properties"] as? Map<*, *> ?: continue
            if (props["kind"] != "leg") continue
            val coords = (feat["geometry"] as? Map<*, *>)?.get("coordinates") as? List<*> ?: continue
            if (coords.size < 2) continue
            val a = pair(coords[0]) ?: continue
            val b = pair(coords[1]) ?: continue
            val d = metersToSegment(here, a, b)
            if (d <= best) {
                val n = (props["leg"] as? Double)?.toInt() ?: continue
                best = d
                pick = LegPick(n, (props["flagged"] as? Double) == 1.0, props["note"] as? String ?: "",
                    (props["shoal"] as? Double) == 1.0, props["lowFt"] as? Double)
            }
        }
        return pick
    }

    /** The leg under a tap, as "Leg N. <note>". */
    fun legAt(geoJson: String, lat: Double, lon: Double, maxM: Double = 300.0): String? =
        legPickAt(geoJson, lat, lon, maxM)?.let { "Leg ${it.leg}. ${it.note}" }

    /**
     * A leg sentence ("Next: 208 T, 0.77 nm, 451 m where ... (…, under your 2 ft); runs
     * along ...: check") as the popup shows it: the course and distance, then one line
     * per note. Commas inside a note's parentheses stay with that note.
     */
    fun legDetail(note: String): Pair<String, List<String>> {
        val parts = note.removePrefix("Next: ").split("; ").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "" to emptyList()
        val m = Regex("""^(\d{3} T), ([0-9.]+ nm)(?:, (.+))?$""").find(parts[0])
            ?: return "" to parts
        val head = "${m.groupValues[1]}, ${m.groupValues[2]}"
        val lines = listOfNotNull(m.groups[3]?.value) + parts.drop(1)
        return head to lines
    }

    /**
     * Where a new via point goes in [trip] (start, vias, destination): the index that adds
     * the least distance, between 1 and trip.size - 1. Appending would send the boat to
     * the via last, after the other vias, wherever it is on the chart.
     */
    fun viaIndex(trip: List<LatLon>, p: LatLon): Int {
        if (trip.size < 2) return trip.size
        var best = 1
        var bestAdd = Double.MAX_VALUE
        for (k in 1 until trip.size) {
            val add = Planner.distanceM(trip[k - 1], p) + Planner.distanceM(p, trip[k]) - Planner.distanceM(trip[k - 1], trip[k])
            if (add < bestAdd) { bestAdd = add; best = k }
        }
        return best
    }

    private fun pair(v: Any?): LatLon? {
        val c = v as? List<*> ?: return null
        if (c.size < 2) return null
        val lon = c[0] as? Double ?: return null
        val lat = c[1] as? Double ?: return null
        return LatLon(lat, lon)
    }

    /** Meters from [p] to the segment [a]-[b]. A very short leg is just the distance to [a]. */
    private fun metersToSegment(p: LatLon, a: LatLon, b: LatLon): Double {
        if (Planner.distanceM(a, b) < 1.0) return Planner.distanceM(p, a)
        val kx = Pack.M_PER_DEG * kotlin.math.cos(Math.toRadians((a.lat + b.lat) / 2))
        val ky = Pack.M_PER_DEG
        val bx = (b.lon - a.lon) * kx
        val by = (b.lat - a.lat) * ky
        val px = (p.lon - a.lon) * kx
        val py = (p.lat - a.lat) * ky
        val l2 = bx * bx + by * by
        if (l2 <= 0.0) return Planner.distanceM(p, a)
        val t = ((px * bx + py * by) / l2).coerceIn(0.0, 1.0)
        return kotlin.math.hypot(px - t * bx, py - t * by)
    }

    /**
     * Restricted areas from the route packs: the outlines the leg notes come from. The
     * chart tiles carry none, so a leg could say "entry restricted" with nothing drawn.
     * limit 1: entry restricted or prohibited, or a security zone (the legs are checks);
     * limit 0: anchoring, fishing, no-wake and other rules (information). An area at a
     * region seam is in both packs and is kept once. Only areas are drawn.
     */
    fun restrictedGeoJson(notes: List<Note>): String {
        val sb = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
        val seen = HashSet<String>()
        var first = true
        for (n in notes) {
            if (n.kind != "resare") continue
            val text = n.describe() ?: continue
            val geom = when {
                (n.type == "Polygon" || n.type == "MultiPolygon") && n.coords != null ->
                    "{\"type\":\"${n.type}\",\"coordinates\":${nested(n.coords)}}"
                n.coords == null -> {
                    val b = n.bbox
                    val ring = listOf(b[0] to b[1], b[2] to b[1], b[2] to b[3], b[0] to b[3], b[0] to b[1])
                        .joinToString(",") { String.format(Locale.US, "[%.6f,%.6f]", it.first, it.second) }
                    "{\"type\":\"Polygon\",\"coordinates\":[[$ring]]}"
                }
                else -> null
            } ?: continue
            if (!seen.add(text + geom)) continue
            val limit = if (Gpx.navigationLimit(text) || text.contains("check")) 1 else 0
            val label = jsonString(n.name + (n.cfr?.let { " ($it)" } ?: ""))
            if (!first) sb.append(',')
            first = false
            sb.append("{\"type\":\"Feature\",\"properties\":{\"limit\":$limit,\"label\":$label},\"geometry\":$geom}")
        }
        return sb.append("]}").toString()
    }

    /**
     * Overhead cables from the route packs (the chart tiles have none), labeled with the
     * report's sentence. low 1: the clearance is not charted, or under [airFt]; with no
     * air draft every cable is low, as the report treats it as unchecked.
     */
    fun cablesGeoJson(notes: List<Note>, airFt: Double?): String {
        val sb = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
        val seen = HashSet<String>()
        var first = true
        for (n in notes) {
            if (n.kind != "cable") continue
            val type = n.type
            if (n.coords == null || (type != "LineString" && type != "MultiLineString")) continue
            val text = n.describe(airFt) ?: continue
            val geom = "{\"type\":\"$type\",\"coordinates\":${nested(n.coords)}}"
            if (!seen.add(text + geom)) continue
            val clr = n.clr
            val low = if (airFt == null || clr == null || clr < airFt * Pack.FT) 1 else 0
            val label = jsonString(clr?.let { "cable ${Planner.printedFeet(it)} ft" } ?: "cable, clearance not charted")
            if (!first) sb.append(',')
            first = false
            sb.append("{\"type\":\"Feature\",\"properties\":{\"low\":$low,\"label\":$label},\"geometry\":$geom}")
        }
        return sb.append("]}").toString()
    }

    /**
     * What a tap off the route lands on: every restricted area the spot is inside, then
     * every overhead cable within [tolM], as the report words them. Empty when none.
     */
    fun notesAt(notes: List<Note>, lat: Double, lon: Double, tolM: Double, airFt: Double?): List<String> {
        val areas = notes.filter { it.kind == "resare" && it.type != "LineString" && it.type != "Point" && it.near(lat, lon, 0.0) }
        val cables = notes.filter { it.kind == "cable" && it.near(lat, lon, tolM) }
        return (areas + cables).mapNotNull { it.describe(airFt) }.distinct()
    }

    private fun nested(v: Any?): String = when (v) {
        is List<*> -> v.joinToString(",", "[", "]") { nested(it) }
        is Double -> String.format(Locale.US, "%.6f", v)
        is Number -> v.toString()
        else -> "0"
    }

    /**
     * The boat's position, and with [courseDeg] a heading line from it [lengthM] along that
     * course (a rhumb line, so it is straight on the chart).
     */
    fun gpsGeoJson(lat: Double, lon: Double, courseDeg: Double? = null, lengthM: Double = 0.0): String {
        val here = String.format(Locale.US, "[%.6f,%.6f]", lon, lat)
        val point = "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Point\",\"coordinates\":$here}}"
        if (courseDeg == null || !courseDeg.isFinite() || !(lengthM > 0)) return "{\"type\":\"FeatureCollection\",\"features\":[$point]}"
        val end = rhumbEnd(lat, lon, courseDeg, lengthM)
        val there = String.format(Locale.US, "[%.6f,%.6f]", end.lon, end.lat)
        val line = "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[$here,$there]}}"
        return "{\"type\":\"FeatureCollection\",\"features\":[$line,$point]}"
    }

    /** The point [distM] from a start along a constant [courseDeg] (true), on a sphere. */
    fun rhumbEnd(lat: Double, lon: Double, courseDeg: Double, distM: Double): LatLon {
        val r = 6371008.8
        val d = distM / r
        val th = Math.toRadians(courseDeg)
        val p1 = Math.toRadians(lat)
        var p2 = p1 + d * Math.cos(th)
        p2 = p2.coerceIn(Math.toRadians(-85.0), Math.toRadians(85.0))
        val dPsi = Math.log(Math.tan(Math.PI / 4 + p2 / 2) / Math.tan(Math.PI / 4 + p1 / 2))
        val q = if (Math.abs(dPsi) > 1e-12) (p2 - p1) / dPsi else Math.cos(p1)
        val dl = d * Math.sin(th) / q
        var lon2 = lon + Math.toDegrees(dl)
        if (lon2 > 180) lon2 -= 360 else if (lon2 < -180) lon2 += 360
        return LatLon(Math.toDegrees(p2), lon2)
    }

    /** The heading line's course: the GPS bearing while moving, else none (a stopped boat's bearing is noise). */
    fun headingCourse(following: Boolean, gpsBearingDeg: Float?, speedMps: Float): Double? =
        if (following && gpsBearingDeg != null && gpsBearingDeg.isFinite() && speedMps >= COURSE_SPEED_MPS)
            normalizeBearing(gpsBearingDeg.toDouble()) else null

    /**
     * MapLibre style. [mbtilesUrl] is an mbtiles:// URL of the chart file on disk.
     * [glyphsUrl] is a file:// template ending in {fontstack}/{range}.pbf.
     * Water whose deep limit is under the safe depth is the strong blue; a depth area that
     * straddles it (the 0-6 ft band for a 3 ft boat) is the lighter blue; deeper water is pale.
     */
    fun style(depthFt: Double, mbtilesUrl: String, glyphsUrl: String): String =
        style(depthFt, listOf(mbtilesUrl), glyphsUrl)

    /**
     * One style for several region charts at once: each file is its own source ("enc",
     * "enc1", ...), and each chart layer is repeated per source, all copies of a layer
     * together, so depth shading stays under contours and marks across a seam. The
     * first source keeps the plain layer ids. Drawing only the region under the screen's
     * center left the next region blank while panning across 38.23 N (04 and 05).
     *
     * [maxZooms] (one per region file, its catalog max zoom) turns on the enlarged
     * copies. A region file stores each chart band up to its own zoom (11, 13, 15, 16;
     * each feature's `z`), and MapLibre draws a missing tile inside the file's zoom
     * range as blank. So each file is also opened once per band zoom below its max, as
     * a source stopping at that zoom ("enc-z13", "enc1-z11"), and past it MapLibre
     * enlarges that zoom's tiles. Those layers draw only features with that `z`, from
     * the next zoom up; finer charts are already cut out of coarser ones, so nothing
     * is drawn twice. Without them, water covered only by an approach chart went blank
     * at zoom 14. A file without `z` (built before) draws as it did.
     */
    fun style(
        depthFt: Double, mbtilesUrls: List<String>, glyphsUrl: String, baseUrls: List<String> = emptyList(),
        maxZooms: List<Int>? = null,
    ): String {
        require(mbtilesUrls.isNotEmpty())
        require(maxZooms == null || maxZooms.size == mbtilesUrls.size)
        val depth = depthText(depthFt)
        fun src(k: Int) = if (k == 0) "enc" else "enc$k"
        fun caps(k: Int) = if (maxZooms == null) emptyList() else BAND_ZOOMS.filter { it < maxZooms[k] }
        val sources = (mbtilesUrls.indices.flatMap { k ->
            listOf("    \"${src(k)}\": {\"type\": \"vector\", \"url\": ${jsonString(mbtilesUrls[k])}}") +
                caps(k).map { z ->
                    // The same file as tiles, so the source can stop at z. MapLibre reads the
                    // tile from x/y/z and drops the query (MBTilesFileSource).
                    "    \"${src(k)}-z$z\": {\"type\": \"vector\", \"tiles\": [" +
                        jsonString(mbtilesUrls[k] + "?file={x}/{y}/{z}.pbf") + "], \"minzoom\": 8, \"maxzoom\": $z}"
                }
        } + baseUrls.indices.map { k ->
            "    \"osm$k\": {\"type\": \"vector\", \"url\": ${jsonString(baseUrls[k])}, " +
                "\"attribution\": \"\u00a9 OpenStreetMap contributors\"}"
        }).joinToString(",\n")
        val text = STYLE.trim()
        val open = text.indexOf("  \"layers\": [\n") + "  \"layers\": [\n".length
        val close = text.lastIndexOf("\n  ]")
        // One layer per block: each starts on a line beginning with {"id":
        val blocks = ArrayList<String>()
        for (line in text.substring(open, close).lines()) {
            if (line.trimStart().startsWith("{\"id\":")) blocks.add(line) else blocks[blocks.size - 1] += "\n" + line
        }
        val layers = ArrayList<String>()
        // OpenStreetMap base map layers, once per region file, all copies of a layer together
        val base = ArrayList<String>()
        if (baseUrls.isNotEmpty()) for (b in BASE_LAYERS.trim().split(Regex(""",\n(?=\s*\{"id":)"""))) {
            val body = b.trim().removeSuffix(",")
            val id = Regex("""\{"id": "([^"]+)"""").find(body)!!.groupValues[1]
            for (k in baseUrls.indices) base.add("    " + body
                .replaceFirst("{\"id\": \"$id\"", "{\"id\": \"$id~$k\"")
                .replace("\"source\": \"osm\"", "\"source\": \"osm$k\""))
        }
        for (b in blocks) {
            val body = b.trimEnd().removeSuffix(",")
            if (!body.contains("\"source\": \"enc\"")) { layers.add(body); continue }
            val id = Regex("""\{"id": "([^"]+)"""").find(body)!!.groupValues[1]
            val minZoom = Regex("""\"minzoom\": (\d+)""").find(body)?.groupValues?.get(1)?.toInt() ?: 0
            for (k in mbtilesUrls.indices) {
                val mine = if (k == 0) id else "$id~$k"
                val copy = if (k == 0) body else body
                    .replaceFirst("{\"id\": \"$id\"", "{\"id\": \"$mine\"")
                    .replace("\"source\": \"enc\"", "\"source\": \"${src(k)}\"")
                // The enlarged coarser bands first, under the file's own tiles
                for (z in caps(k)) {
                    val from = maxOf(minZoom, z + 1)
                    var lo = copy
                        .replaceFirst("{\"id\": \"$mine\"", "{\"id\": \"$mine@$z\"")
                        .replace("\"source\": \"${src(k)}\"", "\"source\": \"${src(k)}-z$z\", \"filter\": [\"==\", [\"get\", \"z\"], $z]")
                    lo = if (lo.contains("\"minzoom\": ")) lo.replace(Regex("""\"minzoom\": \d+"""), "\"minzoom\": $from")
                        else lo.replaceFirst("\"source-layer\": ", "\"minzoom\": $from, \"source-layer\": ")
                    layers.add(lo)
                }
                layers.add(copy)
            }
        }
        // Above the chart's land fill, under its contours, marks, soundings and the route
        if (base.isNotEmpty()) {
            val at = layers.indexOfLast { it.contains("\"source-layer\": \"lndare\"") } + 1
            layers.addAll(at, base)
        }
        return (text.substring(0, open) + layers.joinToString(",\n") + text.substring(close))
            .replace("    \"enc\": {\"type\": \"vector\", \"url\": __MBTILES__}", sources)
            .replace("__DEPTH__", depth)
            .replace("__GLYPHS__", jsonString(glyphsUrl))
    }

    /** Band zooms below a region file's max that get an enlarged copy (tools/build_enc_tiles.py BAND_MAXZOOM). */
    val BAND_ZOOMS = listOf(11, 13, 15)

    private fun coord(p: LatLon) = String.format(Locale.US, "[%.6f,%.6f]", p.lon, p.lat)

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> append(c)
        }
        append('"')
    }

    private const val DEPTH_FILL =
        """["case",["all",["has","d2"],["<=",["get","d2"],0]],"#c4b48a",["all",["has","d2"],["<",["get","d2"],__DEPTH__]],"#5aa0d0",["all",["has","d1"],["<",["get","d1"],__DEPTH__]],"#a9cfe4","#eef6fb"]"""

    private const val MARK_COLOR =
        """["match",["get","side"],"port","#1b8a34","pref_stbd","#1b8a34","starboard","#d01818","pref_port","#d01818","#555555"]"""

    /** S-57 light COLOUR, written on the tile as col (G, R, W, Y, ...). */
    private const val LIGHT_COLOR =
        """["match",["get","col"],"G","#1b8a34","R","#d01818","Y","#e6b800","W","#f4f4f4","Bu","#1565c0","Or","#e67e22","#5c6b73"]"""

    private const val LIGHT_TEXT =
        """["match",["get","col"],"G","#146b28","R","#a81414","Y","#8a6a00","W","#222222","Bu","#0d47a1","Or","#a85a12","#222222"]"""

    /**
     * OpenStreetMap base map (Protomaps basemap layers v4, trimmed by tools/build_basemap.sh
     * to roads, places, boundaries and boating pois, plus `counties`: Census county and state lines and
     * their name labels, tools/county_labels.py). No water or land fill: the chart's own.
     */
    private const val BASE_LAYERS = """
    {"id": "osm-boundary", "type": "line", "source": "osm", "source-layer": "boundaries",
      "filter": ["==", ["get", "kind"], "country"],
      "paint": {"line-color": "#7e57c2", "line-opacity": 0.7, "line-dasharray": [3, 2], "line-width": 1.6}},
    {"id": "osm-county-line", "type": "line", "source": "osm", "source-layer": "counties",
      "filter": ["==", ["geometry-type"], "LineString"],
      "paint": {"line-color": "#7e57c2", "line-opacity": 0.7, "line-dasharray": [3, 2],
        "line-width": ["match", ["get", "state"], 1, 1.6, 1.0]}},
    {"id": "osm-county-label", "type": "symbol", "source": "osm", "source-layer": "counties", "minzoom": 11,
      "filter": ["all", ["==", ["geometry-type"], "Point"], ["==", ["get", "rank"], 0]],
      "layout": {"text-field": ["concat", ["get", "a"], "\n", ["get", "b"]], "text-font": ["$FONT"],
        "text-size": 11, "text-line-height": 1.8, "text-max-width": 30, "text-rotate": ["get", "rot"],
        "text-rotation-alignment": "map", "text-pitch-alignment": "map", "text-padding": 20,
        "text-allow-overlap": true},
      "paint": {"text-color": "#5e35b1", "text-halo-color": "#ffffff", "text-halo-width": 1.5}},
    {"id": "osm-county-label-1", "type": "symbol", "source": "osm", "source-layer": "counties", "minzoom": 12.3,
      "filter": ["all", ["==", ["geometry-type"], "Point"], ["==", ["get", "rank"], 1]],
      "layout": {"text-field": ["concat", ["get", "a"], "\n", ["get", "b"]], "text-font": ["$FONT"],
        "text-size": 11, "text-line-height": 1.8, "text-max-width": 30, "text-rotate": ["get", "rot"],
        "text-rotation-alignment": "map", "text-pitch-alignment": "map", "text-padding": 20,
        "text-allow-overlap": true},
      "paint": {"text-color": "#5e35b1", "text-halo-color": "#ffffff", "text-halo-width": 1.5}},
    {"id": "osm-county-label-2", "type": "symbol", "source": "osm", "source-layer": "counties", "minzoom": 13.3,
      "filter": ["all", ["==", ["geometry-type"], "Point"], ["==", ["get", "rank"], 2]],
      "layout": {"text-field": ["concat", ["get", "a"], "\n", ["get", "b"]], "text-font": ["$FONT"],
        "text-size": 11, "text-line-height": 1.8, "text-max-width": 30, "text-rotate": ["get", "rot"],
        "text-rotation-alignment": "map", "text-pitch-alignment": "map", "text-padding": 20,
        "text-allow-overlap": true},
      "paint": {"text-color": "#5e35b1", "text-halo-color": "#ffffff", "text-halo-width": 1.5}},
    {"id": "osm-county-label-3", "type": "symbol", "source": "osm", "source-layer": "counties", "minzoom": 14.3,
      "filter": ["all", ["==", ["geometry-type"], "Point"], ["==", ["get", "rank"], 3]],
      "layout": {"text-field": ["concat", ["get", "a"], "\n", ["get", "b"]], "text-font": ["$FONT"],
        "text-size": 11, "text-line-height": 1.8, "text-max-width": 30, "text-rotate": ["get", "rot"],
        "text-rotation-alignment": "map", "text-pitch-alignment": "map", "text-padding": 20,
        "text-allow-overlap": true},
      "paint": {"text-color": "#5e35b1", "text-halo-color": "#ffffff", "text-halo-width": 1.5}},
    {"id": "osm-county-label-4", "type": "symbol", "source": "osm", "source-layer": "counties", "minzoom": 15.3,
      "filter": ["all", ["==", ["geometry-type"], "Point"], ["==", ["get", "rank"], 4]],
      "layout": {"text-field": ["concat", ["get", "a"], "\n", ["get", "b"]], "text-font": ["$FONT"],
        "text-size": 11, "text-line-height": 1.8, "text-max-width": 30, "text-rotate": ["get", "rot"],
        "text-rotation-alignment": "map", "text-pitch-alignment": "map", "text-padding": 20,
        "text-allow-overlap": true},
      "paint": {"text-color": "#5e35b1", "text-halo-color": "#ffffff", "text-halo-width": 1.5}},
    {"id": "osm-county-label-5", "type": "symbol", "source": "osm", "source-layer": "counties", "minzoom": 16.3,
      "filter": ["all", ["==", ["geometry-type"], "Point"], ["==", ["get", "rank"], 5]],
      "layout": {"text-field": ["concat", ["get", "a"], "\n", ["get", "b"]], "text-font": ["$FONT"],
        "text-size": 11, "text-line-height": 1.8, "text-max-width": 30, "text-rotate": ["get", "rot"],
        "text-rotation-alignment": "map", "text-pitch-alignment": "map", "text-padding": 20,
        "text-allow-overlap": true},
      "paint": {"text-color": "#5e35b1", "text-halo-color": "#ffffff", "text-halo-width": 1.5}},
    {"id": "osm-county-label-6", "type": "symbol", "source": "osm", "source-layer": "counties", "minzoom": 17.3,
      "filter": ["all", ["==", ["geometry-type"], "Point"], ["==", ["get", "rank"], 6]],
      "layout": {"text-field": ["concat", ["get", "a"], "\n", ["get", "b"]], "text-font": ["$FONT"],
        "text-size": 11, "text-line-height": 1.8, "text-max-width": 30, "text-rotate": ["get", "rot"],
        "text-rotation-alignment": "map", "text-pitch-alignment": "map", "text-padding": 20,
        "text-allow-overlap": true},
      "paint": {"text-color": "#5e35b1", "text-halo-color": "#ffffff", "text-halo-width": 1.5}},
    {"id": "osm-rail", "type": "line", "source": "osm", "source-layer": "roads", "minzoom": 11,
      "filter": ["==", ["get", "kind"], "rail"],
      "paint": {"line-color": "#8d8d8d", "line-width": 1, "line-dasharray": [3, 2]}},
    {"id": "osm-ferry", "type": "line", "source": "osm", "source-layer": "roads", "minzoom": 10,
      "filter": ["==", ["get", "kind"], "ferry"],
      "paint": {"line-color": "#3f6fb5", "line-width": 1.2, "line-dasharray": [4, 2]}},
    {"id": "osm-minor-casing", "type": "line", "source": "osm", "source-layer": "roads", "minzoom": 13,
      "filter": ["==", ["get", "kind"], "minor_road"],
      "paint": {"line-color": "#a8987a", "line-width": ["interpolate", ["linear"], ["zoom"], 13, 2.0, 16, 6]}},
    {"id": "osm-minor", "type": "line", "source": "osm", "source-layer": "roads", "minzoom": 12,
      "filter": ["==", ["get", "kind"], "minor_road"],
      "paint": {"line-color": "#fdfcf7", "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.6, 16, 4]}},
    {"id": "osm-major", "type": "line", "source": "osm", "source-layer": "roads", "minzoom": 9,
      "filter": ["==", ["get", "kind"], "major_road"],
      "paint": {"line-color": "#f2cf7e", "line-width": ["interpolate", ["linear"], ["zoom"], 9, 0.7, 16, 5]}},
    {"id": "osm-highway", "type": "line", "source": "osm", "source-layer": "roads",
      "filter": ["==", ["get", "kind"], "highway"],
      "paint": {"line-color": "#e0913f", "line-width": ["interpolate", ["linear"], ["zoom"], 8, 1, 16, 6]}},
    {"id": "osm-road-label", "type": "symbol", "source": "osm", "source-layer": "roads", "minzoom": 13,
      "filter": ["match", ["get", "kind"], ["highway", "major_road", "minor_road"], true, false],
      "layout": {"symbol-placement": "line", "text-field": ["coalesce", ["get", "name"], ["get", "ref"], ""],
        "text-font": ["$FONT"], "text-size": 10},
      "paint": {"text-color": "#5d4a2a", "text-halo-color": "#ffffff", "text-halo-width": 1.2}},
    {"id": "osm-poi", "type": "symbol", "source": "osm", "source-layer": "pois", "minzoom": 12,
      "layout": {"text-field": ["coalesce", ["get", "name"], ""], "text-font": ["$FONT"], "text-size": 11},
      "paint": {"text-color": "#0d47a1", "text-halo-color": "#ffffff", "text-halo-width": 1.5}},
    {"id": "osm-place-minor", "type": "symbol", "source": "osm", "source-layer": "places", "minzoom": 12,
      "filter": ["match", ["get", "kind_detail"], ["village", "hamlet", "suburb", "neighbourhood", "quarter"], true, false],
      "layout": {"text-field": ["coalesce", ["get", "name"], ""], "text-font": ["$FONT"], "text-size": 11},
      "paint": {"text-color": "#4e342e", "text-halo-color": "#ffffff", "text-halo-width": 1.5}},
    {"id": "osm-place", "type": "symbol", "source": "osm", "source-layer": "places",
      "filter": ["match", ["get", "kind_detail"], ["city", "town"], true, false],
      "layout": {"text-field": ["coalesce", ["get", "name"], ""], "text-font": ["$FONT"],
        "text-size": ["match", ["get", "kind_detail"], "city", 15, 13]},
      "paint": {"text-color": "#3e2723", "text-halo-color": "#ffffff", "text-halo-width": 1.8}}
"""

    private const val STYLE = """
{
  "version": 8,
  "name": "Middle River ENC",
  "glyphs": __GLYPHS__,
  "sources": {
    "enc": {"type": "vector", "url": __MBTILES__},
    "route": {"type": "geojson", "data": {"type": "FeatureCollection", "features": []}},
    "restricted": {"type": "geojson", "data": {"type": "FeatureCollection", "features": []}},
    "cables": {"type": "geojson", "data": {"type": "FeatureCollection", "features": []}},
    "search": {"type": "geojson", "data": {"type": "FeatureCollection", "features": []}},
    "tides": {"type": "geojson", "data": {"type": "FeatureCollection", "features": []}},
    "gps": {"type": "geojson", "data": {"type": "FeatureCollection", "features": []}}
  },
  "layers": [
    {"id": "background", "type": "background", "paint": {"background-color": "#d5e6f2"}},
    {"id": "depare", "type": "fill", "source": "enc", "source-layer": "depare",
      "paint": {"fill-color": $DEPTH_FILL}},
    {"id": "drgare", "type": "fill", "source": "enc", "source-layer": "drgare",
      "paint": {"fill-color": $DEPTH_FILL, "fill-outline-color": "#1f6390"}},
    {"id": "lndare", "type": "fill", "source": "enc", "source-layer": "lndare",
      "paint": {"fill-color": "#e6d7b0", "fill-outline-color": "#b5a37a"}},
    {"id": "depcnt", "type": "line", "source": "enc", "source-layer": "depcnt", "minzoom": 11,
      "paint": {"line-color": "#4d7494", "line-width": 1}},
    {"id": "depcnt-label", "type": "symbol", "source": "enc", "source-layer": "depcnt", "minzoom": 12,
      "layout": {"symbol-placement": "line", "text-field": ["to-string", ["get", "ft"]], "text-font": ["$FONT"], "text-size": 11},
      "paint": {"text-color": "#2c5270", "text-halo-color": "#f4f8fb", "text-halo-width": 1.5}},
    {"id": "coalne", "type": "line", "source": "enc", "source-layer": "coalne", "minzoom": 10,
      "paint": {"line-color": "#6b5a3e", "line-width": 1}},
    {"id": "fairwy", "type": "line", "source": "enc", "source-layer": "fairwy", "minzoom": 12,
      "paint": {"line-color": "#c43b7a", "line-width": 1.5, "line-dasharray": [2, 1.5]}},
    {"id": "slcons-area", "type": "fill", "source": "enc", "source-layer": "slcons_area", "minzoom": 13,
      "paint": {"fill-color": "#8a6a3b"}},
    {"id": "slcons", "type": "line", "source": "enc", "source-layer": "slcons", "minzoom": 13,
      "paint": {"line-color": "#8a6a3b", "line-width": 1.5}},
    {"id": "bridge-area", "type": "fill", "source": "enc", "source-layer": "bridge_area", "minzoom": 12,
      "paint": {"fill-color": "#8e8e8e"}},
    {"id": "bridge", "type": "line", "source": "enc", "source-layer": "bridge", "minzoom": 12,
      "paint": {"line-color": "#4a4a4a", "line-width": 2}},
    {"id": "bridge-label", "type": "symbol", "source": "enc", "source-layer": "bridge_area", "minzoom": 13,
      "layout": {"text-field": ["get", "label"], "text-font": ["$FONT"], "text-size": 11},
      "paint": {"text-color": "#222222", "text-halo-color": "#ffffff", "text-halo-width": 1.5}},
    {"id": "obstrn-area", "type": "fill", "source": "enc", "source-layer": "obstrn_area", "minzoom": 13,
      "paint": {"fill-color": "#d9a441", "fill-opacity": 0.45, "fill-outline-color": "#8a5a10"}},
    {"id": "obstrn-line", "type": "line", "source": "enc", "source-layer": "obstrn_line", "minzoom": 14,
      "paint": {"line-color": "#8a5a10", "line-width": 1.5, "line-dasharray": [1, 1]}},
    {"id": "soundg", "type": "symbol", "source": "enc", "source-layer": "soundg", "minzoom": 14,
      "layout": {"text-field": ["to-string", ["get", "ft"]], "text-font": ["$FONT"], "text-size": 12, "text-padding": 2},
      "paint": {"text-color": ["case", ["<", ["get", "ft"], __DEPTH__], "#9b1c1c", "#1a1a1a"],
        "text-halo-color": "#f7fbfe", "text-halo-width": 1.2}},
    {"id": "wreck", "type": "symbol", "source": "enc", "source-layer": "wreck", "minzoom": 14,
      "layout": {"text-field": ["get", "label"], "text-font": ["$FONT"], "text-size": 11, "text-allow-overlap": true},
      "paint": {"text-color": "#6b3a00", "text-halo-color": "#ffffff", "text-halo-width": 1}},
    {"id": "wreck-area", "type": "symbol", "source": "enc", "source-layer": "wreck_area", "minzoom": 14,
      "layout": {"text-field": ["get", "label"], "text-font": ["$FONT"], "text-size": 11},
      "paint": {"text-color": "#6b3a00", "text-halo-color": "#ffffff", "text-halo-width": 1}},
    {"id": "rock", "type": "symbol", "source": "enc", "source-layer": "rock", "minzoom": 14,
      "layout": {"text-field": ["get", "label"], "text-font": ["$FONT"], "text-size": 11, "text-allow-overlap": true},
      "paint": {"text-color": "#6b3a00", "text-halo-color": "#ffffff", "text-halo-width": 1}},
    {"id": "rock-area", "type": "symbol", "source": "enc", "source-layer": "rock_area", "minzoom": 14,
      "layout": {"text-field": ["get", "label"], "text-font": ["$FONT"], "text-size": 11},
      "paint": {"text-color": "#6b3a00", "text-halo-color": "#ffffff", "text-halo-width": 1}},
    {"id": "obstrn", "type": "symbol", "source": "enc", "source-layer": "obstrn", "minzoom": 14,
      "layout": {"text-field": ["get", "label"], "text-font": ["$FONT"], "text-size": 11},
      "paint": {"text-color": "#6b3a00", "text-halo-color": "#ffffff", "text-halo-width": 1}},
    {"id": "beacon", "type": "circle", "source": "enc", "source-layer": "beacon", "minzoom": 11,
      "paint": {"circle-radius": ["interpolate", ["linear"], ["zoom"], 11, 3.5, 13, 7], "circle-color": $MARK_COLOR,
        "circle-stroke-color": "#222222", "circle-stroke-width": ["interpolate", ["linear"], ["zoom"], 11, 1.5, 13, 2.5]}},
    {"id": "buoy", "type": "circle", "source": "enc", "source-layer": "buoy", "minzoom": 11,
      "paint": {"circle-radius": ["interpolate", ["linear"], ["zoom"], 11, 3.5, 13, 7], "circle-color": $MARK_COLOR,
        "circle-stroke-color": "#ffffff", "circle-stroke-width": ["interpolate", ["linear"], ["zoom"], 11, 1, 13, 1.5]}},
    {"id": "buoy-name", "type": "symbol", "source": "enc", "source-layer": "buoy", "minzoom": 15,
      "layout": {"text-field": ["get", "name"], "text-font": ["$FONT"], "text-size": 10, "text-offset": [0, 1.4]},
      "paint": {"text-color": "#222222", "text-halo-color": "#ffffff", "text-halo-width": 1}},
    {"id": "lights", "type": "circle", "source": "enc", "source-layer": "lights", "minzoom": 11,
      "paint": {"circle-radius": ["interpolate", ["linear"], ["zoom"], 11, 2, 13, 3.5], "circle-color": $LIGHT_COLOR, "circle-opacity": 1,
        "circle-translate": ["interpolate", ["linear"], ["zoom"], 11, ["literal", [0, -6]], 13, ["literal", [0, -12]]],
        "circle-translate-anchor": "viewport",
        "circle-stroke-color": ["match", ["get", "col"], "W", "#333333", "Y", "#333333", "#ffffff"],
        "circle-stroke-width": 1.5}},
    {"id": "lights-label", "type": "symbol", "source": "enc", "source-layer": "lights", "minzoom": 14,
      "layout": {"text-field": ["get", "label"], "text-font": ["$FONT"], "text-size": 10,
        "text-offset": [0.9, 0.15], "text-anchor": "left"},
      "paint": {"text-color": $LIGHT_TEXT, "text-halo-color": "#ffffff", "text-halo-width": 1}},
    {"id": "restricted-info", "type": "line", "source": "restricted", "minzoom": 9,
      "filter": ["==", ["get", "limit"], 0],
      "paint": {"line-color": "#ab47bc", "line-width": 1.2, "line-dasharray": [2, 2]}},
    {"id": "restricted-limit", "type": "line", "source": "restricted",
      "filter": ["==", ["get", "limit"], 1],
      "paint": {"line-color": "#7b1fa2", "line-width": 2.5, "line-dasharray": [3, 1.5]}},
    {"id": "restricted-label", "type": "symbol", "source": "restricted", "minzoom": 11,
      "layout": {"symbol-placement": "line", "text-field": ["get", "label"], "text-font": ["$FONT"],
        "text-size": 11, "symbol-spacing": 400},
      "paint": {"text-color": "#6a1b9a", "text-halo-color": "#ffffff", "text-halo-width": 1.5}},
    {"id": "cable", "type": "line", "source": "cables", "minzoom": 10,
      "paint": {"line-color": ["case", ["==", ["get", "low"], 1], "#c62828", "#002f6c"],
        "line-width": 2, "line-dasharray": [4, 1.5]}},
    {"id": "cable-label", "type": "symbol", "source": "cables", "minzoom": 13,
      "layout": {"symbol-placement": "line-center", "text-field": ["get", "label"], "text-font": ["$FONT"], "text-size": 11},
      "paint": {"text-color": ["case", ["==", ["get", "low"], 1], "#a31515", "#002f6c"],
        "text-halo-color": "#ffffff", "text-halo-width": 1.5}},
    {"id": "route-pick", "type": "line", "source": "route",
      "filter": ["all", ["==", ["get", "kind"], "leg"], ["==", ["get", "leg"], -1]],
      "paint": {"line-color": "#ffd600", "line-width": 14, "line-opacity": 0.85}},
    {"id": "route-casing", "type": "line", "source": "route",
      "filter": ["==", ["get", "kind"], "leg"],
      "paint": {"line-color": "#ffffff", "line-width": 7}},
    {"id": "route-ok", "type": "line", "source": "route",
      "filter": ["all", ["==", ["get", "kind"], "leg"], ["==", ["get", "flagged"], 0]],
      "paint": {"line-color": "#1565c0", "line-width": 4}},
    {"id": "route-check", "type": "line", "source": "route",
      "filter": ["all", ["==", ["get", "kind"], "leg"], ["==", ["get", "flagged"], 1]],
      "paint": {"line-color": "#e65100", "line-width": 5, "line-dasharray": [1.4, 1]}},
    {"id": "route-point", "type": "circle", "source": "route",
      "filter": ["==", ["get", "kind"], "point"],
      "paint": {"circle-radius": 4, "circle-color": "#1565c0", "circle-stroke-color": "#ffffff", "circle-stroke-width": 1.5}},
    {"id": "route-label", "type": "symbol", "source": "route",
      "filter": ["==", ["get", "kind"], "label"],
      "layout": {"text-field": ["to-string", ["get", "leg"]], "text-font": ["$FONT"], "text-size": 13,
        "text-allow-overlap": true, "text-offset": [0, -0.9]},
      "paint": {"text-color": "#102027", "text-halo-color": "#ffffff", "text-halo-width": 1.5}},
    {"id": "check-end", "type": "circle", "source": "route",
      "filter": ["==", ["get", "kind"], "checkend"],
      "paint": {"circle-radius": 8, "circle-color": "#2e7d32", "circle-stroke-color": "#ffffff", "circle-stroke-width": 2}},
    {"id": "check-start", "type": "circle", "source": "route",
      "filter": ["==", ["get", "kind"], "checkstart"],
      "paint": {"circle-radius": 9, "circle-color": "#e65100", "circle-stroke-color": "#ffffff", "circle-stroke-width": 2}},
    {"id": "check-mark", "type": "symbol", "source": "route",
      "filter": ["==", ["get", "kind"], "checkstart"],
      "layout": {"text-field": "!", "text-font": ["$FONT"],
        "text-size": 13, "text-allow-overlap": true, "text-ignore-placement": true},
      "paint": {"text-color": "#ffffff"}},
    {"id": "tide-station", "type": "circle", "source": "tides", "minzoom": 9,
      "paint": {"circle-radius": ["interpolate", ["linear"], ["zoom"], 9, 4, 13, 7],
        "circle-color": ["case", ["get", "saved"], "#00838f", "#ffffff"],
        "circle-stroke-color": ["case", ["get", "saved"], "#ffffff", "#00838f"], "circle-stroke-width": 2}},
    {"id": "tide-label", "type": "symbol", "source": "tides", "minzoom": 11,
      "layout": {"text-field": ["get", "name"], "text-font": ["$FONT"], "text-size": 11, "text-offset": [0, 1.1],
        "text-anchor": "top", "text-optional": true},
      "paint": {"text-color": "#006064", "text-halo-color": "#ffffff", "text-halo-width": 1.5}},
    {"id": "search-pin", "type": "circle", "source": "search",
      "paint": {"circle-radius": 9, "circle-color": "rgba(0,0,0,0)", "circle-stroke-color": "#d500f9", "circle-stroke-width": 3}},
    {"id": "search-label", "type": "symbol", "source": "search",
      "layout": {"text-field": ["get", "label"], "text-font": ["$FONT"], "text-size": 13, "text-offset": [0, 1.5],
        "text-anchor": "top", "text-allow-overlap": true},
      "paint": {"text-color": "#6a1b9a", "text-halo-color": "#ffffff", "text-halo-width": 2}},
    {"id": "gps-heading", "type": "line", "source": "gps", "filter": ["==", ["geometry-type"], "LineString"],
      "layout": {"line-cap": "round"},
      "paint": {"line-color": "#1a73e8", "line-width": 2.5, "line-opacity": 0.85}},
    {"id": "gps-halo", "type": "circle", "source": "gps", "filter": ["==", ["geometry-type"], "Point"],
      "paint": {"circle-radius": 12, "circle-color": "#1a73e8", "circle-opacity": 0.25}},
    {"id": "gps", "type": "circle", "source": "gps", "filter": ["==", ["geometry-type"], "Point"],
      "paint": {"circle-radius": 6, "circle-color": "#1a73e8", "circle-stroke-color": "#ffffff", "circle-stroke-width": 2}}
  ]
}
"""
}
