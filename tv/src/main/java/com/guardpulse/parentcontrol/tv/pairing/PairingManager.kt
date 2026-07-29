package com.guardpulse.parentcontrol.tv.pairing

import android.content.Context
import android.util.Base64
import com.guardpulse.parentcontrol.shared.DeviceIdentity
import com.guardpulse.parentcontrol.shared.PolicyConstants
import com.guardpulse.parentcontrol.tv.security.SecureValueStore
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

    fun current(): PairingState {
        val now = System.currentTimeMillis()
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
        val now = System.currentTimeMillis()
        if (createdAt <= 0 || now - createdAt > PolicyConstants.PAIRING_TTL_MS) return false
        return (!secret.isNullOrBlank() && secret == state.secret) ||
            (!code.isNullOrBlank() && code == state.code)
    }

    fun markPaired(parentUid: String) {
        if (parentUid.isBlank()) return
        secureStore.put("pairedParentUid", parentUid)
        prefs.edit().putLong("pairedAt", System.currentTimeMillis()).apply()
    }

    fun pairedParentUid(): String? = secureStore.migratePlaintext("pairedParentUid")

    fun pairedAt(): Long = prefs.getLong("pairedAt", 0L)

    fun clearPairedParent() {
        secureStore.put("pairedParentUid", null)
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
}
