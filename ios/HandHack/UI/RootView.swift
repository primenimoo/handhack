import SwiftUI

struct RootView: View {
    @EnvironmentObject private var app: AppState

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
        .onChange(of: app.settings.accentColor) { preset in
            AppTheme.current = preset.color
        }
        .fullScreenCover(isPresented: Binding(
            get: { app.settings.firstLaunch },
            set: { _ in app.setFirstLaunch(false) })) {
            OnboardingView()
        }
    }
}