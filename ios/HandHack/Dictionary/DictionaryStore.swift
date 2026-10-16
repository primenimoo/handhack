import Foundation

/// Dictionary files live under Documents/Dictionaries as plain text, one candidate per line,
/// and are addressed by a sanitised file name that is also the DIC utt-id used by the UI.
enum DictionaryStore {
    struct Dictionary: Identifiable {
        let id: String
        let name: String
        /// Physical lines including blank ones.
        let lines: Int
        /// Non-blank lines: exactly the candidates the cracker will try.
        let entries: Int
        /// Distinct entries.
        let unique: Int
        let hadDecodingErrors: Bool

        /// The number the cracker will actually attempt. Kept under the old name so existing
        /// call sites keep compiling, but it is now entries rather than raw newlines.
        var size: Int { entries }

        /// A short, honest description: "N записей" and, only when it differs, how many are
        /// distinct. Plain "строк" was misleading because blank lines were counted as passwords.
        var summary: String {
            var parts = ["\(entries.formatted()) записей"]
            if unique != entries { parts.append("\(unique.formatted()) уникальных") }
            if hadDecodingErrors { parts.append("повреждён") }
            return parts.joined(separator: " · ")
        }
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

    /// The ids of the dictionaries the user has ticked for the next run, kept in UserDefaults.
    /// Until the first tick the set is deliberately empty and every dictionary counts as enabled,
    /// so a fresh install works without configuration; once the user touches a checkbox their
    /// choice is remembered verbatim.
    private static let enabledKey = "enabledDictionaryIDs"

    /// The sha256 the catalogue advertised for every dictionary it installed. It is kept so a
    /// later manifest can tell "already on this version" from "there is a newer one to fetch";
    /// hand-imported files have no entry here and are never offered as updates.
    private static let managedKey = "managedDictionaryHashes"

    static func managedHashes() -> [String: String] {
        guard let data = UserDefaults.standard.data(forKey: managedKey),
              let dictionary = try? JSONDecoder().decode([String: String].self, from: data)
        else { return [:] }
        return dictionary
    }

    static func managedHash(id: String) -> String? {
        managedHashes()[id]
    }

    static func setManagedHash(id: String, _ value: String) {
        var hashes = managedHashes()
        hashes[id] = value
        guard let data = try? JSONEncoder().encode(hashes) else { return }
        UserDefaults.standard.set(data, forKey: managedKey)
    }

    private static func clearManagedHash(id: String) {
        var hashes = managedHashes()
        guard hashes.removeValue(forKey: id) != nil,
              let data = try? JSONEncoder().encode(hashes) else { return }
        UserDefaults.standard.set(data, forKey: managedKey)
    }

    static var hasExplicitSelection: Bool {
        UserDefaults.standard.object(forKey: enabledKey) != nil
    }

    static var enabledIDs: Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: enabledKey) ?? [])
    }

    static func setEnabledIDs(_ ids: Set<String>) {
        UserDefaults.standard.set(Array(ids), forKey: enabledKey)
    }

    static func isEnabled(_ id: String) -> Bool {
        hasExplicitSelection ? enabledIDs.contains(id) : true
    }

    static func setEnabled(_ id: String, _ enabled: Bool) {
        var ids = hasExplicitSelection ? enabledIDs : Set(all.map { $0.id })
        if enabled { ids.insert(id) } else { ids.remove(id) }
        setEnabledIDs(ids)
    }

    static func setAllEnabled(_ enabled: Bool) {
        setEnabledIDs(enabled ? Set(all.map { $0.id }) : [])
    }

    /// The dictionaries to run, in list order: the ticked ones, or all of them when the user has
    /// never unticked anything.
    static var enabled: [Dictionary] {
        guard hasExplicitSelection else { return all }
        let ids = enabledIDs
        return all.filter { ids.contains($0.id) }
    }

    static var all: [Dictionary] {
        ensureDirectory()
        let files = (try? FileManager.default.contentsOfDirectory(
            at: storeDirectory, includingPropertiesForKeys: nil)) ?? []
        return files
            .filter { allowedExtensions.contains($0.pathExtension.lowercased()) }
            .map { file in
                let id = file.deletingPathExtension().lastPathComponent
                let stats = analysis(of: file)
                return Dictionary(id: id,
                                  name: readableName(id),
                                  lines: stats.totalLines,
                                  entries: stats.entryLines,
                                  unique: stats.uniqueEntries,
                                  hadDecodingErrors: stats.hadDecodingErrors)
            }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    static func fetchSize(_ id: String) -> Int {
        analysis(of: url(id: id)).entryLines
    }

    static func fetchAnalysis(id: String) -> DictionaryText.Analysis {
        analysis(of: url(id: id))
    }

    @discardableResult
    static func add(from source: URL, name: String) throws -> DictionaryText.Analysis {
        let data = try Data(contentsOf: source)
        return try add(bytes: Array(data), name: name)
    }

    /// Imports a word list after validating it. Empty files and files with no non-blank line are
    /// refused outright, and binary data is refused rather than being copied verbatim and later
    /// reported as a successful import, which is what the old code did.
    @discardableResult
    static func add(bytes: [UInt8], name: String) throws -> DictionaryText.Analysis {
        ensureDirectory()
        let safeName = sanitize(name)
        guard !safeName.isEmpty else { throw StoreError.invalidName }
        let analysis = DictionaryText.analyze(bytes)
        guard analysis.entryLines > 0 else { throw StoreError.noEntries }
        guard !analysis.hadDecodingErrors else { throw StoreError.binaryData }
        let destination = storeDirectory
            .appendingPathComponent(safeName)
            .appendingPathExtension("txt")
        try Data(bytes).write(to: destination, options: .atomic)
        return analysis
    }

    static func remove(id: String) {
        try? FileManager.default.removeItem(at: url(id: id))
        clearManagedHash(id: id)
    }

    static func url(id: String) -> URL {
        storeDirectory.appendingPathComponent("\(id).txt")
    }

    static func contents(id: String) -> [UInt8]? {
        guard let data = try? Data(contentsOf: url(id: id)) else { return nil }
        return Array(data)
    }

    static func exists(id: String) -> Bool {
        FileManager.default.fileExists(atPath: url(id: id).path)
    }

    static func text(id: String) -> String {
        guard let data = try? Data(contentsOf: url(id: id)) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    static func overwrite(id: String, text: String) throws {
        ensureDirectory()
        try Data(text.utf8).write(to: url(id: id), options: .atomic)
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

    private static func analysis(of file: URL) -> DictionaryText.Analysis {
        guard let data = try? Data(contentsOf: file) else {
            return DictionaryText.analyze([])
        }
        return DictionaryText.analyze(Array(data))
    }

    enum StoreError: LocalizedError {
        case invalidName
        case noEntries
        case binaryData
        var errorDescription: String? {
            switch self {
            case .invalidName: return "Недопустимое имя файла."
            case .noEntries: return "В файле нет ни одной строки с паролем."
            case .binaryData: return "Файл не похож на текстовый словарь — похоже, это двоичные данные."
            }
        }
    }
}