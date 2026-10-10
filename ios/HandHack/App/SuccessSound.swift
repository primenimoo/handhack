import AVFoundation
import AudioToolbox
import Foundation
import UIKit

/// The success sound the user chose: a short mp3 bundled in the asset catalog, played as a soft
/// alert that mixes with whatever else is on screen. If the asset were ever missing the app falls
/// back to the iPhone system confirmation tick (1104) so a found password is never silent.
final class SuccessSound {
    static let shared = SuccessSound()

    private static let fallbackTickSoundId: SystemSoundID = 1104

    private var player: AVAudioPlayer?
    private var sessionConfigured = false

    private init() {}

    func play() {
        guard let data = NSDataAsset(name: "SuccessSound")?.data else {
            AudioServicesPlaySystemSound(Self.fallbackTickSoundId)
            return
        }
        configureSession()
        player = try? AVAudioPlayer(data: data)
        player?.volume = 1.0
        player?.prepareToPlay()
        player?.play()
    }

    private func configureSession() {
        guard !sessionConfigured else { return }
        sessionConfigured = true
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.ambient, mode: .default, options: [.mixWithOthers])
        try? session.setActive(true)
    }
}