package com.guardpulse.parentcontrol.tv.policy

import android.content.Context
import android.content.SharedPreferences
import com.guardpulse.parentcontrol.shared.DateKeys
import org.json.JSONObject

data class AppPolicy(
    val manualBlocked: Boolean = false,
    val dailyLimitMinutes: Int? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("manualBlocked", manualBlocked)
        .put("dailyLimitMinutes", dailyLimitMinutes ?: JSONObject.NULL)
}

class LocalPolicyStore(context: Context) {
    private val prefs = context.getSharedPreferences("local_policy", Context.MODE_PRIVATE)

    // Read caches: loadPolicies() sits on the accessibility hot path (every event),
    // so the full JSON map is parsed once and invalidated on any prefs write.
    private val cacheLock = Any()
    private var policiesCache: Map<String, AppPolicy>? = null
    private var policiesJsonWritten: String? = null
    private var dailyBlocksCache: Pair<String, Set<String>>? = null
    private var usageOffsetsCache: Pair<String, Map<String, Long>>? = null

    // Kept as a field: SharedPreferences holds listeners weakly, so the invalidator
    // needs this strong reference to survive. Registered eagerly — any instance
    // (accessibility service, sync service) may write while another reads.
    private val cacheInvalidator = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        synchronized(cacheLock) {
            policiesCache = null
            dailyBlocksCache = null
            usageOffsetsCache = null
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(cacheInvalidator)
    }

    fun savePolicies(policies: Map<String, AppPolicy>) {
        val json = JSONObject()
        policies.forEach { (packageName, policy) -> json.put(packageName, policy.toJson()) }
        val serialized = json.toString()
        if (serialized == policiesJsonWritten) return
        prefs.edit().putString("policies", serialized).apply()
        policiesJsonWritten = serialized
    }

    fun loadPolicies(): Map<String, AppPolicy> {
        synchronized(cacheLock) {
            policiesCache?.let { return it }
        }
        val raw = prefs.getString("policies", null) ?: return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val parsed = buildMap {
            json.keys().forEach { packageName ->
                val item = json.optJSONObject(packageName) ?: return@forEach
                val limit = if (item.isNull("dailyLimitMinutes")) {
                    null
                } else {
                    item.optInt("dailyLimitMinutes").takeIf { it > 0 }
                }
                put(
                    packageName,
                    AppPolicy(
                        manualBlocked = item.optBoolean("manualBlocked", false),
                        dailyLimitMinutes = limit
                    )
                )
            }
        }
        synchronized(cacheLock) { policiesCache = parsed }
        return parsed
    }

    fun loadDailyLimitBlocks(): Set<String> {
        val key = "dailyBlocks:${DateKeys.today()}"
        synchronized(cacheLock) {
            dailyBlocksCache?.let { (cachedKey, cached) ->
                if (cachedKey == key) return cached
            }
        }
        val loaded = prefs.getStringSet(key, emptySet()).orEmpty()
        synchronized(cacheLock) { dailyBlocksCache = key to loaded }
        return loaded
    }

    fun markDailyLimitBlocked(packageName: String) {
        val key = "dailyBlocks:${DateKeys.today()}"
        val updated = loadDailyLimitBlocks().toMutableSet()
        updated.add(packageName)
        prefs.edit().putStringSet(key, updated).apply()
        synchronized(cacheLock) { dailyBlocksCache = key to updated }
    }

    fun clearDailyLimitBlocks(packageName: String? = null) {
        val key = "dailyBlocks:${DateKeys.today()}"
        if (packageName == null) {
            prefs.edit().remove(key).apply()
            synchronized(cacheLock) { dailyBlocksCache = key to emptySet() }
        } else {
            val updated = loadDailyLimitBlocks().toMutableSet()
            updated.remove(packageName)
            prefs.edit().putStringSet(key, updated).apply()
            synchronized(cacheLock) { dailyBlocksCache = key to updated }
        }
    }

    fun loadUsageOffsetsMs(): Map<String, Long> {
        val day = DateKeys.today()
        val key = "usageOffsetsMs:$day"
        synchronized(cacheLock) {
            usageOffsetsCache?.let { (cachedKey, cached) ->
                if (cachedKey == key) return cached
            }
        }
        val existing = prefs.getString(key, null)
        val loaded = if (existing != null) {
            parseLongMap(existing)
        } else {
            val legacy = prefs.getString("usageOffsets:$day", null)
                ?.let { parseLongMap(it) }
                ?: emptyMap()
            val migrated = legacy.mapValues { (_, minutes) -> minutes.coerceAtLeast(0L) * 60_000L }
            if (legacy.isNotEmpty()) saveLongMap(key, migrated)
            migrated
        }
        synchronized(cacheLock) { usageOffsetsCache = key to loaded }
        return loaded
    }

    fun saveUsageOffsetMs(packageName: String, usageMs: Long) {
        val key = "usageOffsetsMs:${DateKeys.today()}"
        val values = loadUsageOffsetsMs().toMutableMap()
        values[packageName] = usageMs.coerceAtLeast(0L)
        saveLongMap(key, values)
        synchronized(cacheLock) { usageOffsetsCache = key to values }
    }

    private fun parseLongMap(raw: String): Map<String, Long> {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            json.keys().forEach { packageName ->
                put(packageName, json.optLong(packageName, 0L))
            }
        }
    }

    private fun saveLongMap(key: String, values: Map<String, Long>) {
        val json = JSONObject()
        values.forEach { (name, value) -> json.put(name, value) }
        prefs.edit().putString(key, json.toString()).apply()
    }

    fun saveActiveMode(modeId: String?, modeName: String?) {
        prefs.edit()
            .putString("activeModeId", modeId)
            .putString("activeModeName", modeName)
            .apply()
    }

    fun activeModeId(): String? = prefs.getString("activeModeId", null)

    fun activeModeName(): String? = prefs.getString("activeModeName", null)

    fun saveSafeMode(until: Long) {
        prefs.edit().putLong("safeModeUntil", until).apply()
    }

    fun safeModeUntil(): Long = prefs.getLong("safeModeUntil", 0L)

    fun isSafeModeActive(): Boolean = safeModeUntil() > System.currentTimeMillis()

    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** Removes day-keyed entries older than the retention window; called on service start. */
    fun pruneStaleDays(retentionDays: Int = 7) {
        val cutoffDay = DateKeys.daysAgo(retentionDays)
        val editor = prefs.edit()
        var pruned = false
        for (key in prefs.all.keys.toList()) {
            val dayKey = when {
                key.startsWith("dailyBlocks:") -> key.removePrefix("dailyBlocks:")
                key.startsWith("usageOffsetsMs:") -> key.removePrefix("usageOffsetsMs:")
                key.startsWith("usageOffsets:") -> key.removePrefix("usageOffsets:")
                else -> null
            } ?: continue
            if (dayKey < cutoffDay) {
                editor.remove(key)
                pruned = true
            }
        }
        if (pruned) editor.apply()
    }
}
