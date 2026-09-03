package com.guardpulse.parentcontrol.tv.sync

import com.guardpulse.parentcontrol.shared.PolicyConstants
import com.guardpulse.parentcontrol.tv.policy.AppPolicy

/**
 * Pure merge of base policies with the active mode's app entries.
 *
 * The mode wins for apps it explicitly covers (a mode is a deliberate preset);
 * every app the mode does not mention falls back to its root policy so parent
 * toggles keep working while a mode is active. Default-locked sections apply
 * unless base or the mode set them explicitly. With no active mode the base
 * map is returned untouched — matching the pre-mode behavior, where the
 * default-lock fallback lives in effectivePolicy().
 */
internal object EffectivePolicies {
    fun merge(
        basePolicies: Map<String, AppPolicy>,
        activeModeAppPolicies: Map<String, AppPolicy>?,
        defaultLockedPackages: Set<String> = PolicyConstants.defaultLockedPackages
    ): Map<String, AppPolicy> {
        // Only a literally-absent mode means "no mode": an active mode with an
        // empty app map still goes through the merge so the section defaults
        // apply and the base map is returned as a copy.
        if (activeModeAppPolicies == null) return basePolicies
        val merged = basePolicies.toMutableMap()
        activeModeAppPolicies.forEach { (packageName, policy) ->
            merged[packageName] = policy
        }
        defaultLockedPackages.forEach { packageName ->
            merged.putIfAbsent(packageName, AppPolicy(manualBlocked = true))
        }
        return merged
    }
}
