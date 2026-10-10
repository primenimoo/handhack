import SwiftUI
import UniformTypeIdentifiers

struct DictionaryView: View {
    @State private var dictionaries: [DictionaryStore.Dictionary] = []
    @State private var importing = false
    @State private var message: String?
    @State private var showMessage = false
    @StateObject private var downloader = DictionaryDownloader()

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

            Section {
                downloadButton
                if downloader.isDownloading { progressView }
            } header: {
                Text("Загрузить из GitHub")
            } footer: {
                Text("Словарь скачается и сразу появится в списке — его можно так же удалить или заменить.")
                    .font(.caption2)
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
            Text("Добавьте текстовый файл со списком паролей, по одному в строке — или скачайте готовый словарь.")
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

            downloadButton

            if downloader.isDownloading { progressView }
        }
        .padding()
    }

    /// A deliberately colourless button: it is a secondary action next to "Добавить файл".
    private var downloadButton: some View {
        Button {
            startDownload(RemoteDictionaryCatalog.all[0])
        } label: {
            Label("Скачать с GitHub", systemImage: "arrow.down.circle")
                .font(.headline)
        }
        .buttonStyle(.bordered)
        .tint(.gray)
        .disabled(downloader.isDownloading)
    }

    private var progressView: some View {
        VStack(spacing: 8) {
            ProgressView(value: downloader.progress)
                .tint(AppTheme.accent)
            HStack {
                Text(downloader.statusText)
                Spacer()
                Text("\(Int(downloader.progress * 100))%")
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .frame(maxWidth: 300)
    }

    private func reload() {
        dictionaries = DictionaryStore.all
    }

    private func startDownload(_ remote: RemoteDictionary) {
        Task {
            do {
                let bytes = try await downloader.download(remote)
                reload()
                message = "Словарь «\(remote.title)» загружен: \(bytes.formatted()) байт, " +
                    "\((DictionaryStore.fetchSize(remote.id)).formatted()) строк."
                showMessage = true
            } catch {
                message = "Не удалось скачать словарь: \(error.localizedDescription)"
                showMessage = true
            }
        }
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
