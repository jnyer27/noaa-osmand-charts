package com.jnyer27.noaaroute

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File

/**
 * Downloaded route packs, the published catalog and the automatic-download
 * setting, shared by the app and the background updater (UpdateJob). The packs
 * and catalog are [PackCache] (in :core, shared with the command-line planner);
 * this adds the Android parts: the setting, the daily job and the network check.
 * Blocking calls: run them off the UI thread.
 */
class PackStore(private val ctx: Context) {
    companion object {
        val PACK_BASE: String = BuildConfig.PACK_BASE
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
    private val cache = PackCache(File(ctx.filesDir, "areas"), File(ctx.filesDir, "catalog.json"), PACK_BASE)
    val dir: File get() = cache.dir

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

    fun fetchCatalog(): List<AreaInfo> = cache.fetchCatalog()
    fun cachedCatalog(): List<AreaInfo> = cache.cachedCatalog()
    fun installed(): Map<String, String> = cache.installed()
    fun download(a: AreaInfo, progress: (Long, Long) -> Unit = { _, _ -> }) = cache.download(a, progress)
    fun delete(id: String) = cache.delete(id)
    fun dropReplaced(catalog: List<AreaInfo>): Boolean = cache.dropReplaced(catalog)

    /** Background work for UpdateJob (see [PackCache.updateAll]). */
    fun updateAll(): Int = cache.updateAll()
    fun signature(): String = cache.signature()
}
