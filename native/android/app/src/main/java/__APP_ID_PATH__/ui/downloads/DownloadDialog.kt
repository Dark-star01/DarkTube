package __APP_ID__.ui.downloads

import __APP_ID__.core.download.AudioChoice
import __APP_ID__.core.download.DownloadKind
import __APP_ID__.core.download.DownloadSpec
import __APP_ID__.core.download.SubtitleFormat
import __APP_ID__.core.download.SubtitleSpec
import __APP_ID__.core.extraction.AudioTrackKind
import __APP_ID__.core.extraction.StreamCatalog
import __APP_ID__.core.extraction.VideoInfo
import __APP_ID__.core.extraction.audioTracks
import __APP_ID__.core.extraction.qualities
import __APP_ID__.core.extraction.subtitleTracks
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Offers only what this video really has: the qualities and audio tracks the extractor returned,
 * and its subtitle tracks. Nothing is listed unless a stream backs it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadDialog(info: VideoInfo, onDismiss: () -> Unit, onConfirm: (DownloadSpec) -> Unit) {
    val summary = info.details.summary
    val qualities = remember(info) { info.qualities().distinctBy { it.height } }
    val audio = remember(info) { info.audioTracks() }
    val subtitles = remember(info) { info.subtitleTracks() }

    var kind by remember { mutableStateOf(if (qualities.isEmpty() && audio.isNotEmpty()) DownloadKind.AUDIO_ONLY else DownloadKind.VIDEO) }
    var height by remember { mutableStateOf<Int?>(null) } // null = Best
    var audioIndex by remember { mutableStateOf(0) }
    val chosenSubs = remember { mutableStateListOf<Int>() }
    var format by remember { mutableStateOf(SubtitleFormat.SRT) }

    fun audioChoice(): AudioChoice {
        val t = audio.getOrNull(audioIndex) ?: return AudioChoice(null)
        return when {
            t.kind == AudioTrackKind.ORIGINAL || audio.size <= 1 || t.languageTag == null -> AudioChoice(null, AudioChoice.AudioKind.ORIGINAL, t.label)
            t.kind == AudioTrackKind.DESCRIPTIVE -> AudioChoice(t.languageTag, AudioChoice.AudioKind.DESCRIPTIVE, t.label)
            else -> AudioChoice(t.languageTag, AudioChoice.AudioKind.DUBBED, t.label)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Section("Type")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (qualities.isNotEmpty()) Chip("Video", kind == DownloadKind.VIDEO) { kind = DownloadKind.VIDEO }
                    if (audio.isNotEmpty()) Chip("Audio only", kind == DownloadKind.AUDIO_ONLY) { kind = DownloadKind.AUDIO_ONLY }
                    if (subtitles.isNotEmpty()) Chip("Subtitles", kind == DownloadKind.SUBTITLES_ONLY) { kind = DownloadKind.SUBTITLES_ONLY }
                }

                if (kind == DownloadKind.VIDEO && qualities.isNotEmpty()) {
                    Section("Quality")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Best", height == null) { height = null }
                        qualities.forEach { q -> Chip("${q.height}p", height == q.height) { height = q.height } }
                    }
                }

                if (kind != DownloadKind.SUBTITLES_ONLY && audio.size > 1) {
                    Section("Audio")
                    audio.forEachIndexed { i, t ->
                        Row(Modifier.fillMaxWidth().clickable { audioIndex = i }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = audioIndex == i, onClick = { audioIndex = i })
                            Text(t.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                if (subtitles.isNotEmpty()) {
                    Section(if (kind == DownloadKind.SUBTITLES_ONLY) "Subtitles" else "Subtitles (optional)")
                    subtitles.forEachIndexed { i, t ->
                        val on = i in chosenSubs
                        Row(
                            Modifier.fillMaxWidth().clickable { if (on) chosenSubs.remove(i) else chosenSubs.add(i) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = { if (on) chosenSubs.remove(i) else chosenSubs.add(i) })
                            Text(t.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (chosenSubs.isNotEmpty()) {
                        Section("Subtitle format")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SubtitleFormat.entries.forEach { f -> Chip(f.name, format == f) { format = f } }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val ready = kind != DownloadKind.SUBTITLES_ONLY || chosenSubs.isNotEmpty()
            TextButton(
                enabled = ready,
                onClick = {
                    onConfirm(
                        DownloadSpec(
                            videoId = summary.id,
                            title = summary.title,
                            thumbnailUrl = summary.thumbnailUrl,
                            kind = kind,
                            height = if (kind == DownloadKind.VIDEO) height else null,
                            audio = if (kind == DownloadKind.SUBTITLES_ONLY) AudioChoice(null) else audioChoice(),
                            subtitles = chosenSubs.sorted().map { subtitles[it] }.map { SubtitleSpec(it.languageTag, it.autoGenerated) },
                            subtitleFormat = format,
                        ),
                    )
                },
            ) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Section(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) =
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
