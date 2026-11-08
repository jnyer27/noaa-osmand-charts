package com.jnyer27.noaaroute

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Tiles of one chart MBTiles file: Android's SQLite in the app, sqlite-jdbc on the command line. */
interface ChartTileReader : AutoCloseable {
    /** The tile's bytes (gzipped or plain vector tile), or null. [row] is the MBTiles (TMS) row. */
    fun tile(zoom: Int, column: Int, row: Int): ByteArray?
}

/**
 * One chart file for [ChartDepthRoute]: [key] names it in the tile cache, [bounds] and [span]
 * (area of the bounds, a tighter chart wins) come from the catalog or its metadata, [zoom] is
 * the zoom its depth areas are read at, and [open] opens it (an exception skips the chart).
 */
class ChartDepthChart(val key: String, val bounds: DoubleArray, val span: Double, val zoom: Int,
                      val open: () -> ChartTileReader)

/**
 * Attaches [ChartDepth] notes to a finished route, reading DEPARE and DRGARE from chart files
 * leg by leg. A chart that will not open, a leg outside every chart, or a tile that will not
 * read is skipped: the route is unchanged for that leg.
 */
object ChartDepthRoute {
    fun annotate(route: Route, charts: List<ChartDepthChart>): Route {
        if (charts.isEmpty()) return route
        val opens = ArrayList<Pair<ChartDepthChart, ChartTileReader>>()
        try {
            for (chart in charts) {
                if (!hits(chart.bounds, route)) continue
                try { opens.add(chart to chart.open()) } catch (_: Exception) { }
            }
            if (opens.isEmpty()) return route
            val cache = object : LinkedHashMap<String, List<ChartDepth.Area>>(32, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ChartDepth.Area>>) = size > 48
            }
            // Covers are filled per leg so a long trip does not hold every tile at once.
            val n = min(route.legs.size, route.points.size - 1)
            var changed = false
            val legs = ArrayList<Leg>(route.legs.size)
            for (i in route.legs.indices) {
                val leg = route.legs[i]
                if (i >= n) { legs.add(leg); continue }
                val a = route.points[i]
                val b = route.points[i + 1]
                val legCovers = ArrayList<ChartDepth.Cover>()
                for ((chart, reader) in opens) {
                    if (!segmentHits(chart.bounds, a, b)) continue
                    val areas = areas(chart, reader, a, b, cache)
                    if (areas.isNotEmpty()) legCovers.add(ChartDepth.Cover(chart.bounds, chart.span, areas))
                }
                // A leg kept from an annotated route (Try to avoid checks replans only the
                // stretches it changes) already has its notes: add each one once
                val extra = ChartDepth.notes(a, b, route.depthFt, legCovers).filter { it !in leg.notes }
                if (extra.isEmpty()) legs.add(leg)
                else { changed = true; legs.add(Leg(leg.distM, leg.bearing, leg.shoalM, leg.lowM, leg.notes + extra)) }
            }
            if (!changed) return route
            return Route(route.name, route.points, legs, route.depthFt, route.airFt, route.resM, route.messages, route.guideM)
        } catch (_: Exception) {
            return route
        } finally {
            for ((_, reader) in opens) try { reader.close() } catch (_: Exception) { }
        }
    }

    /** True if any route point lies inside [bounds]: only those charts are opened. */
    fun hits(bounds: DoubleArray, route: Route) =
        route.points.any { it.lon in bounds[0]..bounds[2] && it.lat in bounds[1]..bounds[3] }

    private fun areas(chart: ChartDepthChart, reader: ChartTileReader, a: LatLon, b: LatLon,
                      cache: MutableMap<String, List<ChartDepth.Area>>): List<ChartDepth.Area> {
        val west = max(chart.bounds[0], min(a.lon, b.lon))
        val east = min(chart.bounds[2], max(a.lon, b.lon))
        val south = max(chart.bounds[1], min(a.lat, b.lat))
        val north = min(chart.bounds[3], max(a.lat, b.lat))
        if (east < west || north < south) return emptyList()
        var z = chart.zoom
        var tiles = tiles(west, south, east, north, z)
        if (tiles.size > 80 && z > 10) {
            z -= 1
            tiles = tiles(west, south, east, north, z)
        }
        if (tiles.size > 80) return emptyList()
        val out = ArrayList<ChartDepth.Area>()
        for ((x, y) in tiles) {
            val key = "${chart.key}|$z|$x|$y"
            val hit = cache[key] ?: load(reader, z, x, y).also { cache[key] = it }
            out.addAll(hit)
        }
        return out
    }

    private fun load(reader: ChartTileReader, z: Int, x: Int, y: Int): List<ChartDepth.Area> = try {
        val bytes = reader.tile(z, x, (1 shl z) - 1 - y)
        if (bytes == null) emptyList() else ChartDepth.areasInTile(bytes, z, x, y)
    } catch (_: Exception) {
        emptyList()
    }

    private fun tiles(west: Double, south: Double, east: Double, north: Double, z: Int): List<Pair<Int, Int>> {
        val n = 1 shl z
        val x0 = lonX(west, z).coerceIn(0, n - 1)
        val x1 = lonX(east, z).coerceIn(0, n - 1)
        val y0 = latY(north, z).coerceIn(0, n - 1)
        val y1 = latY(south, z).coerceIn(0, n - 1)
        val out = ArrayList<Pair<Int, Int>>((x1 - x0 + 1) * (y1 - y0 + 1))
        for (x in x0..x1) for (y in y0..y1) out.add(x to y)
        return out
    }

    private fun lonX(lon: Double, z: Int) = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt()

    private fun latY(lat: Double, z: Int): Int {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val merc = Math.log(Math.tan(r) + 1.0 / Math.cos(r))
        return floor((1.0 - merc / Math.PI) / 2.0 * (1 shl z)).toInt()
    }

    private fun segmentHits(bounds: DoubleArray, a: LatLon, b: LatLon): Boolean {
        val west = min(a.lon, b.lon); val east = max(a.lon, b.lon)
        val south = min(a.lat, b.lat); val north = max(a.lat, b.lat)
        return west <= bounds[2] && east >= bounds[0] && south <= bounds[3] && north >= bounds[1]
    }
}
