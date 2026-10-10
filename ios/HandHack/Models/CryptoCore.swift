import Foundation
import CommonCrypto

// MARK: - Hex

enum Hex {
    static func encode(_ bytes: [UInt8]) -> String {
        let digits: [Character] = Array("0123456789abcdef")
        var result = ""
        result.reserveCapacity(bytes.count * 2)
        for byte in bytes {
            result.append(digits[Int(byte >> 4)])
            result.append(digits[Int(byte & 0x0F)])
        }
        return result
    }

    /// Decodes a hex string, returning nil when it is not even-length valid hex.
    static func decode(_ hex: String) -> [UInt8]? {
        let trimmed = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed.count % 2 != 0 { return nil }
        var result = [UInt8]()
        result.reserveCapacity(trimmed.count / 2)
        var index = trimmed.startIndex
        while index < trimmed.endIndex {
            let high = digit(trimmed[index])
            let low = digit(trimmed[trimmed.index(after: index)])
            guard let h = high, let l = low else { return nil }
            result.append((h << 4) | l)
            index = trimmed.index(index, offsetBy: 2)
        }
        return result
    }

    private static func digit(_ character: Character) -> UInt8? {
        guard let ascii = character.asciiValue else { return nil }
        switch ascii {
        case 0x30...0x39: return ascii - 0x30
        case 0x41...0x46: return ascii - 0x41 + 10
        case 0x61...0x66: return ascii - 0x61 + 10
        default: return nil
        }
    }
}

// MARK: - HMAC-SHA1 and HMAC-MD5

enum Hmac {
    static func sha1(key: [UInt8], _ data: [UInt8]) -> [UInt8] {
        compute(kCCHmacAlgSHA1, key: key, data: data, length: 20)
    }

    static func md5(key: [UInt8], _ data: [UInt8]) -> [UInt8] {
        compute(kCCHmacAlgMD5, key: key, data: data, length: 16)
    }

    private static func compute(_ algorithm: Int, key: [UInt8], data: [UInt8], length: Int) -> [UInt8] {
        var mac = [UInt8](repeating: 0, count: length)
        key.withUnsafeBytes { keyBytes in
            data.withUnsafeBytes { dataBytes in
                CCHmac(CCHmacAlgorithm(algorithm),
                       keyBytes.baseAddress, keyBytes.count,
                       dataBytes.baseAddress, dataBytes.count,
                       &mac)
            }
        }
        return mac
    }
}

// MARK: - PBKDF2-HMAC-SHA1 (the WPA pairwise master key derivation)

enum Pbkdf2 {
    /// The number of rounds IEEE 802.11i fixes for WPA/WPA2-PSK.
    static let wpaIterations: Int = 4096
    static let keyLengthBytes = 32

    /// Derives 32 bytes of key material, exactly as `PBKDF2-HMAC-SHA1` does in the standard.
    /// The passphrase bytes are whatever the caller passes: a WPA passphrase lives in UTF-8,
    /// and folding byte values down to their low octet would break non-ASCII names.
    static func derive(password: [UInt8], salt: [UInt8], iterations: Int = wpaIterations) -> [UInt8] {
        precondition(iterations > 0, "iterations must be positive")
        var derived = [UInt8](repeating: 0, count: keyLengthBytes)
        let status = password.withUnsafeBytes { (pw: UnsafeRawBufferPointer) -> Int32 in
            salt.withUnsafeBytes { (sl: UnsafeRawBufferPointer) -> Int32 in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    pw.baseAddress?.assumingMemoryBound(to: Int8.self),
                    pw.count,
                    sl.baseAddress?.assumingMemoryBound(to: UInt8.self),
                    sl.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA1),
                    UInt32(iterations),
                    &derived,
                    derived.count
                )
            }
        }
        precondition(status == kCCSuccess, "PBKDF2 derivation failed")
        return derived
    }

    static func derive(passphrase: String, ssid: String) -> [UInt8] {
        derive(password: Array(passphrase.utf8), salt: Array(ssid.utf8))
    }
}

// MARK: - PRF-512 per IEEE 802.11i

/// The pseudorandom function of IEEE 802.11i that stretches the PMK into the PTK:
/// `PRF(K, label, seed) = HMAC(A(1) || seed) || HMAC(A(2) || seed) || …` where
/// `A(0) = label` and `A(i) = HMAC(A(i-1))`.
enum Prf {
    static let keyDescriptorTkip = 1
    static let keyDescriptorCcmp = 2

    static func isLegacyTkip(_ keyDescriptorType: Int) -> Bool {
        keyDescriptorType == keyDescriptorTkip
    }

    /// 512 bits of label-seeded output. WPA/TKIP uses HMAC-MD5, WPA2/CCMP HMAC-SHA1.
    static func derive(pmk: [UInt8], label: [UInt8], seed: [UInt8], keyDescriptorType: Int) -> [UInt8] {
        var output = [UInt8]()
        output.reserveCapacity(64)
        var block = label
        while output.count < 64 {
            block = hash(pmk: pmk, block, keyDescriptorType)
            var toHash = block
            toHash.append(contentsOf: seed)
            let chunk = hash(pmk: pmk, toHash, keyDescriptorType)
            output.append(contentsOf: chunk.prefix(64 - output.count))
        }
        return output
    }

    private static func hash(pmk: [UInt8], _ data: [UInt8], _ type: Int) -> [UInt8] {
        isLegacyTkip(type) ? Hmac.md5(key: pmk, data) : Hmac.sha1(key: pmk, data)
    }
}

// MARK: - PTK

/// The pairwise transient key of a four-way handshake:
/// `PTK = PRF(PMK, "Pairwise key expansion" || 0x00, Min(AA,SA) || Max(AA,SA) ||
/// Min(ANonce,SNonce) || Max(ANonce,SNonce))`.
/// Its first sixteen bytes are the key confirmation key, and that is what the MIC is built with.
enum Ptk {
    static let labelBytes: [UInt8] = Array("Pairwise key expansion".utf8) + [0]
    static let keyConfirmationLength = 16

    static func derive(
        pmk: [UInt8],
        authenticator: [UInt8],
        supplicant: [UInt8],
        authenticatorNonce: [UInt8],
        supplicantNonce: [UInt8],
        keyDescriptorType: Int
    ) -> [UInt8] {
        let addresses = ordered(authenticator, supplicant)
        let nonces = ordered(authenticatorNonce, supplicantNonce)
        var seed = addresses.0
        seed.append(contentsOf: addresses.1)
        seed.append(contentsOf: nonces.0)
        seed.append(contentsOf: nonces.1)
        return Prf.derive(pmk: pmk, label: labelBytes, seed: seed, keyDescriptorType: keyDescriptorType)
    }

    static func deriveKeyConfirmationKey(
        pmk: [UInt8],
        authenticator: [UInt8],
        supplicant: [UInt8],
        authenticatorNonce: [UInt8],
        supplicantNonce: [UInt8],
        keyDescriptorType: Int
    ) -> [UInt8] {
        let ptk = derive(pmk: pmk, authenticator: authenticator, supplicant: supplicant,
                         authenticatorNonce: authenticatorNonce, supplicantNonce: supplicantNonce,
                         keyDescriptorType: keyDescriptorType)
        return Array(ptk.prefix(keyConfirmationLength))
    }

    private static func ordered(_ a: [UInt8], _ b: [UInt8]) -> ([UInt8], [UInt8]) {
        compareUnsigned(a, b) <= 0 ? (a, b) : (b, a)
    }

    /// Lexicographic comparison of two byte strings, unsigned, shorter-is-smaller on a prefix.
    static func compareUnsigned(_ a: [UInt8], _ b: [UInt8]) -> Int {
        let shared = min(a.count, b.count)
        for i in 0..<shared {
            let diff = Int(a[i]) - Int(b[i])
            if diff != 0 { return diff }
        }
        return a.count - b.count
    }
}

// MARK: - PMKID

/// `PMKID = HMAC-SHA1-96(PMK, "PMK Name" || AA || SPA)`, the value `WPA*01` lines carry.
enum Pmkid {
    static let length = 16

    static func derive(pmk: [UInt8], authenticator: [UInt8], supplicant: [UInt8]) -> [UInt8] {
        var context = Array("PMK Name".utf8)
        context.append(contentsOf: authenticator)
        context.append(contentsOf: supplicant)
        return Array(Hmac.sha1(key: pmk, context).prefix(length))
    }
}

// MARK: - Constant-time comparison

enum ConstantTime {
    /// A comparison whose duration does not depend on where the first difference sits.
    static func equals(_ a: [UInt8], _ b: [UInt8]) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in a.indices {
            diff |= a[i] ^ b[i]
        }
        return diff == 0
    }
}