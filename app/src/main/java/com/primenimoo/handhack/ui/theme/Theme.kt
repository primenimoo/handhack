package com.primenimoo.handhack.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.primenimoo.handhack.data.settings.ThemeMode

/** Global motion switch, driven by Settings → Animations. */
val LocalAnimationsEnabled = compositionLocalOf { true }

/** Global haptics switch, driven by Settings → Haptic feedback. */
val LocalHapticsEnabled = compositionLocalOf { true }

/** Shared spacing scale so padding/radii stay consistent across screens. */
object HandHackSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
    val section = 32.dp
}

@Composable
fun HandHackTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accentSeed: Color = AccentPreset.BLUE.seed,
    useDynamicColor: Boolean = false,
    animationsEnabled: Boolean = true,
    hapticsEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val useDynamic = useDynamicColor && dynamicAvailable

    val dynamicScheme: ColorScheme? = if (useDynamic) {
        val context = LocalContext.current
        val darkCtx = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (dark) dynamicDarkColorSchemeCompat(context) else dynamicLightColorSchemeCompat(context, darkCtx)
    } else {
        null
    }

    val scheme = if (dark) {
        HandHackColors.darkScheme(accentSeed, dynamicScheme)
    } else {
        HandHackColors.lightScheme(accentSeed, dynamicScheme)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.Transparent.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.Transparent.toArgb()
        }
    }

    CompositionLocalProvider(
        LocalAnimationsEnabled provides animationsEnabled,
        LocalHapticsEnabled provides hapticsEnabled,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography,
            shapes = HandHackShapes,
            content = content,
        )
    }
}

@Composable
private fun dynamicLightColorSchemeCompat(context: android.content.Context, @Suppress("UNUSED_PARAMETER") dark: Boolean): ColorScheme? =
    runCatching {
        androidx.compose.material3.dynamicLightColorScheme(context)
    }.getOrNull()

@Composable
private fun dynamicDarkColorSchemeCompat(context: android.content.Context): ColorScheme? =
    runCatching {
        androidx.compose.material3.dynamicDarkColorScheme(context)
    }.getOrNull()
