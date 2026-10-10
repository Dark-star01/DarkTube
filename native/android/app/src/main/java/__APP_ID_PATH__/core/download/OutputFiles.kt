package __APP_ID__.core.download

import java.util.Locale

/** Finds what yt-dlp produced in a job's work directory, and validates it. */
object OutputFiles {
    private val mediaExts = setOf("mp4", "mkv", "webm", "m4a", "mp3", "opus", "ogg", "aac", "mov")
    private val subExts = setOf("srt", "vtt", "ttml", "dfxp", "srv3")
    private val partial = Regex("\\.(part|ytdl|temp|tmp)(-Frag\\d+)?$", RegexOption.IGNORE_CASE)
    private val formatFragment = Regex("\\.f[0-9a-z-]+\\.[a-z0-9]+$", RegexOption.IGNORE_CASE) // media.f137.mp4 (pre-merge)

    data class Found(val primary: String?, val subtitles: List<Pair<String, String>>) // subtitles: (language, fileName)

    /** [sizes] maps file name -> size so the biggest candidate wins. */
    fun resolve(sizes: Map<String, Long>, stem: String = YtDlpArgs.MEDIA_STEM): Found {
        val subs = mutableListOf<Pair<String, String>>()
        var primary: String? = null
        var primarySize = -1L
        for ((name, size) in sizes) {
            if (!name.startsWith("$stem.") || partial.containsMatchIn(name)) continue
            val ext = name.substringAfterLast('.').lowercase(Locale.ROOT)
            when {
                ext in subExts -> {
                    val lang = name.removePrefix("$stem.").removeSuffix(".$ext")
                    if (lang.isNotBlank() && size > 0) subs += lang to name
                }
                ext in mediaExts && !formatFragment.containsMatchIn(name) && size > primarySize -> {
                    primary = name; primarySize = size
                }
            }
        }
        return Found(primary, subs.sortedBy { it.first })
    }

    fun mimeType(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "opus", "ogg" -> "audio/ogg"
        "aac" -> "audio/aac"
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "ttml", "dfxp" -> "application/ttml+xml"
        else -> "application/octet-stream"
    }

    /** True if audio/video container (decides whether Play is offered). */
    fun isPlayableMedia(extension: String) = extension.lowercase(Locale.ROOT) in mediaExts

    /** A subtitle file is valid only if it really contains timed cues. */
    fun isValidSubtitle(extension: String, content: String): Boolean {
        val c = content.trimStart('﻿', ' ', '\n', '\r', '\t')
        return when (extension.lowercase(Locale.ROOT)) {
            "srt" -> Regex("\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->\\s*\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}").containsMatchIn(c)
            "vtt" -> c.startsWith("WEBVTT") && c.contains("-->")
            "ttml", "dfxp" -> c.contains("<tt") && c.contains("<p")
            else -> false
        }
    }
}
