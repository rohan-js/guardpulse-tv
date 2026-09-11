package com.guardpulse.parentcontrol.tv.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Process-wide screen state. The TV app runs 24/7 but does nothing useful
 * while the display is off: the accessibility poll, media probes, usage
 * sessions and per-app reconcile passes all pause on SCREEN_OFF and resume on
 * SCREEN_ON. Enforcement deadlines (safe mode, timed unlocks, PIN lockout)
 * are timestamp-based, so pausing the polling loops cannot extend or shorten
 * them. If a given TV ROM never broadcasts screen events, everything simply
 * keeps running exactly as before.
 */
object ScreenState {
    @Volatile
    var off: Boolean = false
}

/**
 * Registers for SCREEN_ON/SCREEN_OFF and invokes [onChanged] with the new
 * off-state. Returns the receiver for symmetric unregister. ACTION_SCREEN_*
 * are protected non-exported broadcasts — no receiver permission needed.
 */
fun registerScreenStateReceiver(context: Context, onChanged: (Boolean) -> Unit): BroadcastReceiver {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    ScreenState.off = true
                    onChanged(true)
                }
                Intent.ACTION_SCREEN_ON -> {
                    ScreenState.off = false
                    onChanged(false)
                }
            }
        }
    }
    context.registerReceiver(
        receiver,
        IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
    )
    return receiver
}
