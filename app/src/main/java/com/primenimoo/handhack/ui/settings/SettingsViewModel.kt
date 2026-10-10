package com.primenimoo.handhack.ui.settings

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.primenimoo.handhack.BuildConfig
import com.primenimoo.handhack.HandHackApplication
import com.primenimoo.handhack.core.FileAccess
import com.primenimoo.handhack.data.history.HistoryRepository
import com.primenimoo.handhack.data.settings.HandHackSettings
import com.primenimoo.handhack.data.settings.PerformanceMode
import com.primenimoo.handhack.data.settings.SettingsRepository
import com.primenimoo.handhack.data.settings.ThemeMode
import com.primenimoo.handhack.data.settings.WorkerCountMode
import com.primenimoo.handhack.engine.LocalAnalysisEngine
import com.primenimoo.handhack.ui.theme.AccentPreset
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    application: Application,
    private val repository: SettingsRepository,
    private val historyRepository: HistoryRepository,
) : AndroidViewModel(application) {

    private val app: HandHackApplication = application as HandHackApplication

    val settings: StateFlow<HandHackSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HandHackSettings(),
    )

    /** Version comes from `versionName` in the build file, never hardcoded. */
    val versionName: String = BuildConfig.VERSION_NAME

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { repository.setThemeMode(mode) }
    fun setAccent(preset: AccentPreset) = viewModelScope.launch { repository.setAccent(preset.storageKey) }
    fun setCustomAccent(color: Color) = viewModelScope.launch { repository.setCustomAccent(color) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { repository.setDynamicColor(enabled) }
    fun setAnimations(enabled: Boolean) = viewModelScope.launch { repository.setAnimations(enabled) }
    fun setHaptics(enabled: Boolean) = viewModelScope.launch { repository.setHaptics(enabled) }
    fun setPerformance(mode: PerformanceMode) = viewModelScope.launch { repository.setPerformanceMode(mode) }
    fun setWorkerMode(mode: WorkerCountMode) = viewModelScope.launch { repository.setWorkerCountMode(mode) }
    fun setWorkerCount(count: Int) = viewModelScope.launch { repository.setManualWorkerCount(count) }
    fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch { repository.setKeepScreenOn(enabled) }
    fun setAutoSave(enabled: Boolean) = viewModelScope.launch { repository.setAutoSave(enabled) }
    fun setNotifyCompleted(enabled: Boolean) = viewModelScope.launch { repository.setNotifyCompleted(enabled) }
    fun setNotifyErrors(enabled: Boolean) = viewModelScope.launch { repository.setNotifyErrors(enabled) }
    fun setNotifyImport(enabled: Boolean) = viewModelScope.launch { repository.setNotifyImport(enabled) }

    fun restartOnboarding() = viewModelScope.launch { repository.restartOnboarding() }

    fun clearHistory() = viewModelScope.launch { historyRepository.clear() }

    fun historyCount(): Int = historyRepository.entries.value.size

    fun coreCount(): Int = app.coreCount

    fun effectiveWorkers(settings: HandHackSettings): Int =
        LocalAnalysisEngine.suggestedWorkers(settings.workerCountMode.storageKey, settings.manualWorkerCount, app.coreCount)

    fun recordImportLocation(label: String) = viewModelScope.launch { repository.setLastImportLocation(label) }

    /** Resolves a display name off the main thread; used for SAF result labels. */
    fun labelFor(uri: android.net.Uri, onLabel: (String) -> Unit) {
        viewModelScope.launch {
            onLabel(FileAccess.displayName(app, uri))
        }
    }
}