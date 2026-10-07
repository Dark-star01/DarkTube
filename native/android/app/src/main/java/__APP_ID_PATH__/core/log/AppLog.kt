package __APP_ID__.core.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of recent diagnostics (extraction, playback, download, FFmpeg, DB errors),
 * viewable and copyable from Settings. URLs are redacted because googlevideo links carry
 * signatures and the viewer's IP. Nothing is written to disk or sent anywhere.
 */
object AppLog {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(val timeMillis: Long, val level: Level, val tag: String, val message: String)

    /** Set by the Application to mirror entries into logcat; stays null in unit tests. */
    @Volatile
    var platformSink: ((Level, String, String) -> Unit)? = null

    private const val MAX_ENTRIES = 500
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val url = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    fun d(tag: String, msg: String) = add(Level.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = add(Level.INFO, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = add(Level.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = add(Level.ERROR, tag, msg, t)

    fun redact(text: String): String = url.replace(text, "<url>")

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }

    fun dump(header: String = ""): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
        return buildString {
            if (header.isNotBlank()) appendLine(header).also { appendLine() }
            for (e in snapshot()) {
                appendLine("${fmt.format(Date(e.timeMillis))} ${e.level.name.first()}/${e.tag}: ${e.message}")
            }
        }
    }

    private fun add(level: Level, tag: String, msg: String, t: Throwable?) {
        val text = buildString {
            append(redact(msg))
            if (t != null) {
                append(" | ").append(t.javaClass.simpleName).append(": ").append(redact(t.message ?: ""))
                t.stackTrace.take(6).forEach { append("\n    at ").append(it) }
            }
        }
        synchronized(lock) {
            entries.addLast(Entry(System.currentTimeMillis(), level, tag, text))
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        platformSink?.invoke(level, tag, text)
    }
}
