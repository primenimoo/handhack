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

        var isUsable: Bool { !targets.isEmpty }
    }

    private static let probeBytes = 4096
    private static let noRecordsMessage = "В файле нет записей формата WPA*01 или WPA*02."

    static func load(data: [UInt8], name: String) -> Loaded {
        if data.isEmpty {
            return Loaded(targets: [], format: .hc22000,
                          notes: ["Файл пустой — нет данных для подбора."])
        }

        let probe = Array(data.prefix(probeBytes))
        switch detect(probe: probe, fileName: name) {
        case .hc22000: return loadText(data: data)
        case .legacyExport: return loadLegacy(data: data)
        case .hccapx: return loadContainer(format: .hccapx, targets: HccapxReader.parse(data: data), size: data.count)
        case .hccap: return loadContainer(format: .hccap, targets: HccapReader.parse(data: data), size: data.count)
        case .pcap, .pcapng: return loadFrames(data: data, format: detect(probe: probe, fileName: name))
        }
    }

    static func load(url: URL) throws -> Loaded {
        let data = try Data(contentsOf: url)
        return load(data: Array(data), name: url.lastPathComponent)
    }

    /// Decides the format from the bytes. The two text formats are told apart by whether the
    /// first record carries a network name of its own, and `.hccap` is recognised by its shape
    /// because the format has no magic number.
    static func detect(probe: [UInt8], fileName: String = "") -> CaptureFormat {
        if probe.isEmpty { return .hc22000 }

        if PcapReader.isCaptureContainer(probe) {
            return PcapReader.detect(probe).kind == .pcapng ? .pcapng : .pcap
        }
        if HccapxReader.sniff(probe) { return .hccapx }
        if HccapReader.sniff(probe) { return .hccap }

        if let firstRecord = firstMeaningfulLine(probe) {
            if Hc22000Parser.looksLikeRecord(firstRecord) &&
                firstRecord.filter({ $0 == "*" }).count >= Hc22000Parser.plainFields - 1 {
                return .hc22000
            }
            if firstRecord.contains(LegacyExportParser.marker) ||
                firstRecord.contains(LegacyExportParser.markerWpa02) {
                return .legacyExport
            }
        }
        return .hc22000
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

    /// First line that is not blank and not a comment, decoded as UTF-8.
    private static func firstMeaningfulLine(_ probe: [UInt8]) -> String? {
        let text = String(decoding: probe, as: UTF8.self)
        for rawLine in text.split(separator: "\n", omittingEmptySubsequences: false) {
            var line = String(rawLine).trimmingCharacters(in: .whitespacesAndNewlines)
            if line.hasPrefix("\u{FEFF}") { line.removeFirst() }
            if line.isEmpty || line.hasPrefix("#") || line.hasPrefix("//") { continue }
            return line
        }
        return nil
    }
}