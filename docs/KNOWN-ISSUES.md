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
- Only formats Media3 can parse are offered: TTML, WebVTT, SRT. On the MrBeast test video every
  track is TTML. A track available only in another format is not listed in the dropdown (it still
  appears in the Stream inspector).
- A subtitle that fails to download is silent (no captions) instead of stopping the video; there is
  no on-screen error for that case.
- The player's own CC button and the Subtitles dropdown on the page both work and stay in sync.

## Other
- A quality that only exists with built-in audio cannot switch audio tracks (the page says so).
- If the player reports an error right after you pick a new audio track, DarkTube assumes the track
  failed, reverts to the previous one and says "This audio track could not be loaded". A video-stream
  error at that same moment would be reported the same way.
