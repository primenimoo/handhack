import SwiftUI
import UIKit

struct CaptureDetailView: View {
    @EnvironmentObject private var app: AppState
    let capture: CaptureRecord

    @State private var targets: [HandshakeTarget] = []
    @State private var loading = true

    var body: some View {
        List {
            Section("Запись") {
                LabeledContent("Файл", value: capture.fileName)
                LabeledContent("Формат", value: capture.format.label)
                if !capture.notes.isEmpty {
                    ForEach(capture.notes, id: \.self) { note in
                        Text(note).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }

            if loading {
                Section { ProgressView().frame(maxWidth: .infinity) }
            } else if targets.isEmpty {
                Section("Сети") {
                    Text("Для этой записи нечего проверять.")
                        .foregroundStyle(.secondary)
                }
            } else {
                Section("Сети (\(targets.count))") {
                    ForEach(targets) { target in
                        TargetRow(target: target)
                    }
                }
                Section {
                    AttackControl(capture: capture, targets: targets)
                } footer: {
                    Text("Проверка идёт по словарю и полностью на устройстве.")
                }
            }
        }
        .navigationTitle(capture.ssids.first ?? "Запись")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: load)
    }

    private func load() {
        guard loading else { return }
        let capture = self.capture
        DispatchQueue.global(qos: .userInitiated).async {
            let parsed = capture.targets()
            DispatchQueue.main.async {
                targets = parsed
                loading = false
            }
        }
    }
}

private struct TargetRow: View {
    let target: HandshakeTarget

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(target.displaySsid)
                .font(.subheadline.weight(.semibold))
            HStack(spacing: 8) {
                Text(target.evidence.label)
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(AppTheme.accent.opacity(0.18), in: Capsule())
                if let bssid = target.bssidHex {
                    Text(bssid)
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}

/// Dictionary picker, run button, live progress and the final result, all in one section.
private struct AttackControl: View {
    @EnvironmentObject private var app: AppState
    @StateObject private var runner = AttackRunner()

    let capture: CaptureRecord
    let targets: [HandshakeTarget]

    @AppStorage("selectedDictionaryID") private var selectedDictionaryID = ""
    @State private var dictionaries: [DictionaryStore.Dictionary] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            dictionaryMenu

            switch runner.phase {
            case .idle:
                startButton
            case .running(let progress):
                runningView(progress)
            case .finished(let outcome):
                resultView(outcome)
                startButton
            }
        }
        .padding(.vertical, 4)
        .onAppear { dictionaries = DictionaryStore.all }
    }

    private var selected: DictionaryStore.Dictionary? {
        dictionaries.first { $0.id == selectedDictionaryID }
    }

    private var dictionaryMenu: some View {
        Menu {
            if dictionaries.isEmpty {
                Text("Нет словарей")
            } else {
                ForEach(dictionaries) { dictionary in
                    Button {
                        selectedDictionaryID = dictionary.id
                    } label: {
                        if dictionary.id == selectedDictionaryID {
                            Label(dictionary.name, systemImage: "checkmark")
                        } else {
                            Text(dictionary.name)
                        }
                    }
                }
            }
        } label: {
            HStack {
                Image(systemName: "books.vertical")
                Text(selected?.name ?? "Выбрать словарь")
                    .lineLimit(1)
                Spacer()
                Image(systemName: "chevron.up.chevron.down").font(.caption)
            }
            .font(.subheadline)
        }
        .disabled(runner.isRunning)
    }

    private var startButton: some View {
        Button {
            start()
        } label: {
            HStack {
                Spacer()
                if runner.isRunning {
                    ProgressView().tint(.white)
                } else {
                    Label("Начать подбор", systemImage: "play.fill")
                }
                Spacer()
            }
        }
        .buttonStyle(.borderedProminent)
        .tint(AppTheme.accent)
        .disabled(runner.isRunning || selected == nil || targets.isEmpty)
    }

    private func runningView(_ progress: AnalysisEngine.Progress) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            ProgressView(value: fraction(progress))
                .tint(AppTheme.accent)
            HStack {
                Text("Попыток: \(progress.attempts.formatted())")
                Spacer()
                Text("\(Int(progress.peekRate).formatted()) /с")
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            Text("Слово: \(progress.currentWord)")
                .font(.caption.monospaced())
                .lineLimit(1)
            Button(role: .destructive) {
                runner.stop()
            } label: {
                Label("Остановить", systemImage: "stop.fill")
            }
            .font(.subheadline)
        }
    }

    private func resultView(_ outcome: AnalysisEngine.Outcome) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            switch outcome {
            case .found(let password, let ssid, let attempts):
                Label("Подбор завершён", systemImage: "checkmark.seal.fill")
                    .foregroundStyle(AppTheme.success)
                    .font(.headline)
                Text("Сеть: \(ssid)").font(.subheadline)
                HStack {
                    Text(password)
                        .font(.title3.monospaced().weight(.semibold))
                        .textSelection(.enabled)
                    Spacer()
                    Button {
                        UIPasteboard.general.string = password
                    } label: {
                        Image(systemName: "doc.on.doc")
                    }
                    .buttonStyle(.bordered)
                }
                Text("Попыток: \(attempts.formatted())")
                    .font(.caption).foregroundStyle(.secondary)

            case .notFound(let attempts):
                Label("Пароль не найден", systemImage: "xmark.seal.fill")
                    .foregroundStyle(AppTheme.danger)
                    .font(.headline)
                Text("Попыток: \(attempts.formatted()). Попробуйте другой словарь.")
                    .font(.caption).foregroundStyle(.secondary)

            case .stopped(let attempts):
                Label("Подбор остановлен", systemImage: "stop.circle.fill")
                    .foregroundStyle(.orange)
                    .font(.headline)
                Text("Попыток: \(attempts.formatted())")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .glassCard()
    }

    private func fraction(_ progress: AnalysisEngine.Progress) -> Double {
        guard progress.totalLines > 0 else { return 0 }
        return min(1, Double(progress.processedLines) / Double(progress.totalLines))
    }

    private func start() {
        guard let dictionary = selected else { return }
        guard let bytes = DictionaryStore.contents(id: dictionary.id) else { return }
        runner.start(
            targets: targets,
            dictionaryBytes: bytes,
            notifyOnFinish: app.settings.notifyOnFinish,
            hapticsEnabled: app.settings.hapticsEnabled) { outcome, duration in
                record(outcome, duration: duration, dictionary: dictionary)
            }
    }

    private func record(_ outcome: AnalysisEngine.Outcome,
                        duration: Double,
                        dictionary: DictionaryStore.Dictionary) {
        let result: CrackResult
        switch outcome {
        case .found(let password, let ssid, let attempts):
            result = CrackResult(captureName: capture.fileName, ssid: ssid, password: password,
                                 attempts: attempts, durationSeconds: duration,
                                 dictionaryName: dictionary.name, found: true)
        case .notFound(let attempts):
            result = CrackResult(captureName: capture.fileName,
                                 ssid: targets.first?.displaySsid ?? "",
                                 password: nil, attempts: attempts, durationSeconds: duration,
                                 dictionaryName: dictionary.name, found: false)
        case .stopped(let attempts):
            result = CrackResult(captureName: capture.fileName,
                                 ssid: targets.first?.displaySsid ?? "",
                                 password: nil, attempts: attempts, durationSeconds: duration,
                                 dictionaryName: dictionary.name, found: false)
        }
        app.recordResult(result)
    }
}