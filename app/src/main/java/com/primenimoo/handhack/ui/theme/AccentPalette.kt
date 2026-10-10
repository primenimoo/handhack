package com.primenimoo.handhack.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Accent presets offered in Settings. Each preset only carries a seed colour —
 * every other role (background tint, surfaces, on-colours, outlines) is derived
 * by [HandHackColors] so that the palette always stays balanced and readable.
 */
enum class AccentPreset(
    val storageKey: String,
    val label: String,
    val seed: Color,
) {
    BLUE("blue", "Синий", Color(0xFF2F6BFF)),
    PURPLE("purple", "Фиолетовый", Color(0xFF7C4DFF)),
    VIOLET("violet", "Виолет", Color(0xFF9B4DFF)),
    CYAN("cyan", "Голубой", Color(0xFF00A8C6)),
    GREEN("green", "Зелёный", Color(0xFF17A05A)),
    ORANGE("orange", "Оранжевый", Color(0xFFF0761B)),
    RED("red", "Красный", Color(0xFFE0453C)),
    PINK("pink", "Розовый", Color(0xFFE0469B)),
    CUSTOM("custom", "Свой", Color(0xFF2F6BFF));

    companion object {
        fun fromKey(key: String?): AccentPreset =
            entries.firstOrNull { it.storageKey == key } ?: BLUE
    }
}

/**
 * Neutral bases that the accent is tinted into. They are deliberately calm so a
 * strong accent never turns the whole surface into an acid-coloured dashboard.
 */
internal object HandHackBases {
    val LightBackground = Color(0xFFF7F8FA)
    val LightSurface = Color(0xFFFFFFFF)
    val DarkBackground = Color(0xFF0B0F0E)
    val DarkSurface = Color(0xFF141A18)

    /** Minimum contrast ratio for text on its container. */
    const val MinTextContrast = 4.5f

    /** Minimum contrast ratio for large/graphic elements such as the progress bar. */
    const val MinGraphicContrast = 3.0f
}
