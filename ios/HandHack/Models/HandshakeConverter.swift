import Foundation

/// Turns any parsed targets back into a standalone handshake file, so a capture that arrived as
/// pcap, hccap(x) or a HandHack export can be handed to hashcat for verification outside the app.
/// The conversion is exact: every verifier restores the record it was parsed from, and a test
/// pushes each output line back through the reader to prove the round trip.
///
/// - `.pmkid` and `.mic` targets become full nine-field `hc22000` records (`WPA*01`/`WPA*02`).
/// - `.pmk` targets hold the whole key and only the app's compact export can carry it, so those
///   become `SSID:WPA*01<pmk>` lines, which HandHack reads right back.
enum HandshakeConverter {

    /// A file name for a converted capture. The extension follows the content: `.hc22000` unless
    /// every network is a direct PMK check, which is the legacy `.handshake` export instead.
    static func exportFileName(for targets: [HandshakeTarget], originalFile: String) -> String {
        let stripped = (originalFile as NSString).deletingPathExtension
        let base = stripped.isEmpty ? "handshake" : stripped
        let allDirectPmk = !targets.isEmpty && targets.allSatisfy { target in
            if case .pmk = target.verifier { return true }
            return false
        }
        return allDirectPmk ? "\(base).handshake" : "\(base).hc22000"
    }

    /// The full text of the converted file, one record per target.
    static func convert(targets: [HandshakeTarget]) -> String {
        var lines: [String] = []
        for target in targets {
            if let line = hc22000Line(target) {
                lines.append(line)
            } else {
                lines.append(compactLine(target))
            }
        }
        return lines.joined(separator: "\n")
    }

    /// A hashcat `.hc22000` record, or nil when the target stores the whole PMK.
    /// Fields: `WPA * TT * K * AA * SPA * ESSID * ANonce * EAPOL * message_pair`.
    private static func hc22000Line(_ target: HandshakeTarget) -> String? {
        let ssid = Hex.encode(target.ssidBytes)
        switch target.verifier {
        case .pmkid(let pmkid, let authenticator, let supplicant):
            return "WPA*01*\(Hex.encode(pmkid))*\(Hex.encode(authenticator))*" +
                "\(Hex.encode(supplicant))*\(ssid)*00*00*0"
        case .mic(let message, let authenticator, let supplicant,
                  let authenticatorNonce, _, _, let expected):
            return "WPA*02*\(Hex.encode(expected))*\(Hex.encode(authenticator))*" +
                "\(Hex.encode(supplicant))*\(ssid)*\(Hex.encode(authenticatorNonce))*" +
                "\(Hex.encode(message))*0"
        case .pmk:
            return nil
        }
    }

    /// The compact export, used only for direct-PMK targets, matching `LegacyExportParser`.
    private static func compactLine(_ target: HandshakeTarget) -> String {
        guard case .pmk(let bytes) = target.verifier else { return "" }
        let ssid = String(decoding: target.ssidBytes, as: UTF8.self)
        return "\(ssid):WPA*01\(Hex.encode(bytes))"
    }
}