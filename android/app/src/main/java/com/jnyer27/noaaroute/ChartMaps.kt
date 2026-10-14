package com.jnyer27.noaaroute

import android.Manifest
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * NOAA Chart Display Service chart maps (paper-chart style raster MBTiles, one per NCDS
 * region) for OsmAnd: downloaded by Android's DownloadManager into app storage, converted
 * to OsmAnd's .sqlitedb exactly as noaa-charts.sh does, and published to
 * Download/noaa-charts. Several can be combined into one map (OsmAnd shows one overlay).
 * State lives in preferences so a download finishing while the app is closed is still
 * converted (ChartDownloadReceiver -> ChartJob).
 */
class ChartMaps(private val ctx: Context) {
    companion object {
        const val SUBDIR = "noaa-charts"
        private const val JOB_ID = 2
        private const val MIME = "application/octet-stream"
        fun url(code: String) = "https://distribution.charts.noaa.gov/ncds/mbtiles/ncds_$code.mbtiles"
        /** Key of a combined map: "m:" + the region codes, sorted. */
        fun mergedKey(codes: List<String>) = "m:" + codes.sorted().joinToString("_")

        /**
         * Run conversions and combinations now, in ChartJob. Not while one is pending or
         * running: rescheduling would stop it mid-conversion, and it keeps going until
         * nothing is left anyway.
         */
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, ChartJob::class.java)).setOverrideDeadline(0).build())
        }
    }

    enum class State { DOWNLOADING, DOWNLOADED, CONVERTING, MERGING, READY, FAILED }

    /** One chart map: a region ("04") or a combined map ("m:04_05"). */
    class Entry(val key: String, val state: State, val download: Long = -1, val uri: String? = null,
                val file: String? = null, val size: Long = 0, val mbtilesOnly: Boolean = false,
                val error: String? = null) {
        val codes get() = if (key.startsWith("m:")) key.removePrefix("m:").split("_") else listOf(key)
        val merged get() = key.startsWith("m:")
        fun json(): JSONObject = JSONObject().put("state", state.name).put("download", download).put("uri", uri)
            .put("file", file).put("size", size).put("mbtilesOnly", mbtilesOnly).put("error", error)
    }

    private val prefs = ctx.getSharedPreferences("noaaroute", Context.MODE_PRIVATE)
    private val dm = ctx.getSystemService(DownloadManager::class.java)
    val dir: File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "charts").apply { mkdirs() }

    @Synchronized
    fun all(): Map<String, Entry> {
        val j = try { JSONObject(prefs.getString("charts", "{}")!!) } catch (_: Exception) { JSONObject() }
        val out = LinkedHashMap<String, Entry>()
        for (k in j.keys()) {
            val e = j.getJSONObject(k)
            out[k] = Entry(k, State.valueOf(e.getString("state")), e.optLong("download", -1),
                e.optString("uri").ifEmpty { null }.takeIf { it != "null" }, e.optString("file").ifEmpty { null }.takeIf { it != "null" },
                e.optLong("size"), e.optBoolean("mbtilesOnly"), e.optString("error").ifEmpty { null }.takeIf { it != "null" })
        }
        return out
    }

    @Synchronized
    private fun put(e: Entry?, key: String = e!!.key) {
        val j = try { JSONObject(prefs.getString("charts", "{}")!!) } catch (_: Exception) { JSONObject() }
        if (e == null) j.remove(key) else j.put(key, e.json())
        prefs.edit().putString("charts", j.toString()).apply()
    }

    fun get(key: String) = all()[key]

    fun freeBytes(): Long = StatFs(dir.path).availableBytes

    /** Size of a region's chart file on NOAA's server (blocking). */
    fun remoteSize(code: String): Long {
        val c = URL(url(code)).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"
        c.connectTimeout = 20000
        c.readTimeout = 30000
        try {
            if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode} for ncds_$code.mbtiles")
            return c.contentLengthLong
        } finally { c.disconnect() }
    }

    /** Start downloading a region's chart map; mbtilesOnly skips the OsmAnd conversion. */
    fun start(code: String, size: Long, mbtilesOnly: Boolean, mobileData: Boolean) {
        File(dir, "ncds_$code.mbtiles").delete()
        val req = DownloadManager.Request(Uri.parse(url(code)))
            .setTitle("NOAA chart map, region $code")
            .setDescription("For OsmAnd (NOAA Route)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, null, "charts/ncds_$code.mbtiles")
            .setAllowedOverMetered(mobileData)
            .setAllowedOverRoaming(false)
        put(Entry(code, State.DOWNLOADING, dm.enqueue(req), size = size, mbtilesOnly = mbtilesOnly))
    }

    /** (bytes so far, total) of a running download, or null. */
    fun progress(e: Entry): Pair<Long, Long>? {
        if (e.state != State.DOWNLOADING) return null
        dm.query(DownloadManager.Query().setFilterById(e.download))?.use { c ->
            if (!c.moveToFirst()) return null
            return c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)) to
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        }
        return null
    }

    /** A download ended (from ChartDownloadReceiver, or noticed by [reconcile]). */
    fun downloadDone(id: Long) {
        val e = all().values.firstOrNull { it.download == id && it.state == State.DOWNLOADING } ?: return
        var ok = false
        var reason = ""
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) {
                ok = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
                reason = "download failed (code ${c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))})"
            } else reason = "download was cancelled"
        }
        put(if (ok) Entry(e.key, State.DOWNLOADED, size = e.size, mbtilesOnly = e.mbtilesOnly)
            else Entry(e.key, State.FAILED, size = e.size, error = reason))
        if (ok) schedule(ctx)
    }

    /** Fix up state after the app was away: finished downloads, maps deleted in Files. */
    fun reconcile() {
        for (e in all().values) when (e.state) {
            State.DOWNLOADING -> {
                var status = -1
                dm.query(DownloadManager.Query().setFilterById(e.download))?.use { c ->
                    if (c.moveToFirst()) status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                }
                if (status == -1 || status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) downloadDone(e.download)
            }
            State.DOWNLOADED, State.CONVERTING, State.MERGING -> schedule(ctx) // finish (or redo) the work
            State.READY -> {
                val gone = try { ctx.contentResolver.openFileDescriptor(Uri.parse(e.uri), "r")?.close(); false } catch (_: Exception) { true }
                if (gone) put(null, e.key)
            }
            State.FAILED -> {}
        }
    }

    fun cancel(key: String) {
        val e = get(key) ?: return
        if (e.state == State.DOWNLOADING) dm.remove(e.download)
        File(dir, "ncds_${e.key}.mbtiles").delete()
        put(null, key)
    }

    /** Delete a published map (the copy OsmAnd imported stays in OsmAnd). */
    fun delete(key: String) {
        val e = get(key) ?: return
        e.uri?.let { try { ctx.contentResolver.delete(Uri.parse(it), null, null) } catch (_: Exception) { } }
        cancel(key)
    }

    /** Ask to combine these ready regions into one map (ChartJob does it). */
    fun requestMerge(codes: List<String>) {
        put(Entry(mergedKey(codes), State.MERGING))
        schedule(ctx)
    }

    // ------------------------------------------------------------ the work (ChartJob)

    /**
     * Convert finished downloads and build requested combinations until none is left
     * (a download may finish while another converts); true if anything failed. A
     * CONVERTING or MERGING entry is one an earlier run did not finish: redone from scratch.
     */
    fun runPending(): Boolean {
        var failed = false
        val done = HashSet<String>()
        while (true) {
            val e = all().values.firstOrNull {
                it.key !in done && it.state in setOf(State.DOWNLOADED, State.CONVERTING, State.MERGING)
            } ?: break
            done.add(e.key)
            try {
                if (e.state == State.MERGING) merge(e) else convert(e)
            } catch (ex: Exception) {
                failed = true
                put(Entry(e.key, State.FAILED, size = e.size, error = ex.message ?: ex.toString()))
                notify("Chart map ${label(e)} failed", ex.message ?: "")
            }
        }
        return failed
    }

    private fun label(e: Entry) = if (e.merged) "regions ${e.codes.joinToString(" + ")}" else "region ${e.key}"

    private fun convert(e: Entry) {
        val src = File(dir, "ncds_${e.key}.mbtiles")
        if (!src.isFile) throw Exception("the downloaded file is gone; download it again")
        put(Entry(e.key, State.CONVERTING, size = e.size, mbtilesOnly = e.mbtilesOnly))
        if (e.mbtilesOnly) {
            val (uri, name) = publish(src, "ncds_${e.key}", "mbtiles")
            src.delete()
            put(Entry(e.key, State.READY, uri = uri.toString(), file = name, size = e.size, mbtilesOnly = true))
            notify("Chart map region ${e.key} downloaded", "Download/$SUBDIR/$name")
            return
        }
        val tmp = File(dir, "ncds_${e.key}.sqlitedb.tmp")
        tmp.delete()
        // Same SQL as noaa-charts.sh convert(), which mirrors tarwirdur/mbtiles2osmand:
        // y = 2^zoom - 1 - tile_row (TMS to XYZ), z = 17 - zoom (OsmAnd's inverted zoom),
        // and an info table with only maxzoom/minzoom (extra columns: OsmAnd shows nothing)
        SQLiteDatabase.openOrCreateDatabase(tmp, null).use { db ->
            db.rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
            db.execSQL("PRAGMA synchronous=OFF")
            db.execSQL("CREATE TABLE tiles (x int, y int, z int, s int, image blob, PRIMARY KEY (x,y,z,s))")
            db.execSQL("CREATE TABLE info (maxzoom Int, minzoom Int)")
            db.execSQL("ATTACH DATABASE ? AS mb", arrayOf(src.path))
            db.execSQL("INSERT INTO tiles (x, y, z, s, image) SELECT tile_column, ((1 << zoom_level) - 1 - tile_row), " +
                "17 - zoom_level, 0, tile_data FROM mb.tiles")
            db.execSQL("DETACH DATABASE mb")
            db.execSQL("INSERT INTO info (maxzoom, minzoom) SELECT max(z), min(z) FROM tiles")
        }
        src.delete()
        val (uri, name) = publish(tmp, "ncds_${e.key}", "sqlitedb")
        tmp.delete()
        put(Entry(e.key, State.READY, uri = uri.toString(), file = name, size = File(DownloadFiles.path(SUBDIR, name)).length()))
        notify("Chart map region ${e.key} ready for OsmAnd", "Download/$SUBDIR/$name: open it in OsmAnd")
    }

    private fun merge(e: Entry) {
        if (Build.VERSION.SDK_INT < 30) throw Exception("combining chart maps needs Android 11 or newer")
        val all = all()
        val srcs = e.codes.map { c ->
            val r = all[c]?.takeIf { it.state == State.READY && !it.mbtilesOnly && it.file != null }
                ?: throw Exception("region $c is not downloaded for OsmAnd")
            File(DownloadFiles.path(SUBDIR, r.file!!)).also { if (!it.canRead()) throw Exception("cannot read ${it.name}") }
        }
        val base = "ncds_" + e.codes.sorted().joinToString("_")
        val tmp = File(dir, "$base.sqlitedb.tmp")
        tmp.delete()
        // Same as noaa-charts.sh merge(): where regions overlap, keep the larger image
        // (usually the one with more chart on it rather than a mostly blank edge tile)
        SQLiteDatabase.openOrCreateDatabase(tmp, null).use { db ->
            db.rawQuery("PRAGMA journal_mode=OFF", null).use { it.moveToFirst() }
            db.execSQL("PRAGMA synchronous=OFF")
            db.execSQL("CREATE TABLE tiles (x int, y int, z int, s int, image blob, PRIMARY KEY (x,y,z,s))")
            db.execSQL("CREATE TABLE info (maxzoom Int, minzoom Int)")
            for (f in srcs) {
                db.execSQL("ATTACH DATABASE ? AS src", arrayOf(f.path))
                db.execSQL("INSERT INTO tiles (x, y, z, s, image) SELECT x, y, z, s, image FROM src.tiles WHERE true " +
                    "ON CONFLICT (x, y, z, s) DO UPDATE SET image = excluded.image WHERE length(excluded.image) > length(tiles.image)")
                db.execSQL("DETACH DATABASE src")
            }
            db.execSQL("INSERT INTO info (maxzoom, minzoom) SELECT max(z), min(z) FROM tiles")
        }
        val (uri, name) = publish(tmp, base, "sqlitedb")
        tmp.delete()
        put(Entry(e.key, State.READY, uri = uri.toString(), file = name, size = File(DownloadFiles.path(SUBDIR, name)).length()))
        notify("Combined chart map ready for OsmAnd", "Download/$SUBDIR/$name (regions ${e.codes.joinToString(" + ")})")
    }

    /** Copy a finished file to Download/noaa-charts. */
    private fun publish(f: File, base: String, ext: String): Pair<Uri, String> {
        val (uri, name) = DownloadFiles.target(ctx, SUBDIR, base, ext, MIME)
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { out -> f.inputStream().use { it.copyTo(out, 1 shl 20) } }
        return uri to name
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
