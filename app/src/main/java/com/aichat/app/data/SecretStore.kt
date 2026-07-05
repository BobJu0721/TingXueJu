package com.aichat.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {
    private val preferences = context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun put(provider: Provider, apiKey: String) {
        put(provider.name, apiKey)
    }

    fun get(provider: Provider): String = get(provider.name)

    fun putCustomEndpointPreset(id: String, apiKey: String) {
        put("custom_endpoint_$id", apiKey)
    }

    fun getCustomEndpointPreset(id: String): String = get("custom_endpoint_$id")

    fun removeCustomEndpointPreset(id: String) {
        preferences.edit().remove("custom_endpoint_$id").apply()
    }

    private fun put(key: String, apiKey: String) {
        if (apiKey.isBlank()) return
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(key, "${encode(cipher.iv)}:${encode(encrypted)}")
            .apply()
    }

    private fun get(key: String): String {
        val stored = preferences.getString(key, null) ?: return ""
        return runCatching {
            val (iv, encrypted) = stored.split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, decode(iv)))
            cipher.doFinal(decode(encrypted)).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "ai_chat_api_key_encryption"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
