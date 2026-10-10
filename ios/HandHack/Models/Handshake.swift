import Foundation

/// What kind of secret the capture exposes, and therefore which check can be run.
enum CaptureEvidence: String, CaseIterable, Codable {
    /// The full 32-byte pairwise master key is stored.
    case pmk = "PMK"
    /// Only `PMKID = HMAC-SHA1-96(PMK, "PMK Name" || AA || SPA)` is stored.
    case pmkid = "PMKID"
    /// One EAPOL-Key message with its MIC, checked through the PTK.
    case eapolMic = "EAPOL MIC"

    var label: String { rawValue }
}

/// How a candidate passphrase is checked against one captured network.
enum PmkVerifier: Equatable, Sendable {
    /// The full 32-byte pairwise master key is stored (the app's compact export).
    case pmk(bytes: [UInt8])
    /// Only the 16-byte PMKID plus the two addresses it was derived from (`WPA*01`).
    case pmkid(pmkid: [UInt8], authenticator: [UInt8], supplicant: [UInt8])
    /// One EAPOL-Key message whose MIC is checked through the key confirmation key (`WPA*02`).
    case mic(message: [UInt8], authenticator: [UInt8], supplicant: [UInt8],
             authenticatorNonce: [UInt8], supplicantNonce: [UInt8],
             keyDescriptorType: Int, expected: [UInt8])

    static let pmkidLength = 16
    static let micLength = 16
    /// Offset of the MIC inside an EAPOL-Key message: 4 header + 1 descriptor + 2 key
    /// information + 2 key length + 8 replay + 32 nonce + 16 IV + 16 RSC + 8 reserved = 81.
    static let micOffset = 81

    /// Short hex the result screen can show.
    var hex: String {
        switch self {
        case .pmk(let bytes): return Hex.encode(bytes)
        case .pmkid(let pmkid, _, _): return Hex.encode(pmkid)
        case .mic(_, _, _, _, _, _, let expected): return Hex.encode(expected)
        }
    }

    /// True when [pmk] reproduces what this verifier captured.
    func accepts(pmk: [UInt8]) -> Bool {
        switch self {
        case .pmk(let expected):
            return ConstantTime.equals(pmk, expected)
        case .pmkid(let pmkid, let authenticator, let supplicant):
            let derived = Pmkid.derive(pmk: pmk, authenticator: authenticator, supplicant: supplicant)
            return ConstantTime.equals(derived, pmkid)
        case .mic(let message, let authenticator, let supplicant,
                  let authenticatorNonce, let supplicantNonce,
                  let keyDescriptorType, let expected):
            let kck = Ptk.deriveKeyConfirmationKey(
                pmk: pmk, authenticator: authenticator, supplicant: supplicant,
                authenticatorNonce: authenticatorNonce, supplicantNonce: supplicantNonce,
                keyDescriptorType: keyDescriptorType)
            var zeroed = message
            for i in Self.micOffset..<min(Self.micOffset + Self.micLength, zeroed.count) {
                zeroed[i] = 0
            }
            let digest = Prf.isLegacyTkip(keyDescriptorType)
                ? Hmac.md5(key: kck, zeroed)
                : Hmac.sha1(key: kck, zeroed)
            let computed = Array(digest.prefix(Self.micLength))
            return ConstantTime.equals(computed, expected)
        }
    }
}

/// One captured network waiting for its password.
struct HandshakeTarget: Identifiable, Equatable, Sendable {
    let id: UUID
    /// The SSID as bytes: a network name is arbitrary octets, not necessarily text.
    let ssidBytes: [UInt8]
    let verifier: PmkVerifier
    let rawLine: String
    let evidence: CaptureEvidence
    /// The authenticator address when the capture names one.
    let bssid: [UInt8]?
    /// The supplicant address when the capture names one.
    let station: [UInt8]?
    let keyDescriptorType: Int?

    init(ssidBytes: [UInt8], verifier: PmkVerifier, rawLine: String, evidence: CaptureEvidence,
         bssid: [UInt8]? = nil, station: [UInt8]? = nil, keyDescriptorType: Int? = nil) {
        self.id = UUID()
        self.ssidBytes = ssidBytes
        self.verifier = verifier
        self.rawLine = rawLine
        self.evidence = evidence
        self.bssid = bssid
        self.station = station
        self.keyDescriptorType = keyDescriptorType
    }

    /// Printable network name, for display only — verification always uses `ssidBytes`.
    var ssid: String {
        String(decoding: ssidBytes, as: UTF8.self)
    }

    /// SSID as the user would write it in a file name, never blank.
    var displaySsid: String {
        ssid.isEmpty ? "(без имени)" : ssid
    }

    var bssidHex: String? { bssid.map(Hex.encode) }
    var stationHex: String? { station.map(Hex.encode) }
    var verifierHex: String { verifier.hex }

    /// WPA passphrases are 8..63 characters.
    func acceptsCandidate(_ candidate: String) -> Bool {
        candidate.count >= HandshakeTarget.wpaMinPassphrase &&
            candidate.count <= HandshakeTarget.wpaMaxPassphrase
    }

    /// Derives the key material once and hands it to the verifier.
    func verify(_ candidate: String) -> Bool {
        verify(candidateBytes: Array(candidate.utf8), ssid: ssidBytes)
    }

    /// Byte-level verification used by the engine, which normalises dictionary lines before it
    /// asks for the PMK. The passphrase length rule counts bytes, matching WPA's 8..63 octets.
    func verify(candidateBytes: [UInt8], ssid: [UInt8]) -> Bool {
        if candidateBytes.count < HandshakeTarget.wpaMinPassphrase ||
            candidateBytes.count > HandshakeTarget.wpaMaxPassphrase {
            return false
        }
        let pmk = Pbkdf2.derive(password: candidateBytes, salt: ssid)
        return verifier.accepts(pmk: pmk)
    }

    static let wpaMinPassphrase = 8
    static let wpaMaxPassphrase = 63

    // Identifiable uses id; Equatable compares value identity by the key material.
    static func == (lhs: HandshakeTarget, rhs: HandshakeTarget) -> Bool {
        lhs.ssidBytes == rhs.ssidBytes && lhs.verifierHex == rhs.verifierHex
    }
}