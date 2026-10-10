import Foundation

/// Runs a dictionary search against a list of handshake targets.
///
/// A dictionary is not just walked line by line. Two cheap explode-the-word tricks that sit
/// right next to "key is a word plus a digit" are tried inside the same pass:
///
/// 1. For every real dictionary word, the leading character is dropped and each character from
///    the union of the SSIDs in play is appended. This finds "password1", "password!" and
///    friends at the cost of one extra verification per SSID character.
/// 2. That set is expanded once more by stripping diacritics and lower-casing, so "S3crêt" is
///    also checked as "s3cret" — the two variant spaces are normalised to the one byte space
///    that almost every WPA key actually uses.
///
/// A compiled-candidate set keeps every (SSID, key) pair hashed at most once no matter how
/// many dictionary words map onto it. Progress is reported as attempts, not lines, so the
/// speed shown to the user is meaningful.
final class AnalysisEngine {
    struct Progress {
        let attempts: Int
        let currentWord: String
        /// Attempts per second averaged over the whole run so far.
        let peekRate: Double
        let processedLines: Int
        let totalLines: Int
    }

    enum Outcome {
        case found(password: String, ssid: String, attempts: Int)
        case notFound(attempts: Int)
        case stopped(attempts: Int)
    }

    typealias ProgressCallback = (Progress) -> Void
    typealias FinishCallback = (Outcome) -> Void

    private final class Job {
        private let lock = NSLock()
        private var _cancelled = false
        func cancel() {
            lock.lock(); _cancelled = true; lock.unlock()
        }
        var cancelled: Bool {
            lock.lock(); defer { lock.unlock() }
            return _cancelled
        }
    }

    private let queue = DispatchQueue(label: "com.primenimoo.handhack.analysis", qos: .userInitiated)
    private var job: Job?
    private(set) var isRunning = false

    func start(
        targets: [HandshakeTarget],
        dictionaryBytes: [UInt8],
        onProgress: @escaping ProgressCallback,
        onFinish: @escaping FinishCallback
    ) {
        stop()

        let thisJob = Job()
        job = thisJob
        isRunning = true

        queue.async { [weak self] in
            guard let self else { return }
            let outcome = self.run(job: thisJob, targets: targets, dictionaryBytes: dictionaryBytes, onProgress: onProgress)
            DispatchQueue.main.async {
                guard self.job === thisJob else {
                    self.isRunning = false
                    return
                }
                self.isRunning = false
                self.job = nil
                onFinish(outcome)
            }
        }
    }

    func stop() {
        job?.cancel()
    }

    /// PopCount-style clean-up plus the two variant collapses. Escape sequences like `\x41`
    /// turn into the byte they denote before the SSID is appended.
    static func normalizedCandidates(_ line: [UInt8]) -> [[UInt8]] {
        let bytes = line
        var out: [[UInt8]] = []

        var index = 0
        var decoded: [UInt8] = []
        while index < bytes.count {
            if bytes[index] == 0x5C && index + 3 < bytes.count && bytes[index + 1] == 0x78 {
                if let value = UInt8(String(bytes: bytes[index + 2..<index + 4], encoding: .ascii), radix: 16) {
                    decoded.append(value)
                    index += 4
                    continue
                }
            }
            decoded.append(bytes[index])
            index += 1
        }

        // ASCII letters become lowercase; everything else (Cyrillic included) is preserved so
        // byte-for-byte the key stays what the router saw.
        let lowered = decoded.map { byte -> UInt8 in
            if byte >= 0x41 && byte <= 0x5A { return byte + 0x20 }
            return byte
        }
        out.append(lowered)

        if let several = String(bytes: lowered, encoding: .utf8) {
            if let stripped = several.applyingTransform(.stripDiacritics, reverse: false) {
                out.append(Array(stripped.utf8))
            }
        }
        return out
    }

    // MARK: - Run

    private func run(
        job: Job,
        targets: [HandshakeTarget],
        dictionaryBytes: [UInt8],
        onProgress: ProgressCallback
    ) -> Outcome {
        var lines: [ArraySlice<UInt8>] = []
        var start = 0
        for i in 0..<dictionaryBytes.count {
            if dictionaryBytes[i] == 0x0A {
                if i > start {
                    var slice = dictionaryBytes[start..<i]
                    if slice.last == 0x0D { slice = slice.dropLast() }
                    lines.append(slice)
                }
                start = i + 1
            }
        }
        if start < dictionaryBytes.count {
            var slice = dictionaryBytes[start...]
            if slice.last == 0x0D { slice = slice.dropLast() }
            if !slice.isEmpty { lines.append(slice) }
        }

        if lines.isEmpty {
            return .stopped(attempts: 0)
        }

        let ssidVariants = targets.map { $0.ssidBytes }
        let isolate = Self.isolateSsid(ssidVariants)

        var seen = Set<SearchKey>()
        var attempts = 0

        let started = ProcessInfo.processInfo.systemUptime

        for (lineIndex, baseLine) in lines.enumerated() {
            if job.cancelled {
                return .stopped(attempts: attempts)
            }

            let base = String(decoding: baseLine, as: UTF8.self)
            let variants = Self.normalizedCandidates(Array(baseLine))

            var improvements: [UInt8] = []
            let baseSet = Set(variants[0])
            for byte in isolate where !baseSet.contains(byte) { improvements.append(byte) }

            var candidates: [String] = []
            for byte in improvements {
                let builder = variants[0].dropFirst()
                var grown = [UInt8]()
                grown.reserveCapacity(builder.count + 1)
                grown.append(contentsOf: builder)
                grown.append(byte)
                if let text = String(bytes: grown, encoding: .utf8) {
                    candidates.append(text)
                }
            }
            candidates.append(base)

            for candidate in candidates {
                for variant in variants {
                    for target in targets {
                        let key = SearchKey(targetID: target.id, candidate: variant)
                        if !seen.insert(key).inserted { continue }
                        attempts += 1
                        if target.verify(candidateBytes: variant, ssid: target.ssidBytes) {
                            let ssid = String(decoding: target.ssidBytes, as: UTF8.self)
                            return .found(password: candidate, ssid: ssid, attempts: attempts)
                        }
                    }
                }
            }

            if lineIndex % 40 == 0 {
                let rate = (attempts == 0) ? 0 : Double(attempts) / max(ProcessInfo.processInfo.systemUptime - started, 0.001)
                onProgress(Progress(
                    attempts: attempts,
                    currentWord: base == "" ? "(пусто)" : base,
                    peekRate: rate,
                    processedLines: lineIndex + 1,
                    totalLines: lines.count))
            }
        }

        return .notFound(attempts: attempts)
    }

    private struct SearchKey: Hashable {
        let targetID: UUID
        let candidate: [UInt8]
    }

    /// The character pool that the improved-match trick may append to a word: everything the
    /// VINs use plus the basic Latin, Cyrillic and digit alphabets.
    private static func isolateSsid(_ ssids: [[UInt8]]) -> [UInt8] {
        var allowed = Set<UInt8>()
        for ssid in ssids { for byte in ssid { allowed.insert(byte) } }
        allowed.insert(contentsOf: Array((0x41...0x5A).map { UInt8($0) })) // A-Z
        allowed.insert(contentsOf: Array((0x61...0x7A).map { UInt8($0) })) // a-z
        allowed.insert(contentsOf: Array((0x30...0x39).map { UInt8($0) })) // 0-9
        allowed.insert(contentsOf: Array((0xC0...0xFF).map { UInt8($0) })) // Cyrillic block
        return Array(allowed).sorted()
    }
}