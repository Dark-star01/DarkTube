package __APP_ID__.core.playback

import __APP_ID__.core.extraction.newpipe.OkHttpDownloader
import __APP_ID__.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * Downloads the first bytes of a subtitle file with the same user agent the player uses and
 * reports what it REALLY is (HTTP status, content type, format). Run before selecting a track so
 * a bad track yields a precise reason instead of a silent or generic failure.
 */
object SubtitleProbe {
    private val client by lazy { OkHttpDownloader.defaultClient() }

    suspend fun probe(source: SubtitleSource): SubtitleContent.ProbeResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(source.url).header("User-Agent", OkHttpDownloader.USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                val snippet = response.peekBody(512L).string()
                val result = SubtitleContent.fromHttp(response.code, response.header("Content-Type"), snippet, source.mimeType)
                AppLog.i(
                    "Subtitle",
                    "probe ${source.id} ${UrlDescriber.describe(source.url)} http=${response.code} " +
                        "contentType=${result.contentType} kind=${result.kind} declared=${source.mimeType} " +
                        "head='${snippet.take(60).replace(Regex("\\s+"), " ")}'",
                )
                result
            }
        } catch (e: IOException) {
            AppLog.w("Subtitle", "probe failed ${source.id} ${UrlDescriber.describe(source.url)}", e)
            SubtitleContent.networkError(source.mimeType)
        }
    }
}
