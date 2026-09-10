package com.guardpulse.parentcontrol.tv.pairing

import android.content.Context
import android.util.Base64
import com.guardpulse.parentcontrol.shared.DeviceIdentity
import com.guardpulse.parentcontrol.shared.PolicyConstants
import com.guardpulse.parentcontrol.tv.security.SecureValueStore
import com.guardpulse.parentcontrol.tv.system.SystemTimeGuard
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

    /**
     * Returns the current pairing credentials, or null when a new generation
     * could not be persisted. Null is fail-closed: a QR whose secret/code were
     * never stored can never validate, and showing it would feed the brute-force
     * rotation counter with false failures (see isValid).
     */
    fun current(): PairingState? {
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

        // TTL boundary (or first mint): park the outgoing generation as the
        // one-deep rotation-boundary grace BEFORE minting replacements. A QR
        // scanned seconds before the boundary must still pair after it (see
        // PairingGracePolicy for the 2x-TTL window math). Forced voids — unpair,
        // brute-force lockout, expired-rejection — go through rotateCredentials(),
        // which clears the grace instead: those must void ALL outstanding
        // credentials.
        if (!existingSecret.isNullOrBlank() && !existingCode.isNullOrBlank() && existingCreatedAt > 0L) {
            secureStore.put("prevSecret", existingSecret)
            secureStore.put("prevCode", existingCode)
            prefs.edit().putLong("prevCreatedAt", existingCreatedAt).apply()
        }

        val secretBytes = ByteArray(32)
        random.nextBytes(secretBytes)
        val secret = Base64.encodeToString(
            secretBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val code = (100000 + random.nextInt(900000)).toString()
        val secretSaved = secureStore.put("secret", secret)
        val codeSaved = secretSaved && secureStore.put("code", code)
        if (!secretSaved || !codeSaved) {
            // Roll back a half-written generation so the next call re-mints
            // instead of returning the parked grace as current credentials.
            if (secretSaved) secureStore.put("secret", null)
            return null
        }
        prefs.edit().putLong("createdAt", now).apply()
        return PairingState(DeviceIdentity.getOrCreate(context), code, secret, now)
    }

    fun isValid(secret: String?, code: String?, createdAt: Long): Boolean {
        val now = SystemTimeGuard.now()
        val valid = PairingGracePolicy.isValid(
            secret = secret,
            code = code,
            requestCreatedAt = createdAt,
            now = now,
            curSecret = secureStore.migratePlaintext("secret"),
            curCode = secureStore.migratePlaintext("code"),
            prevSecret = secureStore.get("prevSecret"),
            prevCode = secureStore.get("prevCode"),
            prevCreatedAt = prefs.getLong("prevCreatedAt", 0L)
        )
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
        // A forced void (unpair / brute-force lockout / expired rejection) must
        // invalidate EVERY outstanding credential, including the grace copy.
        secureStore.put("secret", null)
        secureStore.put("code", null)
        secureStore.put("prevSecret", null)
        secureStore.put("prevCode", null)
        prefs.edit()
            .remove("secret")
            .remove("code")
            .remove("createdAt")
            .remove("prevCreatedAt")
            .apply()
    }

    private companion object {
        const val MAX_INVALID_PAIR_ATTEMPTS = 20
    }
}
