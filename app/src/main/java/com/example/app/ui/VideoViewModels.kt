package com.example.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.app.data.YouTubeDataRepository
import com.example.app.domain.SearchResult
import com.example.app.domain.VideoResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUiState(val query: String = "", val videos: List<VideoResult> = emptyList(), val loading: Boolean = false, val message: String? = null)

class SearchViewModel : ViewModel() {
    private val repository = YouTubeDataRepository()
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    init {
        loadSuggestedVideos()
    }

    fun loadSuggestedVideos() = onQueryChanged("popular videos")

    fun onQueryChanged(query: String) {
        _state.value = _state.value.copy(query = query)
        searchJob?.cancel()
        if (query.isBlank()) { _state.value = _state.value.copy(videos = emptyList(), loading = false, message = null); return }
        searchJob = viewModelScope.launch {
            delay(300)
            _state.value = _state.value.copy(loading = true, message = null)
            when (val result = repository.search(query)) {
                is SearchResult.Success -> _state.value = _state.value.copy(videos = result.videos, loading = false)
                SearchResult.ProviderNotConfigured -> _state.value = _state.value.copy(videos = emptyList(), loading = false, message = "Add YOUTUBE_API_KEY to local.properties to enable YouTube search.")
                is SearchResult.Failure -> _state.value = _state.value.copy(videos = emptyList(), loading = false, message = result.message)
            }
        }
    }
}

/** Deterministic timer state; UI/service code supplies the one-second ticks. */
data class SleepTimer(val remainingSeconds: Int = 0) {
    val active: Boolean get() = remainingSeconds > 0
    fun start(minutes: Int) = copy(remainingSeconds = minutes.coerceAtLeast(1) * 60)
    fun tick() = copy(remainingSeconds = (remainingSeconds - 1).coerceAtLeast(0))
    fun cancel() = copy(remainingSeconds = 0)
}
