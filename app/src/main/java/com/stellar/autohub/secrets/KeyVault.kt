package com.stellar.autohub.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** An app signing key kept inside AutoHub (encrypted). */
@Serializable
data class SigningKey(
    val id: String,
    val label: String,
    val alias: String,
    val password: String,
    /** Empty = same as [password]. */
    val keyPassword: String = "",
    /** The .jks/.p12 file, base64 — exactly what goes into the KEYSTORE_BASE64 secret. */
    val keystoreBase64: String,
    val created: Long,
    val fingerprint: String = "",
    val verified: Boolean = true,
) {
    val effectiveKeyPassword: String get() = keyPassword.ifEmpty { password }
}

class KeyImportException(message: String) : Exception(message)

/**
 * Stores signing keys encrypted with an Android-Keystore AES key.
 * Keystores made here are PKCS12 (what Android Studio/Gradle use by default),
 * with the key password equal to the store password.
 */
class KeyVault(context: Context) {
    private val file = File(context.filesDir, "keys.bin")
    private val alias = "autohub_vault_key"
    private val json = Json { ignoreUnknownKeys = true }
    private val _keys = MutableStateFlow(load())
    val keys: StateFlow<List<SigningKey>> = _keys

    private fun aes(): SecretKey {
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

    private fun load(): List<SigningKey> = try {
        if (!file.exists()) emptyList() else {
            val all = file.readBytes()
            val iv = all.copyOfRange(0, 12)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, aes(), GCMParameterSpec(128, iv))
            json.decodeFromString(ListSerializer(SigningKey.serializer()), String(c.doFinal(all, 12, all.size - 12)))
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    private fun save(list: List<SigningKey>) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, aes())
        val ct = c.doFinal(json.encodeToString(ListSerializer(SigningKey.serializer()), list).toByteArray())
        file.writeBytes(c.iv + ct)
        _keys.value = list
    }

    fun get(id: String) = _keys.value.find { it.id == id }

    fun remove(id: String) = save(_keys.value.filterNot { it.id == id })

    /** Adds keys from a backup, skipping ones already here. Returns how many were added. */
    fun restore(list: List<SigningKey>): Int {
        val have = _keys.value
        val fresh = list.filter { k -> have.none { it.id == k.id || it.keystoreBase64 == k.keystoreBase64 } }
        if (fresh.isNotEmpty()) save(have + fresh)
        return fresh.size
    }

    fun keystoreBytes(k: SigningKey): ByteArray = Base64.decode(k.keystoreBase64, Base64.NO_WRAP)

    /** Creates a new RSA-2048 key with a 30-year self-signed certificate. */
    fun create(label: String, alias: String, password: String, name: String, org: String): SigningKey {
        require(password.length >= 6) { "Password must be at least 6 characters" }
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()

        fun esc(s: String) = s.replace(",", "\\,").replace("=", "\\=").replace("+", "\\+")
        val dn = buildString {
            append("CN=").append(esc(name.ifBlank { "Stellar" }))
            if (org.isNotBlank()) append(", O=").append(esc(org))
        }
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 3600 * 1000)
        val notAfter = Date(now + 30L * 365 * 24 * 3600 * 1000)
        val subject = X500Name(dn)
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE), notBefore, notAfter, subject, kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert: X509Certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(alias, kp.private, password.toCharArray(), arrayOf(cert))
        val bos = ByteArrayOutputStream()
        ks.store(bos, password.toCharArray())

        val key = SigningKey(
            id = UUID.randomUUID().toString(),
            label = label.ifBlank { "Signing key" },
            alias = alias,
            password = password,
            keystoreBase64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP),
            created = now,
            fingerprint = fingerprint(cert),
        )
        save(_keys.value + key)
        return key
    }

    /**
     * Imports an existing .jks/.p12/.keystore so it can be reused (same key = app updates install cleanly).
     * Checks the passwords actually open it, unless [force] is set (for old JKS files Android can't read).
     */
    fun import(
        label: String, bytes: ByteArray, alias: String, storePassword: String, keyPassword: String, force: Boolean = false,
    ): SigningKey {
        var useAlias = alias.trim()
        var fp = ""
        if (!force) {
            var opened = false
            var lastError: Exception? = null
            for (type in listOf("PKCS12", "JKS", "BKS")) {
                val ks = try {
                    KeyStore.getInstance(type).apply { load(ByteArrayInputStream(bytes), storePassword.toCharArray()) }
                } catch (e: Exception) { lastError = e; continue }
                val names = ks.aliases().toList()
                if (useAlias.isEmpty()) useAlias = names.firstOrNull { ks.isKeyEntry(it) } ?: ""
                if (!ks.isKeyEntry(useAlias))
                    throw IllegalArgumentException("No key named \"$useAlias\" in that file. Found: ${names.joinToString()}")
                try {
                    ks.getKey(useAlias, keyPassword.ifEmpty { storePassword }.toCharArray())
                } catch (e: Exception) {
                    throw IllegalArgumentException("The key password is wrong.")
                }
                (ks.getCertificate(useAlias) as? X509Certificate)?.let { fp = fingerprint(it) }
                opened = true
                break
            }
            if (!opened) throw KeyImportException(
                "Couldn't open that key file: wrong store password, or a format this phone can't read " +
                    "(${lastError?.message ?: "unknown"}). If you're sure the details are right, use Save anyway."
            )
        }
        if (useAlias.isEmpty()) throw IllegalArgumentException("Enter the key alias.")
        val key = SigningKey(
            id = UUID.randomUUID().toString(),
            label = label.ifBlank { "Imported key" },
            alias = useAlias,
            password = storePassword,
            keyPassword = keyPassword.takeIf { it.isNotEmpty() && it != storePassword } ?: "",
            keystoreBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            created = System.currentTimeMillis(),
            fingerprint = fp,
            verified = !force,
        )
        save(_keys.value + key)
        return key
    }

    private fun fingerprint(cert: X509Certificate): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(cert.encoded).joinToString(":") { "%02X".format(it) }.take(47)
    }
}
