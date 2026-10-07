package __APP_ID__.core.extraction.newpipe

import __APP_ID__.core.extraction.AudioStreamOption
import __APP_ID__.core.extraction.AudioTrackKind
import __APP_ID__.core.extraction.Delivery
import __APP_ID__.core.extraction.EngineInfo
import __APP_ID__.core.extraction.ExtractionException
import __APP_ID__.core.extraction.ExtractionException.Kind
import __APP_ID__.core.extraction.SearchCursor
import __APP_ID__.core.extraction.SearchPage
import __APP_ID__.core.extraction.StreamSet
import __APP_ID__.core.extraction.SubtitleOption
import __APP_ID__.core.extraction.VideoDetails
import __APP_ID__.core.extraction.VideoExtractor
import __APP_ID__.core.extraction.VideoInfo
import __APP_ID__.core.extraction.VideoStreamOption
import __APP_ID__.core.extraction.VideoSummary
import __APP_ID__.core.extraction.YouTubeUrl
import __APP_ID__.core.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import org.schabi.newpipe.extractor.exceptions.ExtractionException as NpExtractionException

/**
 * [VideoExtractor] backed by NewPipeExtractor (pure JVM; does its own signature/n-parameter
 * solving, so no external JavaScript runtime is needed). This is the ONLY file family that may
 * import org.schabi.*; everything it returns is an engine-neutral model.
 */
class NewPipeVideoExtractor(downloader: Downloader) : VideoExtractor {

    init {
        NewPipe.init(downloader, Localization("en", "US"), ContentCountry("US"))
    }

    override val engine = EngineInfo(
        name = "NewPipeExtractor",
        version = ENGINE_VERSION,
        notes = "Pure-JVM YouTube extractor (GPL-3.0). Update by changing the version in app/build.gradle.",
    )

    private val service get() = ServiceList.YouTube

    override suspend fun search(query: String, cursor: SearchCursor?): SearchPage =
        guarded("search") {
            withContext(Dispatchers.IO) {
                val handler = service.searchQHFactory
                    .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
                if (cursor == null) {
                    val info = SearchInfo.getInfo(service, handler)
                    SearchPage(
                        results = info.relatedItems.toSummaries(),
                        next = if (info.hasNextPage()) SearchCursor(query, info.nextPage) else null,
                    )
                } else {
                    val page = SearchInfo.getMoreItems(service, handler, cursor.raw as Page)
                    SearchPage(
                        results = page.items.toSummaries(),
                        next = if (page.hasNextPage()) SearchCursor(query, page.nextPage) else null,
                    )
                }
            }
        }

    override suspend fun getVideo(videoId: String): VideoInfo =
        guarded("getVideo($videoId)") {
            withContext(Dispatchers.IO) {
                val info = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
                val live = info.streamType.isLive()

                val video = (info.videoStreams.orEmpty() + info.videoOnlyStreams.orEmpty())
                    .mapNotNull { it.toOption() }
                val audio = info.audioStreams.orEmpty().mapNotNull { it.toOption() }
                val subtitles = info.subtitles.orEmpty().mapNotNull { it.toOption() }

                val hls = info.hlsUrl?.takeIf { it.isNotBlank() }
                if (video.isEmpty() && audio.isEmpty() && hls == null) {
                    throw ExtractionException(Kind.NO_STREAMS, "Engine returned no usable streams for $videoId")
                }

                AppLog.i(TAG, "getVideo($videoId): video=${video.size} audio=${audio.size} subs=${subtitles.size} live=$live")
                VideoInfo(
                    details = VideoDetails(
                        summary = VideoSummary(
                            id = videoId,
                            title = info.name.orEmpty(),
                            channel = info.uploaderName.orEmpty(),
                            channelUrl = info.uploaderUrl,
                            durationSeconds = info.duration,
                            thumbnailUrl = info.thumbnails.bestThumbnail(),
                            viewCount = info.viewCount,
                            uploadedText = info.textualUploadDate,
                            isLive = live,
                        ),
                        description = info.description?.content().orEmpty(),
                        ageLimit = info.ageLimit,
                    ),
                    streams = StreamSet(
                        video = video,
                        audio = audio,
                        subtitles = subtitles,
                        hlsUrl = hls,
                        dashManifestUrl = info.dashMpdUrl?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }

    // ───────────── mapping ─────────────

    private fun List<InfoItem>.toSummaries(): List<VideoSummary> =
        filterIsInstance<StreamInfoItem>().mapNotNull { item ->
            val id = YouTubeUrl.parseVideoId(item.url.orEmpty()) ?: return@mapNotNull null
            VideoSummary(
                id = id,
                title = item.name.orEmpty(),
                channel = item.uploaderName.orEmpty(),
                channelUrl = item.uploaderUrl,
                durationSeconds = item.duration,
                thumbnailUrl = item.thumbnails.bestThumbnail(),
                viewCount = item.viewCount,
                uploadedText = item.textualUploadDate,
                isLive = item.streamType.isLive(),
            )
        }

    private fun StreamType?.isLive() = this == StreamType.LIVE_STREAM || this == StreamType.AUDIO_LIVE_STREAM

    private fun List<Image>?.bestThumbnail(): String? =
        this?.maxByOrNull { it.height }?.url ?: this?.firstOrNull()?.url

    private fun DeliveryMethod?.toDelivery(): Delivery = when (this) {
        DeliveryMethod.PROGRESSIVE_HTTP -> Delivery.PROGRESSIVE
        DeliveryMethod.DASH -> Delivery.DASH
        DeliveryMethod.HLS -> Delivery.HLS
        else -> Delivery.OTHER
    }

    /** Only URL-addressable streams are usable; inline manifest content is skipped (and logged). */
    private fun VideoStream.toOption(): VideoStreamOption? {
        if (!isUrl) {
            AppLog.d(TAG, "skipping non-URL video stream itag=$itag")
            return null
        }
        return VideoStreamOption(
            itag = itag,
            width = width,
            height = height,
            fps = fps,
            codec = codec,
            container = format?.suffix,
            bitrateKbps = if (bitrate > 0) bitrate / 1000 else 0, // VideoStream.bitrate is bits/s
            hasAudio = !isVideoOnly,
            delivery = deliveryMethod.toDelivery(),
            url = content,
        )
    }

    private fun AudioStream.toOption(): AudioStreamOption? {
        if (!isUrl) {
            AppLog.d(TAG, "skipping non-URL audio stream itag=$itag")
            return null
        }
        return AudioStreamOption(
            itag = itag,
            trackId = audioTrackId,
            languageTag = audioLocale?.toLanguageTag(),
            trackName = audioTrackName,
            kind = when (audioTrackType?.name) {
                "ORIGINAL" -> AudioTrackKind.ORIGINAL
                "DUBBED" -> AudioTrackKind.DUBBED
                "DESCRIPTIVE" -> AudioTrackKind.DESCRIPTIVE
                "SECONDARY" -> AudioTrackKind.SECONDARY
                else -> AudioTrackKind.UNKNOWN
            },
            codec = codec,
            container = format?.suffix,
            // bitrate is bits/s (precise); averageBitrate is kbps from the itag table (fallback)
            bitrateKbps = if (bitrate > 0) bitrate / 1000 else averageBitrate.coerceAtLeast(0),
            delivery = deliveryMethod.toDelivery(),
            url = content,
        )
    }

    private fun SubtitlesStream.toOption(): SubtitleOption? {
        if (!isUrl) return null
        return SubtitleOption(
            languageTag = languageTag,
            autoGenerated = isAutoGenerated,
            format = format?.suffix,
            url = content,
        )
    }

    // ───────────── error mapping ─────────────

    private inline fun <T> guarded(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExtractionException) {
            AppLog.e(TAG, "$what failed: ${e.kind}", e)
            throw e
        } catch (e: Throwable) {
            val mapped = map(e)
            AppLog.e(TAG, "$what failed: ${mapped.kind}", e)
            throw mapped
        }

    private fun map(e: Throwable): ExtractionException {
        // Order matters: subclasses before their parents.
        val kind = when (e) {
            is SignInConfirmNotBotException -> Kind.BOT_CHECK
            is AgeRestrictedContentException -> Kind.AGE_RESTRICTED
            is PrivateContentException -> Kind.PRIVATE
            is GeographicRestrictionException, is UnsupportedContentInCountryException -> Kind.GEO_BLOCKED
            is PaidContentException, is YoutubeMusicPremiumContentException -> Kind.PAID
            is ContentNotAvailableException, is ContentNotSupportedException -> Kind.UNAVAILABLE
            is ReCaptchaException -> Kind.RATE_LIMITED
            is ParsingException, is NpExtractionException -> Kind.NEEDS_UPDATE
            is IOException -> Kind.NETWORK
            else -> Kind.UNKNOWN
        }
        return ExtractionException(kind, "${e.javaClass.simpleName}: ${AppLog.redact(e.message.orEmpty())}", e)
    }

    companion object {
        private const val TAG = "Extractor"

        /** Keep in sync with the NewPipeExtractor version in native/android/app/build.gradle. */
        const val ENGINE_VERSION = "v0.26.5"
    }
}
