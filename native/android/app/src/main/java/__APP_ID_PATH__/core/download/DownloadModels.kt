package __APP_ID__.core.download

import java.util.Locale

/** Lifecycle of one download. Stored in Room as its name. */
enum class DownloadState {
    QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED;

    val isActive: Boolean get() = this == QUEUED || this == DOWNLOADING
    val isFinished: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

enum class DownloadKind { VIDEO, AUDIO_ONLY, SUBTITLES_ONLY }

enum class SubtitleFormat(val extension: String) {
    SRT("srt"), VTT("vtt"), TTML("ttml");

    companion object {
        fun parse(value: String?): SubtitleFormat =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SRT
    }
}

/** What the user asked for in one subtitle row. [auto] = auto-generated, not uploaded by the creator. */
data class SubtitleSpec(val language: String, val auto: Boolean) {
    companion object {
        /** "ar:m,en:a" <-> list. Kept as a string so Room needs no converters. */
        fun encode(list: List<SubtitleSpec>): String =
            list.joinToString(",") { "${it.language}:${if (it.auto) "a" else "m"}" }

        fun decode(text: String?): List<SubtitleSpec> =
            text.orEmpty().split(',').mapNotNull { part ->
                val bits = part.split(':')
                if (bits.size != 2 || bits[0].isBlank()) null else SubtitleSpec(bits[0], bits[1] == "a")
            }
    }
}

/** Which audio the user wants. [language] null = the video's original audio. */
data class AudioChoice(
    val language: String?,
    val kind: AudioKind = AudioKind.ORIGINAL,
    /** Human label shown in the Downloads list, e.g. "Arabic (dub)". */
    val label: String = "Original",
) {
    enum class AudioKind { ORIGINAL, DUBBED, DESCRIPTIVE }

    val isOriginal: Boolean get() = language == null && kind == AudioKind.ORIGINAL
}

/**
 * Everything needed to (re)start a download. Quality is an exact height (a real quality the
 * video offered) or null for "Best".
 */
data class DownloadSpec(
    val videoId: String,
    val title: String,
    val thumbnailUrl: String?,
    val kind: DownloadKind,
    val height: Int?,
    val audio: AudioChoice,
    val subtitles: List<SubtitleSpec>,
    val subtitleFormat: SubtitleFormat = SubtitleFormat.SRT,
) {
    val qualityLabel: String
        get() = when (kind) {
            DownloadKind.AUDIO_ONLY -> "Audio only"
            DownloadKind.SUBTITLES_ONLY -> "Subtitles"
            DownloadKind.VIDEO -> if (height == null) "Best" else "${height}p"
        }
}

/** Action buttons a row shows, by state. Single source of truth for the UI and tests. */
enum class DownloadAction { PAUSE, RESUME, CANCEL, RETRY, REMOVE, PLAY, OPEN, DELETE }

object DownloadActions {
    fun forState(state: DownloadState, playable: Boolean = true): List<DownloadAction> = when (state) {
        DownloadState.QUEUED, DownloadState.DOWNLOADING -> listOf(DownloadAction.PAUSE, DownloadAction.CANCEL)
        DownloadState.PAUSED -> listOf(DownloadAction.RESUME, DownloadAction.CANCEL)
        DownloadState.FAILED -> listOf(DownloadAction.RETRY, DownloadAction.REMOVE)
        DownloadState.CANCELLED -> listOf(DownloadAction.RETRY, DownloadAction.REMOVE)
        DownloadState.COMPLETED ->
            if (playable) listOf(DownloadAction.PLAY, DownloadAction.OPEN, DownloadAction.DELETE)
            else listOf(DownloadAction.OPEN, DownloadAction.DELETE)
    }
}

object DownloadFormat {
    fun bytes(n: Long): String {
        if (n < 0) return "?"
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var i = -1
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.ROOT, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    fun speed(bytesPerSecond: Long): String = if (bytesPerSecond <= 0) "—" else bytes(bytesPerSecond) + "/s"

    fun eta(seconds: Long): String {
        if (seconds < 0) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}
