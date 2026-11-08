package com.realme.ota

import android.app.job.JobParameters
import android.app.job.JobService

class OtaCheckJobService : JobService() {
    @Volatile
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val t = Thread {
            try {
                val ctx = applicationContext
                val rel = OtaEngine.checkLatestRelease(ctx)
                if (rel != null && OtaEngine.shouldOfferRelease(ctx, rel)) {
                    OtaEngine.postUpdateAvailableNotification(ctx, rel)
                }
            } catch (_: Throwable) {
                // Silent background check retry on next interval
            } finally {
                jobFinished(params, false)
            }
        }
        worker = t
        t.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        worker?.interrupt()
        return true
    }
}
