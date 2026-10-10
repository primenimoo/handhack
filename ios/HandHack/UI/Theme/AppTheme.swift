import SwiftUI

/// Tinted glass look shared by every screen. The controls stay plain SwiftUI so the system
/// Liquid Glass layer on iOS 26 sits on top and feels native; this palette is only the colour
/// accent the app is known by.
enum AppTheme {
    static let accent = Color(red: 0.34, green: 0.60, blue: 1.00, opacity: 1)
    static let violet = Color(red: 0.62, green: 0.40, blue: 1.00, opacity: 1)

    static let gradient = LinearGradient(
        colors: [accent, violet],
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

/// The app mark: a rounded badge with the two-colour gradient.
struct AppBadge: View {
    var size: CGFloat = 64

    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.24, style: .continuous)
            .fill(AppTheme.gradient)
            .frame(width: size, height: size)
            .overlay(
                Image(systemName: "wand.and.stars")
                    .font(.system(size: size * 0.5, weight: .semibold))
                    .foregroundStyle(.white))
    }
}