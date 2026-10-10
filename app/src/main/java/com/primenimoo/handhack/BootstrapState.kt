package com.primenimoo.handhack

/**
 * Startup work HandHack performs before the main UI can be trusted.
 *
 * Exposed as a flow so the first screen can show real progress instead of a spinner that
 * lies about what is happening.
 */
enum class BootstrapPhase {
    /** Deciding whether the prepared copy on disk is still valid. */
    CheckingCache,

    /** First launch: copying the shipped dictionary and parsing it. */
    PreparingDictionary,

    /** Warm launch: reusing the prepared copy. */
    LoadingCache,

    /** Reading previously finished runs. */
    LoadingHistory,

    /** Everything the UI needs is available. */
    Ready,
}

/**
 * Snapshot of the startup sequence.
 *
 * [fromCache] distinguishes a warm launch from a full first-run load so the UI can be
 * honest about it, and [phase] drives both the label and the progress fraction.
 */
data class BootstrapState(
    val phase: BootstrapPhase = BootstrapPhase.CheckingCache,
    val progress: Float = 0f,
    val fromCache: Boolean = false,
    val dictionaryEntries: Long = 0L,
    val historyEntries: Int = 0,
    val failureMessage: String? = null,
) {
    val isReady: Boolean get() = phase == BootstrapPhase.Ready

    /** Short, human readable description of the current step. */
    val label: String
        get() = when (phase) {
            BootstrapPhase.CheckingCache -> "Проверяем кэш"
            BootstrapPhase.PreparingDictionary -> "Готовим словарь"
            BootstrapPhase.LoadingCache -> "Загружаем кэш"
            BootstrapPhase.LoadingHistory -> "Загружаем историю"
            BootstrapPhase.Ready -> if (failureMessage != null) "Запуск с ошибками" else "Готово"
        }
}