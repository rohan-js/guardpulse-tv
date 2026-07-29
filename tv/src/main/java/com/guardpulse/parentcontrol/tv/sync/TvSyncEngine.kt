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
        suspend fun onConnectionChanged(connected: Boolean, sessionId: String?): Boolean
        suspend fun onControlReady(
            snapshot: ControlSnapshotV2,
            desired: SyncDesiredRevision?,
            generation: Long
        )
        suspend fun onControlRejected(revisionId: String?, error: String)
        suspend fun onSyncListenerError(channel: String, error: DatabaseError)
    }

    private sealed interface TvSyncEvent {
        data class Connection(val connected: Boolean) : TvSyncEvent
        data class Control(val snapshot: DataSnapshot) : TvSyncEvent
        data class Desired(val snapshot: DataSnapshot) : TvSyncEvent
        data class ListenerError(val channel: String, val error: DatabaseError) : TvSyncEvent
        data class Work(val operation: suspend () -> Unit) : TvSyncEvent
        data class CoalescedWork(val channel: String) : TvSyncEvent
        data object Reconcile : TvSyncEvent
        data object RetryListeners : TvSyncEvent
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val events = Channel<TvSyncEvent>(Channel.UNLIMITED)
    private val registrations = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()
    private val pendingWork = mutableMapOf<String, suspend () -> Unit>()
    private val queuedWorkChannels = mutableSetOf<String>()
    private val revisionTracker = RevisionGenerationTracker()
    private var pendingSnapshot: ControlSnapshotV2? = null
    private var pendingDesired: SyncDesiredRevision? = null
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

    fun submit(
        channel: String? = null,
        operation: suspend () -> Unit
    ) {
        if (!started) return
        if (channel == null) {
            events.trySend(TvSyncEvent.Work(operation))
            return
        }
        pendingWork[channel] = operation
        if (queuedWorkChannels.add(channel)) {
            events.trySend(TvSyncEvent.CoalescedWork(channel))
        }
    }

    fun isCurrent(generation: Long, revisionId: String): Boolean {
        return revisionTracker.isCurrent(generation, revisionId)
    }

    private suspend fun handle(event: TvSyncEvent) {
        when (event) {
            is TvSyncEvent.Connection -> {
                if (event.connected) {
                    retryDelayMs = INITIAL_RETRY_MS
                    sessionId = UUID.randomUUID().toString()
                }
                val connectionReady = callback.onConnectionChanged(event.connected, sessionId)
                if (event.connected) {
                    if (connectionReady) scheduleReconcile() else scheduleRetry()
                }
            }
            is TvSyncEvent.Control -> {
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
                            localStore.saveValidV2Snapshot(control)
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
            is TvSyncEvent.Desired -> {
                pendingDesired = ControlProtocol.parseDesired(event.snapshot)
                scheduleReconcile()
            }
            is TvSyncEvent.ListenerError -> {
                callback.onSyncListenerError(event.channel, event.error)
                scheduleRetry()
            }
            is TvSyncEvent.Work -> event.operation()
            is TvSyncEvent.CoalescedWork -> {
                queuedWorkChannels.remove(event.channel)
                pendingWork.remove(event.channel)?.invoke()
            }
            TvSyncEvent.Reconcile -> dispatchNewestControl()
            TvSyncEvent.RetryListeners -> {
                detachListeners()
                attachListeners()
            }
        }
    }

    private fun attachListeners() {
        if (!started || registrations.isNotEmpty()) return
        register(database.getReference(".info/connected"), "connection") {
            TvSyncEvent.Connection(it.getValue(Boolean::class.java) ?: false)
        }
        register(
            database.getReference(FirebasePaths.deviceControlV2(deviceId)),
            "controlV2",
            TvSyncEvent::Control
        )
        register(
            database.getReference(FirebasePaths.deviceSyncDesired(deviceId)),
            "desiredRevision",
            TvSyncEvent::Desired
        )
    }

    private fun register(
        ref: DatabaseReference,
        channel: String,
        eventFactory: (DataSnapshot) -> TvSyncEvent
    ) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                events.trySend(eventFactory(snapshot))
            }

            override fun onCancelled(error: DatabaseError) {
                events.trySend(TvSyncEvent.ListenerError(channel, error))
            }
        }
        registrations += ref to listener
        ref.addValueEventListener(listener)
    }

    private fun scheduleReconcile() {
        reconcileJob?.cancel()
        reconcileJob = scope.launch {
            delay(CONTROL_DEBOUNCE_MS)
            events.send(TvSyncEvent.Reconcile)
        }
    }

    private suspend fun dispatchNewestControl() {
        val control = pendingSnapshot ?: return
        val desired = pendingDesired
        if (!shouldApplyControl(
                controlRevisionId = control.revisionId,
                lastAppliedRevisionId = localStore.lastAppliedV2Revision(),
                pendingAppliedRevisionId = localStore.pendingAppliedRevision(),
                currentSessionId = sessionId,
                lastAppliedSessionId = localStore.lastAppliedSessionId()
            )
        ) {
            return
        }
        if (desired != null && desired.revisionId != control.revisionId) {
            reconcileJob?.cancel()
            reconcileJob = scope.launch {
                delay(REVISION_SETTLE_RETRY_MS)
                events.send(TvSyncEvent.Reconcile)
            }
            return
        }
        val generation = revisionTracker.advance(control.revisionId)
        callback.onControlReady(control, desired, generation)
        if (localStore.lastAppliedV2Revision() == control.revisionId &&
            localStore.lastAppliedSessionId() == sessionId &&
            localStore.pendingAppliedRevision() == null
        ) {
            return
        } else {
            reconcileJob?.cancel()
            reconcileJob = scope.launch {
                delay(INITIAL_RETRY_MS)
                events.send(TvSyncEvent.Reconcile)
            }
        }
    }

    private fun scheduleRetry() {
        if (!started) return
        retryJob?.cancel()
        val delayMs = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        retryJob = scope.launch {
            delay(delayMs)
            events.send(TvSyncEvent.RetryListeners)
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
