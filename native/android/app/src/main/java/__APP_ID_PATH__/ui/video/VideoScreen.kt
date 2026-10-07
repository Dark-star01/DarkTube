package __APP_ID__.ui.video

import __APP_ID__.core.extraction.AudioTrackKind
import __APP_ID__.core.extraction.Formatters
import __APP_ID__.core.extraction.StreamCatalog
import __APP_ID__.core.extraction.StreamReport
import __APP_ID__.core.extraction.VideoInfo
import __APP_ID__.core.extraction.audioTracks
import __APP_ID__.core.extraction.qualities
import __APP_ID__.core.extraction.subtitleTracks
import __APP_ID__.core.log.AppLog
import __APP_ID__.core.playback.MediaItems
import __APP_ID__.core.playback.PipState
import __APP_ID__.core.playback.PlaybackPlanner
import __APP_ID__.core.playback.PlayerConnection
import __APP_ID__.ui.common.ErrorCard
import __APP_ID__.ui.common.LoadingBox
import __APP_ID__.ui.common.SectionTitle
import __APP_ID__.ui.common.metaLine
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView

@Composable
fun VideoScreen(
    viewModel: VideoViewModel,
    playerConnection: PlayerConnection,
    pip: PipState,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Leaving this screen ends playback; merely minimizing the app does not (it stays composed).
    DisposableEffect(Unit) {
        onDispose {
            playerConnection.stopAndClear()
            pip.eligible = false
        }
    }

    when (val s = state) {
        VideoUiState.Loading -> Column(Modifier.fillMaxSize()) { BackButton(onBack); LoadingBox() }
        is VideoUiState.Error -> Column(Modifier.fillMaxSize()) {
            BackButton(onBack)
            ErrorCard(s.error, onRetry = viewModel::load, modifier = Modifier.padding(16.dp))
        }
        is VideoUiState.Ready -> VideoContent(s.info, viewModel, playerConnection, pip, onBack)
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) =
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }

@Composable
private fun VideoContent(
    info: VideoInfo,
    viewModel: VideoViewModel,
    playerConnection: PlayerConnection,
    pip: PipState,
    onBack: () -> Unit,
) {
    val summary = info.details.summary
    val context = LocalContext.current
    val controller by playerConnection.controller.collectAsStateWithLifecycle()
    val inPip by pip.inPip.collectAsStateWithLifecycle()

    var qualityKey by rememberSaveable { mutableStateOf<String?>(null) } // null = Auto
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Start (or change quality of) playback. Re-running after a reconnect is a no-op if the same stream is loaded.
    LaunchedEffect(controller, info, qualityKey) {
        val c = controller ?: return@LaunchedEffect
        val plan = PlaybackPlanner.plan(info.streams, qualityKey)
        if (plan == null) {
            playbackError = "No playable stream was found for this video."
            return@LaunchedEffect
        }
        playerConnection.setVideoEnabled(true)
        val sameVideo = c.currentMediaItem?.mediaId == summary.id
        if (sameVideo && c.currentMediaItem?.localConfiguration?.uri?.toString() == plan.videoUrl) return@LaunchedEffect

        val resumeAt = if (sameVideo && !c.isCurrentMediaItemLive) c.currentPosition else 0L
        val keepPlaying = if (sameVideo) c.playWhenReady else true
        playbackError = null
        AppLog.i("Player", "start ${summary.id} quality=${plan.qualityLabel} merged=${plan.audioUrl != null} hls=${plan.isHls}")
        c.setMediaItem(MediaItems.build(summary.id, summary.title, summary.channel, summary.thumbnailUrl, plan), resumeAt)
        c.prepare()
        c.playWhenReady = keepPlaying
    }

    // Track errors, and whether PiP is allowed (only while actually playing).
    DisposableEffect(controller) {
        val c = controller
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = describe(error)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                pip.eligible = isPlaying
            }
        }
        c?.addListener(listener)
        onDispose { c?.removeListener(listener) }
    }

    FullscreenEffect(fullscreen && !inPip)
    BackHandler(enabled = fullscreen) { fullscreen = false }

    if (inPip || fullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PlayerSurface(controller, showControls = !inPip, onFullscreen = { fullscreen = it }, modifier = Modifier.fillMaxSize())
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        BackButton(onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PlayerSurface(controller, showControls = true, onFullscreen = { fullscreen = it }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))

            playbackError?.let { message ->
                ErrorBox(message, onRetry = viewModel::load, modifier = Modifier.padding(horizontal = 16.dp))
            }

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(summary.title, style = MaterialTheme.typography.titleLarge)
                Text(summary.channel, style = MaterialTheme.typography.bodyMedium)
                Text(metaLine(summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            QualityPicker(info, qualityKey, onSelect = { qualityKey = it }, modifier = Modifier.padding(horizontal = 16.dp))
            StreamInspector(info, viewModel, Modifier.padding(horizontal = 16.dp))

            if (info.details.description.isNotBlank()) {
                DescriptionBlock(info.details.description, Modifier.padding(horizontal = 16.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(
    controller: MediaController?,
    showControls: Boolean,
    onFullscreen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                setFullscreenButtonClickListener { requested -> onFullscreen(requested) }
            }
        },
        update = { view ->
            view.player = controller
            view.useController = showControls
        },
        onRelease = { view -> view.player = null },
    )
}

@Composable
private fun FullscreenEffect(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val activity = context.findActivity()
        if (activity != null && enabled) {
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            if (activity != null && enabled) {
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Maps player failures to plain language; the raw code goes to the debug log, not the user. */
private fun describe(error: PlaybackException): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        "YouTube refused this stream. The extraction source may need an update."
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "Playback stopped because the connection dropped."
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED ->
        "This device couldn't decode this quality. Try a lower one."
    else -> "This video could not be played."
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun QualityPicker(info: VideoInfo, selectedKey: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val qualities = remember(info) { info.qualities() }
    if (qualities.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val current = qualities.firstOrNull { it.label == selectedKey }?.label ?: PlaybackPlanner.AUTO_LABEL
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Quality", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(current) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Auto (up to ${PlaybackPlanner.AUTO_MAX_HEIGHT}p)") }, onClick = { onSelect(null); open = false })
                qualities.forEach { q ->
                    DropdownMenuItem(text = { Text(q.label) }, onClick = { onSelect(q.label); open = false })
                }
            }
        }
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
            subs.forEach { t -> Text(t.label, style = MaterialTheme.typography.bodyMedium) }

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
        Muted(if (expanded) "Show less" else "Show more")
    }
}
