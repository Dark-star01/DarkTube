package __APP_ID__.core.playback

import __APP_ID__.core.log.AppLog
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource

/**
 * Adds the separate audio stream (see [MediaItems]) around whatever [DefaultMediaSourceFactory]
 * builds for the item.
 *
 * Subtitles are deliberately NOT handled here. The item's SubtitleConfigurations go to
 * DefaultMediaSourceFactory untouched, which (Media3 1.5.1, parseSubtitlesDuringExtraction = true)
 * loads each one with ProgressiveMediaSource + SubtitleExtractor and emits parsed cues
 * (application/x-media3-cues). An earlier version built SingleSampleMediaSources itself, which feeds
 * raw application/ttml+xml to a TextRenderer that has legacy decoding disabled and crashed playback
 * with "Legacy decoding is disabled, can't handle application/ttml+xml samples". Using the factory
 * also gives Media3's own protection against a failing subtitle download breaking prepare.
 */
@OptIn(UnstableApi::class)
class MergingMediaSourceFactory(
    private val delegate: DefaultMediaSourceFactory,
) : MediaSource.Factory by delegate {

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val audioUrl = mediaItem.requestMetadata.extras?.getString(MediaItems.KEY_AUDIO_URL)
        val subtitleCount = mediaItem.localConfiguration?.subtitleConfigurations?.size ?: 0
        AppLog.d(
            "Player",
            "source: merged-audio=${audioUrl != null} subtitles=$subtitleCount " +
                "parser=DefaultMediaSourceFactory(SubtitleExtractor -> application/x-media3-cues)",
        )
        val primary = delegate.createMediaSource(mediaItem) // video (+ subtitles via the modern path)
        return if (audioUrl == null) primary
        else MergingMediaSource(primary, delegate.createMediaSource(MediaItem.fromUri(audioUrl)))
    }
}

object MediaItems {
    const val KEY_AUDIO_URL = "darktube.audioUrl"

    /** Stored because MediaItem.localConfiguration (the uri) is NOT sent from the service to controllers. */
    const val KEY_VIDEO_URL = "darktube.videoUrl"

    fun build(
        videoId: String,
        title: String,
        channel: String,
        thumbnailUrl: String?,
        plan: PlaybackPlan,
        subtitles: List<SubtitleSource> = emptyList(),
    ): MediaItem {
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(channel)
            .setArtworkUri(thumbnailUrl?.let(android.net.Uri::parse))
            .build()
        val extras = Bundle().apply {
            putString(KEY_VIDEO_URL, plan.videoUrl)
            plan.audioUrl?.let { putString(KEY_AUDIO_URL, it) }
        }
        return MediaItem.Builder()
            .setMediaId(videoId)
            .setUri(plan.videoUrl)
            .setSubtitleConfigurations(subtitles.map { it.toConfiguration() })
            .setMediaMetadata(metadata)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(extras).build())
            .build()
    }

    /** A finished download: one local file (content:// or file://), optional local subtitle files. */
    fun buildLocal(
        mediaId: String,
        title: String,
        thumbnailUrl: String?,
        uri: String,
        subtitles: List<SubtitleSource> = emptyList(),
    ): MediaItem {
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(title)
            .setArtworkUri(thumbnailUrl?.let(android.net.Uri::parse))
            .build()
        val extras = Bundle().apply { putString(KEY_VIDEO_URL, uri) }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setSubtitleConfigurations(subtitles.map { it.toConfiguration() })
            .setMediaMetadata(metadata)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(extras).build())
            .build()
    }

    private fun SubtitleSource.toConfiguration() =
        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(url))
            .setMimeType(mimeType)
            .setLanguage(languageTag)
            .setLabel(label)
            .setId(id)
            .setSelectionFlags(0) // never auto-selected: subtitles stay Off until the user picks one
            .build()
}
