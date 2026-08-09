package com.airat.routervpncontrol

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed password protection. Equivalent of the Windows
 * DPAPI usage in the original desktop app: the key never leaves the device.
 */
object CryptoBox {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "RouterVpnControlPasswordKey"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun protect(plain: String): String {
        if (plain.isEmpty()) {
            return ""
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    fun unprotect(protected: String): String {
        if (protected.isBlank()) {
            return ""
        }
        return try {
            val combined = Base64.decode(protected, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(combined, GCM_IV_LENGTH, combined.size - GCM_IV_LENGTH), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
