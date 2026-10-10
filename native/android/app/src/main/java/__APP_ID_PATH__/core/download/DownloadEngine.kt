package __APP_ID__.core.download

/** What the download manager needs from a downloader. yt-dlp is the only implementation. */
interface DownloadEngine {
    /** Idempotent; unpacks the bundled runtime on first use. */
    fun initialize()

    /** Blocking. One metadata fetch (fresh stream URLs every time). Cancellable through [cancel]. */
    @Throws(DownloadException::class)
    fun fetchInfo(videoId: String, processId: String?): YtInfo

    /**
     * Blocking. Downloads (and, when needed, merges / converts) into [workDir] as `media.*`.
     * @throws DownloadException with kind CANCELLED when [cancel] was called for [processId].
     */
    @Throws(DownloadException::class)
    fun download(
        videoId: String,
        selection: FormatSelection?,
        spec: DownloadSpec,
        subtitles: List<SubtitleSpec>,
        workDir: String,
        processId: String,
        resume: Boolean,
        onProgress: (ProgressSnapshot) -> Unit,
    )

    /** Kills the running process for [processId]; partial files stay on disk. */
    fun cancel(processId: String): Boolean

    fun versionName(): String

    /** @return a short status text, e.g. "Updated to 2026.x" / "Already up to date". */
    @Throws(DownloadException::class)
    fun update(): String
}
