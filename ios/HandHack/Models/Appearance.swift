import Foundation

// MARK: - Accent color

/// The themes the app can take. Only the colour accent changes; the shape of every screen stays.
/// Stored as a plain string so settings survive across builds.
enum AccentColorPreset: String, CaseIterable, Codable, Identifiable {
    case blue = "blue"
    case purple = "purple"
    case teal = "teal"
    case green = "green"
    case orange = "orange"
    case pink = "pink"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .blue: return "Синий"
        case .purple: return "Фиолетовый"
        case .teal: return "Бирюзовый"
        case .green: return "Зелёный"
        case .orange: return "Оранжевый"
        case .pink: return "Розовый"
        }
    }
}

// MARK: - Attack speed

/// How deliberately the engine paces a dictionary search. The word on screen is always a real
/// dictionary line; the profile only caps the derivation rate so the run feels quality instead
/// of scrolling by in a blur.
enum AttackSpeed: String, CaseIterable, Codable, Identifiable {
    case careful = "careful"
    case balanced = "balanced"
    case maximum = "maximum"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .careful: return "Качественный"
        case .balanced: return "Умный"
        case .maximum: return "Максимальный"
        }
    }

    var detail: String {
        switch self {
        case .careful: return "Неторопливая проверка, каждое слово видно."
        case .balanced: return "Плавный прогресс и быстрый результат — выбор по умолчанию."
        case .maximum: return "Максимальный темп без ограничений."
        }
    }

    /// Attempts per second allowed, or nil for no limit.
    var maxAttemptsPerSecond: Double? {
        switch self {
        case .careful: return 160
        case .balanced: return 1200
        case .maximum: return nil
        }
    }
}

// MARK: - Success sound

/// The tone played when a password is found. The first entry is the user's own mp3 bundled as
/// the default; the rest are short generated wav files added next to it.
enum SuccessSoundChoice: String, CaseIterable, Codable, Identifiable {
    case original = "original"
    case chime = "chime"
    case pop = "pop"
    case sparkle = "sparkle"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .original: return "Твой звук (mp3)"
        case .chime: return "Колокольчик"
        case .pop: return "Поп"
        case .sparkle: return "Искры"
        }
    }

    /// The asset catalogue dataset for this choice; `original` points at the mp3 the user gave.
    var assetName: String? {
        switch self {
        case .original: return "SuccessSound"
        case .chime: return "SuccessSoundChime"
        case .pop: return "SuccessSoundPop"
        case .sparkle: return "SuccessSoundSparkle"
        }
    }
}