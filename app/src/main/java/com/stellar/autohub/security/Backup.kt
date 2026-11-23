package com.stellar.autohub.security

import com.stellar.autohub.data.StoreData
import com.stellar.autohub.secrets.SigningKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class BackupData(
    val version: Int = 1,
    val created: Long,
    val app: String,
    val store: StoreData,
    val keys: List<SigningKey>,
    val settings: Map<String, String>,
)

/**
 * Password-protected backup file: "SAHB1" + salt(16) + iv(12) + AES-256-GCM(json).
 * Key = PBKDF2-HMAC-SHA256(password, salt, 210k iterations). The GitHub token is never included.
 */
object Backup {
    private val MAGIC = "SAHB1".toByteArray()
    private const val ITER = 210_000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = f.generateSecret(PBEKeySpec(password, salt, ITER, 256)).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(data: BackupData, password: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password.toCharArray(), salt), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC)
        val ct = c.doFinal(json.encodeToString(BackupData.serializer(), data).toByteArray())
        return MAGIC + salt + iv + ct
    }

    fun decrypt(file: ByteArray, password: String): BackupData {
        require(file.size > MAGIC.size + 28 && file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "That isn't a Stellar AutoHub backup file."
        }
        var o = MAGIC.size
        val salt = file.copyOfRange(o, o + 16); o += 16
        val iv = file.copyOfRange(o, o + 12); o += 12
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(password.toCharArray(), salt), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC)
        val plain = try { c.doFinal(file, o, file.size - o) } catch (e: Exception) {
            throw IllegalArgumentException("Wrong password, or the file is damaged.")
        }
        return json.decodeFromString(BackupData.serializer(), String(plain))
    }
}
