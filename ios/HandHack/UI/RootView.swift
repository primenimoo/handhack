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
        .tint(AppTheme.accent)
        .fullScreenCover(isPresented: Binding(
            get: { app.settings.firstLaunch },
            set: { _ in app.setFirstLaunch(false) })) {
            OnboardingView()
        }
    }
}