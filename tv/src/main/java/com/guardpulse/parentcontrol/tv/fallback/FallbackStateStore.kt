package com.guardpulse.parentcontrol.tv.fallback

import android.content.Context
import android.provider.Settings
import com.guardpulse.parentcontrol.shared.DateKeys
import com.guardpulse.parentcontrol.shared.PinHasher
import com.guardpulse.parentcontrol.shared.PolicyConstants
import com.guardpulse.parentcontrol.tv.security.SecureValueStore
import com.guardpulse.parentcontrol.tv.system.SystemTimeGuard
import org.json.JSONObject

data class PinRecord(
    val salt: String,
    val hash: String,
    val version: Int = PinHasher.LEGACY_VERSION,
    val algorithm: String? = null,
    val iterations: Int? = null,
    val updatedAt: Long = 0L
)

data class FallbackDecision(
    val locked: Boolean,
    val reason: String? = null,
    val policyPackage: String? = null,
    val settingsSectionKey: String? = null
)

data class LiveForegroundSession(
    val packageName: String,
    val startedAt: Long,
    val baselineUsageMs: Long,
    val dayKey: String,
    val lastObservedAt: Long = startedAt,
    val bootCount: Int = 0
)

class FallbackStateStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("fallback_state", Context.MODE_PRIVATE)
    private val secureStore = SecureValueStore(
        context,
        "fallback_state",
        "guardpulse.fallback.secrets"
    )

    fun savePin(pin: PinRecord?) {
        if (pin == null) {
            secureStore.put("pin", null)
            prefs.edit().remove("pin").apply()
            return
        }
        val json = JSONObject()
            .put("salt", pin.salt)
            .put("hash", pin.hash)
            .put("version", pin.version)
            .put("algorithm", pin.algorithm)
            .put("iterations", pin.iterations)
            .put("updatedAt", pin.updatedAt)
        // Fail closed on Keystore corruption: keep the previous record (and the
        // legacy plaintext migration source) rather than wiping the PIN the
        // parent can still enter once the Keystore recovers.
        if (!secureStore.put("pin", json.toString())) return
        prefs.edit().remove("pin").apply()
    }

    fun loadPin(): PinRecord? {
        val raw = secureStore.migratePlaintext("pin") ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val salt = json.optString("salt")
        val hash = json.optString("hash")
        if (salt.isBlank() || hash.isBlank()) return null
        return PinRecord(
            salt = salt,
            hash = hash,
            version = json.optInt("version", PinHasher.LEGACY_VERSION),
            algorithm = json.optString("algorithm").takeIf { it.isNotBlank() && it != "null" },
            iterations = json.optInt("iterations").takeIf { it > 0 },
            updatedAt = json.optLong("updatedAt", 0L)
        )
    }

    fun pinRetryRemainingMs(now: Long = SystemTimeGuard.now()): Long {
        return (prefs.getLong("pinBlockedUntil", 0L) - now).coerceAtLeast(0L)
    }

    fun recordFailedPinAttempt(now: Long = SystemTimeGuard.now()): PinRetryState {
        val attempts = prefs.getInt("pinFailedAttempts", 0) + 1
        val delayMs = pinDelayMs(attempts)
        prefs.edit()
            .putInt("pinFailedAttempts", attempts)
            .putLong("pinBlockedUntil", now + delayMs)
            .apply()
        return PinRetryState(attempts, delayMs)
    }

    fun clearFailedPinAttempts() {
        prefs.edit()
            .remove("pinFailedAttempts")
            .remove("pinBlockedUntil")
            .apply()
    }

    fun grantTemporaryUnlock(packageName: String, durationMs: Long = PolicyConstants.TEMP_UNLOCK_MS) {
        val until = SystemTimeGuard.now() + durationMs
        prefs.edit().putLong("unlock:$packageName", until).apply()
    }

    fun isTemporarilyUnlocked(packageName: String): Boolean {
        val until = prefs.getLong("unlock:$packageName", 0L)
        return until > SystemTimeGuard.now()
    }

    fun grantAppVisitUnlock(policyPackage: String) {
        prefs.edit()
            .putString("appVisitUnlock", policyPackage)
            .putLong("appVisitUnlockAt", SystemTimeGuard.now())
            .putInt("appVisitUnlockBoot", bootCount())
            .apply()
    }

    /** One-visit unlock expires after a day or a reboot — an HDMI-input switch
     *  produces no window events, so time/boot binding is the only cleanup. */
    fun isAppVisitUnlocked(policyPackage: String): Boolean {
        if (prefs.getString("appVisitUnlock", null) != policyPackage) return false
        val grantedAt = prefs.getLong("appVisitUnlockAt", 0L)
        if (grantedAt <= 0L || SystemTimeGuard.now() - grantedAt > APP_VISIT_UNLOCK_MAX_AGE_MS) return false
        return prefs.getInt("appVisitUnlockBoot", Int.MIN_VALUE) == bootCount()
    }

    fun appVisitUnlockPackage(): String? {
        return prefs.getString("appVisitUnlock", null)
            ?.takeIf { granted ->
                val grantedAt = prefs.getLong("appVisitUnlockAt", 0L)
                grantedAt > 0L &&
                    SystemTimeGuard.now() - grantedAt <= APP_VISIT_UNLOCK_MAX_AGE_MS &&
                    prefs.getInt("appVisitUnlockBoot", Int.MIN_VALUE) == bootCount()
            }
    }

    fun clearAppVisitUnlock() {
        prefs.edit()
            .remove("appVisitUnlock")
            .remove("appVisitUnlockAt")
            .remove("appVisitUnlockBoot")
            .apply()
    }

    fun grantSettingsSectionUnlock(sectionKey: String) {
        prefs.edit().putBoolean("sectionUnlock:$sectionKey", true).apply()
    }

    fun isSettingsSectionUnlocked(sectionKey: String): Boolean {
        return prefs.getBoolean("sectionUnlock:$sectionKey", false)
    }

    /** Clears one section's unlock, or every section when key is null. */
    fun clearSettingsSectionUnlock(sectionKey: String? = null) {
        val editor = prefs.edit()
        if (sectionKey == null) {
            prefs.all.keys
                .filter { it.startsWith("sectionUnlock:") }
                .forEach(editor::remove)
        } else {
            editor.remove("sectionUnlock:$sectionKey")
        }
        editor.apply()
    }

    /** While active, the accessibility service re-locks any settings-package
     *  foreground so the DPM disable-confirm dialog cannot be completed after
     *  pressing HOME away from the lock screen. */
    fun grantAdminChangePendingGate(durationMs: Long = ADMIN_CHANGE_GATE_MS) {
        prefs.edit().putLong("adminChangePendingUntil", SystemTimeGuard.now() + durationMs).apply()
    }

    fun isAdminChangePending(): Boolean {
        return prefs.getLong("adminChangePendingUntil", 0L) > SystemTimeGuard.now()
    }

    fun grantSetupVisitUnlock() {
        prefs.edit().putBoolean("setupVisitUnlock", true).apply()
    }

    fun isSetupVisitUnlocked(): Boolean {
        return prefs.getBoolean("setupVisitUnlock", false)
    }

    fun clearSetupVisitUnlock() {
        prefs.edit().remove("setupVisitUnlock").apply()
    }

    fun grantSetupSettingsAccess(durationMs: Long = 120_000L) {
        prefs.edit().putLong("setupSettingsUntil", SystemTimeGuard.now() + durationMs).apply()
    }

    fun isSetupSettingsAccessAllowed(): Boolean {
        return prefs.getLong("setupSettingsUntil", 0L) > SystemTimeGuard.now()
    }

    fun saveLastForeground(packageName: String, observedAt: Long = System.currentTimeMillis()) {
        val previous = memLastForeground
        memLastForeground = packageName to observedAt
        if (previous?.first == packageName &&
            observedAt - lastForegroundPersistedAt < LAST_FOREGROUND_PERSIST_MS
        ) {
            return
        }
        prefs.edit()
            .putString("lastForeground", packageName)
            .putLong("lastForegroundObservedAt", observedAt)
            .putInt("lastForegroundBootCount", bootCount())
            .apply()
        lastForegroundPersistedAt = observedAt
    }

    fun lastForeground(maxAgeMs: Long = FOREGROUND_FRESHNESS_MS): String? {
        val now = System.currentTimeMillis()
        memLastForeground?.let { (packageName, observedAt) ->
            if (now - observedAt in 0..maxAgeMs) return packageName
        }
        val observedAt = prefs.getLong("lastForegroundObservedAt", 0L)
        val observedBoot = prefs.getInt("lastForegroundBootCount", -1)
        if (observedBoot != bootCount() || now - observedAt !in 0..maxAgeMs) {
            return null
        }
        return prefs.getString("lastForeground", null)
    }

    fun startLiveForegroundSession(
        packageName: String,
        baselineUsageMs: Long,
        startedAt: Long = System.currentTimeMillis(),
        dayKey: String = DateKeys.dayKeyUtc(SystemTimeGuard.now())
    ) {
        finalizeLiveForegroundSession(startedAt)
        val committed = committedUsageMillisToday(dayKey)[packageName] ?: 0L
        prefs.edit()
            .putString("liveForegroundPackage", packageName)
            .putLong("liveForegroundStartedAt", startedAt)
            .putLong("liveForegroundBaselineMs", maxOf(baselineUsageMs, committed).coerceAtLeast(0L))
            .putLong("liveForegroundLastObservedAt", startedAt)
            .putInt("liveForegroundBootCount", bootCount())
            .putString("liveForegroundDay", dayKey)
            .apply()
        memSessionObservedAt = startedAt
    }

    fun refreshLiveForegroundSession(observedAt: Long = System.currentTimeMillis()) {
        if (prefs.getString("liveForegroundPackage", null).isNullOrBlank()) return
        memSessionObservedAt = observedAt
        if (observedAt - prefs.getLong("liveForegroundLastObservedAt", 0L) < SESSION_OBSERVE_PERSIST_MS) {
            return
        }
        prefs.edit().putLong("liveForegroundLastObservedAt", observedAt).apply()
    }

    fun liveForegroundSession(
        dayKey: String = DateKeys.dayKeyUtc(SystemTimeGuard.now()),
        now: Long = System.currentTimeMillis()
    ): LiveForegroundSession? {
        val packageName = prefs.getString("liveForegroundPackage", null)?.takeIf { it.isNotBlank() }
            ?: return null
        val sessionDay = prefs.getString("liveForegroundDay", null) ?: return null
        // The in-memory observation is fresher than the throttled prefs write; the
        // overlay is always a past observation, so taking the max is monotone-safe.
        val lastObservedAt = maxOf(
            prefs.getLong("liveForegroundLastObservedAt", 0L),
            memSessionObservedAt
        )
        val session = LiveForegroundSession(
            packageName = packageName,
            startedAt = prefs.getLong("liveForegroundStartedAt", 0L),
            baselineUsageMs = prefs.getLong("liveForegroundBaselineMs", 0L),
            dayKey = sessionDay,
            lastObservedAt = lastObservedAt,
            bootCount = prefs.getInt("liveForegroundBootCount", -1)
        )
        if (session.dayKey != dayKey ||
            session.bootCount != bootCount() ||
            now - session.lastObservedAt !in 0..FOREGROUND_FRESHNESS_MS
        ) {
            finalizeLiveForegroundSession(now)
            return null
        }
        return session.takeIf { it.startedAt > 0L && it.lastObservedAt >= it.startedAt }
    }

    fun finalizeLiveForegroundSession(now: Long = System.currentTimeMillis()) {
        val packageName = prefs.getString("liveForegroundPackage", null)?.takeIf { it.isNotBlank() }
            ?: return
        val dayKey = prefs.getString("liveForegroundDay", null)
            ?: DateKeys.dayKeyUtc(SystemTimeGuard.now())
        val startedAt = prefs.getLong("liveForegroundStartedAt", 0L)
        val lastObservedAt = maxOf(
            prefs.getLong("liveForegroundLastObservedAt", startedAt),
            memSessionObservedAt
        )
        val baseline = prefs.getLong("liveForegroundBaselineMs", 0L)
        val endAt = minOf(now, lastObservedAt + OBSERVATION_GRACE_MS).coerceAtLeast(startedAt)
        val committed = baseline + (endAt - startedAt).coerceAtLeast(0L)
        val ledger = committedUsageMillisToday(dayKey).toMutableMap()
        ledger[packageName] = maxOf(ledger[packageName] ?: 0L, committed)
        saveUsageLedger(dayKey, ledger)
        clearLiveForegroundSessionInternal()
    }

    fun committedUsageMillisToday(dayKey: String = DateKeys.dayKeyUtc(SystemTimeGuard.now())): Map<String, Long> {
        val raw = prefs.getString("usageLedger:$dayKey", null) ?: return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            json.keys().forEach { packageName ->
                put(packageName, json.optLong(packageName, 0L).coerceAtLeast(0L))
            }
        }
    }

    fun clearLiveForegroundSession(finalize: Boolean = true) {
        if (finalize) {
            finalizeLiveForegroundSession()
        } else {
            clearLiveForegroundSessionInternal()
        }
    }

    private fun clearLiveForegroundSessionInternal() {
        memSessionObservedAt = Long.MIN_VALUE
        prefs.edit()
            .remove("liveForegroundPackage")
            .remove("liveForegroundStartedAt")
            .remove("liveForegroundBaselineMs")
            .remove("liveForegroundLastObservedAt")
            .remove("liveForegroundBootCount")
            .remove("liveForegroundDay")
            .apply()
    }

    private fun saveUsageLedger(dayKey: String, values: Map<String, Long>) {
        val json = JSONObject()
        values.forEach { (packageName, usageMs) -> json.put(packageName, usageMs.coerceAtLeast(0L)) }
        prefs.edit().putString("usageLedger:$dayKey", json.toString()).apply()
    }

    private fun bootCount(): Int {
        bootCountCache.let { cached -> if (cached != Int.MIN_VALUE) return cached }
        return runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrDefault(0).also { bootCountCache = it }
    }

    fun pruneStaleKeys(retentionDays: Int = PRUNE_RETENTION_DAYS) {
        val cutoffDay = DateKeys.utcDaysAgo(retentionDays, SystemTimeGuard.now())
        val now = SystemTimeGuard.now()
        val editor = prefs.edit()
        var pruned = false
        for (key in prefs.all.keys.toList()) {
            when {
                key.startsWith("usageLedger:") &&
                    key.removePrefix("usageLedger:") < cutoffDay -> { editor.remove(key); pruned = true }
                key.startsWith("unlock:") &&
                    prefs.getLong(key, 0L) <= now -> { editor.remove(key); pruned = true }
            }
        }
        if (pruned) editor.apply()
    }

    fun saveSafeMode(until: Long) {
        prefs.edit().putLong("safeModeUntil", until).apply()
    }

    fun safeModeUntil(): Long = prefs.getLong("safeModeUntil", 0L)

    fun saveServerTimeOffset(offsetMs: Long) {
        prefs.edit().putLong("serverTimeOffset", offsetMs).apply()
    }

    fun serverNow(): Long = System.currentTimeMillis() + prefs.getLong("serverTimeOffset", 0L)

    fun isSafeModeActive(): Boolean = safeModeUntil() > serverNow()

    fun shouldReportTamper(type: String): Boolean {
        val key = "tamper:$type"
        val now = System.currentTimeMillis()
        val last = prefs.getLong(key, 0L)
        if (now - last < PolicyConstants.TAMPER_EVENT_THROTTLE_MS) return false
        prefs.edit().putLong(key, now).apply()
        return true
    }

    companion object {
        const val FOREGROUND_FRESHNESS_MS = 3_000L
        private const val OBSERVATION_GRACE_MS = 1_500L
        private const val LAST_FOREGROUND_PERSIST_MS = 30_000L
        private const val SESSION_OBSERVE_PERSIST_MS = 30_000L
        private const val PRUNE_RETENTION_DAYS = 7
        private const val MAX_PIN_DELAY_MS = 5 * 60_000L
        private const val APP_VISIT_UNLOCK_MAX_AGE_MS = 24L * 60L * 60_000L
        private const val ADMIN_CHANGE_GATE_MS = 10L * 60_000L

        // Both services share this process (no android:process split), so these
        // overlays carry the per-event state; prefs persist it on a slow cadence
        // for recovery after a process restart. Kept in the companion because
        // each service constructs its own FallbackStateStore instance.
        @Volatile private var memLastForeground: Pair<String, Long>? = null
        @Volatile private var lastForegroundPersistedAt: Long = 0L
        @Volatile private var memSessionObservedAt: Long = Long.MIN_VALUE
        @Volatile private var bootCountCache: Int = Int.MIN_VALUE

        internal fun pinDelayMs(attempts: Int): Long = when {
            attempts <= 3 -> 1_000L
            attempts == 4 -> 5_000L
            attempts == 5 -> 15_000L
            attempts == 6 -> 30_000L
            else -> (30_000L * (1L shl (attempts - 6).coerceAtMost(4)))
                .coerceAtMost(MAX_PIN_DELAY_MS)
        }
    }
}

data class PinRetryState(
    val attempts: Int,
    val delayMs: Long
)
