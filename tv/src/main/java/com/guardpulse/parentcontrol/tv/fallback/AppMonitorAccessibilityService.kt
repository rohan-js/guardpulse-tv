package com.guardpulse.parentcontrol.tv.fallback

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.guardpulse.parentcontrol.shared.PolicyConstants
import com.guardpulse.parentcontrol.tv.activity.MediaAccessibilityParser
import com.guardpulse.parentcontrol.tv.activity.MediaBrowserProbe
import com.guardpulse.parentcontrol.tv.activity.MediaSessionHub
import com.guardpulse.parentcontrol.tv.activity.MediaTitlePolicy
import com.guardpulse.parentcontrol.tv.activity.PlaybackAudioMonitor
import com.guardpulse.parentcontrol.tv.activity.TvActivityTracker
import com.guardpulse.parentcontrol.tv.policy.LocalPolicyStore
import com.guardpulse.parentcontrol.tv.system.ScreenState
import com.guardpulse.parentcontrol.tv.system.SystemTimeGuard
import com.guardpulse.parentcontrol.tv.system.TvServiceStarter
import com.guardpulse.parentcontrol.tv.system.registerScreenStateReceiver
import com.guardpulse.parentcontrol.tv.sync.TvSyncService
import com.guardpulse.parentcontrol.tv.sync.TamperEventQueue
import com.guardpulse.parentcontrol.tv.usage.UsageTracker

class AppMonitorAccessibilityService : AccessibilityService() {
    private lateinit var localPolicyStore: LocalPolicyStore
    private lateinit var fallbackStore: FallbackStateStore
    private lateinit var usageTracker: UsageTracker
    private val lockLaunchGuard = LockLaunchGuard()
    private var lastLiveLimitCheckAt = 0L
    private var lastLiveLimitCheckPackage: String? = null
    @Volatile private var lastEventHandledAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activityTracker: TvActivityTracker? = null
    private var audioMonitor: PlaybackAudioMonitor? = null
    private var mediaBrowserProbe: MediaBrowserProbe? = null
    private var lastNodeWalkAt = 0L
    private var screenStateReceiver: BroadcastReceiver? = null

    private val foregroundPollRunnable = object : Runnable {
        override fun run() {
            // Events keep the foreground evaluation warm; the poll is only a
            // safety net for when they stop flowing, so skip while one landed recently.
            val now = System.currentTimeMillis()
            if (now - lastEventHandledAt >= POLL_EVENT_GRACE_MS) {
                evaluateCurrentWindow()
            }
            // After a minute of total event silence (screen off, idle launcher)
            // relax the safety net to one pass every 5 s; any event restores 1 s.
            val idle = now - lastEventHandledAt >= POLL_IDLE_AFTER_MS
            mainHandler.postDelayed(this, if (idle) POLL_IDLE_RECHECK_MS else FOREGROUND_RECHECK_MS)
        }
    }

    // A TYPE_WINDOW_STATE_CHANGED frequently arrives before the new window's
    // nodes are queryable, and the event path is subject to the poll grace —
    // together that leaves up to ~2.5 s of undetected window transition, enough
    // to start a toggle flow inside a locked settings section. A one-shot
    // re-evaluate 300 ms out closes the gap regardless of event flow.
    private val windowSettleRunnable = Runnable { evaluateCurrentWindow() }

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
        SystemTimeGuard.initialize(this)
        localPolicyStore = LocalPolicyStore(this)
        fallbackStore = FallbackStateStore(this)
        usageTracker = UsageTracker(this)
        localPolicyStore.registerChangeListener(policyChangeListener)
        runCatching { TvServiceStarter.start(this) }
        activityTracker = TvActivityTracker(this)
        audioMonitor = PlaybackAudioMonitor(this, { isPlaying ->
            val foreground = fallbackStore.lastForeground()
            if (foreground != null) {
                runCatching {
                    activityTracker?.observeAudioPlayback(foreground, isPlaying)
                }
            }
        }).also { monitor -> runCatching { monitor.start() } }
        mediaBrowserProbe = MediaBrowserProbe(this) { runtimePackage, title, subtitle, playbackState, positionMs, durationMs ->
            runCatching {
                activityTracker?.observeMediaBrowser(runtimePackage, title, subtitle, playbackState, positionMs, durationMs)
            }
        }
        MediaSessionHub.setListener(object : MediaSessionHub.Listener {
            override fun onSessionMedia(
                runtimePackage: String,
                title: String?,
                subtitle: String?,
                playbackState: String?,
                positionMs: Long?,
                durationMs: Long?
            ) {
                runCatching {
                    activityTracker?.observeMediaSession(runtimePackage, title, subtitle, playbackState, positionMs, durationMs)
                }
            }
        })
        screenStateReceiver = registerScreenStateReceiver(this) { off ->
            mainHandler.post { onScreenStateChanged(off) }
        }
        mainHandler.post(foregroundPollRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (!::localPolicyStore.isInitialized) return
        if (ScreenState.off) {
            // An event while the display is off means the screen actually woke
            // without a broadcast; clear the flag (so the sync tick resumes its
            // full passes) and restart the loop, then process this event.
            ScreenState.off = false
            onScreenStateChanged(false)
        }
        val isWindowTransition = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (isWindowTransition) {
            mainHandler.removeCallbacks(windowSettleRunnable)
            mainHandler.postDelayed(windowSettleRunnable, WINDOW_SETTLE_RECHECK_MS)
        }
        // rootInActiveWindow is an IPC; only the Settings-section detector and
        // the rate-limited media node walk actually consume it.
        val needsRoot = packageName in PolicyConstants.primarySettingsPackages ||
            (MediaTitlePolicy.shouldWalkNodes(packageName, event.text, MediaSessionHub.sessionPackages) &&
                System.currentTimeMillis() - lastNodeWalkAt >= MEDIA_NODE_WALK_MIN_INTERVAL_MS)
        evaluateForeground(
            packageName = packageName,
            eventClassName = event.className,
            eventText = event.text,
            root = if (needsRoot) rootInActiveWindow else null,
            isWindowTransition = isWindowTransition
        )
        lastEventHandledAt = System.currentTimeMillis()
    }

    private fun onScreenStateChanged(off: Boolean) {
        if (off) {
            // Display off: stop the poll loop and release the media probe.
            // Finalizing the session while paused would count the whole dark
            // period as app usage, so the session is simply frozen and resumes
            // on the next real event / SCREEN_ON.
            mainHandler.removeCallbacks(foregroundPollRunnable)
            mainHandler.removeCallbacks(windowSettleRunnable)
            disconnectMediaBrowserProbe()
        } else {
            mainHandler.removeCallbacks(foregroundPollRunnable)
            lastEventHandledAt = 0L
            mainHandler.post(foregroundPollRunnable)
            evaluateCurrentWindow()
        }
    }

    private fun evaluateCurrentWindow() {
        if (!::localPolicyStore.isInitialized) return
        if (ScreenState.off) return
        val root = rootInActiveWindow
        val packageName = root?.packageName?.toString()
            ?: fallbackStore.lastForeground()
            ?: return
        evaluateForeground(
            packageName = packageName,
            eventClassName = root?.className,
            eventText = emptyList(),
            root = root,
            isWindowTransition = true
        )
    }

    private fun evaluateForeground(
        packageName: String,
        eventClassName: CharSequence?,
        eventText: List<CharSequence>,
        root: AccessibilityNodeInfo?,
        isWindowTransition: Boolean
    ) {
        fallbackStore.saveLastForeground(packageName)
        trackLiveForeground(packageName)
        clearSetupVisitUnlockIfLeft(packageName, isWindowTransition)
        clearAppVisitUnlockIfLeft(packageName, isWindowTransition)
        val settingsSection = SettingsSectionDetector.detect(
            packageName = packageName,
            eventClassName = eventClassName,
            eventText = eventText,
            root = root
        )
        // Section unlocks clear on leaving Settings (or GuardPulse flow) only —
        // never on a detector miss while still inside Settings: the text
        // heuristics return null on overlays, dense grids, and node-budget
        // exhaustion, and clearing there re-locks mid-visit and funnels the next
        // PIN entry through the whole-Settings wall.
        if (packageName !in PolicyConstants.primarySettingsPackages &&
            packageName != this.packageName &&
            packageName != TRANSPARENT_OVERLAY_PACKAGE &&
            isWindowTransition
        ) {
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
        val now = System.currentTimeMillis()
        val isOwnPackage = packageName == this.packageName
        val launch = lockLaunchGuard.evaluate(packageName, decision, now, isOwnPackage) ?: run {
            trackActivity(packageName, eventClassName, eventText, root)
            return
        }
        if (decision.reason == PolicyConstants.BLOCK_REASON_RISKY_SETTINGS ||
            decision.reason == PolicyConstants.BLOCK_REASON_SETTINGS_SECTION
        ) {
            uploadRiskySettingsEvent(packageName)
        }
        FallbackProtection.openLock(
            this,
            launch.packageName,
            launch.reason,
            launch.settingsSectionKey
        )
    }

    /**
     * Media-activity tracking rides the same evaluation pass. The node-tree
     * walk is the only expensive part, so it is rate-limited and restricted to
     * packages the parser actually understands; everything else updates the
     * current-app session cheaply.
     */
    private fun trackActivity(
        packageName: String,
        eventClassName: CharSequence?,
        eventText: List<CharSequence>,
        root: AccessibilityNodeInfo?
    ) {
        val tracker = activityTracker ?: return
        runCatching {
            val now = System.currentTimeMillis()
            if (MediaTitlePolicy.shouldWalkNodes(packageName, eventText, MediaSessionHub.sessionPackages) &&
                now - lastNodeWalkAt >= MEDIA_NODE_WALK_MIN_INTERVAL_MS
            ) {
                lastNodeWalkAt = now
                tracker.observe(packageName, eventClassName, eventText, emptyList(), root)
            } else {
                tracker.observePackageOnly(packageName)
            }
        }
    }

    private fun trackLiveForeground(packageName: String) {
        // A volume/PiP overlay window is not leaving the app: finalizing here
        // would disconnect the probe, fire a reconcile and restart the usage
        // session on every overlay blink during playback.
        if (packageName == TRANSPARENT_OVERLAY_PACKAGE) return
        val usagePackage = usagePolicyPackage(packageName)
        val current = fallbackStore.liveForegroundSession()
        if (usagePackage == null) {
            if (current != null) {
                fallbackStore.finalizeLiveForegroundSession()
                disconnectMediaBrowserProbe()
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
        connectMediaBrowserProbe(packageName)
        runCatching { TvServiceStarter.start(this, TvSyncService.ACTION_FOREGROUND_CHANGED) }
    }

    private fun connectMediaBrowserProbe(runtimePackage: String) {
        runCatching { mediaBrowserProbe?.connect(runtimePackage) }
    }

    private fun disconnectMediaBrowserProbe() {
        runCatching { mediaBrowserProbe?.disconnect() }
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

    private fun clearAppVisitUnlockIfLeft(packageName: String, isWindowTransition: Boolean) {
        if (!isWindowTransition) return
        val unlockedPolicyPackage = fallbackStore.appVisitUnlockPackage() ?: return
        if (packageName == this.packageName || packageName == TRANSPARENT_OVERLAY_PACKAGE) return
        val currentPolicyPackage = PolicyConstants.sourceLockPolicyPackage(packageName) ?: packageName
        if (currentPolicyPackage != unlockedPolicyPackage) {
            fallbackStore.clearAppVisitUnlock()
        }
    }

    private fun clearSetupVisitUnlockIfLeft(packageName: String, isWindowTransition: Boolean) {
        if (!isWindowTransition) return
        if (packageName != this.packageName && packageName != TRANSPARENT_OVERLAY_PACKAGE) {
            fallbackStore.clearSetupVisitUnlock()
        }
    }

    override fun onDestroy() {
        if (::fallbackStore.isInitialized) fallbackStore.finalizeLiveForegroundSession()
        if (::localPolicyStore.isInitialized) {
            localPolicyStore.unregisterChangeListener(policyChangeListener)
        }
        screenStateReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenStateReceiver = null
        runCatching { audioMonitor?.stop() }
        disconnectMediaBrowserProbe()
        MediaSessionHub.setListener(null)
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
        // After this much event silence the poll relaxes to one pass per 5 s.
        private const val POLL_IDLE_AFTER_MS = 60_000L
        private const val POLL_IDLE_RECHECK_MS = 5_000L
        private const val LIVE_LIMIT_CHECK_MS = 5_000L
        private const val POLL_EVENT_GRACE_MS = 1_500L
        private const val WINDOW_SETTLE_RECHECK_MS = 300L
        private const val MEDIA_NODE_WALK_MIN_INTERVAL_MS = 750L

        // Volume/PiP overlay windows report com.android.systemui as the event
        // source while the user is still inside the unlocked app; treating them
        // as app exits would kill one-visit unlocks mid-usage.
        private const val TRANSPARENT_OVERLAY_PACKAGE = "com.android.systemui"
    }
}
