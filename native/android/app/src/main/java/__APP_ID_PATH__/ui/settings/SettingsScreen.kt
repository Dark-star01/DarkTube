package __APP_ID__.ui.settings

import __APP_ID__.core.download.DownloadException
import __APP_ID__.core.download.DownloadManager
import __APP_ID__.core.download.DownloadStorage
import __APP_ID__.core.extraction.EngineInfo
import __APP_ID__.core.log.AppLog
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    engine: EngineInfo,
    downloads: DownloadManager,
    storage: DownloadStorage,
    onOpenLog: () -> Unit,
) {
    val context = LocalContext.current
    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Group("Extraction engine") {
            Line("Engine", engine.name)
            Line("Version", engine.version)
            Text(engine.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        DownloadSettings(downloads, storage)
        HorizontalDivider()
        Group("Diagnostics") {
            Text(
                "Recent extraction and error events, kept in memory only. Links are redacted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenLog) { Text("View debug log") }
        }
        HorizontalDivider()
        Group("About") {
            Line("DarkTube", appVersion)
            Line("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        }
    }
}

@Composable
fun LogScreen(onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var revision by remember { mutableIntStateOf(0) } // bump to re-read the buffer after Clear
    val entries = remember(revision) { AppLog.snapshot().asReversed() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    val header = "DarkTube | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ${Build.MODEL}"
                    clipboard.setText(AnnotatedString(AppLog.dump(header)))
                }) { Text("Copy") }
                OutlinedButton(onClick = { AppLog.clear(); revision++ }, modifier = Modifier.padding(start = 8.dp)) { Text("Clear") }
            }
        }
        if (entries.isEmpty()) {
            Text("Log is empty.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(entries) { e ->
                    Text(
                        "${e.level.name.first()}/${e.tag}: ${e.message}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (e.level == AppLog.Level.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DownloadSettings(downloads: DownloadManager, storage: DownloadStorage) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var location by remember { mutableStateOf(storage.destinationDescription()) }
    var version by remember { mutableStateOf("…") }
    var status by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { version = downloads.engineVersion() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            storage.customTreeUri = uri.toString()
            location = storage.destinationDescription()
        }
    }

    Group("Downloads") {
        Line("Save to", location)
        Text(
            "Downloads go to a folder you choose, or Downloads/DarkTube by default. No broad storage permission is used.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(null) }) { Text("Choose folder") }
            if (storage.customTreeUri != null) {
                OutlinedButton(onClick = { storage.customTreeUri = null; location = storage.destinationDescription() }) { Text("Use default") }
            }
        }
        Line("Download engine", "yt-dlp $version")
        Text(
            "yt-dlp does the downloading; FFmpeg only merges video and audio or converts subtitles. If downloads start failing, update yt-dlp.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            enabled = !updating,
            onClick = {
                updating = true
                status = "Updating…"
                scope.launch {
                    status = try {
                        downloads.updateEngine().also { version = downloads.engineVersion() }
                    } catch (e: DownloadException) {
                        e.userMessage
                    }
                    updating = false
                }
            },
        ) { Text("Update yt-dlp") }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
