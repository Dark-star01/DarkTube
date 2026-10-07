package __APP_ID__.core.extraction

import java.util.Locale

/**
 * Turns raw stream lists into what the UI and (later) the player/downloader need:
 * the qualities that really exist, audio tracks with original/dub detection, and
 * subtitle tracks split into manual vs auto-generated. Nothing here is invented:
 * every entry is derived from a stream the engine actually returned.
 */
object StreamCatalog {

    // ───────────── video ─────────────

    enum class VideoCodecFamily { AVC, VP9, AV1, OTHER }

    /** AVC first: widest hardware support and MP4-friendly. Overridable per call. */
    val DEFAULT_CODEC_ORDER = listOf(VideoCodecFamily.AVC, VideoCodecFamily.VP9, VideoCodecFamily.AV1, VideoCodecFamily.OTHER)

    fun codecFamily(codec: String?): VideoCodecFamily {
        val c = codec?.lowercase(Locale.ROOT) ?: return VideoCodecFamily.OTHER
        return when {
            c.startsWith("avc") -> VideoCodecFamily.AVC
            c.startsWith("vp9") || c.startsWith("vp09") -> VideoCodecFamily.VP9
            c.startsWith("av01") || c.startsWith("av1") -> VideoCodecFamily.AV1
            else -> VideoCodecFamily.OTHER
        }
    }

    data class Quality(
        val height: Int,
        val highFrameRate: Boolean,
        /** Every stream at this resolution, best codec/bitrate first. */
        val candidates: List<VideoStreamOption>,
    ) {
        val best: VideoStreamOption get() = candidates.first()
        /** A stream that already includes audio, if one exists at this resolution. */
        val muxed: VideoStreamOption? get() = candidates.firstOrNull { it.hasAudio }
        val label: String get() = if (highFrameRate) "${height}p${best.fps}" else "${height}p"
    }

    fun qualities(
        streams: List<VideoStreamOption>,
        codecOrder: List<VideoCodecFamily> = DEFAULT_CODEC_ORDER,
    ): List<Quality> {
        fun rank(s: VideoStreamOption): Int {
            val i = codecOrder.indexOf(codecFamily(s.codec))
            return if (i < 0) codecOrder.size else i
        }
        return streams
            .filter { it.height > 0 }
            .groupBy { it.height to (it.fps > 30) }
            .map { (key, list) ->
                Quality(
                    height = key.first,
                    highFrameRate = key.second,
                    candidates = list.sortedWith(
                        compareBy<VideoStreamOption> { rank(it) }.thenByDescending { it.bitrateKbps },
                    ),
                )
            }
            .sortedWith(compareByDescending<Quality> { it.height }.thenByDescending { it.highFrameRate })
    }

    // ───────────── audio ─────────────

    data class AudioTrack(
        val trackId: String?,
        val languageTag: String?,
        val label: String,
        val kind: AudioTrackKind,
        /** Every stream of this track, highest bitrate first. */
        val streams: List<AudioStreamOption>,
    ) {
        val best: AudioStreamOption get() = streams.first()
    }

    fun audioTracks(streams: List<AudioStreamOption>): List<AudioTrack> {
        val groups = streams.groupBy { it.trackId ?: it.languageTag ?: "" }.values
        val raw = groups.map { group ->
            val sorted = group.sortedByDescending { it.bitrateKbps }
            RawTrack(
                trackId = sorted.firstNotNullOfOrNull { it.trackId },
                languageTag = sorted.firstNotNullOfOrNull { it.languageTag },
                trackName = sorted.firstNotNullOfOrNull { it.trackName?.takeIf { n -> n.isNotBlank() } },
                kind = sorted.map { it.kind }.firstOrNull { it != AudioTrackKind.UNKNOWN } ?: AudioTrackKind.UNKNOWN,
                streams = sorted,
            )
        }
        val ordered = raw.sortedBy { kindRank(it.kind) } // stable: source order kept within a kind
        val multiple = ordered.size > 1

        // Base names, then disambiguate collisions (e.g. es-ES vs es-419).
        val base = ordered.map { baseName(it, fullName = false) }
        val names = ordered.mapIndexed { i, t ->
            if (base.count { it == base[i] } > 1) baseName(t, fullName = true) else base[i]
        }
        val seen = HashMap<String, Int>()
        return ordered.mapIndexed { i, t ->
            var name = names[i]
            val n = (seen[name] ?: 0) + 1
            seen[name] = n
            if (n > 1) name = "$name #$n"
            val suffix = if (multiple) kindSuffix(t.kind) else ""
            AudioTrack(t.trackId, t.languageTag, name + suffix, t.kind, t.streams)
        }
    }

    /** Preferred-language match (never a descriptive track), else the original, else the first track. */
    fun pickAudioTrack(tracks: List<AudioTrack>, preferredLanguage: String?): AudioTrack? {
        if (tracks.isEmpty()) return null
        if (!preferredLanguage.isNullOrBlank()) {
            val want = primaryLanguage(preferredLanguage)
            tracks.firstOrNull {
                it.kind != AudioTrackKind.DESCRIPTIVE && it.languageTag?.let(::primaryLanguage) == want
            }?.let { return it }
        }
        return tracks.firstOrNull { it.kind == AudioTrackKind.ORIGINAL } ?: tracks.first()
    }

    private class RawTrack(
        val trackId: String?,
        val languageTag: String?,
        val trackName: String?,
        val kind: AudioTrackKind,
        val streams: List<AudioStreamOption>,
    )

    private fun kindRank(k: AudioTrackKind) = when (k) {
        AudioTrackKind.ORIGINAL -> 0
        AudioTrackKind.DUBBED -> 1
        AudioTrackKind.DESCRIPTIVE -> 2
        AudioTrackKind.SECONDARY -> 3
        AudioTrackKind.UNKNOWN -> 4
    }

    private fun kindSuffix(k: AudioTrackKind) = when (k) {
        AudioTrackKind.ORIGINAL -> " (original)"
        AudioTrackKind.DUBBED -> " (dub)"
        AudioTrackKind.DESCRIPTIVE -> " (descriptive)"
        AudioTrackKind.SECONDARY -> " (secondary)"
        AudioTrackKind.UNKNOWN -> ""
    }

    private fun baseName(t: RawTrack, fullName: Boolean): String {
        val tag = t.languageTag
        if (!tag.isNullOrBlank()) {
            val name = languageName(tag, fullName)
            if (name.isNotBlank()) return name
        }
        return t.trackName ?: "Default"
    }

    // ───────────── subtitles ─────────────

    data class SubtitleTrack(
        val languageTag: String,
        val label: String,
        val autoGenerated: Boolean,
        /** Available file formats, most player-friendly first. */
        val formats: List<SubtitleOption>,
    ) {
        val best: SubtitleOption get() = formats.first()
    }

    fun subtitleTracks(subs: List<SubtitleOption>): List<SubtitleTrack> {
        val groups = subs.groupBy { it.languageTag to it.autoGenerated }.entries.toList()
        fun label(tag: String, auto: Boolean, fullName: Boolean): String {
            val name = languageName(tag, fullName).ifBlank { tag }
            return if (auto) "$name (auto-generated)" else name
        }
        val base = groups.map { (key, _) -> label(key.first, key.second, fullName = false) }
        return groups.mapIndexed { i, (key, list) ->
            val (tag, auto) = key
            // Same visible label for different tags (en / en-CA, pt / pt-BR): use the full name.
            val text = if (base.count { it == base[i] } > 1) label(tag, auto, fullName = true) else base[i]
            SubtitleTrack(tag, text, auto, list.sortedBy { formatRank(it.format) })
        }.sortedWith(compareBy<SubtitleTrack> { it.autoGenerated }.thenBy { it.label })
    }

    private fun formatRank(format: String?) = when (format?.lowercase(Locale.ROOT)) {
        "vtt" -> 0
        "ttml" -> 1
        "srt" -> 2
        else -> 9
    }

    // ───────────── helpers ─────────────

    private fun primaryLanguage(tag: String) =
        tag.replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)

    private fun languageName(tag: String, fullName: Boolean): String {
        val locale = Locale.forLanguageTag(tag.replace('_', '-'))
        val name = if (fullName) locale.getDisplayName(Locale.ENGLISH) else locale.getDisplayLanguage(Locale.ENGLISH)
        return name.replace(Regex("\\s+"), " ").trim() // seen in the wild: stray newline in a name
    }
}

/** Spec-style convenience views (getAudioTracks / getSubtitles) over a single fetch. */
fun VideoInfo.audioTracks() = StreamCatalog.audioTracks(streams.audio)
fun VideoInfo.subtitleTracks() = StreamCatalog.subtitleTracks(streams.subtitles)
fun VideoInfo.qualities() = StreamCatalog.qualities(streams.video)
