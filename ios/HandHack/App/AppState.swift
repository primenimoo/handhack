import Combine
import Foundation

/// The single source of truth for the app: imported captures, finished searches, settings and
/// the dictionary list live here and are persisted to Documents as one JSON file plus the files
/// in the folders the store owns.
final class AppState: ObservableObject {
    @Published var captures: [CaptureRecord] {
        didSet { persist() }
    }
    @Published var results: [CrackResult] {
        didSet { persist() }
    }
    @Published var settings: AppSettings {
        didSet { persist() }
    }

    init() {
        let saved: AppStateSnapshot? = AppFiles.loadState(AppStateSnapshot.self)
        captures = saved?.captures ?? []
        results = saved?.results ?? []
        settings = saved?.settings ?? .default
    }

    // MARK: Captures

    @discardableResult
    func importCapture(data: [UInt8], name: String) -> CaptureRecord {
        let record = CaptureRecord(data: data, name: name)
        record.persisted(data: data)
        if let index = captures.firstIndex(where: { $0.fileName == record.fileName }) {
            captures[index] = record
        } else {
            captures.insert(record, at: 0)
        }
        return record
    }

    func deleteCapture(_ capture: CaptureRecord) {
        try? FileManager.default.removeItem(at: capture.fileURL)
        captures.removeAll { $0.id == capture.id }
    }

    // MARK: Results

    func recordResult(_ result: CrackResult) {
        results.insert(result, at: 0)
        if results.count > 100 {
            results.removeLast(results.count - 100)
        }
    }

    func clearResults() {
        results.removeAll()
    }

    // MARK: Settings

    func setFirstLaunch(_ value: Bool) {
        guard settings.firstLaunch != value else { return }
        var copy = settings
        copy.firstLaunch = value
        settings = copy
    }

    func setHapticsEnabled(_ value: Bool) {
        guard settings.hapticsEnabled != value else { return }
        var copy = settings
        copy.hapticsEnabled = value
        settings = copy
    }

    func setNotifyOnFinish(_ value: Bool) {
        guard settings.notifyOnFinish != value else { return }
        var copy = settings
        copy.notifyOnFinish = value
        settings = copy
    }

    // MARK: Persistence

    private func persist() {
        let snapshot = AppStateSnapshot(captures: captures, results: results, settings: settings)
        AppFiles.saveState(snapshot)
    }
}

private struct AppStateSnapshot: Codable {
    let captures: [CaptureRecord]
    let results: [CrackResult]
    let settings: AppSettings
}