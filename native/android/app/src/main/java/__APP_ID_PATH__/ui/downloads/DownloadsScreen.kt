package __APP_ID__.ui.downloads

import __APP_ID__.core.download.DownloadAction
import __APP_ID__.core.download.DownloadActions
import __APP_ID__.core.download.DownloadFormat
import __APP_ID__.core.download.DownloadManager
import __APP_ID__.core.download.DownloadState
import __APP_ID__.core.download.OutputFiles
import __APP_ID__.core.download.SubtitleSpec
import __APP_ID__.data.db.DownloadEntity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel, onPlay: (Long) -> Unit) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Text("Downloads", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 8.dp))
        val list = items
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Text(
                "Nothing downloaded yet. Open a video and tap Download.",
                Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.id }) { e -> DownloadRow(e, viewModel, onPlay) }
            }
        }
    }
}

@Composable
private fun DownloadRow(e: DownloadEntity, vm: DownloadsViewModel, onPlay: (Long) -> Unit) {
    val context = LocalContext.current
    val state = DownloadState.valueOf(e.state)
    var confirmDelete by remember { mutableStateOf(false) }
    val exists by produceState(true, e.id, e.state) {
        value = if (state == DownloadState.COMPLETED) withContext(Dispatchers.IO) { vm.fileExists(e) } else true
    }
    val ext = e.destinationName?.substringAfterLast('.', "").orEmpty()
    val playable = exists && OutputFiles.isPlayableMedia(ext)

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(
                    model = e.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(112.dp, 63.dp).clip(RoundedCornerShape(8.dp)),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(summaryLine(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (state == DownloadState.DOWNLOADING || state == DownloadState.PAUSED || state == DownloadState.QUEUED) {
                if (state == DownloadState.QUEUED || (state == DownloadState.DOWNLOADING && e.progress <= 0f)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { e.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
            Text(statusLine(e, state, exists), style = MaterialTheme.typography.bodySmall,
                color = if (state == DownloadState.FAILED || (state == DownloadState.COMPLETED && !exists)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            e.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (a in DownloadActions.forState(state, playable)) {
                    if (state == DownloadState.COMPLETED && !exists && a != DownloadAction.DELETE) continue
                    TextButton(onClick = {
                        when (a) {
                            DownloadAction.PAUSE -> vm.pause(e.id)
                            DownloadAction.RESUME -> vm.resume(e.id)
                            DownloadAction.CANCEL -> vm.cancel(e.id)
                            DownloadAction.RETRY -> vm.retry(e.id)
                            DownloadAction.REMOVE -> vm.remove(e.id)
                            DownloadAction.PLAY -> onPlay(e.id)
                            DownloadAction.OPEN -> openExternally(context, e)
                            DownloadAction.DELETE -> confirmDelete = true
                        }
                    }) { Text(label(a, state, exists)) }
                }
                if (state == DownloadState.COMPLETED && !exists) {
                    TextButton(onClick = { vm.remove(e.id) }) { Text("Remove") }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete download?") },
            text = { Text("The downloaded file${if (e.subtitleFiles != null) " and its subtitles" else ""} will be deleted from your device.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(e.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

private fun label(a: DownloadAction, state: DownloadState, exists: Boolean) = when (a) {
    DownloadAction.PAUSE -> "Pause"
    DownloadAction.RESUME -> "Resume"
    DownloadAction.CANCEL -> "Cancel"
    DownloadAction.RETRY -> "Retry"
    DownloadAction.REMOVE -> "Remove"
    DownloadAction.PLAY -> "Play"
    DownloadAction.OPEN -> "Open"
    DownloadAction.DELETE -> "Delete"
}

private fun summaryLine(e: DownloadEntity): String {
    val parts = mutableListOf(e.resolvedHeight?.let { "${it}p" } ?: e.qualityLabel)
    if (e.qualityLabel != "Subtitles") parts += e.audioLabel
    val subs = SubtitleSpec.decode(e.subtitleSpecs)
    if (subs.isNotEmpty()) parts += "Subs: " + subs.joinToString(", ") { it.language }
    return parts.joinToString(" · ")
}

private fun statusLine(e: DownloadEntity, state: DownloadState, exists: Boolean): String = when (state) {
    DownloadState.QUEUED -> "Queued"
    DownloadState.DOWNLOADING -> if (e.progress <= 0f && e.speedBps <= 0L) {
        // Nothing has been transferred yet: say what is really happening instead of "0%".
        "Preparing download…" + if (e.attempts > 0) " · retry ${e.attempts} of 3" else ""
    } else {
        val pct = "${(e.progress * 100).toInt()}%"
        val size = if (e.totalBytes > 0 && e.downloadedBytes >= 0) " · ${DownloadFormat.bytes(e.downloadedBytes)} of ${DownloadFormat.bytes(e.totalBytes)}" else ""
        "Downloading $pct$size · ${DownloadFormat.speed(e.speedBps)} · ETA ${DownloadFormat.eta(e.etaSeconds)}"
    }
    DownloadState.PAUSED -> "Paused at ${(e.progress * 100).toInt()}%"
    DownloadState.COMPLETED ->
        if (!exists) "File no longer exists"
        else "Completed · ${if (e.totalBytes > 0) DownloadFormat.bytes(e.totalBytes) + " · " else ""}${e.destinationName ?: ""}"
    DownloadState.FAILED -> "Failed: ${e.errorMessage ?: "unknown error"}"
    DownloadState.CANCELLED -> "Cancelled"
}

private fun openExternally(context: android.content.Context, e: DownloadEntity) {
    val uri = e.destinationUri?.let(Uri::parse) ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, e.mimeType ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (ex: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file.", Toast.LENGTH_SHORT).show()
    } catch (ex: SecurityException) {
        Toast.makeText(context, "This file can't be opened from here.", Toast.LENGTH_SHORT).show()
    }
}
