package com.jnyer27.noaaroute

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.io.File

/**
 * Tide predictions on the phone ([TideCache] in :core): the stations inside the downloaded
 * regions' route data are fetched in the background on Wi-Fi ([TideJob]), a year at a time,
 * and refreshed when under 30 days are left; the stations a planned trip needs are fetched
 * on any network while planning (TideCache.forRoute, about 70 KB each). Blocking calls: run them
 * off the UI thread.
 */
class TideStore(private val ctx: Context) {
    companion object {
        private const val JOB_ID = 3

        /** Fetch what the downloaded regions need, on Wi-Fi (or any unmetered network), soon. */
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, TideJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .build())
        }
    }

    val cache = TideCache(File(ctx.filesDir, "tides"))

    /** Stations inside the boxes of the regions whose route data is downloaded. */
    fun wanted(stations: List<TideStation> = cache.stations()): List<TideStation> {
        val packs = PackStore(ctx)
        val have = packs.installed().keys
        val boxes = packs.cachedCatalog().filter { it.id in have }.map { it.bbox }
        return stations.filter { s -> boxes.any { b -> s.lon in b[0]..b[2] && s.lat in b[1]..b[3] } }
    }

    /**
     * Fetch the station list if due, then every wanted station whose year is missing or
     * running out. Returns how many could not be fetched.
     */
    fun updateAll(): Int {
        val stations = cache.refreshStations()
        // Nearest the boater first: the last GPS fix, else the trip's start
        val todo = Tides.byDistance(wanted(stations), home()).map { it.id }.filter { cache.needsFetch(it) }
        return cache.ensureAll(todo).size
    }

    /** The last known position (if location is allowed), else the start saved by the planning screen. */
    private fun home(): LatLon? {
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                val lm = ctx.getSystemService(android.location.LocationManager::class.java)
                listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
                    .firstNotNullOfOrNull { lm.getLastKnownLocation(it) }
                    ?.let { return LatLon(it.latitude, it.longitude) }
            } catch (_: Exception) { }
        }
        val start = ctx.getSharedPreferences("noaaroute", Context.MODE_PRIVATE).getString("start", null)
        return start?.let { Coords.parse(it) }
    }
}

/** Background tide fetch for the downloaded regions (see [TideStore]). */
class TideJob : JobService() {
    @Volatile private var thread: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        thread = Thread {
            // Stations NOAA has no predictions for would fail every time: no retry for those,
            // the next app start or daily update fills real gaps
            val retry = try { TideStore(this).updateAll(); false } catch (_: Exception) { true }
            jobFinished(params, retry)
        }.apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        thread?.interrupt()
        return true
    }
}
