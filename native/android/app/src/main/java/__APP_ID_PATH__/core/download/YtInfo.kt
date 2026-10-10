package __APP_ID__.core.download

import org.json.JSONObject
import java.util.Locale

/** One entry of yt-dlp's "formats" array, reduced to what selection needs. */
data class YtFormat(
    val id: String,
    val ext: String,
    val height: Int,
    val fps: Int,
    val vcodec: String?,   // null = none
    val acodec: String?,   // null = none
    val abr: Double,
    val tbr: Double,
    val language: String?,
    val languagePreference: Int,
    val protocol: String,
    val sizeBytes: Long,   // exact or approximate, -1 unknown
    val note: String?,
) {
    val hasVideo: Boolean get() = vcodec != null
    val hasAudio: Boolean get() = acodec != null
    val videoOnly: Boolean get() = hasVideo && !hasAudio
    val audioOnly: Boolean get() = hasAudio && !hasVideo
    val isHls: Boolean get() = protocol.contains("m3u8")
    val isAvc: Boolean get() = vcodec?.startsWith("avc") == true
    val isAac: Boolean get() = acodec?.startsWith("mp4a") == true
}

data class YtSubtitleTrack(val language: String, val auto: Boolean, val exts: List<String>)

data class YtInfo(
    val id: String,
    val title: String,
    val formats: List<YtFormat>,
    val subtitles: List<YtSubtitleTrack>,
    val isLive: Boolean,
)

object YtInfoParser {

    fun parse(json: String): YtInfo = parse(JSONObject(json))

    fun parse(root: JSONObject): YtInfo {
        val formats = mutableListOf<YtFormat>()
        val arr = root.optJSONArray("formats")
        if (arr != null) for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val id = f.optString("format_id")
            if (id.isBlank()) continue
            val ext = f.optString("ext")
            // Storyboards ("mhtml") and anything that is neither video nor audio are not downloadable media.
            if (ext == "mhtml") continue
            val v = codec(f, "vcodec")
            val a = codec(f, "acodec")
            if (v == null && a == null) continue
            val size = when {
                !f.isNull("filesize") && f.optLong("filesize", -1) > 0 -> f.optLong("filesize")
                !f.isNull("filesize_approx") && f.optLong("filesize_approx", -1) > 0 -> f.optLong("filesize_approx")
                else -> -1L
            }
            formats += YtFormat(
                id = id,
                ext = ext,
                height = if (f.isNull("height")) 0 else f.optInt("height", 0),
                fps = if (f.isNull("fps")) 0 else f.optDouble("fps", 0.0).toInt(),
                vcodec = v,
                acodec = a,
                abr = if (f.isNull("abr")) 0.0 else f.optDouble("abr", 0.0),
                tbr = if (f.isNull("tbr")) 0.0 else f.optDouble("tbr", 0.0),
                language = f.optString("language").takeIf { it.isNotBlank() && it != "null" },
                languagePreference = if (f.isNull("language_preference")) 0 else f.optInt("language_preference", 0),
                protocol = f.optString("protocol"),
                sizeBytes = size,
                note = f.optString("format_note").takeIf { it.isNotBlank() && it != "null" },
            )
        }
        return YtInfo(
            id = root.optString("id"),
            title = root.optString("title"),
            formats = formats,
            subtitles = subtitleTracks(root.optJSONObject("subtitles"), auto = false) +
                subtitleTracks(root.optJSONObject("automatic_captions"), auto = true),
            isLive = root.optBoolean("is_live", false),
        )
    }

    private fun codec(f: JSONObject, key: String): String? {
        if (f.isNull(key) || !f.has(key)) return null
        val v = f.optString(key)
        return if (v.isBlank() || v == "none") null else v
    }

    private fun subtitleTracks(obj: JSONObject?, auto: Boolean): List<YtSubtitleTrack> {
        if (obj == null) return emptyList()
        val out = mutableListOf<YtSubtitleTrack>()
        for (lang in obj.keys()) {
            val entries = obj.optJSONArray(lang) ?: continue
            val exts = (0 until entries.length()).mapNotNull { entries.optJSONObject(it)?.optString("ext") }
                .filter { it.isNotBlank() }.distinct()
            // Live-chat is reported as a "subtitle" track by yt-dlp; it is not a subtitle.
            if (lang == "live_chat" || exts.isEmpty()) continue
            out += YtSubtitleTrack(lang, auto, exts)
        }
        return out
    }
}

private fun tag(s: String) = s.replace('_', '-').lowercase(Locale.ROOT)
private fun primary(s: String) = tag(s).substringBefore('-')

/** Why a requested thing could not be satisfied. Mapped to user text in [DownloadError]. */
class SelectionException(val kind: DownloadErrorKind, message: String) : Exception(message)

/** The concrete formats chosen for one download. */
data class FormatSelection(
    val video: YtFormat?,
    val audio: YtFormat?,
    /** Single muxed stream containing both (only when no separate audio is needed). */
    val muxed: YtFormat?,
    val container: String,   // mp4 / mkv / m4a / webm / ...
    val estimatedBytes: Long, // -1 if unknown
) {
    /** yt-dlp -f argument. */
    val formatArg: String
        get() = when {
            muxed != null -> muxed.id
            video != null && audio != null -> "${video.id}+${audio.id}"
            video != null -> video.id
            else -> audio!!.id
        }

    val needsMerge: Boolean get() = muxed == null && video != null && audio != null
    val resolvedHeight: Int get() = (video ?: muxed)?.height ?: 0
}

object FormatPicker {

    private fun codecRank(f: YtFormat) = when {
        f.isAvc -> 0
        f.vcodec?.startsWith("vp9") == true || f.vcodec?.startsWith("vp09") == true -> 1
        f.vcodec?.startsWith("av01") == true -> 2
        else -> 3
    }

    private val directOnly: (YtFormat) -> Boolean = { !it.isHls }

    /** Real heights available as downloadable video (descending). */
    fun heights(info: YtInfo): List<Int> =
        info.formats.filter { it.hasVideo && it.height > 0 && directOnly(it) }.map { it.height }.distinct().sortedDescending()

    fun select(info: YtInfo, spec: DownloadSpec): FormatSelection {
        val formats = info.formats.filter(directOnly).ifEmpty { info.formats }
        return when (spec.kind) {
            DownloadKind.SUBTITLES_ONLY -> throw IllegalArgumentException("subtitles-only has no media formats")
            DownloadKind.AUDIO_ONLY -> {
                val a = pickAudio(formats, spec.audio)
                FormatSelection(null, a, null, container = audioContainer(a), estimatedBytes = a.sizeBytes)
            }
            DownloadKind.VIDEO -> selectVideo(formats, spec)
        }
    }

    private fun selectVideo(formats: List<YtFormat>, spec: DownloadSpec): FormatSelection {
        val videoOnly = formats.filter { it.videoOnly }
        val wantHeight = spec.height ?: videoOnly.maxOfOrNull { it.height } ?: formats.filter { it.hasVideo }.maxOfOrNull { it.height }
            ?: throw SelectionException(DownloadErrorKind.FORMAT_UNAVAILABLE, "no video formats")

        val candidates = videoOnly.filter { it.height == wantHeight }
            .sortedWith(compareBy<YtFormat> { codecRank(it) }.thenByDescending { it.fps }.thenByDescending { it.tbr })

        if (candidates.isNotEmpty()) {
            val v = candidates.first()
            val a = pickAudio(formats, spec.audio)
            val container = if (v.isAvc && a.isAac) "mp4" else "mkv"
            return FormatSelection(v, a, null, container, sumSizes(v, a))
        }
        // No video-only stream at this exact height: a muxed one is acceptable ONLY with the original audio.
        val muxed = formats.filter { it.hasVideo && it.hasAudio && it.height == wantHeight }
            .sortedWith(compareBy<YtFormat> { codecRank(it) }.thenByDescending { it.tbr })
        if (muxed.isNotEmpty() && spec.audio.isOriginal) {
            val m = muxed.first()
            return FormatSelection(null, null, m, m.ext.ifBlank { "mp4" }, m.sizeBytes)
        }
        throw SelectionException(DownloadErrorKind.QUALITY_UNAVAILABLE, "no stream at ${wantHeight}p for this audio choice")
    }

    private fun sumSizes(a: YtFormat, b: YtFormat): Long =
        if (a.sizeBytes < 0 || b.sizeBytes < 0) -1 else a.sizeBytes + b.sizeBytes

    private fun audioContainer(a: YtFormat) = when {
        a.isAac -> "m4a"
        a.acodec?.startsWith("opus") == true -> "webm"
        else -> a.ext.ifBlank { "m4a" }
    }

    /**
     * Audio selection. A requested language/kind that does not exist is an error: the caller must never
     * receive the original track while believing it got a dub.
     */
    fun pickAudio(formats: List<YtFormat>, choice: AudioChoice): YtFormat {
        val audio = formats.filter { it.audioOnly }
        if (audio.isEmpty()) throw SelectionException(DownloadErrorKind.AUDIO_UNAVAILABLE, "no audio-only formats")

        // AAC (m4a) first: plays everywhere and lets AVC video merge into MP4. Then highest bitrate.
        val rank = compareBy<YtFormat> { if (it.isAac) 0 else 1 }.thenByDescending { it.abr }.thenByDescending { it.tbr }

        if (choice.isOriginal) {
            // yt-dlp marks the original/default track with the highest language_preference.
            val best = audio.maxOf { it.languagePreference }
            return audio.filter { it.languagePreference == best }.sortedWith(rank).first()
        }

        val want = tag(choice.language ?: "")
        val byLang = audio.filter { it.language != null && tag(it.language) == want }
            .ifEmpty { audio.filter { it.language != null && primary(it.language) == primary(want) } }
        val originalPref = audio.maxOf { it.languagePreference }
        val byKind = when (choice.kind) {
            AudioChoice.AudioKind.DESCRIPTIVE -> byLang.filter { it.languagePreference < -5 }
            AudioChoice.AudioKind.DUBBED -> byLang.filter { it.languagePreference >= -5 && it.languagePreference != originalPref }
            AudioChoice.AudioKind.ORIGINAL -> byLang.filter { it.languagePreference == originalPref }
        }
        if (byKind.isEmpty()) {
            throw SelectionException(
                DownloadErrorKind.AUDIO_UNAVAILABLE,
                "audio '${choice.label}' (${choice.language}) not offered by yt-dlp; offered=" +
                    audio.mapNotNull { it.language }.distinct(),
            )
        }
        return byKind.sortedWith(rank).first()
    }

    /** Subtitle availability per request: tracks that exist, and which asked-for ones don't. */
    data class SubtitlePlan(val available: List<Pair<SubtitleSpec, YtSubtitleTrack>>, val missing: List<SubtitleSpec>)

    fun planSubtitles(info: YtInfo, wanted: List<SubtitleSpec>): SubtitlePlan {
        val found = mutableListOf<Pair<SubtitleSpec, YtSubtitleTrack>>()
        val missing = mutableListOf<SubtitleSpec>()
        for (w in wanted) {
            val t = info.subtitles.firstOrNull { it.auto == w.auto && tag(it.language) == tag(w.language) }
            if (t != null) found += w to t else missing += w
        }
        return SubtitlePlan(found, missing)
    }
}
