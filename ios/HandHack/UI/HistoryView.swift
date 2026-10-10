import SwiftUI
import UIKit

struct HistoryView: View {
    @EnvironmentObject private var app: AppState
    @State private var confirmClear = false

    var body: some View {
        NavigationStack {
            Group {
                if app.results.isEmpty {
                    emptyState
                } else {
                    list
                }
            }
            .navigationTitle("История")
            .toolbar {
                if !app.results.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(role: .destructive) { confirmClear = true } label: {
                            Image(systemName: "trash")
                        }
                        .tint(.red)
                        .accessibilityLabel("Очистить историю")
                    }
                }
            }
            .confirmationDialog("Очистить всю историю?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Очистить", role: .destructive) { app.clearResults() }
                Button("Отмена", role: .cancel) {}
            }
            .supportToolbar()
            .brandToolbar()
        }
    }

    private var list: some View {
        List {
            ForEach(app.results) { result in
                HistoryResultRow(result: result) {
                    app.results.removeAll { $0.id == result.id }
                }
            }
            .onDelete { offsets in
                for index in offsets {
                    app.results.remove(at: index)
                }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "clock.arrow.circlepath")
                .font(.system(size: 46))
                .foregroundStyle(AppTheme.accent)
            Text("История пуста")
                .font(.title3.bold())
            Text("Здесь появятся результаты подбора.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding()
    }
}

/// One history record with its own «Повторить» and «Удалить», so an old run can be replayed
/// right from the list whenever the capture file and the dictionaries are still around.
private struct HistoryResultRow: View {
    @EnvironmentObject private var app: AppState
    let result: CrackResult
    var onDelete: () -> Void

    @StateObject private var runner = AttackRunner()
    @State private var running = false
    @State private var note: String?

    private var currentProgress: AnalysisEngine.Progress? {
        if case .running(let progress) = runner.phase { return progress }
        return nil
    }

    private var fraction: Double {
        guard let progress = currentProgress, progress.totalLines > 0 else { return 0 }
        return min(1, Double(progress.processedLines) / Double(progress.totalLines))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: result.found ? "checkmark.seal.fill" : "xmark.seal.fill")
                    .foregroundStyle(result.found ? AppTheme.success : AppTheme.danger)
                Text(result.ssid.isEmpty ? result.captureName : result.ssid)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Spacer()
                Text(result.date, style: .date)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            if let password = result.password, result.found {
                HStack {
                    Text("Пароль: \(password)")
                        .font(.callout.monospaced())
                        .textSelection(.enabled)
                    Spacer()
                    CopyPasswordButton(password: password)
                }
            }
            Text("\(result.statusText) · \(result.attempts.formatted()) попыток · \(Int(result.durationSeconds)) с · \(result.dictionaryName)")
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)

            if running {
                ProgressView(value: fraction)
                    .tint(AppTheme.accent)
                HStack {
                    Text("Повторный подбор… \(currentProgress?.attempts.formatted() ?? "")")
                    Spacer()
                    Button(role: .destructive) { runner.stop() } label: {
                        Label("Стоп", systemImage: "stop.fill")
                    }
                    .font(.caption)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            HStack(spacing: 18) {
                Button {
                    retry()
                } label: {
                    Label("Повторить", systemImage: "arrow.clockwise")
                }
                .font(.subheadline)
                .disabled(running)

                Button(role: .destructive) {
                    onDelete()
                } label: {
                    Label("Удалить", systemImage: "trash")
                }
                .font(.subheadline)
                .disabled(running)

                Spacer()
            }
            .padding(.top, 2)
        }
        .padding(.vertical, 2)
        .alert("Повторный подбор", isPresented: Binding(
            get: { note != nil },
            set: { if !$0 { note = nil } })) {
                Button("Хорошо", role: .cancel) {}
            } message: {
                Text(note ?? "")
            }
    }

    private func retry() {
        guard !running, !runner.isRunning else { return }
        let captureID = result.captureID
        let capture = app.captures.first(where: { $0.id == captureID })
            ?? app.captures.first(where: { $0.fileName == result.captureName })
        guard let capture else {
            note = "Файл записи удалён — повторить нечего."
            return
        }

        let inputs: [AttackRunner.DictionaryInput]
        if DictionaryStore.hasExplicitSelection {
            inputs = DictionaryStore.enabled.compactMap { dictionary in
                guard let bytes = DictionaryStore.contents(id: dictionary.id) else { return nil }
                return AttackRunner.DictionaryInput(name: dictionary.name, bytes: bytes)
            }
        } else {
            inputs = DictionaryStore.all.compactMap { dictionary in
                guard let bytes = DictionaryStore.contents(id: dictionary.id) else { return nil }
                return AttackRunner.DictionaryInput(name: dictionary.name, bytes: bytes)
            }
        }
        guard !inputs.isEmpty else {
            note = "Нет включённых словарей."
            return
        }

        let fallbackSsid = result.ssid
        Task.detached(priority: .userInitiated) {
            let targets = capture.targets()
            await MainActor.run {
                guard !targets.isEmpty else {
                    note = "В записи нет сетей для проверки."
                    return
                }
                running = true
                runner.start(
                    targets: targets,
                    dictionaries: inputs,
                    speed: app.settings.attackSpeed,
                    soundChoice: app.settings.finishSound,
                    notifyOnFinish: app.settings.notifyOnFinish,
                    hapticsEnabled: app.settings.hapticsEnabled,
                    soundEnabled: app.settings.soundEnabled) { outcome, duration in
                        let name = runner.currentDictionary ?? result.dictionaryName
                        app.addResult(outcome: outcome, durationSeconds: duration,
                                      captureID: capture.id, captureName: capture.fileName,
                                      fallbackSsid: fallbackSsid,
                                      dictionaryName: name)
                        running = false
                    }
            }
        }
    }
}

/// Copies a found passphrase to the system clipboard and briefly shows a confirmation tick.
struct CopyPasswordButton: View {
    let password: String
    @State private var copied = false

    var body: some View {
        Button {
            UIPasteboard.general.string = password
            copied = true
            Task {
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                copied = false
            }
        } label: {
            Image(systemName: copied ? "checkmark" : "doc.on.doc")
        }
        .buttonStyle(.borderless)
        .accessibilityLabel("Копировать пароль")
    }
}