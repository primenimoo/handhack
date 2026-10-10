import Foundation

/// Reader for hashcat `.hc22000` records, the format hcxpcapngtool and HandHack share.
/// A record is nine `*`-separated fields: `WPA * TT * K * AA * SPA * ESSID * ANonce * EAPOL *
/// message_pair`, where `TT` is the type (01 PMKID, 02 MIC), `K` is the PMKID or MIC, `ESSID`
/// is the hex network name and `EAPOL` one EAPOL-Key message. Types 03 and 04 carry an 802.11r
/// fast-transition hierarchy and are reported separately instead of being cracked wrong.
enum Hc22000Parser {
    struct Rejected {
        enum Reason: String {
            case notVerifiable = "нет SSID, проверить пароль невозможно"
            case ftUnsupported = "802.11r fast-transition (WPA*03/WPA*04) пока не поддерживается"
            case malformed = "повреждённая структура записи"
            case noEapol = "в записи нет EAPOL-Key message 2"
        }
        let line: String
        let reason: Reason
    }

    enum RecordOutcome {
        case target(HandshakeTarget)
        case rejected(Rejected.Reason)
    }

    static let plainFields = 9
    static let ftFields = 12

    private static let typePmkid = 1
    private static let typeMic = 2
    private static let typeFtPmkid = 3
    private static let typeFtMic = 4

    static func looksLikeRecord(_ rawLine: String) -> Bool {
        let line = trimmed(rawLine)
        guard line.hasPrefix("WPA*") else { return false }
        let typeLiteral = line.dropFirst(4).prefix(2)
        guard let value = UInt8(typeLiteral, radix: 16) else { return false }
        return value >= typePmkid && value <= typeFtMic
    }

    static func parseFile(text: String) -> (targets: [HandshakeTarget], rejected: [Rejected]) {
        var targets: [HandshakeTarget] = []
        var rejected: [Rejected] = []
        for rawLine in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = trimmed(String(rawLine))
            if line.isEmpty || line.hasPrefix("#") || line.hasPrefix("//") { continue }
            if !looksLikeRecord(line) { continue }
            guard let outcome = parseRecord(line) else { continue }
            switch outcome {
            case .target(let target): targets.append(target)
            case .rejected(let reason): rejected.append(Rejected(line: line, reason: reason))
            }
        }
        return (targets, rejected)
    }

    static func parseRecord(_ rawLine: String) -> RecordOutcome? {
        let line = trimmed(rawLine)
        guard looksLikeRecord(line) else { return nil }
        let fields = line.split(separator: "*", omittingEmptySubsequences: false).map(String.init)
        guard fields.count >= plainFields else { return .rejected(.malformed) }

        guard let type = Int(fields[1], radix: 16) else { return .rejected(.malformed) }
        if type == typeFtPmkid || type == typeFtMic { return .rejected(.ftUnsupported) }

        guard let authenticator = Hex.decode(fields[3]) else { return .rejected(.malformed) }
        guard let supplicant = Hex.decode(fields[4]) else { return .rejected(.malformed) }
        if authenticator.count != 6 || supplicant.count != 6 { return .rejected(.malformed) }

        guard let ssid = Hex.decode(fields[5]) else { return .rejected(.malformed) }
        if ssid.isEmpty { return .rejected(.notVerifiable) }

        guard let keyMaterial = Hex.decode(fields[2]) else { return .rejected(.malformed) }
        if keyMaterial.count != PmkVerifier.pmkidLength { return .rejected(.malformed) }

        switch type {
        case typePmkid:
            return .target(HandshakeTarget(
                ssidBytes: ssid,
                verifier: .pmkid(pmkid: keyMaterial, authenticator: authenticator, supplicant: supplicant),
                rawLine: line,
                evidence: .pmkid,
                bssid: authenticator,
                station: supplicant))

        case typeMic:
            if fields.count != plainFields && fields.count != ftFields {
                return .rejected(.malformed)
            }
            guard let authenticatorNonce = Hex.decode(fields[6]) else { return .rejected(.malformed) }
            guard let eapol = Hex.decode(fields[7]) else { return .rejected(.malformed) }
            return micRecord(ssid: ssid, mic: keyMaterial, authenticator: authenticator,
                             supplicant: supplicant, authenticatorNonce: authenticatorNonce,
                             eapol: eapol, line: line)

        default:
            return .rejected(.malformed)
        }
    }

    private static func micRecord(
        ssid: [UInt8], mic: [UInt8], authenticator: [UInt8], supplicant: [UInt8],
        authenticatorNonce: [UInt8], eapol: [UInt8], line: String
    ) -> RecordOutcome {
        guard EapolKey.isEapolKey(eapol) else { return .rejected(.noEapol) }
        guard EapolKey.messageNumber(eapol) == EapolKey.message2 else { return .rejected(.noEapol) }
        guard authenticatorNonce.count == EapolKey.nonceLength else { return .rejected(.noEapol) }
        let descriptorType = EapolKey.keyDescriptorType(eapol)
        guard descriptorType == Prf.keyDescriptorTkip || descriptorType == Prf.keyDescriptorCcmp else {
            return .rejected(.noEapol)
        }
        // The supplicant nonce is carried inside the message itself.
        let supplicantNonce = EapolKey.nonce(eapol)
        if supplicantNonce.allSatisfy({ $0 == 0 }) { return .rejected(.noEapol) }

        return .target(HandshakeTarget(
            ssidBytes: ssid,
            verifier: .mic(message: eapol, authenticator: authenticator, supplicant: supplicant,
                           authenticatorNonce: authenticatorNonce,
                           supplicantNonce: supplicantNonce,
                           keyDescriptorType: descriptorType, expected: mic),
            rawLine: line,
            evidence: .eapolMic,
            bssid: authenticator,
            station: supplicant,
            keyDescriptorType: descriptorType))
    }

    private static func trimmed(_ raw: String) -> String {
        var line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if line.hasPrefix("\u{FEFF}") {
            line.removeFirst()
        }
        return line
    }
}