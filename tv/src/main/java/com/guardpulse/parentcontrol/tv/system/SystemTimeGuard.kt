package com.guardpulse.parentcontrol.tv.system

import android.content.Context
import android.content.SharedPreferences

/**
 * Tamper-resistant clock for everything that must not move when the device
 * clock or timezone is changed from Settings: unlock deadlines, PIN backoff,
 * and the daily usage day key.
 *
 * now() = max(persisted monotonic floor, device clock + server offset). The
 * floor only ever moves forward, so rolling the device clock back cannot
 * unwind a deadline that was already granted. The server offset is refreshed
 * from [com.guardpulse.parentcontrol.shared.FirebaseServerClock] on each
 * heartbeat so the wall time tracks the RTDB server while the offset is known;
 * with no Firebase the guard degrades to the floored device clock.
 *
 * Safe direction under inflation: if the clock runs ahead, deadlines granted
 * from an inflated now() simply expire sooner in real time — never later.
 */
object SystemTimeGuard {
    private const val PREFS = "time_guard"
    private const val KEY_FLOOR = "floorMs"
    private const val FLOOR_PERSIST_MIN_STEP_MS = 60_000L

    @Volatile
    private var offsetMs: Long = 0L

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Idempotent; called by TvSyncService and AppMonitorAccessibilityService. */
    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    /** Called by TvSyncService with FirebaseServerClock.offsetMillis() each heartbeat. */
    fun setServerOffset(offsetMs: Long) {
        this.offsetMs = offsetMs
    }

    fun now(): Long {
        val deviceNow = System.currentTimeMillis() + offsetMs
        val prefs = prefs ?: return deviceNow
        val floor = prefs.getLong(KEY_FLOOR, 0L)
        val guarded = maxOf(floor, deviceNow)
        if (guarded - floor >= FLOOR_PERSIST_MIN_STEP_MS) {
            prefs.edit().putLong(KEY_FLOOR, guarded).apply()
        }
        return guarded
    }
}
