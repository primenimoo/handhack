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
        .supportToolbar()
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

    @State private var dictionaries: [DictionaryStore.Dictionary] = []
    @State private var enabledIDs: Set<String> = []

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            dictionarySelector

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
        .onAppear(perform: reloadDictionaries)
    }

    private var enabledDictionaries: [DictionaryStore.Dictionary] {
        dictionaries.filter { enabledIDs.contains($0.id) }
    }

    /// The tick list the run will use. Every dictionary is ticked out of the box; the choice is
    /// remembered across captures, so a user who only trusts their own list does not re-tick it.
    private var dictionarySelector: some View {
        DisclosureGroup {
            if dictionaries.isEmpty {
                Text("Нет словарей — добавьте их на вкладке «Словари».")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(dictionaries) { dictionary in
                    Toggle(isOn: Binding(
                        get: { enabledIDs.contains(dictionary.id) },
                        set: { setEnabled(dictionary.id, $0) })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(dictionary.name).font(.subheadline)
                            Text("\(dictionary.size.formatted()) строк")
                                .font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                }
                HStack {
                    Button("Выбрать все") { selectAll(true) }
                    Spacer()
                    Button("Снять все") { selectAll(false) }
                }
                .font(.caption)
            }
        } label: {
            HStack {
                Image(systemName: "books.vertical")
                Text(enabledIDs.isEmpty
                     ? "Выберите словари"
                     : "Словари: \(enabledIDs.count) из \(dictionaries.count)")
                    .lineLimit(1)
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
        .disabled(runner.isRunning || enabledDictionaries.isEmpty || targets.isEmpty)
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
            if let label = runner.currentDictionary, runner.dictionaryTotal > 1 {
                Text("Словарь \(runner.dictionaryIndex)/\(runner.dictionaryTotal): \(label)")
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
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
                    CopyPasswordButton(password: password)
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

    private func reloadDictionaries() {
        dictionaries = DictionaryStore.all
        enabledIDs = Set(DictionaryStore.enabled.map { $0.id })
    }

    private func setEnabled(_ id: String, _ enabled: Bool) {
        if enabled { enabledIDs.insert(id) } else { enabledIDs.remove(id) }
        DictionaryStore.setEnabledIDs(enabledIDs)
    }

    private func selectAll(_ enabled: Bool) {
        enabledIDs = enabled ? Set(dictionaries.map { $0.id }) : []
        DictionaryStore.setEnabledIDs(enabledIDs)
    }

    private func start() {
        let selected = enabledDictionaries
        let inputs = selected.compactMap { dictionary -> AttackRunner.DictionaryInput? in
            guard let bytes = DictionaryStore.contents(id: dictionary.id) else { return nil }
            return AttackRunner.DictionaryInput(name: dictionary.name, bytes: bytes)
        }
        guard !inputs.isEmpty else { return }
        let names = selected.count == 1
            ? selected[0].name
            : "\(selected.count) словар\(selected.count % 10 == 1 && selected.count != 11 ? "ь" : "ей")"

        runner.start(
            targets: targets,
            dictionaries: inputs,
            notifyOnFinish: app.settings.notifyOnFinish,
            hapticsEnabled: app.settings.hapticsEnabled) { outcome, duration in
                record(outcome, duration: duration, dictionaryName: names)
            }
    }

    private func record(_ outcome: AnalysisEngine.Outcome,
                        duration: Double,
                        dictionaryName: String) {
        let result: CrackResult
        switch outcome {
        case .found(let password, let ssid, let attempts):
            result = CrackResult(captureName: capture.fileName, ssid: ssid, password: password,
                                 attempts: attempts, durationSeconds: duration,
                                 dictionaryName: runner.currentDictionary ?? dictionaryName, found: true)
        case .notFound(let attempts):
            result = CrackResult(captureName: capture.fileName,
                                 ssid: targets.first?.displaySsid ?? "",
                                 password: nil, attempts: attempts, durationSeconds: duration,
                                 dictionaryName: dictionaryName, found: false)
        case .stopped(let attempts):
            result = CrackResult(captureName: capture.fileName,
                                 ssid: targets.first?.displaySsid ?? "",
                                 password: nil, attempts: attempts, durationSeconds: duration,
                                 dictionaryName: dictionaryName, found: false)
        }
        app.recordResult(result)
    }
}
