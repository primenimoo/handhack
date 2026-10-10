import Foundation

/// What the smart analysis tells the user before a run starts. Pure, cheap, deterministic: it
/// counts the work the engine is about to do and picks the speed profile that fits it.
struct SmartPlan {
    /// Verifiable records found in the file.
    let records: Int
    /// Distinct network names: the engine derives the PMK once per unique SSID and reuses it,
    /// so this is what the candidate count multiplies by.
    let uniqueSsids: Int
    /// The kinds of key material present, in the CaptureEvidence case order.
    let evidence: [CaptureEvidence]
    /// How many dictionary candidates are enabled.
    let candidates: Int
    /// Candidate lines multiplied by the distinct networks that each one must be checked against.
    let derivations: Int
    /// The profile the analysis recommends for this workload.
    let recommendedSpeed: AttackSpeed
    /// Approximate seconds at the recommended pace, for the "how long" line.
    let estimatedSeconds: Int
    /// Ready-made human sentences for the plan card.
    let summaryLines: [String]
}

/// Examines targets and dictionaries and produces the plan for a smart run: which evidence types
/// are present, how much derivation work the search implies, and at what pace to run it.
enum SmartAnalysis {

    static func plan(targets: [HandshakeTarget],
                     dictionaries: [AttackRunner.DictionaryInput]) -> SmartPlan {
        var ssids = Set<[UInt8]>()
        for target in targets { ssids.insert(target.ssidBytes) }

        var evidence = CaptureEvidence.allCases.filter { evidence in
            targets.contains { $0.evidence == evidence }
        }
        if evidence.isEmpty { evidence = [.eapolMic] }

        var candidates = 0
        for dictionary in dictionaries {
            candidates += DictionaryLines.split(dictionary.bytes).count
        }

        let derivations = candidates * ssids.count
        let recommended: AttackSpeed
        if derivations <= 60_000 {
            recommended = .careful
        } else if derivations <= 900_000 {
            recommended = .balanced
        } else {
            recommended = .maximum
        }

        let nominalRate = nominalPerSecond(recommended)
        let estimated = nominalRate > 0 ? Int(ceil(Double(derivations) / Double(nominalRate))) : 0

        var summary: [String] = []
        summary.append("Сетей в файле: \(targets.count) · уникальных: \(ssids.count)")
        let checkText = evidence.map { Self.evidenceTitle($0) }.joined(separator: ", ")
        summary.append("Проверка: \(checkText)")
        summary.append("Словарей: \(dictionaries.count) · кандидатов: \(candidates)")
        summary.append("Скорость «\(recommended.title)» — примерно \(timeText(estimated))")

        return SmartPlan(
            records: targets.count,
            uniqueSsids: ssids.count,
            evidence: evidence,
            candidates: candidates,
            derivations: derivations,
            recommendedSpeed: recommended,
            estimatedSeconds: estimated,
            summaryLines: summary)
    }

    private static func nominalPerSecond(_ speed: AttackSpeed) -> Int {
        switch speed {
        case .careful: return 160
        case .balanced: return 1200
        case .maximum: return 4000
        }
    }

    static func evidenceTitle(_ evidence: CaptureEvidence) -> String {
        switch evidence {
        case .pmk: return "прямое сравнение PMK"
        case .pmkid: return "PMKID (WPA*01)"
        case .eapolMic: return "EAPOL MIC (WPA*02)"
        }
    }

    private static func timeText(_ seconds: Int) -> String {
        if seconds < 60 { return "\(seconds) с" }
        if seconds < 3600 { return "\(Int(seconds / 60)) мин" }
        return "\(Int(seconds / 3600)) ч"
    }
}