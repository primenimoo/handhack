import Foundation

/// Parser for the compact text export HandHack itself has always accepted.
///
/// Supported line shapes, with the SSID either on the same line or on the line above:
/// ```
/// HomeNet:WPA*01aabbcc...
/// HomeNet<TAB>WPA*01aabbcc...
/// WPA*01aabbcc...            (SSID unknown — cannot be verified)
/// ```
/// The value after the marker is the 32-byte PMK, so the full key is compared directly. Lines
/// that are not handshake records are ignored, so mixed files still work.
enum LegacyExportParser {
    static let marker = "WPA*01"
    static let markerWpa02 = "WPA*02"

    private static var markers: [String] { [marker, markerWpa02] }

    static func countRecords(text: String) -> Int {
        var count = 0
        for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
            if markers.contains(where: { String(line).contains($0) }) { count += 1 }
        }
        return count
    }

    static func parse(text: String) -> [HandshakeTarget] {
        var results: [HandshakeTarget] = []
        var pendingSsid: String?

        for rawLine in text.split(separator: "\n", omittingEmptySubsequences: false) {
            var line = String(rawLine).trimmingCharacters(in: .whitespacesAndNewlines)
            if line.hasPrefix("\u{FEFF}") { line.removeFirst() }
            if line.isEmpty || line.hasPrefix("#") || line.hasPrefix("//") { continue }

            guard let marker = markers.first(where: { line.contains($0) }),
                  let markerRange = line.range(of: marker) else {
                // Not a handshake line: remember it as the SSID for a following record.
                pendingSsid = String(line.split(separator: ":", maxSplits: 1)[0])
                    .trimmingCharacters(in: .whitespaces)
                pendingSsid = (pendingSsid?.isEmpty ?? true) ? nil : pendingSsid
                continue
            }

            var hashHex = String(line[markerRange.upperBound...])
            if let space = hashHex.firstIndex(of: " ") { hashHex = String(hashHex[..<space]) }
            if let tab = hashHex.firstIndex(of: "\t") { hashHex = String(hashHex[..<tab]) }
            let hex = hashHex.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let bytes = Hex.decode(hex), bytes.count == 32 else { continue }

            var inline = String(line[..<markerRange.lowerBound])
                .trimmingCharacters(in: .whitespaces)
            while let last = inline.last, ":=".contains(last) { inline.removeLast() }
            let ssid = inline.isEmpty ? (pendingSsid ?? "") : inline
            if ssid.isEmpty { continue }

            results.append(HandshakeTarget(
                ssidBytes: Array(ssid.utf8),
                verifier: .pmk(bytes: bytes),
                rawLine: line,
                evidence: .pmk))
            pendingSsid = nil
        }
        return results
    }
}