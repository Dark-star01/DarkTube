package __APP_ID__.core

import __APP_ID__.core.extraction.AudioStreamOption
import __APP_ID__.core.extraction.AudioTrackKind
import __APP_ID__.core.extraction.Delivery
import __APP_ID__.core.extraction.StreamSet
import __APP_ID__.core.extraction.SubtitleOption
import __APP_ID__.core.extraction.VideoStreamOption
import __APP_ID__.core.playback.PlaybackPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPlannerTest {
    private fun v(itag: Int, h: Int, muxed: Boolean = false, codec: String = "avc1", fps: Int = 30, d: Delivery = Delivery.PROGRESSIVE) =
        VideoStreamOption(itag, h * 16 / 9, h, fps, codec, "mp4", 1000, muxed, d, "v$itag")

    private fun a(itag: Int, id: String?, lang: String?, kind: AudioTrackKind, kbps: Int = 128) =
        AudioStreamOption(itag, id, lang, null, kind, "opus", "webm", kbps, Delivery.PROGRESSIVE, "a$itag")

    private fun set(video: List<VideoStreamOption>, audio: List<AudioStreamOption>, hls: String? = null) =
        StreamSet(video, audio, emptyList<SubtitleOption>(), hls, null)

    private val dubbed = listOf(
        a(1, "en.4", "en", AudioTrackKind.ORIGINAL, 147),
        a(2, "en.4", "en", AudioTrackKind.ORIGINAL, 76),
        a(3, "ar.3", "ar", AudioTrackKind.DUBBED, 153),
    )

    @Test fun autoPicksBestUpTo1080AndMergesOriginalAudio() {
        val p = PlaybackPlanner.plan(set(listOf(v(10, 2160), v(11, 1080), v(12, 720)), dubbed), null)!!
        assertEquals("v11", p.videoUrl)
        assertEquals("a1", p.audioUrl) // original track, highest bitrate
        assertEquals("1080p", p.qualityLabel)
    }

    @Test fun explicitQualityIsHonored() {
        val p = PlaybackPlanner.plan(set(listOf(v(10, 1080), v(11, 720)), dubbed), "720p")!!
        assertEquals("v11", p.videoUrl)
    }

    @Test fun unknownQualityFallsBackToAuto() {
        val p = PlaybackPlanner.plan(set(listOf(v(10, 1080), v(11, 720)), dubbed), "999p")!!
        assertEquals("1080p", p.qualityLabel)
    }

    @Test fun selectedDubTrackIsUsed() {
        val p = PlaybackPlanner.plan(set(listOf(v(10, 720)), dubbed), "720p", audioTrackId = "ar.3")!!
        assertEquals("a3", p.audioUrl)
    }

    @Test fun preferredLanguageSelectsDub() {
        val p = PlaybackPlanner.plan(set(listOf(v(10, 720)), dubbed), null, preferredLanguage = "ar")!!
        assertEquals("a3", p.audioUrl)
    }

    @Test fun videoOnlyPreferredOverMuxedSoAudioTrackChoiceWorks() {
        val p = PlaybackPlanner.plan(set(listOf(v(18, 360, muxed = true), v(134, 360)), dubbed), "360p")!!
        assertEquals("v134", p.videoUrl)
        assertEquals("a1", p.audioUrl)
    }

    @Test fun muxedUsedWhenNoAudioTracksExist() {
        val p = PlaybackPlanner.plan(set(listOf(v(18, 360, muxed = true), v(134, 360)), emptyList()), "360p")!!
        assertEquals("v18", p.videoUrl)
        assertNull(p.audioUrl)
    }

    @Test fun muxedOnlyResolutionNeedsNoAudioMerge() {
        val p = PlaybackPlanner.plan(set(listOf(v(22, 720, muxed = true)), dubbed), "720p")!!
        assertEquals("v22", p.videoUrl)
        assertNull(p.audioUrl)
    }

    @Test fun autoSkipsAboveCapButUsesLowestIfAllAbove() {
        val p = PlaybackPlanner.plan(set(listOf(v(10, 2160), v(11, 1440)), dubbed), null)!!
        assertEquals("1440p", p.qualityLabel) // nothing <= 1080, take the lowest available
    }

    @Test fun nonProgressiveStreamsAreNotMerged() {
        val p = PlaybackPlanner.plan(set(listOf(v(10, 1080, d = Delivery.DASH), v(11, 720)), dubbed), null)!!
        assertEquals("v11", p.videoUrl)
    }

    @Test fun liveFallsBackToHls() {
        val p = PlaybackPlanner.plan(set(emptyList(), emptyList(), hls = "https://x/hls"), null)!!
        assertTrue(p.isHls)
        assertEquals("https://x/hls", p.videoUrl)
    }

    @Test fun audioOnlyWhenNoVideo() {
        val p = PlaybackPlanner.plan(set(emptyList(), dubbed), null)!!
        assertEquals("a1", p.videoUrl)
        assertEquals("Audio only", p.qualityLabel)
    }

    @Test fun nothingPlayableGivesNull() {
        assertNull(PlaybackPlanner.plan(set(emptyList(), emptyList()), null))
    }
}
