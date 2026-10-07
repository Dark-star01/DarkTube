package __APP_ID__.core.playback

import __APP_ID__.core.extraction.VideoSummary
import androidx.media3.session.MediaController

/**
 * The ONE place that loads streams into the player and decides what to do about position.
 * Quality and audio switches reload the merged source at the same position and play state.
 *
 * KNOWN ISSUE (see docs/KNOWN-ISSUES.md): there is no persisted "resume from last position".
 * Position is only carried across an in-session quality/audio switch, here and nowhere else.
 */
object PlayerSession {

    /** @return true if the player was (re)loaded, false if exactly this stream was already loaded. */
    fun load(
        controller: MediaController,
        video: VideoSummary,
        plan: PlaybackPlan,
        subtitles: List<SubtitleSource>,
    ): Boolean {
        val current = controller.currentMediaItem
        val sameVideo = current?.mediaId == video.id
        // NOTE: do not compare localConfiguration.uri here: controllers never receive it (always null).
        val extras = current?.requestMetadata?.extras
        val sameStream = sameVideo &&
            extras?.getString(MediaItems.KEY_VIDEO_URL) == plan.videoUrl &&
            extras.getString(MediaItems.KEY_AUDIO_URL) == plan.audioUrl
        if (sameStream) return false

        val resumeAt = if (sameVideo && !controller.isCurrentMediaItemLive) controller.currentPosition else 0L
        val keepPlaying = if (sameVideo) controller.playWhenReady else true
        if (!sameVideo) controller.resetSubtitles()

        controller.setMediaItem(
            MediaItems.build(video.id, video.title, video.channel, video.thumbnailUrl, plan, subtitles),
            resumeAt,
        )
        controller.prepare()
        controller.playWhenReady = keepPlaying
        return true
    }
}
