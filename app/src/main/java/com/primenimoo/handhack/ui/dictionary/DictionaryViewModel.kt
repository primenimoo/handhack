package com.primenimoo.handhack.ui.dictionary

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.primenimoo.handhack.HandHackApplication
import com.primenimoo.handhack.core.FileAccess
import com.primenimoo.handhack.data.dictionary.DictionaryRepository
import com.primenimoo.handhack.data.dictionary.DictionaryState
import com.primenimoo.handhack.data.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorUiState(
    val lines: List<String> = emptyList(),
    val savedLines: List<String> = emptyList(),
    val saving: Boolean = false,
    val message: String? = null,
) {
    val dirty: Boolean get() = lines != savedLines
}

data class DictionaryUiState(
    val dictionary: DictionaryState = DictionaryState(),
    val editor: EditorUiState = EditorUiState(),
    val activeTab: Int = 0,
) {
    val entryCount: Long get() = dictionary.entryCount
    val canSave: Boolean get() = editor.dirty && !editor.saving
}

/** Progress of a single remote dictionary download. */
data class DownloadState(
    val downloading: Boolean = false,
    val progress: Float = 0f,
)

class DictionaryViewModel(
    application: Application,
    private val repository: DictionaryRepository,
    private val settingsRepository: SettingsRepository = (application as HandHackApplication).settingsRepository,
) : AndroidViewModel(application) {

    private val notifier = (application as HandHackApplication).notifier
    private val _editor = MutableStateFlow(EditorUiState())
    private val _download = MutableStateFlow(DownloadState())
    private val _tab = MutableStateFlow(0)
    private var autoSaveJob: Job? = null

    // Read once instead of hitting DataStore on every keystroke.
    private var autoSaveEnabled = true

    val editorState: StateFlow<EditorUiState> = _editor.asStateFlow()

    val downloadState: StateFlow<DownloadState> = _download.asStateFlow()

    val uiState: StateFlow<DictionaryUiState> =
        combine(repository.state, _editor, _tab) { dictionary, editor, tab ->
            DictionaryUiState(dictionary, editor, tab)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DictionaryUiState())

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                autoSaveEnabled = settings.autoSaveDictionary
            }
        }
        viewModelScope.launch {
            syncEditor(repository.ensureInitialized())
        }
    }

    private fun syncEditor(state: DictionaryState) {
        _editor.value = EditorUiState(lines = state.entries, savedLines = state.entries)
    }

    fun selectTab(index: Int) {
        _tab.value = index
    }

    /**
     * Edits are applied per line so a keystroke never re-lays out the whole dictionary.
     */
    fun onLineChanged(index: Int, value: String) {
        _editor.update { current ->
            if (index !in current.lines.indices || current.lines[index] == value) return@update current
            val updated = current.lines.toMutableList()
            updated[index] = value
            current.copy(lines = updated, message = null)
        }
        autoSave()
    }

    fun onLineAdded(value: String = "") {
        _editor.update { it.copy(lines = it.lines + value, message = null) }
        autoSave()
    }

    fun onLineRemoved(index: Int) {
        _editor.update { current ->
            if (index !in current.lines.indices) current
            else current.copy(lines = current.lines.toMutableList().also { it.removeAt(index) }, message = null)
        }
        autoSave()
    }

    /**
     * With auto-save enabled the working copy follows the editor. Saves are coalesced
     * so typing does not hammer the disk on every keystroke.
     */
    private fun autoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            if (!autoSaveEnabled) return@launch
            delay(AUTO_SAVE_DEBOUNCE_MS)
            val current = _editor.value
            if (!current.dirty || current.saving) return@launch
            repository.save(current.lines)
            _editor.update { it.copy(savedLines = it.lines) }
        }
    }

    fun loadIntoEditor() {
        viewModelScope.launch {
            syncEditor(repository.reload())
        }
    }

    fun save() {
        viewModelScope.launch {
            _editor.update { it.copy(saving = true) }
            repository.save(_editor.value.lines)
            _editor.update {
                it.copy(
                    savedLines = it.lines,
                    saving = false,
                    message = "Изменения сохранены локально",
                )
            }
        }
    }

    fun discard() {
        _editor.update {
            it.copy(lines = it.savedLines, message = "Несохранённые изменения отменены")
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            _editor.update { it.copy(saving = true) }
            repository.importFrom(uri)
                .onSuccess { state ->
                    _editor.update {
                        it.copy(
                            lines = state.entries,
                            savedLines = state.entries,
                            saving = false,
                            message = "Импортировано записей: ${state.entryCount}",
                        )
                    }
                    notifier.showImportCompleted(
                        entryCount = state.entryCount,
                        fileLabel = FileAccess.displayName(getApplication(), uri),
                    )
                }
                .onFailure { error ->
                    _editor.update { it.copy(saving = false, message = error.message ?: "Не удалось импортировать файл") }
                }
        }
    }

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            repository.exportTo(getApplication(), uri)
                .onSuccess { _editor.update { it.copy(message = "Экспорт завершён") } }
                .onFailure { _editor.update { it.copy(message = it.message ?: "Не удалось экспортировать") } }
        }
    }

    fun clear() {
        viewModelScope.launch {
            repository.clear()
            _editor.update { it.copy(lines = emptyList(), savedLines = emptyList(), message = "Рабочая копия очищена") }
        }
    }

    fun restoreDefault() {
        viewModelScope.launch {
            repository.restoreDefault()
            val entries = repository.state.value.entries
            _editor.update {
                it.copy(lines = entries, savedLines = entries, message = "Словарь восстановлен из special.txt")
            }
        }
    }

    /**
     * Fetches the catalogue's remote list into the working copy, mirroring the iOS
     * «Скачать с GitHub» flow. On success the working copy becomes a downloaded file.
     */
    fun downloadFromGitHub() {
        if (_download.value.downloading) return
        viewModelScope.launch {
            _download.value = DownloadState(downloading = true)
            repository.downloadRemote(DictionaryRepository.REMOTE_SPECIAL_URL) { progress ->
                _download.value = DownloadState(downloading = true, progress = progress)
            }
                .onSuccess { state ->
                    _download.value = DownloadState(downloading = false)
                    _editor.update {
                        it.copy(
                            lines = state.entries,
                            savedLines = state.entries,
                            message = "Скачано записей: ${state.entryCount}",
                        )
                    }
                }
                .onFailure { error ->
                    _download.value = DownloadState(downloading = false)
                    _editor.update {
                        it.copy(message = error.message ?: "Не удалось скачать словарь")
                    }
                }
        }
    }

    fun consumeMessage() {
        _editor.update { it.copy(message = null) }
    }

    /** Resolves a display name off the main thread for import/export labels. */
    fun labelFor(uri: Uri, onLabel: (String) -> Unit) {
        viewModelScope.launch {
            onLabel(FileAccess.displayName(getApplication(), uri))
        }
    }

    private companion object {
        const val AUTO_SAVE_DEBOUNCE_MS = 600L
    }
}