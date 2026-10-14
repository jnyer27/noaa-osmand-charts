package com.jnyer27.noaaroute

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * Daily on Wi-Fi (or any unmetered network) when automatic downloads are on:
 * fetch NOAA's weekly route pack builds for installed regions and finish
 * swapping a replaced area for its regions. The app reopens its charts when it
 * sees the files changed.
 */
class UpdateJob : JobService() {
    @Volatile private var thread: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        thread = Thread {
            var retry = false
            try {
                PackStore(this).updateAll()
            } catch (_: Exception) {
                retry = true // offline or a failed download: the scheduler tries again later
            }
            jobFinished(params, retry)
        }.apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        thread?.interrupt()
        return true
    }
}
