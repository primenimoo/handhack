import AudioToolbox
import AVFoundation
import Foundation

/// The short, quiet confirmation tick the app plays when a password is recovered. It uses the
/// iPhone's own system sound (1104, "Tink") — the same click the on-screen keyboard makes — so it
/// is the real iPhone sound rather than a synthesised chime, needs no bundled asset and respects
/// the silent switch like every other system sound.
final class SuccessSound {
    static let shared = SuccessSound()

    private static let tickSoundId: SystemSoundID = 1104

    private var fallbackPlayer: AVAudioPlayer?

    private init() {}

    func play() {
        let accepted = AudioServicesPlaySystemSoundWithCompletion(Self.tickSoundId, nil) == kAudioServicesNoError
        guard !accepted else { return }
        // Rare failure path (an oddly configured device): play an in-memory replica so the
        // "готово" confirmation is never silent.
        guard let data = Self.tickWav() else { return }
        fallbackPlayer = try? AVAudioPlayer(data: data)
        fallbackPlayer?.volume = 0.9
        fallbackPlayer?.play()
    }

    /// A very short "tck": a bright 2.6 kHz click with a soft 500 Hz body underneath, both dying
    /// out within a tenth of a second so it reads as an approval tick rather than a sound.
    private static func tickWav() -> Data? {
        let sampleRate = 44_100
        let duration = 0.18
        let frameCount = Int(Double(sampleRate) * duration)
        var samples = [Int16](repeating: 0, count: frameCount)

        for index in 0..<frameCount {
            let time = Double(index) / Double(sampleRate)
            let click = sin(2 * .pi * 2600 * time) * exp(-time / 0.012)
            let body = sin(2 * .pi * 480 * time) * exp(-time / 0.045)
            let value = max(-1.0, min(1.0, 0.55 * click + 0.5 * body))
            samples[index] = Int16(value * 32767.0)
        }

        return wavData(samples: samples, sampleRate: sampleRate)
    }

    private static func wavData(samples: [Int16], sampleRate: Int) -> Data {
        let bytesPerSample = 2
        let dataSize = samples.count * bytesPerSample
        var data = Data()

        func append(_ value: UInt32, bytes: Int) {
            var little = value.littleEndian
            withUnsafeBytes(of: &little) { data.append(contentsOf: $0.prefix(bytes)) }
        }
        func append(_ value: UInt16) {
            var little = value.littleEndian
            withUnsafeBytes(of: &little) { data.append(contentsOf: $0) }
        }
        func appendString(_ string: String) { data.append(contentsOf: Array(string.utf8)) }

        let byteRate = UInt32(sampleRate * bytesPerSample)

        appendString("RIFF")
        append(UInt32(36 + dataSize), bytes: 4)
        appendString("WAVE")
        appendString("fmt ")
        append(UInt32(16), bytes: 4)
        append(UInt32(1), bytes: 2)              // PCM
        append(UInt32(1), bytes: 2)              // mono
        append(UInt32(sampleRate), bytes: 4)
        append(byteRate, bytes: 4)
        append(UInt16(bytesPerSample))           // block align
        append(UInt16(16))                       // bits per sample
        appendString("data")
        append(UInt32(dataSize), bytes: 4)

        for sample in samples {
            var little = sample.littleEndian
            withUnsafeBytes(of: &little) { data.append(contentsOf: $0) }
        }
        return data
    }
}