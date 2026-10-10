package __APP_ID__.ui.downloads

import __APP_ID__.core.download.DownloadManager
import __APP_ID__.data.db.DownloadEntity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(private val manager: DownloadManager) : ViewModel() {
    /** null until the first database read, so the screen can tell "loading" from "empty". */
    val items: StateFlow<List<DownloadEntity>?> =
        manager.observeAllNullable().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun pause(id: Long) = viewModelScope.launch { manager.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { manager.resume(id) }
    fun cancel(id: Long) = viewModelScope.launch { manager.cancel(id) }
    fun retry(id: Long) = viewModelScope.launch { manager.retry(id) }
    fun remove(id: Long) = viewModelScope.launch { manager.remove(id) }
    fun delete(id: Long) = viewModelScope.launch { manager.deleteWithFiles(id) }
    fun fileExists(e: DownloadEntity) = manager.fileExists(e)
}
