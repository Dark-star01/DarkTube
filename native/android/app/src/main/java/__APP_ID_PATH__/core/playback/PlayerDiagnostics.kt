package __APP_ID__.core.playback

import __APP_ID__.core.log.AppLog
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException

/**
 * Writes what actually went wrong into the debug log: the exact error code, which renderer or
 * stream failed, the HTTP status, and (for subtitles) the real response. URLs are reduced by
 * [UrlDescriber], so no signatures or tokens ever reach the log.
 */
@OptIn(UnstableApi::class)
object PlayerDiagnostics {
    private const val TAG = "Player"

    /** Full description of a fatal playback error, including the whole cause chain. */
    fun describeError(error: PlaybackException): String {
        val sb = StringBuilder("playback error ").append(error.errorCodeName).append(" (").append(error.errorCode).append(")")
        (error as? ExoPlaybackException)?.let {
            sb.append(" type=").append(it.type)
            sb.append(" renderer=").append(it.rendererName)
            sb.append(" rendererFormat=").append(it.rendererFormat?.sampleMimeType)
        }
        var cause: Throwable? = error.cause
        var depth = 0
        while (cause != null && depth < 4) {
            sb.append("\n  cause: ").append(cause.javaClass.simpleName).append(": ").append(cause.message ?: "")
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                sb.append(" [HTTP ").append(cause.responseCode).append("] ").append(UrlDescriber.describe(cause.dataSpec.uri.toString()))
            } else if (cause is HttpDataSource.HttpDataSourceException) {
                sb.append(" ").append(UrlDescriber.describe(cause.dataSpec.uri.toString()))
            }
            cause = cause.cause
            depth++
        }
        return sb.toString()
    }

    /** Non-fatal load results: lets us see subtitle downloads succeed or fail while the video keeps playing. */
    val analytics = object : AnalyticsListener {
        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            if (mediaLoadData.trackType != C.TRACK_TYPE_TEXT) return
            val headers = loadEventInfo.responseHeaders
            val status = headers.entries.firstOrNull { (it.key as String?) == null }?.value?.firstOrNull()
            val type = headers.entries.firstOrNull { (it.key as String?).equals("content-type", ignoreCase = true) }?.value?.firstOrNull()
            AppLog.i(
                TAG,
                "subtitle loaded ${UrlDescriber.describe(loadEventInfo.uri.toString())} " +
                    "status='$status' contentType='$type' bytes=${loadEventInfo.bytesLoaded} " +
                    "sampleMime=${mediaLoadData.trackFormat?.sampleMimeType}",
            )
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            val http = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            AppLog.w(
                TAG,
                "load error trackType=${mediaLoadData.trackType} dataType=${mediaLoadData.dataType} " +
                    "${UrlDescriber.describe(loadEventInfo.uri.toString())} http=$http canceled=$wasCanceled " +
                    "${error.javaClass.simpleName}: ${error.message ?: ""}",
            )
        }
    }
}
