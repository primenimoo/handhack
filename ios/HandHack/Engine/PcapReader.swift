import Foundation

/// The container header of a capture file, read before any frame is.
struct CaptureContainer {
    enum Kind { case pcap, pcapng, unknown }
    let kind: Kind
    let linkType: Int
    let bigEndian: Bool
    let nanoseconds: Bool
}

/// One link-layer frame lifted out of a capture container. `data` starts at the 802.11
/// header, with any radiotap preamble already removed.
struct CapturedFrame {
    let data: [UInt8]
    let timestampSeconds: UInt64
    let timestampFraction: UInt64
}

/// The container is readable but was not recorded as raw 802.11 traffic.
struct UnsupportedLinkType: Error {
    let linkType: Int
}

/// Reader for the two packet capture containers: classic libpcap and pcapng, walked by their
/// declared lengths so a truncated file stops instead of producing invented data.
enum PcapReader {
    static let LINKTYPE_IEEE802_11 = 105
    static let LINKTYPE_IEEE802_11_RADIOTAP = 127

    private static let maxFrameBytes = 65_535
    private static let maxBlockBytes = 16 * 1024 * 1024

    private struct Variant {
        let bigEndian: Bool
        let nanoseconds: Bool
    }

    /// The four classic magic numbers, keyed by how they read little-endian so no byte
    /// order comparison can creep in.
    private static func magicOf(_ magic: [UInt8]) -> Variant? {
        guard magic.count >= 4 else { return nil }
        switch le32(magic, 0) {
        case 0xA1B2C3D4: return Variant(bigEndian: false, nanoseconds: false)
        case 0xD4C3B2A1: return Variant(bigEndian: true, nanoseconds: false)
        case 0xA1B23C4D: return Variant(bigEndian: false, nanoseconds: true)
        case 0x4D3CB2A1: return Variant(bigEndian: true, nanoseconds: true)
        default: return nil
        }
    }

    /// Reads only the container header, so a format can be named before the file is walked.
    static func detect(_ head: [UInt8]) -> CaptureContainer {
        guard head.count >= 4 else {
            return CaptureContainer(kind: .unknown, linkType: -1, bigEndian: false, nanoseconds: false)
        }
        if let magic = magicOf(head) {
            guard head.count >= 24 else {
                return CaptureContainer(kind: .unknown, linkType: -1, bigEndian: magic.bigEndian, nanoseconds: magic.nanoseconds)
            }
            let linkType = Int(magic.bigEndian ? be32(head, 20) : le32(head, 20))
            return CaptureContainer(kind: .pcap, linkType: linkType, bigEndian: magic.bigEndian, nanoseconds: magic.nanoseconds)
        }
        if Array(head.prefix(4)) == [0x0A, 0x0D, 0x0D, 0x0A] {
            let bom = head.count >= 12 ? Array(head[8..<12]) : []
            let bigEndian = bom == [0x4D, 0x3C, 0x2B, 0x1A]
            return CaptureContainer(kind: .pcapng, linkType: -1, bigEndian: bigEndian, nanoseconds: false)
        }
        return CaptureContainer(kind: .unknown, linkType: -1, bigEndian: false, nanoseconds: false)
    }

    static func isCaptureContainer(_ head: [UInt8]) -> Bool {
        detect(head).kind != .unknown
    }

    /// Yields every frame in the capture, stopping at the first record that does not make sense.
    static func read(data: [UInt8]) throws -> [CapturedFrame] {
        switch detect(data).kind {
        case .pcap: return try readPcap(data, detect(data))
        case .pcapng: return try readPcapng(data)
        case .unknown: return []
        }
    }

    private static func readPcap(_ data: [UInt8], _ container: CaptureContainer) throws -> [CapturedFrame] {
        guard data.count >= 24 else { return [] }
        guard isSupportedLinkType(container.linkType) else {
            throw UnsupportedLinkType(linkType: container.linkType)
        }
        let bigEndian = container.bigEndian
        let nanoseconds = container.nanoseconds
        var frames = [CapturedFrame]()
        var offset = 24
        while offset + 16 <= data.count {
            let seconds = bigEndian ? be32(data, offset) : le32(data, offset)
            let fraction = bigEndian ? be32(data, offset + 4) : le32(data, offset + 4)
            let capturedLength = Int(bigEndian ? be32(data, offset + 8) : le32(data, offset + 8))
            if capturedLength <= 0 || capturedLength > maxFrameBytes { break }
            let payloadStart = offset + 16
            guard payloadStart + capturedLength <= data.count else { break }
            let payload = Array(data[payloadStart..<payloadStart + capturedLength])
            frames.append(CapturedFrame(
                data: stripRadiotap(payload, container.linkType),
                timestampSeconds: seconds,
                timestampFraction: nanoseconds ? fraction / 1000 : fraction))
            offset = payloadStart + capturedLength
        }
        return frames
    }

    private static func readPcapng(_ data: [UInt8]) throws -> [CapturedFrame] {
        var frames = [CapturedFrame]()
        var linkTypes = [Int: Int]()
        var bigEndian = false
        var offset = 0
        while offset + 8 <= data.count {
            let header = Array(data[offset..<offset + 8])
            if Array(header.prefix(4)) == [0x0A, 0x0D, 0x0D, 0x0A] {
                // Section header: the byte order magic decides how this block and every later
                // one are read.
                if offset + 12 <= data.count {
                    bigEndian = Array(data[offset + 8..<offset + 12]) == [0x4D, 0x3C, 0x2B, 0x1A]
                }
            }
            let blockType = Int(bigEndian ? be32(data, offset) : le32(data, offset))
            let blockLength = Int(bigEndian ? be32(data, offset + 4) : le32(data, offset + 4))
            if blockLength < 12 || blockLength > maxBlockBytes { break }
            let end = offset + blockLength
            guard end <= data.count else { break }
            let bodyStart = offset + 8
            let bodyEnd = end - 4 // trailing total length is not part of the body
            let body = Array(data[bodyStart..<bodyEnd])

            switch blockType {
            case 0x0A0D0D0A:
                if body.count >= 4 {
                    bigEndian = Array(body[0..<4]) == [0x4D, 0x3C, 0x2B, 0x1A]
                }
                linkTypes.removeAll()

            case 1:
                let id = linkTypes.count
                linkTypes[id] = body.count >= 2 ? u16(body, 0, bigEndian) : -1

            case 6:
                guard let linkType = linkTypes[Int(u32(body, 0, bigEndian))] else { break }
                guard isSupportedLinkType(linkType) else { break }
                let capturedLength = Int(u32(body, 12, bigEndian))
                if capturedLength <= 0 || capturedLength > maxFrameBytes { break }
                let endIndex = 20 + capturedLength
                guard endIndex <= body.count else { break }
                frames.append(CapturedFrame(
                    data: stripRadiotap(Array(body[20..<endIndex]), linkType),
                    timestampSeconds: 0, timestampFraction: 0))

            case 3:
                guard let linkType = linkTypes[0] else { break }
                guard isSupportedLinkType(linkType), !body.isEmpty else { break }
                frames.append(CapturedFrame(
                    data: stripRadiotap(body, linkType),
                    timestampSeconds: 0, timestampFraction: 0))

            default:
                break
            }
            offset = end
        }
        return frames
    }

    private static func isSupportedLinkType(_ linkType: Int) -> Bool {
        linkType == LINKTYPE_IEEE802_11 || linkType == LINKTYPE_IEEE802_11_RADIOTAP
    }

    /// Removes the radiotap preamble so callers always see the 802.11 header.
    private static func stripRadiotap(_ frame: [UInt8], _ linkType: Int) -> [UInt8] {
        guard linkType == LINKTYPE_IEEE802_11_RADIOTAP, frame.count >= 4 else { return frame }
        let length = le16(frame, 2)
        if length < 8 || length > frame.count { return frame }
        return Array(frame[length...])
    }

    private static func u16(_ data: [UInt8], _ offset: Int, bigEndian: Bool) -> Int {
        bigEndian ? Int(data[offset]) << 8 | Int(data[offset + 1]) : Int(data[offset]) | Int(data[offset + 1]) << 8
    }

    private static func u32(_ data: [UInt8], _ offset: Int, bigEndian: Bool) -> UInt32 {
        if bigEndian {
            return UInt32(data[offset]) << 24 | UInt32(data[offset + 1]) << 16 |
                UInt32(data[offset + 2]) << 8 | UInt32(data[offset + 3])
        }
        return UInt32(data[offset]) | UInt32(data[offset + 1]) << 8 |
            UInt32(data[offset + 2]) << 16 | UInt32(data[offset + 3]) << 24
    }

    private static func le16(_ data: [UInt8], _ offset: Int) -> Int {
        Int(data[offset]) | Int(data[offset + 1]) << 8
    }

    private static func be16(_ data: [UInt8], _ offset: Int) -> Int {
        Int(data[offset]) << 8 | Int(data[offset + 1])
    }

    private static func le32(_ data: [UInt8], _ offset: Int) -> Int {
        Int(data[offset]) | Int(data[offset + 1]) << 8 |
            Int(data[offset + 2]) << 16 | Int(data[offset + 3]) << 24
    }

    private static func be32(_ data: [UInt8], _ offset: Int) -> Int {
        Int(data[offset]) << 24 | Int(data[offset + 1]) << 16 |
            Int(data[offset + 2]) << 8 | Int(data[offset + 3])
    }
}