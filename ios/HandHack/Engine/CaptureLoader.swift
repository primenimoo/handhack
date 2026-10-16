import Foundation

/// The one place that decides which reader a capture file needs.
///
/// Six containers are supported and they share nothing but the promise that a network's key
/// material can be recovered from them. Detection looks at the bytes first and the file name
/// second, so nothing is decided by the extension alone.
enum CaptureLoader {
    enum CaptureFormat: CaseIterable, Codable {
        case hc22000
        case legacyExport
        case hccapx
        case hccap
        case pcap
        case pcapng

        var id: String {
            switch self {
            case .hc22000: return "hc22000"
            case .legacyExport: return "legacy"
            case .hccapx: return "hccapx"
            case .hccap: return "hccap"
            case .pcap: return "pcap"
            case .pcapng: return "pcapng"
            }
        }

        var label: String {
            switch self {
            case .hc22000: return "hc22000 (WPA*01 / WPA*02)"
            case .legacyExport: return "экспорт HandHack"
            case .hccapx: return "hccapx"
            case .hccap: return "hccap"
            case .pcap: return "pcap / cap"
            case .pcapng: return "pcapng"
            }
        }

        var textBased: Bool {
            switch self {
            case .hc22000, .legacyExport: return true
            default: return false
            }
        }

        var extensions: Set<String> {
            switch self {
            case .hc22000: return ["hc22000", "txt", "22000"]
            case .legacyExport: return ["wpa", "handshake", "handshakes", "pmkid", "txt"]
            case .hccapx: return ["hccapx"]
            case .hccap: return ["hccap"]
            case .pcap: return ["pcap", "cap"]
            case .pcapng: return ["pcapng"]
            }
        }

        static var allExtensions: Set<String> {
            var result = Set<String>()
            for format in allCases { result.formUnion(format.extensions) }
            return result
        }
    }

    struct Loaded {
        let targets: [HandshakeTarget]
        let format: CaptureFormat
        /// Plain sentences for the UI, already written in Russian.
        let notes: [String]
        /// What the bytes turned out to be, including the two cases the old code could not tell
        /// apart: a file that is not a recognised format at all, and one that is a known format
        /// but has no usable records in it.
        var recognition: Recognition = .unknown

        var isUsable: Bool { !targets.isEmpty }
    }

    /// The four outcomes of looking at a file, kept separate so a message can say "this is not a
    /// capture we support" instead of quietly pretending it was `hc22000` and reporting damage.
    enum Recognition: Equatable, Codable {
        case empty
        case unknown
        /// A recognised container/text format with no usable records.
        case damaged(CaptureFormat)
        case recognised(CaptureFormat)

        var format: CaptureFormat? {
            switch self {
            case .damaged(let format), .recognised(let format): return format
            case .empty, .unknown: return nil
            }
        }
    }

    /// The verdict handed back after an import attempt, phrased for the user and carrying every
    /// stage so the UI can say exactly where a file stopped being useful.
    struct ImportReport {
        let fileName: String
        let recognition: Recognition
        let targetCount: Int
        let notes: [String]

        var isUsable: Bool { targetCount > 0 }

        var statusLine: String {
            switch recognition {
            case .empty:
                return "«\(fileName)»: файл пустой."
            case .unknown:
                return "«\(fileName)»: формат не распознан."
            case .damaged(let format):
                return "«\(fileName)»: файл похож на \(format.label), но пригодных записей нет."
            case .recognised(let format):
                return "«\(fileName)»: \(format.label), сетей для проверки: \(targetCount)."
            }
        }
    }

    private static let probeBytes = 4096
    private static let noRecordsMessage = "В файле нет записей формата WPA*01 или WPA*02."

    static func load(data: [UInt8], name: String) -> Loaded {
        if data.isEmpty {
            return Loaded(targets: [], format: .hc22000, notes: ["Файл пустой — нет данных для подбора."],
                          recognition: .empty)
        }

        let probe = Array(data.prefix(probeBytes))
        guard let format = sniff(probe: probe, fileName: name) else {
            return Loaded(
                targets: [], format: .hc22000,
                notes: ["Формат не распознан. Поддерживаются: hc22000 (WPA*01 / WPA*02), " +
                    "экспорт HandHack, hccapx, hccap, pcap и pcapng."],
                recognition: .unknown)
        }

        let loaded: Loaded
        switch format {
        case .hc22000: loaded = loadText(data: data)
        case .legacyExport: loaded = loadLegacy(data: data)
        case .hccapx: loaded = loadContainer(format: .hccapx, targets: HccapxReader.parse(data: data), size: data.count)
        case .hccap: loaded = loadContainer(format: .hccap, targets: HccapReader.parse(data: data), size: data.count)
        case .pcap, .pcapng: loaded = loadFrames(data: data, format: format)
        }

        let recognition: Recognition = loaded.targets.isEmpty ? .damaged(format) : .recognised(format)
        return Loaded(targets: loaded.targets, format: format, notes: loaded.notes, recognition: recognition)
    }

    /// The user-facing verdict for one imported file. Built from the same load that the rest of
    /// the app uses, so the message and the stored record can never disagree.
    static func report(data: [UInt8], name: String) -> ImportReport {
        report(load(data: data, name: name), name: name)
    }

    static func report(_ loaded: Loaded, name: String) -> ImportReport {
        ImportReport(fileName: name, recognition: loaded.recognition,
                     targetCount: loaded.targets.count, notes: loaded.notes)
    }

    static func load(url: URL) throws -> Loaded {
        let data = try Data(contentsOf: url)
        return load(data: Array(data), name: url.lastPathComponent)
    }

    /// Decides the format from the bytes. The two text formats are told apart by whether a full
    /// nine-field `.hc22000` record is present anywhere; a bare `WPA*01`/`WPA*02` marker glued to
    /// one hex blob (the legacy export, whose SSID may sit on the line above) is the old format.
    /// `.hccap` is recognised by its shape because the format has no magic number. Returns `nil`
    /// when the bytes match nothing we support, so callers can tell "unknown" from "damaged".
    static func sniff(probe: [UInt8], fileName: String = "") -> CaptureFormat? {
        if probe.isEmpty { return nil }

        if PcapReader.isCaptureContainer(probe) {
            return PcapReader.detect(probe).kind == .pcapng ? .pcapng : .pcap
        }
        if HccapxReader.sniff(probe) { return .hccapx }
        if HccapReader.sniff(probe) { return .hccap }

        var sawLegacyMarker = false
        for line in meaningfulLines(probe) {
            if Hc22000Parser.looksLikeRecord(line) &&
                line.filter({ $0 == "*" }).count >= Hc22000Parser.plainFields - 1 {
                return .hc22000
            }
            if line.contains(LegacyExportParser.marker) ||
                line.contains(LegacyExportParser.markerWpa02) {
                sawLegacyMarker = true
            }
        }
        return sawLegacyMarker ? .legacyExport : nil
    }

    /// Kept for callers that still want a concrete format: falls back to `hc22000` when nothing
    /// matched. New code should prefer `sniff`, which can say "unknown".
    static func detect(probe: [UInt8], fileName: String = "") -> CaptureFormat {
        sniff(probe: probe, fileName: fileName) ?? .hc22000
    }

    private static func loadText(data: [UInt8]) -> Loaded {
        let text = String(decoding: data, as: UTF8.self)
        let result = Hc22000Parser.parseFile(text: text)
        var counts: [String: Int] = [:]
        for rejected in result.rejected {
            counts[rejected.reason.rawValue, default: 0] += 1
        }
        var notes = counts.map { "\($0.key) (\($0.value))" }.sorted()
        if result.targets.isEmpty && notes.isEmpty { notes.append(noRecordsMessage) }
        return Loaded(targets: result.targets, format: .hc22000, notes: notes)
    }

    private static func loadLegacy(data: [UInt8]) -> Loaded {
        let text = String(decoding: data, as: UTF8.self)
        let targets = LegacyExportParser.parse(text: text)
        var notes: [String] = []
        let withoutSsid = LegacyExportParser.countRecords(text: text) - targets.count
        if withoutSsid > 0 {
            notes.append("В \(withoutSsid) записях нет SSID — проверить их невозможно. " +
                "Укажите имя сети перед записью в виде «ИмяСети:\(LegacyExportParser.marker)…».")
        }
        if targets.isEmpty && notes.isEmpty { notes.append(noRecordsMessage) }
        return Loaded(targets: targets, format: .legacyExport, notes: notes)
    }

    private static func loadContainer(format: CaptureFormat, targets: [HandshakeTarget], size: Int) -> Loaded {
        var notes: [String] = []
        if targets.isEmpty {
            notes.append("В файле не найдено ни одной пригодной записи \(format.label).")
            let recordLength = format == .hccapx ? HccapxReader.recordLength : HccapReader.recordLength
            if size % recordLength != 0 {
                notes.append("Размер файла не кратен длине записи, возможно он повреждён.")
            }
        }
        return Loaded(targets: targets, format: format, notes: notes)
    }

    private static func loadFrames(data: [UInt8], format: CaptureFormat) -> Loaded {
        let frames: [CapturedFrame]
        do {
            frames = try PcapReader.read(data: data)
        } catch is UnsupportedLinkType {
            return Loaded(
                targets: [], format: format,
                notes: ["Capture сохранён не как радио-трафик 802.11. " +
                    "Поддерживаются только записи с самими кадрами Wi-Fi."])
        } catch {
            return Loaded(targets: [], format: format, notes: ["Не удалось прочитать запись."])
        }

        let summary = WirelessCaptureParser.parse(frames: frames)
        var notes = summary.notes
        if !summary.targets.isEmpty {
            notes.append("Найдено сетей: \(summary.networkCount), " +
                "EAPOL-Key сообщений: \(summary.eapolCount).")
        }
        return Loaded(targets: summary.targets, format: format, notes: notes)
    }

    /// The lines that matter for format detection: not blank, not a comment, decoded as UTF-8.
    private static func meaningfulLines(_ probe: [UInt8]) -> [String] {
        let text = String(decoding: probe, as: UTF8.self)
        var lines: [String] = []
        for rawLine in text.split(separator: "\n", omittingEmptySubsequences: false) {
            var line = String(rawLine).trimmingCharacters(in: .whitespacesAndNewlines)
            if line.hasPrefix("\u{FEFF}") { line.removeFirst() }
            if line.isEmpty || line.hasPrefix("#") || line.hasPrefix("//") { continue }
            lines.append(line)
        }
        return lines
    }
}