package com.stellar.autohub.secrets

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom

/**
 * libsodium crypto_box_seal, which GitHub requires for Actions secrets.
 * Output = ephemeralPk(32) || poly1305 tag(16) || ciphertext.
 */
object SealedBox {

    fun seal(message: ByteArray, recipientPk: ByteArray): ByteArray {
        require(recipientPk.size == 32) { "Bad public key" }
        val rnd = SecureRandom()
        val esk = ByteArray(32)
        X25519.generatePrivateKey(rnd, esk)
        val epk = ByteArray(32)
        X25519.generatePublicKey(esk, 0, epk, 0)

        // nonce = blake2b-192(epk || pk)
        val nonce = ByteArray(24)
        Blake2bDigest(192).apply {
            update(epk, 0, 32)
            update(recipientPk, 0, 32)
            doFinal(nonce, 0)
        }

        // crypto_box_beforenm: k = HSalsa20(X25519(esk, pk), 0)
        val shared = ByteArray(32)
        X25519.calculateAgreement(esk, 0, recipientPk, 0, shared, 0)
        val k = hsalsa20(shared, ByteArray(16))

        // crypto_secretbox (XSalsa20-Poly1305)
        val xs = XSalsa20Engine()
        xs.init(true, ParametersWithIV(KeyParameter(k), nonce))
        val polyKey = ByteArray(32)
        xs.processBytes(ByteArray(32), 0, 32, polyKey, 0)
        val cipher = ByteArray(message.size)
        xs.processBytes(message, 0, message.size, cipher, 0)

        val mac = Poly1305()
        mac.init(KeyParameter(polyKey))
        mac.update(cipher, 0, cipher.size)
        val tag = ByteArray(16)
        mac.doFinal(tag, 0)

        return epk + tag + cipher
    }

    private fun rotl(v: Int, c: Int) = (v shl c) or (v ushr (32 - c))

    private fun le(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    fun hsalsa20(key: ByteArray, input: ByteArray): ByteArray {
        val x = IntArray(16)
        x[0] = 0x61707865; x[5] = 0x3320646e; x[10] = 0x79622d32; x[15] = 0x6b206574
        for (i in 0 until 4) {
            x[1 + i] = le(key, i * 4)
            x[11 + i] = le(key, 16 + i * 4)
            x[6 + i] = le(input, i * 4)
        }
        repeat(10) {
            x[4] = x[4] xor rotl(x[0] + x[12], 7); x[8] = x[8] xor rotl(x[4] + x[0], 9)
            x[12] = x[12] xor rotl(x[8] + x[4], 13); x[0] = x[0] xor rotl(x[12] + x[8], 18)
            x[9] = x[9] xor rotl(x[5] + x[1], 7); x[13] = x[13] xor rotl(x[9] + x[5], 9)
            x[1] = x[1] xor rotl(x[13] + x[9], 13); x[5] = x[5] xor rotl(x[1] + x[13], 18)
            x[14] = x[14] xor rotl(x[10] + x[6], 7); x[2] = x[2] xor rotl(x[14] + x[10], 9)
            x[6] = x[6] xor rotl(x[2] + x[14], 13); x[10] = x[10] xor rotl(x[6] + x[2], 18)
            x[3] = x[3] xor rotl(x[15] + x[11], 7); x[7] = x[7] xor rotl(x[3] + x[15], 9)
            x[11] = x[11] xor rotl(x[7] + x[3], 13); x[15] = x[15] xor rotl(x[11] + x[7], 18)

            x[1] = x[1] xor rotl(x[0] + x[3], 7); x[2] = x[2] xor rotl(x[1] + x[0], 9)
            x[3] = x[3] xor rotl(x[2] + x[1], 13); x[0] = x[0] xor rotl(x[3] + x[2], 18)
            x[6] = x[6] xor rotl(x[5] + x[4], 7); x[7] = x[7] xor rotl(x[6] + x[5], 9)
            x[4] = x[4] xor rotl(x[7] + x[6], 13); x[5] = x[5] xor rotl(x[4] + x[7], 18)
            x[11] = x[11] xor rotl(x[10] + x[9], 7); x[8] = x[8] xor rotl(x[11] + x[10], 9)
            x[9] = x[9] xor rotl(x[8] + x[11], 13); x[10] = x[10] xor rotl(x[9] + x[8], 18)
            x[12] = x[12] xor rotl(x[15] + x[14], 7); x[13] = x[13] xor rotl(x[12] + x[15], 9)
            x[14] = x[14] xor rotl(x[13] + x[12], 13); x[15] = x[15] xor rotl(x[14] + x[13], 18)
        }
        val outIdx = intArrayOf(0, 5, 10, 15, 6, 7, 8, 9)
        val out = ByteArray(32)
        for ((j, i) in outIdx.withIndex()) {
            val v = x[i]
            out[j * 4] = v.toByte(); out[j * 4 + 1] = (v ushr 8).toByte()
            out[j * 4 + 2] = (v ushr 16).toByte(); out[j * 4 + 3] = (v ushr 24).toByte()
        }
        return out
    }
}
