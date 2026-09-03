package com.guardpulse.parentcontrol.tv.usage

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.guardpulse.parentcontrol.shared.DateKeys
import com.guardpulse.parentcontrol.shared.PolicyConstants
import com.guardpulse.parentcontrol.tv.fallback.LiveForegroundSession
import com.guardpulse.parentcontrol.tv.system.SystemTimeGuard
import java.util.Calendar
import java.util.TimeZone

class UsageTracker(private val context: Context) {
    private val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun rawUsageMillisToday(): Map<String, Long> {
        if (!hasUsageAccess()) return emptyMap()
        val now = System.currentTimeMillis()
        val today = DateKeys.dayKeyUtc(SystemTimeGuard.now())
        synchronized(rawCacheLock) {
            if (today == rawCacheDay && now - rawCacheAt < RAW_USAGE_CACHE_TTL_MS) {
                return rawCacheValue
            }
        }
        // Usage day = UTC day of the guarded clock, matching the day keys used by
        // the local ledger and dailyBlocks; the query window starts at that day's
        // UTC midnight so a timezone shift cannot reset the daily limit.
        val start = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = SystemTimeGuard.now()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
        val usageByPackage = mutableMapOf<String, Long>()
        stats
            .filter { it.totalTimeInForeground > 0 }
            .forEach { stat ->
                val packageName = canonicalUsagePackage(stat.packageName)
                usageByPackage[packageName] = (usageByPackage[packageName] ?: 0L) + stat.totalTimeInForeground
            }
        val result: Map<String, Long> = usageByPackage
        synchronized(rawCacheLock) {
            rawCacheDay = today
            rawCacheAt = now
            rawCacheValue = result
        }
        return result
    }

    fun effectiveUsageMillisToday(
        liveSession: LiveForegroundSession? = null,
        committedUsageMs: Map<String, Long> = emptyMap(),
        now: Long = System.currentTimeMillis()
    ): Map<String, Long> {
        val baseline = rawUsageMillisToday().toMutableMap()
        committedUsageMs.forEach { (packageName, usageMs) ->
            baseline[packageName] = maxOf(baseline[packageName] ?: 0L, usageMs)
        }
        return applyLiveForegroundSession(baseline, liveSession, now, DateKeys.dayKeyUtc(SystemTimeGuard.now()))
    }

    fun usageMinutesToday(liveSession: LiveForegroundSession? = null): Map<String, Long> {
        return effectiveUsageMillisToday(liveSession)
            .mapValues { (_, usageMs) -> (usageMs / MILLIS_PER_MINUTE).coerceAtLeast(0L) }
    }

    private fun canonicalUsagePackage(packageName: String): String {
        return PolicyConstants.sourceLockPolicyPackage(packageName) ?: packageName
    }

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L

        // The system batches usage stats anyway; the live-session extrapolation in
        // applyLiveForegroundSession keeps the effective number fresh between queries.
        private const val RAW_USAGE_CACHE_TTL_MS = 30_000L
        private val rawCacheLock = Any()
        private var rawCacheDay: String? = null
        private var rawCacheAt: Long = 0L
        private var rawCacheValue: Map<String, Long> = emptyMap()

        internal fun applyLiveForegroundSession(
            baselineUsageMs: Map<String, Long>,
            liveSession: LiveForegroundSession?,
            now: Long,
            today: String
        ): Map<String, Long> {
            if (liveSession == null || liveSession.dayKey != today) return baselineUsageMs
            val observedEnd = minOf(now, liveSession.lastObservedAt + 1_500L)
            val elapsedMs = (observedEnd - liveSession.startedAt).coerceAtLeast(0L)
            val liveUsageMs = liveSession.baselineUsageMs + elapsedMs
            val currentUsageMs = baselineUsageMs[liveSession.packageName] ?: 0L
            val effectiveUsageMs = maxOf(currentUsageMs, liveUsageMs)
            return baselineUsageMs.toMutableMap().apply {
                put(liveSession.packageName, effectiveUsageMs)
            }
        }
    }
}
