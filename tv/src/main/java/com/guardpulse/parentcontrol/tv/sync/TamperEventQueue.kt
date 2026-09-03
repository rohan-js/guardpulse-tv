package com.guardpulse.parentcontrol.tv.sync

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.guardpulse.parentcontrol.shared.DeviceIdentity
import com.guardpulse.parentcontrol.shared.FirebasePaths
import com.guardpulse.parentcontrol.shared.FirebaseRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object TamperEventQueue {
    private const val PREFS = "tamper_event_queue"
    private const val KEY_EVENTS = "events"
    @Volatile private var flushing = false

    fun enqueue(context: Context, type: String, message: String) {
        val appContext = context.applicationContext
        synchronized(this) {
            val events = load(appContext).toMutableList()
            events += PendingTamperEvent(
                id = UUID.randomUUID().toString(),
                type = type,
                message = message,
                createdAt = System.currentTimeMillis()
            )
            save(appContext, events.takeLast(100))
        }
        flush(appContext)
    }

    fun flush(context: Context) {
        val appContext = context.applicationContext
        if (!FirebaseRuntime.initialize(appContext).configured || flushing) return
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener { flush(appContext) }
                .addOnFailureListener { error ->
                    // Events stay persisted; retry once after a delay instead of
                    // stalling until the next enqueue/reconnect.
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        { flush(appContext) },
                        SIGN_IN_RETRY_DELAY_MS
                    )
                    android.util.Log.w("GuardPulseTamper", "Tamper sign-in failed", error)
                }
            return
        }
        val next = synchronized(this) { load(appContext).firstOrNull() } ?: return
        flushing = true
        val deviceId = DeviceIdentity.getOrCreate(appContext)
        val root = FirebaseDatabase.getInstance().reference
        root.child(FirebasePaths.deviceTamperEvents(deviceId))
            .child(next.id)
            .setValue(
                mapOf(
                    "eventId" to next.id,
                    "type" to next.type,
                    "createdAt" to next.createdAt,
                    "message" to next.message
                )
            )
            .addOnSuccessListener {
                synchronized(this) {
                    save(appContext, load(appContext).filterNot { it.id == next.id })
                }
                root.child(FirebasePaths.deviceSyncRuntime(deviceId)).updateChildren(
                    mapOf(
                        "lastTamperWriteAt" to ServerValue.TIMESTAMP,
                        "lastSuccessAt" to ServerValue.TIMESTAMP
                    )
                )
                flushing = false
                flush(appContext)
            }
            .addOnFailureListener { error ->
                flushing = false
                android.util.Log.w("GuardPulseTamper", "Tamper upload failed; will retry", error)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { flush(appContext) },
                    SIGN_IN_RETRY_DELAY_MS
                )
            }
    }

    private fun load(context: Context): List<PendingTamperEvent> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EVENTS, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                PendingTamperEvent(
                    id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    type = item.optString("type").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                    message = item.optString("message"),
                    createdAt = item.optLong("createdAt", System.currentTimeMillis())
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, events: List<PendingTamperEvent>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put("id", event.id)
                    .put("type", event.type)
                    .put("message", event.message)
                    .put("createdAt", event.createdAt)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EVENTS, array.toString())
            .apply()
    }

    private data class PendingTamperEvent(
        val id: String,
        val type: String,
        val message: String,
        val createdAt: Long
    )

    private const val SIGN_IN_RETRY_DELAY_MS = 60_000L
}
