package __APP_ID__.core.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP source for googlevideo URLs. Plain GET requests are frequently refused (HTTP 403) or
 * throttled, so this mirrors the minimum the NewPipe app does for progressive streams:
 * a POST with a tiny fixed body, an incrementing `rn` request counter, and a desktop
 * user agent. Web-client URLs additionally need Origin/Referer. This is the single place to
 * adjust when YouTube changes what it accepts.
 */
@OptIn(UnstableApi::class)
object YoutubeDataSourceFactory {
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    private const val YOUTUBE = "https://www.youtube.com"
    private val POST_BODY = byteArrayOf(0x78, 0)

    /**
     * http(s) goes through the YouTube-aware source below; content:// and file:// (finished downloads and
     * their subtitle files) are handled by DefaultDataSource, so local playback uses the same player.
     */
    fun create(context: Context): DataSource.Factory {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val requestNumber = AtomicInteger(0)
        val http = ResolvingDataSource.Factory(upstream) { spec -> rewrite(spec, requestNumber) }
        return DefaultDataSource.Factory(context.applicationContext, http)
    }

    private fun rewrite(spec: DataSpec, requestNumber: AtomicInteger): DataSpec {
        val uri = spec.uri
        if (uri.path?.startsWith("/videoplayback") != true) return spec

        val url = uri.toString()
        val withRn = if (url.contains("&rn=")) url else "$url&rn=${requestNumber.getAndIncrement()}"
        val headers = HashMap(spec.httpRequestHeaders)
        if (uri.getQueryParameter("c")?.startsWith("WEB") == true) {
            headers["Origin"] = YOUTUBE
            headers["Referer"] = YOUTUBE
            headers["Sec-Fetch-Dest"] = "empty"
            headers["Sec-Fetch-Mode"] = "cors"
            headers["Sec-Fetch-Site"] = "cross-site"
        }
        return spec.buildUpon()
            .setUri(Uri.parse(withRn))
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(POST_BODY)
            .setHttpRequestHeaders(headers)
            .build()
    }
}
