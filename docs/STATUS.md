# Status (honest)

Verified here: pipeline integration (real Capacitor 7.6.9 project + all APK Maker steps run; merged
Gradle/manifest inspected) and 39 unit tests for the pure layer (compiled with Kotlin 1.9.24).

NOT verified here: the full Gradle/Compose build (needs Android SDK + Maven/Google hosts, blocked in
the authoring sandbox) and anything involving live YouTube. The first CI run is the first real compile;
expect the possibility of small compile fixes.

## Feature matrix (spec §36 wording)
| Feature | Status |
|---|---|
| Search (videos, paging), video metadata | Working (confirmed on device) |
| Quality list from real streams | Working (confirmed on device) |
| Multiple audio tracks / official dubs | **Supported by the engine** (confirmed: MrBeast video returned original + 20 dubs, each with its own track id) |
| Subtitle tracks (manual vs auto) | Listed correctly (29 tracks, TTML only on that video) |
| Playback (merged video-only + audio-only), speed, seek | Implemented, **untested on device yet** |
| Quality selection (Auto = best up to 1080p, or a specific height) | Implemented, untested |
| Fullscreen, Picture-in-Picture | Implemented, untested |
| Background audio, notification, headset/Bluetooth buttons | Implemented (MediaSessionService), untested |
| Live (HLS) | Implemented, untested |
| Audio-track switching in the player, subtitle rendering | Not built (Phase 4); the planner already supports choosing a track |
| Downloads, FFmpeg, storage manager | Not built (Phase 5) |
| History, resume, favorites, playlists, queue, Room | Not built (Phase 6) |

## Known limitations of Phase 3
- "Auto" is not bandwidth-adaptive: ordinary YouTube videos only expose separate progressive files here, so Auto = best up to 1080p.
- Leaving the video screen stops playback; minimizing the app keeps it playing.
- Quality menu is on the video page; fullscreen only has the player's own controls (speed etc.).
- Notification has play/pause/seek; a dedicated Stop button is not added yet.
- googlevideo may refuse streams (HTTP 403). `core/playback/YoutubeDataSourceFactory` mimics NewPipe's request style (POST + `rn`, desktop UA); if playback fails, copy the debug log from Settings.

## Known risks
- YouTube may answer "confirm you're not a bot" on some networks (mapped to a clear message).
- Spec deviation: `getVideo` returns metadata + streams + tracks in one fetch (one network call).
