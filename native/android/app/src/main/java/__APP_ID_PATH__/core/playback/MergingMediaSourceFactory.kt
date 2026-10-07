package __APP_ID__.core.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource

/**
 * Builds a normal source for most items, but when the item carries an audio URL
 * (see [MediaItems]) merges the item's own URL (video-only) with that separate audio stream.
 * Everything else, including HLS for live, is delegated unchanged.
 */
@OptIn(UnstableApi::class)
class MergingMediaSourceFactory(
    private val delegate: DefaultMediaSourceFactory,
) : MediaSource.Factory by delegate {

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val audioUrl = mediaItem.requestMetadata.extras?.getString(MediaItems.KEY_AUDIO_URL)
            ?: return delegate.createMediaSource(mediaItem)
        return MergingMediaSource(
            delegate.createMediaSource(mediaItem),
            delegate.createMediaSource(MediaItem.fromUri(audioUrl)),
        )
    }
}

object MediaItems {
    const val KEY_AUDIO_URL = "darktube.audioUrl"

    fun build(videoId: String, title: String, channel: String, thumbnailUrl: String?, plan: PlaybackPlan): MediaItem {
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(channel)
            .setArtworkUri(thumbnailUrl?.let(android.net.Uri::parse))
            .build()
        val extras = Bundle().apply { plan.audioUrl?.let { putString(KEY_AUDIO_URL, it) } }
        return MediaItem.Builder()
            .setMediaId(videoId)
            .setUri(plan.videoUrl)
            .setMediaMetadata(metadata)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(extras).build())
            .build()
    }
}
