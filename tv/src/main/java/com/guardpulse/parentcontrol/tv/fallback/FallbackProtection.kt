package com.guardpulse.parentcontrol.tv.fallback

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.guardpulse.parentcontrol.shared.PolicyConstants
import com.guardpulse.parentcontrol.tv.admin.TvDeviceAdminReceiver
import com.guardpulse.parentcontrol.tv.policy.AppPolicy
import com.guardpulse.parentcontrol.tv.policy.DevicePolicyController

object FallbackProtection {
    fun enforcementMode(context: Context): String {
        val policy = DevicePolicyController(context)
        if (policy.isDeviceOwner()) return PolicyConstants.ENFORCEMENT_DEVICE_OWNER
        return if (isAccessibilityEnabled(context)) {
            PolicyConstants.ENFORCEMENT_FALLBACK
        } else {
            PolicyConstants.ENFORCEMENT_UNPROTECTED
        }
    }

    fun isDeviceAdminSetupAvailable(context: Context): Boolean {
        return deviceAdminIntent(context).resolveActivity(context.packageManager) != null
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val expected = ComponentName(context, AppMonitorAccessibilityService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == expected.packageName &&
                it.resolveInfo.serviceInfo.name == expected.className }
    }

    fun shouldLock(
        context: Context,
        foregroundPackage: String,
        policies: Map<String, AppPolicy>,
        dailyBlocks: Set<String>,
        fallbackStore: FallbackStateStore,
        settingsSection: ProtectedSettingsSection? = null
    ): FallbackDecision {
        if (foregroundPackage == context.packageName) return FallbackDecision(false)
        if (fallbackStore.isSafeModeActive()) return FallbackDecision(false)

        if (settingsSection != null &&
            isSettingsSectionLockEnabled(settingsSection.policyPackage, policies, dailyBlocks) &&
            !fallbackStore.isSettingsSectionUnlocked(settingsSection.key)
        ) {
            return FallbackDecision(
                locked = true,
                reason = PolicyConstants.BLOCK_REASON_SETTINGS_SECTION,
                policyPackage = settingsSection.policyPackage,
                settingsSectionKey = settingsSection.key
            )
        }

        val sourcePolicyPackage = PolicyConstants.sourceLockPolicyPackage(foregroundPackage)
        val policyPackage = sourcePolicyPackage ?: foregroundPackage
        if (fallbackStore.isAppVisitUnlocked(policyPackage)) return FallbackDecision(false)
        if (fallbackStore.isTemporarilyUnlocked(policyPackage) ||
            fallbackStore.isTemporarilyUnlocked(foregroundPackage)
        ) {
            return FallbackDecision(false)
        }

        // Admin-disable gate: while a deactivation request is pending, any
        // settings-package foreground re-locks. Unlocks granted via the PIN
        // above still open it — the gate exists to stop pinless re-entry after
        // pressing HOME away from the lock screen, not to trap the parent.
        if (fallbackStore.isAdminChangePending() &&
            foregroundPackage in PolicyConstants.primarySettingsPackages
        ) {
            return FallbackDecision(
                locked = true,
                reason = PolicyConstants.BLOCK_REASON_RISKY_SETTINGS,
                policyPackage = foregroundPackage
            )
        }

        if (foregroundPackage in PolicyConstants.alwaysProtectedPackages &&
            foregroundPackage !in PolicyConstants.primarySettingsPackages
        ) {
            return FallbackDecision(false)
        }

        if (foregroundPackage in PolicyConstants.riskySettingsPackages &&
            fallbackStore.isSetupSettingsAccessAllowed()
        ) {
            return FallbackDecision(false)
        }

        val policy = effectivePolicy(policyPackage, policies)
        val policyBlocked = policy.manualBlocked ||
            (policy.dailyLimitMinutes != null && policyPackage in dailyBlocks)

        if (sourcePolicyPackage != null) {
            if (!policyBlocked) return FallbackDecision(false)
            return FallbackDecision(
                locked = true,
                reason = PolicyConstants.BLOCK_REASON_SOURCE_LOCK,
                policyPackage = sourcePolicyPackage
            )
        }

        if (foregroundPackage in PolicyConstants.primarySettingsPackages) {
            if (!policyBlocked) return FallbackDecision(false)
            return FallbackDecision(
                locked = true,
                reason = PolicyConstants.BLOCK_REASON_RISKY_SETTINGS,
                policyPackage = foregroundPackage
            )
        }

        if (foregroundPackage in PolicyConstants.riskySettingsPackages) {
            if (fallbackStore.isSetupSettingsAccessAllowed()) return FallbackDecision(false)
            return FallbackDecision(
                locked = true,
                reason = PolicyConstants.BLOCK_REASON_RISKY_SETTINGS,
                policyPackage = foregroundPackage
            )
        }

        if (policyPackage in PolicyConstants.virtualPolicyPackages) return FallbackDecision(false)
        if (!policyBlocked) return FallbackDecision(false)

        return FallbackDecision(
            locked = true,
            reason = lockReason(policy, policyPackage, dailyBlocks),
            policyPackage = policyPackage
        )
    }

    fun openLock(
        context: Context,
        packageName: String,
        reason: String,
        settingsSectionKey: String? = null
    ) {
        val intent = Intent(context, LockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(LockActivity.EXTRA_PACKAGE_NAME, packageName)
            .putExtra(LockActivity.EXTRA_REASON, reason)
            .putExtra(LockActivity.EXTRA_SETTINGS_SECTION_KEY, settingsSectionKey)
        runCatching { context.startActivity(intent) }
    }

    fun deviceAdminIntent(context: Context): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                ComponentName(context, TvDeviceAdminReceiver::class.java)
            )
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Required for device protection policies."
            )
    }

    fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    fun usageAccessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun isSettingsSectionLockEnabled(
        policyPackage: String?,
        policies: Map<String, AppPolicy>,
        dailyBlocks: Set<String>
    ): Boolean {
        val packageName = policyPackage ?: return false
        if (packageName !in PolicyConstants.settingsSectionLockPackages) return false
        val policy = effectivePolicy(packageName, policies)
        return policy.manualBlocked || packageName in dailyBlocks
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

    private fun lockReason(
        policy: AppPolicy,
        policyPackage: String,
        dailyBlocks: Set<String>
    ): String {
        return if (!policy.manualBlocked && policyPackage in dailyBlocks) {
            PolicyConstants.BLOCK_REASON_DAILY_LIMIT
        } else {
            PolicyConstants.BLOCK_REASON_MANUAL
        }
    }
}
