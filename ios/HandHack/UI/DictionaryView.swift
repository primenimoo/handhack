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
            List {
                localSection
                catalogueSection
            }
            .navigationTitle("Словари")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { importing = true } label: { Image(systemName: "plus") }
                        .accessibilityLabel("Добавить словарь")
                }
            }
            .supportToolbar()
            .brandToolbar()
            .onAppear { reload() }
            .task { await downloader.refreshCatalogue() }
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

    // MARK: Own files

    private var localSection: some View {
        Section {
            if dictionaries.isEmpty {
                Text("Своих словарей пока нет. Добавьте текстовый файл или установите готовую базу ниже.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(dictionaries) { dictionary in
                    NavigationLink {
                        DictionaryEditorView(id: dictionary.id, name: dictionary.name) { reload() }
                    } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(dictionary.name).font(.subheadline.weight(.semibold))
                            Text(dictionary.summary)
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 2)
                    }
                    .contextMenu {
                        Button(role: .destructive) {
                            DictionaryStore.remove(id: dictionary.id)
                            reload()
                        } label: {
                            Label("Удалить", systemImage: "trash")
                        }
                    }
                }
                .onDelete { offsets in
                    for index in offsets {
                        DictionaryStore.remove(id: dictionaries[index].id)
                    }
                    reload()
                }
            }
        } header: {
            Text("Мои словари")
        } footer: {
            Text("Любой текстовый файл подойдёт: по одному паролю в строке.")
                .font(.caption2)
        }
    }

    // MARK: HandHack catalogue

    private var catalogueSection: some View {
        Section {
            ForEach(downloader.catalogue) { database in
                catalogueRow(database)
            }

            if downloader.actionable.count > 1 {
                Button {
                    installAll()
                } label: {
                    Label("Скачать все (\(downloader.actionable.count))", systemImage: "arrow.down.circle")
                }
                .disabled(downloader.isDownloading)
            }

            if downloader.isDownloading { progressView }
        } header: {
            Text("Базы данных HandHack")
        } footer: {
            Text("Списки лежат на GitHub. Приложение проверяет обновления при каждом запуске.")
                .font(.caption2)
        }
    }

    private func catalogueRow(_ database: RemoteDictionary) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "doc.text")
                .foregroundStyle(AppTheme.accent)
                .frame(width: 26)
            VStack(alignment: .leading, spacing: 3) {
                Text(database.title).font(.subheadline.weight(.semibold))
                Text(database.detail).font(.caption).foregroundStyle(.secondary)
                Text("\(database.lines.formatted()) строк")
                    .font(.caption2).foregroundStyle(.secondary)
            }
            Spacer()
            switch downloader.status(of: database) {
            case .installed:
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
            case .notInstalled:
                Button("Скачать") { install(database) }
                    .buttonStyle(.bordered)
                    .disabled(downloader.isDownloading)
            case .updateAvailable:
                Button("Обновить") { install(database) }
                    .buttonStyle(.borderedProminent)
                    .tint(AppTheme.accent)
                    .disabled(downloader.isDownloading)
            }
        }
        .padding(.vertical, 2)
    }

    private var progressView: some View {
        VStack(spacing: 8) {
            ProgressView(value: downloader.progress)
                .tint(AppTheme.accent)
            HStack {
                Text(downloader.activeTitle ?? downloader.statusText)
                Spacer()
                Text("\(Int(downloader.progress * 100))%")
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .frame(maxWidth: 320)
    }

    private func reload() {
        dictionaries = DictionaryStore.all
    }

    private func install(_ database: RemoteDictionary) {
        Task {
            do {
                _ = try await downloader.install(database)
                reload()
                let installed = DictionaryStore.all.first { $0.id == database.id }
                message = "«\(database.title)» установлен: \(installed?.summary ?? "нет данных")."
                showMessage = true
            } catch {
                message = "Не удалось скачать «\(database.title)»: \(error.localizedDescription)"
                showMessage = true
            }
        }
    }

    private func installAll() {
        Task {
            await downloader.installAll(downloader.actionable)
            reload()
            message = downloader.lastError ?? downloader.installedMessage ?? "Готово."
            showMessage = true
        }
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let error):
            message = error.localizedDescription
            showMessage = true
        case .success(let urls):
            var imported: [String] = []
            var failures: [String] = []
            for url in urls {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                do {
                    let analysis = try DictionaryStore.add(
                        from: url, name: url.deletingPathExtension().lastPathComponent)
                    imported.append("«\(url.lastPathComponent)» — \(Self.describe(analysis))")
                } catch {
                    failures.append("\(url.lastPathComponent): \(error.localizedDescription)")
                }
            }
            reload()
            var lines: [String] = []
            if !imported.isEmpty {
                lines.append("Добавлено:")
                lines.append(contentsOf: imported)
            }
            if !failures.isEmpty {
                lines.append("Не добавлено:")
                lines.append(contentsOf: failures)
            }
            if lines.isEmpty { lines = ["Ничего не добавлено."] }
            message = lines.joined(separator: "\n")
            showMessage = true
        }
    }

    /// Reports the real contents of an imported file: how many passwords it holds, and only when
    /// it matters, how many of those are duplicates or that the file looked damaged.
    private static func describe(_ analysis: DictionaryText.Analysis) -> String {
        var parts = ["\(analysis.entryLines.formatted()) записей"]
        if analysis.uniqueEntries != analysis.entryLines {
            parts.append("\(analysis.uniqueEntries.formatted()) уникальных")
        }
        if analysis.blankLines > 0 {
            parts.append("пустых строк: \(analysis.blankLines.formatted())")
        }
        if analysis.hadDecodingErrors { parts.append("есть повреждённые байты") }
        if analysis.hadByteOrderMark { parts.append("BOM") }
        return parts.joined(separator: ", ")
    }
}
