import SwiftUI
import UserNotifications

struct SettingsView: View {
    @EnvironmentObject private var app: AppState

    var body: some View {
        NavigationStack {
            Form {
                Section("Подбор") {
                    Toggle("Тактильный отклик", isOn: Binding(
                        get: { app.settings.hapticsEnabled },
                        set: { app.setHapticsEnabled($0) }))
                    Toggle("Уведомление по завершении", isOn: Binding(
                        get: { app.settings.notifyOnFinish },
                        set: { value in
                            app.setNotifyOnFinish(value)
                            if value { requestNotifications() }
                        }))
                }

                Section("О приложении") {
                    LabeledContent("Версия", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                    Link(destination: URL(string: "https://t.me/primenimooo")!) {
                        Label("@primenimooo", systemImage: "paperplane")
                    }
                    SupportRow()
                }

                Section {
                    Text("Проверка выполняется только по вашим собственным записям рукопожатия. " +
                         "Используйте приложение только на сетях, которыми владеете или имеете право управлять.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Настройки")
            .supportToolbar()
        }
    }

    private func requestNotifications() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }
}