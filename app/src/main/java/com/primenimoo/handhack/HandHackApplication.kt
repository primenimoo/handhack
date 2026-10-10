package com.primenimoo.handhack

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.primenimoo.handhack.data.dictionary.DictionaryRepository
import com.primenimoo.handhack.data.history.HistoryRepository
import com.primenimoo.handhack.data.settings.SettingsRepository
import com.primenimoo.handhack.engine.AnalysisEngine
import com.primenimoo.handhack.engine.LocalAnalysisEngine
import com.primenimoo.handhack.ui.notifications.AnalysisNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manual dependency container. The dependency set is tiny and fully known, so a
 * DI framework would add build cost for no benefit.
 */
class HandHackApplication : Application() {

    val applicationScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val dictionaryRepository: DictionaryRepository by lazy { DictionaryRepository(this) }
    val historyRepository: HistoryRepository by lazy { HistoryRepository(this) }

    val analysisEngine: AnalysisEngine by lazy {
        LocalAnalysisEngine(applicationScope) { dictionaryRepository.lookupSet() }
    }

    /** Single notifier instance so notification settings stay in sync app-wide. */
    val notifier: AnalysisNotifier by lazy { AnalysisNotifier(this) }

    val coreCount: Int by lazy { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }

    /**
     * Startup progress.
     *
     * The first launch has real work to do (seeding and parsing the shipped dictionary),
     * so the UI waits on this flow instead of rendering an empty home screen. On later
     * launches the prepared copy is reused and the sequence finishes almost immediately.
     */
    private val _bootstrap = MutableStateFlow(BootstrapState())
    val bootstrap: StateFlow<BootstrapState> = _bootstrap.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        applicationScope.launch { runBootstrap() }
        applicationScope.launch {
            settingsRepository.settings.collect(notifier::syncSettings)
        }
    }

    /**
     * Prepares everything the main screens read, publishing progress as it goes.
     *
     * A failure in one step must not leave the app stuck on the loading screen, so the
     * sequence always ends in [BootstrapPhase.Ready] and records the problem instead.
     */
    private suspend fun runBootstrap() {
        var failure: String? = null
        var fromCache = false
        var entries = 0L
        var historyCount = 0

        try {
            val cached = dictionaryRepository.hasPreparedCopy()
            fromCache = cached

            _bootstrap.value = BootstrapState(
                phase = if (cached) BootstrapPhase.LoadingCache else BootstrapPhase.PreparingDictionary,
                progress = if (cached) 0.45f else 0.1f,
                fromCache = cached,
            )

            val dictionary = dictionaryRepository.ensureInitialized()
            entries = dictionary.entryCount

            _bootstrap.value = _bootstrap.value.copy(
                phase = BootstrapPhase.LoadingHistory,
                progress = 0.75f,
                dictionaryEntries = entries,
            )

            historyCount = historyRepository.entries.value.size
        } catch (error: Throwable) {
            failure = error.message ?: "Не удалось подготовить приложение"
        }

        _bootstrap.value = BootstrapState(
            phase = BootstrapPhase.Ready,
            progress = 1f,
            fromCache = fromCache,
            dictionaryEntries = entries,
            historyEntries = historyCount,
            failureMessage = failure,
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_PROGRESS,
                    getString(R.string.channel_progress),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.channel_progress_desc) },
                NotificationChannel(
                    CHANNEL_STATUS,
                    getString(R.string.channel_status),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = getString(R.string.channel_status_desc) },
            ),
        )
    }

    companion object {
        const val CHANNEL_PROGRESS = "handhack_progress"
        const val CHANNEL_STATUS = "handhack_status"
    }
}