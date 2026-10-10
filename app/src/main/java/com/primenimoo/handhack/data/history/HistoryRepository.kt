package com.primenimoo.handhack.data.history

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import com.primenimoo.handhack.engine.AnalysisStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class HistoryOutcome(val storageKey: String, val label: String) {
    COMPLETED("completed", "Подбор завершён"),
    NO_RESULT("no_result", "Совпадений нет"),
    CANCELLED("cancelled", "Остановлен"),
    FAILED("failed", "Ошибка");

    companion object {
        fun fromKey(key: String?): HistoryOutcome = entries.firstOrNull { it.storageKey == key } ?: COMPLETED

        fun fromStatus(status: AnalysisStatus): HistoryOutcome = when (status) {
            AnalysisStatus.COMPLETED -> COMPLETED
            AnalysisStatus.NO_RESULT -> NO_RESULT
            AnalysisStatus.CANCELLED -> CANCELLED
            else -> FAILED
        }
    }
}

data class HistoryEntry(
    val id: String,
    val fileName: String,
    val formatLabel: String,
    val timestampMillis: Long,
    val outcome: HistoryOutcome,
    val processedItems: Long,
    val totalItems: Long,
    val durationMillis: Long,
    val detail: String,
)

/**
 * Lightweight JSON-file backed history. Kept dependency-free on purpose — the
 * app avoids a database because the data set is tiny and always local.
 */
class HistoryRepository(
    private val context: Context,
    private val maxEntries: Int = 60,
) {
    private val file: File
        get() = File(File(context.filesDir, "history"), "history.json")

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val parsed = runCatching {
            if (!file.exists()) return@runCatching emptyList<HistoryEntry>()
            val array = JSONArray(file.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(
                        HistoryEntry(
                            id = obj.optString("id"),
                            fileName = obj.optString("name"),
                            formatLabel = obj.optString("format"),
                            timestampMillis = obj.optLong("ts"),
                            outcome = HistoryOutcome.fromKey(obj.optString("outcome")),
                            processedItems = obj.optLong("processed"),
                            totalItems = obj.optLong("total"),
                            durationMillis = obj.optLong("duration"),
                            detail = obj.optString("detail"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        _entries.value = parsed.sortedByDescending { it.timestampMillis }
    }

    suspend fun add(entry: HistoryEntry) = withContext(Dispatchers.IO) {
        val updated = (listOf(entry) + _entries.value).take(maxEntries)
        _entries.value = updated
        persist(updated)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        _entries.value = emptyList()
        runCatching { if (file.exists()) file.delete() }
    }

    private fun persist(entries: List<HistoryEntry>) {
        runCatching {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(
                    JSONObject().apply {
                        put("id", entry.id)
                        put("name", entry.fileName)
                        put("format", entry.formatLabel)
                        put("ts", entry.timestampMillis)
                        put("outcome", entry.outcome.storageKey)
                        put("processed", entry.processedItems)
                        put("total", entry.totalItems)
                        put("duration", entry.durationMillis)
                        put("detail", entry.detail)
                    },
                )
            }
            file.writeText(array.toString())
        }
    }
}
