package __APP_ID__.core.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource

/**
 * Builds a normal source for most items. When the item carries an audio URL (see [MediaItems])
 * the item's own URL (video-only) is merged with that separate audio stream, and every
 * subtitle on the item is merged in too.
 *
 * Subtitle sources are built here (not by Media3's default factory) with load errors treated as
 * "end of stream": a dead subtitle URL then just yields no captions instead of failing playback.
 */
@OptIn(UnstableApi::class)
class MergingMediaSourceFactory(
    private val delegate: DefaultMediaSourceFactory,
    private val dataSourceFactory: DataSource.Factory,
) : MediaSource.Factory by delegate {

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val audioUrl = mediaItem.requestMetadata.extras?.getString(MediaItems.KEY_AUDIO_URL)
        val subtitles = mediaItem.localConfiguration?.subtitleConfigurations.orEmpty()
        if (audioUrl == null && subtitles.isEmpty()) return delegate.createMediaSource(mediaItem)

        val bare = mediaItem.buildUpon().setSubtitleConfigurations(emptyList()).build()
        val sources = ArrayList<MediaSource>()
        sources += delegate.createMediaSource(bare)
        if (audioUrl != null) sources += delegate.createMediaSource(MediaItem.fromUri(audioUrl))
        val subtitleFactory = SingleSampleMediaSource.Factory(dataSourceFactory).setTreatLoadErrorsAsEndOfStream(true)
        subtitles.forEach { sources += subtitleFactory.createMediaSource(it, C.TIME_UNSET) }
        return if (sources.size == 1) sources[0] else MergingMediaSource(*sources.toTypedArray())
    }
}

object MediaItems {
    const val KEY_AUDIO_URL = "darktube.audioUrl"

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
        val extras = Bundle().apply { plan.audioUrl?.let { putString(KEY_AUDIO_URL, it) } }
        return MediaItem.Builder()
            .setMediaId(videoId)
            .setUri(plan.videoUrl)
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
