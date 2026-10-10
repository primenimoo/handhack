package com.primenimoo.handhack.engine.capture

import com.primenimoo.handhack.engine.handshake.Hex
import com.primenimoo.handhack.engine.handshake.PmkVerifier

/**
 * One EAPOL-Key message as it appears inside a captured frame, addressed by absolute offsets.
 *
 * Layout of IEEE 802.11i, counted from the first byte of the EAPOL header:
 * ```
 *  0  version (1)          01
 *  1  type (1)             03
 *  2  body length (2, LE)
 *  4  key descriptor type (1)
 *  5  key information (2, LE)
 *  7  key length (2, LE)
 *  9  replay counter (8)
 * 17  nonce (32)
 * 49  key IV (16)
 * 65  key RSC (16)
 * 81  key MIC (16)
 * 97  key data length (2, LE)
 * 99  key data
 * ```
 */
object EapolKey {

    const val HEADER_LENGTH = 4
    const val DESCRIPTOR_OFFSET = 4
    const val KEY_INFO_OFFSET = 5
    const val KEY_LENGTH_OFFSET = 7
    const val NONCE_OFFSET = 17
    const val NONCE_LENGTH = 32
    const val MIC_OFFSET = PmkVerifier.MIC_OFFSET
    const val MIC_LENGTH = PmkVerifier.MIC_LENGTH
    const val KEY_DATA_LENGTH_OFFSET = 97
    const val FIXED_LENGTH = 99

    private const val EAPOL_VERSION = 0x01
    private const val EAPOL_KEY = 0x03

    const val MESSAGE_1 = 1
    const val MESSAGE_2 = 2
    const val MESSAGE_3 = 3
    const val MESSAGE_4 = 4

    /** True when [frame] starts with a complete EAPOL-Key message. */
    fun isEapolKey(frame: ByteArray): Boolean {
        if (frame.size < FIXED_LENGTH) return false
        if (byte(frame, 0) != EAPOL_VERSION || byte(frame, 1) != EAPOL_KEY) return false
        val descriptor = byte(frame, DESCRIPTOR_OFFSET)
        if (descriptor < 1 || descriptor > 3) return false
        val message = messageNumber(frame)
        return message in MESSAGE_1..MESSAGE_4
    }

    fun descriptorType(frame: ByteArray): Int = byte(frame, DESCRIPTOR_OFFSET)

    /** Key information is a big-endian sixteen-bit field, exactly as it sits on the wire. */
    fun keyInfo(frame: ByteArray): Int =
        (byte(frame, KEY_INFO_OFFSET) shl 8) or byte(frame, KEY_INFO_OFFSET + 1)

    /**
     * Which of the four messages this is, worked out from the flags the standard puts in the key
     * information field: message 1 is an acknowledgement without a MIC, message 3 an
     * acknowledgement with one and with Install set, and messages 2 and 4 are the supplicant's
     * replies that carry a MIC but no acknowledgement.
     */
    fun messageNumber(frame: ByteArray): Int {
        val info = keyInfo(frame)
        val ack = info and 0x0080 != 0
        val mic = info and 0x0100 != 0
        val install = info and 0x0040 != 0
        return when {
            ack && mic && install -> MESSAGE_3
            ack && !mic -> MESSAGE_1
            !ack && mic -> MESSAGE_2
            else -> 0
        }
    }

    fun hasMic(frame: ByteArray): Boolean = (keyInfo(frame) and 0x0100) != 0

    fun isAck(frame: ByteArray): Boolean = (keyInfo(frame) and 0x0080) != 0

    /**
     * Key descriptor type of the captured message, which decides the MIC digest: HMAC-MD5 for
     * WPA/TKIP (1), HMAC-SHA1 for WPA2 (2). Value 3 belongs to 802.11r and is not supported.
     * This is the descriptor byte at offset 4, not the low bits of the key information field.
     */
    fun keyDescriptorType(frame: ByteArray): Int = descriptorType(frame)

    fun replayCounter(frame: ByteArray): Long {
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or byte(frame, 9 + i).toLong()
        }
        return value
    }

    fun nonce(frame: ByteArray): ByteArray =
        frame.copyOfRange(NONCE_OFFSET, NONCE_OFFSET + NONCE_LENGTH)

    fun mic(frame: ByteArray): ByteArray =
        frame.copyOfRange(MIC_OFFSET, MIC_OFFSET + MIC_LENGTH)

    /** The length, in bytes, of the key-data (KDE) region, a big-endian sixteen-bit value. */
    fun keyDataLength(frame: ByteArray): Int {
        if (frame.size < FIXED_LENGTH) return 0
        return (byte(frame, KEY_DATA_LENGTH_OFFSET) shl 8) or byte(frame, KEY_DATA_LENGTH_OFFSET + 1)
    }

    fun keyData(frame: ByteArray): ByteArray {
        val declared = keyDataLength(frame)
        val available = frame.size - FIXED_LENGTH
        if (declared <= 0 || declared > available) return ByteArray(0)
        return frame.copyOfRange(FIXED_LENGTH, FIXED_LENGTH + declared)
    }

    /**
     * The PMKID an access point puts in message 1 when the client asks for it. This single value
     * already pins the passphrase, which is what makes the PMKID attack work on a capture that has
     * no message 2 at all — the usual output of ESP32 / "Bruce firmware" probes.
     *
     * It rides inside the key data as a key-data element: `0xDD`, length, OUI `00-0F-AC`, type
     * `0x04`, then the sixteen bytes.
     */
    fun pmkid(frame: ByteArray): ByteArray? {
        val data = keyData(frame)
        var i = 0
        while (i + 2 <= data.size) {
            val type = byte(data, i)
            val length = byte(data, i + 1)
            val valueStart = i + 2
            val valueEnd = valueStart + length
            if (valueEnd > data.size) break
            if (type == 0xDD && length >= 20) {
                val oui = data.copyOfRange(valueStart, valueStart + 3)
                if (oui.contentEquals(byteArrayOf(0x00, 0x0F, 0xAC.toByte())) &&
                    byte(data, valueStart + 3) == 0x04
                ) {
                    return data.copyOfRange(valueStart + 4, valueStart + 20)
                }
            }
            i = valueEnd
        }
        return null
    }

    /** The network name advertised inside the key data of message 1, when it carries one. */
    fun ssidFromKeyData(frame: ByteArray): ByteArray? {
        val data = keyData(frame)
        if (data.isEmpty()) return null
        // RSN and WPA information elements both start with ID 0x30 followed by a length, and
        // some capture tools prepend the element to the key data without padding.
        for (start in 0..minOf(2, data.size - 2)) {
            val id = byte(data, start)
            if (id != 0x30) continue
            val declared = byte(data, start + 1)
            val bodyStart = start + 2
            val limit = minOf(bodyStart + declared, data.size)
            val ssid = readSsidIe(data, bodyStart, limit)
            if (ssid != null) return ssid
        }
        return null
    }

    private fun readSsidIe(data: ByteArray, from: Int, limit: Int): ByteArray? {
        var i = from
        while (i + 1 < limit) {
            val id = byte(data, i)
            val length = byte(data, i + 1)
            val valueStart = i + 2
            val valueEnd = valueStart + length
            if (valueEnd > data.size) return null
            if (id == 0x00 && length > 0) return data.copyOfRange(valueStart, valueEnd)
            i = valueEnd
        }
        return null
    }

    /** Reads the SSID information element out of a beacon or probe frame body. */
    fun readBeaconSsid(body: ByteArray, from: Int): ByteArray? =
        readSsidIe(body, from, body.size)

    private fun byte(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

    fun hex(bytes: ByteArray): String = Hex.encode(bytes)
}