import Foundation

// MARK: - Files

enum AppFiles {
    private static var documents: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    static var capturesDirectory: URL {
        let dir = documents.appendingPathComponent("Captures")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private static var stateFile: URL { documents.appendingPathComponent("handhack-state.json") }

    static func sanitize(_ name: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-_.")
        var safe = ""
        for scalar in name.unicodeScalars where allowed.contains(scalar) {
            safe.unicodeScalars.append(scalar)
        }
        return safe
    }

    static func saveState<T: Encodable>(_ value: T) {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        if let data = try? encoder.encode(value) {
            try? data.write(to: stateFile, options: .atomic)
        }
    }

    static func loadState<T: Decodable>(_ type: T.Type) -> T? {
        guard let data = try? Data(contentsOf: stateFile) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(type, from: data)
    }
}

// MARK: - Capture records

/// A capture file the user has imported. The parsed handshakes are not persisted; they are
/// rebuilt from the file on demand, so a record is just the metadata that gives a list entry
/// a name, a date and a shortcut.
struct CaptureRecord: Identifiable, Codable, Equatable {
    var id: UUID
    var fileName: String
    var format: CaptureLoader.CaptureFormat
    var notes: [String]
    var ssids: [String]
    var addedAt: Date

    init(data: [UInt8], name: String) {
        let loaded = CaptureLoader.load(data: data, name: name)
        id = UUID()
        fileName = AppFiles.sanitize(name)
        format = loaded.format
        notes = loaded.notes
        ssids = loaded.targets.map { $0.displaySsid }
        addedAt = Date()
        if (fileName as NSString).pathExtension.isEmpty {
            fileName = "\(fileName).\(format.id)"
        }
    }

    var isUsable: Bool { !ssids.isEmpty }

    var fileURL: URL {
        AppFiles.capturesDirectory.appendingPathComponent(fileName)
    }

    func persisted(data: [UInt8]) {
        try? Data(data).write(to: fileURL, options: .atomic)
    }

    func targets() -> [HandshakeTarget] {
        guard let bytes = try? Array(Data(contentsOf: fileURL)) else { return [] }
        return CaptureLoader.load(data: bytes, name: fileName).targets
    }

    static func == (lhs: CaptureRecord, rhs: CaptureRecord) -> Bool {
        lhs.id == rhs.id
    }
}

// MARK: - One finished search

struct CrackResult: Identifiable, Codable, Equatable {
    var id: UUID
    var captureName: String
    var ssid: String
    var password: String?
    var attempts: Int
    var durationSeconds: Double
    var dictionaryName: String
    var found: Bool
    var date: Date

    init(captureName: String, ssid: String, password: String?, attempts: Int,
         durationSeconds: Double, dictionaryName: String, found: Bool) {
        id = UUID()
        self.captureName = captureName
        self.ssid = ssid
        self.password = password
        self.attempts = attempts
        self.durationSeconds = durationSeconds
        self.dictionaryName = dictionaryName
        self.found = found
        date = Date()
    }

    var statusText: String {
        found ? "Подбор завершён — пароль найден" : "Пароль не найден"
    }
}

// MARK: - Settings

struct AppSettings: Codable, Equatable {
    var hapticsEnabled = true
    var soundEnabled = true
    var notifyOnFinish = false
    var firstLaunch = true
    static let `default` = AppSettings()

    init() {}

    /// Lenient decoding: a settings file written by an older build is missing the keys added
    /// later, so every field falls back to its default instead of failing the whole state load.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        hapticsEnabled = try container.decodeIfPresent(Bool.self, forKey: .hapticsEnabled) ?? true
        soundEnabled = try container.decodeIfPresent(Bool.self, forKey: .soundEnabled) ?? true
        notifyOnFinish = try container.decodeIfPresent(Bool.self, forKey: .notifyOnFinish) ?? false
        firstLaunch = try container.decodeIfPresent(Bool.self, forKey: .firstLaunch) ?? true
    }
}