package com.guardpulse.parentcontrol.tv.fallback

import com.guardpulse.parentcontrol.shared.PolicyConstants

/**
 * Decision for applying an unlock approval that no lock screen consumed while
 * it was open. The TV may only re-write an approved request with
 * tvApplyStatus="applied" (rules forbid transitioning approved to expired), so
 * approvals older than the apply window are left for the parent-side retention
 * cleaner instead of unlocking the app hours later.
 */
object ApprovedUnlockPolicy {
    const val APPROVED_APPLY_MAX_AGE_MS = 30L * 60_000L

    fun shouldApply(
        status: String?,
        tvApplyStatus: String?,
        approvedUpdatedAtMs: Long?,
        now: Long
    ): Boolean {
        if (status != PolicyConstants.UNLOCK_APPROVED) return false
        if (tvApplyStatus == PolicyConstants.SYNC_STATUS_APPLIED) return false
        val updatedAt = approvedUpdatedAtMs ?: return false
        if (updatedAt <= 0L) return false
        return now - updatedAt in 0..APPROVED_APPLY_MAX_AGE_MS
    }
}
