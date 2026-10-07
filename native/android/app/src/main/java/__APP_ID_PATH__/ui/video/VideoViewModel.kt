package __APP_ID__.ui.video

import __APP_ID__.core.extraction.ExtractionException
import __APP_ID__.core.extraction.VideoExtractor
import __APP_ID__.core.extraction.VideoInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface VideoUiState {
    data object Loading : VideoUiState
    data class Error(val error: ExtractionException) : VideoUiState
    data class Ready(val info: VideoInfo) : VideoUiState
}

class VideoViewModel(
    private val videoId: String,
    private val extractor: VideoExtractor,
) : ViewModel() {

    private val _state = MutableStateFlow<VideoUiState>(VideoUiState.Loading)
    val state: StateFlow<VideoUiState> = _state.asStateFlow()
    private var job: Job? = null

    val engineInfo get() = extractor.engine

    init {
        load()
    }

    fun load() {
        job?.cancel()
        _state.value = VideoUiState.Loading
        job = viewModelScope.launch {
            _state.value = try {
                VideoUiState.Ready(extractor.getVideo(videoId))
            } catch (e: ExtractionException) {
                VideoUiState.Error(e)
            }
        }
    }
}
