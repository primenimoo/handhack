package com.primenimoo.handhack.engine.handshake

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The pseudorandom function of IEEE 802.11i, used to stretch the pairwise master key into
 * the pairwise transient key.
 *
 * `PRF-n(key, label, seed) = HMAC-n(A(1) || seed) || HMAC-n(A(2) || seed) || …` where
 * `A(0) = label` and `A(i) = HMAC-n(A(i-1))`, `n` being the digest size.
 */
object Prf {

    /** True for the HMAC-MD5 based WPA/TKIP suite, false for HMAC-SHA1. */
    fun isLegacyTkip(keyDescriptorType: Int): Boolean = keyDescriptorType == KEY_DESCRIPTOR_TKIP

    const val KEY_DESCRIPTOR_TKIP = 1
    const val KEY_DESCRIPTOR_CCMP = 2

    /** Digest used for a key descriptor type, per IEEE 802.11i. */
    fun algorithm(keyDescriptorType: Int): String =
        if (isLegacyTkip(keyDescriptorType)) "HmacMD5" else "HmacSHA1"

    /** Stretches [pmk] into [length] bytes of `label`-seeded output. */
    fun derive(
        pmk: ByteArray,
        label: ByteArray,
        seed: ByteArray,
        length: Int,
        algorithm: String,
    ): ByteArray {
        require(length > 0) { "length must be positive" }
        val out = ByteArray(length)
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(pmk, algorithm))

        // `block` walks A(0), A(1), A(2)… so each round can emit HMAC(A(i) || seed).
        var block = label
        var offset = 0
        while (offset < length) {
            mac.reset()
            mac.update(block)
            block = mac.doFinal()

            mac.reset()
            mac.update(block)
            mac.update(seed)
            val chunk = mac.doFinal()
            val take = minOf(chunk.size, length - offset)
            System.arraycopy(chunk, 0, out, offset, take)
            offset += take
        }
        return out
    }
}

/**
 * The pairwise transient key of a four-way handshake.
 *
 * `PTK = PRF(PMK, "Pairwise key expansion", Min(AA, SPA) || Max(AA, SPA) || Min(ANonce, SNonce)
 * || Max(ANonce, SNonce))`
 *
 * The first sixteen bytes are the key confirmation key, and that is what the MIC of message 2
 * and message 3 is computed with. Deriving the PTK is what separates a real capture from a
 * guess: the MIC cannot be reproduced from the PMK directly.
 */
object Ptk {

    /** "Pairwise key expansion" with the zero octet the standard appends to the label. */
    private val LABEL: ByteArray = "Pairwise key expansion".toByteArray(Charsets.US_ASCII) + 0

    /** 384 bits: KCK (16) || KEK (16) || TKIP RC4 keys (16) || GTK (16). */
    const val LENGTH_BYTES = 48
    const val KEY_CONFIRMATION_LENGTH = 16

    fun derive(
        pmk: ByteArray,
        authenticator: ByteArray,
        supplicant: ByteArray,
        authenticatorNonce: ByteArray,
        supplicantNonce: ByteArray,
        keyDescriptorType: Int,
    ): ByteArray {
        val addresses = ordered(authenticator, supplicant)
        val nonces = ordered(authenticatorNonce, supplicantNonce)
        val seed = addresses.first + addresses.second + nonces.first + nonces.second
        return Prf.derive(pmk, LABEL, seed, LENGTH_BYTES, Prf.algorithm(keyDescriptorType))
    }

    /** Just the KCK, which is all the MIC check needs. */
    fun deriveKeyConfirmationKey(
        pmk: ByteArray,
        authenticator: ByteArray,
        supplicant: ByteArray,
        authenticatorNonce: ByteArray,
        supplicantNonce: ByteArray,
        keyDescriptorType: Int,
    ): ByteArray = derive(
        pmk,
        authenticator,
        supplicant,
        authenticatorNonce,
        supplicantNonce,
        keyDescriptorType,
    ).copyOfRange(0, KEY_CONFIRMATION_LENGTH)

    private fun ordered(a: ByteArray, b: ByteArray): Pair<ByteArray, ByteArray> =
        if (compareUnsigned(a, b) <= 0) a to b else b to a

    /** Lexicographic comparison of two byte strings, unsigned, shorter-is-smaller on a prefix. */
    fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val shared = minOf(a.size, b.size)
        for (i in 0 until shared) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}

/**
 * How a candidate passphrase is checked against one captured network.
 *
 * Three shapes exist because three shapes exist in the wild:
 *
 * - [Pmk] keeps the full 32-byte pairwise master key. Only the compact `SSID:WPA*01<hex>`
 *   export of the app itself carries this.
 * - [PmkId] keeps the 16-byte PMKID together with the two addresses it was derived from,
 *   which is what hashcat `WPA*01` records and `.hc22000` PMKID lines contain.
 * - [Mic] keeps one EAPOL-Key message and checks its message integrity code, which is what
 *   hashcat `WPA*02` records, `.hccapx`, `.hccap` and raw captures provide.
 */
sealed interface PmkVerifier {

    /** Checks one candidate key material against this verifier. */
    fun accepts(pmk: ByteArray): Boolean

    /** Short hex the result screen can show. */
    val hex: String

    /** The full pairwise master key stored in the record. */
    class Pmk(val bytes: ByteArray) : PmkVerifier {
        override val hex: String get() = Hex.encode(bytes)
        override fun accepts(pmk: ByteArray): Boolean = Pbkdf2.constantTimeEquals(pmk, bytes)
        override fun equals(other: Any?): Boolean = other is Pmk && Hex.encode(other.bytes) == hex
        override fun hashCode(): Int = hex.hashCode()
    }

    /**
     * The truncated PMKID of IEEE 802.11i:
     * `PMKID = HMAC-SHA1-96(PMK, "PMK Name" || AA || SPA)`.
     *
     * A candidate therefore only has to reproduce those sixteen bytes, not the key itself.
     */
    class PmkId(
        val pmkid: ByteArray,
        val authenticator: ByteArray,
        val supplicant: ByteArray,
    ) : PmkVerifier {
        override val hex: String get() = Hex.encode(pmkid)
        override fun accepts(pmk: ByteArray): Boolean =
            Pbkdf2.constantTimeEquals(derivePmkid(pmk, authenticator, supplicant), pmkid)
        override fun equals(other: Any?): Boolean = other is PmkId && other.hex == hex
        override fun hashCode(): Int = hex.hashCode()
    }

    /**
     * The message integrity code of a captured EAPOL-Key message.
     *
     * `MIC = HMAC-n(KCK, EAPOL-Key with the MIC field zeroed)`, where the key confirmation key is
     * the head of the PTK and `n` follows the key descriptor type: HMAC-MD5 for WPA/TKIP and
     * HMAC-SHA1 for WPA2. A raw capture (`.pcap`, `.cap`, `.pcapng`) never stores a PMKID,
     * because that value is itself derived from the master key, so this is the only check such
     * a file can support.
     */
    class Mic(
        val message: ByteArray,
        val authenticator: ByteArray,
        val supplicant: ByteArray,
        val authenticatorNonce: ByteArray,
        val supplicantNonce: ByteArray,
        val keyDescriptorType: Int,
        val expected: ByteArray = message.copyOfRange(MIC_OFFSET, MIC_OFFSET + MIC_LENGTH),
    ) : PmkVerifier {
        override val hex: String get() = Hex.encode(expected)
        override fun accepts(pmk: ByteArray): Boolean {
            val kck = Ptk.deriveKeyConfirmationKey(
                pmk,
                authenticator,
                supplicant,
                authenticatorNonce,
                supplicantNonce,
                keyDescriptorType,
            )
            val algorithm = Prf.algorithm(keyDescriptorType)
            val mac = Mac.getInstance(algorithm)
            mac.init(SecretKeySpec(kck, algorithm))
            val zeroed = message.copyOf()
            zeroed.fill(0, MIC_OFFSET, MIC_OFFSET + MIC_LENGTH)
            val computed = mac.doFinal(zeroed).copyOfRange(0, MIC_LENGTH)
            return Pbkdf2.constantTimeEquals(computed, expected)
        }

        override fun equals(other: Any?): Boolean = other is Mic && other.hex == hex
        override fun hashCode(): Int = hex.hashCode()
    }

    companion object {
        const val PMKID_LENGTH = 16
        const val MIC_LENGTH = 16

        /**
         * Offset of the MIC inside an EAPOL-Key message, counted from the first byte of the
         * four byte EAPOL header: 4 header + 1 descriptor + 2 key information + 2 key length
         * + 8 replay counter + 32 nonce + 16 key IV + 16 key RSC + 8 reserved = 81.
         */
        const val MIC_OFFSET = 81

        private const val HASH_ALGORITHM = "HmacSHA1"
        private val CONTEXT = "PMK Name".toByteArray(Charsets.US_ASCII)

        /** Derives the 16-byte PMKID for an already expanded [pmk]. */
        fun derivePmkid(pmk: ByteArray, authenticator: ByteArray, supplicant: ByteArray): ByteArray {
            val mac = Mac.getInstance(HASH_ALGORITHM)
            mac.init(SecretKeySpec(pmk, HASH_ALGORITHM))
            mac.update(CONTEXT)
            mac.update(authenticator)
            mac.update(supplicant)
            return mac.doFinal().copyOf(PMKID_LENGTH)
        }
    }
}