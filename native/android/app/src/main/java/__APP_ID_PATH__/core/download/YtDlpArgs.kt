package __APP_ID__.core.download

/** An option for yt-dlp: [value] null means a bare flag. Pure data so it can be unit tested. */
data class YtOption(val flag: String, val value: String? = null)

/**
 * Builds yt-dlp command lines. FFmpeg is used by yt-dlp only to merge separate streams and to
 * convert subtitles; nothing else is post-processed.
 */
object YtDlpArgs {
    const val MEDIA_STEM = "media"

    private fun common(): List<YtOption> = listOf(
        YtOption("--no-playlist"),
        YtOption("--no-mtime"),
        YtOption("--newline"),
        YtOption("--retries", "3"),
        YtOption("--fragment-retries", "3"),
        YtOption("--socket-timeout", "30"),
    )

    /** `-J`: one JSON document describing the video, nothing downloaded. */
    fun info(): List<YtOption> = common() + listOf(YtOption("--dump-single-json"), YtOption("--no-warnings"))

    /**
     * @param workDir app-private directory for this job. Files are named `media.*` so titles never
     *   reach the shell or the filesystem layer of yt-dlp; the final name is chosen by DownloadStorage.
     * @param resume continue partial `.part` files (same format ids -> same files).
     */
    fun download(
        selection: FormatSelection?,
        spec: DownloadSpec,
        subtitleLanguages: List<SubtitleSpec>,
        workDir: String,
        resume: Boolean,
    ): List<YtOption> {
        val out = common().toMutableList()
        out += YtOption("--paths", workDir)
        out += YtOption("-o", "$MEDIA_STEM.%(ext)s")
        out += YtOption("-o", "subtitle:$MEDIA_STEM.%(ext)s")
        out += if (resume) YtOption("--continue") else YtOption("--no-continue")

        if (selection != null) {
            out += YtOption("-f", selection.formatArg)
            if (selection.needsMerge) out += YtOption("--merge-output-format", selection.container)
        } else {
            out += YtOption("--skip-download")
        }

        if (subtitleLanguages.isNotEmpty()) {
            val manual = subtitleLanguages.filter { !it.auto }
            val auto = subtitleLanguages.filter { it.auto }
            if (manual.isNotEmpty()) out += YtOption("--write-subs")
            if (auto.isNotEmpty()) out += YtOption("--write-auto-subs")
            out += YtOption("--sub-langs", subtitleLanguages.map { it.language }.distinct().joinToString(","))
            when (spec.subtitleFormat) {
                SubtitleFormat.SRT -> {
                    out += YtOption("--sub-format", "srt/vtt/ttml/best")
                    out += YtOption("--convert-subs", "srt")
                }
                SubtitleFormat.VTT -> {
                    out += YtOption("--sub-format", "vtt/srt/ttml/best")
                    out += YtOption("--convert-subs", "vtt")
                }
                SubtitleFormat.TTML -> out += YtOption("--sub-format", "ttml/best")
            }
        }
        return out
    }
}
