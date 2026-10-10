import Foundation

/// One EAPOL-Key message as it appears inside a captured frame, addressed by absolute offsets.
///
/// Layout of IEEE 802.11i, counted from the first byte of the EAPOL header:
/// ```
///  0  version (1)          01
///  1  type (1)             03
///  2  body length (2, LE)
///  4  key descriptor type (1)
///  5  key information (2, LE)
///  7  key length (2, LE)
///  9  replay counter (8)
/// 17  nonce (32)
/// 49  key IV (16)
/// 65  key RSC (16)
/// 81  key MIC (16)
/// 97  key data length (2, LE)
/// 99  key data
/// ```
enum EapolKey {
    static let headerLength = 4
    static let descriptorOffset = 4
    static let keyInfoOffset = 5
    static let keyLengthOffset = 7
    static let nonceOffset = 17
    static let nonceLength = 32
    static let micOffset = PmkVerifier.micOffset
    static let micLength = PmkVerifier.micLength
    static let keyDataLengthOffset = 97
    static let fixedLength = 99

    static let message1 = 1
    static let message2 = 2
    static let message3 = 3
    static let message4 = 4

    static func isEapolKey(_ frame: [UInt8]) -> Bool {
        guard frame.count >= fixedLength else { return false }
        guard byte(frame, 0) == 0x01, byte(frame, 1) == 0x03 else { return false }
        let descriptor = byte(frame, descriptorOffset)
        guard descriptor >= 1, descriptor <= 3 else { return false }
        let message = messageNumber(frame)
        return message >= message1 && message <= message4
    }

    static func keyInfo(_ frame: [UInt8]) -> Int {
        byte(frame, keyInfoOffset) | (byte(frame, keyInfoOffset + 1) << 8)
    }

    static func messageNumber(_ frame: [UInt8]) -> Int {
        (keyInfo(frame) >> 1) & 0x03
    }

    static func hasMic(_ frame: [UInt8]) -> Bool {
        (keyInfo(frame) & 0x20) != 0
    }

    static func isAck(_ frame: [UInt8]) -> Bool {
        (keyInfo(frame) & 0x10) != 0
    }

    /// The reference descriptor byte, which decides the MIC digest: 1 HMAC-MD5, 2 HMAC-SHA1.
    static func keyDescriptorType(_ frame: [UInt8]) -> Int {
        byte(frame, descriptorOffset)
    }

    static func replayCounter(_ frame: [UInt8]) -> UInt64 {
        var value: UInt64 = 0
        for i in stride(from: 7, through: 0, by: -1) {
            value = (value << 8) | UInt64(byte(frame, 9 + i))
        }
        return value
    }

    static func nonce(_ frame: [UInt8]) -> [UInt8] {
        Array(frame[nonceOffset..<nonceOffset + nonceLength])
    }

    static func mic(_ frame: [UInt8]) -> [UInt8] {
        Array(frame[micOffset..<micOffset + micLength])
    }

    /// Reads the SSID information element out of management frame body bytes.
    static func readBeaconSsid(_ body: [UInt8], from: Int) -> [UInt8]? {
        readSsidIe(body, from: from, limit: body.count)
    }

    private static func readSsidIe(_ data: [UInt8], from: Int, limit: Int) -> [UInt8]? {
        var i = from
        while i + 1 < limit {
            let id = byte(data, i)
            let length = byte(data, i + 1)
            let valueStart = i + 2
            let valueEnd = valueStart + length
            if valueEnd > data.count { return nil }
            if id == 0x00 && length > 0 {
                return Array(data[valueStart..<valueEnd])
            }
            i = valueEnd
        }
        return nil
    }

    static func byte(_ bytes: [UInt8], _ offset: Int) -> Int {
        Int(bytes[offset])
    }
}