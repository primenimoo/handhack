package com.primenimoo.handhack.engine

/** Lifecycle of a single analysis run. The UI renders directly from these states. */
enum class AnalysisStatus(val label: String) {
    IDLE("Ожидание"),
    PREPARING("Подготовка"),
    RUNNING("Идёт подбор"),
    PAUSED("Приостановлено"),
    COMPLETED("Завершено"),
    NO_RESULT("Совпадений нет"),
    CANCELLED("Отменено"),
    ERROR("Ошибка"),
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == NO_RESULT || this == CANCELLED || this == ERROR

    val isActive: Boolean
        get() = this == PREPARING || this == RUNNING || this == PAUSED
}

/** Input for one analysis run. */
data class AnalysisRequest(
    val fileName: String,
    val formatLabel: String,
    val sizeBytes: Long,
    val sourcePath: String,
    val dictionaryPath: String,
    val textBased: Boolean,
    val workers: Int,
)

/** Real progress emitted by the engine — never synthesised by the UI. */
data class AnalysisProgress(
    val processed: Long = 0L,
    val total: Long = 0L,
    val fraction: Float = 0f,
    val indeterminate: Boolean = true,
    val stageLabel: String = "",
)

/** Real statistics derived from the actual processing loop. */
data class AnalysisStatistics(
    val processed: Long = 0L,
    val total: Long = 0L,
    val itemsPerSecond: Double = 0.0,
    val elapsedMillis: Long = 0L,
    val remainingMillis: Long = -1L,
    val workers: Int = 1,
    val matchedItems: Int = 0,
)

/** A passphrase recovered for one captured network. */
data class RecoveredPassword(
    val ssid: String,
    val password: String,
)

/** Result of a completed, failed or cancelled run. */
data class AnalysisResult(
    val status: AnalysisStatus,
    val fileName: String,
    val formatLabel: String,
    val sizeBytes: Long,
    val totalItems: Long,
    val processedItems: Long,
    val durationMillis: Long,
    val workersUsed: Int,
    val matchedEntries: List<String> = emptyList(),
    val recoveredPasswords: List<RecoveredPassword> = emptyList(),
    val errorMessage: String? = null,
    val failureReason: String? = null,
)

/**
 * The abstraction layer the UI talks to. Implementations must be safe to call
 * from the main thread; all heavy work happens on background dispatchers inside
 * the engine.
 */
interface AnalysisEngine {
    val status: kotlinx.coroutines.flow.StateFlow<AnalysisStatus>
    val progress: kotlinx.coroutines.flow.StateFlow<AnalysisProgress>
    val statistics: kotlinx.coroutines.flow.StateFlow<AnalysisStatistics>
    val result: kotlinx.coroutines.flow.StateFlow<AnalysisResult?>

    /** Request of the run currently loaded, or `null` before the first [prepare]. */
    val currentRequest: kotlinx.coroutines.flow.StateFlow<AnalysisRequest?>

    suspend fun prepare(request: AnalysisRequest)
    suspend fun start()
    suspend fun pause()
    suspend fun resume()
    suspend fun cancel()
    fun reset()
}
