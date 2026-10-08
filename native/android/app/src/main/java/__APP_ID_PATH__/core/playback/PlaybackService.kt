package __APP_ID__.core.playback

import __APP_ID__.core.log.AppLog
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the one ExoPlayer and its MediaSession so playback survives the UI going away
 * (background audio, lock screen, notification controls, Bluetooth/headset buttons).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val dataSourceFactory = YoutubeDataSourceFactory.create()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(MergingMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addAnalyticsListener(PlayerDiagnostics.analytics)
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                PlayerDiagnostics.logTextTracks(tracks)
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLog.e("Player", PlayerDiagnostics.describeError(error), error)
            }
        })
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away stops the service unless something is actively playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.let {
            it.player.release()
            it.release()
        }
        session = null
        super.onDestroy()
    }
}
