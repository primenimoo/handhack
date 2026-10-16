import Foundation

/// A self-contained performance probe for the local pipeline.
///
/// It separates the two costs that a dictionary run mixes together — reading/decoding the text
/// and deriving keys — and it touches nothing but the CPU: there is no network call anywhere
/// here, so the numbers cannot be confused with a download or with a server round-trip. Every
/// figure is measured from work that really happened, with a monotonic clock.
enum EngineDiagnostics {
    struct Report: Equatable {
        /// Physical lines the sample file contained.
        let linesRead: Int
        /// Distinct non-blank entries in the sample.
        let uniqueEntries: Int
        /// Wall time spent decoding and counting the sample.
        let readSeconds: Double
        /// PBKDF2 derivations actually performed.
        let derivationsRun: Int
        /// Wall time spent on those derivations.
        let derivationSeconds: Double

        var linesPerSecond: Double {
            readSeconds > 0 ? Double(linesRead) / readSeconds : 0
        }

        var derivationsPerSecond: Double {
            derivationSeconds > 0 ? Double(derivationsRun) / derivationSeconds : 0
        }

        var summary: String {
            let lines = Int(linesPerSecond.rounded())
            let rate = Int(derivationsPerSecond.rounded())
            return """
            Чтение словаря: \(linesRead.formatted()) строк (\(uniqueEntries.formatted()) записей) \
            за \(Self.seconds(readSeconds)) — \(lines.formatted()) строк/с.
            Проверка ключей: \(derivationsRun.formatted()) вычислений за \
            \(Self.seconds(derivationSeconds)) — \(rate.formatted()) проверок/с.
            """
        }

        private static func seconds(_ value: Double) -> String {
            String(format: "%.2f с", value)
        }
    }

    /// Runs a fixed, synthetic probe. `derivationBudget` bounds the number of PBKDF2 derivations
    /// so the probe is quick but still measures a real interval.
    static func measure(
        dictionaryBytes: [UInt8],
        targets: [HandshakeTarget] = [],
        derivationBudget: Int = 128,
        clock: () -> Double = { ProcessInfo.processInfo.systemUptime }
    ) -> Report {
        let readStart = clock()
        let decoded = DictionaryText.decode(dictionaryBytes)
        let readSeconds = max(clock() - readStart, 0)

        let salt = targets.first?.ssidBytes ?? Array("HandHackDiagnostics".utf8)
        let budget = max(1, derivationBudget)
        var ran = 0
        let deriveStart = clock()
        while ran < budget {
            _ = Pbkdf2.derive(password: Array("probe-\(ran)".utf8), salt: salt)
            ran += 1
        }
        let derivationSeconds = max(clock() - deriveStart, 0)

        return Report(
            linesRead: decoded.analysis.totalLines,
            uniqueEntries: decoded.analysis.uniqueEntries,
            readSeconds: readSeconds,
            derivationsRun: ran,
            derivationSeconds: derivationSeconds)
    }

    /// A deterministic synthetic dictionary used by the probe and the tests: no user data and no
    /// filesystem are involved.
    static func syntheticDictionary(lines: Int) -> [UInt8] {
        var text = ""
        for index in 0..<max(0, lines) {
            text += "candidate-\(index)\n"
        }
        return Array(text.utf8)
    }
}
