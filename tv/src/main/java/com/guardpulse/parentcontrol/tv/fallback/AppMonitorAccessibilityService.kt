package com.guardpulse.parentcontrol.tv.fallback

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.guardpulse.parentcontrol.shared.PolicyConstants
import com.guardpulse.parentcontrol.tv.policy.LocalPolicyStore
import com.guardpulse.parentcontrol.tv.system.TvServiceStarter
import com.guardpulse.parentcontrol.tv.sync.TvSyncService
import com.guardpulse.parentcontrol.tv.sync.TamperEventQueue
import com.guardpulse.parentcontrol.tv.usage.UsageTracker

class AppMonitorAccessibilityService : AccessibilityService() {
    private lateinit var localPolicyStore: LocalPolicyStore
    private lateinit var fallbackStore: FallbackStateStore
    private lateinit var usageTracker: UsageTracker
    private var lastLockedKey: String? = null
    private var lastLockAt = 0L
    private var lastLiveLimitCheckAt = 0L
    private var lastLiveLimitCheckPackage: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val foregroundPollRunnable = object : Runnable {
        override fun run() {
            evaluateCurrentWindow()
            mainHandler.postDelayed(this, FOREGROUND_RECHECK_MS)
        }
    }
    private val policyChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "policies" ||
            key == "safeModeUntil" ||
            key == "activeModeId" ||
            key?.startsWith("dailyBlocks:") == true ||
            key?.startsWith("usageOffsets") == true
        ) {
            mainHandler.post { evaluateCurrentWindow() }
        }
    }

    override fun onServiceConnected() {
        localPolicyStore = LocalPolicyStore(this)
        fallbackStore = FallbackStateStore(this)
        usageTracker = UsageTracker(this)
        localPolicyStore.registerChangeListener(policyChangeListener)
        runCatching { TvServiceStarter.start(this) }
        mainHandler.post(foregroundPollRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (!::localPolicyStore.isInitialized) return
        evaluateForeground(
            packageName = packageName,
            eventClassName = event.className,
            eventText = event.text,
            root = rootInActiveWindow
        )
    }

    private fun evaluateCurrentWindow() {
        if (!::localPolicyStore.isInitialized) return
        val root = rootInActiveWindow
        val packageName = root?.packageName?.toString()
            ?: fallbackStore.lastForeground()
            ?: return
        evaluateForeground(
            packageName = packageName,
            eventClassName = root?.className,
            eventText = emptyList(),
            root = root
        )
    }

    private fun evaluateForeground(
        packageName: String,
        eventClassName: CharSequence?,
        eventText: List<CharSequence>,
        root: AccessibilityNodeInfo?
    ) {
        fallbackStore.saveLastForeground(packageName)
        trackLiveForeground(packageName)
        clearSetupVisitUnlockIfLeft(packageName)
        clearAppVisitUnlockIfLeft(packageName)
        val settingsSection = SettingsSectionDetector.detect(
            packageName = packageName,
            eventClassName = eventClassName,
            eventText = eventText,
            root = root
        )
        if (settingsSection == null && packageName in PolicyConstants.primarySettingsPackages) {
            fallbackStore.clearSettingsSectionUnlock()
        }
        if (packageName !in PolicyConstants.primarySettingsPackages && packageName != this.packageName) {
            fallbackStore.clearSettingsSectionUnlock()
        }

        val policies = localPolicyStore.loadPolicies()
        val dailyBlocks = localPolicyStore.loadDailyLimitBlocks().toMutableSet()
        enforceLiveDailyLimit(packageName, policies, dailyBlocks)

        val decision = FallbackProtection.shouldLock(
            context = this,
            foregroundPackage = packageName,
            policies = policies,
            dailyBlocks = dailyBlocks,
            fallbackStore = fallbackStore,
            settingsSection = settingsSection
        )
        if (!decision.locked) return

        val now = System.currentTimeMillis()
        val lockKey = listOfNotNull(packageName, decision.reason, decision.settingsSectionKey).joinToString(":")
        if (lastLockedKey == lockKey && now - lastLockAt < 1_500L) return
        lastLockedKey = lockKey
        lastLockAt = now
        if (decision.reason == PolicyConstants.BLOCK_REASON_RISKY_SETTINGS ||
            decision.reason == PolicyConstants.BLOCK_REASON_SETTINGS_SECTION
        ) {
            uploadRiskySettingsEvent(packageName)
        }
        FallbackProtection.openLock(
            this,
            decision.policyPackage ?: packageName,
            decision.reason ?: PolicyConstants.BLOCK_REASON_MANUAL,
            decision.settingsSectionKey
        )
    }

    private fun trackLiveForeground(packageName: String) {
        val usagePackage = usagePolicyPackage(packageName)
        val current = fallbackStore.liveForegroundSession()
        if (usagePackage == null) {
            if (current != null) {
                fallbackStore.finalizeLiveForegroundSession()
                runCatching { TvServiceStarter.start(this, TvSyncService.ACTION_FOREGROUND_CHANGED) }
            }
            return
        }
        if (current?.packageName == usagePackage) {
            fallbackStore.refreshLiveForegroundSession()
            return
        }
        val baselineMs = usageTracker.rawUsageMillisToday()[usagePackage] ?: 0L
        fallbackStore.startLiveForegroundSession(usagePackage, baselineMs)
        runCatching { TvServiceStarter.start(this, TvSyncService.ACTION_FOREGROUND_CHANGED) }
    }

    private fun enforceLiveDailyLimit(
        foregroundPackage: String,
        policies: Map<String, com.guardpulse.parentcontrol.tv.policy.AppPolicy>,
        dailyBlocks: MutableSet<String>
    ) {
        val usagePackage = usagePolicyPackage(foregroundPackage) ?: return
        val now = System.currentTimeMillis()
        if (lastLiveLimitCheckPackage != usagePackage) {
            lastLiveLimitCheckPackage = usagePackage
            lastLiveLimitCheckAt = 0L
        }
        if (now - lastLiveLimitCheckAt < LIVE_LIMIT_CHECK_MS) return
        lastLiveLimitCheckAt = now
        if (usagePackage in dailyBlocks) return

        val limit = policies[usagePackage]?.dailyLimitMinutes ?: return
        val usageOffsetMs = localPolicyStore.loadUsageOffsetsMs()[usagePackage] ?: 0L
        val usedMs = (
            (usageTracker.effectiveUsageMillisToday(
                fallbackStore.liveForegroundSession(),
                fallbackStore.committedUsageMillisToday()
            )[usagePackage] ?: 0L) -
                usageOffsetMs
            ).coerceAtLeast(0L)
        if (usedMs < limit * 60_000L) return

        localPolicyStore.markDailyLimitBlocked(usagePackage)
        dailyBlocks.add(usagePackage)
        runCatching { TvServiceStarter.start(this, TvSyncService.ACTION_RECONCILE) }
    }

    private fun usagePolicyPackage(packageName: String): String? {
        val policyPackage = PolicyConstants.sourceLockPolicyPackage(packageName) ?: packageName
        if (policyPackage == this.packageName) return null
        if (policyPackage in PolicyConstants.alwaysProtectedPackages &&
            policyPackage !in PolicyConstants.parentVisibleLockPackages
        ) {
            return null
        }
        return policyPackage
    }

    private fun clearAppVisitUnlockIfLeft(packageName: String) {
        val unlockedPolicyPackage = fallbackStore.appVisitUnlockPackage() ?: return
        if (packageName == this.packageName) return
        val currentPolicyPackage = PolicyConstants.sourceLockPolicyPackage(packageName) ?: packageName
        if (currentPolicyPackage != unlockedPolicyPackage) {
            fallbackStore.clearAppVisitUnlock()
        }
    }

    private fun clearSetupVisitUnlockIfLeft(packageName: String) {
        if (packageName != this.packageName) {
            fallbackStore.clearSetupVisitUnlock()
        }
    }

    override fun onDestroy() {
        if (::fallbackStore.isInitialized) fallbackStore.finalizeLiveForegroundSession()
        if (::localPolicyStore.isInitialized) {
            localPolicyStore.unregisterChangeListener(policyChangeListener)
        }
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun uploadRiskySettingsEvent(packageName: String) {
        if (!fallbackStore.shouldReportTamper(PolicyConstants.TAMPER_RISKY_SETTINGS_OPENED)) return
        TamperEventQueue.enqueue(
            applicationContext,
            PolicyConstants.TAMPER_RISKY_SETTINGS_OPENED,
            "Protected settings opened: $packageName"
        )
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val FOREGROUND_RECHECK_MS = 1_000L
        private const val LIVE_LIMIT_CHECK_MS = 5_000L
    }
}
