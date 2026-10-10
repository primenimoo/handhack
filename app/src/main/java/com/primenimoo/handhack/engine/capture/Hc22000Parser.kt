package com.primenimoo.handhack.engine.capture

import com.primenimoo.handhack.engine.handshake.CaptureEvidence
import com.primenimoo.handhack.engine.handshake.HandshakeTarget
import com.primenimoo.handhack.engine.handshake.Hex
import com.primenimoo.handhack.engine.handshake.PmkVerifier
import com.primenimoo.handhack.engine.handshake.Prf
import java.io.File

/**
 * Reader for hashcat `.hc22000` records, the format hcxpcapngtool and HandHack share.
 *
 * A record is nine `*`-separated fields, exactly as `module_22000.c` tokenises it:
 * ```
 * WPA * TT * K * AA * SPA * ESSID * ANonce * EAPOL * message_pair
 * ```
 * where `TT` is the record type in hex, `K` is the PMKID for type `01` and the MIC for type
 * `02`, `ESSID` is the network name as hex, and `EAPOL` is one EAPOL-Key message as hex.
 * Types `03` and `04` carry an 802.11r fast-transition hierarchy and are reported separately
 * instead of being cracked with the wrong key derivation.
 *
 * ```
 * WPA*01*4d4fe7aac3a2cecab195321ceb99a7d0*fc690c158264*f4747f87f9f4*686173686361742d6573736964***
 * WPA*02*0102030405060708090a0b0c0d0e0f10*fc690c158264*f4747f87f9f4*68617368636174*<anonce>*<eapol>*00
 * ```
 */
object Hc22000Parser {

    const val MARKER = "WPA"

    /** The nine fields of a plain record, or twelve when fast-transition data is appended. */
    const val PLAIN_FIELDS = 9
    private const val FT_FIELDS = 12

    private const val TYPE_PMKID = 1
    private const val TYPE_MIC = 2
    private const val TYPE_FT_PMKID = 3
    private const val TYPE_FT_MIC = 4

    private const val MAC_LENGTH = 6
    private const val HEX_MAC_LENGTH = MAC_LENGTH * 2

    /** Records that were read but cannot be checked by this build. */
    data class Rejected(val line: String, val reason: Rejection)

    enum class Rejection(val message: String) {
        NOT_VERIFIABLE("нет SSID, проверить пароль невозможно"),
        FT_UNSUPPORTED("802.11r fast-transition (WPA*03/WPA*04) пока не поддерживается"),
        MALFORMED("повреждённая структура записи"),
        NO_EAPOL("в записи нет EAPOL-Key message 2"),
    }

    fun looksLikeRecord(line: String): Boolean {
        val trimmed = line.trim().removePrefix("\uFEFF")
        if (!trimmed.startsWith("$MARKER*")) return false
        val type = trimmed.substringAfter('*').take(2)
        return type.toIntOrNull(16)?.let { it in TYPE_PMKID..TYPE_FT_MIC } == true
    }

    /** Result of one file: the records that can be checked, and the ones that cannot. */
    data class Result(
        val targets: List<HandshakeTarget>,
        val rejected: List<Rejected>,
    )

    fun parseFile(file: File): Result {
        val targets = mutableListOf<HandshakeTarget>()
        val rejected = mutableListOf<Rejected>()
        file.forEachCaptureLine { rawLine ->
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachCaptureLine
            if (!looksLikeRecord(line)) return@forEachCaptureLine
            when (val parsed = parseRecord(line)) {
                is RecordOutcome.Target -> targets += parsed.target
                is RecordOutcome.Rejected -> rejected += Rejected(line, parsed.reason)
                null -> Unit
            }
        }
        return Result(targets, rejected)
    }

    sealed interface RecordOutcome {
        data class Target(val target: HandshakeTarget) : RecordOutcome
        data class Rejected(val reason: Rejection) : RecordOutcome
    }

    fun parseRecord(line: String): RecordOutcome? {
        if (!looksLikeRecord(line)) return null
        val fields = line.split('*')
        if (fields.size < PLAIN_FIELDS) return RecordOutcome.Rejected(Rejection.MALFORMED)

        val type = fields[1].toIntOrNull(16) ?: return RecordOutcome.Rejected(Rejection.MALFORMED)
        if (type == TYPE_FT_PMKID || type == TYPE_FT_MIC) {
            return RecordOutcome.Rejected(Rejection.FT_UNSUPPORTED)
        }

        val authenticator = Hex.decode(fields[3]) ?: return RecordOutcome.Rejected(Rejection.MALFORMED)
        val supplicant = Hex.decode(fields[4]) ?: return RecordOutcome.Rejected(Rejection.MALFORMED)
        if (authenticator.size != MAC_LENGTH || supplicant.size != MAC_LENGTH) {
            return RecordOutcome.Rejected(Rejection.MALFORMED)
        }

        val ssid = Hex.decode(fields[5]) ?: return RecordOutcome.Rejected(Rejection.MALFORMED)
        if (ssid.isEmpty()) return RecordOutcome.Rejected(Rejection.NOT_VERIFIABLE)

        val keyMaterial = Hex.decode(fields[2]) ?: return RecordOutcome.Rejected(Rejection.MALFORMED)
        if (keyMaterial.size != PmkVerifier.PMKID_LENGTH) {
            return RecordOutcome.Rejected(Rejection.MALFORMED)
        }

        return when (type) {
            TYPE_PMKID -> RecordOutcome.Target(
                HandshakeTarget(
                    ssidBytes = ssid,
                    verifier = PmkVerifier.PmkId(keyMaterial, authenticator, supplicant),
                    rawLine = line,
                    evidence = CaptureEvidence.PMKID,
                    bssid = authenticator,
                    station = supplicant,
                ),
            )

            TYPE_MIC -> {
                if (fields.size != PLAIN_FIELDS && fields.size != FT_FIELDS) {
                    return RecordOutcome.Rejected(Rejection.MALFORMED)
                }
                val authenticatorNonce = Hex.decode(fields[6])
                    ?: return RecordOutcome.Rejected(Rejection.MALFORMED)
                val eapol = Hex.decode(fields[7]) ?: return RecordOutcome.Rejected(Rejection.MALFORMED)
                micRecord(ssid, keyMaterial, authenticator, supplicant, authenticatorNonce, eapol, line)
            }

            else -> RecordOutcome.Rejected(Rejection.MALFORMED)
        }
    }

    private fun micRecord(
        ssid: ByteArray,
        mic: ByteArray,
        authenticator: ByteArray,
        supplicant: ByteArray,
        authenticatorNonce: ByteArray,
        eapol: ByteArray,
        line: String,
    ): RecordOutcome {
        if (!EapolKey.isEapolKey(eapol)) return RecordOutcome.Rejected(Rejection.NO_EAPOL)
        if (EapolKey.messageNumber(eapol) != EapolKey.MESSAGE_2) {
            return RecordOutcome.Rejected(Rejection.NO_EAPOL)
        }
        if (authenticatorNonce.size != EapolKey.NONCE_LENGTH) {
            return RecordOutcome.Rejected(Rejection.NO_EAPOL)
        }
        val descriptorType = EapolKey.keyDescriptorType(eapol)
        if (descriptorType != Prf.KEY_DESCRIPTOR_TKIP && descriptorType != Prf.KEY_DESCRIPTOR_CCMP) {
            return RecordOutcome.Rejected(Rejection.NO_EAPOL)
        }

        val supplicantNonce = EapolKey.nonce(eapol)
        if (supplicantNonce.all { it.toInt() == 0 }) {
            // Without the supplicant nonce the PTK cannot be rebuilt, so no candidate can be
            // confirmed. Saying so beats reporting a run that can never succeed.
            return RecordOutcome.Rejected(Rejection.NO_EAPOL)
        }

        return RecordOutcome.Target(
            HandshakeTarget(
                ssidBytes = ssid,
                verifier = PmkVerifier.Mic(
                    message = eapol,
                    authenticator = authenticator,
                    supplicant = supplicant,
                    authenticatorNonce = authenticatorNonce,
                    supplicantNonce = supplicantNonce,
                    keyDescriptorType = descriptorType,
                    expected = mic,
                ),
                rawLine = line,
                evidence = CaptureEvidence.EAPOL_MIC,
                bssid = authenticator,
                station = supplicant,
                keyDescriptorType = descriptorType,
            ),
        )
    }
}

internal inline fun File.forEachCaptureLine(action: (String) -> Unit) {
    useLines(Charsets.UTF_8) { lines -> lines.forEach { action(it) } }
}