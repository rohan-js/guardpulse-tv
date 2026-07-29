package com.guardpulse.parentcontrol.tv.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureValueStore(
    context: Context,
    preferencesName: String,
    private val keyAlias: String
) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun get(key: String): String? {
        val encoded = preferences.getString(encryptedKey(key), null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > IV_BYTES)
            val iv = payload.copyOfRange(0, IV_BYTES)
            val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    fun put(key: String, value: String?) {
        if (value == null) {
            preferences.edit().remove(encryptedKey(key)).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        preferences.edit()
            .putString(encryptedKey(key), Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    fun migratePlaintext(key: String): String? {
        get(key)?.let { return it }
        val plaintext = preferences.getString(key, null) ?: return null
        put(key, plaintext)
        preferences.edit().remove(key).apply()
        return plaintext
    }

    private fun encryptedKey(key: String) = "encrypted:$key"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
