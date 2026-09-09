package com.guardpulse.parentcontrol.tv.pairing

import com.guardpulse.parentcontrol.shared.PolicyConstants
import java.security.MessageDigest

/**
 * Pure decision core for pairing-credential validation, extracted from
 * [PairingManager] so the rotation-boundary grace is unit testable without
 * Android plumbing.
 *
 * The grace window is 2x [PolicyConstants.PAIRING_TTL_MS]: the previous
 * credential generation was the CURRENT one for a full TTL, and a request
 * created any time inside that window can reach the device up to another TTL
 * later, so prev stays acceptable until prevCreatedAt + 20 min. A 1x-TTL
 * window here would expire prev the instant it becomes prev (rotation fires
 * exactly when now reaches prevCreatedAt + TTL) — a zero-width grace that
 * answered every boundary-crossing scan with "expired" and looped the parent
 * into endless re-scans (the laptop agent shipped this same fix as 0.2.34).
 */
object PairingGracePolicy {

    /**
     * True when the request is fresh (created within one pairing TTL of [now])
     * AND the presented secret or manual code matches the current generation,
     * or — as the rotation-boundary grace — the immediately-previous generation
     * while prevCreatedAt + 2x TTL has not passed.
     */
    fun isValid(
        secret: String?,
        code: String?,
        requestCreatedAt: Long,
        now: Long,
        curSecret: String?,
        curCode: String?,
        prevSecret: String?,
        prevCode: String?,
        prevCreatedAt: Long
    ): Boolean {
        if (requestCreatedAt <= 0 || now - requestCreatedAt > PolicyConstants.PAIRING_TTL_MS) return false
        if (matches(secret, code, curSecret, curCode)) return true
        if (prevCreatedAt <= 0L || now - prevCreatedAt > 2 * PolicyConstants.PAIRING_TTL_MS) return false
        return matches(secret, code, prevSecret, prevCode)
    }

    private fun matches(
        secret: String?,
        code: String?,
        expectedSecret: String?,
        expectedCode: String?
    ): Boolean {
        val secretMatches = !secret.isNullOrBlank() &&
            !expectedSecret.isNullOrBlank() &&
            constantTimeEquals(secret, expectedSecret)
        val codeMatches = !code.isNullOrBlank() &&
            !expectedCode.isNullOrBlank() &&
            constantTimeEquals(code, expectedCode)
        return secretMatches || codeMatches
    }

    private fun constantTimeEquals(candidate: String, expected: String): Boolean =
        MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
}
