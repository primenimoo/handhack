package com.primenimoo.handhack.engine.capture

import com.primenimoo.handhack.engine.handshake.CaptureEvidence
import com.primenimoo.handhack.engine.handshake.HandshakeTarget
import com.primenimoo.handhack.engine.handshake.Hex
import com.primenimoo.handhack.engine.handshake.PmkVerifier
import com.primenimoo.handhack.engine.handshake.Prf
import java.io.File
import java.io.RandomAccessFile

/**
 * Shared last step of every container that stores one EAPOL-Key message plus the two nonces and
 * the two addresses it belongs to: turning that into a [PmkVerifier.Mic].
 *
 * Both legacy containers keep the MIC in their own field as well as inside the message, and the
 * field wins, because that is the value the file was written to be checked against.
 */
internal object MicTarget {

    fun build(
        ssid: ByteArray,
        mic: ByteArray,
        authenticator: ByteArray,
        supplicant: ByteArray,
        authenticatorNonce: ByteArray,
        supplicantNonce: ByteArray,
        keyDescriptorType: Int,
        message: ByteArray,
        rawLabel: String,
    ): HandshakeTarget? {
        if (ssid.isEmpty()) return null
        if (authenticator.size != 6 || supplicant.size != 6) return null
        if (mic.size != PmkVerifier.MIC_LENGTH) return null
        if (authenticatorNonce.size != EapolKey.NONCE_LENGTH) return null
        if (supplicantNonce.size != EapolKey.NONCE_LENGTH) return null
        if (message.size < EapolKey.FIXED_LENGTH) return null
        if (keyDescriptorType != Prf.KEY_DESCRIPTOR_TKIP && keyDescriptorType != Prf.KEY_DESCRIPTOR_CCMP) {
            return null
        }
        // A zero supplicant nonce means the capture never carried message 1, and without it the
        // PTK cannot be rebuilt, so no candidate can ever be confirmed.
        if (supplicantNonce.all { it.toInt() == 0 }) return null

        return HandshakeTarget(
            ssidBytes = ssid,
            verifier = PmkVerifier.Mic(
                message = message,
                authenticator = authenticator,
                supplicant = supplicant,
                authenticatorNonce = authenticatorNonce,
                supplicantNonce = supplicantNonce,
                keyDescriptorType = keyDescriptorType,
                expected = mic,
            ),
            rawLine = rawLabel,
            evidence = CaptureEvidence.EAPOL_MIC,
            bssid = authenticator,
            station = supplicant,
            keyDescriptorType = keyDescriptorType,
        )
    }
}

/**
 * Reader for `.hccapx`, the hashcat container of fixed 393-byte records.
 *
 * ```
 *  0  signature  u32   always 0x58504348, the bytes "HCPX"
 *  4  version    u32   4
 *  8  message_pair u8  which two messages the record holds
 *  9  essid_len  u8
 * 10  essid      32 bytes
 * 42  keyver     u8    1 for WPA/TKIP, 2 for WPA2
 * 43  keymic     16 bytes
 * 59  mac_ap     6 bytes
 * 65  nonce_ap   32 bytes
 * 97  mac_sta    6 bytes
 *103  nonce_sta  32 bytes
 *135  eapol_len u16
 *137  eapol     256 bytes
 * ```
 */
object HccapxReader {

    const val RECORD_LENGTH = 393
    private const val SIGNATURE = 0x58504348L
    private const val VERSION = 4L

    fun sniff(head: ByteArray): Boolean {
        if (head.size < 8) return false
        return le32(head, 0) == SIGNATURE && le32(head, 4) == VERSION
    }

    fun parse(file: File): List<HandshakeTarget> =
        RandomAccessFile(file, "r").use { raf ->
            // The signature lives in the first eight bytes of the first record, so the whole
            // record is read once and then reused: skipping ahead would drop it.
            val record = ByteArray(RECORD_LENGTH)
            if (raf.read(record) != RECORD_LENGTH) return emptyList()
            if (!sniff(record)) return emptyList()

            val targets = mutableListOf<HandshakeTarget>()
            target(record)?.let(targets::add)
            while (raf.read(record) == RECORD_LENGTH) {
                target(record)?.let(targets::add)
            }
            targets
        }

    private fun target(record: ByteArray): HandshakeTarget? {
        val essidLength = byte(record, 9)
        if (essidLength == 0 || essidLength > 32) return null
        val essid = record.copyOfRange(10, 10 + essidLength)

        val eapolLength = le16(record, 135)
        if (eapolLength < EapolKey.FIXED_LENGTH || eapolLength > 256) return null
        val eapol = record.copyOfRange(137, 137 + eapolLength)

        return MicTarget.build(
            ssid = essid,
            mic = record.copyOfRange(43, 43 + PmkVerifier.MIC_LENGTH),
            authenticator = record.copyOfRange(59, 59 + 6),
            supplicant = record.copyOfRange(97, 97 + 6),
            authenticatorNonce = record.copyOfRange(65, 65 + EapolKey.NONCE_LENGTH),
            supplicantNonce = record.copyOfRange(103, 103 + EapolKey.NONCE_LENGTH),
            keyDescriptorType = byte(record, 42),
            message = eapol,
            rawLabel = "hccapx ${Hex.encode(essid)}",
        )
    }

    private fun byte(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF
    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}

/**
 * Reader for `.hccap`, the container hashcat v3.5 and lower used. It carries no magic, only a
 * fixed 392-byte record with native-endian `int` fields, so the layout is the only way to tell
 * it apart from other binaries.
 *
 * ```
 *  0  essid      36 bytes, a NUL terminated network name
 * 36  mac1       6 bytes   authenticator
 * 42  mac2       6 bytes   supplicant
 * 48  nonce1     32 bytes  authenticator nonce
 * 80  nonce2     32 bytes  supplicant nonce
 *112  eapol      256 bytes
 *368  eapol_size int32
 *372  keyver     int32
 *376  keymic     16 bytes
 * ```
 */
object HccapReader {

    const val RECORD_LENGTH = 392
    private const val ESSID_FIELD = 36
    private const val MINIMUM_SNIFF_BYTES = 48

    /**
     * A cheap plausibility test, because the format has no signature: the network name must be
     * printable and NUL terminated, and both addresses must be set. Needs the first 48 bytes,
     * so a caller that sniffs a file must read at least that much before asking.
     */
    fun sniff(head: ByteArray): Boolean {
        if (head.size < MINIMUM_SNIFF_BYTES) return false
        val nameLength = nameLength(head) ?: return false
        for (i in 0 until nameLength) {
            val c = head[i].toInt() and 0xFF
            if (c < 0x20 || c == 0x7F) return false
        }
        return !head.copyOfRange(36, 42).contentEquals(ZERO_ADDRESS) &&
            !head.copyOfRange(42, 48).contentEquals(ZERO_ADDRESS)
    }

    fun parse(file: File): List<HandshakeTarget> =
        RandomAccessFile(file, "r").use { raf ->
            val record = ByteArray(RECORD_LENGTH)
            if (raf.read(record) != RECORD_LENGTH) return emptyList()
            if (!sniff(record)) return emptyList()

            val targets = mutableListOf<HandshakeTarget>()
            target(record)?.let(targets::add)
            while (raf.read(record) == RECORD_LENGTH) {
                target(record)?.let(targets::add)
            }
            targets
        }

    private val ZERO_ADDRESS = ByteArray(6)

    /** Length of the NUL terminated network name, or null when it is missing or overlong. */
    private fun nameLength(record: ByteArray): Int? {
        var length = 0
        while (length < ESSID_FIELD && record[length].toInt() != 0) length++
        if (length == 0 || length >= ESSID_FIELD) return null
        return length
    }

    private fun target(record: ByteArray): HandshakeTarget? {
        val nameLength = nameLength(record) ?: return null
        val essid = record.copyOfRange(0, nameLength)

        // The two int fields are native-endian in the format. Little endian is what every capture
        // tool in use writes, and the sanity limits below reject a byte-swapped read, so a big
        // endian file is picked up by the second attempt instead of silently misread.
        for (bigEndian in booleanArrayOf(false, true)) {
            val eapolLength = (if (bigEndian) be32(record, 368) else le32(record, 368)).toInt()
            if (eapolLength < EapolKey.FIXED_LENGTH || eapolLength > 256) continue
            val keyver = (if (bigEndian) be32(record, 372) else le32(record, 372)).toInt()
            if (keyver != Prf.KEY_DESCRIPTOR_TKIP && keyver != Prf.KEY_DESCRIPTOR_CCMP) continue
            val eapol = record.copyOfRange(112, 112 + eapolLength)

            return MicTarget.build(
                ssid = essid,
                mic = record.copyOfRange(376, 376 + PmkVerifier.MIC_LENGTH),
                authenticator = record.copyOfRange(36, 36 + 6),
                supplicant = record.copyOfRange(42, 42 + 6),
                authenticatorNonce = record.copyOfRange(48, 48 + EapolKey.NONCE_LENGTH),
                supplicantNonce = record.copyOfRange(80, 80 + EapolKey.NONCE_LENGTH),
                keyDescriptorType = keyver,
                message = eapol,
                rawLabel = "hccap ${Hex.encode(essid)}",
            )
        }
        return null
    }

    private fun be32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun le32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}