package com.guardpulse.parentcontrol.tv.pairing

import android.content.Context
import android.util.Base64
import com.guardpulse.parentcontrol.shared.DeviceIdentity
import com.guardpulse.parentcontrol.shared.PolicyConstants
import com.guardpulse.parentcontrol.tv.security.SecureValueStore
import com.guardpulse.parentcontrol.tv.system.SystemTimeGuard
import java.security.MessageDigest
import java.security.SecureRandom

data class PairingState(
    val deviceId: String,
    val code: String,
    val secret: String,
    val createdAt: Long
) {
    val qrPayload: String
        get() = "guardpulse://pair?deviceId=$deviceId&secret=$secret"
}

class PairingManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val secureStore = SecureValueStore(
        context,
        "pairing",
        "guardpulse.pairing.secrets"
    )
    private val random = SecureRandom()

    // pairedParentUid() sits on the heartbeat path; a Keystore AES-GCM decrypt
    // every 30 s is pure waste, so the (process-lifetime) value is cached.
    @Volatile
    private var cachedParentUid: String? = null

    @Volatile
    private var parentUidLoaded = false

    fun current(): PairingState {
        val now = SystemTimeGuard.now()
        val existingSecret = secureStore.migratePlaintext("secret")
        val existingCode = secureStore.migratePlaintext("code")
        val existingCreatedAt = prefs.getLong("createdAt", 0L)
        if (
            !existingSecret.isNullOrBlank() &&
            !existingCode.isNullOrBlank() &&
            now - existingCreatedAt < PolicyConstants.PAIRING_TTL_MS
        ) {
            return PairingState(DeviceIdentity.getOrCreate(context), existingCode, existingSecret, existingCreatedAt)
        }

        val secretBytes = ByteArray(32)
        random.nextBytes(secretBytes)
        val secret = Base64.encodeToString(
            secretBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val code = (100000 + random.nextInt(900000)).toString()
        secureStore.put("secret", secret)
        secureStore.put("code", code)
        prefs.edit().putLong("createdAt", now).apply()
        return PairingState(DeviceIdentity.getOrCreate(context), code, secret, now)
    }

    fun isValid(secret: String?, code: String?, createdAt: Long): Boolean {
        val state = current()
        val now = SystemTimeGuard.now()
        if (createdAt <= 0 || now - createdAt > PolicyConstants.PAIRING_TTL_MS) return false
        val secretMatches = !secret.isNullOrBlank() && constantTimeEquals(secret, state.secret)
        val codeMatches = !code.isNullOrBlank() && constantTimeEquals(code, state.code)
        val valid = secretMatches || codeMatches
        if (!valid) {
            // A 6-digit code is brute-forceable in principle; after enough bad
            // attempts rotate the credentials so every outstanding code/secret
            // is void and the parent must reopen the pairing screen.
            val attempts = prefs.getInt("invalidPairAttempts", 0) + 1
            if (attempts >= MAX_INVALID_PAIR_ATTEMPTS) {
                rotateCredentials()
                prefs.edit().putInt("invalidPairAttempts", 0).apply()
            } else {
                prefs.edit().putInt("invalidPairAttempts", attempts).apply()
            }
        } else {
            prefs.edit().putInt("invalidPairAttempts", 0).apply()
        }
        return valid
    }

    fun markPaired(parentUid: String) {
        if (parentUid.isBlank()) return
        if (secureStore.put("pairedParentUid", parentUid)) {
            cachedParentUid = parentUid
            parentUidLoaded = true
        }
        prefs.edit().putLong("pairedAt", System.currentTimeMillis()).apply()
    }

    fun pairedParentUid(): String? {
        if (!parentUidLoaded) {
            cachedParentUid = secureStore.migratePlaintext("pairedParentUid")
            parentUidLoaded = true
        }
        return cachedParentUid
    }

    fun pairedAt(): Long = prefs.getLong("pairedAt", 0L)

    fun clearPairedParent() {
        secureStore.put("pairedParentUid", null)
        cachedParentUid = null
        parentUidLoaded = true
        prefs.edit()
            .remove("pairedParentUid")
            .remove("pairedAt")
            .apply()
        rotateCredentials()
    }

    fun rotateCredentials() {
        secureStore.put("secret", null)
        secureStore.put("code", null)
        prefs.edit()
            .remove("secret")
            .remove("code")
            .remove("createdAt")
            .apply()
    }

    private fun constantTimeEquals(candidate: String, expected: String?): Boolean {
        if (expected == null) return false
        return MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    private companion object {
        const val MAX_INVALID_PAIR_ATTEMPTS = 20
    }
}
