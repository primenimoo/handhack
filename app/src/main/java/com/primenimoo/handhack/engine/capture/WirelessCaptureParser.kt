package com.primenimoo.handhack.engine.capture

import com.primenimoo.handhack.engine.handshake.HandshakeTarget
import com.primenimoo.handhack.engine.handshake.Hex

/**
 * Turns raw 802.11 frames into the handshake records the cracker can check.
 *
 * A packet capture contains no PMK and no PMKID, only the four EAPOL-Key messages of one
 * four-way handshake. Message 2 is the useful one: its MIC is computed with the key
 * confirmation key taken from the PTK, so a candidate passphrase is confirmed by rebuilding
 * that key and reproducing those sixteen bytes.
 *
 * Message 2 alone is not enough — the PTK needs the supplicant nonce from message 1 — so
 * messages are paired per (authenticator, supplicant) before a target is produced.
 */
object WirelessCaptureParser {

    /** Why a capture produced no usable record, phrased for the user. */
    data class Summary(
        val targets: List<HandshakeTarget>,
        val networkCount: Int,
        val eapolCount: Int,
        val notes: List<String>,
    )

    private const val MAC_LENGTH = 6
    private const val HEADER_MINIMUM = 24
    private const val ETHER_TYPE_EAPOL = 0x888E

    private const val FRAME_TYPE_MANAGEMENT = 0
    private const val FRAME_TYPE_DATA = 2

    private const val SUBTYPE_ASSOCIATION_RESPONSE = 1
    private const val SUBTYPE_BEACON = 8
    private const val SUBTYPE_PROBE_REQUEST = 4
    private const val SUBTYPE_PROBE_RESPONSE = 5
    private const val SUBTYPE_QOS_DATA = 8
    private const val SUBTYPE_QOS_NULL = 0x0B

    // Frame control bits 8 and 9, which is where "to" and "from" the distribution system live.
    private const val BIT_TO_DS = 0x0100
    private const val BIT_FROM_DS = 0x0200

    /** One EAPOL-Key message together with the two addresses it travelled between. */
    private class Message(
        val authenticator: ByteArray,
        val supplicant: ByteArray,
        val number: Int,
        val frame: ByteArray,
        val hasMic: Boolean,
        val replayCounter: Long,
    ) {
        val key: String get() = Hex.encode(authenticator) + Hex.encode(supplicant)
    }

    fun parse(frames: List<CapturedFrame>): Summary {
        val notes = mutableListOf<String>()
        val ssidByBssid = mutableMapOf<String, ByteArray>()
        val messages = mutableListOf<Message>()

        for (captured in frames) {
            val frame = captured.data
            if (frame.size < HEADER_MINIMUM) continue
            val control = le16(frame, 0)
            if (control and 0x03 != 0) continue // not a 802.11 revision we read
            val type = (control shr 2) and 0x03
            val subtype = (control shr 4) and 0x0F

            val address1 = frame.copyOfRange(4, 10)
            val address2 = frame.copyOfRange(10, 16)
            val address3 = frame.copyOfRange(16, 22)

            if (type == FRAME_TYPE_MANAGEMENT) {
                readSsid(frame, subtype)?.let { ssid ->
                    // A beacon and a probe response both carry the access point in address 2.
                    if (subtype == SUBTYPE_BEACON || subtype == SUBTYPE_PROBE_RESPONSE) {
                        ssidByBssid.putIfAbsent(Hex.encode(address2), ssid)
                    } else if (subtype == SUBTYPE_PROBE_REQUEST) {
                        ssidByBssid.putIfAbsent(Hex.encode(address3), ssid)
                    } else if (subtype == SUBTYPE_ASSOCIATION_RESPONSE) {
                        ssidByBssid.putIfAbsent(Hex.encode(address3), ssid)
                    }
                }
                continue
            }

            if (type != FRAME_TYPE_DATA) continue

            val eapol = findEapol(frame, control) ?: continue
            if (!EapolKey.isEapolKey(eapol)) continue

            val pair = identifyEndpoints(address1, address2, address3, control, ssidByBssid)
            messages += Message(
                authenticator = pair.first,
                supplicant = pair.second,
                number = EapolKey.messageNumber(eapol),
                frame = eapol,
                hasMic = EapolKey.hasMic(eapol),
                replayCounter = EapolKey.replayCounter(eapol),
            )
        }

        val targets = buildTargets(messages, ssidByBssid, notes)
        return Summary(
            targets = targets,
            networkCount = ssidByBssid.size,
            eapolCount = messages.size,
            notes = notes,
        )
    }

    private fun buildTargets(
        messages: List<Message>,
        ssidByBssid: Map<String, ByteArray>,
        notes: MutableList<String>,
    ): List<HandshakeTarget> {
        if (messages.isEmpty()) {
            notes += "В capture нет ни одного EAPOL-Key сообщения."
            return emptyList()
        }

        val messageOnes = messages.filter { it.number == EapolKey.MESSAGE_1 }
            .associateBy { it.key }
        val messageTwos = messages.filter { it.number == EapolKey.MESSAGE_2 }

        if (messageTwos.isEmpty()) {
            val seen = messages.map { it.number }.distinct().sorted()
            notes += "В capture нет EAPOL-Key message 2 — проверить пароль не по чему. " +
                "Найдены сообщения: ${seen.joinToString(", ") { "M$it" }}."
            return emptyList()
        }

        val targets = mutableListOf<HandshakeTarget>()
        var missingMessageOne = 0
        var missingSsid = 0
        var missingMic = 0

        for (second in messageTwos) {
            if (!second.hasMic) {
                missingMic++
                continue
            }
            val first = messageOnes[second.key]
            if (first == null) {
                missingMessageOne++
                continue
            }
            // Message 1 carries the authenticator's nonce, message 2 the supplicant's: the PTK
            // is built from the nonce each side generated, so they must not be crossed over.
            val authenticatorNonce = EapolKey.nonce(first.frame)
            val supplicantNonce = EapolKey.nonce(second.frame)
            val descriptorType = EapolKey.keyDescriptorType(second.frame)
            val ssid = ssidByBssid[Hex.encode(second.authenticator)]

            val target = MicTarget.build(
                ssid = ssid ?: ByteArray(0),
                mic = EapolKey.mic(second.frame),
                authenticator = second.authenticator,
                supplicant = second.supplicant,
                authenticatorNonce = authenticatorNonce,
                supplicantNonce = supplicantNonce,
                keyDescriptorType = descriptorType,
                message = second.frame,
                rawLabel = "EAPOL M2 ${Hex.encode(second.authenticator)}",
            )
            if (target != null) {
                targets += target
            } else if (ssid == null) {
                missingSsid++
            }
        }

        if (targets.isEmpty()) {
            if (missingMic > 0) {
                notes += "EAPOL-Key message 2 найдена без MIC — в capture он обнулён."
            }
            if (missingMessageOne > 0) {
                notes += "Нет EAPOL-Key message 1: без supplicant nonce нельзя построить PTK."
            }
            if (missingSsid > 0) {
                notes += "Не найдено имя сети для BSSID — в capture нет beacon или probe frames " +
                    "с этим адресом, а имя сети обязательно для PBKDF2."
            }
        }
        return targets
    }

    /**
     * Works out which address is the access point. A beacon or probe frame settles it outright;
     * otherwise the DS bits of the data header decide, which is what the standard guarantees for
     * a frame that came from or went to the distribution system.
     */
    private fun identifyEndpoints(
        address1: ByteArray,
        address2: ByteArray,
        address3: ByteArray,
        control: Int,
        ssidByBssid: Map<String, ByteArray>,
    ): Pair<ByteArray, ByteArray> {
        val firstIsBssid = ssidByBssid.containsKey(Hex.encode(address1))
        val secondIsBssid = ssidByBssid.containsKey(Hex.encode(address2))
        if (firstIsBssid && !secondIsBssid) return address1 to address2
        if (secondIsBssid && !firstIsBssid) return address2 to address1

        val toDistributionSystem = control and BIT_TO_DS != 0
        val fromDistributionSystem = control and BIT_FROM_DS != 0
        return when {
            // From the distribution system: address 2 is the transmitter, so the access point.
            fromDistributionSystem && !toDistributionSystem -> address2 to address1
            // To the distribution system: address 1 is the access point the frame is sent to.
            toDistributionSystem && !fromDistributionSystem -> address1 to address2
            // A WDS frame names the network in address 3 and sends between two stations.
            else -> address3 to address2
        }
    }

    /** Reads the SSID information element out of a management frame. */
    private fun readSsid(frame: ByteArray, subtype: Int): ByteArray? {
        val fixed = when (subtype) {
            SUBTYPE_BEACON, SUBTYPE_PROBE_RESPONSE -> 12
            SUBTYPE_ASSOCIATION_RESPONSE -> 6
            0 -> 4
            SUBTYPE_PROBE_REQUEST -> 0
            else -> return null
        }
        val start = HEADER_MINIMUM + fixed
        if (start >= frame.size) return null
        return EapolKey.readBeaconSsid(frame, start)
    }

    /**
     * Finds the EAPOL frame inside a data frame. The payload of an 802.11 data frame starts with
     * the 24-byte MAC header, plus two control bytes when the subtype marks a QoS frame, plus one
     * more address when both DS bits are set, and the EAPOL payload itself is wrapped in an
     * LLC/SNAP header.
     */
    private fun findEapol(frame: ByteArray, control: Int): ByteArray? {
        val toDistributionSystem = control and BIT_TO_DS != 0
        val fromDistributionSystem = control and BIT_FROM_DS != 0
        val subtype = (control shr 4) and 0x0F

        var header = HEADER_MINIMUM
        if (toDistributionSystem && fromDistributionSystem) header += MAC_LENGTH
        if (subtype == SUBTYPE_QOS_DATA || subtype == SUBTYPE_QOS_NULL) header += 2
        if (header >= frame.size) return null

        // Search a short window rather than trusting one exact header size: capture tools differ
        // on whether the fourth address and the QoS control field are counted, and a frame that
        // is misaligned by a couple of bytes still holds a perfectly good handshake.
        val limit = minOf(frame.size - 8, header + 12)
        for (offset in header..limit) {
            if (frame[offset] != 0xAA.toByte()) continue
            if (frame[offset + 1] != 0xAA.toByte()) continue
            if (frame[offset + 2] != 0x03.toByte()) continue
            val etherType = (frame[offset + 6].toInt() and 0xFF shl 8) or (frame[offset + 7].toInt() and 0xFF)
            if (etherType != ETHER_TYPE_EAPOL) continue
            return frame.copyOfRange(offset + 8, frame.size)
        }
        return null
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}