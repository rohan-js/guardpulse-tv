package com.guardpulse.parentcontrol.tv.system

import android.content.Context
import android.util.Log
import com.guardpulse.parentcontrol.tv.network.NetworkFilterController
import com.guardpulse.parentcontrol.tv.sync.TvSyncService

object StrictProtectionStarter {
    private const val TAG = "GuardPulseProtection"

    fun recover(context: Context, action: String? = TvSyncService.ACTION_RECONCILE) {
        val appContext = context.applicationContext
        runCatching { TvServiceStarter.start(appContext, action) }
            .onFailure {
                // On Android 12+ background-start restrictions can make this
                // throw (ForegroundServiceStartNotAllowedException); the WorkManager
                // path is the recovery net, so never swallow it silently.
                Log.w(TAG, "Protected service start was not allowed", it)
            }
        runCatching {
            NetworkFilterController.applyBlockedPackages(appContext, emptySet())
        }.onFailure { Log.w(TAG, "Network filter apply failed", it) }
    }
}
