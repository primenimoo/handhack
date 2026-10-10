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

    /** Message numbers as they appear in bits 1..2 of the key information field. */
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

    fun keyInfo(frame: ByteArray): Int =
        byte(frame, KEY_INFO_OFFSET) or (byte(frame, KEY_INFO_OFFSET + 1) shl 8)

    fun messageNumber(frame: ByteArray): Int = (keyInfo(frame) shr 1) and 0x03

    fun hasMic(frame: ByteArray): Boolean = (keyInfo(frame) and 0x20) != 0

    fun isAck(frame: ByteArray): Boolean = (keyInfo(frame) and 0x10) != 0

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

    fun keyData(frame: ByteArray): ByteArray {
        val declared = byte(frame, KEY_DATA_LENGTH_OFFSET) or (byte(frame, KEY_DATA_LENGTH_OFFSET + 1) shl 8)
        val available = frame.size - FIXED_LENGTH
        if (declared <= 0 || declared > available) return ByteArray(0)
        return frame.copyOfRange(FIXED_LENGTH, FIXED_LENGTH + declared)
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