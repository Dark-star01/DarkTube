package __APP_ID__.ui.video

import __APP_ID__.core.download.DownloadManager
import __APP_ID__.core.extraction.AudioTrackKind
import __APP_ID__.ui.downloads.DownloadDialog
import __APP_ID__.core.extraction.Formatters
import __APP_ID__.core.extraction.StreamCatalog
import __APP_ID__.core.extraction.StreamReport
import __APP_ID__.core.extraction.VideoInfo
import __APP_ID__.core.extraction.audioTracks
import __APP_ID__.core.extraction.qualities
import __APP_ID__.core.extraction.subtitleTracks
import __APP_ID__.core.log.AppLog
import __APP_ID__.core.playback.PipState
import __APP_ID__.core.playback.PlaybackPlan
import __APP_ID__.core.playback.PlaybackPlanner
import __APP_ID__.core.playback.PlayerConnection
import __APP_ID__.core.playback.PlayerSession
import __APP_ID__.core.playback.SubtitleProbe
import __APP_ID__.core.playback.UrlDescriber
import __APP_ID__.core.playback.SubtitlePlanner
import __APP_ID__.core.playback.selectSubtitle
import __APP_ID__.core.playback.subtitleActive
import __APP_ID__.core.playback.selectedSubtitleId
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView

@Composable
fun VideoScreen(
    viewModel: VideoViewModel,
    playerConnection: PlayerConnection,
    pip: PipState,
    downloads: DownloadManager,
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
        is VideoUiState.Ready -> VideoContent(s.info, viewModel, playerConnection, pip, downloads, onBack)
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
    downloads: DownloadManager,
    onBack: () -> Unit,
) {
    val summary = info.details.summary
    var showDownload by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val controller by playerConnection.controller.collectAsStateWithLifecycle()
    val inPip by pip.inPip.collectAsStateWithLifecycle()

    var qualityKey by rememberSaveable { mutableStateOf<String?>(null) }   // null = Auto
    var audioTrackId by rememberSaveable { mutableStateOf<String?>(null) } // null = default (original)
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var playbackDetails by remember { mutableStateOf<String?>(null) }
    var subtitleNote by remember { mutableStateOf<String?>(null) }
    var subtitleRecoveryTried by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<PlaybackPlan?>(null) }
    var selectedSubtitleId by remember { mutableStateOf<String?>(null) }
    // Last audio selection that actually played; restored if a newly chosen track fails to load.
    var lastGoodAudioId by rememberSaveable { mutableStateOf<String?>(null) }

    val audioTracks = remember(info) { info.audioTracks() }
    val subtitleSources = remember(info) { SubtitlePlanner.sources(info.subtitleTracks()) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Start playback, or reload it when quality / audio track changes (same position, same play state).
    LaunchedEffect(controller, info, qualityKey, audioTrackId) {
        val c = controller ?: return@LaunchedEffect
        val next = PlaybackPlanner.plan(info.streams, qualityKey, audioTrackId)
        plan = next
        if (next == null) {
            playbackError = "No playable stream was found for this video."
            return@LaunchedEffect
        }
        playerConnection.setVideoEnabled(true)
        playbackError = null
        playbackDetails = null
        if (PlayerSession.load(c, summary, next, subtitleSources)) {
            AppLog.i("Player", "load ${summary.id} quality=${next.qualityLabel} audio=${next.audioTrackId ?: "built-in"} subs=${subtitleSources.size}")
            AppLog.i("Player", "  video: ${UrlDescriber.describe(next.videoUrl)}")
            next.audioUrl?.let { AppLog.i("Player", "  audio: ${UrlDescriber.describe(it)}") }
        }
    }

    // Reflect the player's real state in the UI: errors, PiP eligibility, selected subtitle.
    DisposableEffect(controller) {
        val c = controller
        selectedSubtitleId = c?.selectedSubtitleId()
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // A subtitle problem must never end the video: turn subtitles off and resume, once.
                if (c != null && c.subtitleActive() && !subtitleRecoveryTried) {
                    subtitleRecoveryTried = true
                    c.selectSubtitle(null)
                    c.prepare()
                    subtitleNote = "This subtitle track could not be played, so subtitles were turned off."
                    playbackDetails = "${error.errorCodeName} (${error.errorCode})"
                    return
                }
                playbackDetails = "${error.errorCodeName} (${error.errorCode})"
                if (audioTrackId != lastGoodAudioId) {
                    audioTrackId = lastGoodAudioId // revert; this triggers a reload of the last working track
                    playbackError = "This audio track could not be loaded."
                } else {
                    playbackError = describe(error)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                pip.eligible = isPlaying
                if (isPlaying) lastGoodAudioId = audioTrackId
            }

            override fun onTracksChanged(tracks: Tracks) {
                selectedSubtitleId = c?.selectedSubtitleId()
            }

            override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) {
                selectedSubtitleId = c?.selectedSubtitleId()
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
                ErrorBox(message, playbackDetails, onRetry = viewModel::load, modifier = Modifier.padding(horizontal = 16.dp))
            }

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(summary.title, style = MaterialTheme.typography.titleLarge)
                Text(summary.channel, style = MaterialTheme.typography.bodyMedium)
                Text(metaLine(summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!summary.isLive) {
                    OutlinedButton(onClick = { showDownload = true }) { Text("Download") }
                }
            }

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                QualityRow(info, qualityKey, onSelect = { qualityKey = it })
                AudioRow(audioTracks, audioTrackId, builtInOnly = plan != null && plan?.audioUrl == null, onSelect = { audioTrackId = it })
                SubtitleRow(
                    options = subtitleSources.map { it.id to it.label },
                    selectedId = selectedSubtitleId,
                    note = subtitleNote,
                    onSelect = { id ->
                        val c = controller
                        if (c != null) {
                            if (id == null) {
                                subtitleNote = null
                                c.selectSubtitle(null)
                            } else {
                                val source = subtitleSources.first { it.id == id }
                                subtitleRecoveryTried = false
                                scope.launch {
                                    // Check what YouTube really sends before selecting, so a bad track
                                    // gives a precise reason instead of silently showing nothing.
                                    val probe = SubtitleProbe.probe(source)
                                    if (!probe.usable) {
                                        subtitleNote = probe.userMessage
                                    } else if (!c.selectSubtitle(id)) {
                                        subtitleNote = "Subtitle tracks are still loading. Try again in a moment."
                                    } else {
                                        subtitleNote = null
                                    }
                                }
                            }
                        }
                    },
                )
            }

            StreamInspector(info, viewModel, Modifier.padding(horizontal = 16.dp))

            if (showDownload) {
                DownloadDialog(
                    info = info,
                    onDismiss = { showDownload = false },
                    onConfirm = { spec ->
                        showDownload = false
                        scope.launch { downloads.enqueue(spec) }
                        android.widget.Toast.makeText(context, "Added to Downloads", android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
            }

            if (info.details.description.isNotBlank()) {
                DescriptionBlock(info.details.description, Modifier.padding(horizontal = 16.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * True while the player is genuinely not ready to show frames: Media3 reports STATE_BUFFERING (initial
 * load, source replacement after a quality/audio switch, seeks, rebuffering) or it is prepared-but-idle
 * with play requested. It follows the player's real state, so it disappears the moment playback is READY
 * or fails; there is no timer.
 */
@Composable
internal fun rememberPlayerBusy(controller: MediaController?): Boolean {
    var busy by remember(controller) { mutableStateOf(controller?.isBusy() == true) }
    DisposableEffect(controller) {
        val c = controller
        busy = c?.isBusy() == true
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { busy = c?.isBusy() == true }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { busy = c?.isBusy() == true }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { busy = c?.isBusy() == true }
            override fun onPlayerError(error: PlaybackException) { busy = false }
            override fun onIsLoadingChanged(isLoading: Boolean) { busy = c?.isBusy() == true }
        }
        c?.addListener(listener)
        onDispose { c?.removeListener(listener) }
    }
    return busy
}

private fun Player.isBusy(): Boolean =
    playbackState == Player.STATE_BUFFERING ||
        (playbackState == Player.STATE_IDLE && mediaItemCount > 0 && playerError == null && playWhenReady)

@OptIn(UnstableApi::class)
@Composable
internal fun PlayerSurface(
    controller: MediaController?,
    showControls: Boolean,
    onFullscreen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = rememberPlayerBusy(controller)
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setShowSubtitleButton(true)
                    setFullscreenButtonClickListener { requested -> onFullscreen(requested) }
                }
            },
            update = { view ->
                view.player = controller
                view.useController = showControls
            },
            onRelease = { view -> view.player = null },
        )
        // Small centered spinner on top of the video: never blocks touches, so controls stay usable.
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(40.dp),
                color = Color.White,
                strokeWidth = 3.dp,
            )
        }
    }
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
private fun ErrorBox(message: String, details: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    var showDetails by remember { mutableStateOf(false) }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, style = MaterialTheme.typography.bodyLarge)
            if (showDetails && details != null) Muted(details)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRetry) { Text("Retry") }
                if (details != null) OutlinedButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide details" else "Details") }
            }
        }
    }
}

/** A labelled row with a dropdown. [options] are (key, label); a null key means the "default/Off" entry. */
@Composable
internal fun DropdownRow(
    title: String,
    currentLabel: String,
    options: List<Pair<String?, String>>,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(currentLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (key, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(key); open = false })
                }
            }
        }
    }
}

@Composable
private fun StaticRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QualityRow(info: VideoInfo, selectedKey: String?, onSelect: (String?) -> Unit) {
    val qualities = remember(info) { info.qualities() }
    if (qualities.isEmpty()) return
    val current = qualities.firstOrNull { it.label == selectedKey }?.label ?: PlaybackPlanner.AUTO_LABEL
    DropdownRow(
        "Quality",
        current,
        listOf<Pair<String?, String>>(null to "Auto (up to ${PlaybackPlanner.AUTO_MAX_HEIGHT}p)") + qualities.map { it.label to it.label },
        onSelect,
    )
}

/** No selector unless there is a real choice: none -> hidden, one -> plain text, several -> dropdown. */
@Composable
private fun AudioRow(
    tracks: List<StreamCatalog.AudioTrack>,
    selectedId: String?,
    builtInOnly: Boolean,
    onSelect: (String?) -> Unit,
) {
    if (tracks.isEmpty()) return
    val current = tracks.firstOrNull { it.trackId != null && it.trackId == selectedId }
        ?: StreamCatalog.pickAudioTrack(tracks, null)!!
    if (tracks.size == 1) {
        StaticRow("Audio", current.label)
        return
    }
    DropdownRow("Audio", current.label, tracks.map { it.trackId to it.label }, onSelect)
    if (builtInOnly) {
        Muted("This quality has built-in audio only. Pick another quality to switch audio tracks.")
    }
}

@Composable
private fun SubtitleRow(
    options: List<Pair<String, String>>,
    selectedId: String?,
    note: String?,
    onSelect: (String?) -> Unit,
) {
    if (options.isEmpty()) {
        StaticRow("Subtitles", "None available")
        return
    }
    val current = options.firstOrNull { it.first == selectedId }?.second ?: "Off"
    DropdownRow("Subtitles", current, listOf<Pair<String?, String>>(null to "Off") + options, onSelect)
    if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
internal fun Muted(text: String) =
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
