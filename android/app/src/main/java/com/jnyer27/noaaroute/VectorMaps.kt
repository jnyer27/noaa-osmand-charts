package com.jnyer27.noaaroute

import android.Manifest
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Vector chart maps for OsmAnd, made from NOAA's ENC charts by vector-maps.yml: two .obf
 * files per NCDS region, the depth map (contours, soundings; OsmAnd lists it under
 * Nautical maps) and the seamarks map (marks, lights, hazards, channels; Standard maps).
 * Android's DownloadManager fetches them into app storage; ChartJob checks them against
 * the catalog's SHA-256 and saves them to Download/noaa-charts, where OsmAnd imports a
 * copy. State lives in preferences, like ChartMaps, so a download finishing while the
 * app is closed is still saved.
 */
class VectorMaps(private val ctx: Context) {
    companion object {
        val BASE: String = BuildConfig.VECTOR_BASE
        private const val MIME = "application/octet-stream"

        /** Free space needed: the download plus the copy in Download. */
        fun spaceNeeded(r: Region) = 2 * r.size
    }

    class FileInfo(val kind: String, val file: String, val size: Long, val sha256: String)

    /** A region in the published catalog. */
    class Region(val ncds: String, val name: String, val created: String, val files: List<FileInfo>) {
        val size get() = files.sumOf { it.size }
        fun file(kind: String) = files.firstOrNull { it.kind == kind }
    }

    enum class State { DOWNLOADING, DOWNLOADED, SAVING, READY, FAILED }

    /**
     * One region's maps on this phone. [downloads]: DownloadManager ids by file name while
     * downloading; [uris]: the saved files in Download/noaa-charts by kind once ready.
     */
    class Entry(val ncds: String, val state: State, val created: String, val files: List<FileInfo>,
                val downloads: Map<String, Long> = emptyMap(), val uris: Map<String, String> = emptyMap(),
                val names: Map<String, String> = emptyMap(), val error: String? = null) {
        val size get() = files.sumOf { it.size }
        fun json(): JSONObject = JSONObject().put("state", state.name).put("created", created)
            .put("files", JSONArray(files.map { JSONObject().put("kind", it.kind).put("file", it.file).put("size", it.size).put("sha256", it.sha256) }))
            .put("downloads", JSONObject(downloads.mapValues { it.value }))
            .put("uris", JSONObject(uris)).put("names", JSONObject(names)).put("error", error)
    }

    private val prefs = ctx.getSharedPreferences("noaaroute", Context.MODE_PRIVATE)
    private val dm = ctx.getSystemService(DownloadManager::class.java)
    val dir: File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "vector").apply { mkdirs() }
    private val catalogFile = File(ctx.filesDir, "vector-catalog.json")

    // ------------------------------------------------------------ catalog

    private fun parseCatalog(text: String): List<Region> {
        val a = JSONObject(text).optJSONArray("regions") ?: return emptyList()
        return (0 until a.length()).map { k ->
            val r = a.getJSONObject(k)
            val fa = r.getJSONArray("files")
            Region(r.getString("ncds"), r.optString("name"), r.optString("created"), (0 until fa.length()).map { j ->
                val f = fa.getJSONObject(j)
                FileInfo(f.getString("kind"), f.getString("file"), f.getLong("size"), f.optString("sha256"))
            })
        }
    }

    /** The published catalog (blocking); kept for offline use. */
    fun fetchCatalog(): List<Region> {
        val c = URL(BASE + "catalog.json").openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode == 404) return emptyList() // nothing published yet
            if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode} for the vector chart catalog")
            val text = c.inputStream.bufferedReader().readText()
            val list = parseCatalog(text)
            catalogFile.writeText(text)
            return list
        } finally { c.disconnect() }
    }

    /** The catalog as last fetched, or empty. */
    fun cachedCatalog(): List<Region> = try { parseCatalog(catalogFile.readText()) } catch (_: Exception) { emptyList() }

    // ------------------------------------------------------------ state

    @Synchronized
    fun all(): Map<String, Entry> {
        val j = try { JSONObject(prefs.getString("vector", "{}")!!) } catch (_: Exception) { JSONObject() }
        val out = LinkedHashMap<String, Entry>()
        for (k in j.keys()) {
            val e = j.getJSONObject(k)
            val fa = e.optJSONArray("files") ?: JSONArray()
            fun map(name: String): Map<String, String> = e.optJSONObject(name)?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } } ?: emptyMap()
            out[k] = Entry(k, State.valueOf(e.getString("state")), e.optString("created"),
                (0 until fa.length()).map { i ->
                    val f = fa.getJSONObject(i)
                    FileInfo(f.getString("kind"), f.getString("file"), f.getLong("size"), f.optString("sha256"))
                },
                map("downloads").mapValues { it.value.toLong() }, map("uris"), map("names"),
                e.optString("error").ifEmpty { null }.takeIf { it != "null" })
        }
        return out
    }

    @Synchronized
    private fun put(e: Entry?, key: String = e!!.ncds) {
        val j = try { JSONObject(prefs.getString("vector", "{}")!!) } catch (_: Exception) { JSONObject() }
        if (e == null) j.remove(key) else j.put(key, e.json())
        prefs.edit().putString("vector", j.toString()).apply()
    }

    fun get(code: String) = all()[code]

    fun freeBytes(): Long = StatFs(dir.path).availableBytes

    /** A newer build of a ready region's maps is published. */
    fun hasUpdate(e: Entry, r: Region?) = e.state == State.READY && r != null && r.created > e.created

    // ------------------------------------------------------------ downloads

    /** Download a region's two maps; a ready region keeps its maps until the new ones are saved. */
    fun start(r: Region, mobileData: Boolean) {
        val old = get(r.ncds)
        old?.downloads?.values?.forEach { dm.remove(it) }
        val ids = LinkedHashMap<String, Long>()
        for (f in r.files) {
            File(dir, f.file).delete()
            val req = DownloadManager.Request(Uri.parse(BASE + f.file))
                .setTitle("NOAA vector chart, region ${r.ncds} (${f.kind})")
                .setDescription("For OsmAnd (NOAA Route)")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(ctx, null, "vector/${f.file}")
                .setAllowedOverMetered(mobileData)
                .setAllowedOverRoaming(false)
            ids[f.file] = dm.enqueue(req)
        }
        put(Entry(r.ncds, State.DOWNLOADING, r.created, r.files, downloads = ids,
            uris = old?.uris.orEmpty(), names = old?.names.orEmpty()))
    }

    /** (bytes so far, total) of a region's running downloads, or null. */
    fun progress(e: Entry): Pair<Long, Long>? {
        if (e.state != State.DOWNLOADING || e.downloads.isEmpty()) return null
        var got = 0L
        var total = 0L
        dm.query(DownloadManager.Query().setFilterById(*e.downloads.values.toLongArray()))?.use { c ->
            while (c.moveToNext()) {
                got += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                total += maxOf(0L, c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
            }
        }
        return got to (if (total > 0) total else e.size)
    }

    /** DownloadManager status of each of a region's downloads (-1 if it is gone). */
    private fun statuses(e: Entry): Map<Long, Int> {
        val out = e.downloads.values.associateWith { -1 }.toMutableMap()
        dm.query(DownloadManager.Query().setFilterById(*e.downloads.values.toLongArray()))?.use { c ->
            while (c.moveToNext()) out[c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))] =
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
        return out
    }

    /**
     * A download ended (from ChartDownloadReceiver, or noticed by [reconcile]); true if it
     * was one of ours. When both of a region's files are in, ChartJob saves them.
     */
    fun downloadDone(id: Long): Boolean {
        val e = all().values.firstOrNull { it.state == State.DOWNLOADING && id in it.downloads.values } ?: return false
        val st = statuses(e)
        if (st.values.any { it == -1 || it == DownloadManager.STATUS_FAILED }) {
            e.downloads.values.forEach { dm.remove(it) }
            put(failed(e, if (st.values.any { it == -1 }) "download was cancelled" else "download failed"))
        } else if (st.values.all { it == DownloadManager.STATUS_SUCCESSFUL }) {
            put(Entry(e.ncds, State.DOWNLOADED, e.created, e.files, uris = e.uris, names = e.names))
            ChartMaps.schedule(ctx)
        }
        return true
    }

    /** A failed download or save; maps saved earlier (an update that failed) stay usable. */
    private fun failed(e: Entry, why: String) =
        if (e.uris.isNotEmpty()) Entry(e.ncds, State.READY, prefs.getString("vector-created-${e.ncds}", "") ?: "", e.files,
            uris = e.uris, names = e.names, error = "update failed: $why")
        else Entry(e.ncds, State.FAILED, e.created, e.files, error = why)

    /** Fix up state after the app was away: finished downloads, maps deleted in Files. */
    fun reconcile() {
        for (e in all().values) when (e.state) {
            State.DOWNLOADING -> {
                val st = statuses(e)
                if (st.values.all { it == -1 || it == DownloadManager.STATUS_SUCCESSFUL || it == DownloadManager.STATUS_FAILED })
                    e.downloads.values.firstOrNull()?.let { downloadDone(it) } ?: put(failed(e, "download was cancelled"))
            }
            State.DOWNLOADED, State.SAVING -> ChartMaps.schedule(ctx)
            State.READY -> {
                val gone = e.uris.values.any { u ->
                    try { ctx.contentResolver.openFileDescriptor(Uri.parse(u), "r")?.close(); false } catch (_: Exception) { true }
                }
                if (gone) delete(e.ncds) // one was deleted in Files: offer the download again
            }
            State.FAILED -> {}
        }
    }

    fun cancel(code: String) {
        val e = get(code) ?: return
        e.downloads.values.forEach { dm.remove(it) }
        e.files.forEach { File(dir, it.file).delete() }
        if (e.uris.isNotEmpty()) put(Entry(e.ncds, State.READY, prefs.getString("vector-created-$code", "") ?: "", e.files, uris = e.uris, names = e.names))
        else put(null, code)
    }

    /** Delete a region's saved maps (the copies OsmAnd imported stay in OsmAnd). */
    fun delete(code: String) {
        val e = get(code) ?: return
        e.downloads.values.forEach { dm.remove(it) }
        e.files.forEach { File(dir, it.file).delete() }
        e.uris.values.forEach { u -> try { ctx.contentResolver.delete(Uri.parse(u), null, null) } catch (_: Exception) { } }
        put(null, code)
    }

    // ------------------------------------------------------------ saving (ChartJob)

    /** Check and save downloaded maps until none is left; true if anything failed. */
    fun runPending(): Boolean {
        var failed = false
        val done = HashSet<String>()
        while (true) {
            val e = all().values.firstOrNull { it.ncds !in done && it.state in setOf(State.DOWNLOADED, State.SAVING) } ?: break
            done.add(e.ncds)
            try {
                save(e)
            } catch (ex: Exception) {
                failed = true
                put(failed(e, ex.message ?: ex.toString()))
                notify("Vector charts region ${e.ncds} failed", ex.message ?: "")
            }
        }
        return failed
    }

    private fun save(e: Entry) {
        put(Entry(e.ncds, State.SAVING, e.created, e.files, uris = e.uris, names = e.names))
        for (f in e.files) {
            val src = File(dir, f.file)
            if (!src.isFile) throw Exception("the downloaded ${f.file} is gone; download it again")
            if (f.sha256.isNotEmpty() && sha256(src) != f.sha256) throw Exception("${f.file} is damaged (checksum); download it again")
        }
        val uris = LinkedHashMap<String, String>()
        val names = LinkedHashMap<String, String>()
        for (f in e.files) {
            val src = File(dir, f.file)
            // "Ncds_04_depth" + "depth.obf": OsmAnd tells a depth map by its .depth.obf ending
            val ext = if (f.file.endsWith(".depth.obf")) "depth.obf" else "obf"
            val base = f.file.removeSuffix(".$ext")
            val (uri, name) = DownloadFiles.target(ctx, ChartMaps.SUBDIR, base, ext, MIME)
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 20) } }
            src.delete()
            uris[f.kind] = uri.toString()
            names[f.kind] = name
        }
        prefs.edit().putString("vector-created-${e.ncds}", e.created).apply()
        put(Entry(e.ncds, State.READY, e.created, e.files, uris = uris, names = names))
        notify("Vector charts region ${e.ncds} ready for OsmAnd",
            "Download/${ChartMaps.SUBDIR}/${names.values.joinToString(", ")}: open them in OsmAnd")
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

    private fun notify(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("charts", "Chart maps", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(title.hashCode(), Notification.Builder(ctx, "charts")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).build())
    }
}
