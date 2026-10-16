import SwiftUI

struct RootView: View {
    @EnvironmentObject private var app: AppState
    @StateObject private var updates = DictionaryDownloader()
    @State private var showUpdatePrompt = false

    var body: some View {
        TabView {
            AttackView()
                .tabItem { Label("Подбор", systemImage: "wand.and.stars") }
            DictionaryView()
                .tabItem { Label("Словари", systemImage: "books.vertical") }
            HistoryView()
                .tabItem { Label("История", systemImage: "clock.arrow.circlepath") }
            SettingsView()
                .tabItem { Label("Настройки", systemImage: "gearshape") }
        }
        .tint(app.settings.accentColor.color)
        .onAppear { AppTheme.current = app.settings.accentColor.color }
        .onChange(of: app.settings.accentColor) { _, preset in
            AppTheme.current = preset.color
        }
        .fullScreenCover(isPresented: Binding(
            get: { app.settings.firstLaunch },
            set: { _ in app.setFirstLaunch(false) })) {
            OnboardingView()
        }
        .task(id: app.settings.firstLaunch) {
            guard !app.settings.firstLaunch else { return }
            await updates.refreshCatalogue()
            showUpdatePrompt = !updates.actionable.isEmpty
        }
        .alert("Обновить базы данных?", isPresented: $showUpdatePrompt) {
            Button("Обновить") {
                Task { await updates.installAll(updates.actionable) }
            }
            Button("Позже", role: .cancel) {}
        } message: {
            Text(updates.actionable.count == 1
                 ? "Доступна 1 новая или обновлённая база данных."
                 : "Доступно обновлений: \(updates.actionable.count).")
        }
    }
}