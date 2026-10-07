package __APP_ID__.core.extraction

import java.util.Locale

/** Locale.ROOT everywhere so digits stay ASCII regardless of the device language. */
object Formatters {
    fun duration(seconds: Long): String {
        val s = if (seconds < 0) 0 else seconds
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    fun count(n: Long): String {
        if (n < 0) return "?"
        if (n < 1_000) return n.toString()
        val units = arrayOf("K", "M", "B")
        var value = n.toDouble()
        var unit = -1
        while (value >= 1_000 && unit < units.lastIndex) {
            value /= 1_000
            unit++
        }
        // 999_999 -> 1000.0K after rounding; promote to the next unit instead.
        if (String.format(Locale.ROOT, "%.1f", value).toDouble() >= 1_000 && unit < units.lastIndex) {
            value /= 1_000
            unit++
        }
        val text = String.format(Locale.ROOT, "%.1f", value)
        return (if (text.endsWith(".0")) text.dropLast(2) else text) + units[unit]
    }

    fun bitrate(kbps: Int): String = if (kbps <= 0) "? kbps" else "$kbps kbps"
}
