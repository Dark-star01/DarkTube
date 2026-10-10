package __APP_ID__.core.download

import java.text.Normalizer

/**
 * Readable file names from video titles. The YouTube id never appears in a name.
 * Collisions are resolved by the caller's [exists] check, never by overwriting.
 */
object NamePolicy {
    private const val MAX_BASE_LENGTH = 120
    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val spaces = Regex("\\s+")

    /** Title -> safe base name (no extension). Keeps Arabic and other scripts intact. */
    fun baseName(title: String, fallback: String = "DarkTube video"): String {
        var s = Normalizer.normalize(title, Normalizer.Form.NFC)
        s = s.replace(illegal, " ").replace(spaces, " ").trim()
        s = s.trim('.', ' ')
        if (s.length > MAX_BASE_LENGTH) s = s.take(MAX_BASE_LENGTH).trim().trimEnd('.')
        // Drop a trailing lone surrogate left by take().
        if (s.isNotEmpty() && Character.isHighSurrogate(s.last())) s = s.dropLast(1)
        return s.ifBlank { fallback }
    }

    /**
     * "Title.ext" if free, otherwise "Title (2).ext", "Title (3).ext"... The first free number wins.
     * [exists] is asked about complete file names.
     */
    fun uniqueFileName(base: String, extension: String, exists: (String) -> Boolean): String {
        val ext = extension.trim('.').let { if (it.isEmpty()) "" else ".$it" }
        val first = base + ext
        if (!exists(first)) return first
        var n = 2
        while (true) {
            val candidate = "$base ($n)$ext"
            if (!exists(candidate)) return candidate
            n++
        }
    }

    /** Subtitle sibling: "Title.ar.srt". Language tag is sanitised the same way. */
    fun subtitleFileName(mediaBase: String, language: String, auto: Boolean, extension: String): String {
        val lang = language.replace(illegal, "").ifBlank { "und" }
        return "$mediaBase.$lang${if (auto) ".auto" else ""}.${extension.trim('.')}"
    }
}
