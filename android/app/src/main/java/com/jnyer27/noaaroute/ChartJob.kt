package com.jnyer27.noaaroute

import android.app.DownloadManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Converts finished chart map downloads, builds combined maps and saves vector chart maps, off the UI. */
class ChartJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            ChartMaps(this).runPending()
            VectorMaps(this).runPending()
            ChartTileStore(this).runPending()
            jobFinished(params, false)
        }.start()
        return true
    }

    // Interrupted (time limit): the entry is still CONVERTING/MERGING, so the next
    // reconcile() or a new job redoes it from the start
    override fun onStopJob(params: JobParameters) = true
}

/** DownloadManager says a download ended; chart maps or vector chart maps take it from there. */
class ChartDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id >= 0 && !ChartTileStore(ctx).downloadDone(id) && !VectorMaps(ctx).downloadDone(id))
            ChartMaps(ctx).downloadDone(id)
    }
}
