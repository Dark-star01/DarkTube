package __APP_ID__.ui.search

import __APP_ID__.core.extraction.ExtractionException
import __APP_ID__.core.extraction.SearchCursor
import __APP_ID__.core.extraction.VideoExtractor
import __APP_ID__.core.extraction.VideoSummary
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(private val extractor: VideoExtractor) : ViewModel() {

    data class UiState(
        val query: String = "",
        val searched: Boolean = false,
        val results: List<VideoSummary> = emptyList(),
        val next: SearchCursor? = null,
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val error: ExtractionException? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }

    fun submit() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        job?.cancel()
        _state.update {
            it.copy(searched = true, results = emptyList(), next = null, loading = true, loadingMore = false, error = null)
        }
        job = viewModelScope.launch {
            try {
                val page = extractor.search(q)
                _state.update { it.copy(results = page.results, next = page.next, loading = false) }
            } catch (e: ExtractionException) {
                _state.update { it.copy(loading = false, error = e) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.next ?: return
        if (s.loading || s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        job = viewModelScope.launch {
            try {
                val page = extractor.search(cursor.query, cursor)
                _state.update { cur ->
                    val known = cur.results.mapTo(HashSet()) { it.id }
                    cur.copy(
                        results = cur.results + page.results.filter { known.add(it.id) },
                        next = page.next,
                        loadingMore = false,
                        error = null,
                    )
                }
            } catch (e: ExtractionException) {
                // Keep what we have; surface the error without discarding results.
                _state.update { it.copy(loadingMore = false, error = e) }
            }
        }
    }
}
