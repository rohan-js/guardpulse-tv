package com.guardpulse.parentcontrol.tv.fallback

data class LockLaunch(
    val packageName: String,
    val reason: String,
    val settingsSectionKey: String?
)

class LockLaunchGuard(private val duplicateWindowMs: Long = 1_500L) {
    private var lastKey: String? = null
    private var lastLaunchAt = 0L

    /**
     * [isOwnPackage] marks events from GuardPulse itself (the PIN wall being
     * foreground). Those must not reset [lastKey]: while the wall is up, every
     * covered app's window event re-locks, and a reset turns each such event
     * into an immediate relaunch that wipes the PIN being typed via
     * LockActivity.onNewIntent.
     */
    fun evaluate(
        observedPackage: String,
        decision: FallbackDecision,
        now: Long,
        isOwnPackage: Boolean = false
    ): LockLaunch? {
        if (!decision.locked) {
            if (!isOwnPackage) {
                lastKey = null
            }
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
