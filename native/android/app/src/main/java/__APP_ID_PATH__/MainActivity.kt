package __APP_ID__

import __APP_ID__.core.extraction.YouTubeUrl
import __APP_ID__.ui.DarkTubeApp
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
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

        setContent {
            val pending by incomingVideoId.collectAsStateWithLifecycle()
            DarkTubeApp(
                container = container,
                incomingVideoId = pending,
                onIncomingConsumed = { incomingVideoId.value = null },
            )
        }
    }

    private val container get() = (application as DarkTubeApplication).container

    override fun onStart() {
        super.onStart()
        container.playerConnection.connect()
    }

    override fun onStop() {
        // Hidden UI: stop decoding video (audio continues in the service), then let go of the controller.
        container.playerConnection.setVideoEnabled(false)
        container.playerConnection.release()
        super.onStop()
    }

    /** Home/recents while a video plays: shrink into Picture-in-Picture instead of just backgrounding. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (container.pip.eligible && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        container.pip.setInPip(isInPictureInPictureMode)
        // Leaving PiP while the activity is not even started means the user closed the PiP window.
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            container.playerConnection.pause()
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
