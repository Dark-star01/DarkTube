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
            AppLog.i("Download", "engine ready: yt-dlp ${versionName()} (Android API ${android.os.Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            AppLog.e("Download", "engine init failed", e)
            throw DownloadException(DownloadErrorKind.ENGINE_FAILURE, "init: ${e.message}", e)
        }
    }

    override fun fetchInfo(videoId: String, processId: String?, profile: ClientProfile): YtInfo {
        initialize()
        val request = YoutubeDLRequest(watchUrl(videoId))
        apply(request, YtDlpArgs.info(profile))
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
        profile: ClientProfile,
        onProgress: (ProgressSnapshot) -> Unit,
    ) {
        initialize()
        val request = YoutubeDLRequest(watchUrl(videoId))
        val options = YtDlpArgs.download(selection, spec, subtitles, workDir, resume, profile)
        apply(request, options)
        AppLog.d("Download", "yt-dlp client=${profile.name} ${options.joinToString(" ") { "${it.flag} ${it.value ?: ""}".trim() }}")
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

    @Volatile private var cachedVersion: String? = null

    /** Runs `yt-dlp --version`; the library's own version prefs stay empty until the first update. */
    override fun versionName(): String {
        cachedVersion?.let { return it }
        val asked = try {
            val r = YoutubeDLRequest(emptyList<String>())
            r.addOption("--version")
            YoutubeDL.getInstance().execute(r, null, null).out.trim().lineSequence().firstOrNull { it.isNotBlank() }
        } catch (e: Exception) {
            AppLog.w("Download", "could not read yt-dlp version: ${e.message?.take(120)}")
            null
        }
        val v = asked ?: YoutubeDL.getInstance().versionName(context) ?: YoutubeDL.getInstance().version(context) ?: "unknown"
        if (asked != null) cachedVersion = v
        return v
    }

    override fun update(): String {
        initialize()
        return try {
            when (YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)) {
                YoutubeDL.UpdateStatus.DONE -> { cachedVersion = null; "Updated to ${versionName()}" }
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
        // Everything yt-dlp said on stderr (warnings often name the real cause, e.g. a missing PO token or
        // a failed JS challenge). URLs are redacted by AppLog; length is capped.
        val lines = text.orEmpty().lines().filter { it.isNotBlank() }
        AppLog.w("Download", "yt-dlp failed (${lines.size} stderr lines)")
        lines.take(12).forEach { AppLog.w("Download", "  yt-dlp: ${it.take(300)}") }
        if (lines.size > 12) AppLog.w("Download", "  yt-dlp: … ${lines.last().take(300)}")
        throw DownloadException(DownloadError.classify(text), text ?: "no message", e)
    }
}
