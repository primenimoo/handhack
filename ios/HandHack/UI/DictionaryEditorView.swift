import SwiftUI

/// A plain text editor for a dictionary: one candidate per line, editable in place. Saving
/// rewrites the file the cracker reads, so an edited word list takes effect on the next run.
struct DictionaryEditorView: View {
    let id: String
    let name: String
    var onSave: () -> Void

    @State private var text = ""
    @State private var loaded = false
    @State private var message: String?
    @State private var showMessage = false

    var body: some View {
        VStack(spacing: 0) {
            TextEditor(text: $text)
                .font(.system(.body, design: .monospaced))
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .padding(.horizontal, 8)
            Divider()
            HStack {
                Text("\(lineCount.formatted()) строк")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Button("Сохранить") { save() }
                    .buttonStyle(.borderedProminent)
                    .tint(AppTheme.accent)
            }
            .padding()
        }
        .navigationTitle(name)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if !loaded {
                text = DictionaryStore.text(id: id)
                loaded = true
            }
        }
        .alert("Словарь", isPresented: $showMessage) {
            Button("Хорошо", role: .cancel) {}
        } message: {
            Text(message ?? "")
        }
    }

    private var lineCount: Int {
        if text.isEmpty { return 0 }
        let newlines = text.reduce(0) { $0 + ($1 == "\n" ? 1 : 0) }
        return newlines + (text.hasSuffix("\n") ? 0 : 1)
    }

    private func save() {
        do {
            try DictionaryStore.overwrite(id: id, text: text)
            onSave()
            message = "Сохранено: \(lineCount.formatted()) строк."
        } catch {
            message = "Не удалось сохранить: \(error.localizedDescription)"
        }
        showMessage = true
    }
}
