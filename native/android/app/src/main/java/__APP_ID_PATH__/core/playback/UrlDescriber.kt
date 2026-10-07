package __APP_ID__.core.playback

import java.net.URLDecoder

/**
 * Describes a YouTube URL for diagnostics WITHOUT leaking anything sensitive: only an allow-list of
 * descriptive parameters (stream id, mime, client, dub tag...) is kept. Signatures, tokens, IPs,
 * expiry and everything else are dropped.
 */
object UrlDescriber {
    private val PARTS = Regex("""^https?://([^/?#]+)([^?#]*)(?:\?([^#]*))?""", RegexOption.IGNORE_CASE)
    private val SAFE_KEYS = listOf("itag", "mime", "c", "xtags", "clen", "dur", "fmt", "lang", "tlang", "kind", "type", "name")
    private const val MAX_VALUE = 80

    fun describe(url: String): String {
        val m = PARTS.find(url) ?: return "<unparsed>"
        val host = m.groupValues[1].lowercase()
        val hostClass = when {
            host.endsWith("googlevideo.com") -> "googlevideo"
            host.endsWith("youtube.com") -> "youtube"
            else -> "other"
        }
        val path = m.groupValues[2].trim('/').substringBefore('/')
        val query = parseQuery(m.groupValues[3])
        val parts = SAFE_KEYS.mapNotNull { key -> query[key]?.let { "$key=${it.take(MAX_VALUE)}" } }
        return (listOf("$hostClass/$path") + parts).joinToString(" ")
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            val key = pair.substring(0, i)
            val value = try {
                URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            } catch (e: IllegalArgumentException) {
                pair.substring(i + 1)
            }
            out.putIfAbsent(key, value)
        }
        return out
    }
}
