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
    /// What the file actually was when it was imported. Persisted so list rows can say "формат не
    /// распознан" instead of showing the `hc22000` fallback the old detector used.
    var recognition: CaptureLoader.Recognition

    init(data: [UInt8], name: String, loaded: CaptureLoader.Loaded? = nil) {
        self.init(name: name, loaded: loaded ?? CaptureLoader.load(data: data, name: name))
    }

    /// Builds the record from an already-parsed load, so importing a file parses it once instead
    /// of once here and again in the caller.
    init(name: String, loaded: CaptureLoader.Loaded) {
        id = UUID()
        fileName = AppFiles.sanitize(name)
        format = loaded.format
        notes = loaded.notes
        ssids = loaded.targets.map { $0.displaySsid }
        addedAt = Date()
        recognition = loaded.recognition
        if (fileName as NSString).pathExtension.isEmpty {
            fileName = "\(fileName).\(format.id)"
        }
    }

    enum CodingKeys: String, CodingKey {
        case id, fileName, format, notes, ssids, addedAt, recognition
    }

    /// Lenient decode: records saved before `recognition` existed fall back to a value derived
    /// from what was stored rather than failing the whole state load.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        fileName = try container.decode(String.self, forKey: .fileName)
        format = try container.decode(CaptureLoader.CaptureFormat.self, forKey: .format)
        notes = try container.decodeIfPresent([String].self, forKey: .notes) ?? []
        ssids = try container.decodeIfPresent([String].self, forKey: .ssids) ?? []
        addedAt = try container.decodeIfPresent(Date.self, forKey: .addedAt) ?? Date()
        recognition = try container.decodeIfPresent(CaptureLoader.Recognition.self, forKey: .recognition)
            ?? (ssids.isEmpty ? .damaged(format) : .recognised(format))
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
    /// Lets "Повторить" in History take the user straight back to the same capture file.
    var captureID: UUID?

    init(captureName: String, ssid: String, password: String?, attempts: Int,
         durationSeconds: Double, dictionaryName: String, found: Bool, captureID: UUID? = nil) {
        id = UUID()
        self.captureName = captureName
        self.ssid = ssid
        self.password = password
        self.attempts = attempts
        self.durationSeconds = durationSeconds
        self.dictionaryName = dictionaryName
        self.found = found
        date = Date()
        self.captureID = captureID
    }

    enum CodingKeys: String, CodingKey {
        case id, captureName, ssid, password, attempts, durationSeconds,
             dictionaryName, found, date, captureID
    }

    /// Lenient decode: results saved by an older build have no `captureID`, which must fall back
    /// to nil instead of failing to load the whole history.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        captureName = try container.decode(String.self, forKey: .captureName)
        ssid = try container.decode(String.self, forKey: .ssid)
        password = try container.decodeIfPresent(String.self, forKey: .password)
        attempts = try container.decodeIfPresent(Int.self, forKey: .attempts) ?? 0
        durationSeconds = try container.decodeIfPresent(Double.self, forKey: .durationSeconds) ?? 0
        dictionaryName = try container.decodeIfPresent(String.self, forKey: .dictionaryName) ?? ""
        found = try container.decodeIfPresent(Bool.self, forKey: .found) ?? false
        date = try container.decodeIfPresent(Date.self, forKey: .date) ?? Date(timeIntervalSince1970: 0)
        captureID = try container.decodeIfPresent(UUID.self, forKey: .captureID)
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
    var accentColor: AccentColorPreset = .blue
    var finishSound: SuccessSoundChoice = .original
    var attackSpeed: AttackSpeed = .balanced
    var autoRunAfterAnalysis = true
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
        accentColor = try container.decodeIfPresent(AccentColorPreset.self, forKey: .accentColor) ?? .blue
        finishSound = try container.decodeIfPresent(SuccessSoundChoice.self, forKey: .finishSound) ?? .original
        attackSpeed = try container.decodeIfPresent(AttackSpeed.self, forKey: .attackSpeed) ?? .balanced
        autoRunAfterAnalysis = try container.decodeIfPresent(Bool.self, forKey: .autoRunAfterAnalysis) ?? true
    }
}