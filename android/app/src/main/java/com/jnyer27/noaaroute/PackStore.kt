package com.jnyer27.noaaroute

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloaded route packs, the published catalog and the automatic-download
 * setting, shared by the app and the background updater (UpdateJob). Blocking
 * calls: run them off the UI thread.
 */
class PackStore(private val ctx: Context) {
    companion object {
        const val PACK_BASE = "https://github.com/jnyer27/noaa-osmand-charts/releases/download/route-pack/"
        private const val JOB_ID = 1

        /** Daily background update on unmetered networks, kept across reboots. */
        fun schedule(ctx: Context, on: Boolean) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (!on) { js.cancel(JOB_ID); return }
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPeriodic(24L * 3600 * 1000)
                .setPersisted(true)
                .build())
        }
    }

    private val prefs = ctx.getSharedPreferences("noaaroute", Context.MODE_PRIVATE)
    val dir = File(ctx.filesDir, "areas").apply { mkdirs() }
    private val catalogFile = File(ctx.filesDir, "catalog.json")

    /** Download and update regions automatically on Wi-Fi: true, false, or null = not asked yet. */
    var auto: Boolean?
        get() = if (prefs.contains("auto")) prefs.getBoolean("auto", false) else null
        set(v) {
            if (v == null) prefs.edit().remove("auto").apply() else prefs.edit().putBoolean("auto", v).apply()
            schedule(ctx, v == true)
        }

    fun unmetered(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun httpGet(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.instanceFollowRedirects = true
        if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode} for $url")
        return c
    }

    /** The published catalog; the last copy fetched stays usable offline. */
    fun fetchCatalog(): List<AreaInfo> {
        val text = httpGet(PACK_BASE + "catalog.json").inputStream.bufferedReader().readText()
        val list = Regions.parseCatalog(text)
        catalogFile.writeText(text)
        return list
    }

    fun cachedCatalog(): List<AreaInfo> = try { Regions.parseCatalog(catalogFile.readText()) } catch (_: Exception) { emptyList() }

    /** Installed pack ids and build times, read from the files' headers. */
    fun installed(): Map<String, String> {
        val out = HashMap<String, String>()
        for (f in dir.listFiles { f -> f.name.endsWith(".bin") }.orEmpty()) {
            try { Pack(f).use { out[it.areaId] = it.created } } catch (_: Exception) { }
        }
        return out
    }

    /** Download a pack (checked against the catalog's SHA-256) and put it in place. */
    fun download(a: AreaInfo, progress: (Long, Long) -> Unit = { _, _ -> }) {
        // A name of our own: the app and the background job may both be at it
        val part = File(dir, "${a.id}.${System.nanoTime()}.part")
        try {
            val md = MessageDigest.getInstance("SHA-256")
            httpGet(PACK_BASE + a.file).inputStream.use { inp ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var got = 0L
                    var last = 0L
                    while (true) {
                        val r = inp.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        md.update(buf, 0, r)
                        got += r
                        if (got - last > 500_000) { last = got; progress(got, a.size) }
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != a.sha256) throw Exception("download check failed, try again")
            // Rename over the old file: anything still reading it keeps the old copy until reopened
            if (!part.renameTo(File(dir, "${a.id}.bin"))) throw Exception("could not save ${a.file}")
        } finally {
            part.delete()
        }
    }

    fun delete(id: String) { File(dir, "$id.bin").delete() }

    /** Delete replaced packs whose replacements are all installed. True if anything went. */
    fun dropReplaced(catalog: List<AreaInfo>): Boolean {
        var gone = false
        for ((old, missing) in Regions.replacements(catalog, installed().keys)) {
            if (missing.isEmpty()) { delete(old); gone = true }
        }
        return gone
    }

    /**
     * Background work for UpdateJob: newer builds of installed packs, then the
     * replacements of a replaced pack, then drop the replaced pack.
     */
    fun updateAll(): Int {
        val catalog = fetchCatalog()
        val have = installed()
        var n = 0
        for (a in catalog) {
            val built = have[a.id] ?: continue
            if (a.created > built && !a.deprecated) { download(a); n++ }
        }
        for ((_, missing) in Regions.replacements(catalog, installed().keys)) for (a in missing) { download(a); n++ }
        dropReplaced(catalog)
        return n
    }

    /** Changes when packs are added, updated or deleted (the background job may do it). */
    fun signature() = dir.listFiles { f -> f.name.endsWith(".bin") }.orEmpty()
        .sortedBy { it.name }.joinToString { "${it.name}:${it.lastModified()}:${it.length()}" }
}
