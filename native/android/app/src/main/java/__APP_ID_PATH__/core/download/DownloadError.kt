package __APP_ID__.core.download

import java.util.Locale

enum class DownloadErrorKind {
    NETWORK,
    EXPIRED_URL,
    VIDEO_UNAVAILABLE,
    AGE_RESTRICTED,
    BOT_CHECK,
    QUALITY_UNAVAILABLE,
    AUDIO_UNAVAILABLE,
    SUBTITLE_UNAVAILABLE,
    FORMAT_UNAVAILABLE,
    ENGINE_OUTDATED,
    ENGINE_FAILURE,
    MERGE_FAILED,
    STORAGE_FULL,
    STORAGE_FAILURE,
    UNSUPPORTED_FORMAT,
    CANCELLED,
    UNKNOWN,
}

/** Error that leaves the download layer. [technical] is for the debug log only. */
class DownloadException(val kind: DownloadErrorKind, val technical: String, cause: Throwable? = null) :
    Exception(technical, cause) {
    val userMessage: String get() = DownloadError.userMessage(kind)
}

object DownloadError {

    fun userMessage(kind: DownloadErrorKind): String = when (kind) {
        DownloadErrorKind.NETWORK -> "The connection was lost. Check your internet and retry."
        DownloadErrorKind.EXPIRED_URL -> "The download link expired. Retry to get a fresh one."
        DownloadErrorKind.VIDEO_UNAVAILABLE -> "This video is unavailable for download."
        DownloadErrorKind.AGE_RESTRICTED -> "This video is age-restricted and can't be downloaded without signing in."
        DownloadErrorKind.BOT_CHECK -> "YouTube asked to confirm you're not a bot. Try again later or on another network."
        DownloadErrorKind.QUALITY_UNAVAILABLE -> "The selected quality isn't available for this video."
        DownloadErrorKind.AUDIO_UNAVAILABLE -> "The selected audio track isn't available for download."
        DownloadErrorKind.SUBTITLE_UNAVAILABLE -> "The selected subtitles aren't available for download."
        DownloadErrorKind.FORMAT_UNAVAILABLE -> "No downloadable format was found for this video."
        DownloadErrorKind.ENGINE_OUTDATED -> "The download engine needs an update. Open Settings and update it, then retry."
        DownloadErrorKind.ENGINE_FAILURE -> "The download engine failed. Retry, or check the debug log for details."
        DownloadErrorKind.MERGE_FAILED -> "Combining video and audio failed. Retry the download."
        DownloadErrorKind.STORAGE_FULL -> "Not enough free storage to save this download."
        DownloadErrorKind.STORAGE_FAILURE -> "Couldn't save the file to the chosen location."
        DownloadErrorKind.UNSUPPORTED_FORMAT -> "This format can't be downloaded or played on this device."
        DownloadErrorKind.CANCELLED -> "Download cancelled."
        DownloadErrorKind.UNKNOWN -> "The download failed for an unknown reason."
    }

    /** Worth an automatic bounded retry (fresh extraction happens on every attempt). */
    fun isRetryable(kind: DownloadErrorKind): Boolean = when (kind) {
        DownloadErrorKind.NETWORK, DownloadErrorKind.EXPIRED_URL, DownloadErrorKind.ENGINE_FAILURE,
        DownloadErrorKind.BOT_CHECK, DownloadErrorKind.UNKNOWN -> true
        else -> false
    }

    /**
     * Classifies yt-dlp / FFmpeg / IO output. Order matters: the first matching rule wins and
     * specific causes come before generic ones.
     */
    fun classify(output: String?): DownloadErrorKind {
        val t = (output ?: "").lowercase(Locale.ROOT)
        fun has(vararg needles: String) = needles.any { t.contains(it) }
        return when {
            t.isBlank() -> DownloadErrorKind.UNKNOWN
            has("no space left", "enospc", "disk quota", "not enough space") -> DownloadErrorKind.STORAGE_FULL
            has("permission denied", "eacces", "read-only file system", "erofs") -> DownloadErrorKind.STORAGE_FAILURE
            has("not a bot") -> DownloadErrorKind.BOT_CHECK
            has("confirm your age", "age-restricted", "age restricted", "inappropriate for some users") -> DownloadErrorKind.AGE_RESTRICTED
            has("requested format is not available", "no video formats found", "no formats found") -> DownloadErrorKind.FORMAT_UNAVAILABLE
            has("video unavailable", "private video", "this video is not available", "has been removed",
                "video is private", "account associated with this video has been terminated",
                "members-only", "this video is no longer available", "who has blocked it") -> DownloadErrorKind.VIDEO_UNAVAILABLE
            has("http error 403", "403: forbidden", "forbidden") -> DownloadErrorKind.EXPIRED_URL
            has("postprocessing", "conversion failed", "error while decoding", "ffmpeg", "merger") -> DownloadErrorKind.MERGE_FAILED
            has("unable to download", "temporary failure in name resolution", "timed out", "timeout", "connection reset",
                "network is unreachable", "urlopen error", "connection refused", "connectionerror", "remote end closed",
                "incompleteread", "ssl:", "too many requests", "http error 429", "unable to resolve host", "failed to resolve") -> DownloadErrorKind.NETWORK
            has("unsupported url", "unable to extract", "nsig", "please report this issue", "signature extraction",
                "sabr", "player response", "http error 400", "http error 410") -> DownloadErrorKind.ENGINE_OUTDATED
            has("unsupported", "unknown format", "invalid data found") -> DownloadErrorKind.UNSUPPORTED_FORMAT
            has("error:") || has("exception") -> DownloadErrorKind.ENGINE_FAILURE
            else -> DownloadErrorKind.UNKNOWN
        }
    }
}

/** Bounded retry. Every attempt re-extracts, so an expired URL is refreshed implicitly. */
object RetryPolicy {
    const val MAX_ATTEMPTS = 3

    enum class Next { RETRY, UPDATE_ENGINE_THEN_RETRY, FAIL }

    /** [attempt] = number of attempts already made (>= 1 after the first failure). */
    fun next(kind: DownloadErrorKind, attempt: Int, engineUpdatedAlready: Boolean): Next = when {
        kind == DownloadErrorKind.ENGINE_OUTDATED && !engineUpdatedAlready -> Next.UPDATE_ENGINE_THEN_RETRY
        DownloadError.isRetryable(kind) && attempt < MAX_ATTEMPTS -> Next.RETRY
        else -> Next.FAIL
    }

    fun backoffMillis(attempt: Int): Long = 2_000L * attempt.coerceAtLeast(1)
}
