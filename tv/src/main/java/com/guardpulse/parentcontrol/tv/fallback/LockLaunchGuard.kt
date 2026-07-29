package com.guardpulse.parentcontrol.tv.fallback

data class LockLaunch(
    val packageName: String,
    val reason: String,
    val settingsSectionKey: String?
)

class LockLaunchGuard(private val duplicateWindowMs: Long = 1_500L) {
    private var lastKey: String? = null
    private var lastLaunchAt = 0L

    fun evaluate(
        observedPackage: String,
        decision: FallbackDecision,
        now: Long
    ): LockLaunch? {
        if (!decision.locked) {
            lastKey = null
            return null
        }
        val packageName = decision.policyPackage ?: observedPackage
        val reason = decision.reason ?: return null
        val key = listOfNotNull(packageName, reason, decision.settingsSectionKey).joinToString(":")
        if (lastKey == key && now - lastLaunchAt in 0 until duplicateWindowMs) return null
        lastKey = key
        lastLaunchAt = now
        return LockLaunch(packageName, reason, decision.settingsSectionKey)
    }
}
