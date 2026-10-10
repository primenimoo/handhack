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
                ResultRow(result: result)
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

private struct ResultRow: View {
    let result: CrackResult

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
        }
        .padding(.vertical, 2)
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