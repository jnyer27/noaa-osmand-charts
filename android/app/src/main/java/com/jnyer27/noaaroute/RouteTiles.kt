package com.jnyer27.noaaroute

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Route-data images for the chart screen. Reads cells with [Pack.tileGlobal] (the same
 * call [ChartSet] uses) and paints them with [RouteOverlay]. Images are cached as PNG
 * under the app's private storage, one file per block, depth, air draft and pack build.
 * Nothing is fetched over the network.
 */
class RouteTiles(
    private val packs: List<Pack>,
    private val cacheRoot: File,
    private val depthFt: Double,
    private val airFt: Double?,
) {
    class Placed(
        val id: String,
        val west: Double, val south: Double, val east: Double, val north: Double,
        val bitmap: Bitmap,
    )

    class Built(
        val covered: Boolean,
        /** Every image the view needs, including ones already on the map. */
        val ids: List<String>,
        /** Images not in [already] when [build] was called. Their bitmaps still need a recycle. */
        val placed: List<Placed>,
        val images: Int,
        val seconds: Double,
        val stride: Int,
        val aborted: Boolean,
    )

    private val depthM = depthFt * Pack.FT
    private val airM = airFt?.let { it * Pack.FT }
    private val grid = packs.filter { it.tile == RouteOverlay.PIX && it.dlat > 0.0 && it.dlon > 0.0 }
        .let { list ->
            if (list.isEmpty()) list
            else list.filter { it.dlat == list[0].dlat && it.dlon == list[0].dlon }
        }
    private val dir: File = File(cacheRoot, cacheKey()).apply { mkdirs() }

    init {
        // One depth and air draft per visit to the chart. Drop images painted for another.
        cacheRoot.listFiles()?.forEach { if (it.isDirectory && it.name != dir.name) it.deleteRecursively() }
    }

    /**
     * Images for the view, north and west included. [alive] is checked between tiles so a
     * pan or a closed screen abandons the work. Covered is false when no open pack's grid
     * meets the view. Aborted is true when the view is too large or [alive] failed.
     */
    fun build(west: Double, south: Double, east: Double, north: Double, already: Set<String>, alive: () -> Boolean): Built {
        val t0 = System.nanoTime()
        if (grid.isEmpty()) return Built(false, emptyList(), emptyList(), 0, 0.0, 1, false)
        val p0 = grid[0]
        val covered = grid.any { it.west < east && it.east > west && it.south < north && it.north > south }
        if (!covered) return Built(false, emptyList(), emptyList(), 0, elapsed(t0), 1, false)
        val tiles = RouteOverlay.tilesInView(west, south, east, north, p0.dlat, p0.dlon, p0.tile) { gtx, gty ->
            grid.any { RouteOverlay.packCoversTile(it.tx0, it.ty0, it.width, it.height, it.tile, gtx, gty) }
        } ?: return Built(true, emptyList(), emptyList(), 0, elapsed(t0), 1, true)
        val blocks = RouteOverlay.chooseBlocks(tiles)
        val stride = blocks.firstOrNull()?.stride ?: 1
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val ids = ArrayList<String>(blocks.size)
        val placed = ArrayList<Placed>(blocks.size)
        for (block in blocks) {
            if (!alive()) {
                placed.forEach { it.bitmap.recycle() }
                return Built(true, emptyList(), emptyList(), 0, elapsed(t0), stride, true)
            }
            val id = "s${block.stride}_${block.gtx}_${block.gty}"
            val png = File(dir, "$id.png")
            if (id in already) {
                if (png.isFile) png.setLastModified(System.currentTimeMillis())
                ids.add(id)
                continue
            }
            var bitmap = if (png.isFile) BitmapFactory.decodeFile(png.absolutePath, opts) else null
            if (bitmap == null) {
                val pixels = IntArray(RouteOverlay.PIX * RouteOverlay.PIX)
                val drawn = RouteOverlay.paintBlock(block.stride, p0.tile, { tx, ty ->
                    planes(block.gtx + tx, block.gty + ty)
                }, depthM, airM, pixels, alive)
                if (drawn < 0) {
                    placed.forEach { it.bitmap.recycle() }
                    return Built(true, emptyList(), emptyList(), 0, elapsed(t0), stride, true)
                }
                if (drawn == 0) continue
                bitmap = Bitmap.createBitmap(RouteOverlay.PIX, RouteOverlay.PIX, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(pixels, 0, RouteOverlay.PIX, 0, 0, RouteOverlay.PIX, RouteOverlay.PIX)
                val part = File(png.path + ".part")
                try {
                    part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    if (png.exists()) png.delete()
                    if (!part.renameTo(png)) part.delete()
                } catch (_: Exception) {
                    part.delete()
                }
            }
            val b = RouteOverlay.blockBounds(block.gtx, block.gty, block.stride, p0.dlat, p0.dlon, p0.tile)
            ids.add(id)
            placed.add(Placed(id, b[0], b[1], b[2], b[3], bitmap))
        }
        trim()
        val seconds = elapsed(t0)
        Log.i(TAG, "route data ${ids.size} images (${placed.size} new), stride $stride, ${tiles.size} tiles, " +
            String.format(Locale.US, "%.0f ms", seconds * 1000))
        return Built(true, ids, placed, ids.size, seconds, stride, false)
    }

    /**
     * First pack that has bytes for this global tile. An empty tile is skipped, so a
     * neighbor that does have the tile is used. [ChartSet] walks its packs the same way.
     */
    private fun planes(gtx: Int, gty: Int): RouteOverlay.TilePlanes? {
        for (p in grid) {
            val data = p.tileGlobal(gtx, gty) ?: continue
            return RouteOverlay.TilePlanes(data, p.depthTable)
        }
        return null
    }

    private fun cacheKey(): String {
        val packsKey = grid.joinToString(";") { "${it.areaId}:${it.created}:${it.tx0}:${it.ty0}:${it.width}x${it.height}" }
        val raw = "v1|$packsKey|d=" + String.format(Locale.US, "%.4f", depthFt) +
            "|a=" + (airFt?.let { String.format(Locale.US, "%.4f", it) } ?: "-")
        val md = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return md.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Keep the cache to a few hundred images. The oldest go first. */
    private fun trim() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortBy { it.lastModified() }
        for (i in 0 until files.size - MAX_FILES / 2) files[i].delete()
    }

    private fun elapsed(t0: Long) = (System.nanoTime() - t0) / 1e9

    companion object {
        private const val TAG = "RouteOverlay"
        private const val MAX_FILES = 400
    }
}
