package com.guardpulse.parentcontrol.tv.pairing

import com.guardpulse.parentcontrol.shared.PolicyConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingGracePolicyTest {

    private val ttl = PolicyConstants.PAIRING_TTL_MS

    @Test
    fun `current generation matches within ttl`() {
        val now = 1_000_000L
        assertTrue(
            PairingGracePolicy.isValid(
                secret = "s1", code = null, requestCreatedAt = now - 60_000, now = now,
                curSecret = "s1", curCode = "c1", prevSecret = null, prevCode = null, prevCreatedAt = 0L
            )
        )
        assertTrue(
            PairingGracePolicy.isValid(
                secret = null, code = "c1", requestCreatedAt = now - 60_000, now = now,
                curSecret = "s1", curCode = "c1", prevSecret = null, prevCode = null, prevCreatedAt = 0L
            )
        )
    }

    @Test
    fun `stale request beyond ttl rejected`() {
        val now = 1_000_000L
        assertFalse(
            PairingGracePolicy.isValid(
                secret = "s1", code = null, requestCreatedAt = now - ttl - 1, now = now,
                curSecret = "s1", curCode = "c1", prevSecret = null, prevCode = null, prevCreatedAt = 0L
            )
        )
    }

    @Test
    fun `wrong credentials rejected`() {
        val now = 1_000_000L
        assertFalse(
            PairingGracePolicy.isValid(
                secret = "nope", code = null, requestCreatedAt = now - 60_000, now = now,
                curSecret = "s1", curCode = "c1", prevSecret = null, prevCode = null, prevCreatedAt = 0L
            )
        )
    }

    @Test
    fun `request created before rotation arriving after rotation matches prev`() {
        // Production shape of the rotation-boundary grace: the previous generation
        // becomes prev exactly at prevCreatedAt + TTL (the auto-rotation trigger),
        // so a request created seconds before the boundary is validated when prev
        // is already OLDER than one TTL. The 2x-TTL window is what makes that pass.
        val prevMintedAt = 1_000_000L
        val requestCreatedAt = prevMintedAt + 9 * 60_000L
        val now = prevMintedAt + 13 * 60_000L

        assertTrue(
            PairingGracePolicy.isValid(
                secret = "s1", code = null, requestCreatedAt = requestCreatedAt, now = now,
                curSecret = "s2", curCode = "c2", prevSecret = "s1", prevCode = "c1",
                prevCreatedAt = prevMintedAt
            )
        )
        assertTrue(
            PairingGracePolicy.isValid(
                secret = null, code = "c1", requestCreatedAt = requestCreatedAt, now = now,
                curSecret = "s2", curCode = "c2", prevSecret = "s1", prevCode = "c1",
                prevCreatedAt = prevMintedAt
            )
        )
    }

    @Test
    fun `grace slot expires after double ttl`() {
        val prevMintedAt = 1_000_000L
        val requestCreatedAt = prevMintedAt + 12 * 60_000L
        val now = prevMintedAt + 21 * 60_000L

        assertFalse(
            PairingGracePolicy.isValid(
                secret = "s1", code = null, requestCreatedAt = requestCreatedAt, now = now,
                curSecret = "s2", curCode = "c2", prevSecret = "s1", prevCode = "c1",
                prevCreatedAt = prevMintedAt
            )
        )
    }

    @Test
    fun `two generations old rejected`() {
        val now = 1_000_000L
        assertFalse(
            PairingGracePolicy.isValid(
                secret = "s0", code = null, requestCreatedAt = now - 60_000, now = now,
                curSecret = "s2", curCode = "c2", prevSecret = "s1", prevCode = "c1",
                prevCreatedAt = now - ttl
            )
        )
    }

    @Test
    fun `no grace slot recorded rejected even on secret collision with null`() {
        val now = 1_000_000L
        assertFalse(
            PairingGracePolicy.isValid(
                secret = "s1", code = null, requestCreatedAt = now - 60_000, now = now,
                curSecret = "s2", curCode = "c2", prevSecret = null, prevCode = null, prevCreatedAt = 0L
            )
        )
    }

    @Test
    fun `blank request credentials rejected`() {
        val now = 1_000_000L
        assertFalse(
            PairingGracePolicy.isValid(
                secret = "", code = "", requestCreatedAt = now - 60_000, now = now,
                curSecret = "s2", curCode = "c2", prevSecret = "s1", prevCode = "c1",
                prevCreatedAt = now
            )
        )
    }
}
