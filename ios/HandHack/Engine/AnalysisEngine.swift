import Foundation

/// Runs a dictionary search against a list of handshake targets.
///
/// The search is deliberately plain: every non-empty dictionary line is used *exactly as it is
/// written* as a candidate passphrase, in file order. For the candidate the engine derives the
/// PMK once per distinct SSID (PBKDF2-HMAC-SHA1, 4096 rounds) and asks each target's verifier
/// whether that key reproduces the captured PMK, PMKID or EAPOL MIC. The first line that matches
/// is reported verbatim, so the password shown is always the bytes that were actually tested.
///
/// There is no guessing and no synthesised "improvement" pass: the attempt counter and the word
/// on screen both come straight from that loop, so the speed the user sees is a real derivation
/// rate and the password is never anything but a dictionary line.
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

        var attempts: Int {
            switch self {
            case .found(_, _, let attempts), .notFound(let attempts), .stopped(let attempts):
                return attempts
            }
        }
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
            let outcome = self.search(job: thisJob, targets: targets,
                                      dictionaryBytes: dictionaryBytes, onProgress: onProgress)
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

    /// Synchronous run used by the tests: it exercises the exact same loop as [start] with no
    /// dispatch and no cancellation.
    func crack(targets: [HandshakeTarget], dictionaryBytes: [UInt8]) -> Outcome {
        search(job: Job(), targets: targets, dictionaryBytes: dictionaryBytes, onProgress: { _ in })
    }

    // MARK: - Run

    private func search(
        job: Job,
        targets: [HandshakeTarget],
        dictionaryBytes: [UInt8],
        onProgress: ProgressCallback
    ) -> Outcome {
        let lines = DictionaryLines.split(dictionaryBytes)
        if lines.isEmpty {
            return .stopped(attempts: 0)
        }

        // Networks that share an SSID share a PMK, so derive once per network and reuse the key
        // for every target of that network.
        var order: [String] = []
        var buckets: [String: [HandshakeTarget]] = [:]
        for target in targets {
            let key = Hex.encode(target.ssidBytes)
            if buckets[key] == nil {
                buckets[key] = []
                order.append(key)
            }
            buckets[key]?.append(target)
        }
        let groups = order.map { key -> (ssid: [UInt8], targets: [HandshakeTarget]) in
            let list = buckets[key]!
            return (ssid: list[0].ssidBytes, targets: list)
        }

        var attempts = 0
        let started = ProcessInfo.processInfo.systemUptime
        let total = lines.count

        for (lineIndex, line) in lines.enumerated() {
            if job.cancelled {
                return .stopped(attempts: attempts)
            }

            let candidateBytes = Array(line)
            let candidate = String(decoding: candidateBytes, as: UTF8.self)

            if candidateBytes.count >= HandshakeTarget.wpaMinPassphrase &&
                candidateBytes.count <= HandshakeTarget.wpaMaxPassphrase {
                for group in groups {
                    let pmk = Pbkdf2.derive(password: candidateBytes, salt: group.ssid)
                    attempts += 1
                    for target in group.targets where target.verifier.accepts(pmk: pmk) {
                        let ssid = String(decoding: group.ssid, as: UTF8.self)
                        return .found(password: candidate, ssid: ssid, attempts: attempts)
                    }
                }
            }

            if lineIndex % 25 == 0 || lineIndex == total - 1 {
                let elapsed = max(ProcessInfo.processInfo.systemUptime - started, 0.001)
                onProgress(Progress(
                    attempts: attempts,
                    currentWord: candidate.isEmpty ? "(пусто)" : candidate,
                    peekRate: Double(attempts) / elapsed,
                    processedLines: lineIndex + 1,
                    totalLines: total))
            }
        }

        return .notFound(attempts: attempts)
    }
}

/// Splits a dictionary file into candidate lines, honouring both Unix and Windows endings.
enum DictionaryLines {
    static func split(_ bytes: [UInt8]) -> [ArraySlice<UInt8>] {
        var lines: [ArraySlice<UInt8>] = []
        var start = 0
        for i in 0..<bytes.count where bytes[i] == 0x0A {
            if i > start {
                var slice = bytes[start..<i]
                if slice.last == 0x0D { slice = slice.dropLast() }
                if !slice.isEmpty { lines.append(slice) }
            }
            start = i + 1
        }
        if start < bytes.count {
            var slice = bytes[start...]
            if slice.last == 0x0D { slice = slice.dropLast() }
            if !slice.isEmpty { lines.append(slice) }
        }
        return lines
    }
}
