import Foundation

/// Shared last step of every container that stores one EAPOL-Key message plus the two nonces
/// and the two addresses it belongs to: turning those into a [PmkVerifier.Mic].
///
/// Both legacy containers keep the MIC in their own field as well as inside the message, and the
/// field wins, because that is the value the file was written to be checked against.
enum MicTarget {
    static func build(
        ssid: [UInt8],
        mic: [UInt8],
        authenticator: [UInt8],
        supplicant: [UInt8],
        authenticatorNonce: [UInt8],
        supplicantNonce: [UInt8],
        keyDescriptorType: Int,
        message: [UInt8],
        rawLabel: String
    ) -> HandshakeTarget? {
        if ssid.isEmpty { return nil }
        if authenticator.count != 6 || supplicant.count != 6 { return nil }
        if mic.count != PmkVerifier.micLength { return nil }
        if authenticatorNonce.count != EapolKey.nonceLength { return nil }
        if supplicantNonce.count != EapolKey.nonceLength { return nil }
        if message.count < EapolKey.fixedLength { return nil }
        if keyDescriptorType != Prf.keyDescriptorTkip && keyDescriptorType != Prf.keyDescriptorCcmp {
            return nil
        }
        // A zero supplicant nonce means the capture never carried message 1, and without it the
        // PTK cannot be rebuilt, so no candidate can ever be confirmed.
        if supplicantNonce.allSatisfy({ $0 == 0 }) { return nil }

        return HandshakeTarget(
            ssidBytes: ssid,
            verifier: .mic(
                message: message,
                authenticator: authenticator,
                supplicant: supplicant,
                authenticatorNonce: authenticatorNonce,
                supplicantNonce: supplicantNonce,
                keyDescriptorType: keyDescriptorType,
                expected: mic),
            rawLine: rawLabel,
            evidence: .eapolMic,
            bssid: authenticator,
            station: supplicant,
            keyDescriptorType: keyDescriptorType)
    }
}