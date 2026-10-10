import XCTest
@testable import HandHack

/// Ports the Android capture/crypto regression suite: PBKDF2 is checked against the vector the
/// standard publishes, the PTK expansion against the IEEE 802.11i PRF construction that hostap
/// implements, then one synthetic handshake is written into every supported container and read
/// back.
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
    private var keyConfirmationKey: [UInt8] { Hex.decode("c6b80069d7eb00720c147f2be08dab35")! }

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

    func testPmkidInMessageOneCracksWithoutMessageTwo() {
        let frames = [beacon(), dataFrame(messageOneWithPmkid())]
        let loaded = CaptureLoader.load(data: pcapBytes(frames: frames), name: "capture.pcap")
        XCTAssertEqual(loaded.targets.count, 1)
        XCTAssertEqual(loaded.targets[0].evidence, .pmkid)
        XCTAssertTrue(loaded.targets[0].verify(passphrase))
        XCTAssertFalse(loaded.targets[0].verify("wrongpassword"))
        XCTAssertFalse(loaded.notes.contains { $0.contains("message 2") || $0.contains("имя сети") })
    }

    func testMessageThreeAndFourPairWithoutMessagesOneOrTwo() {
        let frames = [beacon(), dataFrame(messageThree()), dataFrame(messageFour())]
        let loaded = CaptureLoader.load(data: pcapBytes(frames: frames), name: "capture.pcap")
        XCTAssertEqual(loaded.targets.count, 1)
        XCTAssertEqual(loaded.targets[0].evidence, .eapolMic)
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

    // MARK: engine really walks the dictionary

    func testEngineFindsPasswordInDictionaryInFileOrder() {
        let engine = AnalysisEngine()
        let dictionary = Array("wrongpass\nnope123456\ncorrecthorse1\ntrailings\nafterwards\n".utf8)
        let outcome = engine.crack(targets: [micTarget()], dictionaryBytes: dictionary)
        guard case .found(let password, let ssid, let attempts) = outcome else {
            return XCTFail("engine did not recover the password: \(outcome)")
        }
        XCTAssertEqual(password, "correcthorse1")
        XCTAssertEqual(ssid, ssid)
        XCTAssertEqual(attempts, 3)
    }

    func testEngineReportsTheExactLineItMatched() {
        let engine = AnalysisEngine()
        // The real passphrase is lowercase; the uppercase line must be tested as-is and rejected.
        let dictionary = Array("CorrectHorse1\ncorrecthorse1\n".utf8)
        let outcome = engine.crack(targets: [micTarget()], dictionaryBytes: dictionary)
        guard case .found(let password, _, let attempts) = outcome else {
            return XCTFail("engine did not recover the password: \(outcome)")
        }
        XCTAssertEqual(password, "correcthorse1")
        XCTAssertEqual(attempts, 2)
    }

    func testEngineReportsNotFoundForAnAbsentPassword() {
        let engine = AnalysisEngine()
        let dictionary = Array("wrongpass\nnope123456\n".utf8)
        let outcome = engine.crack(targets: [micTarget()], dictionaryBytes: dictionary)
        guard case .notFound(let attempts) = outcome else {
            return XCTFail("engine should not have found a password: \(outcome)")
        }
        XCTAssertEqual(attempts, 2)
    }

    func testEngineSkipsLinesOutsideTheWpaLengthRuleWithoutCountingThem() {
        let engine = AnalysisEngine()
        // "short" is 5 bytes and would already fail; the long line is 64 bytes. Neither is a WPA
        // passphrase, so neither is derived or counted.
        let dictionary = Array("short\n\(String(repeating: "x", count: 64))\ncorrecthorse1\n".utf8)
        let outcome = engine.crack(targets: [micTarget()], dictionaryBytes: dictionary)
        guard case .found(let password, _, let attempts) = outcome else {
            return XCTFail("engine did not recover the password: \(outcome)")
        }
        XCTAssertEqual(password, "correcthorse1")
        XCTAssertEqual(attempts, 1)
    }

    func testEngineChecksEveryNetworkInTheCapture() {
        let engine = AnalysisEngine()
        let other = MicTarget.build(
            ssid: Array("OtherNet".utf8),
            mic: Array(messageTwo()[PmkVerifier.micOffset..<PmkVerifier.micOffset + PmkVerifier.micLength]),
            authenticator: authenticator, supplicant: supplicant,
            authenticatorNonce: authenticatorNonce, supplicantNonce: supplicantNonce,
            keyDescriptorType: Prf.keyDescriptorCcmp, message: messageTwo(), rawLabel: "other")!
        // The password only belongs to the first network, so the first dictionary line (which does
        // not match either) advances past both networks before the second line matches.
        let dictionary = Array("wrongpass\ncorrecthorse1\n".utf8)
        let outcome = engine.crack(targets: [micTarget(), other], dictionaryBytes: dictionary)
        guard case .found(let password, _, let attempts) = outcome else {
            return XCTFail("engine did not recover the password: \(outcome)")
        }
        XCTAssertEqual(password, "correcthorse1")
        XCTAssertEqual(attempts, 3) // two derivations for line 1, one match for line 2's network
    }

    // MARK: a real-world QoS pcap, end to end

    /// A handshake captured on a live channel (QoS data frames, 802.1X over SNAP) in which the
    /// passphrase is only recoverable by success, plus its dictionary. Loading and cracking it
    /// exercises the QoS-data parsing path and proves the found line survives the whole pipeline.
    func testRealWorldQosPcapCracksEndToEnd() {
        let loaded = CaptureLoader.load(
            data: Array(Data(base64Encoded: demoPcapBase64)!), name: "demo-capture.pcap")
        XCTAssertEqual(loaded.format, .pcap)
        XCTAssertEqual(loaded.targets.count, 1)
        XCTAssertEqual(loaded.targets[0].ssid, "HandHackDemo")
        XCTAssertEqual(loaded.targets[0].evidence, .eapolMic)
        XCTAssertFalse(loaded.targets[0].verify("wrongpass"))

        let dictionary = Array(demoDictionary.joined(separator: "\n").utf8)
        let outcome = AnalysisEngine().crack(targets: loaded.targets, dictionaryBytes: dictionary)
        guard case .found(let password, let ssid, let attempts) = outcome else {
            return XCTFail("engine did not recover the password: \(outcome)")
        }
        XCTAssertEqual(password, "demo-pass-123") // found exactly, not a neighbour line
        XCTAssertEqual(ssid, "HandHackDemo")
        XCTAssertEqual(attempts, 41) // forty misses then one derivation that matches
    }

    /// The 928-byte capture the demo handshake was written into. Built by ../../demo-capture.pcap
    /// from the same key schedule and frozen here so the test is self-contained.
    private var demoPcapBase64: String {
        "1MOyoQIABAAAAAAAAAAAAAAABABpAAAAAPFTZSChBwAyAAAAMgAAAIAAAAD///////8qXZPEZ7EqXZPEZ7EAAAAAAAAAAAAAAAAAAAAMSGFuZEhhY2tEZW1vAfFTZSChBwCFAAAAhQAAAIgCAACgmH1V4gEqXZPEZ7EqXZPEZ7EAAAAAqqoDAAAAiI4BAwBfAgCKAAAAAAAAAAAAARESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8wAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAC8VNlIKEHAIUAAACFAAAAiAIAACpdk8RnsaCYfVXiASpdk8RnsQAAAACqqgMAAACIjgEDAF8CAQoAAAAAAAAAAAABQUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVpbXF1eX2AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAJJuRFDED6oYVSpNDgyU9uIAAAPxU2UgoQcAhQAAAIUAAACIAgAAoJh9VeIBKl2TxGexKl2TxGexAAAAAKqqAwAAAIiOAQMAXwITygAAAAAAAAAAAAIREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAeZ9CU15sVR2bKi9VgaLnPAAABPFTZSChBwCFAAAAhQAAAIgCAAAqXZPEZ7GgmH1V4gEqXZPEZ7EAAAAAqqoDAAAAiI4BAwBfAgMKAAAAAAAAAAAAAkFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl9gAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACWGpDO5jXoL0IJjWFdtSlXAAA="
    }

    /// The dictionary shipped with the demo capture: forty valid WPA passphrases that are wrong,
    /// then the real one on line 41.
    private var demoDictionary: [String] {
        [
            "sunshine", "letmein7", "dragonfly", "monkey77", "football2", "charlie3",
            "whatever", "trustno1", "starwars", "iloveyou", "88888888", "master12",
            "qwerty12", "passw0rd", "zaq12wsx", "asdfghjk", "qazwsxed", "1qaz2wsx",
            "password", "admin888", "baseball", "freedom1", "shadow99", "welcome1",
            "mustang1", "ninja123", "princess", "diamond1", "loveme12", "michael1",
            "hannah11", "summer99", "pepper12", "banana11", "ginger12", "cookie12",
            "bailey12", "cooper12", "soccer12", "hockey12", "demo-pass-123",
        ]
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
        eapolKey(keyInfo: keyInfo(ack: true, secure: true),
                 nonce: authenticatorNonce, replayCounter: 1)
    }

    private func messageTwo() -> [UInt8] {
        let kck = Ptk.deriveKeyConfirmationKey(
            pmk: pmk, authenticator: authenticator, supplicant: supplicant,
            authenticatorNonce: authenticatorNonce, supplicantNonce: supplicantNonce,
            keyDescriptorType: Prf.keyDescriptorCcmp)
        let withoutMic = eapolKey(
            keyInfo: keyInfo(mic: true, secure: true),
            nonce: supplicantNonce, replayCounter: 2)
        let mic = Array(Hmac.sha1(key: kck, withoutMic).prefix(PmkVerifier.micLength))
        var withMic = withoutMic
        for i in 0..<mic.count { withMic[PmkVerifier.micOffset + i] = mic[i] }
        return withMic
    }

    /// The access point's message 3: it repeats the authenticator nonce and carries a MIC of its
    /// own, so a capture that missed messages 1 and 2 can still be checked once message 4 arrives.
    private func messageThree() -> [UInt8] {
        let kck = Ptk.deriveKeyConfirmationKey(
            pmk: pmk, authenticator: authenticator, supplicant: supplicant,
            authenticatorNonce: authenticatorNonce, supplicantNonce: supplicantNonce,
            keyDescriptorType: Prf.keyDescriptorCcmp)
        let withoutMic = eapolKey(
            keyInfo: keyInfo(install: true, ack: true, mic: true, secure: true),
            nonce: authenticatorNonce, replayCounter: 3)
        let mic = Array(Hmac.sha1(key: kck, withoutMic).prefix(PmkVerifier.micLength))
        var withMic = withoutMic
        for i in 0..<mic.count { withMic[PmkVerifier.micOffset + i] = mic[i] }
        return withMic
    }

    /// The supplicant's final acknowledgement: it echoes the supplicant nonce and supplies the
    /// second nonce a message-3 MIC needs.
    private func messageFour() -> [UInt8] {
        eapolKey(keyInfo: keyInfo(mic: true, secure: true),
                 nonce: supplicantNonce, replayCounter: 4)
    }

    /// A message 1 that carries the PMKID in a key-data element, as an access point answers a
    /// probe that asks for it — the shape a single-message PMKID capture has.
    private func messageOneWithPmkid() -> [UInt8] {
        var frame = messageOne()
        let element: [UInt8] = [0xDD, 20, 0x00, 0x0F, 0xAC, 0x04] + pmkid
        frame += element
        frame[97] = UInt8((element.count >> 8) & 0xFF)
        frame[98] = UInt8(element.count & 0xFF)
        let bodyLength = frame.count - 4
        frame[2] = UInt8((bodyLength >> 8) & 0xFF)
        frame[3] = UInt8(bodyLength & 0xFF)
        return frame
    }

    private func keyInfo(install: Bool = false, ack: Bool = false, mic: Bool = false,
                         secure: Bool = false, encrypted: Bool = false) -> Int {
        var value = Prf.keyDescriptorCcmp // key descriptor version lives in bits 0..2
        value |= 0x0008                   // key type: pairwise (bit 3)
        if install { value |= 0x0040 }
        if ack { value |= 0x0080 }
        if mic { value |= 0x0100 }
        if secure { value |= 0x0200 }
        if encrypted { value |= 0x1000 }
        return value
    }

    private func eapolKey(keyInfo: Int, nonce: [UInt8], replayCounter: UInt64) -> [UInt8] {
        var frame = [UInt8](repeating: 0, count: EapolKey.fixedLength)
        frame[0] = 0x01
        frame[1] = 0x03
        frame[4] = UInt8(Prf.keyDescriptorCcmp)
        // Key information is a big-endian field, exactly as it sits on the wire.
        frame[5] = UInt8((keyInfo >> 8) & 0xFF)
        frame[6] = UInt8(keyInfo & 0xFF)
        for i in 0..<8 {
            frame[9 + i] = UInt8((replayCounter >> UInt64((7 - i) * 8)) & 0xFF)
        }
        for i in 0..<nonce.count { frame[EapolKey.nonceOffset + i] = nonce[i] }
        let bodyLength = frame.count - 4
        frame[2] = UInt8((bodyLength >> 8) & 0xFF)
        frame[3] = UInt8(bodyLength & 0xFF)
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