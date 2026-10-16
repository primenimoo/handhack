import SwiftUI

struct OnboardingView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var downloader = DictionaryDownloader()
    @State private var step = 0
    @State private var didInstall = false

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color.black, Color(red: 0.06, green: 0.08, blue: 0.16, opacity: 1)],
                startPoint: .top,
                endPoint: .bottom)
                .ignoresSafeArea()

            ScrollView {
                VStack(spacing: 22) {
                    if step == 0 { intro } else { databaseStep }
                    Text("@primenimooo")
                        .font(.footnote)
                        .foregroundStyle(.white.opacity(0.5))
                    Spacer(minLength: 20)
                }
            }
        }
        .onChange(of: step) { _, value in
            if value == 1 {
                Task { await downloader.refreshCatalogue() }
            }
        }
    }

    // MARK: Step 1 — what the app is

    private var intro: some View {
        VStack(spacing: 22) {
            Spacer(minLength: 40)
            AppBadge(size: 92)
            Text("HandHack")
                .font(.largeTitle.bold())
                .foregroundStyle(.white)
            Text("Проверка стойкости паролей Wi-Fi по вашим собственным записям рукопожатия")
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundStyle(.white.opacity(0.75))
                .padding(.horizontal, 24)

            VStack(alignment: .leading, spacing: 16) {
                point("lock.shield", "Только ваши сети",
                      "Приложение проверяет пароли к записям, которые вы сами загрузили.")
                point("iphone.and.arrow.forward", "Всё считается на устройстве",
                      "Никаких серверов и отправки данных — словарь и расчёт остаются в телефоне.")
                point("checkmark.seal", "Без взлома чужих сетей",
                      "Используйте только на оборудовании, которым владеете или имеете право управлять.")
            }
            .padding(.horizontal)

            Button {
                withAnimation { step = 1 }
            } label: {
                Text("Далее")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
            }
            .buttonStyle(.borderedProminent)
            .tint(AppTheme.accent)
            .padding(.horizontal)
            .padding(.top, 8)

            Button("Пропустить") {
                withAnimation { step = 1 }
            }
            .font(.subheadline)
            .foregroundStyle(.white.opacity(0.7))
        }
    }

    // MARK: Step 2 — install a database from GitHub

    private var databaseStep: some View {
        VStack(spacing: 18) {
            Spacer(minLength: 32)
            AppBadge(size: 72)
            Text("Установить базу данных")
                .font(.title2.bold())
                .foregroundStyle(.white)
            Text("Скачаем готовые списки паролей с GitHub. По ним приложение и проверяет ваши сети.")
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundStyle(.white.opacity(0.75))
                .padding(.horizontal, 24)

            if downloader.isLoadingCatalogue {
                ProgressView().tint(.white).padding(.top, 8)
            } else {
                VStack(spacing: 0) {
                    ForEach(downloader.catalogue) { database in
                        databaseRow(database)
                        if database.id != downloader.catalogue.last?.id {
                            Divider().overlay(Color.white.opacity(0.08))
                        }
                    }
                }
                .glassCard()
                .padding(.horizontal)
            }

            if downloader.isDownloading { progressView }

            if let message = downloader.installedMessage {
                Label(message, systemImage: "checkmark.circle.fill")
                    .font(.subheadline)
                    .foregroundStyle(.green)
            }
            if let error = downloader.lastError {
                Text(error)
                    .font(.caption)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.orange)
                    .padding(.horizontal)
            }

            if showDone {
                Button {
                    dismiss()
                } label: {
                    Text("Готово")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
                .buttonStyle(.borderedProminent)
                .tint(AppTheme.accent)
                .padding(.horizontal)
            } else {
                Button {
                    install()
                } label: {
                    Text("Установить (\(downloader.actionable.count))")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
                .buttonStyle(.borderedProminent)
                .tint(AppTheme.accent)
                .disabled(downloader.isDownloading || downloader.catalogue.isEmpty)
                .padding(.horizontal)

                Button("Пропустить") {
                    dismiss()
                }
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.7))
            }
        }
    }

    private func databaseRow(_ database: RemoteDictionary) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "doc.text")
                .foregroundStyle(AppTheme.accent)
                .frame(width: 26)
            VStack(alignment: .leading, spacing: 3) {
                Text(database.title).font(.subheadline.weight(.semibold)).foregroundStyle(.white)
                Text("\(database.lines.formatted()) строк · \(byteString(database.bytes))")
                    .font(.caption).foregroundStyle(.white.opacity(0.6))
            }
            Spacer()
            if downloader.status(of: database) == .installed {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
            }
        }
        .padding(.vertical, 10)
        .padding(.horizontal, 14)
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
            .foregroundStyle(.white.opacity(0.7))
        }
        .frame(maxWidth: 300)
        .padding(.horizontal)
    }

    private var showDone: Bool {
        didInstall || (!downloader.isLoadingCatalogue && !downloader.catalogue.isEmpty
            && downloader.actionable.isEmpty)
    }

    private func install() {
        Task {
            await downloader.installAll(downloader.actionable)
            if downloader.actionable.isEmpty { didInstall = true }
        }
    }

    private func byteString(_ bytes: Int) -> String {
        if bytes >= 1024 * 1024 {
            return String(format: "%.1f МБ", Double(bytes) / 1_048_576)
        }
        return "\(max(1, bytes / 1024)) КБ"
    }

    private func point(_ symbol: String, _ title: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(AppTheme.accent)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.headline).foregroundStyle(.white)
                Text(text).font(.subheadline).foregroundStyle(.white.opacity(0.7))
            }
        }
        .glassCard()
    }
}
