package __APP_ID__.core.playback

import __APP_ID__.core.log.AppLog
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * Wraps Media3's own parsers and drops cues that are exact duplicates (same text, same start, same
 * end). Parsing, TTML styling and the modern cue pipeline are untouched; only identical repeats
 * are removed, so the same sentence spoken twice at different times stays.
 *
 * Every removal is logged (count + a short sample) so the real cue data behind a "shown twice"
 * report can be read from Settings -> Debug log.
 */
@OptIn(UnstableApi::class)
class DedupingSubtitleParserFactory(
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
) : SubtitleParser.Factory {

    override fun supportsFormat(format: Format) = delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format) = delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser = Deduping(delegate.create(format), format)

    private class Deduping(private val inner: SubtitleParser, private val format: Format) : SubtitleParser {

        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>,
        ) {
            val seen = HashSet<CueDedupe.Key>()
            var removed = 0
            var emitted = 0
            var sample: String? = null
            inner.parse(data, offset, length, outputOptions) { item ->
                emitted++
                val cues = item.cues
                val keep = CueDedupe.keep(
                    texts = cues.map { it.text?.toString() },
                    startUs = item.startTimeUs,
                    endUs = item.endTimeUs,
                    seen = seen,
                )
                if (keep.size == cues.size) {
                    output.accept(item)
                } else {
                    removed += cues.size - keep.size
                    if (sample == null) {
                        val dropped = cues.indices.first { it !in keep }
                        sample = "'${cues[dropped].text}' at ${item.startTimeUs / 1000}ms"
                    }
                    if (keep.isNotEmpty()) {
                        output.accept(CuesWithTiming(keep.map { cues[it] }, item.startTimeUs, item.durationUs))
                    }
                }
            }
            AppLog.i(
                "Subtitles",
                "parsed ${format.sampleMimeType} lang=${format.language} groups=$emitted duplicatesRemoved=$removed" +
                    (sample?.let { " first=$it" } ?: ""),
            )
        }

        override fun getCueReplacementBehavior() = inner.cueReplacementBehavior

        override fun reset() = inner.reset()
    }
}
