package com.guardpulse.parentcontrol.tv.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.guardpulse.parentcontrol.shared.PolicyConstants
import com.guardpulse.parentcontrol.tv.fallback.FallbackProtection
import com.guardpulse.parentcontrol.tv.fallback.FallbackStateStore
import com.guardpulse.parentcontrol.tv.sync.TamperEventQueue
import com.guardpulse.parentcontrol.tv.system.SystemTimeGuard

class TvDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val appContext = context.applicationContext
        // The DPM confirm dialog stays interactive while the advisory lock is
        // shown, so press-HOME-and-return would otherwise bypass the PIN. The
        // gate re-locks any settings-package foreground for the next 10 minutes
        // (FallbackProtection.shouldLock) while PIN-verified unlocks still pass.
        SystemTimeGuard.initialize(appContext)
        FallbackStateStore(appContext).grantAdminChangePendingGate()
        uploadTamper(
            appContext,
            PolicyConstants.TAMPER_ADMIN_DISABLE_REQUESTED,
            "Device Admin deactivation was requested on the TV"
        )
        FallbackProtection.openLock(
            appContext,
            context.packageName,
            PolicyConstants.TAMPER_ADMIN_DISABLE_REQUESTED
        )
        return "Device protection will be disabled. Enter the parent PIN before changing this setting."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        uploadTamper(
            context.applicationContext,
            PolicyConstants.TAMPER_ADMIN_DISABLED,
            "Device Admin was disabled on the TV"
        )
    }

    private fun uploadTamper(context: Context, type: String, message: String) {
        TamperEventQueue.enqueue(context, type, message)
    }
}
