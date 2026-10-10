import Foundation

/// Turns raw 802.11 frames into the handshake records the cracker can check.
///
/// A packet capture holds no PMK and no PMKID, only the EAPOL-Key messages of a four-way
/// handshake. Message 2 is the useful one: its MIC is computed with the key confirmation key
/// taken from the PTK, so a candidate passphrase is confirmed by rebuilding that key and
/// reproducing those sixteen bytes. Message 2 alone is not enough — the PTK also needs the
/// supplicant nonce from message 1 — so messages are paired before a target is produced.
enum WirelessCaptureParser {
    struct Summary {
        let targets: [HandshakeTarget]
        let networkCount: Int
        let eapolCount: Int
        /// Why a capture produced no usable record, phrased for the user.
        let notes: [String]
    }

    private static let macLength = 6
    private static let headerMinimum = 24
    private static let etherTypeEapol = 0x888E

    private static let frameTypeManagement = 0
    private static let frameTypeData = 2

    private static let subtypeAssociationResponse = 1
    private static let subtypeProbeRequest = 4
    private static let subtypeProbeResponse = 5
    private static let subtypeBeacon = 8
    private static let subtypeQosData = 8
    private static let subtypeQosNull = 0x0B

    // Frame control bits 8 and 9, which is where "to" and "from" the distribution system live.
    private static let bitToDs = 0x0100
    private static let bitFromDs = 0x0200

    private struct Message {
        let authenticator: [UInt8]
        let supplicant: [UInt8]
        let number: Int
        let frame: [UInt8]
        let hasMic: Bool
        let replayCounter: UInt64

        var key: String { Hex.encode(authenticator) + Hex.encode(supplicant) }
    }

    static func parse(frames: [CapturedFrame]) -> Summary {
        var notes: [String] = []
        var ssidByBssid: [String: [UInt8]] = [:]
        var messages: [Message] = []

        for captured in frames {
            let frame = captured.data
            if frame.count < headerMinimum { continue }
            let control = le16(frame, 0)
            if control & 0x03 != 0 { continue } // not a revision we read
            let type = (control >> 2) & 0x03
            let subtype = (control >> 4) & 0x0F

            let address1 = Array(frame[4..<10])
            let address2 = Array(frame[10..<16])
            let address3 = Array(frame[16..<22])

            if type == frameTypeManagement {
                if let ssid = readSsid(frame, subtype) {
                    // A beacon and a probe response both carry the access point in address 2.
                    if subtype == subtypeBeacon || subtype == subtypeProbeResponse {
                        ssidByBssid[Hex.encode(address2)] = ssid
                    } else if subtype == subtypeProbeRequest || subtype == subtypeAssociationResponse {
                        ssidByBssid[Hex.encode(address3)] = ssid
                    }
                }
                continue
            }
            if type != frameTypeData { continue }

            guard let eapol = findEapol(frame, control) else { continue }
            guard EapolKey.isEapolKey(eapol) else { continue }

            let pair = identifyEndpoints(address1, address2, address3, control, ssidByBssid)
            messages.append(Message(
                authenticator: pair.0,
                supplicant: pair.1,
                number: EapolKey.messageNumber(eapol),
                frame: eapol,
                hasMic: EapolKey.hasMic(eapol),
                replayCounter: EapolKey.replayCounter(eapol)))
        }

        let targets = buildTargets(messages, ssidByBssid: ssidByBssid, notes: &notes)
        return Summary(targets: targets, networkCount: ssidByBssid.count, eapolCount: messages.count, notes: notes)
    }

    private static func buildTargets(
        _ messages: [Message],
        ssidByBssid: [String: [UInt8]],
        notes: inout [String]
    ) -> [HandshakeTarget] {
        if messages.isEmpty {
            notes.append("В capture нет ни одного EAPOL-Key сообщения.")
            return []
        }

        var messageOnes: [String: Message] = [:]
        for message in messages where message.number == EapolKey.message1 {
            messageOnes[message.key] = message
        }
        let messageTwos = messages.filter { $0.number == EapolKey.message2 }

        if messageTwos.isEmpty {
            let seen = Set(messages.map { $0.number }).sorted()
            notes.append("В capture нет EAPOL-Key message 2 — проверить пароль не по чему. " +
                "Найдены сообщения: \(seen.map { "M\($0)" }.joined(separator: ", ")).")
            return []
        }

        var targets: [HandshakeTarget] = []
        var missingMessageOne = 0
        var missingSsid = 0
        var missingMic = 0

        for second in messageTwos {
            if !second.hasMic {
                missingMic += 1
                continue
            }
            guard let first = messageOnes[second.key] else {
                missingMessageOne += 1
                continue
            }
            // Message 1 carries the authenticator's nonce, message 2 the supplicant's: they
            // must not be crossed over, or the PTK can never match the captured MIC.
            let authenticatorNonce = EapolKey.nonce(first.frame)
            let supplicantNonce = EapolKey.nonce(second.frame)
            let descriptorType = EapolKey.keyDescriptorType(second.frame)
            let ssid = ssidByBssid[Hex.encode(second.authenticator)]

            let target = MicTarget.build(
                ssid: ssid ?? [],
                mic: EapolKey.mic(second.frame),
                authenticator: second.authenticator,
                supplicant: second.supplicant,
                authenticatorNonce: authenticatorNonce,
                supplicantNonce: supplicantNonce,
                keyDescriptorType: descriptorType,
                message: second.frame,
                rawLabel: "EAPOL M2 \(Hex.encode(second.authenticator))")
            if let created = target {
                targets.append(created)
            } else if ssid == nil {
                missingSsid += 1
            }
        }

        if targets.isEmpty {
            if missingMic > 0 {
                notes.append("EAPOL-Key message 2 найдена без MIC — в capture он обнулён.")
            }
            if missingMessageOne > 0 {
                notes.append("Нет EAPOL-Key message 1: без supplicant nonce нельзя построить PTK.")
            }
            if missingSsid > 0 {
                notes.append("Не найдено имя сети для BSSID — в capture нет beacon или probe frames " +
                    "с этим адресом, а имя сети обязательно для PBKDF2.")
            }
        }
        return targets
    }

    /// Works out which address is the access point: a beacon settles it outright, otherwise the
    /// DS bits of the data header decide, which is what the standard guarantees.
    private static func identifyEndpoints(
        _ address1: [UInt8], _ address2: [UInt8], _ address3: [UInt8],
        _ control: Int, ssidByBssid: [String: [UInt8]]
    ) -> ([UInt8], [UInt8]) {
        let firstIsBssid = ssidByBssid[Hex.encode(address1)] != nil
        let secondIsBssid = ssidByBssid[Hex.encode(address2)] != nil
        if firstIsBssid && !secondIsBssid { return (address1, address2) }
        if secondIsBssid && !firstIsBssid { return (address2, address1) }

        let toDistributionSystem = control & bitToDs != 0
        let fromDistributionSystem = control & bitFromDs != 0
        if fromDistributionSystem && !toDistributionSystem {
            return (address2, address1)
        }
        if toDistributionSystem && !fromDistributionSystem {
            return (address1, address2)
        }
        return (address3, address2)
    }

    private static func readSsid(_ frame: [UInt8], _ subtype: Int) -> [UInt8]? {
        let fixed: Int
        switch subtype {
        case subtypeBeacon, subtypeProbeResponse: fixed = 12
        case subtypeAssociationResponse: fixed = 6
        case subtypeProbeRequest: fixed = 0
        default: return nil
        }
        let start = headerMinimum + fixed
        if start >= frame.count { return nil }
        return EapolKey.readBeaconSsid(frame, from: start)
    }

    /// Finds the EAPOL frame inside a data frame, searching a short window rather than trusting
    /// one exact header size because capture tools differ on the fourth address and QoS control.
    private static func findEapol(_ frame: [UInt8], _ control: Int) -> [UInt8]? {
        let toDistributionSystem = control & bitToDs != 0
        let fromDistributionSystem = control & bitFromDs != 0
        let subtype = (control >> 4) & 0x0F

        var header = headerMinimum
        if toDistributionSystem && fromDistributionSystem { header += macLength }
        if subtype == subtypeQosData || subtype == subtypeQosNull { header += 2 }
        if header >= frame.count { return nil }

        let limit = min(frame.count - 8, header + 12)
        guard limit >= header else { return nil }
        for offset in header...limit {
            if frame[offset] != 0xAA { continue }
            if frame[offset + 1] != 0xAA { continue }
            if frame[offset + 2] != 0x03 { continue }
            let etherType = Int(frame[offset + 6]) << 8 | Int(frame[offset + 7])
            if etherType != etherTypeEapol { continue }
            return Array(frame[(offset + 8)...])
        }
        return nil
    }

    private static func le16(_ bytes: [UInt8], _ offset: Int) -> Int {
        Int(bytes[offset]) | Int(bytes[offset + 1]) << 8
    }
}