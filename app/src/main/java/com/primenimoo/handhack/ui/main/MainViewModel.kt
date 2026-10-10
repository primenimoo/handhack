package com.primenimoo.handhack.ui.main

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.primenimoo.handhack.HandHackApplication
import com.primenimoo.handhack.core.FileAccess
import com.primenimoo.handhack.data.history.HistoryEntry
import com.primenimoo.handhack.data.history.HistoryOutcome
import com.primenimoo.handhack.data.history.HistoryRepository
import com.primenimoo.handhack.data.settings.SettingsRepository
import com.primenimoo.handhack.detect.DetectionResult
import com.primenimoo.handhack.detect.FileDetector
import com.primenimoo.handhack.detect.FileInfo
import com.primenimoo.handhack.engine.AnalysisEngine
import com.primenimoo.handhack.engine.AnalysisProgress
import com.primenimoo.handhack.engine.AnalysisRequest
import com.primenimoo.handhack.engine.AnalysisResult
import com.primenimoo.handhack.engine.AnalysisStatistics
import com.primenimoo.handhack.engine.AnalysisStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class MainStep { IMPORT, DETECT, VALIDATE, PREPARE, PROGRESS, RESULT }

data class MainUiState(
    val step: MainStep = MainStep.IMPORT,
    val fileInfo: FileInfo? = null,
    val localCopy: File? = null,
    val status: AnalysisStatus = AnalysisStatus.IDLE,
    val progress: AnalysisProgress = AnalysisProgress(),
    val statistics: AnalysisStatistics = AnalysisStatistics(),
    val result: AnalysisResult? = null,
    val message: String? = null,
    val isPreparing: Boolean = false,
    val dictionarySize: Long = 0L,
    val workers: Int = 1,
    val keepScreenOn: Boolean = true,
) {
    val canStart: Boolean get() = fileInfo != null && localCopy != null && !isPreparing && status != AnalysisStatus.RUNNING
    val isRunning: Boolean get() = status == AnalysisStatus.RUNNING || status == AnalysisStatus.PAUSED
    val canPause: Boolean get() = status == AnalysisStatus.RUNNING || status == AnalysisStatus.PAUSED
}

@androidx.compose.runtime.Immutable
data class AnalysisSnapshot(
    val status: AnalysisStatus = AnalysisStatus.IDLE,
    val progress: AnalysisProgress = AnalysisProgress(),
    val statistics: AnalysisStatistics = AnalysisStatistics(),
    val result: AnalysisResult? = null,
)

/**
 * Owns the entire import → detect → validate → prepare → analyse → result flow.
 * All heavy lifting is delegated to [AnalysisEngine] so this class stays reactive
 * and cheap to test.
 */
class MainViewModel(
    application: Application,
    private val engine: AnalysisEngine,
    private val dictionaryRepository: com.primenimoo.handhack.data.dictionary.DictionaryRepository,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val detector: FileDetector = FileDetector(),
) : AndroidViewModel(application) {

    private val context = application
    private val app = application as HandHackApplication
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val snapshot: StateFlow<AnalysisSnapshot> =
        combine(engine.status, engine.progress, engine.statistics, engine.result) { status, progress, stats, result ->
            AnalysisSnapshot(status, progress, stats, result)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalysisSnapshot())

    private var recordedTerminalKey: String? = null

    init {
        viewModelScope.launch {
            dictionaryRepository.ensureInitialized()
            _uiState.update { it.copy(dictionarySize = dictionaryRepository.state.value.entryCount) }
        }
        viewModelScope.launch {
            historyRepository.load()
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        workers = com.primenimoo.handhack.engine.LocalAnalysisEngine.suggestedWorkers(
                            settings.workerCountMode.storageKey,
                            settings.manualWorkerCount,
                            app.coreCount,
                        ),
                        keepScreenOn = settings.keepScreenOn,
                    )
                }
            }
        }
        viewModelScope.launch {
            snapshot.collect { current ->
                _uiState.update {
                    it.copy(
                        status = current.status,
                        progress = current.progress,
                        statistics = current.statistics,
                        result = current.result,
                    )
                }
                if (current.status.isTerminal) onRunFinished(current)
            }
        }
    }

    fun importFile(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparing = true, message = null, step = MainStep.DETECT) }
            when (val detection = detector.inspect(context, uri)) {
                is DetectionResult.Unsupported -> fail(detection.reason, MainStep.IMPORT)
                is DetectionResult.Failed ->
                    fail(detection.reason, MainStep.IMPORT)
                is DetectionResult.Detected -> withFile(context, uri, detection.info)
            }
        }
    }

    private suspend fun withFile(context: Application, uri: Uri, info: FileInfo) {
        _uiState.update { it.copy(step = MainStep.VALIDATE, isPreparing = true) }
        val target = FileAccess.workspaceFile(context, "${System.currentTimeMillis()}_${info.fileName}")
        val copied = runCatching { FileAccess.copyToLocal(context, uri, target) }
        copied.onFailure {
            fail("Не удалось подготовить файл: ${it.message ?: "неизвестная ошибка"}", MainStep.IMPORT)
            return
        }
        _uiState.update {
            it.copy(
                fileInfo = info,
                localCopy = target,
                step = MainStep.PREPARE,
                result = null,
                message = null,
            )
        }
        prepare()
    }

    fun prepare() {
        val state = _uiState.value
        val info = state.fileInfo ?: return
        val local = state.localCopy ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(step = MainStep.PREPARE, isPreparing = true) }
            engine.prepare(
                AnalysisRequest(
                    fileName = info.fileName,
                    formatLabel = info.format.label,
                    sizeBytes = info.sizeBytes,
                    sourcePath = local.absolutePath,
                    dictionaryPath = File(context.filesDir, "dictionary").absolutePath,
                    textBased = info.format.textBased,
                    workers = state.workers,
                ),
            )
            _uiState.update { it.copy(isPreparing = false, step = MainStep.PROGRESS) }
        }
    }

    fun start() {
        viewModelScope.launch {
            _uiState.update { it.copy(step = MainStep.PROGRESS, result = null) }
            engine.start()
        }
    }

    fun pause() {
        viewModelScope.launch { engine.pause() }
    }

    fun resume() {
        viewModelScope.launch { engine.resume() }
    }

    fun cancel() {
        viewModelScope.launch { engine.cancel() }
    }

    fun reset() {
        viewModelScope.launch {
            engine.reset()
            _uiState.value.localCopy?.delete()
            _uiState.value = MainUiState(
                dictionarySize = dictionaryRepository.state.value.entryCount,
                workers = _uiState.value.workers,
                keepScreenOn = _uiState.value.keepScreenOn,
            )
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun openTelegram() {
        val intent = Intent(Intent.ACTION_VIEW, context.getString(com.primenimoo.handhack.R.string.telegram_url).toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun onTelegramCardDismissed() {
        viewModelScope.launch { settingsRepository.markTelegramCardSeen() }
    }

    private fun onRunFinished(current: AnalysisSnapshot) {
        val result = current.result ?: return
        val terminalKey = "${result.status}:${result.durationMillis}:${result.processedItems}"
        if (terminalKey == recordedTerminalKey) return
        recordedTerminalKey = terminalKey

        if (result.status == AnalysisStatus.ERROR || result.status == AnalysisStatus.CANCELLED) {
            _uiState.update { it.copy(message = result.errorMessage ?: "Подбор остановлен") }
        }
        viewModelScope.launch {
            historyRepository.add(
                HistoryEntry(
                    id = UUID.randomUUID().toString(),
                    fileName = result.fileName,
                    formatLabel = result.formatLabel,
                    timestampMillis = System.currentTimeMillis(),
                    outcome = HistoryOutcome.fromStatus(result.status),
                    processedItems = result.processedItems,
                    totalItems = result.totalItems,
                    durationMillis = result.durationMillis,
                    detail = result.failureReason ?: result.errorMessage.orEmpty(),
                ),
            )
            _uiState.update { it.copy(step = MainStep.RESULT) }
        }
    }

    private fun fail(message: String, step: MainStep) {
        _uiState.update {
            it.copy(isPreparing = false, message = message, step = step, status = AnalysisStatus.IDLE)
        }
    }

    override fun onCleared() {
        engine.reset()
        super.onCleared()
    }
}