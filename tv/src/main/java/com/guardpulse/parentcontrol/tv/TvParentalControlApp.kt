package com.guardpulse.parentcontrol.tv

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.guardpulse.parentcontrol.shared.FirebaseConfiguration
import com.guardpulse.parentcontrol.shared.FirebaseRuntime
import com.guardpulse.parentcontrol.tv.fallback.FallbackStateStore
import com.guardpulse.parentcontrol.tv.sync.PolicyReconcileWorker
import com.guardpulse.parentcontrol.tv.system.StrictProtectionStarter
import java.util.concurrent.TimeUnit

class TvParentalControlApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseRuntime.initialize(
            this,
            FirebaseConfiguration(
                appId = BuildConfig.FIREBASE_APP_ID,
                apiKey = BuildConfig.FIREBASE_API_KEY,
                projectId = BuildConfig.FIREBASE_PROJECT_ID,
                databaseUrl = BuildConfig.FIREBASE_DATABASE_URL
            )
        )
        FallbackStateStore(this).clearSetupVisitUnlock()
        val work = PeriodicWorkRequest.Builder(
            PolicyReconcileWorker::class.java,
            15,
            TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "policy-reconcile",
            // KEEP, not UPDATE: UPDATE resets the 15-minute schedule on EVERY
            // process start, so a TV whose process restarts more often than
            // every 15 minutes could starve the reconcile worker entirely.
            // The request spec never changes; StrictProtectionStarter.recover
            // below still runs a full pass on every start.
            ExistingPeriodicWorkPolicy.KEEP,
            work
        )
        runCatching { StrictProtectionStarter.recover(this) }
    }
}
