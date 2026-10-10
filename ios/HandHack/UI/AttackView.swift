import SwiftUI
import UniformTypeIdentifiers

struct AttackView: View {
    @EnvironmentObject private var app: AppState
    @State private var importing = false
    @State private var message: String?
    @State private var showMessage = false

    var body: some View {
        NavigationStack {
            Group {
                if app.captures.isEmpty {
                    emptyState
                } else {
                    capturesList
                }
            }
            .navigationTitle("Подбор")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { importing = true } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("Добавить capture")
                }
            }
            .supportToolbar()
            .fileImporter(
                isPresented: $importing,
                allowedContentTypes: [.item],
                allowsMultipleSelection: true) { result in
                    handleImport(result)
                }
            .alert("Импорт", isPresented: $showMessage) {
                Button("Хорошо", role: .cancel) {}
            } message: {
                Text(message ?? "")
            }
        }
    }

    private var capturesList: some View {
        List {
            Section {
                ForEach(app.captures) { capture in
                    NavigationLink {
                        CaptureDetailView(capture: capture)
                    } label: {
                        CaptureRow(capture: capture)
                    }
                }
                .onDelete { offsets in
                    for index in offsets { app.deleteCapture(app.captures[index]) }
                }
            } header: {
                Text("Загруженные записи")
            } footer: {
                Text("Форматы: hc22000, hccapx, hccap, pcap, pcapng и экспорт HandHack.")
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 18) {
            AppBadge(size: 84)
            Text("Пока нет записей")
                .font(.title3.bold())
            Text("Добавьте файл рукопожатия — приложение покажет сети, для которых сможет проверить пароль.")
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 32)
            Button {
                importing = true
            } label: {
                Label("Добавить capture", systemImage: "square.and.arrow.down")
                    .font(.headline)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
            }
            .buttonStyle(.borderedProminent)
            .tint(AppTheme.accent)
        }
        .padding()
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let error):
            message = "Не удалось выбрать файл: \(error.localizedDescription)"
            showMessage = true
        case .success(let urls):
            importFiles(urls)
        }
    }

    private func importFiles(_ urls: [URL]) {
        DispatchQueue.global(qos: .userInitiated).async {
            var imported = 0
            var lastNotes: [String] = []
            var failures: [String] = []

            for url in urls {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                do {
                    let data = Array(try Data(contentsOf: url))
                    let record = CaptureLoader.load(data: data, name: url.lastPathComponent)
                    lastNotes = record.notes
                    DispatchQueue.main.async {
                        app.importCapture(data: data, name: url.lastPathComponent)
                    }
                    imported += 1
                } catch {
                    failures.append("\(url.lastPathComponent): \(error.localizedDescription)")
                }
            }

            DispatchQueue.main.async {
                if imported == 0 {
                    message = failures.isEmpty
                        ? "Не удалось прочитать выбранные файлы."
                        : failures.joined(separator: "\n")
                    showMessage = true
                } else if !lastNotes.isEmpty {
                    message = lastNotes.joined(separator: "\n")
                    showMessage = true
                }
            }
        }
    }
}

private struct CaptureRow: View {
    let capture: CaptureRecord

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: capture.isUsable ? "checkmark.shield.fill" : "exclamationmark.triangle.fill")
                .font(.title3)
                .foregroundStyle(capture.isUsable ? AppTheme.success : .orange)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 3) {
                Text(capture.fileName)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
    }

    private var subtitle: String {
        if capture.ssids.isEmpty {
            return "\(capture.format.label) · нет проверяемых сетей"
        }
        return "\(capture.ssids.joined(separator: ", ")) · \(capture.ssids.count) сет."
    }
}