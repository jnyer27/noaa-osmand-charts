package com.jnyer27.noaaroute

import java.util.Locale
import kotlin.math.min

/**
 * Nautical style and route overlay for the in-app chart.
 * Plain Kotlin: the map activity supplies the tile file and the user's safe depth.
 * Depth colors follow that depth, so the same tiles serve a 3 ft boat and an 8 ft one.
 *
 * A region chart (Ncds_04.mbtiles and, later, the other regions) is one MapLibre
 * source, same layers as the bundled Middle River file. [pick] chooses which
 * downloaded set to draw. Several sets can be returned as separate sources once
 * more than one region is on screen; today the choice is a single set.
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

    /** Region charts listed in a vector-maps catalog. Regions with no `tiles` object are skipped. */
    fun tileSets(catalogJson: String): List<TileSet> {
        val root = MiniJson.parse(catalogJson) as? Map<*, *> ?: return emptyList()
        val regions = root["regions"] as? List<*> ?: return emptyList()
        val out = ArrayList<TileSet>()
        for (raw in regions) {
            val reg = raw as? Map<*, *> ?: continue
            val tiles = reg["tiles"] as? Map<*, *> ?: continue
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

    /** A catalog chart that covers this view and is not downloaded yet. */
    fun offer(catalog: List<TileSet>, downloaded: List<TileSet>, points: List<LatLon>): TileSet? {
        val have = downloaded.map { it.ncds }.toSet()
        return pick(catalog.filter { it.ncds !in have }, points)
    }

    fun depthText(ft: Double): String =
        if (ft.isFinite() && ft % 1.0 == 0.0) ft.toInt().toString() else String.format(Locale.US, "%.2f", ft)

    /**
     * The depth sentence of the chart legend. Strong blue is a depth area whose
     * deep limit is under the safe depth, lighter blue is one whose range straddles
     * it, pale blue is at or over it.
     */
    fun depthLegend(depthFt: Double): String {
        val depth = depthText(depthFt)
        return "Safe operating depth $depth ft: strong blue is charted entirely under it, " +
            "lighter blue is a range that straddles it, pale blue is at or over it, olive dries. "
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
            feature("{\"type\":\"Feature\",\"properties\":{\"kind\":\"leg\",\"flagged\":$flagged}," +
                "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[${coord(route.points[i])},${coord(route.points[i + 1])}]}}")
        }
        for (p in route.points) {
            feature("{\"type\":\"Feature\",\"properties\":{\"kind\":\"point\"}," +
                "\"geometry\":{\"type\":\"Point\",\"coordinates\":${coord(p)}}}")
        }
        sb.append("]}")
        return sb.toString()
    }

    fun gpsGeoJson(lat: Double, lon: Double): String =
        "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{}," +
            "\"geometry\":{\"type\":\"Point\",\"coordinates\":${String.format(Locale.US, "[%.6f,%.6f]", lon, lat)}}}]} "

            .trim()

    /**
     * MapLibre style. [mbtilesUrl] is an mbtiles:// URL of the chart file on disk.
     * [glyphsUrl] is a file:// template ending in {fontstack}/{range}.pbf.
     * Water whose deep limit is under the safe depth is the strong blue; a depth area that
     * straddles it (the 0-6 ft band for a 3 ft boat) is the lighter blue; deeper water is pale.
     */
    fun style(depthFt: Double, mbtilesUrl: String, glyphsUrl: String): String {
        val depth = depthText(depthFt)
        return STYLE.trim().replace("__DEPTH__", depth)
            .replace("__MBTILES__", jsonString(mbtilesUrl))
            .replace("__GLYPHS__", jsonString(glyphsUrl))
    }

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

    private const val STYLE = """
{
  "version": 8,
  "name": "Middle River ENC",
  "glyphs": __GLYPHS__,
  "sources": {
    "enc": {"type": "vector", "url": __MBTILES__},
    "route": {"type": "geojson", "data": {"type": "FeatureCollection", "features": []}},
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
    {"id": "beacon", "type": "circle", "source": "enc", "source-layer": "beacon", "minzoom": 13,
      "paint": {"circle-radius": 7, "circle-color": $MARK_COLOR, "circle-stroke-color": "#222222", "circle-stroke-width": 2.5}},
    {"id": "buoy", "type": "circle", "source": "enc", "source-layer": "buoy", "minzoom": 13,
      "paint": {"circle-radius": 7, "circle-color": $MARK_COLOR, "circle-stroke-color": "#ffffff", "circle-stroke-width": 1.5}},
    {"id": "buoy-name", "type": "symbol", "source": "enc", "source-layer": "buoy", "minzoom": 15,
      "layout": {"text-field": ["get", "name"], "text-font": ["$FONT"], "text-size": 10, "text-offset": [0, 1.4]},
      "paint": {"text-color": "#222222", "text-halo-color": "#ffffff", "text-halo-width": 1}},
    {"id": "lights", "type": "circle", "source": "enc", "source-layer": "lights", "minzoom": 13,
      "paint": {"circle-radius": 3.5, "circle-color": $LIGHT_COLOR, "circle-opacity": 1,
        "circle-translate": [0, -12], "circle-translate-anchor": "viewport",
        "circle-stroke-color": ["match", ["get", "col"], "W", "#333333", "Y", "#333333", "#ffffff"],
        "circle-stroke-width": 1.5}},
    {"id": "lights-label", "type": "symbol", "source": "enc", "source-layer": "lights", "minzoom": 14,
      "layout": {"text-field": ["get", "label"], "text-font": ["$FONT"], "text-size": 10,
        "text-offset": [0.9, 0.15], "text-anchor": "left"},
      "paint": {"text-color": $LIGHT_TEXT, "text-halo-color": "#ffffff", "text-halo-width": 1}},
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
    {"id": "gps-halo", "type": "circle", "source": "gps",
      "paint": {"circle-radius": 12, "circle-color": "#1a73e8", "circle-opacity": 0.25}},
    {"id": "gps", "type": "circle", "source": "gps",
      "paint": {"circle-radius": 6, "circle-color": "#1a73e8", "circle-stroke-color": "#ffffff", "circle-stroke-width": 2}}
  ]
}
"""
}
