package __APP_ID__.core.download

import __APP_ID__.core.playback.CueDedupe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NamePolicyTest {
    @Test fun keepsArabicAndStripsIllegalChars() {
        assertEquals("كيف تتعلم البرمجة", NamePolicy.baseName("كيف تتعلم البرمجة?"))
        assertEquals("A B C D", NamePolicy.baseName("A/B\\C:D"))
    }

    @Test fun collapsesWhitespaceAndTrimsDots() {
        assertEquals("Hello World", NamePolicy.baseName("  Hello    World...  "))
    }

    @Test fun emptyTitleFallsBack() {
        assertEquals("DarkTube video", NamePolicy.baseName("???"))
        assertEquals("DarkTube video", NamePolicy.baseName(""))
    }

    @Test fun longTitleIsTruncatedSafely() {
        val s = NamePolicy.baseName("x".repeat(300))
        assertEquals(120, s.length)
        val emoji = NamePolicy.baseName("a".repeat(119) + "😀")
        assertFalse(Character.isHighSurrogate(emoji.last()))
    }

    @Test fun noYoutubeIdIsAddedAnywhere() {
        val n = NamePolicy.uniqueFileName(NamePolicy.baseName("My Video"), "mp4") { false }
        assertEquals("My Video.mp4", n)
    }

    @Test fun collisionNumbersAndNeverOverwrites() {
        val taken = mutableSetOf("My Video.mp4")
        assertEquals("My Video (2).mp4", NamePolicy.uniqueFileName("My Video", "mp4") { it in taken })
        taken += "My Video (2).mp4"
        assertEquals("My Video (3).mp4", NamePolicy.uniqueFileName("My Video", "mp4") { it in taken })
        // same base, different extension is not a collision
        assertEquals("My Video.m4a", NamePolicy.uniqueFileName("My Video", "m4a") { it in taken })
    }

    @Test fun subtitleNames() {
        assertEquals("My Video (2).ar.srt", NamePolicy.subtitleFileName("My Video (2)", "ar", false, "srt"))
        assertEquals("My Video.en.auto.vtt", NamePolicy.subtitleFileName("My Video", "en", true, ".vtt"))
    }
}

class ModelsTest {
    @Test fun subtitleSpecRoundTrip() {
        val l = listOf(SubtitleSpec("ar", false), SubtitleSpec("en", true))
        assertEquals("ar:m,en:a", SubtitleSpec.encode(l))
        assertEquals(l, SubtitleSpec.decode("ar:m,en:a"))
        assertEquals(emptyList<SubtitleSpec>(), SubtitleSpec.decode(null))
        assertEquals(emptyList<SubtitleSpec>(), SubtitleSpec.decode("garbage,:m"))
    }

    @Test fun actionsPerState() {
        assertEquals(listOf(DownloadAction.PAUSE, DownloadAction.CANCEL), DownloadActions.forState(DownloadState.DOWNLOADING))
        assertEquals(listOf(DownloadAction.PAUSE, DownloadAction.CANCEL), DownloadActions.forState(DownloadState.QUEUED))
        assertEquals(listOf(DownloadAction.RESUME, DownloadAction.CANCEL), DownloadActions.forState(DownloadState.PAUSED))
        assertEquals(listOf(DownloadAction.RETRY, DownloadAction.REMOVE), DownloadActions.forState(DownloadState.FAILED))
        assertEquals(listOf(DownloadAction.PLAY, DownloadAction.OPEN, DownloadAction.DELETE), DownloadActions.forState(DownloadState.COMPLETED))
        assertEquals(listOf(DownloadAction.OPEN, DownloadAction.DELETE), DownloadActions.forState(DownloadState.COMPLETED, playable = false))
    }

    @Test fun qualityLabels() {
        val base = DownloadSpec("id", "t", null, DownloadKind.VIDEO, null, AudioChoice(null), emptyList())
        assertEquals("Best", base.qualityLabel)
        assertEquals("720p", base.copy(height = 720).qualityLabel)
        assertEquals("Audio only", base.copy(kind = DownloadKind.AUDIO_ONLY).qualityLabel)
    }

    @Test fun formatters() {
        assertEquals("512 B", DownloadFormat.bytes(512))
        assertEquals("1.5 MB", DownloadFormat.bytes(1_572_864))
        assertEquals("—", DownloadFormat.speed(0))
        assertEquals("1:05", DownloadFormat.eta(65))
        assertEquals("1:01:01", DownloadFormat.eta(3661))
        assertEquals("—", DownloadFormat.eta(-1))
    }

    @Test fun stateFlags() {
        assertTrue(DownloadState.QUEUED.isActive)
        assertFalse(DownloadState.PAUSED.isActive)
        assertTrue(DownloadState.FAILED.isFinished)
        assertFalse(DownloadState.PAUSED.isFinished)
    }
}

class ProgressTest {
    @Test fun parsesSpeedAndTotal() {
        val l = "[download]  45.3% of ~  10.00MiB at    1.20MiB/s ETA 00:05"
        assertEquals((1.2 * 1024 * 1024).toLong(), ProgressTracker.parseSpeed(l))
        assertEquals(10L * 1024 * 1024, ProgressTracker.parseTotal(l))
        assertNull(ProgressTracker.parseSpeed("[download] Destination: x"))
        assertEquals(512L * 1024, ProgressTracker.parseSpeed("at 512.00KiB/s"))
    }

    @Test fun singleFileProgress() {
        val t = ProgressTracker(1)
        t.update(0f, -1, "[download] Destination: media.mp4")
        val s = t.update(50f, 10, "[download]  50.0% of  100.00MiB at  5.00MiB/s ETA 00:10")
        assertEquals(0.5f, s.progress, 0.001f)
        assertEquals(100L * 1024 * 1024, s.totalBytes)
        assertEquals(50L * 1024 * 1024, s.downloadedBytes)
        assertEquals(5L * 1024 * 1024, s.speedBps)
        assertEquals(10L, s.etaSeconds)
    }

    @Test fun twoFilesNeverGoBackwards() {
        val t = ProgressTracker(2)
        t.update(0f, -1, "[download] Destination: media.f137.mp4")
        val a = t.update(100f, 0, "[download] 100% of 80.00MiB at 4.00MiB/s ETA 00:00")
        assertEquals(0.5f, a.progress, 0.001f)
        val b = t.update(0f, -1, "[download] Destination: media.f140.m4a")
        assertTrue(b.progress >= a.progress)
        val c = t.update(50f, 3, "[download]  50.0% of  10.00MiB at 1.00MiB/s ETA 00:03")
        assertEquals(0.75f, c.progress, 0.001f)
        val d = t.update(100f, 0, "[download] 100% of 10.00MiB")
        assertEquals(1f, d.progress, 0.001f)
    }

    @Test fun estimatedTotalUsedWhenKnown() {
        val t = ProgressTracker(2, estimatedTotalBytes = 1000)
        t.update(0f, -1, "[download] Destination: a")
        val s = t.update(50f, -1, "x")
        assertEquals(1000L, s.totalBytes)
        assertEquals(250L, s.downloadedBytes)
    }

    @Test fun nullAndGarbageLinesAreSafe() {
        val t = ProgressTracker(1)
        val s = t.update(-5f, -1, null)
        assertEquals(0f, s.progress, 0f)
        assertEquals(-1L, s.etaSeconds)
        assertEquals(0L, s.speedBps)
    }
}

class ErrorTest {
    @Test fun classification() {
        assertEquals(DownloadErrorKind.EXPIRED_URL, DownloadError.classify("ERROR: unable to download video data: HTTP Error 403: Forbidden"))
        assertEquals(DownloadErrorKind.NETWORK, DownloadError.classify("ERROR: Unable to download webpage: <urlopen error [Errno -3] Temporary failure in name resolution>"))
        assertEquals(DownloadErrorKind.STORAGE_FULL, DownloadError.classify("OSError: [Errno 28] No space left on device"))
        assertEquals(DownloadErrorKind.VIDEO_UNAVAILABLE, DownloadError.classify("ERROR: [youtube] abc: Video unavailable"))
        assertEquals(DownloadErrorKind.VIDEO_UNAVAILABLE, DownloadError.classify("ERROR: Private video. Sign in if you've been granted access"))
        assertEquals(DownloadErrorKind.BOT_CHECK, DownloadError.classify("ERROR: Sign in to confirm you're not a bot"))
        assertEquals(DownloadErrorKind.AGE_RESTRICTED, DownloadError.classify("ERROR: Sign in to confirm your age"))
        assertEquals(DownloadErrorKind.FORMAT_UNAVAILABLE, DownloadError.classify("ERROR: Requested format is not available"))
        assertEquals(DownloadErrorKind.MERGE_FAILED, DownloadError.classify("ERROR: Postprocessing: Conversion failed!"))
        assertEquals(DownloadErrorKind.ENGINE_OUTDATED, DownloadError.classify("ERROR: [youtube] x: Unable to extract initial player response; please report this issue"))
        assertEquals(DownloadErrorKind.STORAGE_FAILURE, DownloadError.classify("PermissionError: [Errno 13] Permission denied"))
        assertEquals(DownloadErrorKind.NETWORK, DownloadError.classify("HTTP Error 429: Too Many Requests"))
        assertEquals(DownloadErrorKind.UNKNOWN, DownloadError.classify(""))
        assertEquals(DownloadErrorKind.UNKNOWN, DownloadError.classify(null))
    }

    @Test fun everyKindHasAFriendlyMessageWithoutTechnicalNoise() {
        for (k in DownloadErrorKind.entries) {
            val m = DownloadError.userMessage(k)
            assertTrue(k.name, m.isNotBlank())
            assertFalse(k.name, m.contains("Exception") || (m.contains("yt-dlp") && k != DownloadErrorKind.STREAM_REFUSED) || m.contains("Errno") || m.contains("HTTP"))
        }
    }

    @Test fun a403BeforeAnyByteIsARefusalNotAnExpiredLink() {
        assertEquals(DownloadErrorKind.STREAM_REFUSED, DownloadError.refine(DownloadErrorKind.EXPIRED_URL, transferStarted = false))
        assertEquals(DownloadErrorKind.EXPIRED_URL, DownloadError.refine(DownloadErrorKind.EXPIRED_URL, transferStarted = true))
        assertEquals(DownloadErrorKind.NETWORK, DownloadError.refine(DownloadErrorKind.NETWORK, transferStarted = false))
    }

    @Test fun refusedStreamUpdatesEngineOnceThenRetries() {
        assertEquals(RetryPolicy.Next.UPDATE_ENGINE_THEN_RETRY, RetryPolicy.next(DownloadErrorKind.STREAM_REFUSED, 1, false))
        assertEquals(RetryPolicy.Next.RETRY, RetryPolicy.next(DownloadErrorKind.STREAM_REFUSED, 2, true))
        assertEquals(RetryPolicy.Next.FAIL, RetryPolicy.next(DownloadErrorKind.STREAM_REFUSED, RetryPolicy.MAX_ATTEMPTS, true))
    }

    @Test fun clientProfilesProgressAndSettle() {
        assertEquals(ClientProfile.ANDROID_VR, ClientProfile.DEFAULT.next())
        assertEquals(ClientProfile.ANDROID_VR, ClientProfile.ANDROID_VR.next())
    }

    @Test fun retryPolicyIsBounded() {
        assertEquals(RetryPolicy.Next.RETRY, RetryPolicy.next(DownloadErrorKind.NETWORK, 1, false))
        assertEquals(RetryPolicy.Next.RETRY, RetryPolicy.next(DownloadErrorKind.EXPIRED_URL, 2, false))
        assertEquals(RetryPolicy.Next.FAIL, RetryPolicy.next(DownloadErrorKind.NETWORK, RetryPolicy.MAX_ATTEMPTS, false))
        assertEquals(RetryPolicy.Next.FAIL, RetryPolicy.next(DownloadErrorKind.VIDEO_UNAVAILABLE, 1, false))
        assertEquals(RetryPolicy.Next.FAIL, RetryPolicy.next(DownloadErrorKind.AUDIO_UNAVAILABLE, 1, false))
        assertEquals(RetryPolicy.Next.FAIL, RetryPolicy.next(DownloadErrorKind.STORAGE_FULL, 1, false))
        assertEquals(RetryPolicy.Next.UPDATE_ENGINE_THEN_RETRY, RetryPolicy.next(DownloadErrorKind.ENGINE_OUTDATED, 1, false))
        assertEquals(RetryPolicy.Next.FAIL, RetryPolicy.next(DownloadErrorKind.ENGINE_OUTDATED, 1, true))
        assertTrue(RetryPolicy.backoffMillis(2) > RetryPolicy.backoffMillis(1))
    }
}

internal val FIXTURE = """
{
 "id": "abc123", "title": "Demo", "is_live": false,
 "formats": [
  {"format_id":"sb0","ext":"mhtml","vcodec":"none","acodec":"none","height":45,"protocol":"mhtml"},
  {"format_id":"18","ext":"mp4","vcodec":"avc1.42001E","acodec":"mp4a.40.2","height":360,"fps":30,"tbr":500,"protocol":"https","filesize":10000000},
  {"format_id":"137","ext":"mp4","vcodec":"avc1.640028","acodec":"none","height":1080,"fps":30,"tbr":4000,"protocol":"https","filesize":200000000},
  {"format_id":"248","ext":"webm","vcodec":"vp09.00.40.08","acodec":"none","height":1080,"fps":30,"tbr":2500,"protocol":"https","filesize":120000000},
  {"format_id":"136","ext":"mp4","vcodec":"avc1.4d401f","acodec":"none","height":720,"fps":30,"tbr":2000,"protocol":"https","filesize":90000000},
  {"format_id":"135","ext":"mp4","vcodec":"avc1.4d401e","acodec":"none","height":480,"fps":30,"tbr":1000,"protocol":"https"},
  {"format_id":"134","ext":"mp4","vcodec":"avc1.4d401e","acodec":"none","height":360,"fps":30,"tbr":600,"protocol":"https","filesize_approx":30000000},
  {"format_id":"140-0","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":129.5,"tbr":129.5,"language":"en","language_preference":10,"protocol":"https","filesize":15000000,"format_note":"English (US) original (default), medium"},
  {"format_id":"140-drc","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":130.0,"tbr":130,"language":"en","language_preference":10,"protocol":"https","filesize":15000000,"format_note":"English (US) original (default), medium, DRC"},
  {"format_id":"140-1","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":129.5,"tbr":129.5,"language":"ar","language_preference":-1,"protocol":"https","filesize":15000000,"format_note":"Arabic, medium"},
  {"format_id":"251-1","ext":"webm","vcodec":"none","acodec":"opus","abr":135.0,"tbr":135,"language":"ar","language_preference":-1,"protocol":"https","filesize":16000000},
  {"format_id":"139-2","ext":"m4a","vcodec":"none","acodec":"mp4a.40.5","abr":48.0,"tbr":48,"language":"es-US","language_preference":-1,"protocol":"https","filesize":5000000},
  {"format_id":"140-3","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","abr":129.5,"language":"en","language_preference":-10,"protocol":"https","filesize":15000000,"format_note":"English descriptive"},
  {"format_id":"91","ext":"mp4","vcodec":"avc1","acodec":"mp4a.40.5","height":144,"protocol":"m3u8_native"}
 ],
 "subtitles": {"ar":[{"ext":"vtt","url":"u"},{"ext":"ttml","url":"u"}], "live_chat":[{"ext":"json"}]},
 "automatic_captions": {"en":[{"ext":"vtt","url":"u"}], "ar":[{"ext":"srv3","url":"u"}]}
}
""".trimIndent()

class YtInfoTest {
    private val info = YtInfoParser.parse(FIXTURE)

    @Test fun parsesFormatsAndDropsStoryboards() {
        assertEquals("Demo", info.title)
        assertTrue(info.formats.none { it.ext == "mhtml" })
        val v = info.formats.first { it.id == "137" }
        assertTrue(v.videoOnly); assertEquals(1080, v.height); assertTrue(v.isAvc)
        val a = info.formats.first { it.id == "140-1" }
        assertTrue(a.audioOnly); assertEquals("ar", a.language); assertEquals(-1, a.languagePreference)
        assertEquals(30_000_000L, info.formats.first { it.id == "134" }.sizeBytes)
    }

    @Test fun parsesSubtitlesWithoutLiveChat() {
        assertTrue(info.subtitles.any { it.language == "ar" && !it.auto && "vtt" in it.exts })
        assertTrue(info.subtitles.any { it.language == "en" && it.auto })
        assertTrue(info.subtitles.none { it.language == "live_chat" })
    }

    @Test fun realHeightsOnly() {
        assertEquals(listOf(1080, 720, 480, 360), FormatPicker.heights(info)) // 144p HLS and storyboard excluded
    }
}

class FormatPickerTest {
    private val info = YtInfoParser.parse(FIXTURE)
    private fun spec(kind: DownloadKind = DownloadKind.VIDEO, h: Int? = null, audio: AudioChoice = AudioChoice(null)) =
        DownloadSpec("abc123", "Demo", null, kind, h, audio, emptyList())

    @Test fun best1080PrefersAvcAndMergesToMp4() {
        val s = FormatPicker.select(info, spec(h = 1080))
        assertEquals("137", s.video!!.id)
        assertEquals("140-0", s.audio!!.id) // original (language_preference 10)
        assertEquals("137+140-0", s.formatArg)
        assertEquals("mp4", s.container)
        assertTrue(s.needsMerge)
        assertEquals(215_000_000L, s.estimatedBytes)
    }

    @Test fun bestMeansHighestRealHeight() {
        assertEquals(1080, FormatPicker.select(info, spec(h = null)).resolvedHeight)
    }

    @Test fun exactRequestedQualityNoSilentDowngrade() {
        assertEquals(720, FormatPicker.select(info, spec(h = 720)).resolvedHeight)
        assertEquals(480, FormatPicker.select(info, spec(h = 480)).resolvedHeight)
        try {
            FormatPicker.select(info, spec(h = 1440))
            fail("1440p does not exist")
        } catch (e: SelectionException) {
            assertEquals(DownloadErrorKind.QUALITY_UNAVAILABLE, e.kind)
        }
    }

    @Test fun arabicDubIsSelectedAndNeverTheOriginal() {
        val s = FormatPicker.select(info, spec(h = 720, audio = AudioChoice("ar", AudioChoice.AudioKind.DUBBED, "Arabic (dub)")))
        assertEquals("ar", s.audio!!.language)
        assertEquals("136+140-1", s.formatArg) // AAC dub keeps mp4 container
        assertEquals("mp4", s.container)
    }

    @Test fun drcCopyOfTheAudioIsNeverChosenWhenARealOneExists() {
        // 140-drc has a higher bitrate in the fixture; it must still lose.
        assertEquals("140-0", FormatPicker.pickAudio(info.formats, AudioChoice(null)).id)
        assertEquals("135+140-0", FormatPicker.select(info, spec(h = 480)).formatArg)
        assertTrue(info.formats.first { it.id == "140-drc" }.isDrc)
        // but if DRC is all there is, it is still better than nothing
        val onlyDrc = info.formats.filter { !it.audioOnly || it.id == "140-drc" }
        assertEquals("140-drc", FormatPicker.pickAudio(onlyDrc, AudioChoice(null)).id)
    }

    @Test fun regionalDubMatchesByExactTagThenPrimary() {
        val a = FormatPicker.pickAudio(info.formats, AudioChoice("es-US", AudioChoice.AudioKind.DUBBED))
        assertEquals("139-2", a.id)
        val b = FormatPicker.pickAudio(info.formats, AudioChoice("es", AudioChoice.AudioKind.DUBBED))
        assertEquals("139-2", b.id)
    }

    @Test fun missingDubFailsInsteadOfFallingBack() {
        try {
            FormatPicker.select(info, spec(audio = AudioChoice("fr", AudioChoice.AudioKind.DUBBED, "French (dub)")))
            fail("French does not exist")
        } catch (e: SelectionException) {
            assertEquals(DownloadErrorKind.AUDIO_UNAVAILABLE, e.kind)
        }
        try {
            FormatPicker.select(info, spec(kind = DownloadKind.AUDIO_ONLY, audio = AudioChoice("de", AudioChoice.AudioKind.DUBBED)))
            fail()
        } catch (e: SelectionException) {
            assertEquals(DownloadErrorKind.AUDIO_UNAVAILABLE, e.kind)
        }
    }

    @Test fun descriptiveIsNotAnAcceptableDub() {
        // English exists as ORIGINAL and DESCRIPTIVE; asking for an English dub must not return either by accident.
        try {
            FormatPicker.pickAudio(info.formats, AudioChoice("en", AudioChoice.AudioKind.DUBBED))
            fail("original English is not a dub")
        } catch (_: SelectionException) {}
        // original English *is* returned for the original choice
        assertEquals("140-0", FormatPicker.pickAudio(info.formats, AudioChoice("en", AudioChoice.AudioKind.ORIGINAL)).id)
    }

    @Test fun descriptiveWhenAsked() {
        assertEquals("140-3", FormatPicker.pickAudio(info.formats, AudioChoice("en", AudioChoice.AudioKind.DESCRIPTIVE)).id)
    }

    @Test fun audioOnlyContainer() {
        val orig = FormatPicker.select(info, spec(kind = DownloadKind.AUDIO_ONLY))
        assertEquals("140-0", orig.formatArg); assertEquals("m4a", orig.container)
        assertNull(orig.video)
        val ar = FormatPicker.select(info, spec(kind = DownloadKind.AUDIO_ONLY, audio = AudioChoice("ar", AudioChoice.AudioKind.DUBBED)))
        assertEquals("140-1", ar.formatArg) // 129 kbps AAC (abr tie with opus 135? opus abr is higher)
    }

    @Test fun mixedCodecFallsBackToMkv() {
        val vp9only = info.copy(formats = info.formats.filter { it.id != "137" })
        val s = FormatPicker.select(vp9only, spec(h = 1080))
        assertEquals("248", s.video!!.id)
        assertEquals("mkv", s.container) // vp9 cannot go into mp4 safely
    }

    @Test fun muxedOnlyAllowedForOriginalAudio() {
        val muxedOnly = info.copy(formats = info.formats.filter { it.id != "134" })
        val ok = FormatPicker.select(muxedOnly, spec(h = 360))
        assertEquals("18", ok.formatArg); assertFalse(ok.needsMerge)
        try {
            FormatPicker.select(muxedOnly, spec(h = 360, audio = AudioChoice("ar", AudioChoice.AudioKind.DUBBED)))
            fail("a muxed stream already contains the original audio")
        } catch (e: SelectionException) {
            assertEquals(DownloadErrorKind.QUALITY_UNAVAILABLE, e.kind)
        }
    }

    @Test fun subtitlePlanSeparatesAvailableFromMissing() {
        val plan = FormatPicker.planSubtitles(info, listOf(SubtitleSpec("ar", false), SubtitleSpec("en", false), SubtitleSpec("en", true)))
        assertEquals(2, plan.available.size)
        assertEquals(listOf(SubtitleSpec("en", false)), plan.missing)
    }
}

class YtDlpArgsTest {
    private val info = YtInfoParser.parse(FIXTURE)
    private fun spec(subs: List<SubtitleSpec> = emptyList(), fmt: SubtitleFormat = SubtitleFormat.SRT) =
        DownloadSpec("abc123", "Demo", null, DownloadKind.VIDEO, 1080, AudioChoice(null), subs, fmt)

    private fun List<YtOption>.get(flag: String) = filter { it.flag == flag }.map { it.value }

    @Test fun videoDownloadArgs() {
        val sel = FormatPicker.select(info, spec())
        val o = YtDlpArgs.download(sel, spec(), emptyList(), "/work/1", resume = false)
        assertEquals(listOf<String?>("137+140-0"), o.get("-f"))
        assertEquals(listOf<String?>("mp4"), o.get("--merge-output-format"))
        assertEquals(listOf<String?>("/work/1"), o.get("--paths"))
        assertTrue(o.any { it.flag == "--no-playlist" })
        assertTrue(o.any { it.flag == "--no-continue" } && o.none { it.flag == "--continue" })
        assertTrue(o.none { it.flag == "--write-subs" })
    }

    @Test fun clientProfileReachesBothInfoAndDownload() {
        val vr = ClientProfile.ANDROID_VR
        assertEquals(listOf<String?>("youtube:player_client=android_vr"), YtDlpArgs.info(vr).get("--extractor-args"))
        assertTrue(YtDlpArgs.info().none { it.flag == "--extractor-args" })
        val sel = FormatPicker.select(info, spec())
        val d = YtDlpArgs.download(sel, spec(), emptyList(), "/w", false, vr)
        assertEquals(listOf<String?>("youtube:player_client=android_vr"), d.get("--extractor-args"))
    }

    @Test fun resumeUsesContinue() {
        val sel = FormatPicker.select(info, spec())
        val o = YtDlpArgs.download(sel, spec(), emptyList(), "/w", resume = true)
        assertTrue(o.any { it.flag == "--continue" } && o.none { it.flag == "--no-continue" })
    }

    @Test fun muxedHasNoMergeFlag() {
        val sel = FormatPicker.select(info.copy(formats = info.formats.filter { it.id != "134" }), spec().copy(height = 360))
        assertTrue(YtDlpArgs.download(sel, spec(), emptyList(), "/w", false).none { it.flag == "--merge-output-format" })
    }

    @Test fun subtitleArgsConvertWhenNeeded() {
        val subs = listOf(SubtitleSpec("ar", false), SubtitleSpec("en", true))
        val sel = FormatPicker.select(info, spec(subs))
        val o = YtDlpArgs.download(sel, spec(subs), subs, "/w", false)
        assertTrue(o.any { it.flag == "--write-subs" }); assertTrue(o.any { it.flag == "--write-auto-subs" })
        assertEquals(listOf<String?>("ar,en"), o.get("--sub-langs"))
        assertEquals(listOf<String?>("srt"), o.get("--convert-subs"))
        val ttml = YtDlpArgs.download(sel, spec(subs, SubtitleFormat.TTML), subs, "/w", false)
        assertTrue(ttml.none { it.flag == "--convert-subs" })
    }

    @Test fun subtitlesOnlySkipsMedia() {
        val subs = listOf(SubtitleSpec("ar", false))
        val o = YtDlpArgs.download(null, spec(subs), subs, "/w", false)
        assertTrue(o.any { it.flag == "--skip-download" }); assertTrue(o.none { it.flag == "-f" })
    }

    @Test fun titleNeverReachesYtDlp() {
        val sel = FormatPicker.select(info, spec())
        val o = YtDlpArgs.download(sel, spec().copy(title = "Dangerous \$(rm -rf) title"), emptyList(), "/w", false)
        assertTrue(o.none { (it.value ?: "").contains("Dangerous") })
    }
}

class OutputFilesTest {
    @Test fun picksMergedFileNotFragmentsOrPartials() {
        val f = OutputFiles.resolve(mapOf(
            "media.mp4" to 300L, "media.f137.mp4" to 200L, "media.f140-0.m4a" to 20L,
            "media.mp4.part" to 5L, "media.f251-1.webm.part" to 3L, "media.ar.srt" to 400L, "media.en.vtt" to 0L,
        ))
        assertEquals("media.mp4", f.primary)
        assertEquals(listOf("ar" to "media.ar.srt"), f.subtitles) // empty file ignored
    }

    @Test fun nothingProducedMeansNoPrimary() {
        assertNull(OutputFiles.resolve(mapOf("media.mp4.part" to 10L)).primary)
        assertNull(OutputFiles.resolve(emptyMap()).primary)
    }

    @Test fun autoSubLanguageKeepsSuffix() {
        val f = OutputFiles.resolve(mapOf("media.mp4" to 1L, "media.en-orig.srt" to 10L))
        assertEquals("en-orig", f.subtitles.single().first)
    }

    @Test fun mimeTypesAreHonest() {
        assertEquals("video/mp4", OutputFiles.mimeType("mp4"))
        assertEquals("video/x-matroska", OutputFiles.mimeType("MKV"))
        assertEquals("audio/mp4", OutputFiles.mimeType("m4a"))
        assertEquals("application/x-subrip", OutputFiles.mimeType("srt"))
        assertEquals("application/octet-stream", OutputFiles.mimeType("xyz"))
        assertTrue(OutputFiles.isPlayableMedia("mkv")); assertFalse(OutputFiles.isPlayableMedia("srt"))
    }

    @Test fun subtitleValidation() {
        assertTrue(OutputFiles.isValidSubtitle("srt", "﻿1\n00:00:01,000 --> 00:00:02,500\nمرحبا\n"))
        assertFalse(OutputFiles.isValidSubtitle("srt", "<html>error</html>"))
        assertTrue(OutputFiles.isValidSubtitle("vtt", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nhi"))
        assertFalse(OutputFiles.isValidSubtitle("vtt", "00:00:01.000 --> 00:00:02.000"))
        assertTrue(OutputFiles.isValidSubtitle("ttml", "<?xml version=\"1.0\"?><tt xmlns=\"x\"><body><div><p begin=\"0s\">a</p></div></body></tt>"))
        assertFalse(OutputFiles.isValidSubtitle("ttml", ""))
        assertFalse(OutputFiles.isValidSubtitle("exe", "x"))
    }
}

class CueDedupeTest {
    @Test fun identicalTextSameTimeIsRemoved() {
        val seen = HashSet<CueDedupe.Key>()
        assertEquals(listOf(0), CueDedupe.keep(listOf("مرحبا", "مرحبا"), 1_000_000, 3_000_000, seen))
    }

    @Test fun sameSentenceAtDifferentTimeIsKept() {
        val seen = HashSet<CueDedupe.Key>()
        assertEquals(listOf(0), CueDedupe.keep(listOf("Thank you"), 1_000_000, 2_000_000, seen))
        assertEquals(listOf(0), CueDedupe.keep(listOf("Thank you"), 5_000_000, 6_000_000, seen))
        // different end time only is NOT provably identical either
        assertEquals(listOf(0), CueDedupe.keep(listOf("Thank you"), 5_000_000, 7_000_000, seen))
    }

    @Test fun repeatedGroupWithSameKeyIsRemovedAcrossEmissions() {
        val seen = HashSet<CueDedupe.Key>()
        assertEquals(listOf(0, 1), CueDedupe.keep(listOf("a", "b"), 0, 10, seen))
        assertEquals(emptyList<Int>(), CueDedupe.keep(listOf("a"), 0, 10, seen))
    }

    @Test fun differentTextsAndBitmapsKept() {
        val seen = HashSet<CueDedupe.Key>()
        assertEquals(listOf(0, 1, 2, 3), CueDedupe.keep(listOf("a", "b", null, null), 0, 10, seen))
    }
}
