package __APP_ID__.core.playback

import __APP_ID__.core.extraction.Delivery
import __APP_ID__.core.extraction.StreamCatalog
import __APP_ID__.core.extraction.StreamSet

/** What to hand the player: one URL, or a video URL plus a separate audio URL to be merged. */
data class PlaybackPlan(
    val videoUrl: String,
    val audioUrl: String?,
    val qualityLabel: String,
    val isHls: Boolean = false,
    /** Track id of the separately merged audio; null when the stream carries its own audio. */
    val audioTrackId: String? = null,
)

/**
 * Decides which concrete streams to play. Pure logic (no Android), unit-tested.
 *
 * YouTube serves most resolutions as separate video-only and audio-only files, so "quality"
 * and "audio track" are chosen independently and merged at playback time. Real adaptive
 * streaming (DASH) isn't available from this engine for ordinary videos, so "Auto" means
 * "best quality up to [AUTO_MAX_HEIGHT]", not bandwidth-adaptive.
 */
object PlaybackPlanner {
    const val AUTO_MAX_HEIGHT = 1080
    const val AUTO_LABEL = "Auto"

    /**
     * @param qualityKey a [StreamCatalog.Quality.label] such as "720p"; null or unknown = Auto.
     * @param audioTrackId a track id from [StreamCatalog.AudioTrack.trackId]; null = by [preferredLanguage], else original.
     */
    fun plan(
        streams: StreamSet,
        qualityKey: String?,
        audioTrackId: String? = null,
        preferredLanguage: String? = null,
    ): PlaybackPlan? {
        val video = streams.video.filter { it.delivery == Delivery.PROGRESSIVE }
        val audio = streams.audio.filter { it.delivery == Delivery.PROGRESSIVE }
        val qualities = StreamCatalog.qualities(video)
        val tracks = StreamCatalog.audioTracks(audio)
        val track = tracks.firstOrNull { audioTrackId != null && it.trackId == audioTrackId }
            ?: StreamCatalog.pickAudioTrack(tracks, preferredLanguage)

        if (qualities.isEmpty()) {
            streams.hlsUrl?.let { return PlaybackPlan(it, null, "Live", isHls = true) }
            return track?.let { PlaybackPlan(it.best.url, null, "Audio only") }
        }

        val q = qualities.firstOrNull { it.label == qualityKey }
            ?: qualities.firstOrNull { it.height <= AUTO_MAX_HEIGHT }
            ?: qualities.last()
        val videoOnly = q.candidates.firstOrNull { !it.hasAudio }
        val muxed = q.muxed

        return when {
            videoOnly != null && track != null -> PlaybackPlan(videoOnly.url, track.best.url, q.label, audioTrackId = track.trackId)
            // No audio track to merge (or this height only exists muxed): the muxed stream carries its own audio.
            muxed != null -> PlaybackPlan(muxed.url, null, q.label)
            videoOnly != null -> PlaybackPlan(videoOnly.url, null, q.label)
            else -> null
        }
    }
}
