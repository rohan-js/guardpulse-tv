package com.guardpulse.parentcontrol.tv.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import com.guardpulse.parentcontrol.shared.ControlSnapshotV2
import com.guardpulse.parentcontrol.shared.DeviceIdentity
import com.guardpulse.parentcontrol.shared.DesiredAppPolicy
import com.guardpulse.parentcontrol.shared.FirebaseRuntime
import com.guardpulse.parentcontrol.shared.FirebasePaths
import com.guardpulse.parentcontrol.shared.FirebaseServerClock
import com.guardpulse.parentcontrol.shared.PackageKeys
import com.guardpulse.parentcontrol.shared.PolicyDecider
import com.guardpulse.parentcontrol.shared.PolicyConstants
import com.guardpulse.parentcontrol.shared.SyncDesiredRevision
import com.guardpulse.parentcontrol.tv.MainActivity
import com.guardpulse.parentcontrol.tv.R
import com.guardpulse.parentcontrol.tv.apps.AppInventoryProvider
import com.guardpulse.parentcontrol.tv.apps.TvInstalledApp
import com.guardpulse.parentcontrol.tv.fallback.FallbackProtection
import com.guardpulse.parentcontrol.tv.fallback.FallbackStateStore
import com.guardpulse.parentcontrol.tv.fallback.PinRecord
import com.guardpulse.parentcontrol.tv.network.NetworkFilterController
import com.guardpulse.parentcontrol.tv.pairing.PairingManager
import com.guardpulse.parentcontrol.tv.policy.AppPolicy
import com.guardpulse.parentcontrol.tv.policy.DevicePolicyController
import com.guardpulse.parentcontrol.tv.policy.LocalPolicyStore
import com.guardpulse.parentcontrol.tv.system.BackgroundRestrictionStatus
import com.guardpulse.parentcontrol.tv.usage.UsageTracker
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await

private data class ModePolicy(
    val modeId: String,
    val name: String,
    val appPolicies: Map<String, AppPolicy>
)

class TvSyncService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var deviceId: String
    private lateinit var policyController: DevicePolicyController
    private lateinit var inventoryProvider: AppInventoryProvider
    private lateinit var localPolicyStore: LocalPolicyStore
    private lateinit var usageTracker: UsageTracker
    private lateinit var pairingManager: PairingManager
    private lateinit var fallbackStore: FallbackStateStore
    private lateinit var syncLocalStore: TvSyncLocalStore
    private lateinit var serverClock: FirebaseServerClock

    private var db: DatabaseReference? = null
    private var startedFirebase = false
    private var listenersAttached = false
    private var authRetryDelayMs = 5_000L
    private var lastSyncError: String? = null
    private var basePolicies: Map<String, AppPolicy> = emptyMap()
    private var modes: Map<String, ModePolicy> = emptyMap()
    private var activeModeId: String? = null
    private var activeModeName: String? = null
    private var safeModeUntil: Long = 0L
    private var syncEngine: TvSyncEngine? = null
    private var firebaseConnected = false
    private var lastUsageWritePackage: String? = null
    private val valueListeners = mutableListOf<Pair<Query, ValueEventListener>>()
    private val childListeners = mutableListOf<Pair<Query, ChildEventListener>>()
    private val retryFirebaseRunnable = Runnable {
        startedFirebase = false
        startFirebaseIfConfigured()
    }

    override fun onCreate() {
        super.onCreate()
        deviceId = DeviceIdentity.getOrCreate(this)
        policyController = DevicePolicyController(this)
        inventoryProvider = AppInventoryProvider(this)
        localPolicyStore = LocalPolicyStore(this)
        usageTracker = UsageTracker(this)
        pairingManager = PairingManager(this)
        fallbackStore = FallbackStateStore(this)
        syncLocalStore = TvSyncLocalStore(this)
        serverClock = FirebaseServerClock()
        serverClock.start()
        basePolicies = localPolicyStore.loadPolicies()
        activeModeId = localPolicyStore.activeModeId()
        activeModeName = localPolicyStore.activeModeName()
        safeModeUntil = localPolicyStore.safeModeUntil()
        fallbackStore.saveSafeMode(safeModeUntil)

        startForeground(NOTIFICATION_ID, buildNotification("Sync service starting"))
        policyController.applyHardening()
        NetworkFilterController.applyBlockedPackages(this, emptySet())
        applyPoliciesAndUpload()
        startFirebaseIfConfigured()
        handler.post(tickRunnable)
        handler.post(foregroundUsageRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESCAN_APPS -> enqueueSyncWork("inventory") { awaitInventoryUpload() }
            ACTION_RECONCILE -> enqueueSyncWork("state") { awaitPolicyReconciliation() }
            ACTION_FOREGROUND_CHANGED -> {
                enqueueSyncWork("foreground") {
                    awaitPolicyReconciliation()
                    lastUsageWritePackage = fallbackStore.liveForegroundSession()?.packageName
                    updateSecurityRuntime()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        syncEngine?.stop()
        syncEngine = null
        serverClock.stop()
        valueListeners.forEach { (ref, listener) -> ref.removeEventListener(listener) }
        childListeners.forEach { (ref, listener) -> ref.removeEventListener(listener) }
        valueListeners.clear()
        childListeners.clear()
        listenersAttached = false
        super.onDestroy()
    }

    private fun startFirebaseIfConfigured() {
        if (startedFirebase) return
        val status = FirebaseRuntime.initialize(this)
        if (!status.configured) return
        startedFirebase = true

        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.let { user ->
            onFirebaseReady(user)
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                val user = result.user ?: return@addOnSuccessListener
                onFirebaseReady(user)
            }
            .addOnFailureListener { error ->
                recordSyncError(error.message ?: "Firebase anonymous sign-in failed")
                scheduleFirebaseRetry()
            }
    }

    private fun onFirebaseReady(user: FirebaseUser) {
        authRetryDelayMs = 5_000L
        lastSyncError = null
        db = FirebaseDatabase.getInstance().reference
        startSyncEngine()
        registerDevice(user.uid)
        recoverPairingStatus()
        attachFirebaseListeners()
        enqueueSyncWork("startup") {
            awaitInventoryUpload()
            updateHeartbeat()
        }
    }

    private fun startSyncEngine() {
        if (syncEngine != null) return
        syncEngine = TvSyncEngine(
            database = FirebaseDatabase.getInstance(),
            deviceId = deviceId,
            localStore = syncLocalStore,
            callback = object : TvSyncEngine.Callback {
                override suspend fun onConnectionChanged(
                    connected: Boolean,
                    sessionId: String?
                ): Boolean {
                    firebaseConnected = connected
                    return !connected || onFirebaseReconnected(sessionId)
                }

                override suspend fun onControlReady(
                    snapshot: ControlSnapshotV2,
                    desired: SyncDesiredRevision?,
                    generation: Long
                ) {
                    applyV2Control(snapshot, desired, generation)
                }

                override suspend fun onControlRejected(revisionId: String?, error: String) {
                    recordSyncError("V2 control rejected: $error", "control")
                    writeFailedRevision(revisionId, error)
                }

                override suspend fun onSyncListenerError(channel: String, error: DatabaseError) {
                    recordSyncError("$channel listener cancelled: ${error.message}", channel)
                }
            }
        ).also { it.start() }
    }

    private fun enqueueSyncWork(channel: String, operation: suspend () -> Unit) {
        val engine = syncEngine
        if (engine != null) {
            engine.submit(channel, operation)
        } else {
            handler.post {
                when (channel) {
                    "inventory" -> uploadAppInventory()
                    "usage" -> uploadForegroundUsage()
                    else -> applyPoliciesAndUpload()
                }
            }
        }
    }

    private suspend fun awaitPolicyReconciliation(
        appliedRevision: SyncDesiredRevision? = null
    ) {
        suspendCancellableCoroutine { continuation ->
            applyPoliciesAndUpload(appliedRevision = appliedRevision) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    private suspend fun awaitInventoryUpload() {
        suspendCancellableCoroutine { continuation ->
            uploadAppInventory {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    private suspend fun awaitForegroundUsageUpload() {
        suspendCancellableCoroutine { continuation ->
            uploadForegroundUsage {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    private fun scheduleFirebaseRetry() {
        handler.removeCallbacks(retryFirebaseRunnable)
        handler.postDelayed(retryFirebaseRunnable, authRetryDelayMs)
        authRetryDelayMs = (authRetryDelayMs * 2).coerceAtMost(5 * 60_000L)
    }

    private fun attachFirebaseListeners() {
        if (listenersAttached) return
        listenersAttached = true
        attachPairingListener()
        attachPolicyListener()
        attachModesListener()
        attachActiveModeListener()
        attachSafeModeListener()
        attachSecurityListener()
        attachCommandListener()
    }

    private fun scheduleListenerRecovery() {
        handler.post {
            valueListeners.forEach { (ref, listener) -> ref.removeEventListener(listener) }
            childListeners.forEach { (ref, listener) -> ref.removeEventListener(listener) }
            valueListeners.clear()
            childListeners.clear()
            listenersAttached = false
            handler.removeCallbacks(retryFirebaseRunnable)
            handler.postDelayed({ attachFirebaseListeners() }, authRetryDelayMs)
            authRetryDelayMs = (authRetryDelayMs * 2).coerceAtMost(5 * 60_000L)
        }
    }

    private fun registerValueListener(query: Query, listener: ValueEventListener) {
        val serialized = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val engine = syncEngine
                if (engine == null) {
                    listener.onDataChange(snapshot)
                } else {
                    engine.submit { listener.onDataChange(snapshot) }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                listener.onCancelled(error)
            }
        }
        query.addValueEventListener(serialized)
        valueListeners += query to serialized
    }

    private fun registerChildListener(query: Query, listener: ChildEventListener) {
        val serialized = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                submit { listener.onChildAdded(snapshot, previousChildName) }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                submit { listener.onChildChanged(snapshot, previousChildName) }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                submit { listener.onChildRemoved(snapshot) }
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
                submit { listener.onChildMoved(snapshot, previousChildName) }
            }

            override fun onCancelled(error: DatabaseError) {
                listener.onCancelled(error)
            }

            private fun submit(operation: () -> Unit) {
                val engine = syncEngine
                if (engine == null) {
                    operation()
                } else {
                    engine.submit { operation() }
                }
            }
        }
        query.addChildEventListener(serialized)
        childListeners += query to serialized
    }

    private fun recordSyncError(message: String?, channel: String = "firebase") {
        val redacted = redactError(message)
        lastSyncError = redacted
        syncLocalStore.saveLastError(channel, redacted)
        syncLocalStore.markChannelDirty(channel)
        db?.child(FirebasePaths.deviceSecurityRuntime(deviceId))
            ?.updateChildren(
                mapOf(
                    "lastSyncError" to redacted,
                    "updatedAt" to ServerValue.TIMESTAMP
                )
            )
        db?.child(FirebasePaths.deviceSyncRuntime(deviceId))
            ?.updateChildren(
                mapOf(
                    "lastFailedChannel" to channel,
                    "lastError" to redacted,
                    "lastErrorAt" to ServerValue.TIMESTAMP
                )
            )
    }

    private fun redactError(message: String?): String? {
        return message
            ?.replace(Regex("https?://\\S+"), "[redacted-url]")
            ?.replace(Regex("[A-Za-z0-9_-]{24,}"), "[redacted]")
            ?.take(200)
    }

    private fun clearSyncError() {
        lastSyncError = null
        syncLocalStore.saveLastError(null, null)
    }

    private fun markChannelSynced(channel: String) {
        syncLocalStore.markChannelClean(channel)
        if (syncLocalStore.dirtyChannels().isEmpty()) clearSyncError()
    }

    private suspend fun onFirebaseReconnected(sessionId: String?): Boolean {
        val root = db ?: return false
        fallbackStore.saveServerTimeOffset(serverClock.offsetMillis())
        FirebaseAuth.getInstance().currentUser?.uid?.let(::registerDevice)
        val runtimeRef = root.child(FirebasePaths.deviceSyncRuntime(deviceId))
        runtimeRef.onDisconnect().updateChildren(
            mapOf(
                "connected" to false,
                "disconnectedAt" to ServerValue.TIMESTAMP
            )
        )
        try {
            runtimeRef.updateChildren(
                mapOf(
                    "connected" to true,
                    "sessionId" to sessionId,
                    "protocolVersion" to PolicyConstants.SYNC_PROTOCOL_VERSION,
                    "connectedAt" to ServerValue.TIMESTAMP,
                    "lastSuccessAt" to ServerValue.TIMESTAMP
                )
            )
                .await()
        } catch (error: Exception) {
            recordSyncError(error.message ?: "Runtime session upload failed", "connection")
            return false
        }
        pairingManager.pairedParentUid()?.let { parentUid ->
            root.child(FirebasePaths.userDevice(parentUid, deviceId))
                .onDisconnect()
                .updateChildren(mapOf("online" to false, "lastSeen" to ServerValue.TIMESTAMP))
        }
        // The control/desired listeners will replay the newest matching revision.
        // Reconcile runtime state here without acknowledging a potentially stale cached revision.
        awaitPolicyReconciliation()
        updateHeartbeat()
        awaitInventoryUpload()
        TamperEventQueue.flush(this)
        return true
    }

    private suspend fun applyV2Control(
        snapshot: ControlSnapshotV2,
        desired: SyncDesiredRevision?,
        generation: Long
    ) {
        fallbackStore.saveServerTimeOffset(serverClock.offsetMillis())
        basePolicies = snapshot.apps.mapValues { (_, rule) ->
            AppPolicy(rule.manualBlocked, rule.dailyLimitMinutes)
        }
        modes = snapshot.modes.mapValues { (_, mode) ->
            ModePolicy(
                modeId = mode.modeId,
                name = mode.name,
                appPolicies = mode.apps.mapValues { (_, rule) ->
                    AppPolicy(rule.manualBlocked, rule.dailyLimitMinutes)
                }
            )
        }
        activeModeId = snapshot.activeMode?.modeId
        activeModeName = snapshot.activeMode?.modeName
            ?: snapshot.activeMode?.modeId?.let { modes[it]?.name }
        safeModeUntil = snapshot.safeMode
            .takeIf { it.enabled && it.until > serverClock.now() }
            ?.until
            ?: 0L
        fallbackStore.savePin(
            snapshot.pin?.let { pin ->
                PinRecord(
                    salt = pin.salt,
                    hash = pin.hash,
                    version = pin.version,
                    algorithm = pin.algorithm,
                    iterations = pin.iterations,
                    updatedAt = pin.updatedAt ?: 0L
                )
            }
        )
        saveEffectivePolicies()
        syncLocalStore.savePendingAppliedRevision(snapshot.revisionId)
        db?.child(FirebasePaths.deviceSyncRuntime(deviceId))?.updateChildren(
            mapOf("lastPolicyReceivedAt" to ServerValue.TIMESTAMP)
        )
        suspendCancellableCoroutine { continuation ->
            applyPoliciesAndUpload(
                appliedRevision = desired ?: SyncDesiredRevision(
                    revisionId = snapshot.revisionId,
                    kind = PolicyConstants.REVISION_MIGRATION
                ),
                applyGeneration = generation
            ) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    private fun writeFailedRevision(revisionId: String?, error: String) {
        if (revisionId.isNullOrBlank()) return
        db?.child(FirebasePaths.deviceSyncApplied(deviceId))?.setValue(
            mapOf(
                "revisionId" to revisionId,
                "status" to PolicyConstants.SYNC_STATUS_FAILED,
                "appliedAt" to ServerValue.TIMESTAMP,
                "sessionId" to syncEngine?.currentSessionId(),
                "error" to error
            )
        )
    }

    private fun registerDevice(tvUid: String) {
        val meta = mapOf<String, Any?>(
            "deviceId" to deviceId,
            "tvUid" to tvUid,
            "label" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "androidSdk" to Build.VERSION.SDK_INT,
            "appVersion" to packageManager.getPackageInfo(packageName, 0).versionName,
            "lastRegisteredAt" to ServerValue.TIMESTAMP
        )
        db?.child(FirebasePaths.deviceMeta(deviceId))?.updateChildren(meta)
        db?.child(FirebasePaths.deviceHeartbeat(deviceId))
            ?.onDisconnect()
            ?.updateChildren(mapOf("online" to false, "lastSeen" to ServerValue.TIMESTAMP))
    }

    private fun attachPairingListener() {
        val ref = db?.child(FirebasePaths.pairRequests(deviceId))
            ?.orderByChild("status")
            ?.equalTo(PolicyConstants.PAIR_PENDING)
            ?: return
        cleanupPairRequests()
        registerChildListener(
            ref,
            object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    if (snapshot.child("status").getValue(String::class.java) != PolicyConstants.PAIR_PENDING) return
                    val parentUid = snapshot.child("parentUid").getValue(String::class.java) ?: return
                    if (!pairingManager.pairedParentUid().isNullOrBlank()) {
                        rejectPairRequest(snapshot, "TV is already paired")
                        return
                    }
                    val secret = snapshot.child("secret").getValue(String::class.java)
                    val code = snapshot.child("code").getValue(String::class.java)
                    val createdAt = snapshot.child("createdAt").getValue(Long::class.java) ?: 0L
                    if (!pairingManager.isValid(secret, code, createdAt)) {
                        val expiresAt = snapshot.child("expiresAt").getValue(Long::class.java) ?: 0L
                        val expired = expiresAt > 0L && serverClock.now() > expiresAt
                        rejectPairRequest(
                            snapshot,
                            if (expired) "Pair request expired" else "Invalid pairing credentials",
                            if (expired) PolicyConstants.PAIR_EXPIRED else PolicyConstants.PAIR_REJECTED,
                            rotateCredentials = expired
                        )
                        return
                    }

                    val deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                    val requestPath = FirebasePaths.pairRequest(deviceId, snapshot.key ?: return)
                    val ownerRef = db?.child(FirebasePaths.deviceMeta(deviceId))?.child("ownerUid") ?: return
                    ownerRef.runTransaction(object : Transaction.Handler {
                        override fun doTransaction(currentData: MutableData): Transaction.Result {
                            if (!currentData.getValue(String::class.java).isNullOrBlank()) {
                                return Transaction.abort()
                            }
                            currentData.value = parentUid
                            return Transaction.success(currentData)
                        }

                        override fun onComplete(
                            error: DatabaseError?,
                            committed: Boolean,
                            currentData: DataSnapshot?
                        ) {
                            if (error != null || !committed) {
                                rejectPairRequest(snapshot, "TV is already paired")
                                return
                            }
                            val updates = mapOf<String, Any?>(
                                "${FirebasePaths.deviceMeta(deviceId)}/pairedAt" to ServerValue.TIMESTAMP,
                                "${FirebasePaths.userDevice(parentUid, deviceId)}/deviceId" to deviceId,
                                "${FirebasePaths.userDevice(parentUid, deviceId)}/label" to deviceLabel,
                                "${FirebasePaths.userDevice(parentUid, deviceId)}/pairedAt" to ServerValue.TIMESTAMP,
                                "${FirebasePaths.userDevice(parentUid, deviceId)}/lastSeen" to ServerValue.TIMESTAMP,
                                "$requestPath/status" to PolicyConstants.PAIR_ACCEPTED,
                                "$requestPath/respondedAt" to ServerValue.TIMESTAMP
                            )
                            db?.updateChildren(updates)
                                ?.addOnSuccessListener {
                                    pairingManager.markPaired(parentUid)
                                    pairingManager.rotateCredentials()
                                    Log.i(TAG, "Pairing completed")
                                }
                                ?.addOnFailureListener {
                                    releaseOwnerClaim(parentUid)
                                    rejectPairRequest(snapshot, "Pairing completion failed", PolicyConstants.PAIR_FAILED)
                                }
                        }
                    })
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = Unit
                override fun onChildRemoved(snapshot: DataSnapshot) = Unit
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
                override fun onCancelled(error: DatabaseError) {
                    recordSyncError("Pairing listener cancelled: ${error.message}")
                    scheduleListenerRecovery()
                }
            }
        )
    }

    private fun recoverPairingStatus() {
        db?.child(FirebasePaths.deviceMeta(deviceId))
            ?.child("ownerUid")
            ?.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val ownerUid = snapshot.getValue(String::class.java)
                    if (!ownerUid.isNullOrBlank()) {
                        pairingManager.markPaired(ownerUid)
                        Log.i(TAG, "Recovered paired ownership from device metadata")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "Could not recover pairing status")
                    recordSyncError(error.message, "pairing")
                }
            })
    }

    private fun attachPolicyListener() {
        val ref = db?.child(FirebasePaths.devicePolicyApps(deviceId)) ?: return
        registerValueListener(
            ref,
            object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (syncEngine?.usesV2() == true) return
                    val policies = snapshot.children.mapNotNull { child ->
                        val packageName = child.child("packageName").getValue(String::class.java)
                            ?: runCatching { PackageKeys.decode(child.key.orEmpty()) }.getOrNull()
                            ?: return@mapNotNull null
                        val manualBlocked = child.child("manualBlocked").getValue(Boolean::class.java) ?: false
                        val limit = child.child("dailyLimitMinutes").getValue(Long::class.java)
                            ?.toInt()
                            ?.takeIf { it > 0 }
                        packageName to AppPolicy(manualBlocked, limit)
                    }.toMap()
                    basePolicies = policies
                    saveEffectivePolicies()
                    applyPoliciesAndUpload()
                }

                override fun onCancelled(error: DatabaseError) {
                    recordSyncError("Policy listener cancelled: ${error.message}")
                    scheduleListenerRecovery()
                }
            }
        )
    }

    private fun cleanupPairRequests() {
        val path = FirebasePaths.pairRequests(deviceId)
        db?.child(path)
            ?.orderByChild("createdAt")
            ?.endAt((serverClock.now() - PAIR_REQUEST_RETENTION_MS).toDouble())
            ?.limitToFirst(100)
            ?.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    snapshot.children.forEach { child ->
                        val status = child.child("status").getValue(String::class.java)
                        if (status in TERMINAL_PAIR_STATUSES) child.ref.removeValue()
                    }
                }

                override fun onCancelled(error: DatabaseError) = Unit
            })
    }

    private fun rejectPairRequest(
        snapshot: DataSnapshot,
        message: String,
        status: String = PolicyConstants.PAIR_REJECTED,
        rotateCredentials: Boolean = false
    ) {
        snapshot.ref.updateChildren(
            mapOf(
                "status" to status,
                "respondedAt" to ServerValue.TIMESTAMP,
                "error" to message
            )
        )
        if (rotateCredentials) pairingManager.rotateCredentials()
    }

    private fun releaseOwnerClaim(parentUid: String) {
        db?.child(FirebasePaths.deviceMeta(deviceId))
            ?.child("ownerUid")
            ?.runTransaction(object : Transaction.Handler {
                override fun doTransaction(currentData: MutableData): Transaction.Result {
                    if (currentData.getValue(String::class.java) != parentUid) return Transaction.abort()
                    currentData.value = null
                    return Transaction.success(currentData)
                }

                override fun onComplete(
                    error: DatabaseError?,
                    committed: Boolean,
                    currentData: DataSnapshot?
                ) = Unit
            })
    }

    private fun attachModesListener() {
        val ref = db?.child(FirebasePaths.devicePolicyModes(deviceId)) ?: return
        registerValueListener(
            ref,
            object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (syncEngine?.usesV2() == true) return
                    modes = snapshot.children.mapNotNull { modeSnapshot ->
                        val modeId = modeSnapshot.child("modeId").getValue(String::class.java)
                            ?: modeSnapshot.key
                            ?: return@mapNotNull null
                        val name = modeSnapshot.child("name").getValue(String::class.java)
                            ?.takeIf { it.isNotBlank() }
                            ?: "Mode"
                        val appPolicies = modeSnapshot.child("apps").children.mapNotNull appPolicy@{ appSnapshot ->
                            val packageName = appSnapshot.child("packageName").getValue(String::class.java)
                                ?: runCatching { PackageKeys.decode(appSnapshot.key.orEmpty()) }.getOrNull()
                                ?: return@appPolicy null
                            val manualBlocked = appSnapshot.child("manualBlocked").getValue(Boolean::class.java) ?: false
                            val limit = appSnapshot.child("dailyLimitMinutes").getValue(Long::class.java)
                                ?.toInt()
                                ?.takeIf { it > 0 }
                            packageName to AppPolicy(manualBlocked, limit)
                        }.toMap()
                        modeId to ModePolicy(modeId, name, appPolicies)
                    }.toMap()
                    activeModeName = activeModeId?.let { modes[it]?.name } ?: activeModeName
                    saveEffectivePolicies()
                    applyPoliciesAndUpload()
                }

                override fun onCancelled(error: DatabaseError) {
                    recordSyncError("Modes listener cancelled: ${error.message}")
                    scheduleListenerRecovery()
                }
            }
        )
    }

    private fun attachActiveModeListener() {
        val ref = db?.child(FirebasePaths.devicePolicyActiveMode(deviceId)) ?: return
        registerValueListener(
            ref,
            object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (syncEngine?.usesV2() == true) return
                    activeModeId = snapshot.child("modeId").getValue(String::class.java)?.takeIf { it.isNotBlank() }
                    activeModeName = snapshot.child("modeName").getValue(String::class.java)
                        ?: activeModeId?.let { modes[it]?.name }
                    localPolicyStore.saveActiveMode(activeModeId, activeModeName)
                    saveEffectivePolicies()
                    applyPoliciesAndUpload()
                    updateSecurityRuntime()
                }

                override fun onCancelled(error: DatabaseError) {
                    recordSyncError("Active mode listener cancelled: ${error.message}")
                    scheduleListenerRecovery()
                }
            }
        )
    }

    private fun attachSafeModeListener() {
        val ref = db?.child(FirebasePaths.deviceSecuritySafeMode(deviceId)) ?: return
        registerValueListener(
            ref,
            object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (syncEngine?.usesV2() == true) return
                    val enabled = snapshot.child("enabled").getValue(Boolean::class.java) ?: false
                    val until = snapshot.child("until").getValue(Long::class.java) ?: 0L
                    safeModeUntil = if (enabled && until > serverClock.now()) until else 0L
                    localPolicyStore.saveSafeMode(safeModeUntil)
                    fallbackStore.saveSafeMode(safeModeUntil)
                    applyPoliciesAndUpload()
                    updateSecurityRuntime()
                }

                override fun onCancelled(error: DatabaseError) {
                    recordSyncError("Safe Mode listener cancelled: ${error.message}")
                    scheduleListenerRecovery()
                }
            }
        )
    }

    private fun attachSecurityListener() {
        val ref = db?.child(FirebasePaths.deviceSecurityPin(deviceId)) ?: return
        registerValueListener(
            ref,
            object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (syncEngine?.usesV2() == true) return
                    val salt = snapshot.child("salt").getValue(String::class.java)
                    val hash = snapshot.child("hash").getValue(String::class.java)
                    if (salt.isNullOrBlank() || hash.isNullOrBlank()) {
                        fallbackStore.savePin(null)
                    } else {
                        fallbackStore.savePin(
                            PinRecord(
                                salt = salt,
                                hash = hash,
                                version = snapshot.child("version").getValue(Long::class.java)?.toInt()
                                    ?: com.guardpulse.parentcontrol.shared.PinHasher.LEGACY_VERSION,
                                algorithm = snapshot.child("algorithm").getValue(String::class.java),
                                iterations = snapshot.child("iterations").getValue(Long::class.java)?.toInt(),
                                updatedAt = snapshot.child("updatedAt").getValue(Long::class.java) ?: 0L
                            )
                        )
                    }
                    updateSecurityRuntime()
                }

                override fun onCancelled(error: DatabaseError) {
                    recordSyncError("Security listener cancelled: ${error.message}")
                    scheduleListenerRecovery()
                }
            }
        )
    }

    private fun attachCommandListener() {
        val ref = db?.child(FirebasePaths.deviceCommands(deviceId))
            ?.orderByChild("createdAt")
            ?.limitToLast(20)
            ?: return
        registerChildListener(
            ref,
            object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    handleCommand(snapshot)
                }

                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                    handleCommand(snapshot)
                }
                override fun onChildRemoved(snapshot: DataSnapshot) = Unit
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
                override fun onCancelled(error: DatabaseError) {
                    recordSyncError("Command listener cancelled: ${error.message}")
                    scheduleListenerRecovery()
                }
            }
        )
    }

    private fun handleCommand(snapshot: DataSnapshot) {
        val commandId = snapshot.key ?: return
        val type = snapshot.child("type").getValue(String::class.java) ?: return
        val status = snapshot.child("status").getValue(String::class.java)
        if (status != null && status != PolicyConstants.COMMAND_PENDING) return
        if (syncLocalStore.isCommandProcessed(commandId)) {
            snapshot.ref.updateChildren(
                mapOf("status" to PolicyConstants.COMMAND_DONE, "completedAt" to ServerValue.TIMESTAMP)
            )
            return
        }
        val createdAt = snapshot.child("createdAt").getValue(Long::class.java) ?: 0L
        val ttlMs = snapshot.child("ttlMs").getValue(Long::class.java)
            ?: PolicyConstants.commandTtlMs(type)
        if (createdAt <= 0L || serverClock.now() > createdAt + ttlMs) {
            snapshot.ref.updateChildren(
                mapOf("status" to PolicyConstants.COMMAND_EXPIRED, "completedAt" to ServerValue.TIMESTAMP)
            )
            return
        }
        snapshot.ref.runTransaction(
            object : Transaction.Handler {
                override fun doTransaction(currentData: MutableData): Transaction.Result {
                    val currentStatus = currentData.child("status").value as? String
                    if (currentStatus != null && currentStatus != PolicyConstants.COMMAND_PENDING) {
                        return Transaction.abort()
                    }
                    currentData.child("status").value = PolicyConstants.COMMAND_RUNNING
                    currentData.child("startedAt").value = serverClock.now()
                    currentData.child("sessionId").value = syncEngine?.currentSessionId()
                    return Transaction.success(currentData)
                }

                override fun onComplete(
                    error: DatabaseError?,
                    committed: Boolean,
                    currentData: DataSnapshot?
                ) {
                    if (error != null) {
                        recordSyncError("Command claim failed: ${error.message}", "command")
                        return
                    }
                    if (committed) {
                        processClaimedCommand(
                            commandId = commandId,
                            ref = snapshot.ref,
                            type = type,
                            packageName = snapshot.child("packageName").getValue(String::class.java)
                        )
                    }
                }
            },
            false
        )
    }

    private fun processClaimedCommand(
        commandId: String,
        ref: DatabaseReference,
        type: String,
        packageName: String?
    ) {
        when (type) {
            PolicyConstants.COMMAND_RESCAN_APPS -> uploadAppInventory { result ->
                finishCommand(commandId, ref, result)
            }
            PolicyConstants.COMMAND_RESET_TODAY -> {
                val currentUsage = usageTracker.effectiveUsageMillisToday(
                    fallbackStore.liveForegroundSession(),
                    fallbackStore.committedUsageMillisToday()
                )
                if (packageName == null) {
                    localPolicyStore.clearDailyLimitBlocks()
                    currentUsage.forEach { (pkg, usageMs) ->
                        localPolicyStore.saveUsageOffsetMs(pkg, usageMs)
                    }
                } else {
                    localPolicyStore.clearDailyLimitBlocks(packageName)
                    localPolicyStore.saveUsageOffsetMs(packageName, currentUsage[packageName] ?: 0L)
                }
                applyPoliciesAndUpload { result -> finishCommand(commandId, ref, result) }
            }
            PolicyConstants.COMMAND_UNPAIR -> {
                val parentUid = pairingManager.pairedParentUid()
                val updates = mutableMapOf<String, Any?>(
                    "${FirebasePaths.deviceMeta(deviceId)}/ownerUid" to null,
                    "${FirebasePaths.deviceMeta(deviceId)}/pairedAt" to null,
                    "${FirebasePaths.deviceCommands(deviceId)}/$commandId/status" to PolicyConstants.COMMAND_DONE,
                    "${FirebasePaths.deviceCommands(deviceId)}/$commandId/completedAt" to ServerValue.TIMESTAMP,
                    "${FirebasePaths.deviceCommands(deviceId)}/$commandId/error" to null
                )
                if (!parentUid.isNullOrBlank()) {
                    updates[FirebasePaths.userDevice(parentUid, deviceId)] = null
                }
                val root = db
                if (root == null) {
                    finishCommand(commandId, ref, Result.failure(IllegalStateException("Firebase is unavailable")))
                } else {
                    root.updateChildren(updates)
                        .addOnSuccessListener {
                            pairingManager.clearPairedParent()
                            syncLocalStore.markCommandProcessed(commandId)
                        }
                        .addOnFailureListener { finishCommand(commandId, ref, Result.failure(it)) }
                }
            }
            PolicyConstants.COMMAND_OPEN_SETUP -> {
                runCatching { openSetup() }
                    .fold(
                        onSuccess = { finishCommand(commandId, ref, Result.success(Unit)) },
                        onFailure = { finishCommand(commandId, ref, Result.failure(it)) }
                    )
            }
            else -> finishCommand(
                commandId,
                ref,
                Result.failure(IllegalArgumentException("Unknown command type: $type"))
            )
        }
    }

    private fun finishCommand(commandId: String, ref: DatabaseReference, result: Result<Unit>) {
        val updates = mutableMapOf<String, Any?>(
            "status" to if (result.isSuccess) PolicyConstants.COMMAND_DONE else PolicyConstants.COMMAND_FAILED,
            "completedAt" to ServerValue.TIMESTAMP,
            "error" to result.exceptionOrNull()?.message
        )
        ref.updateChildren(updates)
            .addOnSuccessListener {
                markChannelSynced("command")
                if (result.isSuccess) syncLocalStore.markCommandProcessed(commandId)
                db?.child(FirebasePaths.deviceSyncRuntime(deviceId))?.updateChildren(
                    mapOf("lastCommandWriteAt" to ServerValue.TIMESTAMP, "lastSuccessAt" to ServerValue.TIMESTAMP)
                )
            }
            .addOnFailureListener { error ->
                recordSyncError(error.message ?: "Command completion upload failed", "command")
            }
    }

    private fun uploadAppInventory(onComplete: ((Result<Unit>) -> Unit)? = null) {
        val apps = inventoryProvider.listLaunchableApps()
        val payload = apps.associate { PackageKeys.encode(it.packageName) to it.toFirebaseMap() }
        val root = db ?: run {
            onComplete?.invoke(Result.failure(IllegalStateException("Firebase is unavailable")))
            applyPoliciesAndUpload(apps)
            return
        }
        root.child(FirebasePaths.deviceApps(deviceId)).setValue(payload)
            .addOnSuccessListener {
                markChannelSynced("inventory")
                root.child(FirebasePaths.deviceSyncRuntime(deviceId)).updateChildren(
                    mapOf(
                        "inventoryRevision" to java.util.UUID.randomUUID().toString(),
                        "lastInventoryWriteAt" to ServerValue.TIMESTAMP,
                        "lastSuccessAt" to ServerValue.TIMESTAMP
                    )
                )
                applyPoliciesAndUpload(apps, onComplete = onComplete)
            }
            .addOnFailureListener { error ->
                recordSyncError(error.message ?: "Inventory upload failed", "inventory")
                onComplete?.invoke(Result.failure(error))
            }
    }

    private fun updateHeartbeat() {
        fallbackStore.saveServerTimeOffset(serverClock.offsetMillis())
        val mode = FallbackProtection.enforcementMode(this)
        val adminSetupAvailable = FallbackProtection.isDeviceAdminSetupAvailable(this)
        val accessibility = FallbackProtection.isAccessibilityEnabled(this)
        val usageAccess = usageTracker.hasUsageAccess()
        val vpnStatus = NetworkFilterController.refreshPreparedStatus(this)
        val backgroundUnrestricted = BackgroundRestrictionStatus.isBatteryUnrestricted(this)
        val pinConfigured = fallbackStore.loadPin() != null
        val safeModeActive = fallbackStore.isSafeModeActive()
        val protectionHealthy = mode == PolicyConstants.ENFORCEMENT_DEVICE_OWNER ||
            ((!adminSetupAvailable || policyController.isAdminActive()) &&
                accessibility &&
                usageAccess &&
                backgroundUnrestricted &&
                pinConfigured)

        val heartbeat = mapOf(
            "online" to true,
            "lastSeen" to ServerValue.TIMESTAMP,
            "usageAccess" to usageAccess,
            "vpnActive" to vpnStatus.active,
            "vpnBlockedCount" to vpnStatus.blockedCount,
            "backgroundUnrestricted" to backgroundUnrestricted,
            "deviceOwner" to policyController.isDeviceOwner(),
            "enforcementMode" to mode,
            "protectionHealthy" to protectionHealthy,
            "safeModeActive" to safeModeActive,
            "activeModeId" to activeModeId,
            "activeModeName" to activeModeName
        )
        db?.child(FirebasePaths.deviceHeartbeat(deviceId))?.updateChildren(heartbeat)
            ?.addOnSuccessListener {
                markChannelSynced("heartbeat")
                db?.child(FirebasePaths.deviceSyncRuntime(deviceId))?.updateChildren(
                    mapOf(
                        "connected" to firebaseConnected,
                        "sessionId" to syncEngine?.currentSessionId(),
                        "protocolVersion" to PolicyConstants.SYNC_PROTOCOL_VERSION,
                        "lastHeartbeatWriteAt" to ServerValue.TIMESTAMP,
                        "lastSuccessAt" to ServerValue.TIMESTAMP
                    )
                )
            }
            ?.addOnFailureListener { error ->
                recordSyncError(error.message ?: "Heartbeat upload failed", "heartbeat")
            }
        pairingManager.pairedParentUid()?.let { parentUid ->
            db?.child(FirebasePaths.userDevice(parentUid, deviceId))?.updateChildren(
                mapOf(
                    "deviceId" to deviceId,
                    "lastSeen" to ServerValue.TIMESTAMP,
                    "online" to true,
                    "enforcementMode" to mode,
                    "protectionHealthy" to protectionHealthy
                )
            )?.addOnFailureListener { error ->
                recordSyncError(error.message ?: "Parent heartbeat upload failed", "heartbeat")
            }
            db?.child(FirebasePaths.userDevice(parentUid, deviceId))
                ?.onDisconnect()
                ?.updateChildren(mapOf("online" to false, "lastSeen" to ServerValue.TIMESTAMP))
        }
        updateSecurityRuntime()
    }

    private fun updateSecurityRuntime() {
        val adminActive = policyController.isAdminActive()
        val adminSetupAvailable = FallbackProtection.isDeviceAdminSetupAvailable(this)
        val accessibility = FallbackProtection.isAccessibilityEnabled(this)
        val usage = usageTracker.hasUsageAccess()
        val mode = FallbackProtection.enforcementMode(this)
        val vpnStatus = NetworkFilterController.refreshPreparedStatus(this)
        val backgroundUnrestricted = BackgroundRestrictionStatus.isBatteryUnrestricted(this)
        val safeModeActive = fallbackStore.isSafeModeActive()
        val pinRecord = fallbackStore.loadPin()
        db?.child(FirebasePaths.deviceSecurityRuntime(deviceId))?.updateChildren(
            mapOf(
                "enforcementMode" to mode,
                "deviceOwner" to policyController.isDeviceOwner(),
                "deviceAdmin" to adminActive,
                "deviceAdminSetupAvailable" to adminSetupAvailable,
                "accessibility" to accessibility,
                "usageAccess" to usage,
                "vpnPrepared" to vpnStatus.prepared,
                "vpnActive" to vpnStatus.active,
                "vpnBlockedCount" to vpnStatus.blockedCount,
                "vpnLastError" to vpnStatus.lastError,
                "backgroundUnrestricted" to backgroundUnrestricted,
                "pinConfigured" to (pinRecord != null),
                "pinHashVersion" to (pinRecord?.version ?: 0),
                "protectionHealthy" to (
                    mode == PolicyConstants.ENFORCEMENT_DEVICE_OWNER ||
                        ((adminActive || !adminSetupAvailable) &&
                            accessibility &&
                            usage &&
                            backgroundUnrestricted &&
                            pinRecord != null)
                    ),
                "lastForegroundPackage" to fallbackStore.lastForeground(),
                "safeModeActive" to safeModeActive,
                "safeModeUntil" to fallbackStore.safeModeUntil().takeIf { it > 0L },
                "activeModeId" to activeModeId,
                "activeModeName" to activeModeName,
                "lastSyncError" to lastSyncError,
                "updatedAt" to ServerValue.TIMESTAMP
            )
        )?.addOnSuccessListener {
            markChannelSynced("health")
            db?.child(FirebasePaths.deviceSyncRuntime(deviceId))?.updateChildren(
                mapOf("lastHealthWriteAt" to ServerValue.TIMESTAMP, "lastSuccessAt" to ServerValue.TIMESTAMP)
            )
        }?.addOnFailureListener { error ->
            recordSyncError(error.message ?: "Health upload failed", "health")
        }
        uploadMissingProtectionEvents(adminActive, adminSetupAvailable, accessibility, usage, vpnStatus)
    }

    private fun uploadMissingProtectionEvents(
        adminActive: Boolean,
        adminSetupAvailable: Boolean,
        accessibility: Boolean,
        usageAccess: Boolean,
        @Suppress("UNUSED_PARAMETER") vpnStatus: com.guardpulse.parentcontrol.tv.network.NetworkFilterStatus
    ) {
        if (policyController.isDeviceOwner()) return
        val missing = mutableListOf<String>()
        if (adminSetupAvailable && !adminActive) missing += PolicyConstants.TAMPER_ADMIN_DISABLED
        if (!accessibility) missing += PolicyConstants.TAMPER_ACCESSIBILITY_DISABLED
        if (!usageAccess) missing += PolicyConstants.TAMPER_USAGE_ACCESS_MISSING
        missing.forEach { type ->
            if (!fallbackStore.shouldReportTamper(type)) return@forEach
            TamperEventQueue.enqueue(
                this,
                type,
                "Fallback protection is incomplete: $type"
            )
        }
    }

    private fun applyPoliciesAndUpload(
        apps: List<TvInstalledApp> = inventoryProvider.listLaunchableApps(),
        appliedRevision: SyncDesiredRevision? = null,
        applyGeneration: Long = 0L,
        onComplete: ((Result<Unit>) -> Unit)? = null
    ) {
        expireSafeModeIfNeeded()
        saveEffectivePolicies()
        val policies = localPolicyStore.loadPolicies()
        val liveSession = fallbackStore.liveForegroundSession()
        val rawUsageMs = usageTracker.effectiveUsageMillisToday(
            liveSession,
            fallbackStore.committedUsageMillisToday()
        )
        val usageOffsetsMs = localPolicyStore.loadUsageOffsetsMs()
        val dailyBlocks = localPolicyStore.loadDailyLimitBlocks().toMutableSet()
        val states = mutableMapOf<String, Any?>()
        val enforcementMode = FallbackProtection.enforcementMode(this)
        val decisionsByPackage = mutableMapOf<String, com.guardpulse.parentcontrol.shared.PolicyDecision>()
        val usageMsByPackage = mutableMapOf<String, Long>()
        val networkBlockedPackages = emptySet<String>()
        val controlRevisionId = appliedRevision?.revisionId ?: syncLocalStore.lastAppliedV2Revision()

        apps.forEach { app ->
            val policy = effectivePolicy(app.packageName, policies)
            val appRawUsageMs = rawUsageMs[app.packageName] ?: 0L
            val usageMs = (appRawUsageMs - (usageOffsetsMs[app.packageName] ?: 0L)).coerceAtLeast(0L)
            val usageMinutes = usageMs / 60_000L
            val limit = policy.dailyLimitMinutes
            if (limit != null && usageMs >= limit * 60_000L) {
                localPolicyStore.markDailyLimitBlocked(app.packageName)
                dailyBlocks.add(app.packageName)
            }

            val decision = PolicyDecider.decide(
                policy = DesiredAppPolicy(
                    packageName = app.packageName,
                    manualBlocked = policy.manualBlocked,
                    dailyLimitMinutes = policy.dailyLimitMinutes
                ),
                usageMinutesToday = usageMinutes,
                alreadyDailyBlocked = app.packageName in dailyBlocks && policy.dailyLimitMinutes != null
            )
            if (decision.dailyLimitBlocked) {
                dailyBlocks.add(app.packageName)
            }
            decisionsByPackage[app.packageName] = decision
            usageMsByPackage[app.packageName] = usageMs
        }

        val vpnStatus = NetworkFilterController.applyBlockedPackages(this, networkBlockedPackages)

        apps.forEach { app ->
            val policy = effectivePolicy(app.packageName, policies)
            val decision = decisionsByPackage[app.packageName]
                ?: com.guardpulse.parentcontrol.shared.PolicyDecision(false, null, false, false)
            val usageMs = usageMsByPackage[app.packageName] ?: 0L
            val usageMinutes = usageMs / 60_000L
            val appRawUsageMs = rawUsageMs[app.packageName] ?: 0L
            val limit = policy.dailyLimitMinutes
            val networkBlocked = app.packageName in networkBlockedPackages
            val vpnApplied = networkBlocked && vpnStatus.active
            val sourceLocked = decision.shouldBlock && app.packageName in PolicyConstants.sourceLockPackages
            val settingsSectionLocked = decision.shouldBlock &&
                app.packageName in PolicyConstants.settingsSectionLockPackages
            val primarySettingsLocked = decision.shouldBlock && app.packageName in PolicyConstants.primarySettingsPackages
            val riskySettingsLocked = primarySettingsLocked ||
                (app.packageName in PolicyConstants.riskySettingsPackages &&
                    app.packageName !in PolicyConstants.primarySettingsPackages)
            val normalAppLocked = decision.shouldBlock &&
                app.blockable &&
                app.packageName !in PolicyConstants.virtualPolicyPackages &&
                app.packageName !in PolicyConstants.sourceLockPackages &&
                app.packageName !in PolicyConstants.primarySettingsPackages &&
                app.packageName != packageName
            val safeModeActive = fallbackStore.isSafeModeActive()
            val lockBlocked = !safeModeActive &&
                (riskySettingsLocked || sourceLocked || settingsSectionLocked || normalAppLocked)
            val lockReason = when {
                safeModeActive -> null
                sourceLocked -> PolicyConstants.BLOCK_REASON_SOURCE_LOCK
                settingsSectionLocked -> PolicyConstants.BLOCK_REASON_SETTINGS_SECTION
                riskySettingsLocked -> PolicyConstants.BLOCK_REASON_RISKY_SETTINGS
                normalAppLocked -> decision.reason ?: PolicyConstants.BLOCK_REASON_MANUAL
                else -> null
            }
            var lastError: String? = null
            if (decision.shouldBlock && !app.blockable) {
                lastError = "Protected package: ${app.protectedReason}"
            }

            states[PackageKeys.encode(app.packageName)] = mapOf(
                "packageName" to app.packageName,
                "suspended" to policyController.isSuspended(app.packageName),
                "requestedSuspended" to false,
                "manualBlocked" to decision.manualBlocked,
                "dailyLimitBlocked" to decision.dailyLimitBlocked,
                "networkBlocked" to networkBlocked,
                "vpnApplied" to vpnApplied,
                "vpnActive" to vpnStatus.active,
                "lockBlocked" to lockBlocked,
                "lockReason" to lockReason,
                "vpnLastError" to null,
                "blockReason" to decision.reason,
                "enforcementMode" to enforcementMode,
                "fallbackLocked" to lockBlocked,
                "usageMinutesToday" to usageMinutes,
                "usageMsToday" to usageMs,
                "rawUsageMinutesToday" to appRawUsageMs / 60_000L,
                "rawUsageMsToday" to appRawUsageMs,
                "usageCapturedAt" to ServerValue.TIMESTAMP,
                "foregroundActive" to (liveSession?.packageName == app.packageName),
                "foregroundStartedAt" to liveSession
                    ?.takeIf { it.packageName == app.packageName }
                    ?.let { serverClock.now() - (System.currentTimeMillis() - it.startedAt).coerceAtLeast(0L) },
                "controlRevisionId" to controlRevisionId,
                "dailyLimitMinutes" to limit,
                "blockable" to app.blockable,
                "lastError" to lastError,
                "updatedAt" to ServerValue.TIMESTAMP
            )
        }
        PolicyConstants.deprecatedVirtualPolicyPackages.forEach { packageName ->
            states[PackageKeys.encode(packageName)] = null
        }

        val root = db ?: run {
            onComplete?.invoke(Result.failure(IllegalStateException("Firebase is unavailable")))
            return
        }
        val updates = mutableMapOf<String, Any?>()
        states.forEach { (packageKey, value) ->
            updates["${FirebasePaths.deviceStateApps(deviceId)}/$packageKey"] = value
        }
        updates["${FirebasePaths.deviceSyncRuntime(deviceId)}/lastStateWriteAt"] = ServerValue.TIMESTAMP
        updates["${FirebasePaths.deviceSyncRuntime(deviceId)}/lastSuccessAt"] = ServerValue.TIMESTAMP
        updates["${FirebasePaths.deviceSyncRuntime(deviceId)}/lastFailedChannel"] = null
        updates["${FirebasePaths.deviceSyncRuntime(deviceId)}/lastError"] = null
        updates["${FirebasePaths.deviceSyncRuntime(deviceId)}/lastErrorAt"] = null
        val appliedSessionId = syncEngine?.currentSessionId()
        val shouldAcknowledge = appliedRevision != null &&
            !appliedSessionId.isNullOrBlank() &&
            (applyGeneration == 0L ||
                syncEngine?.isCurrent(applyGeneration, appliedRevision.revisionId) == true)
        if (shouldAcknowledge && appliedRevision != null) {
            updates[FirebasePaths.deviceSyncApplied(deviceId)] = mapOf(
                "revisionId" to appliedRevision.revisionId,
                "status" to PolicyConstants.SYNC_STATUS_APPLIED,
                "appliedAt" to ServerValue.TIMESTAMP,
                "sessionId" to appliedSessionId
            )
            updates["${FirebasePaths.deviceSyncRuntime(deviceId)}/lastPolicyAppliedAt"] = ServerValue.TIMESTAMP
        }
        root.updateChildren(updates)
            .addOnSuccessListener {
                if (shouldAcknowledge && appliedRevision != null) {
                    syncLocalStore.saveAppliedV2Revision(
                        appliedRevision.revisionId,
                        appliedSessionId
                    )
                    syncLocalStore.savePendingAppliedRevision(null)
                }
                markChannelSynced("state")
                onComplete?.invoke(Result.success(Unit))
            }
            .addOnFailureListener { error ->
                recordSyncError(error.message ?: "App state upload failed", "state")
                onComplete?.invoke(Result.failure(error))
            }
    }

    private fun effectivePolicy(
        packageName: String,
        policies: Map<String, AppPolicy>
    ): AppPolicy {
        return policies[packageName] ?: if (PolicyConstants.isDefaultLocked(packageName)) {
            AppPolicy(manualBlocked = true)
        } else {
            AppPolicy()
        }
    }

    private fun uploadForegroundUsage(onComplete: ((Result<Unit>) -> Unit)? = null) {
        val root = db ?: run {
            onComplete?.invoke(Result.failure(IllegalStateException("Firebase is unavailable")))
            return
        }
        val session = fallbackStore.liveForegroundSession() ?: run {
            onComplete?.invoke(Result.success(Unit))
            return
        }
        val packageName = session.packageName
        val rawUsageMs = usageTracker.effectiveUsageMillisToday(
            session,
            fallbackStore.committedUsageMillisToday()
        )[packageName] ?: run {
            onComplete?.invoke(Result.success(Unit))
            return
        }
        val offsetMs = localPolicyStore.loadUsageOffsetsMs()[packageName] ?: 0L
        val usageMs = (rawUsageMs - offsetMs).coerceAtLeast(0L)
        val statePath = FirebasePaths.deviceStateApp(deviceId, packageName)
        val updates = mutableMapOf<String, Any?>(
            "$statePath/usageMinutesToday" to usageMs / 60_000L,
            "$statePath/usageMsToday" to usageMs,
            "$statePath/rawUsageMinutesToday" to rawUsageMs / 60_000L,
            "$statePath/rawUsageMsToday" to rawUsageMs,
            "$statePath/usageCapturedAt" to ServerValue.TIMESTAMP,
            "$statePath/foregroundActive" to true,
            "$statePath/foregroundStartedAt" to (
                serverClock.now() - (System.currentTimeMillis() - session.startedAt).coerceAtLeast(0L)
            ),
            "$statePath/updatedAt" to ServerValue.TIMESTAMP,
            "${FirebasePaths.deviceSyncRuntime(deviceId)}/lastUsageWriteAt" to ServerValue.TIMESTAMP,
            "${FirebasePaths.deviceSyncRuntime(deviceId)}/lastSuccessAt" to ServerValue.TIMESTAMP
        )
        lastUsageWritePackage
            ?.takeIf { it != packageName }
            ?.let { previousPackage ->
                val previousPath = FirebasePaths.deviceStateApp(deviceId, previousPackage)
                updates["$previousPath/foregroundActive"] = false
                updates["$previousPath/updatedAt"] = ServerValue.TIMESTAMP
            }
        lastUsageWritePackage = packageName
        root.updateChildren(updates)
            .addOnSuccessListener {
                markChannelSynced("usage")
                onComplete?.invoke(Result.success(Unit))
            }
            .addOnFailureListener { error ->
                recordSyncError(error.message ?: "Foreground usage upload failed", "usage")
                onComplete?.invoke(Result.failure(error))
            }
    }

    private fun saveEffectivePolicies() {
        localPolicyStore.savePolicies(effectivePolicies())
        localPolicyStore.saveActiveMode(activeModeId, activeModeName)
        localPolicyStore.saveSafeMode(safeModeUntil)
        fallbackStore.saveSafeMode(safeModeUntil)
    }

    private fun effectivePolicies(): Map<String, AppPolicy> {
        val modeId = activeModeId
        val activeMode = if (modeId.isNullOrBlank()) null else modes[modeId]
        if (activeMode == null) return basePolicies
        val merged = activeMode.appPolicies.toMutableMap()
        PolicyConstants.defaultLockedPackages.forEach { packageName ->
            merged.putIfAbsent(packageName, AppPolicy(manualBlocked = true))
        }
        return merged
    }

    private fun expireSafeModeIfNeeded() {
        if (safeModeUntil > 0L && safeModeUntil <= serverClock.now()) {
            safeModeUntil = 0L
            localPolicyStore.saveSafeMode(0L)
            fallbackStore.saveSafeMode(0L)
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            policyController.applyHardening()
            enqueueSyncWork("heartbeat-cycle") {
                awaitPolicyReconciliation()
                updateHeartbeat()
            }
            handler.postDelayed(this, PolicyConstants.HEARTBEAT_INTERVAL_MS)
        }
    }

    private val foregroundUsageRunnable = object : Runnable {
        override fun run() {
            enqueueSyncWork("usage") { awaitForegroundUsageUpload() }
            handler.postDelayed(this, PolicyConstants.FOREGROUND_USAGE_UPLOAD_INTERVAL_MS)
        }
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Device service sync",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.drawable.ic_stat_control)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "GuardPulseTvSync"
        const val ACTION_RESCAN_APPS = "com.guardpulse.parentcontrol.tv.action.RESCAN_APPS"
        const val ACTION_RECONCILE = "com.guardpulse.parentcontrol.tv.action.RECONCILE"
        const val ACTION_FOREGROUND_CHANGED = "com.guardpulse.parentcontrol.tv.action.FOREGROUND_CHANGED"
        private const val CHANNEL_ID = "tv_parental_control"
        private const val NOTIFICATION_ID = 1001
        private const val PAIR_REQUEST_RETENTION_MS = 7L * 24 * 60 * 60_000
        private val TERMINAL_PAIR_STATUSES = setOf(
            PolicyConstants.PAIR_ACCEPTED,
            PolicyConstants.PAIR_REJECTED,
            PolicyConstants.PAIR_EXPIRED,
            PolicyConstants.PAIR_FAILED
        )
    }

    private fun openSetup() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }
}
