package com.jnyer27.noaaroute

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * In-app ENC tiles (Ncds_04.mbtiles and, later, the other regions), from the vector
 * chart catalog's `tiles` entry. DownloadManager fetches the file into app storage.
 * ChartJob checks the SHA-256 and renames it into place. The file stays in the app.
 * It is not copied to Download/noaa-charts and it is not an OsmAnd map.
 *
 * A newer catalog sha replaces the file only after the new download checks out.
 * Until then the chart already on the phone keeps working.
 *
 * [Kind.BASEMAP] is the same store for the OpenStreetMap base map (Ncds_04_basemap.pmtiles,
 * the catalog's `basemap` entry): its own folder and saved state, the same download and check.
 */
class ChartTileStore(private val ctx: Context, val kind: Kind = Kind.CHART) {
    enum class State { DOWNLOADING, DOWNLOADED, SAVING, READY, FAILED }

    /** [key]: the catalog object; [what]: the word in messages ("chart", "base map"). */
    enum class Kind(val key: String, val prefs: String, val folder: String, val what: String, val title: String) {
        CHART("tiles", "charttiles", "chart-tiles", "chart", "NOAA chart"),
        BASEMAP("basemap", "basemap", "basemap", "base map", "Base map"),
    }

    class Entry(
        val ncds: String, val state: State, val name: String, val file: String,
        val sha256: String, val readySha: String?, val size: Long, val bounds: DoubleArray,
        val minZoom: Int, val maxZoom: Int, val download: Long = -1, val error: String? = null,
    ) {
        fun tileSet(): ChartTiles.TileSet =
            ChartTiles.TileSet(ncds, name, file, size, readySha ?: sha256, bounds, minZoom, maxZoom)

        fun json(): JSONObject = JSONObject()
            .put("state", state.name).put("name", name).put("file", file)
            .put("sha256", sha256).put("readySha", readySha).put("size", size)
            .put("bounds", JSONArray(bounds.toList()))
            .put("minzoom", minZoom).put("maxzoom", maxZoom)
            .put("download", download).put("error", error)
    }

    private val prefs = ctx.getSharedPreferences("noaaroute", Context.MODE_PRIVATE)
    private val dm = ctx.getSystemService(DownloadManager::class.java)
    val dir: File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, kind.folder).apply { mkdirs() }
    /**
     * Each its own copy of the vector chart catalog, from [base]: a branch APK reads the
     * branch's charts and base maps, while VectorMaps (vector-catalog.json) keeps the
     * main release's OsmAnd maps.
     */
    private val catalogFile = File(ctx.filesDir, if (kind == Kind.CHART) "chart-catalog.json" else "basemap-catalog.json")
    val base: String = if (kind == Kind.CHART) BuildConfig.CHART_BASE else BuildConfig.BASEMAP_BASE
    /**
     * Where the saved catalog came from. A test APK reads the beta release and the app
     * after it reads main (or the other way round), and files keep their names but not
     * their checksums: a catalog saved from another release is not used.
     */
    private val catalogBaseFile = File(ctx.filesDir, catalogFile.name + ".base")

    fun catalogSets(): List<ChartTiles.TileSet> = try {
        if (catalogBaseFile.readText() != base) emptyList()
        else ChartTiles.tileSets(catalogFile.readText(), kind.key)
    } catch (_: Exception) { emptyList() }

    /** Fetch the vector catalog from [base] (branch builds read vector-maps-beta) and return its tile sets. */
    fun refreshCatalog(): List<ChartTiles.TileSet> {
        try { fetchCatalog() } catch (_: Exception) { }
        return catalogSets()
    }

    private fun fetchCatalog() {
        val c = java.net.URL(base + "catalog.json").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode} for the ${kind.what} catalog")
            val text = c.inputStream.bufferedReader().readText()
            ChartTiles.tileSets(text, kind.key) // parses, or throws before the saved copy is replaced
            catalogFile.writeText(text)
            catalogBaseFile.writeText(base)
        } finally { c.disconnect() }
    }

    fun file(set: ChartTiles.TileSet) = File(dir, set.file)
    private fun partial(file: String) = File(dir, "$file.download")

    fun freeBytes(): Long = StatFs(dir.path).availableBytes

    /** Room for the new file beside the one already shown, during an update. */
    fun spaceNeeded(set: ChartTiles.TileSet) = set.size * 2

    @Synchronized
    fun all(): Map<String, Entry> {
        val j = try { JSONObject(prefs.getString(kind.prefs, "{}")!!) } catch (_: Exception) { JSONObject() }
        val out = LinkedHashMap<String, Entry>()
        for (k in j.keys()) {
            val e = j.getJSONObject(k)
            val b = e.optJSONArray("bounds")
            val bounds = if (b != null && b.length() == 4) DoubleArray(4) { b.getDouble(it) } else doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            out[k] = Entry(
                k, State.valueOf(e.getString("state")), e.optString("name"), e.optString("file"),
                e.optString("sha256"), e.optString("readySha").ifEmpty { null }?.takeIf { it != "null" }, e.optLong("size"), bounds,
                e.optInt("minzoom", 8), e.optInt("maxzoom", 15), e.optLong("download", -1),
                e.optString("error").ifEmpty { null }.takeIf { it != "null" },
            )
        }
        return out
    }

    fun get(ncds: String) = all()[ncds]

    /** Charts whose file is in place and checked. */
    fun readySets(): List<ChartTiles.TileSet> = all().values.mapNotNull { e ->
        if (e.state != State.READY || e.readySha.isNullOrEmpty()) null
        else if (!File(dir, e.file).isFile) null
        else e.tileSet()
    }

    fun busy(ncds: String?): Boolean {
        if (ncds == null) return false
        return get(ncds)?.state in BUSY
    }

    @Synchronized
    private fun put(e: Entry?, key: String = e!!.ncds) {
        val j = try { JSONObject(prefs.getString(kind.prefs, "{}")!!) } catch (_: Exception) { JSONObject() }
        if (e == null) j.remove(key) else j.put(key, e.json())
        prefs.edit().putString(kind.prefs, j.toString()).apply()
    }

    /** Start a download of [set]. A chart already shown stays until this one checks out. */
    fun start(set: ChartTiles.TileSet, mobileData: Boolean) {
        val old = get(set.ncds)
        if (old != null && old.download >= 0) dm.remove(old.download)
        partial(set.file).delete()
        val req = DownloadManager.Request(Uri.parse(set.url(base)))
            .setTitle("${kind.title}, ${set.shortName}")
            .setDescription("For the map in NOAA Route")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, null, "${kind.folder}/${set.file}.download")
            .setAllowedOverMetered(mobileData)
            .setAllowedOverRoaming(false)
        val id = dm.enqueue(req)
        put(Entry(
            set.ncds, State.DOWNLOADING, set.name, set.file, set.sha256, old?.readySha, set.size, set.bounds,
            set.minZoom, set.maxZoom, id,
        ))
    }

    /** (bytes so far, total) while a download is running, or null. */
    fun progress(ncds: String): Pair<Long, Long>? {
        val e = get(ncds) ?: return null
        if (e.state != State.DOWNLOADING || e.download < 0) return null
        dm.query(DownloadManager.Query().setFilterById(e.download))?.use { c ->
            if (!c.moveToFirst()) return 0L to e.size
            val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return got to (if (total > 0) total else e.size)
        }
        return 0L to e.size
    }

    /** Why a download has not moved, from DownloadManager: queued, waiting for Wi-Fi, ... or null while running. */
    fun waitReason(ncds: String): String? {
        val e = get(ncds) ?: return null
        if (e.state != State.DOWNLOADING || e.download < 0) return null
        dm.query(DownloadManager.Query().setFilterById(e.download))?.use { c ->
            if (!c.moveToFirst()) return "not in Android's download list"
            return when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_PENDING -> "queued"
                DownloadManager.STATUS_PAUSED -> when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))) {
                    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "waiting for Wi-Fi (this connection counts as metered)"
                    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "waiting for a network"
                    DownloadManager.PAUSED_WAITING_TO_RETRY -> "waiting to retry"
                    else -> "paused"
                }
                else -> null
            }
        }
        return null
    }

    /**
     * A download ended (from ChartDownloadReceiver, or noticed by [reconcile]).
     * True if it was one of ours.
     */
    fun downloadDone(id: Long): Boolean {
        val e = all().values.firstOrNull { it.state == State.DOWNLOADING && it.download == id } ?: return false
        var ok = false
        var reason = "download failed"
        var local: String? = null
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) {
                ok = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
                if (!ok) reason = "download failed (code ${c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))})"
                local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            } else reason = "download was cancelled"
        }
        // Android can save under another name than the one asked for (Ncds_06_basemap-1...)
        // when a file is in the way: check the file it wrote, not an old one at our path
        if (ok) try {
            val got = local?.let { Uri.parse(it).path }?.let { File(it) }
            val want = partial(e.file)
            if (got != null && got.isFile && got.canonicalPath != want.canonicalPath) {
                want.delete()
                if (!got.renameTo(want)) { got.copyTo(want, overwrite = true); got.delete() }
            }
        } catch (_: Exception) { }
        if (!ok) {
            dm.remove(id)
            partial(e.file).delete()
            fail(e, reason)
        } else {
            put(e.copy(state = State.DOWNLOADED, download = -1))
            ChartMaps.schedule(ctx)
        }
        return true
    }

    fun reconcile() {
        for (e in all().values) when (e.state) {
            State.DOWNLOADING -> {
                if (e.download < 0) { fail(e, "download was cancelled"); continue }
                var status = -1
                dm.query(DownloadManager.Query().setFilterById(e.download))?.use { c ->
                    if (c.moveToFirst()) status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                }
                if (status == -1 || status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED)
                    downloadDone(e.download)
            }
            State.DOWNLOADED, State.SAVING -> ChartMaps.schedule(ctx)
            State.READY -> if (!File(dir, e.file).isFile) put(null, e.ncds)
            State.FAILED -> {}
        }
    }

    /** Remove a region's chart. View map falls back to the bundled chart there. */
    fun delete(ncds: String) {
        val e = get(ncds) ?: return
        if (e.download >= 0) dm.remove(e.download)
        partial(e.file).delete()
        File(dir, e.file).delete()
        put(null, ncds)
    }

    fun needsUpdate(e: Entry?, remote: ChartTiles.TileSet) =
        e?.state == State.READY && e.readySha != null && e.readySha != remote.sha256

    fun cancel(ncds: String) {
        val e = get(ncds) ?: return
        if (e.download >= 0) dm.remove(e.download)
        partial(e.file).delete()
        if (e.readySha != null && File(dir, e.file).isFile)
            put(e.copy(state = State.READY, sha256 = e.readySha, download = -1, error = null))
        else put(null, ncds)
    }

    /** Check finished downloads. True if one failed. */
    fun runPending(): Boolean {
        var failed = false
        val done = HashSet<String>()
        while (true) {
            val e = all().values.firstOrNull { it.ncds !in done && it.state in setOf(State.DOWNLOADED, State.SAVING) } ?: break
            done.add(e.ncds)
            try {
                check(e)
            } catch (ex: Exception) {
                failed = true
                partial(e.file).delete()
                fail(e, ex.message ?: ex.toString())
                ChartMaps.notifyChart(ctx, "Region ${e.ncds} ${kind.what} failed", ex.message ?: "", null)
            }
        }
        return failed
    }

    private fun check(e: Entry) {
        put(e.copy(state = State.SAVING, download = -1))
        val src = partial(e.file)
        if (!src.isFile) throw Exception("the downloaded ${e.file} is gone; download it again")
        val got = sha256(src)
        var want = e.sha256
        if (got != want) {
            // The entry's sha came from the saved catalog, which can be older than the
            // file just downloaded (rebuilt since, or saved from another release):
            // the file is good if the current catalog lists it with this sha
            try { fetchCatalog() } catch (_: Exception) { }
            val now = catalogSets().firstOrNull { it.ncds == e.ncds && it.file == e.file }
            if (now != null && now.sha256 == got) want = got
            else throw Exception("${e.file} is damaged (checksum; %.1f MB, expected %.1f MB, sha %s, catalog %s); download it again"
                .format(src.length() / 1e6, (now?.size ?: e.size) / 1e6, got.take(8), (now?.sha256 ?: e.sha256).take(8)))
        }
        val dest = File(dir, e.file)
        if (dest.exists()) dest.delete()
        if (!src.renameTo(dest)) {
            src.copyTo(dest, overwrite = true)
            src.delete()
        }
        put(e.copy(state = State.READY, sha256 = want, readySha = want, download = -1, error = null))
        ChartMaps.notifyChart(ctx, "Region ${e.ncds} ${kind.what} ready", "Open View map to see it.", null)
    }

    private fun fail(e: Entry, why: String) {
        if (e.readySha != null && File(dir, e.file).isFile)
            put(e.copy(state = State.READY, sha256 = e.readySha, download = -1, error = "update failed: $why"))
        else put(e.copy(state = State.FAILED, download = -1, error = why))
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Entry.copy(
        state: State = this.state, sha256: String = this.sha256, readySha: String? = this.readySha,
        download: Long = this.download, error: String? = this.error,
    ) = Entry(ncds, state, name, file, sha256, readySha, size, bounds, minZoom, maxZoom, download, error)

    companion object {
        private val BUSY = setOf(State.DOWNLOADING, State.DOWNLOADED, State.SAVING)
    }
}
