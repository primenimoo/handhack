import SwiftUI
import UIKit

struct CaptureDetailView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss
    let capture: CaptureRecord

    @State private var targets: [HandshakeTarget] = []
    @State private var loading = true
    @State private var exportFile: URL?
    @State private var confirmDelete = false

    var body: some View {
        List {
            Section("Запись") {
                LabeledContent("Файл", value: capture.fileName)
                LabeledContent("Формат", value: capture.format.label)
                if !targets.isEmpty {
                    LabeledContent("Сетей", value: "\(targets.count)")
                }
                if !targets.isEmpty, let url = exportFile {
                    ShareLink(item: url) {
                        Label("Конвертировать и поделиться (.\(exportExtension))",
                              systemImage: "arrowshape.turn.up.right")
                    }
                }
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
                    SmartAttackControl(capture: capture, targets: targets)
                } footer: {
                    Text("Умный анализ читает файл, выбирает метод и скорость и сам запускает подбор по словарю — всё на устройстве.")
                }
            }
        }
        .navigationTitle(capture.ssids.first ?? "Запись")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(role: .destructive) { confirmDelete = true } label: {
                    Image(systemName: "trash")
                }
                .tint(.red)
                .accessibilityLabel("Удалить запись")
            }
        }
        .confirmationDialog("Удалить эту запись?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Удалить", role: .destructive) {
                app.deleteCapture(capture)
                dismiss()
            }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text("Файл записи будет удалён без возможности восстановления.")
        }
        .supportToolbar()
        .onAppear(perform: load)
    }

    private var exportName: String {
        HandshakeConverter.exportFileName(for: targets, originalFile: capture.fileName)
    }

    private var exportExtension: String {
        (exportName as NSString).pathExtension
    }

    private func load() {
        guard loading else { return }
        let capture = self.capture
        DispatchQueue.global(qos: .userInitiated).async {
            let parsed = capture.targets()
            let name = HandshakeConverter.exportFileName(for: parsed, originalFile: capture.fileName)
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
            try? HandshakeConverter.convert(targets: parsed).write(to: url, atomically: true, encoding: .utf8)
            DispatchQueue.main.async {
                targets = parsed
                exportFile = url
                loading = false
            }
        }
    }
}

private struct TargetRow: View {
    let target: HandshakeTarget

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "lock.shield")
                .font(.title3)
                .foregroundStyle(AppTheme.evidenceColor(target.evidence))
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 4) {
                Text(target.displaySsid)
                    .font(.subheadline.weight(.semibold))
                if let bssid = target.bssidHex {
                    Text(bssid)
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            Text(target.evidence.label)
                .font(.caption2.weight(.semibold))
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .foregroundStyle(AppTheme.evidenceColor(target.evidence))
                .background(AppTheme.evidenceColor(target.evidence).opacity(0.14), in: Capsule())
        }
        .padding(.vertical, 2)
    }
}

/// The smart flow: analyse the capture, show the plan, then run the whole search by itself.
/// The manual dictionary tick-list is still there so the user can steer which words are tried.
private struct SmartAttackControl: View {
    @EnvironmentObject private var app: AppState
    @StateObject private var runner = AttackRunner()

    let capture: CaptureRecord
    let targets: [HandshakeTarget]

    @State private var dictionaries: [DictionaryStore.Dictionary] = []
    @State private var enabledIDs: Set<String> = []
    @State private var showingPicker = false
    @State private var plan: SmartPlan?
    @State private var analyzing = false
    @State private var showMessage = false
    @State private var message = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header

            dictionarySelector

            switch runner.phase {
            case .idle:
                if analyzing {
                    analyzingView
                } else if let plan {
                    planView(plan)
                } else {
                    analyzeButton
                }
            case .running(let progress):
                runningView(progress)
            case .finished(let outcome):
                resultView(outcome)
                runAgainButton
            }
        }
        .padding(.vertical, 4)
        .onAppear(perform: reloadDictionaries)
        .alert("Умный анализ", isPresented: $showMessage) {
            Button("Хорошо", role: .cancel) {}
        } message: {
            Text(message)
        }
    }

    private var enabledDictionaries: [DictionaryStore.Dictionary] {
        dictionaries.filter { enabledIDs.contains($0.id) }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "wand.and.stars")
                .font(.title3)
                .foregroundStyle(AppTheme.accent)
            VStack(alignment: .leading, spacing: 2) {
                Text("Умный анализ")
                    .font(.headline)
                Text("Прочитает файл, выберет метод и скорость и сам запустит подбор.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var dictionarySelector: some View {
        Button {
            showingPicker = true
        } label: {
            HStack {
                Image(systemName: "books.vertical")
                Text(enabledIDs.isEmpty
                     ? "Выберите словари"
                     : "Словари: \(enabledIDs.count) из \(dictionaries.count)")
                    .lineLimit(1)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .font(.subheadline)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.primary)
        .disabled(runner.isRunning)
        .sheet(isPresented: $showingPicker) {
            DictionaryPicker(
                dictionaries: dictionaries,
                enabledIDs: $enabledIDs,
                onChange: { DictionaryStore.setEnabledIDs($0) },
                onReload: reloadDictionaries)
        }
    }

    private var analyzeButton: some View {
        Button {
            analyze()
        } label: {
            HStack {
                Spacer()
                Label("Проанализировать", systemImage: "magnifyingglass")
                Spacer()
            }
        }
        .buttonStyle(.borderedProminent)
        .tint(AppTheme.accent)
    }

    private var analyzingView: some View {
        VStack(spacing: 10) {
            ProgressView()
            Text("Анализируем файл… определяем тип и план подбора")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 6)
    }

    private func planView(_ plan: SmartPlan) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(plan.summaryLines, id: \.self) { line in
                HStack(alignment: .top, spacing: 6) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.caption)
                        .foregroundStyle(AppTheme.success)
                    Text(line)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            HStack {
                Text("Скорость: \(app.settings.attackSpeed.title)")
                    .font(.caption.weight(.semibold))
                Spacer()
                Button {
                    startRun()
                } label: {
                    Label("Начать подбор", systemImage: "play.fill")
                }
                .buttonStyle(.borderedProminent)
                .tint(AppTheme.accent)
                .disabled(runner.isRunning || enabledDictionaries.isEmpty)
            }
        }
        .glassCard()
    }

    private func runningView(_ progress: AnalysisEngine.Progress) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            ProgressView(value: fraction(progress))
                .tint(AppTheme.accent)
            HStack {
                Text("Попыток: \(progress.attempts.formatted())")
                Spacer()
                Text("\(Int(progress.currentRate).formatted()) /с · ∅ \(Int(progress.averageRate).formatted()) /с")
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            if let label = runner.currentDictionary, runner.dictionaryTotal > 1 {
                Text("Словарь \(runner.dictionaryIndex)/\(runner.dictionaryTotal): \(label)")
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Text("Метод: \(methodText)")
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
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

    private var runAgainButton: some View {
        Button {
            startRun()
        } label: {
            HStack {
                Spacer()
                Label("Повторить подбор", systemImage: "arrow.clockwise")
                Spacer()
            }
        }
        .buttonStyle(.borderedProminent)
        .tint(AppTheme.accent)
        .disabled(runner.isRunning || enabledDictionaries.isEmpty)
    }

    private var methodText: String {
        var types: [CaptureEvidence] = []
        for evidence in CaptureEvidence.allCases where targets.contains(where: { $0.evidence == evidence }) {
            types.append(evidence)
        }
        return types.map(SmartAnalysis.evidenceTitle).joined(separator: ", ")
    }

    private func fraction(_ progress: AnalysisEngine.Progress) -> Double {
        guard progress.totalLines > 0 else { return 0 }
        return min(1, Double(progress.processedLines) / Double(progress.totalLines))
    }

    private func reloadDictionaries() {
        dictionaries = DictionaryStore.all
        enabledIDs = Set(DictionaryStore.enabled.map { $0.id })
    }

    private func enabledDictionaryInputs() -> [AttackRunner.DictionaryInput] {
        enabledDictionaries.compactMap { dictionary in
            guard let bytes = DictionaryStore.contents(id: dictionary.id) else { return nil }
            return AttackRunner.DictionaryInput(name: dictionary.name, bytes: bytes)
        }
    }

    private func dictionaryLabel(_ selected: [DictionaryStore.Dictionary]) -> String {
        guard !selected.isEmpty else { return "—" }
        if selected.count == 1 { return selected[0].name }
        return "\(selected.count) словар\(selected.count % 10 == 1 && selected.count != 11 ? "ь" : "ей")"
    }

    private func analyze() {
        guard !analyzing, !runner.isRunning else { return }
        guard !enabledDictionaryInputs().isEmpty else {
            message = "Нет включённых словарей — включите хотя бы один."
            showMessage = true
            return
        }
        analyzing = true
        plan = nil
        let inputs = enabledDictionaryInputs()
        let snapshot = targets
        let autoRun = app.settings.autoRunAfterAnalysis
        Task {
            // A deliberate beat so the "Анализируем файл…" phase reads instead of blinking.
            try? await Task.sleep(nanoseconds: 700_000_000)
            let computed = SmartAnalysis.plan(targets: snapshot, dictionaries: inputs)
            await MainActor.run {
                plan = computed
                analyzing = false
            }
            if autoRun {
                try? await Task.sleep(nanoseconds: 500_000_000)
                await MainActor.run {
                    guard !runner.isRunning else { return }
                    startRun()
                }
            }
        }
    }

    private func startRun() {
        let inputs = enabledDictionaryInputs()
        guard !inputs.isEmpty else {
            message = "Нет включённых словарей — включите хотя бы один."
            showMessage = true
            return
        }
        let label = dictionaryLabel(enabledDictionaries)
        let fallbackSsid = targets.first?.displaySsid ?? ""
        runner.start(
            targets: targets,
            dictionaries: inputs,
            speed: app.settings.attackSpeed,
            soundChoice: app.settings.finishSound,
            notifyOnFinish: app.settings.notifyOnFinish,
            hapticsEnabled: app.settings.hapticsEnabled,
            soundEnabled: app.settings.soundEnabled) { outcome, duration in
                let name = runner.currentDictionary ?? label
                app.addResult(outcome: outcome, durationSeconds: duration,
                              captureID: capture.id, captureName: capture.fileName,
                              fallbackSsid: fallbackSsid,
                              dictionaryName: name)
            }
    }
}

/// The sliding list of dictionaries a run can use. Ticking a row enables it without leaving the
/// sheet; tapping the name opens the dictionary itself so its words can be read and edited.
private struct DictionaryPicker: View {
    let dictionaries: [DictionaryStore.Dictionary]
    @Binding var enabledIDs: Set<String>
    let onChange: (Set<String>) -> Void
    let onReload: () -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if dictionaries.isEmpty {
                    ContentUnavailableView("Нет словарей",
                                           systemImage: "books.vertical",
                                           description: Text("Добавьте словари на вкладке «Словари»."))
                } else {
                    List {
                        ForEach(dictionaries) { dictionary in
                            HStack(spacing: 12) {
                                Button {
                                    setEnabled(dictionary.id, !enabledIDs.contains(dictionary.id))
                                } label: {
                                    Image(systemName: enabledIDs.contains(dictionary.id)
                                          ? "checkmark.circle.fill" : "circle")
                                        .font(.title3)
                                        .foregroundStyle(enabledIDs.contains(dictionary.id)
                                                         ? AppTheme.accent : Color.secondary)
                                }
                                .buttonStyle(.plain)

                                NavigationLink {
                                    DictionaryEditorView(id: dictionary.id, name: dictionary.name,
                                                         onSave: onReload)
                                } label: {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(dictionary.name).font(.subheadline)
                                        Text(dictionary.summary)
                                            .font(.caption2).foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Словари")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .topBarLeading) {
                    Button("Все") { selectAll(true) }
                    Button("Ничего") { selectAll(false) }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Готово") { dismiss() }
                }
            }
        }
    }

    private func setEnabled(_ id: String, _ enabled: Bool) {
        var updated = enabledIDs
        if enabled { updated.insert(id) } else { updated.remove(id) }
        enabledIDs = updated
        onChange(updated)
    }

    private func selectAll(_ enabled: Bool) {
        let updated = enabled ? Set(dictionaries.map { $0.id }) : []
        enabledIDs = updated
        onChange(updated)
    }
}