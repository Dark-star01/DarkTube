package __APP_ID__.core

import __APP_ID__.core.playback.SubtitleContent
import __APP_ID__.core.playback.SubtitlePlanner
import __APP_ID__.core.playback.UrlDescriber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDiagnosticsTest {

    // ── ids rewritten by MergingMediaPeriod ──
    @Test fun originalIdStripsMergePrefix() {
        assertEquals("sub:ar:manual", SubtitlePlanner.originalId("3:sub:ar:manual"))
        assertEquals("sub:en:auto", SubtitlePlanner.originalId("12:sub:en:auto"))
        assertEquals("sub:zh-Hans:manual", SubtitlePlanner.originalId("0:sub:zh-Hans:manual"))
    }
    @Test fun originalIdLeavesPlainIdsAndNull() {
        assertEquals("sub:ar:manual", SubtitlePlanner.originalId("sub:ar:manual"))
        assertNull(SubtitlePlanner.originalId(null))
        assertEquals("1:", SubtitlePlanner.originalId("1:"))
    }

    // ── url description never leaks secrets ──
    @Test fun describesStreamWithoutSecrets() {
        val url = "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&ei=X&ip=1.2.3.4&id=o-AAA&itag=251&" +
            "mime=audio%2Fwebm&xtags=acont%3Ddubbed-auto%3Alang%3Dar&c=IOS&sig=SECRETSIG&n=SECRETN&pot=SECRETPOT&clen=123"
        val d = UrlDescriber.describe(url)
        assertTrue(d.contains("googlevideo/videoplayback"))
        assertTrue(d.contains("itag=251"))
        assertTrue(d.contains("mime=audio/webm"))
        assertTrue(d.contains("xtags=acont=dubbed-auto:lang=ar"))
        assertTrue(d.contains("c=IOS"))
        for (secret in listOf("SECRET", "1.2.3.4", "expire", "sn-abc", "o-AAA")) assertFalse(d, d.contains(secret))
    }
    @Test fun describesTimedtextUrl() {
        val d = UrlDescriber.describe("https://www.youtube.com/api/timedtext?v=ID&lang=ar&fmt=ttml&signature=SECRET&kind=asr")
        assertTrue(d.contains("youtube/api"))
        assertTrue(d.contains("lang=ar"))
        assertTrue(d.contains("fmt=ttml"))
        assertTrue(d.contains("kind=asr"))
        assertFalse(d.contains("SECRET"))
    }
    @Test fun describeSurvivesGarbage() {
        assertEquals("<unparsed>", UrlDescriber.describe("not a url"))
        assertTrue(UrlDescriber.describe("https://x.test/a?b").startsWith("other/a"))
    }

    // ── subtitle content classification ──
    @Test fun classifiesRealFormats() {
        assertEquals(SubtitleContent.Kind.TTML, SubtitleContent.classify("<?xml version=\"1.0\" encoding=\"utf-8\" ?><tt xmlns=\"http://www.w3.org/ns/ttml\">"))
        assertEquals(SubtitleContent.Kind.TTML, SubtitleContent.classify("\uFEFF  <tt xmlns=\"x\">"))
        assertEquals(SubtitleContent.Kind.VTT, SubtitleContent.classify("WEBVTT\n\n00:00.000 --> 00:01.000"))
        assertEquals(SubtitleContent.Kind.SRT, SubtitleContent.classify("1\n00:00:00,000 --> 00:00:01,000\nHi"))
    }
    @Test fun classifiesBadContent() {
        assertEquals(SubtitleContent.Kind.EMPTY, SubtitleContent.classify(""))
        assertEquals(SubtitleContent.Kind.EMPTY, SubtitleContent.classify("  \n "))
        assertEquals(SubtitleContent.Kind.HTML, SubtitleContent.classify("<!DOCTYPE html><html>"))
        assertEquals(SubtitleContent.Kind.UNKNOWN, SubtitleContent.classify("<?xml version=\"1.0\"?><transcript>"))
        assertEquals(SubtitleContent.Kind.UNKNOWN, SubtitleContent.classify("{\"events\":[]}"))
    }
    @Test fun httpErrorWinsOverBody() {
        val r = SubtitleContent.fromHttp(429, "text/html", "<html>", SubtitlePlanner.MIME_TTML)
        assertEquals(SubtitleContent.Kind.HTTP_ERROR, r.kind)
        assertFalse(r.usable)
        assertTrue(r.userMessage.contains("429"))
    }
    @Test fun usableOnlyWhenContentMatchesDeclaredMime() {
        val ok = SubtitleContent.fromHttp(200, "application/ttml+xml", "<tt>", SubtitlePlanner.MIME_TTML)
        assertTrue(ok.usable)
        assertEquals("", ok.userMessage)
        val mismatch = SubtitleContent.fromHttp(200, "text/vtt", "WEBVTT", SubtitlePlanner.MIME_TTML)
        assertFalse(mismatch.usable)
        assertTrue(mismatch.userMessage.contains("different format"))
    }
    @Test fun emptyBodyIsReportedAsEmptyNotAsParseFailure() {
        val r = SubtitleContent.fromHttp(200, "text/xml", "", SubtitlePlanner.MIME_TTML)
        assertEquals(SubtitleContent.Kind.EMPTY, r.kind)
        assertTrue(r.userMessage.contains("empty"))
    }
}
