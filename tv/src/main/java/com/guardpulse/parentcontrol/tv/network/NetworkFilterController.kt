package com.guardpulse.parentcontrol.tv.network

import android.content.Context
import android.content.Intent

object NetworkFilterController {
    fun prepareIntent(@Suppress("UNUSED_PARAMETER") context: Context): Intent? = null

    fun isPrepared(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

    // The VPN/network-filter feature is not implemented on TV; the controller
    // exists so the state schema keeps reporting an explicit "disabled" status.
    // Writes happen only when the stored status differs — this used to rewrite
    // two prefs keys on every 30-second policy tick for no effect.
    fun applyBlockedPackages(context: Context, @Suppress("UNUSED_PARAMETER") packages: Set<String>): NetworkFilterStatus {
        val appContext = context.applicationContext
        val status = disabledStatus()
        if (NetworkFilterStore.status(appContext) == status &&
            NetworkFilterStore.blockedPackages(appContext).isEmpty()
        ) {
            return status
        }
        NetworkFilterStore.saveBlockedPackages(appContext, emptySet())
        NetworkFilterStore.saveStatus(appContext, status)
        return status
    }

    fun requestApply(context: Context) {
        applyBlockedPackages(context, emptySet())
    }

    fun refreshPreparedStatus(context: Context): NetworkFilterStatus {
        return applyBlockedPackages(context, emptySet())
    }

    private fun disabledStatus() = NetworkFilterStatus(
        prepared = false,
        active = false,
        blockedCount = 0,
        lastError = null
    )
}
