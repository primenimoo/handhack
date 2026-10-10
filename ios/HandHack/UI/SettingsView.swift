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
                    Toggle("Звук при находке", isOn: Binding(
                        get: { app.settings.soundEnabled },
                        set: { app.setSoundEnabled($0) }))
                    Toggle("Уведомление по завершении", isOn: Binding(
                        get: { app.settings.notifyOnFinish },
                        set: { value in
                            app.setNotifyOnFinish(value)
                            if value { requestNotifications() }
                        }))
                }

                Section {
                    Picker("Скорость подбора", selection: Binding(
                        get: { app.settings.attackSpeed },
                        set: { app.setAttackSpeed($0) })) {
                            ForEach(AttackSpeed.allCases) { speed in
                                Text(speed.title).tag(speed)
                            }
                        }
                        .pickerStyle(.menu)
                    Text(app.settings.attackSpeed.detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Toggle("Автоматически запускать подбор", isOn: Binding(
                        get: { app.settings.autoRunAfterAnalysis },
                        set: { app.setAutoRunAfterAnalysis($0) }))
                } header: {
                    Text("Умный анализ")
                } footer: {
                    Text("После анализа файла приложение само выбирает метод и запускает подбор.")
                }

                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Цвет темы")
                            .font(.subheadline)
                        HStack(spacing: 14) {
                            ForEach(AccentColorPreset.allCases) { preset in
                                Button {
                                    app.setAccentColor(preset)
                                } label: {
                                    ZStack {
                                        Circle()
                                            .fill(preset.color)
                                            .frame(width: 36, height: 36)
                                            .overlay(
                                                Circle().strokeBorder(.quaternary, lineWidth: 1))
                                        if app.settings.accentColor == preset {
                                            Image(systemName: "checkmark")
                                                .font(.caption.weight(.bold))
                                                .foregroundStyle(.white)
                                        }
                                    }
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(preset.title)
                            }
                        }
                    }

                    Picker("Звук успеха", selection: Binding(
                        get: { app.settings.finishSound },
                        set: { app.setFinishSound($0) })) {
                            ForEach(SuccessSoundChoice.allCases) { choice in
                                Text(choice.title).tag(choice)
                            }
                        }
                        .pickerStyle(.menu)
                    Button {
                        SuccessSound.shared.play(choice: app.settings.finishSound)
                    } label: {
                        Label("Прослушать звук", systemImage: "speaker.wave.2")
                    }
                    .font(.subheadline)
                } header: {
                    Text("Внешний вид")
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
            .brandToolbar()
        }
    }

    private func requestNotifications() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }
}