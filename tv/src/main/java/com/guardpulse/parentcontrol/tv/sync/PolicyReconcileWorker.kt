package com.guardpulse.parentcontrol.tv.sync

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.guardpulse.parentcontrol.tv.system.TvServiceStarter

class PolicyReconcileWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        // Mirrors StrictProtectionStarter.recover: a foreground-service start from
        // background-restricted state throws on Android 12+; WorkManager then
        // retries with its own backoff, so swallow and report retry.
        return runCatching {
            TvServiceStarter.start(applicationContext, TvSyncService.ACTION_RECONCILE)
            Result.success()
        }.getOrElse {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
