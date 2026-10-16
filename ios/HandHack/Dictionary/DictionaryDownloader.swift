import Combine
import CryptoKit
import Foundation

/// A dictionary offered by the HandHack catalogue that lives on GitHub. The manifest is the single
/// source of truth for what can be pulled down, so the UI never fetches an arbitrary URL: every
/// entry resolves against the fixed `DictionaryCatalogue.baseURL`.
struct RemoteDictionary: Identifiable, Hashable {
    let id: String
    let title: String
    let detail: String
    let file: String
    let bytes: Int
    let lines: Int
    let sha256: String
    let version: Int
    let recommended: Bool

    var url: URL { DictionaryCatalogue.baseURL.appendingPathComponent(file) }
}

/// The wire format of `dictionaries/manifest.json`. Fields beyond the identifiers are optional so
/// a half-written manifest still decodes instead of breaking the whole catalogue.
struct DictionaryManifest: Decodable {
    let version: Int
    let databases: [Entry]

    struct Entry: Decodable {
        let id: String
        let title: String
        let detail: String
        let file: String
        let lines: Int?
        let bytes: Int?
        let sha256: String?
        let version: Int?
        let recommended: Bool?
    }
}

enum DictionaryCatalogue {
    static let baseURL = URL(string: "https://raw.githubusercontent.com/primenimoo/handhack/main/dictionaries/")!
    static let manifestURL = baseURL.appendingPathComponent("manifest.json")

    /// Offered when the manifest cannot be reached (for example the very first launch is offline)
    /// so the install step still has something useful to say. Title and detail match the live
    /// manifest; the checksum is left empty, which skips verification for this single entry.
    static let fallback: [RemoteDictionary] = [
        RemoteDictionary(id: "special", title: "Special",
                         detail: "Частые пароли (базовый список)", file: "special.txt",
                         bytes: 141_693, lines: 13_846, sha256: "", version: 1,
                         recommended: true)
    ]

    static func remote(_ entry: DictionaryManifest.Entry) -> RemoteDictionary {
        RemoteDictionary(id: entry.id, title: entry.title, detail: entry.detail, file: entry.file,
                         bytes: entry.bytes ?? 0, lines: entry.lines ?? 0,
                         sha256: entry.sha256 ?? "", version: entry.version ?? 1,
                         recommended: entry.recommended ?? true)
    }
}

/// Whether a catalogue entry is absent, current, or behind the manifest.
enum DictionaryStatus {
    case notInstalled
    case installed
    case updateAvailable
}

/// Fetches the manifest, streams dictionaries down with byte progress, verifies their checksum and
/// hands the bytes to `DictionaryStore` so a downloaded file behaves exactly like an imported one.
@MainActor
final class DictionaryDownloader: ObservableObject {
    @Published var isDownloading = false
    @Published var progress: Double = 0
    @Published var statusText = ""
    @Published var activeTitle: String?
    @Published var catalogue: [RemoteDictionary] = []
    @Published var isLoadingCatalogue = false
    @Published var lastError: String?
    @Published var installedMessage: String?

    /// Everything in the catalogue that is missing or behind the manifest, in list order.
    var actionable: [RemoteDictionary] {
        catalogue.filter { status(of: $0) != .installed }
    }

    func status(of database: RemoteDictionary) -> DictionaryStatus {
        guard DictionaryStore.exists(id: database.id) else { return .notInstalled }
        guard !database.sha256.isEmpty, let installed = DictionaryStore.managedHash(id: database.id)
        else { return .installed }
        return installed == database.sha256 ? .installed : .updateAvailable
    }

    /// Loads the manifest and, with it, the current status of every dictionary.
    func refreshCatalogue() async {
        isLoadingCatalogue = true
        lastError = nil
        defer { isLoadingCatalogue = false }
        do {
            let (data, _) = try await URLSession.shared.data(from: DictionaryCatalogue.manifestURL)
            let manifest = try JSONDecoder().decode(DictionaryManifest.self, from: data)
            catalogue = manifest.databases.map(DictionaryCatalogue.remote)
            if catalogue.isEmpty { catalogue = DictionaryCatalogue.fallback }
        } catch {
            if catalogue.isEmpty { catalogue = DictionaryCatalogue.fallback }
            lastError = error.localizedDescription
        }
    }

    @discardableResult
    func install(_ database: RemoteDictionary) async throws -> Int {
        isDownloading = true
        progress = 0
        activeTitle = database.title
        statusText = "Подключение…"
        defer {
            isDownloading = false
            activeTitle = nil
        }

        let (bytes, response) = try await URLSession.shared.bytes(from: database.url)
        let total = response.expectedContentLength
        var data = Data()
        if total > 0 { data.reserveCapacity(Int(total)) }

        var received = 0
        for try await byte in bytes {
            data.append(byte)
            received += 1
            if total > 0, received % 4096 == 0 {
                progress = Double(received) / Double(total)
                statusText = "Скачано \(received / 1024) КБ из \(Int(total) / 1024) КБ"
            }
        }

        if !database.sha256.isEmpty {
            let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            guard digest == database.sha256 else { throw DownloadError.checksumMismatch }
        }

        progress = 1
        statusText = "Сохранение…"
        try DictionaryStore.add(bytes: Array(data), name: database.id)
        DictionaryStore.setManagedHash(id: database.id, database.sha256)
        return data.count
    }

    /// Installs each entry in turn, keeping the progress UI alive across the batch. Failures are
    /// collected into `lastError` instead of aborting, so one bad file does not sink the rest.
    func installAll(_ databases: [RemoteDictionary]) async {
        guard !databases.isEmpty else { return }
        lastError = nil
        installedMessage = nil
        var succeeded = 0
        for database in databases {
            do {
                _ = try await install(database)
                succeeded += 1
            } catch {
                lastError = "\(database.title): \(error.localizedDescription)"
            }
        }
        if succeeded > 0 {
            installedMessage = succeeded == 1
                ? "Готово: 1 база данных"
                : "Готово: баз данных — \(succeeded)"
        }
    }

    enum DownloadError: LocalizedError {
        case checksumMismatch
        var errorDescription: String? {
            switch self {
            case .checksumMismatch: return "Проверка контрольной суммы не прошла."
            }
        }
    }
}
