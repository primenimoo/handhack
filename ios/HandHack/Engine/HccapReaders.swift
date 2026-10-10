import Foundation

/// Reader for `.hccapx`, the hashcat container of fixed 393-byte records.
/// Layout: signature u32 "HCPX", version u32, message_pair u8, essid_len u8, essid 32,
/// keyver u8, keymic 16, mac_ap 6, nonce_ap 32, mac_sta 6, nonce_sta 32, eapol_len u16, eapol 256.
enum HccapxReader {
    static let recordLength = 393
    private static let signature: UInt32 = 0x58504348
    private static let version: UInt32 = 4

    static func sniff(_ head: [UInt8]) -> Bool {
        guard head.count >= 8 else { return false }
        return le32(head, 0) == signature && le32(head, 4) == version
    }

    static func parse(data: [UInt8]) -> [HandshakeTarget] {
        var targets: [HandshakeTarget] = []
        var offset = 0
        while offset + recordLength <= data.count {
            let record = Array(data[offset..<offset + recordLength])
            if !sniff(record) { break }
            if let target = target(record) { targets.append(target) }
            offset += recordLength
        }
        return targets
    }

    private static func target(_ record: [UInt8]) -> HandshakeTarget? {
        let essidLength = byte(record, 9)
        if essidLength == 0 || essidLength > 32 { return nil }
        let essid = Array(record[10..<(10 + essidLength)])

        let eapolLength = le16(record, 135)
        if eapolLength < EapolKey.fixedLength || eapolLength > 256 { return nil }
        let eapol = Array(record[137..<(137 + eapolLength)])

        return MicTarget.build(
            ssid: essid,
            mic: Array(record[43..<43 + PmkVerifier.micLength]),
            authenticator: Array(record[59..<65]),
            supplicant: Array(record[97..<103]),
            authenticatorNonce: Array(record[65..<65 + EapolKey.nonceLength]),
            supplicantNonce: Array(record[103..<103 + EapolKey.nonceLength]),
            keyDescriptorType: byte(record, 42),
            message: eapol,
            rawLabel: "hccapx \(Hex.encode(essid))")
    }

    private static func byte(_ bytes: [UInt8], _ offset: Int) -> Int { Int(bytes[offset]) }

    private static func le16(_ bytes: [UInt8], _ offset: Int) -> Int {
        Int(bytes[offset]) | Int(bytes[offset + 1]) << 8
    }

    private static func le32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
        UInt32(bytes[offset]) | UInt32(bytes[offset + 1]) << 8 |
            UInt32(bytes[offset + 2]) << 16 | UInt32(bytes[offset + 3]) << 24
    }
}

/// Reader for `.hccap`, the container hashcat v3.5 and lower used. It carries no magic, only
/// fixed 392-byte records with native-endian `int` fields, so the layout is the only way to
/// tell it apart from other binaries.
/// Layout: essid 36 (NUL terminated), mac1/authenticator 6, mac2/supplicant 6, nonce1 32,
/// nonce2 32, eapol 256, eapol_size int32, keyver int32, keymic 16.
enum HccapReader {
    static let recordLength = 392
    private static let essidField = 36
    private static let minimumSniffBytes = 48
    private static let zeroAddress = [UInt8](repeating: 0, count: 6)

    /// A cheap plausibility test, because the format has no signature: the network name must be
    /// printable and NUL terminated, and both addresses must be set.
    static func sniff(_ head: [UInt8]) -> Bool {
        guard head.count >= minimumSniffBytes else { return false }
        guard let nameLength = nameLength(head) else { return false }
        for i in 0..<nameLength {
            let c = Int(head[i])
            if c < 0x20 || c == 0x7F { return false }
        }
        return !Array(head[36..<42]).elementsEqual(zeroAddress) &&
            !Array(head[42..<48]).elementsEqual(zeroAddress)
    }

    static func parse(data: [UInt8]) -> [HandshakeTarget] {
        var targets: [HandshakeTarget] = []
        var offset = 0
        while offset + recordLength <= data.count {
            let record = Array(data[offset..<offset + recordLength])
            if !sniff(record) { break }
            if let target = target(record) { targets.append(target) }
            offset += recordLength
        }
        return targets
    }

    /// Length of the NUL terminated network name, or nil when it is missing or overlong.
    private static func nameLength(_ record: [UInt8]) -> Int? {
        var length = 0
        while length < essidField && record[length] != 0 { length += 1 }
        if length == 0 || length >= essidField { return nil }
        return length
    }

    private static func target(_ record: [UInt8]) -> HandshakeTarget? {
        guard let nameLength = nameLength(record) else { return nil }
        let essid = Array(record[0..<nameLength])

        // The two int fields are native-endian. Every capture tool in use writes little endian,
        // and the sanity limits reject a byte-swapped read, so a big-endian file is picked up
        // by the second attempt instead of being silently misread.
        for bigEndian in [false, true] {
            let declared = bigEndian ? be32(record, 368) : le32(record, 368)
            let eapolLength = Int(declared)
            if eapolLength < EapolKey.fixedLength || eapolLength > 256 { continue }
            let keyver = Int(bigEndian ? be32(record, 372) : le32(record, 372))
            if keyver != Prf.keyDescriptorTkip && keyver != Prf.keyDescriptorCcmp { continue }
            let eapol = Array(record[112..<(112 + eapolLength)])

            if let target = MicTarget.build(
                ssid: essid,
                mic: Array(record[376..<376 + PmkVerifier.micLength]),
                authenticator: Array(record[36..<42]),
                supplicant: Array(record[42..<48]),
                authenticatorNonce: Array(record[48..<48 + EapolKey.nonceLength]),
                supplicantNonce: Array(record[80..<80 + EapolKey.nonceLength]),
                keyDescriptorType: keyver,
                message: eapol,
                rawLabel: "hccap \(Hex.encode(essid))") {
                return target
            }
        }
        return nil
    }

    private static func be32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
        UInt32(bytes[offset]) << 24 | UInt32(bytes[offset + 1]) << 16 |
            UInt32(bytes[offset + 2]) << 8 | UInt32(bytes[offset + 3])
    }

    private static func le32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
        UInt32(bytes[offset]) | UInt32(bytes[offset + 1]) << 8 |
            UInt32(bytes[offset + 2]) << 16 | UInt32(bytes[offset + 3]) << 24
    }
}