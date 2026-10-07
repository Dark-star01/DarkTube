# Phase 4 fix pass (v0.4.1)

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
