package com.guardpulse.parentcontrol.tv.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class TvSyncLocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("tv_sync_runtime", Context.MODE_PRIVATE)

    fun activateV2(revisionId: String) {
        prefs.edit()
            .putBoolean("v2Activated", true)
            .putString("lastV2Revision", revisionId)
            .apply()
    }

    fun isV2Activated(): Boolean = prefs.getBoolean("v2Activated", false)

    fun lastV2Revision(): String? = prefs.getString("lastV2Revision", null)

    fun saveAppliedV2Revision(revisionId: String, sessionId: String?) {
        prefs.edit()
            .putString("lastAppliedV2Revision", revisionId)
            .putString("lastAppliedSessionId", sessionId)
            .apply()
    }

    /** Package keys from the last successfully uploaded inventory; the state
     *  uploader diffs against this to delete state children of uninstalled apps. */
    fun saveInventoryPackageKeys(keys: Set<String>) {
        prefs.edit().putStringSet("inventoryPackageKeys", keys).apply()
    }

    fun inventoryPackageKeys(): Set<String> =
        prefs.getStringSet("inventoryPackageKeys", emptySet())?.toSet().orEmpty()

    fun lastAppliedV2Revision(): String? = prefs.getString("lastAppliedV2Revision", null)
    fun lastAppliedSessionId(): String? = prefs.getString("lastAppliedSessionId", null)

    fun savePendingAppliedRevision(revisionId: String?) {
        prefs.edit().putString("pendingAppliedRevision", revisionId).apply()
    }

    fun pendingAppliedRevision(): String? = prefs.getString("pendingAppliedRevision", null)

    fun saveLastError(channel: String?, message: String?) {
        prefs.edit()
            .putString("lastFailedChannel", channel)
            .putString("lastError", message)
            .putLong("lastErrorAt", if (message == null) 0L else System.currentTimeMillis())
            .apply()
    }

    fun lastFailedChannel(): String? = prefs.getString("lastFailedChannel", null)
    fun lastError(): String? = prefs.getString("lastError", null)
    fun lastErrorAt(): Long = prefs.getLong("lastErrorAt", 0L)

    fun markChannelDirty(channel: String) {
        val channels = dirtyChannels().toMutableSet()
        channels += channel
        prefs.edit().putStringSet("dirtyChannels", channels).apply()
    }

    fun markChannelClean(channel: String) {
        val channels = dirtyChannels().toMutableSet()
        channels -= channel
        prefs.edit().putStringSet("dirtyChannels", channels).apply()
    }

    fun dirtyChannels(): Set<String> =
        prefs.getStringSet("dirtyChannels", emptySet())?.toSet().orEmpty()

    fun isCommandProcessed(commandId: String): Boolean = commandId in processedCommands()

    fun markCommandProcessed(commandId: String) {
        val commands = appendProcessedCommand(
            processedCommands(),
            commandId,
            MAX_PROCESSED_COMMANDS
        )
        prefs.edit().putString("processedCommands", JSONArray(commands).toString()).apply()
    }

    private fun processedCommands(): List<String> {
        val raw = prefs.getString("processedCommands", "[]") ?: "[]"
        val json = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until json.length()) {
                json.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    companion object {
        private const val MAX_PROCESSED_COMMANDS = 100
    }
}
