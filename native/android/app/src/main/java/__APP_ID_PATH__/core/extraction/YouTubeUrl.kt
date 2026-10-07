package __APP_ID__.core.extraction

/** Extracts a YouTube video id from a pasted link, shared text, or a bare id. Pure; no engine needed. */
object YouTubeUrl {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val FIRST_URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val PARTS = Regex("""^https?://([^/?#:]+)(?::\d+)?([^?#]*)(?:\?([^#]*))?""", RegexOption.IGNORE_CASE)

    fun parseVideoId(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (ID.matches(text)) return text

        val url = FIRST_URL.find(text)?.value ?: return null
        val parts = PARTS.find(url) ?: return null
        val host = parts.groupValues[1].lowercase()
            .removePrefix("www.").removePrefix("m.").removePrefix("music.")
        val segments = parts.groupValues[2].split('/').filter { it.isNotEmpty() }
        val query = parts.groupValues[3]

        val candidate = when (host) {
            "youtu.be" -> segments.firstOrNull()
            "youtube.com", "youtube-nocookie.com" -> when (segments.firstOrNull()) {
                "watch" -> queryParam(query, "v")
                "shorts", "embed", "live", "v" -> segments.getOrNull(1)
                else -> null
            }
            else -> null
        }
        return candidate?.takeIf { ID.matches(it) }
    }

    private fun queryParam(query: String, name: String): String? =
        query.split('&').firstNotNullOfOrNull { part ->
            val i = part.indexOf('=')
            if (i > 0 && part.substring(0, i) == name) part.substring(i + 1) else null
        }
}
