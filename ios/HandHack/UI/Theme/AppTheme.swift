import SwiftUI

/// Tinted glass look shared by every screen. The controls stay plain SwiftUI so the system
/// Liquid Glass layer on iOS 26 sits on top and feels native; this palette is only the colour
/// accent the app is known by.
enum AppTheme {
    static let accent = Color(red: 0.10, green: 0.45, blue: 1.00, opacity: 1)
    static let sky = Color(red: 0.55, green: 0.78, blue: 1.00, opacity: 1)

    static let gradient = LinearGradient(
        colors: [accent, sky],
        startPoint: .topLeading,
        endPoint: .bottomTrailing)

    static let success = Color.green
    static let danger = Color.red
}

extension View {
    /// A card of frosted glass: translucent fill, soft corners, a hairline rim.
    func glassCard() -> some View {
        self
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .strokeBorder(.quaternary, lineWidth: 0.5))
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

/// The app mark: a rounded badge with the two-colour gradient behind the real logo artwork.
struct AppBadge: View {
    var size: CGFloat = 64

    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.24, style: .continuous)
            .fill(AppTheme.gradient)
            .frame(width: size, height: size)
            .overlay(
                Image("Logo")
                    .resizable()
                    .scaledToFit()
                    .padding(size * 0.12))
            .clipShape(RoundedRectangle(cornerRadius: size * 0.24, style: .continuous))
    }
}

extension View {
    /// Puts the app logo in the centre of a screen's navigation bar, the way the brand is shown
    /// at the top of the app.
    func brandToolbar(height: CGFloat = 26) -> some View {
        self
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Image("Logo")
                        .resizable()
                        .scaledToFit()
                        .frame(height: height)
                        .accessibilityLabel("HandHack")
                }
            }
    }
}