package __APP_ID__.core

import __APP_ID__.core.extraction.AudioStreamOption
import __APP_ID__.core.extraction.AudioTrackKind
import __APP_ID__.core.extraction.Delivery
import __APP_ID__.core.extraction.EngineInfo
import __APP_ID__.core.extraction.ExtractionException
import __APP_ID__.core.extraction.Formatters
import __APP_ID__.core.extraction.StreamReport
import __APP_ID__.core.extraction.StreamSet
import __APP_ID__.core.extraction.SubtitleOption
import __APP_ID__.core.extraction.VideoDetails
import __APP_ID__.core.extraction.VideoInfo
import __APP_ID__.core.extraction.VideoStreamOption
import __APP_ID__.core.extraction.VideoSummary
import __APP_ID__.core.log.AppLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiscCoreTest {

    @Test fun durationFormatting() {
        assertEquals("0:00", Formatters.duration(0))
        assertEquals("0:00", Formatters.duration(-5))
        assertEquals("4:05", Formatters.duration(245))
        assertEquals("1:02:03", Formatters.duration(3723))
        assertEquals("10:00:00", Formatters.duration(36000))
    }

    @Test fun countFormatting() {
        assertEquals("999", Formatters.count(999))
        assertEquals("1K", Formatters.count(1000))
        assertEquals("1.2K", Formatters.count(1234))
        assertEquals("1M", Formatters.count(999_999)) // not "1000K"
        assertEquals("15.3M", Formatters.count(15_300_000))
        assertEquals("2.1B", Formatters.count(2_100_000_000))
        assertEquals("?", Formatters.count(-1))
    }

    @Test fun bitrateFormatting() {
        assertEquals("128 kbps", Formatters.bitrate(128))
        assertEquals("? kbps", Formatters.bitrate(0))
    }

    @Test fun errorMessagesAreHumanAndRetryRulesSensible() {
        val e = ExtractionException(ExtractionException.Kind.NEEDS_UPDATE, "ParsingException: boom")
        assertTrue(e.userMessage.contains("may need an update"))
        assertFalse(e.userMessage.contains("Parsing"))
        assertTrue(e.retryable)
        assertFalse(ExtractionException(ExtractionException.Kind.PRIVATE, "x").retryable)
        assertFalse(ExtractionException(ExtractionException.Kind.GEO_BLOCKED, "x").retryable)
        assertTrue(ExtractionException.Kind.values().all { ExtractionException(it, "t").userMessage.isNotBlank() })
    }

    @Test fun logRedactsUrlsAndCapsBuffer() {
        AppLog.clear()
        AppLog.i("T", "fetching https://rr1.googlevideo.com/videoplayback?sig=SECRET&ip=1.2.3.4 now")
        assertFalse(AppLog.snapshot().single().message.contains("SECRET"))
        assertTrue(AppLog.snapshot().single().message.contains("<url>"))
        AppLog.e("T", "failed", IllegalStateException("bad https://x.test/a?token=SECRET2"))
        assertFalse(AppLog.dump().contains("SECRET2"))
        repeat(700) { AppLog.d("T", "line $it") }
        assertEquals(500, AppLog.snapshot().size)
        assertEquals("line 699", AppLog.snapshot().last().message)
        AppLog.clear()
        assertTrue(AppLog.snapshot().isEmpty())
    }

    @Test fun reportDescribesWhatWasReturnedAndNeverLeaksUrls() {
        val summary = VideoSummary("dQw4w9WgXcQ", "Title", "Chan", null, 245, null, 10, null, false)
        val info = VideoInfo(
            VideoDetails(summary, "desc", 0),
            StreamSet(
                video = listOf(VideoStreamOption(137, 1920, 1080, 30, "avc1.640028", "mp4", 4000, false, Delivery.PROGRESSIVE, "https://secret.example/v")),
                audio = listOf(
                    AudioStreamOption(140, "en.4", "en", null, AudioTrackKind.ORIGINAL, "mp4a.40.2", "m4a", 128, Delivery.PROGRESSIVE, "https://secret.example/a1"),
                    AudioStreamOption(141, "ar.3", "ar", null, AudioTrackKind.DUBBED, "mp4a.40.2", "m4a", 128, Delivery.PROGRESSIVE, "https://secret.example/a2"),
                ),
                subtitles = listOf(SubtitleOption("ar", false, "vtt", "https://secret.example/s")),
                hlsUrl = null,
                dashManifestUrl = null,
            ),
        )
        val r = StreamReport.build(info, EngineInfo("NewPipeExtractor", "v0.26.5", ""))
        assertTrue(r.contains("Video qualities (1)"))
        assertTrue(r.contains("1080p"))
        assertTrue(r.contains("Audio tracks (2)"))
        assertTrue(r.contains("Arabic (dub)"))
        assertTrue(r.contains("kind=DUBBED"))
        assertTrue(r.contains("Subtitles (1)"))
        assertTrue(r.contains("manual"))
        assertFalse(r.contains("secret.example"))
    }
}
