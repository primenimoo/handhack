import XCTest
@testable import HandHack

/// Proves the 1.5 converter: every handshake the app parsed can be written back out as a
/// standalone file, and the app's own readers accept that file unchanged. Also checks the smart
/// analysis counts the work a run implies.
final class HandshakeConverterTests: XCTestCase {

    private let ssid = "ConvertLab"
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

    // MARK: PMKID round trip

    func testPmkidTargetConvertsToHc22000AndReadsBack() {
        let target = HandshakeTarget(
            ssidBytes: ssidBytes,
            verifier: .pmkid(pmkid: pmkid, authenticator: authenticator, supplicant: supplicant),
            rawLine: "", evidence: .pmkid, bssid: authenticator, station: supplicant)

        let text = HandshakeConverter.convert(targets: [target])
        XCTAssertTrue(text.hasPrefix("WPA*01*"))

        let loaded = CaptureLoader.load(data: Array(text.utf8), name: "converted.hc22000")
        XCTAssertEqual(loaded.format, .hc22000)
        XCTAssertEqual(loaded.targets.count, 1)
        XCTAssertEqual(loaded.targets[0].evidence, .pmkid)
        XCTAssertEqual(loaded.targets[0].verifierHex, target.verifierHex)
        XCTAssertEqual(loaded.targets[0].ssidBytes, ssidBytes)
    }

    // MARK: EAPOL MIC round trip

    func testMicTargetConvertsToHc22000AndReadsBack() {
        let message = eapolMessageTwo()
        let mic = Array(message[PmkVerifier.micOffset..<PmkVerifier.micOffset + PmkVerifier.micLength])
        let target = HandshakeTarget(
            ssidBytes: ssidBytes,
            verifier: .mic(message: message, authenticator: authenticator, supplicant: supplicant,
                           authenticatorNonce: authenticatorNonce, supplicantNonce: supplicantNonce,
                           keyDescriptorType: Prf.keyDescriptorCcmp, expected: mic),
            rawLine: "", evidence: .eapolMic, bssid: authenticator, station: supplicant,
            keyDescriptorType: Prf.keyDescriptorCcmp)

        let text = HandshakeConverter.convert(targets: [target])
        XCTAssertTrue(text.hasPrefix("WPA*02*"))

        let loaded = CaptureLoader.load(data: Array(text.utf8), name: "converted.hc22000")
        XCTAssertEqual(loaded.targets.count, 1)
        XCTAssertEqual(loaded.targets[0].evidence, .eapolMic)
        XCTAssertEqual(loaded.targets[0].verifierHex, target.verifierHex)
    }

    // MARK: direct PMK falls back to the compact export

    func testPmkTargetConvertsToCompactExportAndReadsBack() {
        let target = HandshakeTarget(
            ssidBytes: ssidBytes, verifier: .pmk(bytes: pmk), rawLine: "", evidence: .pmk)

        let text = HandshakeConverter.convert(targets: [target])
        XCTAssertTrue(text.hasPrefix("\(ssid):WPA*01"))

        let loaded = CaptureLoader.load(data: Array(text.utf8), name: "converted.handshake")
        XCTAssertEqual(loaded.format, .legacyExport)
        XCTAssertEqual(loaded.targets.count, 1)
        XCTAssertEqual(loaded.targets[0].verifierHex, Hex.encode(pmk))
    }

    // MARK: file naming follows the content

    func testExportFileNameFollowsContentType() {
        let pmkTarget = HandshakeTarget(
            ssidBytes: ssidBytes, verifier: .pmk(bytes: pmk), rawLine: "", evidence: .pmk)
        let pmkidTarget = HandshakeTarget(
            ssidBytes: ssidBytes,
            verifier: .pmkid(pmkid: pmkid, authenticator: authenticator, supplicant: supplicant),
            rawLine: "", evidence: .pmkid)

        XCTAssertEqual(HandshakeConverter.exportFileName(for: [pmkTarget], originalFile: "cap.pcap"),
                       "cap.handshake")
        XCTAssertEqual(HandshakeConverter.exportFileName(for: [pmkidTarget], originalFile: "cap.pcap"),
                       "cap.hc22000")
        XCTAssertEqual(HandshakeConverter.exportFileName(for: [pmkTarget, pmkidTarget],
                                                         originalFile: "cap"), "cap.hc22000")
    }

    // MARK: smart analysis

    func testSmartPlanCountsWorkAndRecommendsAPace() {
        let targetA = HandshakeTarget(
            ssidBytes: Array("Alpha".utf8), verifier: .pmk(bytes: pmk), rawLine: "", evidence: .pmk)
        let targetB = HandshakeTarget(
            ssidBytes: Array("Bravo".utf8),
            verifier: .pmkid(pmkid: pmkid, authenticator: authenticator, supplicant: supplicant),
            rawLine: "", evidence: .pmkid)
        let dictionary = AttackRunner.DictionaryInput(name: "small",
                                                      bytes: Array("one\ntwo\nthree\n".utf8))

        let plan = SmartAnalysis.plan(targets: [targetA, targetA, targetB],
                                      dictionaries: [dictionary])
        XCTAssertEqual(plan.records, 3)
        XCTAssertEqual(plan.uniqueSsids, 2)
        XCTAssertEqual(plan.candidates, 3)
        XCTAssertEqual(plan.derivations, 6)
        XCTAssertEqual(plan.recommendedSpeed, .careful)
        XCTAssertTrue(plan.evidence.contains(.pmk))
        XCTAssertTrue(plan.evidence.contains(.pmkid))
        XCTAssertFalse(plan.summaryLines.isEmpty)
    }

    func testSmartPlanScalesThePaceWithTheWorkload() {
        var huge = Data()
        for i in 0..<40_000 { huge.append(contentsOf: Array("candidate\(i)\n".utf8)) }
        let dictionary = AttackRunner.DictionaryInput(name: "big", bytes: Array(huge))
        let alpha = HandshakeTarget(
            ssidBytes: Array("Alpha".utf8), verifier: .pmk(bytes: pmk), rawLine: "", evidence: .pmk)
        let bravo = HandshakeTarget(
            ssidBytes: Array("Bravo".utf8), verifier: .pmk(bytes: pmk), rawLine: "", evidence: .pmk)

        // Forty thousand lines across two networks is eighty thousand derivations, past the
        // careful boundary, so the plan should pick a faster profile.
        let plan = SmartAnalysis.plan(targets: [alpha, bravo], dictionaries: [dictionary])
        XCTAssertEqual(plan.derivations, 80_000)
        XCTAssertGreaterThan(plan.derivations, 60_000)
        XCTAssertNotEqual(plan.recommendedSpeed, .careful)
    }

    // MARK: fixtures

    /// A structurally valid EAPOL-Key message 2: no ACK, MIC set, secure set, key descriptor
    /// CCMP, with the supplicant nonce in place. Same shape the format tests build.
    private func eapolMessageTwo() -> [UInt8] {
        var frame = [UInt8](repeating: 0, count: EapolKey.fixedLength)
        frame[0] = 0x01
        frame[1] = 0x03
        frame[4] = UInt8(Prf.keyDescriptorCcmp)
        let keyInfo = Prf.keyDescriptorCcmp | 0x0008 | 0x0100 | 0x0200
        frame[5] = UInt8((keyInfo >> 8) & 0xFF)
        frame[6] = UInt8(keyInfo & 0xFF)
        frame[16] = 2
        for i in 0..<supplicantNonce.count { frame[EapolKey.nonceOffset + i] = supplicantNonce[i] }
        let bodyLength = frame.count - 4
        frame[2] = UInt8((bodyLength >> 8) & 0xFF)
        frame[3] = UInt8(bodyLength & 0xFF)
        return frame
    }
}