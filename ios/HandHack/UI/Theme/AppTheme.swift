import SwiftUI

/// Tinted glass look shared by every screen. The controls stay plain SwiftUI so the system
/// Liquid Glass layer on iOS 26 sits on top and feels native; this palette is only the colour
/// accent the app is known by.
enum AppTheme {
    static let sky = Color(red: 0.55, green: 0.78, blue: 1.00, opacity: 1)

    /// The accent currently in effect. RootView sets it every time the user changes the theme,
    /// so screens that reach for `AppTheme.accent` at build time always render the chosen colour.
    static var current: Color = AccentColorPreset.blue.color
    static var accent: Color { current }

    static var gradient: LinearGradient {
        LinearGradient(
            colors: [accent, sky],
            startPoint: .topLeading,
            endPoint: .bottomTrailing)
    }

    static let success = Color.green
    static let danger = Color.red

    /// A colour per kind of key material, so evidence chips never melt into the accent tint.
    static func evidenceColor(_ evidence: CaptureEvidence) -> Color {
        switch evidence {
        case .pmk: return Color(red: 0.10, green: 0.60, blue: 0.38)
        case .pmkid: return Color(red: 0.95, green: 0.55, blue: 0.12)
        case .eapolMic: return Color(red: 0.55, green: 0.35, blue: 0.95)
        }
    }
}

extension AccentColorPreset {
    /// The concrete colour a preset stands for.
    var color: Color {
        switch self {
        case .blue: return Color(red: 0.10, green: 0.45, blue: 1.00)
        case .purple: return Color(red: 0.52, green: 0.33, blue: 0.95)
        case .teal: return Color(red: 0.08, green: 0.62, blue: 0.72)
        case .green: return Color(red: 0.10, green: 0.62, blue: 0.38)
        case .orange: return Color(red: 1.00, green: 0.56, blue: 0.12)
        case .pink: return Color(red: 0.90, green: 0.30, blue: 0.56)
        }
    }
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