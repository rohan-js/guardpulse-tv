package com.guardpulse.parentcontrol.tv.fallback

import android.content.Context
import android.provider.Settings
import com.guardpulse.parentcontrol.shared.DateKeys
import com.guardpulse.parentcontrol.shared.PolicyConstants
import org.json.JSONObject

data class PinRecord(
    val salt: String,
    val hash: String,
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

    fun savePin(pin: PinRecord?) {
        if (pin == null) {
            prefs.edit().remove("pin").apply()
            return
        }
        val json = JSONObject()
            .put("salt", pin.salt)
            .put("hash", pin.hash)
            .put("updatedAt", pin.updatedAt)
        prefs.edit().putString("pin", json.toString()).apply()
    }

    fun loadPin(): PinRecord? {
        val raw = prefs.getString("pin", null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val salt = json.optString("salt")
        val hash = json.optString("hash")
        if (salt.isBlank() || hash.isBlank()) return null
        return PinRecord(salt, hash, json.optLong("updatedAt", 0L))
    }

    fun grantTemporaryUnlock(packageName: String, durationMs: Long = PolicyConstants.TEMP_UNLOCK_MS) {
        val until = System.currentTimeMillis() + durationMs
        prefs.edit().putLong("unlock:$packageName", until).apply()
    }

    fun isTemporarilyUnlocked(packageName: String): Boolean {
        val until = prefs.getLong("unlock:$packageName", 0L)
        return until > System.currentTimeMillis()
    }

    fun grantAppVisitUnlock(policyPackage: String) {
        prefs.edit().putString("appVisitUnlock", policyPackage).apply()
    }

    fun isAppVisitUnlocked(policyPackage: String): Boolean {
        return prefs.getString("appVisitUnlock", null) == policyPackage
    }

    fun appVisitUnlockPackage(): String? = prefs.getString("appVisitUnlock", null)

    fun clearAppVisitUnlock() {
        prefs.edit().remove("appVisitUnlock").apply()
    }

    fun grantSettingsSectionUnlock(sectionKey: String) {
        prefs.edit().putString("settingsSectionUnlock", sectionKey).apply()
    }

    fun isSettingsSectionUnlocked(sectionKey: String): Boolean {
        return prefs.getString("settingsSectionUnlock", null) == sectionKey
    }

    fun clearSettingsSectionUnlock() {
        prefs.edit().remove("settingsSectionUnlock").apply()
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
        prefs.edit().putLong("setupSettingsUntil", System.currentTimeMillis() + durationMs).apply()
    }

    fun isSetupSettingsAccessAllowed(): Boolean {
        return prefs.getLong("setupSettingsUntil", 0L) > System.currentTimeMillis()
    }

    fun saveLastForeground(packageName: String, observedAt: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString("lastForeground", packageName)
            .putLong("lastForegroundObservedAt", observedAt)
            .putInt("lastForegroundBootCount", bootCount())
            .apply()
    }

    fun lastForeground(maxAgeMs: Long = FOREGROUND_FRESHNESS_MS): String? {
        val observedAt = prefs.getLong("lastForegroundObservedAt", 0L)
        val observedBoot = prefs.getInt("lastForegroundBootCount", -1)
        if (observedBoot != bootCount() || System.currentTimeMillis() - observedAt !in 0..maxAgeMs) {
            return null
        }
        return prefs.getString("lastForeground", null)
    }

    fun startLiveForegroundSession(
        packageName: String,
        baselineUsageMs: Long,
        startedAt: Long = System.currentTimeMillis(),
        dayKey: String = DateKeys.today()
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
    }

    fun refreshLiveForegroundSession(observedAt: Long = System.currentTimeMillis()) {
        if (prefs.getString("liveForegroundPackage", null).isNullOrBlank()) return
        prefs.edit().putLong("liveForegroundLastObservedAt", observedAt).apply()
    }

    fun liveForegroundSession(
        dayKey: String = DateKeys.today(),
        now: Long = System.currentTimeMillis()
    ): LiveForegroundSession? {
        val packageName = prefs.getString("liveForegroundPackage", null)?.takeIf { it.isNotBlank() }
            ?: return null
        val sessionDay = prefs.getString("liveForegroundDay", null) ?: return null
        val session = LiveForegroundSession(
            packageName = packageName,
            startedAt = prefs.getLong("liveForegroundStartedAt", 0L),
            baselineUsageMs = prefs.getLong("liveForegroundBaselineMs", 0L),
            dayKey = sessionDay,
            lastObservedAt = prefs.getLong("liveForegroundLastObservedAt", 0L),
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
        val dayKey = prefs.getString("liveForegroundDay", null) ?: DateKeys.today()
        val startedAt = prefs.getLong("liveForegroundStartedAt", 0L)
        val lastObservedAt = prefs.getLong("liveForegroundLastObservedAt", startedAt)
        val baseline = prefs.getLong("liveForegroundBaselineMs", 0L)
        val endAt = minOf(now, lastObservedAt + OBSERVATION_GRACE_MS).coerceAtLeast(startedAt)
        val committed = baseline + (endAt - startedAt).coerceAtLeast(0L)
        val ledger = committedUsageMillisToday(dayKey).toMutableMap()
        ledger[packageName] = maxOf(ledger[packageName] ?: 0L, committed)
        saveUsageLedger(dayKey, ledger)
        clearLiveForegroundSessionInternal()
    }

    fun committedUsageMillisToday(dayKey: String = DateKeys.today()): Map<String, Long> {
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
        return runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrDefault(0)
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
    }
}
