package com.primenimoo.handhack.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Adaptive colour engine for HandHack.
 *
 * The accent colour is treated as a *seed*, not as a final decision. From a
 * single seed the engine derives:
 *
 *  - a base background that carries a very soft tint of the accent,
 *  - a surface ladder that sits above the background,
 *  - primary / secondary / tertiary roles whose contrast against the actual
 *    background is guaranteed (WCAG relative luminance),
 *  - on- colours picked by measured contrast rather than by guessing white or black,
 *  - outlines that stay visible in both themes.
 *
 * This is what keeps "голубой текст на голубом фоне" from ever happening: every
 * foreground is validated against the surface it is actually painted on.
 */
object HandHackColors {

    // ---------------------------------------------------------------- blending

    /** Mixes [foreground] into [background] by [amount] (0f..1f). */
    fun blend(background: Color, foreground: Color, amount: Float): Color {
        val t = amount.coerceIn(0f, 1f)
        return Color(
            red = background.red + (foreground.red - background.red) * t,
            green = background.green + (foreground.green - background.green) * t,
            blue = background.blue + (foreground.blue - background.blue) * t,
            alpha = background.alpha + (foreground.alpha - background.alpha) * t,
        )
    }

    fun mix(a: Color, b: Color, t: Float): Color = blend(a, b, t)

    /** WCAG relative luminance. */
    fun luminance(color: Color): Float = color.luminance()

    /** WCAG contrast ratio between two opaque colours, 1f..21f. */
    fun contrastRatio(foreground: Color, background: Color): Float {
        val l1 = foreground.luminance()
        val l2 = background.luminance()
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    fun readableTextOn(background: Color): Color {
        val onWhite = contrastRatio(Color.White, background)
        val onNearBlack = contrastRatio(Color(0xFF0A0C0B), background)
        return if (onWhite >= onNearBlack) Color.White else Color(0xFF0A0C0B)
    }

    /**
     * Nudges [candidate] away from [background] (towards [escapeToward]) until it
     * reaches [minRatio] contrast. Guarantees a readable result for *any* seed,
     * including near-white or near-black custom picks.
     */
    fun ensureContrast(
        candidate: Color,
        background: Color,
        minRatio: Float,
        escapeToward: Color,
    ): Color {
        if (contrastRatio(candidate, background) >= minRatio) return candidate

        var lo = 0f
        var hi = 1f
        var best = candidate
        repeat(20) {
            val mid = (lo + hi) / 2f
            val probe = blend(candidate, escapeToward, mid)
            if (contrastRatio(probe, background) >= minRatio) {
                best = probe
                hi = mid
            } else {
                lo = mid
            }
        }
        return best
    }

    /**
     * Returns true when a colour is "usable" as a seed: it must not be so close
     * to white/black that a tint derived from it would be invisible.
     */
    fun isUsableSeed(seed: Color): Boolean {
        val white = contrastRatio(seed, Color.White)
        val black = contrastRatio(seed, Color(0xFF000000))
        return max(white, black) >= 1.9f
    }

    /** Sanitises a user supplied custom colour so it can drive a whole palette. */
    fun sanitizeSeed(seed: Color): Color {
        if (isUsableSeed(seed)) return seed
        // Too close to white or black: pull it back toward the middle of the range.
        val l = seed.luminance()
        return if (l > 0.5f) blend(seed, Color(0xFF1B2430), 0.55f) else blend(seed, Color(0xFFDCE6F5), 0.55f)
    }

    // ------------------------------------------------------------------ tints

    /**
     * Adaptive background: a soft tint of the accent, kept deliberately close to
     * neutral so surfaces stay calm and text keeps its contrast.
     */
    fun tintedBackground(seed: Color, dark: Boolean): Color {
        val base = if (dark) HandHackBases.DarkBackground else HandHackBases.LightBackground
        val amount = if (dark) 0.16f else 0.055f
        val tinted = blend(base, seed, amount)
        // Clamp the lightness band so the background never becomes muddy or glaring.
        return if (dark) {
            blend(tinted, Color(0xFF000000), ((0.075f - tinted.luminance()).coerceAtLeast(0f)) * 1.6f)
        } else {
            blend(tinted, Color.White, ((tinted.luminance() - 0.975f).coerceAtLeast(0f)) * 3.2f)
        }
    }

    private fun surfaceLadderStep(background: Color, dark: Boolean, step: Int): Color {
        val target = if (dark) Color.White else Color.White
        val amount = when (step) {
            0 -> 0.030f
            1 -> 0.055f
            2 -> 0.080f
            3 -> 0.105f
            else -> 0.130f
        }
        return blend(background, target, if (dark) amount * 0.85f else amount)
    }

    // ---------------------------------------------------------------- schemes

    fun lightScheme(seed: Color, dynamicBase: ColorScheme? = null): ColorScheme {
        val accent = sanitizeSeed(seed)
        val background = dynamicBase?.background ?: tintedBackground(accent, dark = false)
        val onBackground = ensureContrast(
            candidate = dynamicBase?.onBackground ?: Color(0xFF14181C),
            background = background,
            minRatio = HandHackBases.MinTextContrast,
            escapeToward = Color.Black,
        )

        val surface = dynamicBase?.surface ?: surfaceLadderStep(background, dark = false, 1)
        val onSurface = ensureContrast(onBackground, surface, HandHackBases.MinTextContrast, Color.Black)

        val primary = ensureContrast(
            candidate = accent,
            background = surface,
            minRatio = HandHackBases.MinTextContrast,
            escapeToward = Color.Black,
        )
        val onPrimary = readableTextOn(primary)

        val primaryContainer = blend(surface, accent, 0.16f)
        val onPrimaryContainer = ensureContrast(
            candidate = accent,
            background = primaryContainer,
            minRatio = HandHackBases.MinTextContrast,
            escapeToward = Color.Black,
        )

        val secondary = rotateTowards(accent, Color(0xFF3E4A57), 0.42f)
            .let { ensureContrast(it, surface, HandHackBases.MinTextContrast, Color.Black) }
        val onSecondary = readableTextOn(secondary)

        val secondaryContainer = blend(surface, secondary, 0.14f)
        val onSecondaryContainer = ensureContrast(secondary, secondaryContainer, HandHackBases.MinTextContrast, Color.Black)

        val tertiary = rotateTowards(accent, Color(0xFF00696B), 0.40f)
            .let { ensureContrast(it, surface, HandHackBases.MinTextContrast, Color.Black) }
        val onTertiary = readableTextOn(tertiary)

        val tertiaryContainer = blend(surface, tertiary, 0.14f)
        val onTertiaryContainer = ensureContrast(tertiary, tertiaryContainer, HandHackBases.MinTextContrast, Color.Black)

        val error = ensureContrast(Color(0xFFD3342B), surface, HandHackBases.MinTextContrast, Color.Black)
        val onError = readableTextOn(error)
        val errorContainer = blend(surface, error, 0.14f)
        val onErrorContainer = ensureContrast(error, errorContainer, HandHackBases.MinTextContrast, Color.Black)

        val outline = blend(surface, onSurface, 0.22f)
        val outlineVariant = blend(surface, onSurface, 0.12f)
        val scrim = Color(0xFF000000)

        return lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = ensureContrast(accent, Color(0xFF2B3138), HandHackBases.MinTextContrast, Color.White),
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = blend(surface, onSurface, 0.07f),
            onSurfaceVariant = ensureContrast(
                blend(onSurface, surface, 0.28f), surface, HandHackBases.MinTextContrast, Color.Black,
            ),
            surfaceTint = accent,
            inverseSurface = Color(0xFF2B3138),
            inverseOnSurface = Color(0xFFF1F3F6),
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = scrim,
            surfaceBright = blend(surface, Color.White, 0.35f),
            surfaceDim = blend(surface, Color.Black, 0.10f),
            surfaceContainerLowest = blend(background, Color.White, 0.55f),
            surfaceContainerLow = surfaceLadderStep(background, dark = false, 0),
            surfaceContainer = surfaceLadderStep(background, dark = false, 1),
            surfaceContainerHigh = surfaceLadderStep(background, dark = false, 2),
            surfaceContainerHighest = surfaceLadderStep(background, dark = false, 3),
        )
    }

    fun darkScheme(seed: Color, dynamicBase: ColorScheme? = null): ColorScheme {
        val accent = sanitizeSeed(seed)
        val background = dynamicBase?.background ?: tintedBackground(accent, dark = true)
        val onBackground = ensureContrast(
            candidate = dynamicBase?.onBackground ?: Color(0xFFE6EAE7),
            background = background,
            minRatio = HandHackBases.MinTextContrast,
            escapeToward = Color.White,
        )

        val surface = dynamicBase?.surface ?: surfaceLadderStep(background, dark = true, 1)
        val onSurface = ensureContrast(onBackground, surface, HandHackBases.MinTextContrast, Color.White)

        val primary = ensureContrast(
            candidate = blend(accent, Color.White, 0.34f),
            background = surface,
            minRatio = HandHackBases.MinTextContrast,
            escapeToward = Color.White,
        )
        val onPrimary = readableTextOn(primary)

        val primaryContainer = blend(surface, accent, 0.26f)
        val onPrimaryContainer = ensureContrast(
            candidate = blend(accent, Color.White, 0.46f),
            background = primaryContainer,
            minRatio = HandHackBases.MinTextContrast,
            escapeToward = Color.White,
        )

        val secondary = rotateTowards(accent, Color(0xFFB9C6CF), 0.52f)
            .let { ensureContrast(it, surface, HandHackBases.MinTextContrast, Color.White) }
        val onSecondary = readableTextOn(secondary)

        val secondaryContainer = blend(surface, secondary, 0.22f)
        val onSecondaryContainer = ensureContrast(secondary, secondaryContainer, HandHackBases.MinTextContrast, Color.White)

        val tertiary = rotateTowards(accent, Color(0xFF7FD4D6), 0.50f)
            .let { ensureContrast(it, surface, HandHackBases.MinTextContrast, Color.White) }
        val onTertiary = readableTextOn(tertiary)

        val tertiaryContainer = blend(surface, tertiary, 0.22f)
        val onTertiaryContainer = ensureContrast(tertiary, tertiaryContainer, HandHackBases.MinTextContrast, Color.White)

        val error = ensureContrast(Color(0xFFFF8A80), surface, HandHackBases.MinTextContrast, Color.White)
        val onError = readableTextOn(error)
        val errorContainer = blend(surface, error, 0.22f)
        val onErrorContainer = ensureContrast(error, errorContainer, HandHackBases.MinTextContrast, Color.White)

        val outline = blend(surface, onSurface, 0.28f)
        val outlineVariant = blend(surface, onSurface, 0.15f)
        val scrim = Color(0xFF000000)

        return darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = ensureContrast(accent, Color(0xFFE6EAE7), HandHackBases.MinTextContrast, Color.Black),
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = blend(surface, Color.White, 0.06f),
            onSurfaceVariant = ensureContrast(
                blend(onSurface, surface, 0.26f), surface, HandHackBases.MinTextContrast, Color.White,
            ),
            surfaceTint = accent,
            inverseSurface = Color(0xFFE6EAE7),
            inverseOnSurface = Color(0xFF14181C),
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = scrim,
            surfaceBright = blend(surface, Color.White, 0.14f),
            surfaceDim = blend(surface, Color.Black, 0.22f),
            surfaceContainerLowest = blend(background, Color.Black, 0.30f),
            surfaceContainerLow = surfaceLadderStep(background, dark = true, 0),
            surfaceContainer = surfaceLadderStep(background, dark = true, 1),
            surfaceContainerHigh = surfaceLadderStep(background, dark = true, 2),
            surfaceContainerHighest = surfaceLadderStep(background, dark = true, 3),
        )
    }

    /** Mixes [from] towards [to] while keeping a hint of [from]'s identity. */
    private fun rotateTowards(from: Color, to: Color, amount: Float): Color {
        val mixed = blend(from, to, amount)
        return blend(mixed, from, 0.18f)
    }

    /** Formats a colour as #RRGGBB, used by the custom colour picker. */
    fun toHex(color: Color): String {
        fun part(v: Float): String {
            val int = (v * 255f).toInt().coerceIn(0, 255)
            return int.toString(16).uppercase().padStart(2, '0')
        }
        return "#" + part(color.red) + part(color.green) + part(color.blue)
    }

    /** Lightness (0..1) of a colour, used by the custom colour picker. */
    fun lightness(color: Color): Float = color.luminance()

    /** Perceptual-ish "is this colour bright" helper used to pick readable previews. */
    fun isBright(color: Color): Boolean = color.luminance() > 0.42f

    @Suppress("unused")
    private fun relativeLuminancePow(c: Color): Double {
        fun channel(v: Float): Double {
            val x = v.toDouble()
            return if (x <= 0.03928) x / 12.92 else x.pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    @Suppress("unused")
    private fun nearlyEqual(a: Color, b: Color, epsilon: Float = 0.004f): Boolean =
        abs(a.red - b.red) < epsilon && abs(a.green - b.green) < epsilon && abs(a.blue - b.blue) < epsilon
}
