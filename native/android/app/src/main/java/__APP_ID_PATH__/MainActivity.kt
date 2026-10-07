package __APP_ID__

import __APP_ID__.core.extraction.YouTubeUrl
import __APP_ID__.ui.DarkTubeApp
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** Video id arriving from a tapped/shared YouTube link, consumed once by the UI. */
    private val incomingVideoId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (savedInstanceState == null) handleIntent(intent)

        val container = (application as DarkTubeApplication).container
        setContent {
            val pending by incomingVideoId.collectAsStateWithLifecycle()
            DarkTubeApp(
                container = container,
                incomingVideoId = pending,
                onIncomingConsumed = { incomingVideoId.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        YouTubeUrl.parseVideoId(text)?.let { incomingVideoId.value = it }
    }
}
