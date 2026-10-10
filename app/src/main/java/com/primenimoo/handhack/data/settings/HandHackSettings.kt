package com.primenimoo.handhack.data.settings

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val storageKey: String, val label: String) {
    SYSTEM("system", "Системная"),
    LIGHT("light", "Светлая"),
    DARK("dark", "Тёмная");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}

enum class PerformanceMode(val storageKey: String, val label: String, val description: String) {
    AUTO("auto", "Авто", "Разумное число потоков для этого устройства"),
    ECO("eco", "Экономичный", "Минимум CPU, дольше по времени, холоднее телефон"),
    BALANCED("balanced", "Сбалансированный", "Половина доступных ядер"),
    MAXIMUM("maximum", "Максимальная", "Почти все ядра, с ограничением нагрева");

    companion object {
        fun fromKey(key: String?): PerformanceMode = entries.firstOrNull { it.storageKey == key } ?: AUTO
    }
}

enum class WorkerCountMode(val storageKey: String, val label: String) {
    AUTO("auto", "Авто"),
    LIMITED("limited", "Вручную");

    companion object {
        fun fromKey(key: String?): WorkerCountMode = entries.firstOrNull { it.storageKey == key } ?: AUTO
    }
}

/** Immutable snapshot of everything stored in DataStore. */
data class HandHackSettings(
    val onboardingCompleted: Boolean = false,
    val onboardingPage: Int = 0,
    val telegramCardSeen: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentKey: String = "blue",
    val customAccent: Color = Color(0xFF2F6BFF),
    val dynamicColor: Boolean = true,
    val animationsEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val performanceMode: PerformanceMode = PerformanceMode.AUTO,
    val workerCountMode: WorkerCountMode = WorkerCountMode.AUTO,
    val manualWorkerCount: Int = 2,
    val keepScreenOn: Boolean = true,
    val autoSaveDictionary: Boolean = false,
    val lastImportLabel: String = "Не выбрано",
    val lastExportLabel: String = "Не выбрано",
    val notifyProcessCompleted: Boolean = true,
    val notifyErrors: Boolean = true,
    val notifyImportCompleted: Boolean = true,
) {
    val accentPreset get() = com.primenimoo.handhack.ui.theme.AccentPreset.fromKey(accentKey)
    val accentSeed: Color get() = if (accentPreset == com.primenimoo.handhack.ui.theme.AccentPreset.CUSTOM) customAccent else accentPreset.seed
}

/** Maps the DataStore flow into the UI-facing settings model. */
fun Flow<androidx.datastore.preferences.core.Preferences>.mapToHandHackSettings(): Flow<HandHackSettings> =
    map { prefs ->
        HandHackSettings(
            onboardingCompleted = prefs[Keys.ONBOARDING_DONE] ?: false,
            onboardingPage = prefs[Keys.ONBOARDING_PAGE] ?: 0,
            telegramCardSeen = prefs[Keys.TELEGRAM_CARD_SEEN] ?: false,
            themeMode = ThemeMode.fromKey(prefs[Keys.THEME]),
            accentKey = prefs[Keys.ACCENT] ?: "blue",
            customAccent = prefs[Keys.CUSTOM_ACCENT]?.let { Color(it.toULong()) } ?: Color(0xFF2F6BFF),
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            animationsEnabled = prefs[Keys.ANIMATIONS] ?: true,
            hapticsEnabled = prefs[Keys.HAPTICS] ?: true,
            performanceMode = PerformanceMode.fromKey(prefs[Keys.PERFORMANCE]),
            workerCountMode = WorkerCountMode.fromKey(prefs[Keys.WORKER_MODE]),
            manualWorkerCount = prefs[Keys.WORKER_COUNT] ?: 2,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            autoSaveDictionary = prefs[Keys.AUTO_SAVE] ?: false,
            lastImportLabel = prefs[Keys.LAST_IMPORT] ?: "Не выбрано",
            lastExportLabel = prefs[Keys.LAST_EXPORT] ?: "Не выбрано",
            notifyProcessCompleted = prefs[Keys.NOTIFY_COMPLETED] ?: true,
            notifyErrors = prefs[Keys.NOTIFY_ERRORS] ?: true,
            notifyImportCompleted = prefs[Keys.NOTIFY_IMPORT] ?: true,
        )
    }
