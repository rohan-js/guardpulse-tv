package com.guardpulse.parentcontrol.tv.fallback

import com.guardpulse.parentcontrol.shared.PolicyConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedUnlockPolicyTest {
    private val now = 1_000_000_000L

    @Test
    fun freshApprovedUnappliedRequestIsApplied() {
        assertTrue(
            ApprovedUnlockPolicy.shouldApply(
                PolicyConstants.UNLOCK_APPROVED,
                null,
                now - 60_000L,
                now
            )
        )
    }

    @Test
    fun approvalRightAtWindowEdgeIsApplied() {
        assertTrue(
            ApprovedUnlockPolicy.shouldApply(
                PolicyConstants.UNLOCK_APPROVED,
                null,
                now - ApprovedUnlockPolicy.APPROVED_APPLY_MAX_AGE_MS,
                now
            )
        )
    }

    @Test
    fun alreadyAppliedRequestIsSkipped() {
        assertFalse(
            ApprovedUnlockPolicy.shouldApply(
                PolicyConstants.UNLOCK_APPROVED,
                PolicyConstants.SYNC_STATUS_APPLIED,
                now - 60_000L,
                now
            )
        )
    }

    @Test
    fun pendingRequestIsNotAppliedHere() {
        assertFalse(
            ApprovedUnlockPolicy.shouldApply(
                PolicyConstants.UNLOCK_PENDING,
                null,
                now - 60_000L,
                now
            )
        )
    }

    @Test
    fun deniedRequestIsNotApplied() {
        assertFalse(
            ApprovedUnlockPolicy.shouldApply(
                PolicyConstants.UNLOCK_DENIED,
                null,
                now - 60_000L,
                now
            )
        )
    }

    @Test
    fun staleApprovalIsNotApplied() {
        assertFalse(
            ApprovedUnlockPolicy.shouldApply(
                PolicyConstants.UNLOCK_APPROVED,
                null,
                now - ApprovedUnlockPolicy.APPROVED_APPLY_MAX_AGE_MS - 1,
                now
            )
        )
    }

    @Test
    fun missingOrInvalidTimestampIsRejected() {
        assertFalse(ApprovedUnlockPolicy.shouldApply(PolicyConstants.UNLOCK_APPROVED, null, null, now))
        assertFalse(ApprovedUnlockPolicy.shouldApply(PolicyConstants.UNLOCK_APPROVED, null, 0L, now))
    }
}
