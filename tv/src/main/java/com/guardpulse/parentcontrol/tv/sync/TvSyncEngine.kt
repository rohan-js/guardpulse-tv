package com.guardpulse.parentcontrol.tv.sync

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.guardpulse.parentcontrol.shared.ControlProtocol
import com.guardpulse.parentcontrol.shared.ControlSnapshotV2
import com.guardpulse.parentcontrol.shared.FirebasePaths
import com.guardpulse.parentcontrol.shared.SyncDesiredRevision
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TvSyncEngine(
    private val database: FirebaseDatabase,
    private val deviceId: String,
    private val localStore: TvSyncLocalStore,
    private val callback: Callback
) {
    interface Callback {
        suspend fun onConnectionChanged(connected: Boolean, sessionId: String?)
        suspend fun onControlReady(
            snapshot: ControlSnapshotV2,
            desired: SyncDesiredRevision?,
            generation: Long
        )
        suspend fun onControlRejected(revisionId: String?, error: String)
        suspend fun onSyncListenerError(channel: String, error: DatabaseError)
    }

    private sealed interface Event {
        data class Connection(val connected: Boolean) : Event
        data class Control(val snapshot: DataSnapshot) : Event
        data class Desired(val snapshot: DataSnapshot) : Event
        data class ListenerError(val channel: String, val error: DatabaseError) : Event
        data object Reconcile : Event
        data object RetryListeners : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val registrations = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()
    private var pendingSnapshot: ControlSnapshotV2? = null
    private var pendingDesired: SyncDesiredRevision? = null
    private var generation = 0L
    private var sessionId: String? = null
    private var retryDelayMs = INITIAL_RETRY_MS
    private var reconcileJob: Job? = null
    private var retryJob: Job? = null
    private var started = false

    init {
        scope.launch {
            for (event in events) handle(event)
        }
    }

    fun start() {
        if (started) return
        started = true
        attachListeners()
    }

    fun stop() {
        started = false
        reconcileJob?.cancel()
        retryJob?.cancel()
        detachListeners()
        events.close()
        scope.coroutineContext[Job]?.cancel()
    }

    fun usesV2(): Boolean = localStore.isV2Activated()
    fun currentSessionId(): String? = sessionId

    fun isCurrent(generation: Long, revisionId: String): Boolean {
        return this.generation == generation && pendingSnapshot?.revisionId == revisionId
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Connection -> {
                if (event.connected) {
                    retryDelayMs = INITIAL_RETRY_MS
                    sessionId = UUID.randomUUID().toString()
                }
                callback.onConnectionChanged(event.connected, sessionId)
            }
            is Event.Control -> {
                if (!event.snapshot.exists()) {
                    if (localStore.isV2Activated()) {
                        callback.onControlRejected(
                            localStore.lastV2Revision(),
                            "V2 control snapshot was removed"
                        )
                    }
                } else {
                    ControlProtocol.parse(event.snapshot)
                        .onSuccess { control ->
                            pendingSnapshot = control
                            localStore.activateV2(control.revisionId)
                            scheduleReconcile()
                        }
                        .onFailure { error ->
                            callback.onControlRejected(
                                event.snapshot.child("revisionId").getValue(String::class.java),
                                error.message ?: "Invalid V2 control snapshot"
                            )
                        }
                }
            }
            is Event.Desired -> {
                pendingDesired = ControlProtocol.parseDesired(event.snapshot)
                scheduleReconcile()
            }
            is Event.ListenerError -> {
                callback.onSyncListenerError(event.channel, event.error)
                scheduleRetry()
            }
            Event.Reconcile -> dispatchNewestControl()
            Event.RetryListeners -> {
                detachListeners()
                attachListeners()
            }
        }
    }

    private fun attachListeners() {
        if (!started || registrations.isNotEmpty()) return
        register(database.getReference(".info/connected"), "connection") {
            Event.Connection(it.getValue(Boolean::class.java) ?: false)
        }
        register(database.getReference(FirebasePaths.deviceControlV2(deviceId)), "controlV2", Event::Control)
        register(database.getReference(FirebasePaths.deviceSyncDesired(deviceId)), "desiredRevision", Event::Desired)
    }

    private fun register(
        ref: DatabaseReference,
        channel: String,
        eventFactory: (DataSnapshot) -> Event
    ) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                events.trySend(eventFactory(snapshot))
            }

            override fun onCancelled(error: DatabaseError) {
                events.trySend(Event.ListenerError(channel, error))
            }
        }
        registrations += ref to listener
        ref.addValueEventListener(listener)
    }

    private fun scheduleReconcile() {
        reconcileJob?.cancel()
        reconcileJob = scope.launch {
            delay(CONTROL_DEBOUNCE_MS)
            events.send(Event.Reconcile)
        }
    }

    private suspend fun dispatchNewestControl() {
        val control = pendingSnapshot ?: return
        val desired = pendingDesired
        if (desired != null && desired.revisionId != control.revisionId) {
            reconcileJob?.cancel()
            reconcileJob = scope.launch {
                delay(REVISION_SETTLE_RETRY_MS)
                events.send(Event.Reconcile)
            }
            return
        }
        generation += 1
        callback.onControlReady(control, desired, generation)
    }

    private fun scheduleRetry() {
        if (!started) return
        retryJob?.cancel()
        val delayMs = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        retryJob = scope.launch {
            delay(delayMs)
            events.send(Event.RetryListeners)
        }
    }

    private fun detachListeners() {
        registrations.forEach { (ref, listener) -> ref.removeEventListener(listener) }
        registrations.clear()
    }

    companion object {
        private const val CONTROL_DEBOUNCE_MS = 250L
        private const val REVISION_SETTLE_RETRY_MS = 500L
        private const val INITIAL_RETRY_MS = 5_000L
        private const val MAX_RETRY_MS = 5 * 60_000L
    }
}
