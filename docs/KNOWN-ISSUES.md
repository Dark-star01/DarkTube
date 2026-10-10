# Known issues

## Resume playback from last position: NOT WORKING (deferred)
Reported after Phase 3. Root cause found while reading the code: **there is no persisted resume
feature at all yet.** Nothing saves a position when you leave a video, and nothing reads one when
you open it. The only position handling is carrying the position across an in-session quality or
audio-track switch, and that lives in one function: `core/playback/PlayerSession.load()`.

Planned fix (Phase 6, with Room): store `lastPosition` per video id when leaving the player
(and periodically while playing), and have `PlayerSession.load()` take an optional start position
plus the "Resume from 14:32? / Start over" prompt from the spec. Phase 4 does not touch this.

## Queue: not implemented in code
Phase 3 status lists "Queue" as working, but no queue exists in the code: the player holds one
item and leaving the screen clears it. The spec places the queue in Phase 6. If something queue-like
appeared to work on your device, please describe it, because it is not something the app does deliberately.

## Audio switching is a reload, not a seamless swap (Partially supported)
YouTube serves each audio track as a separate file and this engine gives no combined manifest.
Switching track rebuilds the merged source at the same position, so expect a short rebuffer
(video re-buffers too). Loading all tracks at once to switch instantly would open ~20 connections
per video, so it was rejected.

## Subtitles
- Fixed in 0.4.1: selection never worked because Media3 prefixes merged track ids.
- Fixed in 0.4.2: TTML now goes through Media3's modern parser path (see PHASE4-FIX.md, fix #2).
- Cost: all attached subtitle files are downloaded when a video is prepared (see PHASE4-FIX.md).
- Rendering is UNVERIFIED on device. Before a track is selected DarkTube probes its file and reports
  empty / HTML / HTTP-error / wrong-format responses with a specific message. If YouTube requires a
  token for subtitle files, those tracks cannot be shown with the current engine.
- Only formats Media3 can parse are offered: TTML, WebVTT, SRT.
- A subtitle that fails during playback is silent rather than stopping the video.

## Other
- A quality that only exists with built-in audio cannot switch audio tracks (the page says so).
- If the player reports an error right after you pick a new audio track, DarkTube assumes the track
  failed, reverts to the previous one and says "This audio track could not be loaded". A video-stream
  error at that same moment would be reported the same way.

## Phase 5 (downloads)
- NOT verified on device or in CI yet; the authoring sandbox cannot reach Maven/Google hosts.
- Pause kills the yt-dlp process and keeps `.part` files; Resume continues them. If YouTube refuses a
  ranged resume the file restarts from zero (yt-dlp decides). Progress never moves backwards in the UI.
- The foreground service uses type dataSync; Android 15 limits it to ~6 h per day.
- Finished files are copied out of the work folder (needs roughly 2x the file size free while copying).
- Dub audio is selected by yt-dlp's `language` field and never replaced by the original; if yt-dlp does
  not list that language the download fails with "audio track isn't available". Whether a given yt-dlp
  client lists all dubs must be confirmed on device.
- Subtitle tags from the player (NewPipe) are matched to yt-dlp keys exactly; mismatch gives a note
  "Subtitles not available: xx" while the video still downloads.
- APK contains only arm64-v8a and armeabi-v7a (no x86 emulator).
- Android 9 and older store files in the app's own folder (no MediaStore.Downloads).
- Duplicate-cue fix removes only identical text+start+end; the real TTML data was not available, so look
  at the debug log line `parsed ... duplicatesRemoved=N` to see whether it triggered.
