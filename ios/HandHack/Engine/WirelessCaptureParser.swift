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

            let pair = identifyEndpoints(address1, address2, address3, control, ssidByBssid: ssidByBssid)
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

        var targets: [HandshakeTarget] = []
        var claimed = Set<String>()
        var messageOnesWithPmkid = 0
        var missingSsid = 0

        // The PMKID attack: an access point that is asked hands its PMKID out in message 1, and
        // that one value is enough to test a passphrase. This is what makes a capture that never
        // got past message 1 crackable, and it is the usual output of ESP32 / "Bruce firmware".
        for message in messages where message.number == EapolKey.message1 {
            guard let pmkid = EapolKey.pmkid(message.frame) else { continue }
            messageOnesWithPmkid += 1
            guard let ssid = ssidByBssid[Hex.encode(message.authenticator)] else {
                missingSsid += 1
                continue
            }
            guard claimed.insert("pmkid:\(message.key)").inserted else { continue }
            targets.append(HandshakeTarget(
                ssidBytes: ssid,
                verifier: .pmkid(pmkid: pmkid,
                                 authenticator: message.authenticator,
                                 supplicant: message.supplicant),
                rawLine: "PMKID \(Hex.encode(message.authenticator))",
                evidence: .pmkid,
                bssid: message.authenticator,
                station: message.supplicant))
        }

        // A MIC needs both nonces to be rebuilt into a PTK: the authenticator's (message 1 or 3)
        // and the supplicant's (message 2 or 4). Whichever message carries the MIC is the one
        // whose sixteen bytes are checked, so message 3 and message 4 work as well as message 2.
        var groups: [String: [Message]] = [:]
        for message in messages { groups[message.key, default: []].append(message) }

        var micWithoutNonces = 0
        for group in groups.values {
            let authenticatorMessages = group.filter {
                ($0.number == EapolKey.message1 || $0.number == EapolKey.message3) && !isZeroNonce($0.frame)
            }
            let supplicantNonce = group
                .first { ($0.number == EapolKey.message2 || $0.number == EapolKey.message4) && !isZeroNonce($0.frame) }
                .map { EapolKey.nonce($0.frame) }
            let carried = group.first {
                $0.hasMic && ($0.number == EapolKey.message2 || $0.number == EapolKey.message3 || $0.number == EapolKey.message4)
            }
            guard let micMessage = carried else { continue }

            // A genuine four-way handshake repeats the access point's nonce in message 1 and
            // message 3. When the two differ, these frames cannot belong to one handshake: the
            // file was assembled or damaged rather than sniffed, so no passphrase can reproduce its
            // MIC — hashcat would fail on it too. Say that instead of quietly reporting "not found".
            if let mismatch = differingAuthenticatorNonces(authenticatorMessages) {
                let note = "Кадры M1 и M3 несут разные ANonce (\(mismatch.0) и \(mismatch.1)) — " +
                    "это не одно четырёхстороннее рукопожатие, а повреждённый или собранный вручную " +
                    "файл. Проверить пароль по нему нельзя: MIC не совпадёт ни с одним паролем."
                if !notes.contains(note) { notes.append(note) }
                continue
            }
            let authenticatorNonce = authenticatorMessages.first.map { EapolKey.nonce($0.frame) }
            guard let authenticatorNonce, let supplicantNonce else {
                micWithoutNonces += 1
                continue
            }
            guard let ssid = ssidByBssid[Hex.encode(micMessage.authenticator)] else {
                missingSsid += 1
                continue
            }
            guard claimed.insert("mic:\(micMessage.key)").inserted else { continue }

            let descriptorType = EapolKey.keyDescriptorType(micMessage.frame)
            if let target = MicTarget.build(
                ssid: ssid,
                mic: EapolKey.mic(micMessage.frame),
                authenticator: micMessage.authenticator,
                supplicant: micMessage.supplicant,
                authenticatorNonce: authenticatorNonce,
                supplicantNonce: supplicantNonce,
                keyDescriptorType: descriptorType,
                message: micMessage.frame,
                rawLabel: "EAPOL M\(micMessage.number) \(Hex.encode(micMessage.authenticator))") {
                targets.append(target)
            }
        }

        if targets.isEmpty {
            explain(messages, messageOnesWithPmkid: messageOnesWithPmkid,
                    micWithoutNonces: micWithoutNonces, missingSsid: missingSsid, notes: &notes)
        }
        return targets
    }

    private static func explain(
        _ messages: [Message],
        messageOnesWithPmkid: Int,
        micWithoutNonces: Int,
        missingSsid: Int,
        notes: inout [String]
    ) {
        let present = Array(Set(messages.map { $0.number })).sorted()
        let hasSupplicantNonce = present.contains(EapolKey.message2) || present.contains(EapolKey.message4)

        if messageOnesWithPmkid == 0 && !hasSupplicantNonce {
            notes.append("В capture нет EAPOL-Key message 2 — проверить пароль не по чему. " +
                "Найдены сообщения: \(present.map { "M\($0)" }.joined(separator: ", ")). " +
                "Для атаки по PMKID нужна message 1 с полем PMKID.")
        }
        if micWithoutNonces > 0 {
            notes.append("Есть сообщение с MIC, но нет обоих nonce (message 1/3 и 2/4) — " +
                "без них нельзя построить PTK.")
        }
        if missingSsid > 0 && !notes.contains(where: { $0.contains("имя сети") }) {
            notes.append("Не найдено имя сети для BSSID — в capture нет beacon или probe frames " +
                "с этим адресом, а имя сети обязательно для PBKDF2.")
        }
    }

    private static func isZeroNonce(_ frame: [UInt8]) -> Bool {
        EapolKey.nonce(frame).allSatisfy { $0 == 0 }
    }

    /// The two hex nonces when message 1 and message 3 disagree on the access point's nonce.
    /// A real handshake reuses one ANonce in both messages, so a disagreement means the capture
    /// is not a single, genuine four-way handshake.
    private static func differingAuthenticatorNonces(_ messages: [Message]) -> (String, String)? {
        guard let one = messages.first(where: { $0.number == EapolKey.message1 }),
              let three = messages.first(where: { $0.number == EapolKey.message3 }) else { return nil }
        let first = EapolKey.nonce(one.frame)
        let second = EapolKey.nonce(three.frame)
        guard first != second else { return nil }
        return (Hex.encode(first), Hex.encode(second))
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