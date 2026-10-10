import Combine
import Foundation
import UIKit
import UserNotifications

/// Drives one dictionary run and exposes it to SwiftUI as three phases. The engine itself runs
/// on its own queue; every callback is bounced back to the main thread here.
final class AttackRunner: ObservableObject {
    /// One word list to feed the engine, already read into memory.
    struct DictionaryInput {
        let name: String
        let bytes: [UInt8]
    }

    enum Phase {
        case idle
        case running(AnalysisEngine.Progress)
        case finished(AnalysisEngine.Outcome)
    }

    @Published private(set) var phase: Phase = .idle
    /// The dictionary currently being tried, and how far through the queue it is.
    @Published private(set) var currentDictionary: String?
    @Published private(set) var dictionaryIndex = 0
    @Published private(set) var dictionaryTotal = 0

    private let engine = AnalysisEngine()
    private var startedAt: Date?
    private var completion: ((AnalysisEngine.Outcome, Double) -> Void)?
    private var notifyOnFinish = false
    private var hapticsEnabled = true

    private var targets: [HandshakeTarget] = []
    private var dictionaries: [DictionaryInput] = []
    private var nextDictionary = 0
    private var accumulatedAttempts = 0

    var isRunning: Bool {
        if case .running = phase { return true }
        return false
    }

    var wasFound: Bool {
        if case .finished(.found) = phase { return true }
        return false
    }

    /// Runs every dictionary in turn until one finds the password, the user stops the run, or the
    /// queue is exhausted. Attempts are summed across the dictionaries so the reported total is
    /// the real work done, not just the last list's.
    func start(
        targets: [HandshakeTarget],
        dictionaries: [DictionaryInput],
        notifyOnFinish: Bool,
        hapticsEnabled: Bool,
        onComplete: @escaping (AnalysisEngine.Outcome, Double) -> Void
    ) {
        guard !isRunning else { return }
        guard !targets.isEmpty, !dictionaries.isEmpty else { return }

        self.targets = targets
        self.dictionaries = dictionaries
        self.nextDictionary = 0
        self.accumulatedAttempts = 0
        self.notifyOnFinish = notifyOnFinish
        self.hapticsEnabled = hapticsEnabled

        phase = .idle
        startedAt = Date()
        completion = onComplete
        dictionaryTotal = dictionaries.count
        dictionaryIndex = 0

        runCurrentDictionary()
    }

    func stop() {
        engine.stop()
    }

    private func runCurrentDictionary() {
        guard nextDictionary < dictionaries.count else {
            finish(.notFound(attempts: accumulatedAttempts))
            return
        }
        let dictionary = dictionaries[nextDictionary]
        currentDictionary = dictionary.name
        dictionaryIndex = nextDictionary + 1

        engine.start(
            targets: targets,
            dictionaryBytes: dictionary.bytes,
            onProgress: { [weak self] progress in
                DispatchQueue.main.async {
                    guard let self, self.isRunning || self.phaseIsIdle else { return }
                    self.phase = .running(progress)
                }
            },
            onFinish: { [weak self] outcome in
                guard let self else { return }
                switch outcome {
                case .found(let password, let ssid, let attempts):
                    self.finish(.found(password: password, ssid: ssid,
                                       attempts: self.accumulatedAttempts + attempts))
                case .notFound(let attempts):
                    self.accumulatedAttempts += attempts
                    self.nextDictionary += 1
                    self.runCurrentDictionary()
                case .stopped(let attempts):
                    self.accumulatedAttempts += attempts
                    self.finish(.stopped(attempts: self.accumulatedAttempts))
                }
            })
    }

    private var phaseIsIdle: Bool {
        if case .idle = phase { return true }
        return false
    }

    private func finish(_ outcome: AnalysisEngine.Outcome) {
        let duration = startedAt.map { Date().timeIntervalSince($0) } ?? 0
        phase = .finished(outcome)
        // The completion handler still needs to know which dictionary was in flight when the run
        // ended, so it is cleared only afterwards.
        completion?(outcome, duration)
        completion = nil
        currentDictionary = nil
        startedAt = nil
        if hapticsEnabled { Self.haptic(for: outcome) }
        if notifyOnFinish { Self.notify(outcome) }
    }

    private static func haptic(for outcome: AnalysisEngine.Outcome) {
        let generator = UINotificationFeedbackGenerator()
        switch outcome {
        case .found: generator.notificationOccurred(.success)
        case .notFound: generator.notificationOccurred(.warning)
        case .stopped: generator.notificationOccurred(.error)
        }
    }

    private static func notify(_ outcome: AnalysisEngine.Outcome) {
        let content = UNMutableNotificationContent()
        content.title = "HandHack"
        switch outcome {
        case .found(let password, let ssid, _):
            content.body = "Подбор завершён: сеть «\(ssid)», пароль \(password)"
        case .notFound:
            content.body = "Подбор завершён: пароль не найден."
        case .stopped:
            content.body = "Подбор остановлен."
        }
        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: content,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: 0.2, repeats: false))
        UNUserNotificationCenter.current().add(request)
    }
}