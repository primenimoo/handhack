import XCTest
@testable import HandHack

/// Ports the Android capture/crypto regression suite: the key schedule is checked against
/// vectors this project did not produce, then one synthetic handshake is written into every
/// supported container and read back.
final class CaptureFormatTests: XCTestCase {

    private let ssid = "HandHackLab"
    private let passphrase = "correcthorse1"
    private var ssidBytes: [UInt8] { Array(ssid.utf8) }

    private var authenticator: [UInt8] { Hex.decode("1831bfbfb954")! }
    private var supplicant: [UInt8] { Hex.decode("f854f67780cb")! }
    private var authenticatorNonce: [UInt8] {
        Hex.decode("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")!
    }
    private var supplicantNonce: [UInt8] {
        Hex.decode("2122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40")!
    }

    private var pmk: [UInt8] { Hex.decode("031cc89c376d8b215ccaff9a24b3849740ba2774bb58d128a7d29722999d7724")! }
    private var pmkid: [UInt8] { Hex.decode("34445a8ed3d4a876d2dc0586867c3d2d")! }
    private var keyConfirmationKey: [UInt8] { Hex.decode("53cc8933ef579773792e261c80bda167")! }

    // MARK: key schedule against external vectors

    func testPbkdf2MatchesPublishedStandardVector() {
        XCTAssertEqual(
            Hex.encode(Pbkdf2.derive(passphrase: "password", ssid: "IEEE")),
            "f42c6fc52df0ebef9ebb4b90b38a5f902e83fe1b135a70e23aed762e9710a12e")
    }

    func testPbkdf2MatchesOracleForLabNetwork() {
        XCTAssertEqual(Hex.encode(Pbkdf2.derive(password: Array(passphrase.utf8), salt: ssidBytes)),
                       Hex.encode(pmk))
    }

    func testNonAsciiPassphraseIsDerivedFromItsUtf8Bytes() {
        XCTAssertEqual(
            Hex.encode(Pbkdf2.derive(password: Array("пароль123".utf8), salt: ssidBytes)),
            "f6d2fa8312debf4945b0cca89479a045cb136ed836b000f16ce590c42142debd")
    }

    func testSameCharactersInAnotherEncodingAreADifferentPassphrase() {
        let windows1251: [UInt8] = [0xEF, 0xE0, 0xEB, 0xEE, 0xEB, 0xF2, 0x31, 0x32, 0x33]
        XCTAssertEqual(
            Hex.encode(Pbkdf2.derive(password: windows1251, salt: ssidBytes)),
            "c53439bbe710661719d84bea001c21bed877b1c90d48b2573dbfd6c72d96f1e3")
    }

    func testPtkExpansionReproducesOracleKeyConfirmationKey() {
        let kck = Ptk.deriveKeyConfirmationKey(
            pmk: pmk, authenticator: authenticator, supplicant: supplicant,
            authenticatorNonce: authenticatorNonce, supplicantNonce: supplicantNonce,
            keyDescriptorType: Prf.keyDescriptorCcmp)
        XCTAssertEqual(Hex.encode(kck), Hex.encode(keyConfirmationKey))
    }

    func testPtkExpansionIsIndependentOfAddressOrder() {
        let forward = Ptk.deriveKeyConfirmationKey(
            pmk: pmk, authenticator: authenticator, supplicant: supplicant,
            authenticatorNonce: authenticatorNonce, supplicantNonce: supplicantNonce,
            keyDescriptorType: Prf.keyDescriptorCcmp)
        let reversed = Ptk.deriveKeyConfirmationKey(
            pmk: pmk, authenticator: supplicant, supplicant: authenticator,
            authenticatorNonce: supplicantNonce, supplicantNonce: authenticatorNonce,
            keyDescriptorType: Prf.keyDescriptorCcmp)
        XCTAssertEqual(forward, reversed)
    }

    func testPmkidMatchesTruncatedHmacOfTheStandard() {
        XCTAssertEqual(Hex.encode(Pmkid.derive(pmk: pmk, authenticator: authenticator, supplicant: supplicant)),
                       Hex.encode(pmkid))
    }

    // MARK: one synthetic handshake

    func testSynthesisedMessage2AcceptsTheRightPassphraseAndNoOther() {
        let target = micTarget()
        XCTAssertEqual(target.evidence, .eapolMic)
        XCTAssertEqual(target.bssidHex, Hex.encode(authenticator))
        XCTAssertEqual(target.stationHex, Hex.encode(supplicant))
        XCTAssertTrue(target.verify(passphrase))
        XCTAssertFalse(target.verify("wrongpassword"))
        XCTAssertFalse(target.verify("short"))
        XCTAssertFalse(target.verify(String(repeating: "x", count: 64)))
    }

    func testHc22000PmkidRecordRoundTrips() {
        let line = "WPA*01*\(Hex.encode(pmkid))*\(Hex.encode(authenticator))*" +
            "\(Hex.encode(supplicant))*\(Hex.encode(ssidBytes))***"
        let loaded = CaptureLoader.load(data: Array(line.utf8), name: "capture.hc22000")
        XCTAssertEqual(loaded.format, .hc22000)
        XCTAssertEqual(loaded.targets.count, 1)
        XCTAssertEqual(loaded.targets[0].evidence, .pmkid)
        XCTAssertTrue(loaded.targets[0].verify(passphrase))
        XCTAssertFalse(loaded.targets[0].verify("wrongpassword"))
    }

    func testHc22000MicRecordRoundTrips() {
        let loaded = CaptureLoader.load(data: Array(micRecordLine().utf8), name: "capture.hc22000")
        XCTAssertEqual(loaded.format, .hc22000)
        XCTAssertEqual(loaded.targets[0].evidence, .eapolMic)
        XCTAssertTrue(loaded.targets[0].verify(passphrase))
        XCTAssertFalse(loaded.targets[0].verify("wrongpassword"))
    }

    func testHccapxRoundTrips() {
        let loaded = CaptureLoader.load(data: hccapxRecord(messageTwo()), name: "capture.hccapx")
        XCTAssertEqual(loaded.format, .hccapx)
        XCTAssertEqual(loaded.targets[0].ssid, ssid)
        XCTAssertEqual(loaded.targets[0].bssidHex, Hex.encode(authenticator))
        XCTAssertEqual(loaded.targets[0].stationHex, Hex.encode(supplicant))
        XCTAssertTrue(loaded.targets[0].verify(passphrase))
        XCTAssertFalse(loaded.targets[0].verify("wrongpassword"))
    }

    func testHccapRoundTrips() {
        let loaded = CaptureLoader.load(data: hccapRecord(messageTwo()), name: "capture.hccap")
        XCTAssertEqual(loaded.format, .hccap)
        XCTAssertTrue(loaded.targets[0].verify(passphrase))
        XCTAssertFalse(loaded.targets[0].verify("wrongpassword"))
    }

    func testClassicPcapRoundTripsWhateverTheFileName() {
        let bytes = pcapBytes()
        for name in ["capture.pcap", "actually-a-hccapx.cap"] {
            let loaded = CaptureLoader.load(data: bytes, name: name)
            XCTAssertEqual(loaded.format, .pcap, name)
            XCTAssertEqual(loaded.targets[0].ssid, ssid)
            XCTAssertTrue(loaded.targets[0].verify(passphrase))
            XCTAssertFalse(loaded.targets[0].verify("wrongpassword"))
        }
    }

    func testBigEndianClassicPcapIsRead() {
        let loaded = CaptureLoader.load(data: pcapBytes(bigEndian: true), name: "capture.pcap")
        XCTAssertEqual(loaded.format, .pcap)
        XCTAssertTrue(loaded.targets[0].verify(passphrase))
    }

    func testRadiotapPreambleIsRemovedBeforeParsing() {
        let loaded = CaptureLoader.load(
            data: pcapBytes(frames: radiotapFrames(), linkType: 127), name: "monitor.pcap")
        XCTAssertEqual(loaded.format, .pcap)
        XCTAssertEqual(loaded.targets.count, 1)
        XCTAssertTrue(loaded.targets[0].verify(passphrase))
    }

    func testPcapngRoundTrips() {
        let loaded = CaptureLoader.load(data: pcapngBytes(), name: "capture.pcapng")
        XCTAssertEqual(loaded.format, .pcapng)
        XCTAssertTrue(loaded.targets[0].verify(passphrase))
        XCTAssertFalse(loaded.targets[0].verify("wrongpassword"))
    }

    func testCaptureWithoutMessage2IsRefusedWithAReason() {
        let partial = pcapBytes(frames: [beacon(), dataFrame(messageOne())])
        let loaded = CaptureLoader.load(data: partial, name: "capture.pcap")
        XCTAssertTrue(loaded.targets.isEmpty)
        XCTAssertTrue(loaded.notes.contains { $0.contains("message 2") })
    }

    func testCaptureWithoutNetworkNameIsRefusedWithAReason() {
        let anonymous = pcapBytes(frames: [dataFrame(messageOne()), dataFrame(messageTwo())])
        let loaded = CaptureLoader.load(data: anonymous, name: "capture.pcap")
        XCTAssertTrue(loaded.targets.isEmpty)
        XCTAssertTrue(loaded.notes.contains { $0.contains("имя сети") })
    }

    func testEthernetCaptureIsRefusedWithAReason() {
        let loaded = CaptureLoader.load(
            data: pcapBytes(frames: frames(), linkType: 1), name: "capture.pcap")
        XCTAssertTrue(loaded.targets.isEmpty)
        XCTAssertTrue(loaded.notes.contains { $0.contains("802.11") })
    }

    func testTruncatedCaptureStopsCleanly() {
        let full = pcapBytes()
        let truncated = Array(full.prefix(full.count - 40))
        let loaded = CaptureLoader.load(data: truncated, name: "capture.pcap")
        XCTAssertTrue(loaded.targets.isEmpty)
        XCTAssertFalse(loaded.notes.isEmpty)
    }

    func testUnrelatedBinaryFileIsRefused() {
        let garbage = (0..<2048).map { UInt8(($0 * 31) % 251) }
        let loaded = CaptureLoader.load(data: garbage, name: "photo.png")
        XCTAssertTrue(loaded.targets.isEmpty)
        XCTAssertFalse(loaded.notes.isEmpty)
    }

    func testLegacyExportRoundTripsWithInlineAndPrecedingSsid() {
        let inline = "\(ssid):WPA*01\(Hex.encode(pmk))"
        let inlineLoaded = CaptureLoader.load(data: Array(inline.utf8), name: "export.txt")
        XCTAssertEqual(inlineLoaded.format, .legacyExport)
        XCTAssertTrue(inlineLoaded.targets[0].verify(passphrase))

        let preceding = "\(ssid)\nWPA*01\(Hex.encode(pmk))"
        let precedingLoaded = CaptureLoader.load(data: Array(preceding.utf8), name: "export.txt")
        XCTAssertEqual(precedingLoaded.format, .legacyExport)
        XCTAssertTrue(precedingLoaded.targets[0].verify(passphrase))
    }

    func testEveryExtensionIsOfferedAndEveryFormatClaimsOne() {
        let extensions = CaptureLoader.CaptureFormat.allCases.flatMap { $0.extensions }
        XCTAssertTrue(Set(["hc22000", "hccapx", "hccap", "pcap", "cap", "pcapng"]).isSubset(of: Set(extensions)))
        XCTAssertEqual(Set(extensions), CaptureLoader.CaptureFormat.allExtensions)
        XCTAssertTrue(CaptureLoader.CaptureFormat.allCases.allSatisfy { !$0.extensions.isEmpty && !$0.label.isEmpty })
    }

    func testContainerHeaderIsReadRatherThanGuessed() {
        let classic = PcapReader.detect(Array(pcapBytes().prefix(24)))
        XCTAssertEqual(classic.kind, .pcap)
        XCTAssertEqual(classic.linkType, 105)
        XCTAssertFalse(classic.bigEndian)
        XCTAssertFalse(classic.nanoseconds)

        let bigEndian = PcapReader.detect(Array(pcapBytes(bigEndian: true).prefix(24)))
        XCTAssertEqual(bigEndian.kind, .pcap)
        XCTAssertTrue(bigEndian.bigEndian)

        XCTAssertEqual(PcapReader.detect(Array(pcapngBytes().prefix(24))).kind, .pcapng)
        XCTAssertEqual(PcapReader.detect(Array("hello world, not a capture at all".utf8)).kind, .unknown)
    }

    // MARK: fixtures

    private func micTarget() -> HandshakeTarget {
        let message = messageTwo()
        return MicTarget.build(
            ssid: ssidBytes,
            mic: Array(message[PmkVerifier.micOffset..<PmkVerifier.micOffset + PmkVerifier.micLength]),
            authenticator: authenticator,
            supplicant: supplicant,
            authenticatorNonce: authenticatorNonce,
            supplicantNonce: supplicantNonce,
            keyDescriptorType: Prf.keyDescriptorCcmp,
            message: message,
            rawLabel: "test")!
    }

    private func messageOne() -> [UInt8] {
        eapolKey(keyInfo: keyInfo(keyType: 1, ack: true, secure: true),
                 nonce: authenticatorNonce, replayCounter: 1)
    }

    private func messageTwo() -> [UInt8] {
        let kck = Ptk.deriveKeyConfirmationKey(
            pmk: pmk, authenticator: authenticator, supplicant: supplicant,
            authenticatorNonce: authenticatorNonce, supplicantNonce: supplicantNonce,
            keyDescriptorType: Prf.keyDescriptorCcmp)
        let withoutMic = eapolKey(
            keyInfo: keyInfo(keyType: 2, install: true, mic: true, secure: true, encrypted: true),
            nonce: supplicantNonce, replayCounter: 2)
        let mic = Array(Hmac.sha1(key: kck, withoutMic).prefix(PmkVerifier.micLength))
        var withMic = withoutMic
        for i in 0..<mic.count { withMic[PmkVerifier.micOffset + i] = mic[i] }
        return withMic
    }

    private func keyInfo(keyType: Int, install: Bool = false, ack: Bool = false,
                         mic: Bool = false, secure: Bool = false, encrypted: Bool = false) -> Int {
        var value = 1
        value |= (keyType << 1)
        if install { value |= 0x08 }
        if ack { value |= 0x10 }
        if mic { value |= 0x20 }
        if secure { value |= 0x40 }
        if encrypted { value |= 0x80 }
        return value
    }

    private func eapolKey(keyInfo: Int, nonce: [UInt8], replayCounter: UInt64) -> [UInt8] {
        var frame = [UInt8](repeating: 0, count: EapolKey.fixedLength)
        frame[0] = 0x01
        frame[1] = 0x03
        frame[4] = UInt8(Prf.keyDescriptorCcmp)
        frame[5] = UInt8(keyInfo & 0xFF)
        frame[6] = UInt8((keyInfo >> 8) & 0xFF)
        for i in 0..<8 {
            frame[9 + i] = UInt8((replayCounter >> UInt64((7 - i) * 8)) & 0xFF)
        }
        for i in 0..<nonce.count { frame[EapolKey.nonceOffset + i] = nonce[i] }
        let bodyLength = frame.count - 4
        frame[2] = UInt8(bodyLength & 0xFF)
        frame[3] = UInt8((bodyLength >> 8) & 0xFF)
        return frame
    }

    private func beacon() -> [UInt8] {
        let header = macHeader(0x0080, broadcast, authenticator, authenticator)
        let fixed = [UInt8](repeating: 0, count: 12)
        let element: [UInt8] = [0x00, UInt8(ssidBytes.count)] + ssidBytes
        return header + fixed + element
    }

    private func dataFrame(_ eapol: [UInt8]) -> [UInt8] {
        macHeader(0x0208, supplicant, authenticator, authenticator) + snap + eapol
    }

    private func radiotapFrames() -> [[UInt8]] {
        [beacon(), dataFrame(messageOne()), dataFrame(messageTwo())].map { radiotap + $0 }
    }

    private func frames() -> [[UInt8]] {
        [beacon(), dataFrame(messageOne()), dataFrame(messageTwo())]
    }

    private func macHeader(_ frameControl: Int, _ a1: [UInt8], _ a2: [UInt8], _ a3: [UInt8]) -> [UInt8] {
        var header = [UInt8](repeating: 0, count: 24)
        header[0] = UInt8(frameControl & 0xFF)
        header[1] = UInt8((frameControl >> 8) & 0xFF)
        for i in 0..<6 { header[4 + i] = a1[i] }
        for i in 0..<6 { header[10 + i] = a2[i] }
        for i in 0..<6 { header[16 + i] = a3[i] }
        return header
    }

    private func pcapBytes(frames framesIn: [[UInt8]]? = nil, linkType: Int = 105,
                           bigEndian: Bool = false) -> [UInt8] {
        let framesToWrite = framesIn ?? frames()
        var out: [UInt8] = []

        func put(_ value: Int, _ width: Int) {
            for i in 0..<width {
                let shift = bigEndian ? (width - 1 - i) * 8 : i * 8
                out.append(UInt8((value >> shift) & 0xFF))
            }
        }

        out += bigEndian ? [0xA1, 0xB2, 0xC3, 0xD4] : [0xD4, 0xC3, 0xB2, 0xA1]
        put(2, 2)
        put(4, 2)
        put(0, 4)
        put(0, 4)
        put(0x00040000, 4)
        put(linkType, 4)

        for (index, frame) in framesToWrite.enumerated() {
            put(1_700_000_000 + index, 4)
            put(500_000, 4)
            put(frame.count, 4)
            put(frame.count, 4)
            out += frame
        }
        return out
    }

    private func pcapngBytes() -> [UInt8] {
        var out: [UInt8] = []
        func put32(_ value: Int) {
            for i in 0..<4 { out.append(UInt8((value >> (i * 8)) & 0xFF)) }
        }
        func put16(_ value: Int) {
            out.append(UInt8(value & 0xFF))
            out.append(UInt8((value >> 8) & 0xFF))
        }

        put32(0x0A0D0D0A)
        put32(28)
        out += [0x1A, 0x2B, 0x3C, 0x4D]
        put16(1)
        put16(0)
        put32(0)
        put32(0)
        put32(28)

        put32(1)
        put32(20)
        put16(105)
        put16(0)
        put32(0xFFFF)
        put32(20)

        for (index, frame) in frames().enumerated() {
            let padded = ((frame.count + 3) / 4) * 4
            let total = 32 + padded
            put32(6)
            put32(total)
            put32(0)
            put32(0)
            put32(1_700_000_000 + index)
            put32(frame.count)
            put32(frame.count)
            out += frame
            out += [UInt8](repeating: 0, count: padded - frame.count)
            put32(total)
        }
        return out
    }

    private func micRecordLine() -> String {
        let message = messageTwo()
        let mic = Array(message[PmkVerifier.micOffset..<PmkVerifier.micOffset + PmkVerifier.micLength])
        return "WPA*02*\(Hex.encode(mic))*\(Hex.encode(authenticator))*\(Hex.encode(supplicant))" +
            "*\(Hex.encode(ssidBytes))*\(Hex.encode(authenticatorNonce))*\(Hex.encode(message))*00"
    }

    private func hccapxRecord(_ message: [UInt8]) -> [UInt8] {
        var record = [UInt8](repeating: 0, count: HccapxReader.recordLength)
        putLe32(&record, 0, 0x58504348)
        putLe32(&record, 4, 4)
        record[8] = 0x02
        record[9] = UInt8(ssidBytes.count)
        for i in 0..<ssidBytes.count { record[10 + i] = ssidBytes[i] }
        record[42] = UInt8(Prf.keyDescriptorCcmp)
        for i in 0..<PmkVerifier.micLength { record[43 + i] = message[PmkVerifier.micOffset + i] }
        for i in 0..<6 { record[59 + i] = authenticator[i] }
        for i in 0..<32 { record[65 + i] = authenticatorNonce[i] }
        for i in 0..<6 { record[97 + i] = supplicant[i] }
        for i in 0..<32 { record[103 + i] = supplicantNonce[i] }
        putLe16(&record, 135, message.count)
        for i in 0..<message.count { record[137 + i] = message[i] }
        return record
    }

    private func hccapRecord(_ message: [UInt8]) -> [UInt8] {
        var record = [UInt8](repeating: 0, count: HccapReader.recordLength)
        for i in 0..<ssidBytes.count { record[i] = ssidBytes[i] }
        for i in 0..<6 { record[36 + i] = authenticator[i] }
        for i in 0..<6 { record[42 + i] = supplicant[i] }
        for i in 0..<32 { record[48 + i] = authenticatorNonce[i] }
        for i in 0..<32 { record[80 + i] = supplicantNonce[i] }
        for i in 0..<message.count { record[112 + i] = message[i] }
        putLe32(&record, 368, message.count)
        putLe32(&record, 372, Prf.keyDescriptorCcmp)
        for i in 0..<PmkVerifier.micLength { record[376 + i] = message[PmkVerifier.micOffset + i] }
        return record
    }

    private func putLe16(_ target: inout [UInt8], _ offset: Int, _ value: Int) {
        target[offset] = UInt8(value & 0xFF)
        target[offset + 1] = UInt8((value >> 8) & 0xFF)
    }

    private func putLe32(_ target: inout [UInt8], _ offset: Int, _ value: Int) {
        for i in 0..<4 { target[offset + i] = UInt8((value >> (i * 8)) & 0xFF) }
    }

    private var broadcast: [UInt8] { [UInt8](repeating: 0xFF, count: 6) }
    private var radiotap: [UInt8] { [0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00] }
    private var snap: [UInt8] { [0xAA, 0xAA, 0x03, 0x00, 0x00, 0x00, 0x88, 0x8E] }
}