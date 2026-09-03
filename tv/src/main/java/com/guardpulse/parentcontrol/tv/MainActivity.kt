package com.guardpulse.parentcontrol.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.guardpulse.parentcontrol.shared.DeviceIdentity
import com.guardpulse.parentcontrol.shared.FirebaseBootstrap
import com.guardpulse.parentcontrol.shared.FirebasePaths
import com.guardpulse.parentcontrol.shared.PolicyConstants
import com.guardpulse.parentcontrol.tv.fallback.FallbackProtection
import com.guardpulse.parentcontrol.tv.fallback.FallbackStateStore
import com.guardpulse.parentcontrol.tv.network.NetworkFilterController
import com.guardpulse.parentcontrol.tv.network.NetworkFilterStatus
import com.guardpulse.parentcontrol.tv.pairing.PairingManager
import com.guardpulse.parentcontrol.tv.pairing.QrCodeBitmap
import com.guardpulse.parentcontrol.tv.policy.DevicePolicyController
import com.guardpulse.parentcontrol.tv.sync.TvSyncService
import com.guardpulse.parentcontrol.tv.system.BackgroundRestrictionStatus
import com.guardpulse.parentcontrol.tv.system.TvServiceStarter
import com.guardpulse.parentcontrol.tv.usage.UsageTracker

class MainActivity : Activity() {
    private lateinit var policyController: DevicePolicyController
    private lateinit var usageTracker: UsageTracker
    private lateinit var pairingManager: PairingManager
    private lateinit var fallbackStore: FallbackStateStore
    private val focusableActions = mutableListOf<MaterialButton>()
    private var lastActionMessage: String? = null
    private var banner: TextView? = null
    private var syncAutoStarted = false

    private val tvBackground = Color.rgb(15, 23, 42)
    private val tvPanel = Color.rgb(30, 41, 59)
    private val tvPanelSoft = Color.rgb(26, 43, 76)
    private val tvText = Color.WHITE
    private val tvMuted = Color.rgb(203, 213, 225)
    private val tvBlue = Color.rgb(0, 81, 213)
    private val tvRed = Color.rgb(225, 29, 72)
    private val tvGreen = Color.rgb(16, 185, 129)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        policyController = DevicePolicyController(this)
        usageTracker = UsageTracker(this)
        pairingManager = PairingManager(this)
        fallbackStore = FallbackStateStore(this)
    }

    override fun onResume() {
        super.onResume()
        if (!ensureSetupUnlocked()) return
        render()
    }

    override fun onStop() {
        fallbackStore.clearSetupVisitUnlock()
        super.onStop()
    }

    @Deprecated("Used for Android VPN consent on older TV firmware.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN_PREPARE) return

        NetworkFilterController.requestApply(this)
        showAction("Network filter is disabled")
        render()
    }

    private fun render() {
        focusableActions.clear()
        val firebaseStatus = FirebaseBootstrap.initialize(this)
        if (firebaseStatus.configured && !syncAutoStarted) {
            syncAutoStarted = true
            runCatching { TvServiceStarter.start(this) }
            if (lastActionMessage == null) lastActionMessage = "Sync service started"
        }
        val pairing = pairingManager.current()
        val mode = FallbackProtection.enforcementMode(this)
        val adminActive = policyController.isAdminActive()
        val adminSetupAvailable = FallbackProtection.isDeviceAdminSetupAvailable(this)
        val accessibilityEnabled = FallbackProtection.isAccessibilityEnabled(this)
        val usageAccess = usageTracker.hasUsageAccess()
        val vpnStatus = NetworkFilterController.refreshPreparedStatus(this)
        val backgroundUnrestricted = BackgroundRestrictionStatus.isBatteryUnrestricted(this)
        val pinConfigured = fallbackStore.loadPin() != null
        Log.i(
            "GuardPulseTvStatus",
            "firebaseConfigured=${firebaseStatus.configured}, mode=$mode, adminActive=$adminActive, adminSetupAvailable=$adminSetupAvailable, accessibilityEnabled=$accessibilityEnabled, usageAccess=$usageAccess, vpnPrepared=${vpnStatus.prepared}, vpnActive=${vpnStatus.active}, backgroundUnrestricted=$backgroundUnrestricted, pinConfigured=$pinConfigured"
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(28), dp(36), dp(28))
            setBackgroundColor(tvBackground)
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(title("Device Service").apply { textSize = 34f })
                addView(modePill("Mode: ${modeLabel(mode)}", mode != PolicyConstants.ENFORCEMENT_UNPROTECTED).apply {
                    textSize = 15f
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                })
            })
            banner = body(lastActionMessage ?: "Use the remote to complete each setup step.").apply {
                setTextColor(tvMuted)
                background = rounded(tvPanel, dp(12), Color.argb(80, 255, 255, 255), dp(1))
                setPadding(dp(16), dp(10), dp(16), dp(10))
                textSize = 14f
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(dp(470), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            addView(banner)
        }

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(20) }
            addView(ScrollView(this@MainActivity).apply {
                isFillViewport = false
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(section("Required Permissions"))
                    addView(setupCard(firebaseStatus.configured, adminActive, adminSetupAvailable, accessibilityEnabled, usageAccess, vpnStatus, backgroundUnrestricted, pinConfigured))
                    addView(diagnosticsCard(firebaseStatus.configured, adminActive, adminSetupAvailable, accessibilityEnabled, usageAccess, vpnStatus, backgroundUnrestricted, pinConfigured))
                })
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(390), ViewGroup.LayoutParams.MATCH_PARENT)
                    .apply { leftMargin = dp(24) }
                addView(pairingDashboardCard(pairing.deviceId, pairing.code, pairing.qrPayload))
            })
        }

        root.addView(header)
        root.addView(main)
        linkActionFocus()
        setContentView(root)
        root.post { root.requestFocus() }
    }

    private fun setupSummaryCard(
        firebaseConfigured: Boolean,
        adminActive: Boolean,
        adminSetupAvailable: Boolean,
        accessibilityEnabled: Boolean,
        usageAccess: Boolean,
        vpnStatus: NetworkFilterStatus,
        backgroundUnrestricted: Boolean,
        pinConfigured: Boolean
    ): MaterialCardView {
        return dashboardCard(verticalWeight = 1f, horizontalPadding = 20, verticalPadding = 14) {
            addView(section("Required Permissions").apply {
                textSize = 22f
                setPadding(0, 0, 0, dp(8))
            })
            addView(setupCompactRow(
                title = "Device Admin",
                detail = if (adminActive) {
                    "Tamper protection active"
                } else if (!adminSetupAvailable) {
                    "Firmware blocks admin prompt"
                } else {
                    "Enable strict disable-warning flow"
                },
                ok = adminActive || !adminSetupAvailable,
                actionText = when {
                    adminActive -> "Active"
                    !adminSetupAvailable -> "Unavailable"
                    else -> "Activate"
                },
                enabled = !adminActive && adminSetupAvailable
            ) {
                safeStartActivity(
                    label = "Device Admin",
                    primary = FallbackProtection.deviceAdminIntent(this@MainActivity),
                    fallback = null,
                    fallbackLabel = null,
                    allowProtectedSettings = true
                )
            })
            addView(setupCompactRow(
                title = "Accessibility",
                detail = "Required for app lock monitoring",
                ok = accessibilityEnabled,
                actionText = if (accessibilityEnabled) "Enabled" else "Activate",
                enabled = !accessibilityEnabled
            ) {
                safeStartActivity(
                    label = "Accessibility",
                    primary = FallbackProtection.accessibilitySettingsIntent(),
                    fallback = Intent(Settings.ACTION_SETTINGS),
                    fallbackLabel = "Android settings",
                    allowProtectedSettings = true
                )
            })
            addView(setupCompactRow(
                title = "Usage Access",
                detail = "Tracks daily screen time",
                ok = usageAccess,
                actionText = if (usageAccess) "Enabled" else "Open",
                enabled = !usageAccess
            ) {
                safeStartActivity(
                    label = "Usage Access",
                    primary = FallbackProtection.usageAccessSettingsIntent(),
                    fallback = Intent(Settings.ACTION_SETTINGS),
                    fallbackLabel = "Android settings",
                    allowProtectedSettings = true
                )
            })
            addView(setupCompactRow(
                title = "Cloud Sync",
                detail = if (firebaseConfigured) "Connected to parent dashboard" else "Firebase config missing",
                ok = firebaseConfigured,
                actionText = "Start",
                enabled = firebaseConfigured
            ) {
                safeRun("Start Sync") {
                    TvServiceStarter.start(this@MainActivity)
                    showAction("Sync service started")
                }
            })
            addView(setupCompactRow(
                title = "Network Filter",
                detail = networkFilterDetail(vpnStatus),
                ok = networkFilterOk(vpnStatus),
                actionText = "Unused",
                enabled = false
            ) {
                openOrRestartNetworkFilter()
            })
            addView(setupCompactRow(
                title = "Background Access",
                detail = if (backgroundUnrestricted) "Android will not optimize this service." else "Disable battery restriction for strict recovery.",
                ok = backgroundUnrestricted,
                actionText = if (backgroundUnrestricted) "Active" else "Allow",
                enabled = true
            ) {
                openBatteryOptimizationSettings()
            })
            addView(setupCompactRow(
                title = "Parent PIN",
                detail = if (pinConfigured) "PIN received from parent app" else "Set PIN in parent Security tab",
                ok = pinConfigured,
                actionText = "Refresh",
                enabled = true
            ) {
                safeRun("Refresh Status") {
                    TvServiceStarter.start(this@MainActivity)
                    showAction("Status refreshed")
                    render()
                }
            })
        }
    }

    private fun systemActionsCard(
        firebaseConfigured: Boolean,
        adminActive: Boolean,
        adminSetupAvailable: Boolean,
        accessibilityEnabled: Boolean,
        usageAccess: Boolean,
        pinConfigured: Boolean
    ): MaterialCardView {
        return dashboardCard(horizontalPadding = 20, verticalPadding = 12) {
            addView(section("Status").apply {
                textSize = 18f
                setPadding(0, 0, 0, dp(4))
            })
            addView(body(
                // Non-owner admin only provides force-lock; claim "limited"
                // rather than a plain green so the status line can't imply
                // uninstall-blocking that requires device owner.
                "Owner ${stateText(policyController.isDeviceOwner())}  |  Admin ${adminStatusText(adminActive, adminSetupAvailable)}  |  Accessibility ${stateText(accessibilityEnabled)}  |  Usage ${stateText(usageAccess)}  |  PIN ${stateText(pinConfigured)}  |  Firebase ${stateText(firebaseConfigured)}"
            ).apply {
                textSize = 12f
                setTextColor(tvMuted)
                maxLines = 2
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, 0)
                addView(actionButton("Rescan Apps", true) {
                    safeRun("Rescan Apps") {
                        TvServiceStarter.start(this@MainActivity, TvSyncService.ACTION_RESCAN_APPS)
                        showAction("App rescan requested")
                    }
                }.apply {
                    minHeight = dp(40)
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(actionButton("Android Settings", true) {
                    safeStartActivity(
                        label = "Android Settings",
                        primary = Intent(Settings.ACTION_SETTINGS),
                        fallback = null,
                        fallbackLabel = null
                    )
                }.apply {
                    minHeight = dp(40)
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { leftMargin = dp(12) }
                })
            })
        }
    }

    private fun pairingDashboardCard(deviceId: String, code: String, payload: String): MaterialCardView {
        val pairedParentUid = pairingManager.pairedParentUid()
        return dashboardCard(fillHeight = true) {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(section(if (pairedParentUid.isNullOrBlank()) "Pair This TV" else "Paired").apply {
                textSize = 24f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(6))
            })
            addView(body("Scan this QR from the parent app.").apply {
                gravity = Gravity.CENTER
                textSize = 14f
                maxLines = 1
                setTextColor(tvMuted)
            })
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(QrCodeBitmap.create(payload, dp(220)))
                adjustViewBounds = false
                background = rounded(Color.WHITE, dp(14))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                isFocusable = false
                layoutParams = LinearLayout.LayoutParams(dp(242), dp(242)).apply {
                    setMargins(0, dp(12), 0, dp(12))
                }
            })
            addView(body("Manual code").apply {
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(tvMuted)
                maxLines = 1
            })
            addView(body(code.chunked(3).joinToString("-")).apply {
                gravity = Gravity.CENTER
                textSize = 30f
                typeface = Typeface.MONOSPACE
                setTextColor(tvBlue)
                background = rounded(Color.argb(40, 211, 228, 254), dp(12), Color.argb(80, 255, 255, 255), dp(1))
                setPadding(dp(18), dp(10), dp(18), dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(4), 0, dp(12)) }
            })
            addView(statusBox(
                if (!pairedParentUid.isNullOrBlank()) {
                    "Paired with a parent account."
                } else {
                    "Parent App > Devices > Scan QR, or enter this code."
                },
                !pairedParentUid.isNullOrBlank()
            ).apply {
                textSize = 14f
                setPadding(dp(14), dp(10), dp(14), dp(10))
                maxLines = 2
            })
            addView(body("Device ID: $deviceId").apply {
                gravity = Gravity.CENTER
                setTextColor(tvMuted)
                textSize = 12f
                maxLines = 2
            })
            if (!pairedParentUid.isNullOrBlank()) {
                addView(actionButton("Emergency Reset Pairing", true) {
                    confirmPairingResetFirst(pairedParentUid)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(12) }
                })
            }
        }
    }

    private fun confirmPairingResetFirst(parentUid: String) {
        AlertDialog.Builder(this)
            .setTitle("Reset pairing?")
            .setMessage("Use this only when the parent app cannot unpair this TV. Protection and the PIN stay on this TV.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> confirmPairingResetFinal(parentUid) }
            .show()
    }

    private fun confirmPairingResetFinal(parentUid: String) {
        AlertDialog.Builder(this)
            .setTitle("Confirm emergency reset")
            .setMessage("The current parent will lose control of this TV. A new pairing is required.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset Pairing") { _, _ -> resetPairing(parentUid) }
            .show()
    }

    private fun resetPairing(parentUid: String) {
        val status = FirebaseBootstrap.initialize(this)
        if (!status.configured) {
            showAction("Firebase is not configured. Pairing was not reset.")
            return
        }
        val deviceId = DeviceIdentity.getOrCreate(this)
        val updates = mapOf<String, Any?>(
            "${FirebasePaths.deviceMeta(deviceId)}/ownerUid" to null,
            "${FirebasePaths.deviceMeta(deviceId)}/pairedAt" to null,
            FirebasePaths.userDevice(parentUid, deviceId) to null
        )
        FirebaseDatabase.getInstance().reference.updateChildren(updates)
            .addOnSuccessListener {
                pairingManager.clearPairedParent()
                showAction("Pairing reset. Open this setup screen again to pair a parent.")
                render()
            }
            .addOnFailureListener {
                showAction("Pairing reset could not be confirmed. Check the TV connection and try again.")
            }
    }

    private fun setupCard(
        firebaseConfigured: Boolean,
        adminActive: Boolean,
        adminSetupAvailable: Boolean,
        accessibilityEnabled: Boolean,
        usageAccess: Boolean,
        vpnStatus: NetworkFilterStatus,
        backgroundUnrestricted: Boolean,
        pinConfigured: Boolean
    ): MaterialCardView {
        return card {
            addView(setupRow(
                title = "Device Admin",
                detail = if (adminActive) {
                    "Fallback tamper protection is active."
                } else if (!adminSetupAvailable) {
                    "Android TV firmware restricts direct admin prompts. Accessibility protects uninstall and settings screens."
                } else {
                    "Required for strict enforcement and disable-warning flow."
                },
                ok = adminActive || !adminSetupAvailable,
                actionText = when {
                    adminActive -> "Active"
                    !adminSetupAvailable -> "Unavailable"
                    else -> "Activate"
                },
                enabled = !adminActive && adminSetupAvailable
            ) {
                safeStartActivity(
                    label = "Device Admin",
                    primary = FallbackProtection.deviceAdminIntent(this@MainActivity),
                    fallback = null,
                    fallbackLabel = null,
                    allowProtectedSettings = true
                )
            })
            addView(setupRow(
                title = "Accessibility Service",
                detail = "Core requirement for monitoring app usage and enforcing blocking screens on TV.",
                ok = accessibilityEnabled,
                actionText = if (accessibilityEnabled) "Enabled" else "Activate",
                enabled = !accessibilityEnabled
            ) {
                safeStartActivity(
                    label = "Accessibility",
                    primary = FallbackProtection.accessibilitySettingsIntent(),
                    fallback = Intent(Settings.ACTION_SETTINGS),
                    fallbackLabel = "Android settings",
                    allowProtectedSettings = true
                )
            })
            addView(setupRow(
                title = "Usage Data Access",
                detail = "Needed to track screen time accurately across different TV applications.",
                ok = usageAccess,
                actionText = if (usageAccess) "Enabled" else "Open Settings",
                enabled = !usageAccess
            ) {
                safeStartActivity(
                    label = "Usage Access",
                    primary = FallbackProtection.usageAccessSettingsIntent(),
                    fallback = Intent(Settings.ACTION_SETTINGS),
                    fallbackLabel = "Android settings",
                    allowProtectedSettings = true
                )
            })
            addView(setupRow(
                title = "Network Filter",
                detail = networkFilterDetail(vpnStatus),
                ok = networkFilterOk(vpnStatus),
                actionText = "Unused",
                enabled = false
            ) {
                openOrRestartNetworkFilter()
            })
            addView(setupRow(
                title = "Background Access",
                detail = if (backgroundUnrestricted) {
                    "Device Service is unrestricted from Android battery/background optimization."
                } else {
                    "Required so sync and Accessibility recovery stay active after boot or process cleanup."
                },
                ok = backgroundUnrestricted,
                actionText = if (backgroundUnrestricted) "Active" else "Allow",
                enabled = true
            ) {
                openBatteryOptimizationSettings()
            })
            addView(setupRow(
                title = "Cloud Sync",
                detail = if (firebaseConfigured) "Connects this TV to the Parent App dashboard for remote management." else firebaseStatusText(),
                ok = firebaseConfigured,
                actionText = "Start Sync",
                enabled = firebaseConfigured
            ) {
                safeRun("Start Sync") {
                    TvServiceStarter.start(this@MainActivity)
                    showAction("Sync service started")
                }
            })
            addView(setupRow(
                title = "Parent PIN",
                detail = if (pinConfigured) "PIN hash received from parent app." else "Set a 6-digit PIN in the parent app Security tab.",
                ok = pinConfigured,
                actionText = "Refresh",
                enabled = true
            ) {
                safeRun("Refresh Status") {
                    TvServiceStarter.start(this@MainActivity)
                    showAction("Status refreshed")
                    render()
                }
            })
        }
    }

    private fun pairingCard(deviceId: String, code: String, payload: String): MaterialCardView {
        val pairedParentUid = pairingManager.pairedParentUid()
        return card {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(section(if (pairedParentUid.isNullOrBlank()) "Waiting for pairing" else "Paired"))
            addView(body("Connect this TV from the parent app.").apply {
                gravity = Gravity.CENTER
                setTextColor(tvMuted)
            })
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(QrCodeBitmap.create(payload, dp(210)))
                adjustViewBounds = true
                maxWidth = dp(210)
                maxHeight = dp(210)
                background = rounded(Color.WHITE, dp(14))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(dp(234), dp(234)).apply {
                    setMargins(0, dp(24), 0, dp(24))
                }
                setOnFocusChangeListener { view, hasFocus ->
                    view.animate().scaleX(if (hasFocus) 1.04f else 1f)
                        .scaleY(if (hasFocus) 1.04f else 1f)
                        .setDuration(90L)
                        .start()
                }
            })
            addView(body("Or enter manual code").apply {
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(tvMuted)
            })
            addView(body(code.chunked(3).joinToString("-")).apply {
                gravity = Gravity.CENTER
                textSize = 34f
                typeface = Typeface.MONOSPACE
                setTextColor(tvBlue)
                background = rounded(Color.argb(40, 211, 228, 254), dp(12), Color.argb(80, 255, 255, 255), dp(1))
                setPadding(dp(22), dp(14), dp(22), dp(14))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(8), 0, dp(20)) }
            })
            if (!pairedParentUid.isNullOrBlank()) {
                addView(statusBox("Paired with a parent account.", true))
            } else {
                addView(statusBox("Open the Parent App > Devices > Scan QR, or enter this manual code.", false))
            }
            addView(body("Device ID: $deviceId").apply {
                gravity = Gravity.CENTER
                setTextColor(tvMuted)
                textSize = 15f
            })
        }
    }

    private fun ensureSetupUnlocked(): Boolean {
        if (fallbackStore.isSetupVisitUnlocked()) return true
        FallbackProtection.openLock(
            context = this,
            packageName = packageName,
            reason = PolicyConstants.BLOCK_REASON_RISKY_SETTINGS
        )
        return false
    }

    private fun diagnosticsCard(
        firebaseConfigured: Boolean,
        adminActive: Boolean,
        adminSetupAvailable: Boolean,
        accessibilityEnabled: Boolean,
        usageAccess: Boolean,
        vpnStatus: NetworkFilterStatus,
        backgroundUnrestricted: Boolean,
        pinConfigured: Boolean
    ): MaterialCardView {
        return card {
            addView(section("Diagnostics"))
            addView(statusLine("Device Owner", policyController.isDeviceOwner(), "Enterprise package suspension"))
            addView(statusLine("Device Admin", adminActive || !adminSetupAvailable, if (adminSetupAvailable) "Fallback admin receiver" else "Not exposed by TV firmware"))
            addView(statusLine("Accessibility", accessibilityEnabled, "Foreground monitor"))
            addView(statusLine("Usage Access", usageAccess, "Daily limits"))
            addView(statusLine("Network Filter", networkFilterOk(vpnStatus), networkFilterDetail(vpnStatus)))
            addView(statusLine("Background Access", backgroundUnrestricted, if (backgroundUnrestricted) "Unrestricted" else "Battery optimization may delay recovery"))
            addView(statusLine("Parent PIN", pinConfigured, "Local PIN unlock"))
            addView(statusLine("Firebase", firebaseConfigured, "Remote control sync"))
            addView(actionButton("Rescan Installed Apps", true) {
                safeRun("Rescan Apps") {
                    TvServiceStarter.start(this@MainActivity, TvSyncService.ACTION_RESCAN_APPS)
                    showAction("App rescan requested")
                }
            })
            addView(actionButton("Open Android Settings", true) {
                safeStartActivity(
                    label = "Android Settings",
                    primary = Intent(Settings.ACTION_SETTINGS),
                    fallback = null,
                    fallbackLabel = null
                )
            })
        }
    }

    private fun setupRow(
        title: String,
        detail: String,
        ok: Boolean,
        actionText: String,
        enabled: Boolean,
        action: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = rounded(
                if (ok) Color.argb(22, 16, 185, 129) else Color.argb(35, 225, 29, 72),
                dp(12),
                if (ok) Color.argb(80, 16, 185, 129) else Color.argb(150, 225, 29, 72),
                dp(1)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(18)) }
            isFocusable = false
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(body(title).apply {
                    textSize = 23f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(tvText)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@MainActivity).apply {
                    text = if (ok) "ACTIVE" else "NEEDS ACTION"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(if (ok) tvGreen else tvRed)
                    background = rounded(if (ok) Color.argb(36, 16, 185, 129) else Color.argb(45, 225, 29, 72), dp(8))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    layoutParams = LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { leftMargin = dp(16) }
                    isFocusable = false
                })
            })
            addView(body(detail).apply {
                textSize = 16f
                setTextColor(tvMuted)
                setPadding(0, dp(8), 0, dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
            addView(actionButton(actionText, enabled, action).apply {
                layoutParams = LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }
    }

    private fun setupCompactRow(
        title: String,
        detail: String,
        ok: Boolean,
        actionText: String,
        enabled: Boolean,
        action: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = rounded(
                if (ok) Color.argb(22, 16, 185, 129) else Color.argb(35, 225, 29, 72),
                dp(12),
                if (ok) Color.argb(70, 16, 185, 129) else Color.argb(130, 225, 29, 72),
                dp(1)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { setMargins(0, 0, 0, dp(6)) }
            isFocusable = false
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(tvText)
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = detail
                textSize = 12f
                setTextColor(tvMuted)
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f)
                    .apply { leftMargin = dp(12) }
            })
            addView(TextView(this@MainActivity).apply {
                text = if (ok) "ACTIVE" else "SETUP"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(if (ok) tvGreen else tvRed)
                background = rounded(if (ok) Color.argb(36, 16, 185, 129) else Color.argb(45, 225, 29, 72), dp(8))
                setPadding(dp(8), dp(5), dp(8), dp(5))
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { leftMargin = dp(12) }
            })
        }
    }

    private fun statusLine(label: String, ok: Boolean, detail: String): TextView {
        return body("${if (ok) "OK" else "SETUP"}  $label - $detail").apply {
            setTextColor(if (ok) tvGreen else tvRed)
            textSize = 15f
        }
    }

    private fun networkFilterOk(@Suppress("UNUSED_PARAMETER") status: NetworkFilterStatus): Boolean {
        return true
    }

    private fun networkFilterDetail(@Suppress("UNUSED_PARAMETER") status: NetworkFilterStatus): String {
        return "Disabled; app blocking uses the PIN wall."
    }

    private fun openOrRestartNetworkFilter() {
        NetworkFilterController.requestApply(this)
        showAction("Network filter is disabled; app blocking uses the PIN wall.")
        render()
    }

    private fun openBatteryOptimizationSettings() {
        safeStartActivity(
            label = "Background Access",
            primary = BackgroundRestrictionStatus.batteryOptimizationIntent(this),
            fallback = Intent(Settings.ACTION_SETTINGS),
            fallbackLabel = "Android settings",
            allowProtectedSettings = true
        )
    }

    private fun safeStartActivity(
        label: String,
        primary: Intent,
        fallback: Intent?,
        fallbackLabel: String?,
        allowProtectedSettings: Boolean = false
    ) {
        if (allowProtectedSettings) {
            fallbackStore.grantSetupSettingsAccess()
        }
        val primaryResolved = primary.resolveActivity(packageManager) != null
        if (primaryResolved && tryStart(label, primary)) return

        if (fallback != null) {
            val fallbackResolved = fallback.resolveActivity(packageManager) != null
            if (fallbackResolved && tryStart(fallbackLabel ?: label, fallback)) {
                showAction("$label screen was unavailable. Opened ${fallbackLabel ?: "fallback settings"}.")
                writeDiagnostic(label, "fallback", null)
                return
            }
        }

        val message = "$label screen is not available on this TV firmware."
        showAction(message)
        writeDiagnostic(label, "unavailable", message)
    }

    private fun tryStart(label: String, intent: Intent): Boolean {
        return try {
            startActivity(intent)
            showAction("Opened $label")
            writeDiagnostic(label, "opened", null)
            true
        } catch (error: ActivityNotFoundException) {
            writeDiagnostic(label, "activityNotFound", error.message)
            false
        } catch (error: SecurityException) {
            showAction("$label is blocked by this TV firmware.")
            writeDiagnostic(label, "securityException", error.message)
            false
        } catch (error: RuntimeException) {
            showAction("$label could not be opened: ${error.message ?: "unknown error"}")
            writeDiagnostic(label, "runtimeException", error.message)
            false
        }
    }

    private fun safeRun(label: String, action: () -> Unit) {
        try {
            action()
            writeDiagnostic(label, "ok", null)
        } catch (error: RuntimeException) {
            val message = "$label failed: ${error.message ?: "unknown error"}"
            showAction(message)
            writeDiagnostic(label, "runtimeException", error.message)
        }
    }

    private fun showAction(message: String) {
        lastActionMessage = message
        banner?.text = message
    }

    private fun writeDiagnostic(label: String, status: String, error: String?) {
        val firebaseStatus = FirebaseBootstrap.initialize(this)
        if (!firebaseStatus.configured) return
        val write = {
            val deviceId = DeviceIdentity.getOrCreate(this)
            FirebaseDatabase.getInstance().reference
                .child(FirebasePaths.deviceSecurityRuntime(deviceId))
                .updateChildren(
                    mapOf(
                        "lastTvAction" to label,
                        "lastTvActionStatus" to status,
                        "lastTvActionError" to error,
                        "lastTvActionAt" to ServerValue.TIMESTAMP
                    )
                )
        }
        if (FirebaseAuth.getInstance().currentUser == null) {
            FirebaseAuth.getInstance().signInAnonymously().addOnSuccessListener { write() }
        } else {
            write()
        }
    }

    private fun modePill(text: String, ok: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (ok) tvGreen else tvRed)
            background = rounded(if (ok) Color.argb(35, 16, 185, 129) else Color.argb(45, 225, 29, 72), dp(24))
            setPadding(dp(18), dp(10), dp(18), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
            isFocusable = false
        }
    }

    private fun statusBox(text: String, ok: Boolean): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(if (ok) tvGreen else tvMuted)
            background = rounded(if (ok) Color.argb(30, 16, 185, 129) else Color.argb(55, 3, 22, 54), dp(12), Color.argb(60, 255, 255, 255), dp(1))
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, dp(16)) }
            isFocusable = false
        }
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null, strokeWidth: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeColor != null && strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun card(content: LinearLayout.() -> Unit): MaterialCardView {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(26), dp(28), dp(26))
            isFocusable = false
            content()
        }
        return MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.argb(70, 255, 255, 255)
            setCardBackgroundColor(tvPanel)
            isFocusable = false
            setContentPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(16), 0, dp(16)) }
            addView(inner)
        }
    }

    private fun dashboardCard(
        verticalWeight: Float? = null,
        fillHeight: Boolean = false,
        horizontalPadding: Int = 22,
        verticalPadding: Int = 18,
        content: LinearLayout.() -> Unit
    ): MaterialCardView {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(horizontalPadding), dp(verticalPadding), dp(horizontalPadding), dp(verticalPadding))
            isFocusable = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (fillHeight || verticalWeight != null) {
                    ViewGroup.LayoutParams.MATCH_PARENT
                } else {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                }
            )
            content()
        }
        return MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = Color.argb(65, 255, 255, 255)
            setCardBackgroundColor(tvPanel)
            isFocusable = false
            setContentPadding(0, 0, 0, 0)
            layoutParams = when {
                fillHeight -> LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                verticalWeight != null -> LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    verticalWeight
                ).apply { setMargins(0, 0, 0, dp(12)) }
                else -> LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            addView(inner)
        }
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 44f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(tvText)
        isFocusable = false
    }

    private fun subtitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setPadding(0, dp(6), 0, dp(16))
        isFocusable = false
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 30f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(tvText)
        setPadding(0, dp(2), 0, dp(18))
        isFocusable = false
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(tvMuted)
        setPadding(0, dp(4), 0, dp(4))
        isFocusable = false
    }

    private fun actionButton(text: String, enabled: Boolean = true, action: () -> Unit) = MaterialButton(this).apply {
        id = View.generateViewId()
        this.text = text
        setAllCaps(false)
        isEnabled = enabled
        isFocusable = enabled
        isFocusableInTouchMode = enabled
        minHeight = dp(58)
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        backgroundTintList = ColorStateList.valueOf(if (enabled) tvBlue else Color.rgb(71, 85, 105))
        setTextColor(if (enabled) Color.WHITE else tvMuted)
        gravity = Gravity.CENTER
        setOnClickListener { action() }
        setOnFocusChangeListener { view, hasFocus ->
            view.animate().scaleX(if (hasFocus) 1.05f else 1f)
                .scaleY(if (hasFocus) 1.05f else 1f)
                .setDuration(90L)
                .start()
            strokeWidth = if (hasFocus) dp(4) else 0
            strokeColor = ColorStateList.valueOf(Color.WHITE)
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(7), 0, dp(7)) }
        if (enabled) focusableActions += this
    }

    private fun linkActionFocus() {
        focusableActions.forEachIndexed { index, button ->
            focusableActions.getOrNull(index - 1)?.let { button.nextFocusUpId = it.id }
            focusableActions.getOrNull(index + 1)?.let { button.nextFocusDownId = it.id }
        }
    }

    private fun stateText(ok: Boolean): String = if (ok) "OK" else "SETUP"

    private fun adminStatusText(adminActive: Boolean, adminSetupAvailable: Boolean): String = when {
        adminActive && policyController.isDeviceOwner() -> "OK"
        adminActive -> "LIMITED"
        !adminSetupAvailable -> "SETUP"
        else -> "SETUP"
    }

    private fun modeLabel(mode: String): String {
        return when (mode) {
            PolicyConstants.ENFORCEMENT_DEVICE_OWNER -> "Device Owner"
            PolicyConstants.ENFORCEMENT_FALLBACK -> "Fallback"
            else -> "Setup incomplete"
        }
    }

    private fun firebaseStatusText(): String {
        return "Firebase configuration is missing. Rebuild with valid Firebase config."
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val REQUEST_VPN_PREPARE = 4101
    }
}
