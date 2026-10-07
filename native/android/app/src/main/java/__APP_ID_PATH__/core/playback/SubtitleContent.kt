package __APP_ID__.core.playback

import java.util.Locale

/**
 * Decides what a subtitle download REALLY is, independent of what the extractor declared.
 * Pure logic so it can be unit-tested; the HTTP part lives in SubtitleProbe.
 */
object SubtitleContent {

    enum class Kind { TTML, VTT, SRT, EMPTY, HTML, HTTP_ERROR, NETWORK_ERROR, UNKNOWN }

    data class ProbeResult(
        val kind: Kind,
        val status: Int?,
        val contentType: String?,
        val declaredMime: String,
    ) {
        val actualMime: String? get() = mimeOf(kind)

        /** Usable only if it is a renderable format AND matches what the player was told to expect. */
        val usable: Boolean get() = actualMime != null && actualMime == declaredMime

        val userMessage: String
            get() = when (kind) {
                Kind.TTML, Kind.VTT, Kind.SRT ->
                    if (usable) "" else "This subtitle track arrived in a different format than announced ($kind), so it can't be shown."
                Kind.EMPTY -> "YouTube returned an empty file for this subtitle track, so there is nothing to show."
                Kind.HTML -> "YouTube returned a web page instead of subtitles for this track."
                Kind.HTTP_ERROR -> "YouTube refused to send this subtitle track (HTTP ${status ?: "?"})."
                Kind.NETWORK_ERROR -> "This subtitle track could not be downloaded. Check your connection."
                Kind.UNKNOWN -> "This subtitle track is in a format DarkTube doesn't recognize."
            }
    }

    fun mimeOf(kind: Kind): String? = when (kind) {
        Kind.TTML -> SubtitlePlanner.MIME_TTML
        Kind.VTT -> SubtitlePlanner.MIME_VTT
        Kind.SRT -> SubtitlePlanner.MIME_SRT
        else -> null
    }

    /** Classifies the first bytes of a response body. */
    fun classify(snippet: String): Kind {
        val s = snippet.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val head = s.take(512).lowercase(Locale.ROOT)
        return when {
            s.isEmpty() -> Kind.EMPTY
            head.startsWith("webvtt") -> Kind.VTT
            head.startsWith("<!doctype html") || head.startsWith("<html") -> Kind.HTML
            head.startsWith("<?xml") || head.startsWith("<tt") -> if (head.contains("<tt")) Kind.TTML else Kind.UNKNOWN
            head.first().isDigit() && head.contains("-->") -> Kind.SRT
            else -> Kind.UNKNOWN
        }
    }

    fun fromHttp(status: Int, contentType: String?, bodySnippet: String, declaredMime: String): ProbeResult {
        val kind = if (status !in 200..299) Kind.HTTP_ERROR else classify(bodySnippet)
        return ProbeResult(kind, status, contentType, declaredMime)
    }

    fun networkError(declaredMime: String) = ProbeResult(Kind.NETWORK_ERROR, null, null, declaredMime)
}
