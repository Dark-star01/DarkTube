package __APP_ID__.core.extraction

/**
 * The only way the rest of the app talks to YouTube.
 *
 * Deliberate deviation from the spec's sketch: NewPipe (and yt-dlp) return metadata, streams,
 * audio tracks and subtitles from ONE network fetch, so separate getStreamInfo/getSubtitles/
 * getAudioTracks calls would fetch the same page repeatedly. [getVideo] returns everything;
 * [audioTracks] and [subtitleTracks] (in StreamCatalog) are cheap views over the result.
 */
interface VideoExtractor {
    val engine: EngineInfo

    /** Videos only. Pass the cursor from a previous page to continue. */
    @Throws(ExtractionException::class)
    suspend fun search(query: String, cursor: SearchCursor? = null): SearchPage

    @Throws(ExtractionException::class)
    suspend fun getVideo(videoId: String): VideoInfo
}
