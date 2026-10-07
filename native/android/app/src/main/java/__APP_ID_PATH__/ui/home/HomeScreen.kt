package __APP_ID__.ui.home

import __APP_ID__.core.extraction.YouTubeUrl
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(onOpenVideo: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    fun open() {
        val id = YouTubeUrl.parseVideoId(input)
        if (id == null) invalid = true else onOpenVideo(id)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("DarkTube", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Private, ad-free, no account. Paste a YouTube link or video ID to inspect it, or use Search.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = input,
            onValueChange = { input = it; invalid = false },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("YouTube link or video ID") },
            singleLine = true,
            isError = invalid,
            supportingText = if (invalid) {
                { Text("That doesn't look like a YouTube link or video ID.") }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { open() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::open, enabled = input.isNotBlank()) { Text("Open") }
            OutlinedButton(onClick = {
                clipboard.getText()?.text?.let { input = it; invalid = false }
            }) { Text("Paste") }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Build status", style = MaterialTheme.typography.titleSmall)
                Text("Working: search, video details, stream inspector (qualities, audio tracks, dubs, subtitles), debug log.", style = MaterialTheme.typography.bodySmall)
                Text("Not built yet: playback, downloads, library.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
