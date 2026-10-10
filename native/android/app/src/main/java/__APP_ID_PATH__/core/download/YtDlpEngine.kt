package __APP_ID__.core.download

import __APP_ID__.core.log.AppLog
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest

/**
 * yt-dlp (via the youtubedl-android library) is the primary download engine. FFmpeg, bundled by the
 * same library, is invoked BY yt-dlp only to merge separate video+audio streams and to convert
 * subtitle formats; it is never used to fetch media.
 */
class YtDlpEngine(private val context: Context) : DownloadEngine {

    @Volatile private var initialized = false

    @Synchronized
    override fun initialize() {
        if (initialized) return
        if (android.os.Build.VERSION.SDK_INT < 24) {
            throw DownloadException(DownloadErrorKind.UNSUPPORTED_FORMAT, "yt-dlp runtime needs Android 7.0 (API 24) or newer")
        }
        try {
            YoutubeDL.getInstance().init(context.applicationContext)
            FFmpeg.getInstance().init(context.applicationContext)
            initialized = true
            AppLog.i("Download", "engine ready: yt-dlp ${versionName()}")
        } catch (e: Exception) {
            AppLog.e("Download", "engine init failed", e)
            throw DownloadException(DownloadErrorKind.ENGINE_FAILURE, "init: ${e.message}", e)
        }
    }

    override fun fetchInfo(videoId: String, processId: String?): YtInfo {
        initialize()
        val request = YoutubeDLRequest(watchUrl(videoId))
        apply(request, YtDlpArgs.info())
        val response = run(request, processId = processId, callback = null)
        return try {
            YtInfoParser.parse(response.out.trim())
        } catch (e: Exception) {
            throw DownloadException(DownloadErrorKind.ENGINE_FAILURE, "unparseable info: ${e.message}", e)
        }
    }

    override fun download(
        videoId: String,
        selection: FormatSelection?,
        spec: DownloadSpec,
        subtitles: List<SubtitleSpec>,
        workDir: String,
        processId: String,
        resume: Boolean,
        onProgress: (ProgressSnapshot) -> Unit,
    ) {
        initialize()
        val request = YoutubeDLRequest(watchUrl(videoId))
        val options = YtDlpArgs.download(selection, spec, subtitles, workDir, resume)
        apply(request, options)
        AppLog.d("Download", "yt-dlp ${options.joinToString(" ") { "${it.flag} ${it.value ?: ""}".trim() }}")
        val files = (if (selection?.needsMerge == true) 2 else 1)
        val tracker = ProgressTracker(expectedFiles = files, estimatedTotalBytes = selection?.estimatedBytes ?: -1)
        run(request, processId) { percent, eta, line ->
            onProgress(tracker.update(percent, eta, line))
        }
    }

    override fun cancel(processId: String): Boolean = try {
        YoutubeDL.getInstance().destroyProcessById(processId)
    } catch (e: Exception) {
        false
    }

    override fun versionName(): String = try {
        YoutubeDL.getInstance().versionName(context) ?: YoutubeDL.getInstance().version(context) ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

    override fun update(): String {
        initialize()
        return try {
            when (YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)) {
                YoutubeDL.UpdateStatus.DONE -> "Updated to ${versionName()}"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already up to date (${versionName()})"
                else -> "No update information"
            }
        } catch (e: Exception) {
            throw DownloadException(DownloadError.classify(e.message).let {
                if (it == DownloadErrorKind.UNKNOWN) DownloadErrorKind.NETWORK else it
            }, "update: ${e.message}", e)
        }
    }

    // ───────────── internals ─────────────

    private fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    private fun apply(request: YoutubeDLRequest, options: List<YtOption>) {
        for (o in options) if (o.value == null) request.addOption(o.flag) else request.addOption(o.flag, o.value)
    }

    private fun run(
        request: YoutubeDLRequest,
        processId: String?,
        callback: ((Float, Long, String) -> Unit)?,
    ) = try {
        YoutubeDL.getInstance().execute(request, processId, callback)
    } catch (e: YoutubeDL.CanceledException) {
        throw DownloadException(DownloadErrorKind.CANCELLED, "cancelled", e)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw DownloadException(DownloadErrorKind.CANCELLED, "interrupted", e)
    } catch (e: YoutubeDLException) {
        val text = e.message ?: e.cause?.message
        AppLog.w("Download", "yt-dlp failed: ${text?.lines()?.lastOrNull { it.isNotBlank() }}")
        throw DownloadException(DownloadError.classify(text), text ?: "no message", e)
    }
}
