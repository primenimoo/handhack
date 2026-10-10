package com.primenimoo.handhack.data.dictionary

import android.content.Context
import android.net.Uri
import com.primenimoo.handhack.core.FileAccess
import com.primenimoo.handhack.core.TextStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class DictionaryState(
    val entries: List<String> = emptyList(),
    val entryCount: Long = 0L,
    val sizeBytes: Long = 0L,
    val isDefault: Boolean = true,
    val loaded: Boolean = false,
    val sourceLabel: String = "special.txt",
)

/**
 * Owns the working copy of the dictionary.
 *
 * The shipped `assets/raw/special.txt` is treated as read-only master data: it is
 * copied once into internal storage and never modified. Reset restores it.
 */
class DictionaryRepository(
    private val context: Context,
    private val assetPath: String = DEFAULT_ASSET,
    private val workingFileName: String = DEFAULT_WORKING_NAME,
) {
    private val assetManager = context.assets
    private val workingFile: File
        get() = File(File(context.filesDir, "dictionary"), workingFileName)

    private val _state = MutableStateFlow(DictionaryState())
    val state: StateFlow<DictionaryState> = _state.asStateFlow()

    /**
     * Guards initialisation.
     *
     * Three different callers ask for the dictionary at startup (the application, the
     * main ViewModel and the dictionary ViewModel). A plain boolean flag used to be set
     * *before* the work finished, so the second and third callers received an empty
     * [DictionaryState] and the UI briefly believed the dictionary was empty.
     */
    private val initMutex = Mutex()
    private var initialized = false

    /** Sidecar that remembers expensive metadata so warm launches skip re-reading the asset. */
    private val markerFile: File
        get() = File(workingFile.parentFile, "$workingFileName.cache")

    /**
     * Creates the working copy on first launch and loads it into memory.
     *
     * Safe to call concurrently: the first caller performs the work and every other one
     * waits for and receives the same completed [DictionaryState].
     */
    suspend fun ensureInitialized(): DictionaryState = withContext(Dispatchers.IO) {
        if (initialized && _state.value.loaded) return@withContext _state.value
        initMutex.withLock {
            if (initialized && _state.value.loaded) return@withLock _state.value
            if (!workingFile.exists() || workingFile.length() == 0L) {
                restoreDefault()
            }
            reload()
            initialized = true
            _state.value
        }
    }

    /**
     * Cheap check for whether the working copy already exists on disk.
     *
     * Used by the startup screen to decide between the "prepare everything" and the
     * "reuse what we built last time" path without touching the file contents.
     */
    fun hasPreparedCopy(): Boolean = workingFile.isFile && workingFile.length() > 0L

    suspend fun reload(): DictionaryState = withContext(Dispatchers.IO) {
        if (!workingFile.exists()) {
            _state.value = DictionaryState(loaded = true)
            return@withContext _state.value
        }
        val entries = ArrayList<String>(16_384)
        runCatching {
            workingFile.inputStream().buffered(FileAccess.BUFFER_SIZE).use { input ->
                val reader = TextStream.reader(input)
                TextStream.forEachLine(reader) { line -> entries.add(line) }
            }
        }
        val cached = readMarker()
        _state.value = DictionaryState(
            entries = entries,
            entryCount = entries.size.toLong(),
            sizeBytes = workingFile.length(),
            isDefault = cached?.matches(workingFile) ?: isPristineDefault(),
            loaded = true,
        ).also { writeMarker(it) }
        _state.value
    }

    suspend fun save(entries: List<String>): DictionaryState = withContext(Dispatchers.IO) {
        writeWorkingCopy(entries)
        reload()
    }

    suspend fun importFrom(uri: Uri): Result<DictionaryState> = withContext(Dispatchers.IO) {
        runCatching {
            val entries = ArrayList<String>()
            FileAccess.openInput(context, uri).use { input ->
                val reader = TextStream.reader(input)
                TextStream.forEachLine(reader) { line -> entries.add(line) }
            }
            writeWorkingCopy(entries)
            reload().copy(isDefault = false, sourceLabel = "Импортированный файл")
        }
    }

    suspend fun exportTo(context: Context, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            FileAccess.openOutput(context, uri).use { rawOut ->
                java.io.BufferedWriter(java.io.OutputStreamWriter(rawOut, Charsets.UTF_8), FileAccess.BUFFER_SIZE).use { writer ->
                    _state.value.entries.forEach { entry ->
                        writer.write(entry)
                        writer.write("\n")
                    }
                    writer.flush()
                }
            }
        }
    }

    suspend fun clear(): DictionaryState = withContext(Dispatchers.IO) {
        writeWorkingCopy(emptyList())
        reload().copy(isDefault = false)
    }

    suspend fun restoreDefault(): DictionaryState = withContext(Dispatchers.IO) {
        workingFile.parentFile?.mkdirs()
        markerFile.delete()
        assetManager.open(ASSET_SOURCE).use { input ->
            workingFile.outputStream().buffered(FileAccess.BUFFER_SIZE).use { output ->
                input.copyTo(output, FileAccess.BUFFER_SIZE)
                output.flush()
            }
        }
        val restored = reload()
        restored.copy(isDefault = true, sourceLabel = DEFAULT_WORKING_NAME)
    }

    /** Non-empty entries only — used by the analysis engine as its lookup set. */
    suspend fun lookupSet(): Set<String> = withContext(Dispatchers.IO) {
        HashSet<String>(_state.value.entries.size.coerceAtLeast(16)).apply {
            _state.value.entries.forEach { entry ->
                val trimmed = entry.trim()
                if (trimmed.isNotEmpty()) add(trimmed)
            }
        }
    }

    private suspend fun writeWorkingCopy(entries: List<String>) {
        workingFile.parentFile?.mkdirs()
        markerFile.delete()
        val tmp = File(workingFile.parentFile, "$workingFileName.tmp")
        java.io.BufferedWriter(
            java.io.OutputStreamWriter(tmp.outputStream().buffered(FileAccess.BUFFER_SIZE), Charsets.UTF_8),
            FileAccess.BUFFER_SIZE,
        ).use { writer ->
            entries.forEach { entry ->
                writer.write(entry)
                writer.write("\n")
            }
            writer.flush()
        }
        if (!tmp.renameTo(workingFile)) {
            tmp.copyTo(workingFile, overwrite = true)
            tmp.delete()
        }
    }

    private fun isPristineDefault(): Boolean = runCatching {
        assetManager.open(ASSET_SOURCE).use { input ->
            workingFile.inputStream().buffered(FileAccess.BUFFER_SIZE).use { working ->
                // Streamed comparison: the previous implementation materialised both files
                // (~280 KB of garbage) on every single launch.
                val assetBuffer = ByteArray(FileAccess.BUFFER_SIZE)
                val workingBuffer = ByteArray(FileAccess.BUFFER_SIZE)
                while (true) {
                    val assetRead = input.read(assetBuffer)
                    val workingRead = working.read(workingBuffer)
                    if (assetRead != workingRead) return@use false
                    if (assetRead <= 0) return@use true
                    for (i in 0 until assetRead) {
                        if (assetBuffer[i] != workingBuffer[i]) return@use false
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
        }
    }.getOrDefault(false)

    /**
     * Reads the sidecar describing the current working copy.
     *
     * Returns null when the marker is absent, unreadable or written by another cache
     * schema, which makes the caller fall back to the expensive byte comparison.
     */
    private fun readMarker(): DictionaryCacheMarker? =
        DictionaryCacheMarker.parse(runCatching { markerFile.readText() }.getOrNull())

    /** Persists the metadata a warm launch can trust instead of re-reading the asset. */
    private fun writeMarker(state: DictionaryState) {
        runCatching {
            workingFile.parentFile?.mkdirs()
            markerFile.writeText(DictionaryCacheMarker.of(workingFile, state.entryCount, state.isDefault).serialize())
        }
    }

    companion object {
        const val DEFAULT_ASSET = "raw/special.txt"
        const val ASSET_SOURCE = "raw/special.txt"
        const val DEFAULT_WORKING_NAME = "special.txt"
    }
}
