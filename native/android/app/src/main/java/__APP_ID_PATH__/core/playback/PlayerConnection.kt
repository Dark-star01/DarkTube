package __APP_ID__.core.playback

import __APP_ID__.core.log.AppLog
import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The UI's handle on the playback service. Connect while the activity is visible; release when
 * it stops. Playback itself lives in [PlaybackService] and continues without a controller.
 */
class PlayerConnection(private val context: Context) {

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()
    private var future: ListenableFuture<MediaController>? = null

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener(
            {
                _controller.value = try {
                    f.get()
                } catch (e: Exception) {
                    AppLog.e("Player", "could not connect to playback service", e)
                    null
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun release() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
        _controller.value = null
    }

    fun pause() {
        _controller.value?.pause()
    }

    /** Disabling video while the UI is hidden saves battery; audio keeps playing. */
    fun setVideoEnabled(enabled: Boolean) {
        val c = _controller.value ?: return
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    /** Called when the player screen is left: stop and forget the current item. */
    fun stopAndClear() {
        _controller.value?.let {
            it.stop()
            it.clearMediaItems()
        }
    }
}

/** Shared between the activity (which owns PiP) and the player screen (which knows if it's eligible). */
class PipState {
    @Volatile
    var eligible: Boolean = false

    private val _inPip = MutableStateFlow(false)
    val inPip: StateFlow<Boolean> = _inPip.asStateFlow()

    fun setInPip(value: Boolean) {
        _inPip.value = value
    }
}
