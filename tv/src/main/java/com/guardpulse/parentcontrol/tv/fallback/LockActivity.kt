package com.guardpulse.parentcontrol.tv.fallback

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.guardpulse.parentcontrol.shared.DeviceIdentity
import com.guardpulse.parentcontrol.shared.FirebaseBootstrap
import com.guardpulse.parentcontrol.shared.FirebasePaths
import com.guardpulse.parentcontrol.shared.FirebaseServerClock
import com.guardpulse.parentcontrol.shared.PinHasher
import com.guardpulse.parentcontrol.shared.PolicyConstants
import com.guardpulse.parentcontrol.tv.R
import com.guardpulse.parentcontrol.tv.policy.LocalPolicyStore
import com.guardpulse.parentcontrol.tv.sync.TamperEventQueue
import com.guardpulse.parentcontrol.tv.system.SystemTimeGuard

class LockActivity : Activity() {
    private lateinit var fallbackStore: FallbackStateStore
    private lateinit var localPolicyStore: LocalPolicyStore
    private lateinit var serverClock: FirebaseServerClock
    private lateinit var packageNameToUnlock: String
    private lateinit var reason: String
    private var settingsSectionKey: String? = null
    private var pin = ""
    private var statusText: TextView? = null
    private var pinDotsView: TextView? = null
    private var requestId: String? = null
    private var unlockRequestRef: DatabaseReference? = null
    private var unlockRequestListener: ValueEventListener? = null
    private val keypadButtons = mutableListOf<MaterialButton>()
    private var remoteUnlockButton: MaterialButton? = null
    private val guardNavy = Color.rgb(3, 22, 54)
    private val lockSurface = Color.rgb(30, 41, 59)
    private val lockPanel = Color.rgb(15, 23, 42)
    private val actionBlue = Color.rgb(59, 130, 246)
    private val mutedText = Color.rgb(203, 213, 225)
    private val errorSoft = Color.rgb(76, 29, 49)
    private val outlineSoft = Color.rgb(71, 85, 105)
    private val lockText = Color.WHITE
    private val mainHandler = Handler(Looper.getMainLooper())
    private val policyChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "policies" ||
            key == "safeModeUntil" ||
            key == "activeModeId" ||
            key?.startsWith("dailyBlocks:") == true ||
            key?.startsWith("usageOffsets") == true
        ) {
            mainHandler.post { finishIfNoLongerBlocked() }
        }
    }
    private val autoDismissRunnable = object : Runnable {
        override fun run() {
            finishIfNoLongerBlocked()
            if (!isFinishing && !isDestroyed) {
                mainHandler.postDelayed(this, AUTO_DISMISS_CHECK_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fallbackStore = FallbackStateStore(this)
        localPolicyStore = LocalPolicyStore(this)
        serverClock = FirebaseServerClock()
        serverClock.start()
        bindLockIntent(intent)
        localPolicyStore.registerChangeListener(policyChangeListener)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        detachRemoteListeners()
        bindLockIntent(intent)
        startAutoDismissChecks()
    }

    override fun onResume() {
        super.onResume()
        startAutoDismissChecks()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        super.onPause()
    }

    override fun onBackPressed() {
        // Keep the gate in front while the app/screen is blocked.
    }

    override fun onDestroy() {
        detachRemoteListeners()
        if (::localPolicyStore.isInitialized) {
            localPolicyStore.unregisterChangeListener(policyChangeListener)
        }
        mainHandler.removeCallbacksAndMessages(null)
        if (::serverClock.isInitialized) serverClock.stop()
        super.onDestroy()
    }

    private fun bindLockIntent(lockIntent: Intent) {
        packageNameToUnlock = lockIntent.getStringExtra(EXTRA_PACKAGE_NAME) ?: packageName
        reason = lockIntent.getStringExtra(EXTRA_REASON) ?: PolicyConstants.BLOCK_REASON_MANUAL
        settingsSectionKey = lockIntent.getStringExtra(EXTRA_SETTINGS_SECTION_KEY)
        pin = ""
        requestId = null
        statusText = null
        pinDotsView = null
        remoteUnlockButton = null
        keypadButtons.clear()
        render()
        attachExistingUnlockRequestListener()
    }

    private fun detachRemoteListeners() {
        unlockRequestListener?.let { listener ->
            unlockRequestRef?.removeEventListener(listener)
        }
        unlockRequestListener = null
        unlockRequestRef = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        return when (event.keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> appendDigit("0")
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> appendDigit("1")
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> appendDigit("2")
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> appendDigit("3")
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> appendDigit("4")
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> appendDigit("5")
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> appendDigit("6")
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> appendDigit("7")
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> appendDigit("8")
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> appendDigit("9")
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_CLEAR -> {
                pin = ""
                updatePinDisplay()
                true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                checkPin()
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun render() {
        keypadButtons.clear()
        remoteUnlockButton = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(44), dp(34), dp(44), dp(34))
            setBackgroundColor(guardNavy)
            isFocusable = false
        }

        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(lockSurface)
            strokeWidth = dp(1)
            strokeColor = outlineSoft
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFocusable = false
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(46), dp(36), dp(46), dp(36))
            isFocusable = false
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            isFocusable = false
        }

        left.addView(TextView(this).apply {
            text = getString(R.string.lock_access_title)
            textSize = 38f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(lockText)
            isFocusable = false
        })
        left.addView(TextView(this).apply {
            text = getString(R.string.lock_parent_subtitle)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(lockText)
            isFocusable = false
        })
        left.addView(TextView(this).apply {
            text = lockReasonLabel()
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(251, 113, 133))
            background = rounded(errorSoft, dp(28))
            setPadding(dp(18), dp(7), dp(18), dp(7))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(14), 0, dp(10)) }
            isFocusable = false
        })
        left.addView(TextView(this).apply {
            text = lockedTargetLabel()
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(mutedText)
            maxLines = 2
            setPadding(dp(10), 0, dp(10), dp(16))
            isFocusable = false
        })

        val pinDots = TextView(this).apply {
            text = pinDisplay()
            textSize = 34f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setTextColor(lockText)
            background = rounded(Color.rgb(15, 23, 42), dp(10), outlineSoft, dp(1))
            setPadding(dp(22), dp(13), dp(22), dp(13))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(8), 0, dp(18)) }
            isFocusable = false
        }
        pinDotsView = pinDots
        left.addView(pinDots)

        left.addView(lockButton(getString(R.string.lock_ask_parent)).apply {
            setOnClickListener { createRemoteUnlockRequest() }
            backgroundTintList = ColorStateList.valueOf(guardNavy)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
            ).apply { setMargins(0, 0, dp(24), dp(12)) }
            remoteUnlockButton = this
        })

        statusText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(actionBlue)
            setPadding(0, dp(2), 0, dp(6))
            isFocusable = false
        }
        left.addView(statusText)
        left.addView(TextView(this).apply {
            text = getString(R.string.lock_pin_instructions)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(mutedText)
            isFocusable = false
        })

        val keypadPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(lockPanel, dp(14), outlineSoft, dp(1))
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dp(38) }
            isFocusable = false
            addView(keypad())
        }

        content.addView(left)
        content.addView(keypadPanel)

        card.addView(content)
        root.addView(card)
        setContentView(root)
        linkKeypadFocus()
        keypadButtons.firstOrNull()?.post { keypadButtons.firstOrNull()?.requestFocus() }
    }

    private fun keypad(): View {
        return GridLayout(this).apply {
            columnCount = 3
            rowCount = 4
            isFocusable = false
            for (label in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "DEL", "0", "OK")) {
                addView(lockButton(label).apply {
                    text = label
                    layoutParams = ViewGroup.MarginLayoutParams(dp(72), dp(72)).apply {
                        setMargins(dp(7), dp(7), dp(7), dp(7))
                    }
                    cornerRadius = dp(36)
                    textSize = if (label == "DEL") 16f else 25f
                    backgroundTintList = ColorStateList.valueOf(
                        when (label) {
                            "OK" -> actionBlue
                            "DEL" -> Color.rgb(51, 65, 85)
                            else -> Color.rgb(30, 41, 59)
                        }
                    )
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        when (label) {
                            "DEL" -> pin = ""
                            "OK" -> checkPin()
                            else -> if (pin.length < 6) pin += label
                        }
                        updatePinDisplay()
                    }
                    keypadButtons += this
                })
            }
        }
    }

    private fun lockButton(label: String): MaterialButton {
        return MaterialButton(this).apply {
            id = View.generateViewId()
            text = label
            setAllCaps(false)
            isFocusable = true
            isFocusableInTouchMode = true
            minHeight = dp(54)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            cornerRadius = dp(8)
            setOnFocusChangeListener { view, hasFocus ->
                view.animate().scaleX(if (hasFocus) 1.09f else 1f)
                    .scaleY(if (hasFocus) 1.09f else 1f)
                    .setDuration(80L)
                    .start()
                strokeWidth = if (hasFocus) dp(4) else 0
                strokeColor = ColorStateList.valueOf(actionBlue)
            }
        }
    }

    private fun linkKeypadFocus() {
        keypadButtons.forEachIndexed { index, button ->
            val row = index / 3
            val col = index % 3
            button.nextFocusLeftId = keypadButtons.getOrNull(row * 3 + (col - 1))?.id ?: button.id
            button.nextFocusRightId = keypadButtons.getOrNull(row * 3 + (col + 1))?.id ?: button.id
            button.nextFocusUpId = keypadButtons.getOrNull(index - 3)?.id ?: button.id
            button.nextFocusDownId = keypadButtons.getOrNull(index + 3)?.id ?: remoteUnlockButton?.id ?: button.id
        }
        remoteUnlockButton?.let { remote ->
            remote.nextFocusUpId = keypadButtons[10].id
            remote.nextFocusDownId = remote.id
            remote.nextFocusLeftId = remote.id
            remote.nextFocusRightId = remote.id
        }
    }

    private fun appendDigit(value: String): Boolean {
        if (pin.length < 6) {
            pin += value
            updatePinDisplay()
        }
        return true
    }

    private fun updatePinDisplay() {
        pinDotsView?.text = pinDisplay()
    }

    private fun checkPin() {
        val retryMs = fallbackStore.pinRetryRemainingMs()
        if (retryMs > 0L) {
            statusText?.text = getString(R.string.lock_retry_wait, (retryMs + 999L) / 1_000L)
            pin = ""
            updatePinDisplay()
            return
        }
        val record = fallbackStore.loadPin()
        if (record == null) {
            statusText?.text = getString(R.string.lock_pin_missing)
            return
        }
        if (PinHasher.verify(
                pin = pin,
                salt = record.salt,
                expectedHash = record.hash,
                version = record.version,
                algorithm = record.algorithm,
                iterations = record.iterations
            )
        ) {
            fallbackStore.clearFailedPinAttempts()
            // Transparently upgrade a legacy (v1, unsalted-iteration) PIN hash to
            // the current PBKDF2 parameters on the next successful verify.
            if (record.version == PinHasher.LEGACY_VERSION) {
                val upgraded = PinHasher.create(pin)
                fallbackStore.savePin(
                    PinRecord(
                        salt = upgraded.salt,
                        hash = upgraded.hash,
                        version = upgraded.version,
                        algorithm = upgraded.algorithm,
                        iterations = upgraded.iterations,
                        updatedAt = SystemTimeGuard.now()
                    )
                )
            }
            if (isSettingsSectionGate()) {
                settingsSectionKey?.let(fallbackStore::grantSettingsSectionUnlock)
            } else if (isSetupGate()) {
                fallbackStore.grantSetupVisitUnlock()
            } else {
                fallbackStore.grantAppVisitUnlock(packageNameToUnlock)
            }
            finishAndReturnToUnlockedTarget()
        } else {
            val retry = fallbackStore.recordFailedPinAttempt()
            statusText?.text = getString(R.string.lock_pin_incorrect, retry.delayMs / 1_000L)
            if (retry.attempts >= PIN_TAMPER_THRESHOLD &&
                fallbackStore.shouldReportTamper(PolicyConstants.TAMPER_PIN_RETRY_LOCKED)
            ) {
                TamperEventQueue.enqueue(
                    applicationContext,
                    PolicyConstants.TAMPER_PIN_RETRY_LOCKED,
                    "Repeated incorrect PIN attempts were blocked"
                )
            }
            pin = ""
            updatePinDisplay()
        }
    }

    private fun finishIfNoLongerBlocked() {
        if (!::localPolicyStore.isInitialized) return
        // The own-app (setup) gate honors remote approvals: when an approved
        // unlock for our package exists, the wall must come down — the old
        // early-return here trapped the parent after a remote approval.
        if (isSetupGate()) {
            if (fallbackStore.isAppVisitUnlocked(packageNameToUnlock) ||
                fallbackStore.isTemporarilyUnlocked(packageNameToUnlock)
            ) {
                finishAndReturnToUnlockedTarget()
            }
            return
        }
        if (fallbackStore.isSafeModeActive()) {
            finishAndReturnToUnlockedTarget()
            return
        }
        if (isSettingsSectionGate()) {
            if (!FallbackProtection.isSettingsSectionLockEnabled(
                    packageNameToUnlock,
                    localPolicyStore.loadPolicies(),
                    localPolicyStore.loadDailyLimitBlocks()
                )
            ) {
                finishAndReturnToUnlockedTarget()
            }
            return
        }
        val decision = FallbackProtection.shouldLock(
            context = this,
            foregroundPackage = packageNameToUnlock,
            policies = localPolicyStore.loadPolicies(),
            dailyBlocks = localPolicyStore.loadDailyLimitBlocks(),
            fallbackStore = fallbackStore
        )
        if (!decision.locked) finishAndReturnToUnlockedTarget()
    }

    private fun startAutoDismissChecks() {
        mainHandler.removeCallbacks(autoDismissRunnable)
        autoDismissRunnable.run()
    }


    private fun createRemoteUnlockRequest() {
        if (requestId != null) {
            statusText?.text = getString(R.string.lock_waiting_parent)
            return
        }
        val status = FirebaseBootstrap.initialize(this)
        if (!status.configured) {
            statusText?.text = getString(R.string.lock_firebase_unavailable)
            return
        }
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            statusText?.text = getString(R.string.lock_firebase_connecting)
            auth.signInAnonymously()
                .addOnSuccessListener { writeRemoteUnlockRequest() }
                .addOnFailureListener { statusText?.text = getString(R.string.lock_firebase_sign_in_failed) }
            return
        }
        writeRemoteUnlockRequest()
    }

    private fun writeRemoteUnlockRequest() {
        val deviceId = DeviceIdentity.getOrCreate(this)
        val ref = FirebaseDatabase.getInstance().reference
            .child(FirebasePaths.deviceUnlockRequests(deviceId))
            .push()
        val id = ref.key ?: return
        requestId = id
        unlockRequestRef = ref
        statusText?.text = getString(R.string.lock_waiting_parent)
        ref.setValue(
            mapOf(
                "requestId" to id,
                "packageName" to packageNameToUnlock,
                "reason" to reason,
                "status" to PolicyConstants.UNLOCK_PENDING,
                "createdAt" to ServerValue.TIMESTAMP,
                "expiresAt" to serverClock.now() + PolicyConstants.TEMP_UNLOCK_MS,
                "ttlMs" to PolicyConstants.TEMP_UNLOCK_MS
            )
        ).addOnSuccessListener {
            attachUnlockListener(ref)
        }.addOnFailureListener {
            requestId = null
            unlockRequestRef = null
            statusText?.text = getString(R.string.lock_request_failed)
        }
    }

    /**
     * Re-arms remote unlock handling on every bind: this activity is the only
     * consumer of unlock-request approvals, and HOME/re-bind cycles destroy the
     * previous listener (onNewIntent → detachRemoteListeners). Without this, an
     * approval that arrives after the kid left the lock screen is never applied.
     */
    private fun attachExistingUnlockRequestListener() {
        val status = FirebaseBootstrap.initialize(this)
        if (!status.configured) return
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            auth.signInAnonymously().addOnSuccessListener { findExistingUnlockRequest() }
            return
        }
        findExistingUnlockRequest()
    }

    private fun findExistingUnlockRequest() {
        val deviceId = DeviceIdentity.getOrCreate(this)
        FirebaseDatabase.getInstance().reference
            .child(FirebasePaths.deviceUnlockRequests(deviceId))
            .orderByChild("createdAt")
            .limitToLast(20)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val newest = snapshot.children
                        .filter { child ->
                            child.child("packageName").getValue(String::class.java) == packageNameToUnlock
                        }
                        .filter { child ->
                            val status = child.child("status").getValue(String::class.java)
                            status == PolicyConstants.UNLOCK_PENDING ||
                                ApprovedUnlockPolicy.shouldApply(
                                    status,
                                    child.child("tvApplyStatus").getValue(String::class.java),
                                    child.child("updatedAt").getValue(Long::class.java),
                                    serverClock.now()
                                )
                        }
                        .maxByOrNull { child ->
                            child.child("createdAt").getValue(Long::class.java) ?: 0L
                        }
                        ?: return
                    attachUnlockListener(newest.ref)
                }

                override fun onCancelled(error: DatabaseError) = Unit
            })
    }

    private fun attachUnlockListener(ref: DatabaseReference) {
        detachRemoteListeners()
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java)
                val expiresAt = snapshot.child("expiresAt").getValue(Long::class.java) ?: 0L
                if (status == PolicyConstants.UNLOCK_PENDING &&
                    expiresAt > 0L &&
                    serverClock.now() > expiresAt
                ) {
                    snapshot.ref.updateChildren(
                        mapOf(
                            "status" to PolicyConstants.UNLOCK_EXPIRED,
                            "updatedAt" to ServerValue.TIMESTAMP
                        )
                    )
                    statusText?.text = getString(R.string.lock_request_expired)
                    return
                }
                when (status) {
                    PolicyConstants.UNLOCK_APPROVED -> {
                        unlockRequestListener?.let { ref.removeEventListener(it) }
                        unlockRequestListener = null
                        grantApprovedUnlock(snapshot)
                        snapshot.ref.updateChildren(
                            mapOf(
                                "tvApplyStatus" to PolicyConstants.SYNC_STATUS_APPLIED,
                                "tvAppliedAt" to ServerValue.TIMESTAMP
                            )
                        )
                        finishAndReturnToUnlockedTarget()
                    }
                    PolicyConstants.UNLOCK_DENIED -> statusText?.text = getString(R.string.lock_request_denied)
                    PolicyConstants.UNLOCK_EXPIRED -> statusText?.text = getString(R.string.lock_request_expired)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                statusText?.text = getString(R.string.lock_request_failed)
            }
        }
        unlockRequestListener = listener
        ref.addValueEventListener(listener)
    }

    private fun pinDisplay(): String {
        return (0 until 6).joinToString(" ") { index -> if (pin.length > index) "*" else "-" }
    }

    private fun lockReasonLabel(): String {
        return when (reason) {
            PolicyConstants.BLOCK_REASON_DAILY_LIMIT -> getString(R.string.lock_title_daily_limit)
            PolicyConstants.BLOCK_REASON_RISKY_SETTINGS -> getString(R.string.lock_title_protected_settings)
            PolicyConstants.BLOCK_REASON_SOURCE_LOCK -> getString(R.string.lock_title_live_tv)
            PolicyConstants.BLOCK_REASON_SETTINGS_SECTION ->
                "${settingsSectionLabel()} Locked"
            PolicyConstants.TAMPER_ADMIN_DISABLE_REQUESTED -> getString(R.string.lock_title_admin_change)
            else -> getString(R.string.lock_title_app)
        }
    }

    private fun isSetupGate(): Boolean = packageNameToUnlock == packageName
    private fun isSettingsSectionGate(): Boolean = reason == PolicyConstants.BLOCK_REASON_SETTINGS_SECTION

    private fun grantApprovedUnlock(snapshot: DataSnapshot? = null) {
        val approvalType = snapshot?.child("approvalType")?.getValue(String::class.java)
            ?: PolicyConstants.UNLOCK_APPROVAL_ONE_VISIT
        val approvalDurationMs = snapshot?.child("approvalDurationMs")?.getValue(Long::class.java)
        if (!isSettingsSectionGate() &&
            !isSetupGate() &&
            approvalType == PolicyConstants.UNLOCK_APPROVAL_TIMED &&
            approvalDurationMs != null &&
            approvalDurationMs > 0
        ) {
            fallbackStore.grantTemporaryUnlock(packageNameToUnlock, approvalDurationMs)
            return
        }
        if (isSettingsSectionGate()) {
            settingsSectionKey?.let(fallbackStore::grantSettingsSectionUnlock)
        } else if (isSetupGate()) {
            fallbackStore.grantSetupVisitUnlock()
        } else {
            fallbackStore.grantAppVisitUnlock(packageNameToUnlock)
        }
    }

    private fun finishAndReturnToUnlockedTarget() {
        val target = unlockedTargetIntent()
        if (target != null) {
            runCatching { startActivity(target) }
        }
        finish()
    }

    private fun unlockedTargetIntent(): Intent? {
        if (isSetupGate() || isSettingsSectionGate()) return null
        val targetPackage = packageNameToUnlock
        val intent = if (targetPackage in PolicyConstants.primarySettingsPackages) {
            Intent(Settings.ACTION_SETTINGS)
        } else {
            packageManager.getLeanbackLaunchIntentForPackage(targetPackage)
                ?: packageManager.getLaunchIntentForPackage(targetPackage)
                ?: Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setPackage(targetPackage)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    private fun lockedTargetLabel(): String {
        return if (isSettingsSectionGate()) {
            getString(R.string.lock_settings_section, settingsSectionLabel())
        } else {
            packageNameToUnlock
        }
    }

    private fun lockReasonText(): String {
        val label = when (reason) {
            PolicyConstants.BLOCK_REASON_DAILY_LIMIT -> getString(R.string.lock_detail_daily_limit)
            PolicyConstants.BLOCK_REASON_RISKY_SETTINGS -> getString(R.string.lock_detail_protected_settings)
            PolicyConstants.BLOCK_REASON_SOURCE_LOCK -> getString(R.string.lock_detail_live_tv)
            PolicyConstants.BLOCK_REASON_SETTINGS_SECTION -> "${settingsSectionLabel()} locked"
            PolicyConstants.TAMPER_ADMIN_DISABLE_REQUESTED -> getString(R.string.lock_detail_admin_change)
            else -> getString(R.string.lock_detail_app)
        }
        return "$label\n$packageNameToUnlock"
    }

    private fun settingsSectionLabel(): String {
        return PolicyConstants.settingsSectionPolicy(packageNameToUnlock)?.shortLabel
            ?: getString(R.string.lock_settings_section_default)
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeColor != null && strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val PIN_TAMPER_THRESHOLD = 4
        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_REASON = "reason"
        const val EXTRA_SETTINGS_SECTION_KEY = "settingsSectionKey"
        private const val AUTO_DISMISS_CHECK_MS = 750L
    }
}
