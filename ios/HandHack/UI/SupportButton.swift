import SwiftUI

/// Where the "Поддержать проект" button sends the user: the author's donation page.
enum Support {
    static let url = URL(string: "https://dalink.to/primenimoo")!
    static let title = "Поддержать проект"
}

/// The heart shown in every screen's top bar.
struct SupportToolbarItem: View {
    var body: some View {
        Link(destination: Support.url) {
            Image(systemName: "heart.fill")
        }
        .accessibilityLabel(Support.title)
    }
}

/// The full-width row, for the about section of Settings.
struct SupportRow: View {
    var body: some View {
        Link(destination: Support.url) {
            Label(Support.title, systemImage: "heart.fill")
        }
    }
}

extension View {
    /// Adds the support heart to the trailing side of a screen's top bar.
    func supportToolbar() -> some View {
        toolbar {
            ToolbarItem(placement: .topBarTrailing) { SupportToolbarItem() }
        }
    }
}
