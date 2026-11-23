package com.stellar.autohub.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the GitHub token encrypted with a key that never leaves the Android Keystore. */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)
    private val alias = "autohub_token_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun saveToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(token.toByteArray())
        prefs.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
    }

    /** True if a token is saved, even if it can't be read right this moment. */
    fun hasToken(): Boolean = prefs.contains("ct") && prefs.contains("iv")

    /**
     * Reads the saved token. The Android Keystore can fail briefly (e.g. while the system is busy),
     * so it retries before giving up, and it never deletes the saved token on failure.
     */
    fun token(): String? {
        if (!hasToken()) return null
        repeat(4) { attempt ->
            try {
                val iv = prefs.getString("iv", null) ?: return null
                val ct = prefs.getString("ct", null) ?: return null
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
                return String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)))
            } catch (e: Exception) {
                if (attempt < 3) Thread.sleep(150L * (attempt + 1))
            }
        }
        return null
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
