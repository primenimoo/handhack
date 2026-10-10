package com.primenimoo.handhack.engine.handshake

/**
 * What kind of secret the capture actually exposes, and therefore which check can be run.
 *
 * The three are ordered by how much of the key material the file gives away, and each one is
 * reported to the user so the strength of a verification is never overstated.
 */
enum class CaptureEvidence(val label: String) {
    /** The full 32-byte pairwise master key is stored. */
    PMK("PMK"),

    /** Only `PMKID = HMAC-SHA1-96(PMK, "PMK Name" || AA || SPA)` is stored. */
    PMKID("PMKID"),

    /** One EAPOL-Key message with its MIC, checked through the PTK. */
    EAPOL_MIC("EAPOL MIC"),
}

/**
 * One captured network waiting for its password.
 *
 * [verifier] decides how a candidate is checked, so the same engine drives the app's compact
 * `SSID:WPA*01…` export, hashcat `.hc22000` records, `.hccapx`/`.hccap` containers and raw
 * packet captures.
 */
class HandshakeTarget(
    /** The SSID as bytes: a network name is arbitrary octets, not necessarily text. */
    val ssidBytes: ByteArray,
    val verifier: PmkVerifier,
    val rawLine: String,
    val evidence: CaptureEvidence,
    /** The authenticator address when the capture names one. */
    val bssid: ByteArray? = null,
    /** The supplicant address when the capture names one. */
    val station: ByteArray? = null,
    /** Key descriptor type of the captured EAPOL-Key message, when there is one. */
    val keyDescriptorType: Int? = null,
) {

    /** Printable network name, for display only — verification always uses [ssidBytes]. */
    val ssid: String
        get() = if (ssidBytes.isEmpty()) "" else String(ssidBytes, Charsets.UTF_8)

    /** SSID as the user would write it in a file name, never blank. */
    val displaySsid: String
        get() = ssid.ifBlank { "(без имени)" }

    /** Hex of the stored key material, shown on the result screen. */
    val verifierHex: String get() = verifier.hex

    val bssidHex: String? get() = bssid?.let { Hex.encode(it) }
    val stationHex: String? get() = station?.let { Hex.encode(it) }

    /** WPA passphrases are 8..63 characters. */
    fun accepts(candidate: String): Boolean = candidate.length in WPA_MIN_PASSPHRASE..WPA_MAX_PASSPHRASE

    /** Derives the key material once and hands it to the verifier. */
    fun verify(candidate: String, iterations: Int = Pbkdf2.WPA_ITERATIONS): Boolean {
        if (!accepts(candidate)) return false
        return verifier.accepts(
            Pbkdf2.derive(candidate.toByteArray(Charsets.UTF_8), ssidBytes, iterations),
        )
    }

    /** True when this record can never be checked because its network name is unknown. */
    val isVerifiable: Boolean get() = ssidBytes.isNotEmpty()

    override fun equals(other: Any?): Boolean =
        other is HandshakeTarget &&
            other.ssidBytes.contentEquals(ssidBytes) &&
            other.verifierHex == verifierHex

    override fun hashCode(): Int = 31 * ssidBytes.contentHashCode() + verifierHex.hashCode()

    override fun toString(): String = "HandshakeTarget(ssid=$displaySsid, evidence=$evidence)"

    companion object {
        const val WPA_MIN_PASSPHRASE = 8
        const val WPA_MAX_PASSPHRASE = 63
    }
}