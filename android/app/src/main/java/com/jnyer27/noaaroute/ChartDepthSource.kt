package com.jnyer27.noaaroute

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Reads DEPARE and DRGARE out of the chart mbtiles on the phone and attaches
 * [ChartDepth] notes to a route ([ChartDepthRoute], shared with the command-line
 * planner). A missing chart, a leg outside every chart, or a tile that will not
 * read is skipped: the route is unchanged for that leg.
 */
class ChartDepthSource(private val ctx: android.content.Context) {
    fun annotate(route: Route): Route {
        val charts = try { charts() } catch (_: Exception) { emptyList() }
        return ChartDepthRoute.annotate(route, charts)
    }

    /** What the downloaded charts say at [p] (blocking: off the UI thread). */
    fun spot(p: LatLon): ChartDepthRoute.Spot? = ChartDepthRoute.spot(p, charts())

    /** One chart file read with Android's SQLite. */
    private class Reader(file: File) : ChartTileReader {
        private val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)

        override fun tile(zoom: Int, column: Int, row: Int): ByteArray? =
            db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                arrayOf(zoom.toString(), column.toString(), row.toString()),
            ).use { c -> if (!c.moveToFirst()) null else c.getBlob(0) ?: ByteArray(0) }

        override fun close() = db.close()
    }

    private fun chart(file: File, bounds: DoubleArray, span: Double, zoom: Int) =
        ChartDepthChart(file.path, bounds, span, zoom) { Reader(file) }

    private fun charts(): List<ChartDepthChart> {
        val out = ArrayList<ChartDepthChart>()
        val store = ChartTileStore(ctx)
        for (set in store.readySets()) {
            val file = store.file(set)
            if (!file.isFile) continue
            out.add(chart(file, set.bounds, set.span(), set.maxZoom.coerceIn(8, 15)))
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
            out.add(chart(bundled, bounds, span, zoom.coerceIn(8, 15)))
        }
        return out
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
