import Foundation

/// Dictionary files live under Documents/Dictionaries as plain text, one candidate per line,
/// and are addressed by a sanitised file name that is also the DIC utt-id used by the UI.
enum DictionaryStore {
    struct Dictionary: Identifiable {
        let id: String
        let name: String
        let size: Int
    }

    static let allowedExtensions: Set<String> = [
        "txt", "dic", "wordlist", "lst", "names", "passwords", "crack",
        "gz1", "gz2", "gz3", "gz4", "gz5", "gz6", "gz7", "380m", "12m", "1b",
        "wpa", "yml", "pw", "dic2", "fr", "gpa", "ids", "in", "it", "l3", "l4",
        "l7", "pt", "rm", "rt", "sigs", "tr", "vbvb", "txts", "wifi", "z",
    ]

    private static var storeDirectory: URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documents.appendingPathComponent("Dictionaries")
    }

    static var all: [Dictionary] {
        ensureDirectory()
        let files = (try? FileManager.default.contentsOfDirectory(
            at: storeDirectory, includingPropertiesForKeys: nil)) ?? []
        return files
            .filter { allowedExtensions.contains($0.pathExtension.lowercased()) }
            .map { file in
                Dictionary(id: file.deletingPathExtension().lastPathComponent,
                           name: readableName(file.deletingPathExtension().lastPathComponent),
                           size: lineCount(of: file))
            }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    static func fetchSize(_ id: String) -> Int {
        lineCount(of: url(id: id))
    }

    static func add(from source: URL, name: String) throws {
        let data = try Data(contentsOf: source)
        try add(bytes: Array(data), name: name)
    }

    static func add(bytes: [UInt8], name: String) throws {
        ensureDirectory()
        let safeName = sanitize(name)
        guard !safeName.isEmpty else { throw StoreError.invalidName }
        let destination = storeDirectory
            .appendingPathComponent(safeName)
            .appendingPathExtension("txt")
        try Data(bytes).write(to: destination, options: .atomic)
    }

    static func remove(id: String) {
        try? FileManager.default.removeItem(at: url(id: id))
    }

    static func url(id: String) -> URL {
        storeDirectory.appendingPathComponent("\(id).txt")
    }

    static func contents(id: String) -> [UInt8]? {
        guard let data = try? Data(contentsOf: url(id: id)) else { return nil }
        return Array(data)
    }

    static func sanitize(_ name: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-_")
        var safe = ""
        for scalar in name.unicodeScalars where allowed.contains(scalar) {
            safe.unicodeScalars.append(scalar)
        }
        let trimmed = safe.trimmingCharacters(in: CharacterSet(charactersIn: "-_"))
        return String(trimmed.prefix(80))
    }

    static func readableName(_ id: String) -> String {
        let words = id.split(separator: "-").flatMap { $0.split(separator: "_") }
        let lower = words.map { $0.lowercased() }
        let capitalized = lower.map { word -> String in
            guard let first = word.first else { return word }
            return String(first).uppercased() + String(word.dropFirst())
        }
        return capitalized.joined(separator: " ")
    }

    private static func ensureDirectory() {
        try? FileManager.default.createDirectory(at: storeDirectory, withIntermediateDirectories: true)
    }

    private static func lineCount(of file: URL) -> Int {
        guard let data = try? Data(contentsOf: file) else { return 0 }
        let bytes = Array(data)
        var count = 0
        for byte in bytes where byte == 0x0A { count += 1 }
        if bytes.isEmpty || bytes.last != 0x0A { count += 1 }
        return count
    }

    enum StoreError: LocalizedError {
        case invalidName
        var errorDescription: String? {
            switch self {
            case .invalidName: return "Недопустимое имя файла."
            }
        }
    }
}