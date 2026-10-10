import AVFoundation
import Foundation

/// A short, soft three-note chime played when a password is recovered. The waveform is generated
/// in memory — no bundled asset — so it stays tiny and behaves the same on every device. The
/// audio session is `.ambient`, which means the chime mixes with whatever the user is already
/// listening to and respects the silent switch.
final class SuccessSound {
    static let shared = SuccessSound()

    private var player: AVAudioPlayer?
    private var sessionConfigured = false

    private init() {}

    func play() {
        configureSession()
        guard let data = Self.chimeWav() else { return }
        player = try? AVAudioPlayer(data: data)
        player?.volume = 0.9
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

    /// A C-major arpeggio (C6, E6, G6) with a soft attack and an exponential decay, so it reads
    /// as a gentle "готово" rather than an alarm.
    private static func chimeWav() -> Data? {
        let sampleRate = 44_100
        let duration = 0.95
        let frameCount = Int(Double(sampleRate) * duration)
        var samples = [Int16](repeating: 0, count: frameCount)

        let notes: [(frequency: Double, start: Double, length: Double, gain: Double)] = [
            (1046.50, 0.00, 0.55, 0.55),
            (1318.51, 0.11, 0.55, 0.50),
            (1567.98, 0.22, 0.65, 0.50),
        ]

        for index in 0..<frameCount {
            let time = Double(index) / Double(sampleRate)
            var value = 0.0
            for note in notes {
                let local = time - note.start
                guard local >= 0, local < note.length else { continue }
                let envelope = attackDecay(local, length: note.length)
                let fundamental = sin(2 * .pi * note.frequency * local)
                let shimmer = 0.18 * sin(2 * .pi * note.frequency * 2 * local)
                value += (fundamental + shimmer) * envelope * note.gain
            }
            let clamped = max(-1.0, min(1.0, value))
            samples[index] = Int16(clamped * 32767.0)
        }

        return wavData(samples: samples, sampleRate: sampleRate)
    }

    private static func attackDecay(_ time: Double, length: Double) -> Double {
        let attack = 0.008
        let rise = min(1.0, time / attack)
        let fall = exp(-4.0 * time / length)
        return rise * fall
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
