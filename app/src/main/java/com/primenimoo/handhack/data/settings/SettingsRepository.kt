package com.primenimoo.handhack.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.Flow

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "handhack_settings")

object Keys {
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    val ONBOARDING_PAGE = intPreferencesKey("onboarding_page")
    val TELEGRAM_CARD_SEEN = booleanPreferencesKey("telegram_card_seen")
    val THEME = stringPreferencesKey("theme_mode")
    val ACCENT = stringPreferencesKey("accent")
    val CUSTOM_ACCENT = longPreferencesKey("custom_accent")
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val ANIMATIONS = booleanPreferencesKey("animations_enabled")
    val HAPTICS = booleanPreferencesKey("haptics_enabled")
    val PERFORMANCE = stringPreferencesKey("performance_mode")
    val WORKER_MODE = stringPreferencesKey("worker_count_mode")
    val WORKER_COUNT = intPreferencesKey("worker_count")
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    val AUTO_SAVE = booleanPreferencesKey("auto_save_dictionary")
    val LAST_IMPORT = stringPreferencesKey("last_import_location")
    val LAST_EXPORT = stringPreferencesKey("last_export_location")
    val NOTIFY_COMPLETED = booleanPreferencesKey("notify_completed")
    val NOTIFY_ERRORS = booleanPreferencesKey("notify_errors")
    val NOTIFY_IMPORT = booleanPreferencesKey("notify_import")
}

/**
 * Single source of truth for user preferences. Everything is stored offline in
 * DataStore; nothing here touches the network.
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<HandHackSettings> = context.dataStore.data.mapToHandHackSettings()

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    suspend fun completeOnboarding(page: Int) = edit { it[Keys.ONBOARDING_DONE] = true; it[Keys.ONBOARDING_PAGE] = page }
    suspend fun setOnboardingPage(page: Int) = edit { it[Keys.ONBOARDING_PAGE] = page }
    suspend fun restartOnboarding() = edit { it[Keys.ONBOARDING_DONE] = false; it[Keys.ONBOARDING_PAGE] = 0 }
    suspend fun markTelegramCardSeen() = edit { it[Keys.TELEGRAM_CARD_SEEN] = true }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME] = mode.storageKey }
    suspend fun setAccent(key: String) = edit { it[Keys.ACCENT] = key }
    suspend fun setCustomAccent(color: Color) = edit { it[Keys.CUSTOM_ACCENT] = color.value.toLong() }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setAnimations(enabled: Boolean) = edit { it[Keys.ANIMATIONS] = enabled }
    suspend fun setHaptics(enabled: Boolean) = edit { it[Keys.HAPTICS] = enabled }
    suspend fun setPerformanceMode(mode: PerformanceMode) = edit { it[Keys.PERFORMANCE] = mode.storageKey }
    suspend fun setWorkerCountMode(mode: WorkerCountMode) = edit { it[Keys.WORKER_MODE] = mode.storageKey }
    suspend fun setManualWorkerCount(count: Int) = edit { it[Keys.WORKER_COUNT] = count.coerceIn(1, 32) }
    suspend fun setKeepScreenOn(enabled: Boolean) = edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    suspend fun setAutoSave(enabled: Boolean) = edit { it[Keys.AUTO_SAVE] = enabled }
    suspend fun setLastImportLocation(label: String) = edit { it[Keys.LAST_IMPORT] = label }
    suspend fun setLastExportLocation(label: String) = edit { it[Keys.LAST_EXPORT] = label }
    suspend fun setNotifyCompleted(enabled: Boolean) = edit { it[Keys.NOTIFY_COMPLETED] = enabled }
    suspend fun setNotifyErrors(enabled: Boolean) = edit { it[Keys.NOTIFY_ERRORS] = enabled }
    suspend fun setNotifyImport(enabled: Boolean) = edit { it[Keys.NOTIFY_IMPORT] = enabled }
}
