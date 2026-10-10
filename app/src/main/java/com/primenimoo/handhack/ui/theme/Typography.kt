package com.primenimoo.handhack.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.primenimoo.handhack.R

/**
 * Manrope is bundled locally (res/font) and is the only typeface used by the
 * application. The editor keeps a monospace family on purpose — it is the one
 * place where column alignment measurably improves readability.
 */
val ManropeFamily = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

val EditorMonoFamily = FontFamily.Monospace

private val TrimNone = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

@OptIn(ExperimentalTextApi::class)
private fun manrope(
    size: Int,
    weight: FontWeight,
    lineHeight: Int,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = ManropeFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = TrimNone,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** Compact, technical type scale for the dense parts of the UI. */
object HandHackTypography {

    val displayLarge = manrope(40, FontWeight.Bold, 46, (-0.8))
    val displayMedium = manrope(32, FontWeight.Bold, 38, (-0.6))
    val headlineLarge = manrope(28, FontWeight.Bold, 34, (-0.4))
    val headlineMedium = manrope(24, FontWeight.SemiBold, 30, (-0.3))
    val headlineSmall = manrope(20, FontWeight.SemiBold, 26, (-0.2))
    val titleLarge = manrope(19, FontWeight.SemiBold, 25, (-0.1))
    val titleMedium = manrope(16, FontWeight.SemiBold, 22)
    val titleSmall = manrope(15, FontWeight.Medium, 20)
    val bodyLarge = manrope(16, FontWeight.Normal, 24)
    val bodyMedium = manrope(14, FontWeight.Normal, 21)
    val bodySmall = manrope(13, FontWeight.Normal, 19)
    val labelLarge = manrope(15, FontWeight.SemiBold, 20, 0.1)
    val labelMedium = manrope(13, FontWeight.Medium, 18, 0.2)
    val labelSmall = manrope(12, FontWeight.Medium, 16, 0.3)

    /** Monospaced variant used by the dictionary editor and by technical readouts. */
    @OptIn(ExperimentalTextApi::class)
    val monoBody = TextStyle(
    fontFamily = EditorMonoFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    lineHeightStyle = TrimNone,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** Large tabular numbers for progress/statistics readouts. */
    @OptIn(ExperimentalTextApi::class)
    val statValue = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.6).sp,
        lineHeightStyle = TrimNone,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
}

/** Material 3 mapping so `MaterialTheme(typography = …)` stays a single source. */
val Typography = androidx.compose.material3.Typography(
    displayLarge = HandHackTypography.displayLarge,
    displayMedium = HandHackTypography.displayMedium,
    headlineLarge = HandHackTypography.headlineLarge,
    headlineMedium = HandHackTypography.headlineMedium,
    headlineSmall = HandHackTypography.headlineSmall,
    titleLarge = HandHackTypography.titleLarge,
    titleMedium = HandHackTypography.titleMedium,
    titleSmall = HandHackTypography.titleSmall,
    bodyLarge = HandHackTypography.bodyLarge,
    bodyMedium = HandHackTypography.bodyMedium,
    bodySmall = HandHackTypography.bodySmall,
    labelLarge = HandHackTypography.labelLarge,
    labelMedium = HandHackTypography.labelMedium,
    labelSmall = HandHackTypography.labelSmall,
)
