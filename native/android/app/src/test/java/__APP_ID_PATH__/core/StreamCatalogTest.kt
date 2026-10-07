package __APP_ID__.core

import __APP_ID__.core.extraction.AudioStreamOption
import __APP_ID__.core.extraction.AudioTrackKind
import __APP_ID__.core.extraction.Delivery
import __APP_ID__.core.extraction.StreamCatalog
import __APP_ID__.core.extraction.SubtitleOption
import __APP_ID__.core.extraction.VideoStreamOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCatalogTest {

    private fun v(itag: Int, h: Int, fps: Int = 30, codec: String = "avc1.640028", kbps: Int = 1000, muxed: Boolean = false) =
        VideoStreamOption(itag, h * 16 / 9, h, fps, codec, "mp4", kbps, muxed, Delivery.PROGRESSIVE, "u$itag")

    private fun a(
        itag: Int, trackId: String?, lang: String?, kind: AudioTrackKind,
        kbps: Int = 128, name: String? = null, codec: String = "mp4a.40.2",
    ) = AudioStreamOption(itag, trackId, lang, name, kind, codec, "m4a", kbps, Delivery.PROGRESSIVE, "u$itag")

    private fun s(lang: String, auto: Boolean, fmt: String? = "vtt") = SubtitleOption(lang, auto, fmt, "u-$lang-$auto-$fmt")

    // ── video ──

    @Test fun qualitiesSortedHighToLowAndOnlyRealOnes() {
        val q = StreamCatalog.qualities(listOf(v(1, 360), v(2, 1080), v(3, 720), v(4, 1080)))
        assertEquals(listOf(1080, 720, 360), q.map { it.height })
    }

    @Test fun highFrameRateIsASeparateEntryListedFirst() {
        val q = StreamCatalog.qualities(listOf(v(1, 1080, 30), v(2, 1080, 60)))
        assertEquals(listOf("1080p60", "1080p"), q.map { it.label })
    }

    @Test fun avcPreferredOverVp9OverAv1ByDefault() {
        val q = StreamCatalog.qualities(
            listOf(v(1, 1080, codec = "av01.0.08M.08", kbps = 900), v(2, 1080, codec = "vp09.00.40.08", kbps = 1500), v(3, 1080, codec = "avc1.640028", kbps = 2500)),
        ).single()
        assertEquals(listOf(3, 2, 1), q.candidates.map { it.itag })
        assertEquals(3, q.best.itag)
    }

    @Test fun customCodecOrderIsRespected() {
        val order = listOf(StreamCatalog.VideoCodecFamily.AV1, StreamCatalog.VideoCodecFamily.VP9, StreamCatalog.VideoCodecFamily.AVC)
        val q = StreamCatalog.qualities(listOf(v(1, 720, codec = "avc1"), v(2, 720, codec = "av01")), order).single()
        assertEquals(2, q.best.itag)
    }

    @Test fun mutedVideoOnlyVsMuxedIsReported() {
        val q = StreamCatalog.qualities(listOf(v(1, 720, muxed = false, kbps = 2500), v(2, 720, muxed = true, kbps = 1200))).single()
        assertEquals(2, q.muxed?.itag)
        val only = StreamCatalog.qualities(listOf(v(3, 1080))).single()
        assertNull(only.muxed)
    }

    @Test fun unknownHeightIsDropped() {
        assertTrue(StreamCatalog.qualities(listOf(v(1, 0), v(2, -1))).isEmpty())
    }

    // ── audio / dubs ──

    @Test fun multiTrackOriginalAndDubsAreDetectedAndLabeled() {
        val tracks = StreamCatalog.audioTracks(
            listOf(
                a(10, "ar.3", "ar", AudioTrackKind.DUBBED),
                a(11, "en.4", "en-US", AudioTrackKind.ORIGINAL, kbps = 48),
                a(12, "en.4", "en-US", AudioTrackKind.ORIGINAL, kbps = 128),
                a(13, "ja.3", "ja", AudioTrackKind.DUBBED),
            ),
        )
        assertEquals(listOf("English (original)", "Arabic (dub)", "Japanese (dub)"), tracks.map { it.label })
        assertEquals(AudioTrackKind.ORIGINAL, tracks.first().kind)
        assertEquals(2, tracks.first().streams.size)
        assertEquals(12, tracks.first().best.itag) // highest bitrate wins
    }

    @Test fun singleTrackGetsNoKindSuffix() {
        val t = StreamCatalog.audioTracks(listOf(a(1, "en.4", "en", AudioTrackKind.ORIGINAL))).single()
        assertEquals("English", t.label)
    }

    @Test fun unlabeledSingleTrackIsJustDefault() {
        val t = StreamCatalog.audioTracks(listOf(a(1, null, null, AudioTrackKind.UNKNOWN), a(2, null, null, AudioTrackKind.UNKNOWN, kbps = 50))).single()
        assertEquals("Default", t.label)
        assertEquals(2, t.streams.size)
    }

    @Test fun trackNameUsedWhenNoLanguage() {
        val t = StreamCatalog.audioTracks(listOf(a(1, "x", null, AudioTrackKind.UNKNOWN, name = "Commentary"))).single()
        assertEquals("Commentary", t.label)
    }

    @Test fun sameLanguageVariantsAreDisambiguated() {
        val tracks = StreamCatalog.audioTracks(
            listOf(a(1, "es.1", "es-ES", AudioTrackKind.DUBBED), a(2, "es.2", "es-419", AudioTrackKind.DUBBED)),
        )
        assertEquals(2, tracks.map { it.label }.toSet().size)
        assertTrue(tracks.all { it.label.startsWith("Spanish (") })
    }

    @Test fun noInventedTracks() {
        assertTrue(StreamCatalog.audioTracks(emptyList()).isEmpty())
    }

    @Test fun pickPrefersLanguageThenOriginalThenFirst() {
        val tracks = StreamCatalog.audioTracks(
            listOf(
                a(1, "en.4", "en", AudioTrackKind.ORIGINAL),
                a(2, "ar.3", "ar", AudioTrackKind.DUBBED),
                a(3, "fr.2", "fr", AudioTrackKind.DESCRIPTIVE),
            ),
        )
        assertEquals("ar.3", StreamCatalog.pickAudioTrack(tracks, "ar")?.trackId)
        assertEquals("en.4", StreamCatalog.pickAudioTrack(tracks, "de")?.trackId) // not available -> original
        assertEquals("en.4", StreamCatalog.pickAudioTrack(tracks, "fr")?.trackId) // descriptive never auto-picked
        assertEquals("en.4", StreamCatalog.pickAudioTrack(tracks, null)?.trackId)
        assertNull(StreamCatalog.pickAudioTrack(emptyList(), "ar"))
    }

    // ── subtitles ──

    @Test fun manualBeforeAutoAndLabelsMarkAuto() {
        val tracks = StreamCatalog.subtitleTracks(listOf(s("en", true), s("ar", false), s("en", false), s("fr", true)))
        assertEquals(listOf("Arabic", "English", "English (auto-generated)", "French (auto-generated)"), tracks.map { it.label })
        assertFalse(tracks[0].autoGenerated)
        assertTrue(tracks[2].autoGenerated)
    }

    @Test fun formatsGroupedAndVttPreferred() {
        val t = StreamCatalog.subtitleTracks(listOf(s("en", false, "srt"), s("en", false, "ttml"), s("en", false, "vtt"))).single()
        assertEquals(listOf("vtt", "ttml", "srt"), t.formats.map { it.format })
        assertEquals("vtt", t.best.format)
    }

    @Test fun noInventedSubtitles() {
        assertTrue(StreamCatalog.subtitleTracks(emptyList()).isEmpty())
    }

    @Test fun codecFamilyDetection() {
        assertEquals(StreamCatalog.VideoCodecFamily.AVC, StreamCatalog.codecFamily("avc1.4d401f"))
        assertEquals(StreamCatalog.VideoCodecFamily.VP9, StreamCatalog.codecFamily("vp9"))
        assertEquals(StreamCatalog.VideoCodecFamily.AV1, StreamCatalog.codecFamily("av01.0.05M.08"))
        assertEquals(StreamCatalog.VideoCodecFamily.OTHER, StreamCatalog.codecFamily(null))
        assertNotNull(StreamCatalog.DEFAULT_CODEC_ORDER)
        assertNotEquals(0, StreamCatalog.DEFAULT_CODEC_ORDER.size)
    }
}
