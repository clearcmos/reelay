package com.clearcmos.reelay

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Prunes the clip cache about an hour after a handoff, so cleanup does not wait for the next run. */
class CleanupJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        ClipCache.of(this).prune()
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 1

        fun schedule(context: Context) {
            val job =
                JobInfo
                    .Builder(JOB_ID, ComponentName(context, CleanupJobService::class.java))
                    .setMinimumLatency(ClipCache.RETENTION_MS)
                    .setOverrideDeadline(ClipCache.RETENTION_MS * 2)
                    .build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        }
    }
}
