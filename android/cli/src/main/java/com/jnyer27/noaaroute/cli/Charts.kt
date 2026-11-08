package com.jnyer27.noaaroute.cli

import com.jnyer27.noaaroute.ChartDepthChart
import com.jnyer27.noaaroute.ChartTileReader
import com.jnyer27.noaaroute.ChartTiles
import com.jnyer27.noaaroute.PackCache
import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection
import java.sql.PreparedStatement

/** One chart MBTiles file read with sqlite-jdbc, read-only. */
class JdbcTileReader(file: File) : ChartTileReader {
    private val conn: Connection = SQLiteConfig().apply { setReadOnly(true) }.createConnection("jdbc:sqlite:" + file.path)
    private val query: PreparedStatement =
        conn.prepareStatement("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?")

    override fun tile(zoom: Int, column: Int, row: Int): ByteArray? {
        query.setInt(1, zoom)
        query.setInt(2, column)
        query.setInt(3, row)
        return query.executeQuery().use { rs -> if (rs.next()) rs.getBytes(1) ?: ByteArray(0) else null }
    }

    override fun close() {
        try { query.close() } finally { conn.close() }
    }
}

/**
 * The region charts (Ncds_XX.mbtiles, the app's in-app chart) that the depth check reads,
 * from the vector chart catalog. Each file is kept with a .sha256 beside it, so a newer
 * build in the catalog is downloaded again.
 */
class ChartCache(val dir: File, private val base: String) {
    private val catalogFile = File(dir, "chart-catalog.json")

    init { dir.mkdirs() }

    /** The catalog's chart files: fetched, else the saved copy (empty if there is none). */
    fun sets(offline: Boolean): List<ChartTiles.TileSet> {
        if (!offline) {
            try {
                val text = PackCache.httpGet(base + "catalog.json").inputStream.bufferedReader().readText()
                val sets = ChartTiles.tileSets(text) // parses, or throws before the saved copy is replaced
                catalogFile.writeText(text)
                return sets
            } catch (_: Exception) { }
        }
        return try { ChartTiles.tileSets(catalogFile.readText()) } catch (_: Exception) { emptyList() }
    }

    fun file(set: ChartTiles.TileSet) = File(dir, set.file)
    private fun shaFile(set: ChartTiles.TileSet) = File(dir, set.file + ".sha256")

    /** True if this exact build is here. */
    fun current(set: ChartTiles.TileSet) =
        file(set).isFile && try { shaFile(set).readText().trim() == set.sha256 } catch (_: Exception) { false }

    /** True if some build of it is here (offline, an older build is better than none). */
    fun have(set: ChartTiles.TileSet) = file(set).isFile

    fun download(set: ChartTiles.TileSet, progress: (Long, Long) -> Unit = { _, _ -> }) {
        shaFile(set).delete()
        PackCache.fetchChecked(set.url(base), file(set), set.sha256, set.size, progress)
        shaFile(set).writeText(set.sha256)
    }

    fun chart(set: ChartTiles.TileSet): ChartDepthChart {
        val f = file(set)
        return ChartDepthChart(f.path, set.bounds, set.span(), set.maxZoom.coerceIn(8, 15)) { JdbcTileReader(f) }
    }
}
