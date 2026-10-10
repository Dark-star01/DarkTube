package __APP_ID__.core.download

import java.util.Locale

/** Snapshot published to the database / notification. */
data class ProgressSnapshot(
    val progress: Float,       // 0..1 across ALL files of the job
    val downloadedBytes: Long, // -1 unknown
    val totalBytes: Long,      // -1 unknown
    val speedBps: Long,        // 0 unknown
    val etaSeconds: Long,      // -1 unknown
)

/**
 * Interprets yt-dlp's progress callback (percent, eta, console line) across a job that may download
 * several files (video, audio) and then merge. [expectedFiles] is how many downloads there will be.
 * A new file is detected from the "Destination:" line, so the overall bar never jumps backwards.
 */
class ProgressTracker(private val expectedFiles: Int, private val estimatedTotalBytes: Long = -1) {
    private var fileIndex = 0
    private var started = false
    private var lastPercent = 0f
    private var currentTotal = -1L
    private var doneBytes = 0L
    private var lastOverall = 0f

    fun onLine(line: String?) {
        if (line == null) return
        if (line.contains("[download] Destination:") || line.contains("has already been downloaded")) {
            if (started) {
                // Previous file finished.
                if (currentTotal > 0) doneBytes += currentTotal
                if (fileIndex < expectedFiles - 1) fileIndex++
            }
            started = true
            lastPercent = 0f
            currentTotal = -1
        }
        parseTotal(line)?.let { currentTotal = it }
    }

    fun update(percent: Float, etaSeconds: Long, line: String?): ProgressSnapshot {
        onLine(line)
        val p = percent.coerceIn(0f, 100f)
        if (p >= 0f) lastPercent = p
        val overall = ((fileIndex + lastPercent / 100f) / expectedFiles.coerceAtLeast(1)).coerceIn(0f, 1f)
        lastOverall = maxOf(lastOverall, overall)
        val total = when {
            estimatedTotalBytes > 0 -> estimatedTotalBytes
            currentTotal > 0 && expectedFiles == 1 -> currentTotal
            else -> -1L
        }
        val downloaded = if (total > 0) (total * lastOverall).toLong() else -1L
        return ProgressSnapshot(lastOverall, downloaded, total, parseSpeed(line) ?: 0L, if (etaSeconds >= 0) etaSeconds else -1L)
    }

    companion object {
        private val speedRe = Regex("at\\s+~?\\s*([0-9.]+)\\s*([KMGT]?i?B)/s")
        private val totalRe = Regex("of\\s+~?\\s*([0-9.]+)\\s*([KMGT]?i?B)")

        fun parseSpeed(line: String?): Long? = line?.let { speedRe.find(it) }?.let { toBytes(it.groupValues[1], it.groupValues[2]) }
        fun parseTotal(line: String?): Long? = line?.let { totalRe.find(it) }?.let { toBytes(it.groupValues[1], it.groupValues[2]) }

        private fun toBytes(num: String, unit: String): Long? {
            val v = num.toDoubleOrNull() ?: return null
            val mult = when (unit.uppercase(Locale.ROOT).removeSuffix("B").removeSuffix("I")) {
                "" -> 1.0
                "K" -> 1024.0
                "M" -> 1024.0 * 1024
                "G" -> 1024.0 * 1024 * 1024
                "T" -> 1024.0 * 1024 * 1024 * 1024
                else -> return null
            }
            return (v * mult).toLong()
        }
    }
}
