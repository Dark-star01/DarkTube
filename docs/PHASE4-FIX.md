# Phase 4 fix passes (v0.4.1, v0.4.2)

Written honestly: the authoring sandbox cannot build an APK or run a device. Everything below marked
VERIFIED was confirmed by reading the Media3 1.5.1 source or by unit tests. Everything marked
UNVERIFIED needs the new debug log from the device.

## Subtitle selection failure ("This subtitle track could not be loaded") : ROOT CAUSE VERIFIED
`MergingMediaPeriod` rewrites each merged track's `Format.id` to `"<childIndex>:" + id`
(MergingMediaPeriod.java line 295). DarkTube looked for the exact id `sub:ar:manual`, but the player
holds `3:sub:ar:manual`, so no track ever matched and the error appeared before any download.
Fix: `SubtitlePlanner.originalId()` strips the prefix; used by `selectSubtitle` and `selectedSubtitleId`
(`PlayerTracks.kt`). Unit-tested.

## "Is this stream already loaded?" bug : ROOT CAUSE VERIFIED
`MediaItem.toBundle()` omits `localConfiguration` (MediaItem.java line 2398) while keeping
`requestMetadata` (line 2382). Controllers therefore always see `localConfiguration == null`, so
`PlayerSession` never recognised an already-loaded stream and reloaded on every effect re-run.
Fix: both URLs are stored in `requestMetadata.extras` and compared there (`PlayerSession.kt`,
`MediaItems.KEY_VIDEO_URL`).

## Playback error at 0:10 on a video with a single audio track : CAUSE NOT YET KNOWN
The screenshot shows the generic message, meaning the error code was none of: HTTP status, network
failure, decoder failure. The previous build did not record the code, so it cannot be named honestly.
Note this video has no dubbed track, so this failure is NOT specific to dubs.
The new build logs the exact code, failing renderer, full cause chain, HTTP status and the failing
stream (itag/mime/client, never signatures): see `PlayerDiagnostics`.

## Dubbed audio : UNVERIFIED
In the MrBeast screenshot the dropdown still reads "Arabic (dub)" with a frame on screen; on an audio
failure DarkTube reverts the dropdown to the previous track, which did not happen. So the dub may have
loaded and the only visible error was the subtitle one. Please confirm by ear. The log now records the
audio stream used (itag, mime, `xtags` dub tag).

## Subtitle rendering after selection works : UNVERIFIED, one likely risk
Before selecting a track the app now downloads its first bytes (same user agent as the player) and
reports status / content type / real format (`SubtitleProbe`). If YouTube returns an empty file or an
HTTP error (a known risk: timedtext increasingly needs a proof-of-origin token that this engine does
not supply), the page now says exactly that instead of a generic failure.

## Send me, for any failure
Settings > View debug log > Copy. Lines of interest: `Player:` (error code + cause chain),
`Subtitle: probe ...` (status, content type, first characters), `subtitle loaded ...`.


---

# Fix #2 (v0.4.2): TTML reached the renderer as `application/ttml+xml`

## Exact root cause: VERIFIED on device log + Media3 1.5.1 source
Device: `IllegalStateException: Legacy decoding is disabled, can't handle application/ttml+xml samples
(expected application/x-media3-cues)`, error 1004, repeating across audio/quality/video changes.

`MergingMediaSourceFactory` (v0.4.1) removed the subtitles from the MediaItem and built
`SingleSampleMediaSource`s itself. That is Media3's legacy path: it delivers the raw TTML bytes with
`sampleMimeType = application/ttml+xml`. In 1.5.1 the TextRenderer has legacy decoding disabled by default and
only accepts parsed cues (`application/x-media3-cues`). The error persisted after switching video because the
text selection override stayed in the player and the new source was built the same wrong way.
Why my first design missed it: I bypassed `DefaultMediaSourceFactory` to get failure tolerance, and so also
bypassed the step that makes subtitles parse.

## Exact Media3 1.5.1 architecture now used (all signatures read from the 1.5.1 tag)
`DefaultMediaSourceFactory` (default `parseSubtitlesDuringExtraction = true`), for each
`MediaItem.SubtitleConfiguration`: builds a `Format` (mime, language, label, id, flags), then
`ProgressiveMediaSource.Factory(dataSource, () -> SubtitleExtractor(DefaultSubtitleParserFactory.create(format), format))`
with `setSuppressPrepareError(true)`, merged with the video in a `MergingMediaSource`.
`DefaultSubtitleParserFactory` supports TTML (`TtmlParser`), WebVTT, SubRip. Output to the renderer is
`application/x-media3-cues`. DarkTube now passes the subtitle configurations to this factory unchanged and only
wraps the result with the separate audio stream (`MergingMediaSourceFactory`).
The MIME declared on each SubtitleConfiguration stays the TRUE MIME of the downloaded file
(`application/ttml+xml`); it is the input to the parser, not what the renderer receives.
Legacy decoding was NOT enabled. A custom parser pipeline was not needed.
`setSuppressPrepareError` is package-private in 1.5.1, so DarkTube cannot copy Media3's construction; it relies on
the factory instead.

## Safety net added
If any player error happens while a subtitle track is active, DarkTube turns subtitles off, re-prepares at the
same position and says so (once per selection). Video playback is not abandoned for a subtitle problem.

## New diagnostics
- `Player: source: merged-audio=.. subtitles=N parser=DefaultMediaSourceFactory(SubtitleExtractor -> application/x-media3-cues)`
- `Player: text tracks=N sampleMime=[...] selected=[...]`: the sample MIME the renderer really gets.
  Expected `[application/x-media3-cues]`. If `application/ttml+xml` ever appears here, the legacy path is back.
- `Player: subtitle file loaded youtube/api ... status contentType bytes`
- `Subtitle: probe ...` (declared vs actual content, as before)

## Known cost of this design (decision for you)
Media3 downloads EVERY attached subtitle file when the video is prepared (ProgressiveMediaPeriod starts
loading on prepare). With 29 tracks on a one-hour video that is 29 small requests and likely several MB of data
at the start of every video, even if you never open subtitles. Options if that bothers you:
(a) attach only some tracks (e.g. device language + English), (b) attach a track only when chosen (reloads at
the same position, short rebuffer). Not changed yet because it alters the "instant switching" behaviour.

## Not verified here
Everything on device. The sandbox cannot build or run the app. Test sheet: docs/TESTING-PHASE4.md.
