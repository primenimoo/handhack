package com.primenimoo.handhack.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.primenimoo.handhack.data.history.HistoryEntry
import com.primenimoo.handhack.data.history.HistoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(
    application: Application,
    private val repository: HistoryRepository,
) : AndroidViewModel(application) {

    val entries: StateFlow<List<HistoryEntry>> = repository.entries.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    init {
        viewModelScope.launch { repository.load() }
    }

    fun clear() {
        viewModelScope.launch { repository.clear() }
    }
}