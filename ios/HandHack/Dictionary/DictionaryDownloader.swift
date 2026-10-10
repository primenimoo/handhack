import Combine
import Foundation

/// A dictionary that lives on a remote host and can be pulled into the app in one tap. The
/// catalogue is compiled in, so the list is fixed and safe — no arbitrary URLs from the UI.
struct RemoteDictionary: Identifiable, Hashable {
    let id: String
    let title: String
    let detail: String
    let url: URL
}

enum RemoteDictionaryCatalog {
    static let all: [RemoteDictionary] = [
        RemoteDictionary(
            id: "special",
            title: "special",
            detail: "Частые пароли · 138 КБ",
            url: URL(string: "https://raw.githubusercontent.com/primenimoo/handhack/main/dictionaries/special.txt")!)
    ]
}

/// Streams a remote dictionary down, publishing byte progress for the UI, then hands the bytes
/// to `DictionaryStore` so the downloaded file behaves exactly like an imported one.
@MainActor
final class DictionaryDownloader: ObservableObject {
    @Published var isDownloading = false
    @Published var progress: Double = 0
    @Published var statusText = ""
    @Published var activeTitle: String?

    @discardableResult
    func download(_ remote: RemoteDictionary) async throws -> Int {
        isDownloading = true
        progress = 0
        activeTitle = remote.title
        statusText = "Подключение…"
        defer {
            isDownloading = false
            activeTitle = nil
        }

        let (bytes, response) = try await URLSession.shared.bytes(from: remote.url)
        let total = response.expectedContentLength
        var data = Data()
        if total > 0 { data.reserveCapacity(Int(total)) }

        var received = 0
        for try await byte in bytes {
            data.append(byte)
            received += 1
            if total > 0, received % 2048 == 0 {
                progress = Double(received) / Double(total)
                statusText = "Скачано \(received / 1024) КБ из \(Int(total) / 1024) КБ"
            }
        }

        progress = 1
        statusText = "Сохранение…"
        try DictionaryStore.add(bytes: Array(data), name: remote.id)
        return data.count
    }
}
