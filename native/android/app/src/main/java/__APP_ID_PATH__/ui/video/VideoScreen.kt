package __APP_ID__.ui.video

import __APP_ID__.core.extraction.AudioTrackKind
import __APP_ID__.core.extraction.Formatters
import __APP_ID__.core.extraction.StreamCatalog
import __APP_ID__.core.extraction.StreamReport
import __APP_ID__.core.extraction.VideoInfo
import __APP_ID__.core.extraction.audioTracks
import __APP_ID__.core.extraction.qualities
import __APP_ID__.core.extraction.subtitleTracks
import __APP_ID__.ui.common.DurationBadge
import __APP_ID__.ui.common.ErrorCard
import __APP_ID__.ui.common.LoadingBox
import __APP_ID__.ui.common.SectionTitle
import __APP_ID__.ui.common.metaLine
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

@Composable
fun VideoScreen(viewModel: VideoViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        when (val s = state) {
            VideoUiState.Loading -> LoadingBox()
            is VideoUiState.Error -> ErrorCard(s.error, onRetry = viewModel::load, modifier = Modifier.padding(16.dp))
            is VideoUiState.Ready -> VideoContent(s.info, viewModel)
        }
    }
}

@Composable
private fun VideoContent(info: VideoInfo, viewModel: VideoViewModel) {
    val summary = info.details.summary
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AsyncImage(
                model = summary.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            DurationBadge(summary, Modifier.align(Alignment.BottomEnd))
        }

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(summary.title, style = MaterialTheme.typography.titleLarge)
            Text(summary.channel, style = MaterialTheme.typography.bodyMedium)
            Text(metaLine(summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        StreamInspector(info, viewModel, Modifier.padding(horizontal = 16.dp))

        if (info.details.description.isNotBlank()) {
            DescriptionBlock(info.details.description, Modifier.padding(horizontal = 16.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Shows exactly what the extraction engine returned: real qualities, audio tracks (with
 * original/dub kind) and subtitle tracks. Nothing is listed unless a stream backs it.
 */
@Composable
private fun StreamInspector(info: VideoInfo, viewModel: VideoViewModel, modifier: Modifier = Modifier) {
    val qualities = remember(info) { info.qualities() }
    val audio = remember(info) { info.audioTracks() }
    val subs = remember(info) { info.subtitleTracks() }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Stream inspector", style = MaterialTheme.typography.titleMedium)

            SectionTitle("Video quality (${qualities.size})")
            if (qualities.isEmpty()) Muted("None (audio-only or live manifest)")
            qualities.forEach { q ->
                val codecs = q.candidates.map { StreamCatalog.codecFamily(it.codec) }.distinct().joinToString(" / ")
                val audioNote = if (q.muxed != null) "muxed available" else "separate audio"
                Text("${q.label}  ·  $codecs  ·  $audioNote", style = MaterialTheme.typography.bodyMedium)
            }

            SectionTitle("Audio tracks (${audio.size})")
            if (audio.isEmpty()) Muted("None")
            audio.forEach { t ->
                val kind = when (t.kind) {
                    AudioTrackKind.UNKNOWN -> ""
                    else -> "  ·  ${t.kind.name.lowercase()}"
                }
                Text("${t.label}$kind", style = MaterialTheme.typography.bodyMedium)
                Muted("${t.best.codec ?: "?"} · ${Formatters.bitrate(t.best.bitrateKbps)} · ${t.streams.size} stream(s)")
            }

            SectionTitle("Subtitles (${subs.size})")
            if (subs.isEmpty()) Muted("None")
            subs.forEach { t ->
                Text(t.label, style = MaterialTheme.typography.bodyMedium)
            }

            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(StreamReport.build(info, viewModel.engineInfo)))
                copied = true
            }) { Text(if (copied) "Copied" else "Copy report") }
        }
    }
}

@Composable
private fun Muted(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun DescriptionBlock(description: String, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle("Description")
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
        Row { Muted(if (expanded) "Show less" else "Show more") }
    }
}
