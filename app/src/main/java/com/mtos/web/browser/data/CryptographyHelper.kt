package com.mtos.web.browser.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptographyHelper {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "BrowserSecurePasswordKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    init {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val params = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                 .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                 .build()
                keyGenerator.init(params)
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    fun encrypt(plainText: String): String {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            
            val ivString = Base64.encodeToString(iv, Base64.NO_WRAP)
            val encryptedString = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
            "$ivString:$encryptedString"
        } catch (e: Exception) {
            e.printStackTrace()
            val simpleBytes = plainText.toByteArray(Charsets.UTF_8)
            val encoded = Base64.encodeToString(simpleBytes, Base64.NO_WRAP)
            "FALLBACK:$encoded"
        }
    }

    fun decrypt(encryptedPayload: String): String {
        return try {
            if (encryptedPayload.startsWith("FALLBACK:")) {
                val payload = encryptedPayload.substringAfter("FALLBACK:")
                return String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
            }
            val parts = encryptedPayload.split(":")
            if (parts.size != 2) return ""
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encryptedBytes = Base64.decode(parts[1], Base64.NO_WRAP)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
}
