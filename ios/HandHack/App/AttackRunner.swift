import Combine
import Foundation
import UIKit
import UserNotifications

/// Drives one dictionary run and exposes it to SwiftUI as three phases. The engine itself runs
/// on its own queue; every callback is bounced back to the main thread here.
final class AttackRunner: ObservableObject {
    enum Phase {
        case idle
        case running(AnalysisEngine.Progress)
        case finished(AnalysisEngine.Outcome)
    }

    @Published private(set) var phase: Phase = .idle

    private let engine = AnalysisEngine()
    private var startedAt: Date?
    private var completion: ((AnalysisEngine.Outcome, Double) -> Void)?

    var isRunning: Bool {
        if case .running = phase { return true }
        return false
    }

    var wasFound: Bool {
        if case .finished(.found) = phase { return true }
        return false
    }

    func start(
        targets: [HandshakeTarget],
        dictionaryBytes: [UInt8],
        notifyOnFinish: Bool,
        hapticsEnabled: Bool,
        onComplete: @escaping (AnalysisEngine.Outcome, Double) -> Void
    ) {
        guard !isRunning else { return }
        guard !targets.isEmpty, !dictionaryBytes.isEmpty else { return }

        phase = .idle
        startedAt = Date()
        completion = onComplete

        engine.start(
            targets: targets,
            dictionaryBytes: dictionaryBytes,
            onProgress: { [weak self] progress in
                DispatchQueue.main.async {
                    guard let self, self.isRunning else { return }
                    self.phase = .running(progress)
                }
            },
            onFinish: { [weak self] outcome in
                guard let self else { return }
                let duration = self.startedAt.map { Date().timeIntervalSince($0) } ?? 0
                self.phase = .finished(outcome)
                self.startedAt = nil
                self.completion?(outcome, duration)
                self.completion = nil
                if hapticsEnabled { Self.haptic(for: outcome) }
                if notifyOnFinish { Self.notify(outcome) }
            })
    }

    func stop() {
        engine.stop()
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