import SwiftUI
import UniformTypeIdentifiers

struct DictionaryView: View {
    @State private var dictionaries: [DictionaryStore.Dictionary] = []
    @State private var importing = false
    @State private var message: String?
    @State private var showMessage = false

    var body: some View {
        NavigationStack {
            Group {
                if dictionaries.isEmpty {
                    emptyState
                } else {
                    list
                }
            }
            .navigationTitle("Словари")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { importing = true } label: { Image(systemName: "plus") }
                        .accessibilityLabel("Добавить словарь")
                }
            }
            .onAppear { reload() }
            .fileImporter(
                isPresented: $importing,
                allowedContentTypes: [.plainText, .text, .data],
                allowsMultipleSelection: true) { result in
                    handleImport(result)
                }
            .alert("Словари", isPresented: $showMessage) {
                Button("Хорошо", role: .cancel) {}
            } message: {
                Text(message ?? "")
            }
        }
    }

    private var list: some View {
        List {
            ForEach(dictionaries) { dictionary in
                VStack(alignment: .leading, spacing: 3) {
                    Text(dictionary.name).font(.subheadline.weight(.semibold))
                    Text("\(dictionary.size.formatted()) строк")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .padding(.vertical, 2)
            }
            .onDelete { offsets in
                for index in offsets {
                    DictionaryStore.remove(id: dictionaries[index].id)
                }
                reload()
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 16) {
            Image(systemName: "books.vertical")
                .font(.system(size: 48))
                .foregroundStyle(AppTheme.accent)
            Text("Нет словарей")
                .font(.title3.bold())
            Text("Добавьте текстовый файл со списком паролей, по одному в строке.")
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 32)
            Button {
                importing = true
            } label: {
                Label("Добавить файл", systemImage: "square.and.arrow.down")
                    .font(.headline)
            }
            .buttonStyle(.borderedProminent)
            .tint(AppTheme.accent)
        }
        .padding()
    }

    private func reload() {
        dictionaries = DictionaryStore.all
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let error):
            message = error.localizedDescription
            showMessage = true
        case .success(let urls):
            var added = 0
            var failures: [String] = []
            for url in urls {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                do {
                    try DictionaryStore.add(from: url, name: url.deletingPathExtension().lastPathComponent)
                    added += 1
                } catch {
                    failures.append("\(url.lastPathComponent): \(error.localizedDescription)")
                }
            }
            reload()
            if !failures.isEmpty {
                message = failures.joined(separator: "\n")
                showMessage = true
            } else if added == 0 {
                message = "Ничего не добавлено."
                showMessage = true
            }
        }
    }
}