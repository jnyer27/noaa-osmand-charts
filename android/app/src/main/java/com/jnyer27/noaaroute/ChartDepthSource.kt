package com.jnyer27.noaaroute

import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Reads DEPARE and DRGARE out of the chart mbtiles on the phone and attaches
 * [ChartDepth] notes to a route. A missing chart, a leg outside every chart,
 * or a tile that will not read is skipped: the route is unchanged for that leg.
 */
class ChartDepthSource(private val ctx: android.content.Context) {
    private class Chart(val file: File, val bounds: DoubleArray, val span: Double, val zoom: Int)

    fun annotate(route: Route): Route {
        val charts = try { charts() } catch (_: Exception) { emptyList() }
        if (charts.isEmpty()) return route
        val opens = ArrayList<Pair<Chart, SQLiteDatabase>>()
        try {
            for (chart in charts) {
                if (!hits(chart.bounds, route)) continue
                try {
                    opens.add(chart to SQLiteDatabase.openDatabase(chart.file.path, null, SQLiteDatabase.OPEN_READONLY))
                } catch (_: Exception) { }
            }
            if (opens.isEmpty()) return route
            val cache = object : LinkedHashMap<String, List<ChartDepth.Area>>(32, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ChartDepth.Area>>) = size > 48
            }
            val covers = opens.map { (chart, db) ->
                ChartDepth.Cover(chart.bounds, chart.span, emptyList()) to (chart to db)
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
                for ((shell, src) in covers) {
                    val (chart, db) = src
                    if (!segmentHits(chart.bounds, a, b)) continue
                    val areas = areas(chart, db, a, b, cache)
                    if (areas.isNotEmpty()) legCovers.add(ChartDepth.Cover(shell.bounds, shell.span, areas))
                }
                val extra = ChartDepth.notes(a, b, route.depthFt, legCovers)
                if (extra.isEmpty()) legs.add(leg)
                else { changed = true; legs.add(Leg(leg.distM, leg.bearing, leg.shoalM, leg.lowM, leg.notes + extra)) }
            }
            if (!changed) return route
            return Route(route.name, route.points, legs, route.depthFt, route.airFt, route.resM, route.messages, route.guideM)
        } catch (_: Exception) {
            return route
        } finally {
            for ((_, db) in opens) try { db.close() } catch (_: Exception) { }
        }
    }

    private fun charts(): List<Chart> {
        val out = ArrayList<Chart>()
        val store = ChartTileStore(ctx)
        for (set in store.readySets()) {
            val file = store.file(set)
            if (!file.isFile) continue
            out.add(Chart(file, set.bounds, set.span(), set.maxZoom.coerceIn(8, 15)))
        }
        val bundled = File(ctx.filesDir, ChartTiles.ASSET)
        if (!bundled.isFile) {
            try {
                ctx.assets.open(ChartTiles.ASSET).use { input ->
                    bundled.outputStream().use { input.copyTo(it) }
                }
            } catch (_: Exception) { }
        }
        if (bundled.isFile) {
            val bounds = metadataBounds(bundled) ?: doubleArrayOf(ChartTiles.WEST, ChartTiles.SOUTH, ChartTiles.EAST, ChartTiles.NORTH)
            val span = (bounds[2] - bounds[0]) * (bounds[3] - bounds[1])
            val zoom = metadataZoom(bundled) ?: 15
            out.add(Chart(bundled, bounds, span, zoom.coerceIn(8, 15)))
        }
        return out
    }

    private fun areas(chart: Chart, db: SQLiteDatabase, a: LatLon, b: LatLon, cache: MutableMap<String, List<ChartDepth.Area>>): List<ChartDepth.Area> {
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
            val key = "${chart.file.path}|$z|$x|$y"
            val hit = cache[key] ?: load(db, chart, z, x, y).also { cache[key] = it }
            out.addAll(hit)
        }
        return out
    }

    private fun load(db: SQLiteDatabase, chart: Chart, z: Int, x: Int, y: Int): List<ChartDepth.Area> {
        val tms = ((1 shl z) - 1 - y).toString()
        return try {
            db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                arrayOf(z.toString(), x.toString(), tms),
            ).use { c ->
                if (!c.moveToFirst()) emptyList()
                else ChartDepth.areasInTile(c.getBlob(0) ?: ByteArray(0), z, x, y)
            }
        } catch (_: Exception) {
            emptyList()
        }
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

    private fun hits(bounds: DoubleArray, route: Route) =
        route.points.any { it.lon in bounds[0]..bounds[2] && it.lat in bounds[1]..bounds[3] }

    private fun segmentHits(bounds: DoubleArray, a: LatLon, b: LatLon): Boolean {
        val west = min(a.lon, b.lon); val east = max(a.lon, b.lon)
        val south = min(a.lat, b.lat); val north = max(a.lat, b.lat)
        return west <= bounds[2] && east >= bounds[0] && south <= bounds[3] && north >= bounds[1]
    }

    private fun metadataBounds(file: File): DoubleArray? = meta(file, "bounds")?.split(',')?.let { p ->
        if (p.size != 4) null
        else try { DoubleArray(4) { p[it].trim().toDouble() } } catch (_: Exception) { null }
    }

    private fun metadataZoom(file: File): Int? = meta(file, "maxzoom")?.trim()?.toIntOrNull()

    private fun meta(file: File, name: String): String? = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM metadata WHERE name=?", arrayOf(name)).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }
    } catch (_: Exception) { null }
}
