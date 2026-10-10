package com.primenimoo.handhack.engine.handshake

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * PBKDF2-HMAC-SHA1 exactly as specified by IEEE 802.11i for deriving the WPA/WPA2
 * pairwise master key from a passphrase.
 *
 * The platform `PBKDF2WithHmacSHA1` key factory is deliberately avoided: on Android it
 * folds `char[]` passwords down to their low byte, which silently breaks any passphrase
 * containing non-ASCII characters. Here the passphrase is encoded as UTF-8 bytes first,
 * which is what the standard requires.
 */
object Pbkdf2 {

    const val WPA_ITERATIONS = 4096
    const val KEY_LENGTH_BYTES = 32
    private const val HASH_LENGTH = 20
    private const val ALGORITHM = "HmacSHA1"

    /** Derives [KEY_LENGTH_BYTES] of key material for [passphrase] and [ssid]. */
    fun derive(passphrase: String, ssid: String, iterations: Int = WPA_ITERATIONS): ByteArray =
        derive(passphrase.toByteArray(Charsets.UTF_8), ssid.toByteArray(Charsets.UTF_8), iterations)

    fun derive(password: ByteArray, salt: ByteArray, iterations: Int = WPA_ITERATIONS): ByteArray {
        require(iterations > 0) { "iterations must be positive" }
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(password, ALGORITHM))

        val block = ByteArray(salt.size + 4)
        System.arraycopy(salt, 0, block, 0, salt.size)

        val output = ByteArray(KEY_LENGTH_BYTES)
        var offset = 0
        var blockIndex = 1
        while (offset < output.size) {
            block[salt.size] = (blockIndex ushr 24).toByte()
            block[salt.size + 1] = (blockIndex ushr 16).toByte()
            block[salt.size + 2] = (blockIndex ushr 8).toByte()
            block[salt.size + 3] = blockIndex.toByte()

            var u = mac.doFinal(block)
            val accumulator = u.copyOf()
            for (iteration in 2..iterations) {
                u = mac.doFinal(u)
                for (i in accumulator.indices) {
                    accumulator[i] = (accumulator[i].toInt() xor u[i].toInt()).toByte()
                }
            }
            val take = minOf(HASH_LENGTH, output.size - offset)
            System.arraycopy(accumulator, 0, output, offset, take)
            offset += take
            blockIndex++
        }
        return output
    }

    /** True when [candidate] reproduces [expected] for [ssid]. */
    fun matches(candidate: String, ssid: String, expected: ByteArray, iterations: Int = WPA_ITERATIONS): Boolean {
        val derived = derive(candidate, ssid, iterations)
        return constantTimeEquals(derived, expected)
    }

    /** Comparison whose duration does not depend on where the first difference is. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }
}

/** Hex helpers shared by the handshake parser and the result screen. */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            sb.append(DIGITS[value ushr 4])
            sb.append(DIGITS[value and 0x0F])
        }
        return sb.toString()
    }

    /** Decodes [hex], returning null when it is not valid hex of even length. */
    fun decode(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}