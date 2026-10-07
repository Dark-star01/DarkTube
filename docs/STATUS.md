# Status (honest)

Verified here: pipeline integration (real Capacitor 7.6.9 project + all APK Maker steps run; merged
Gradle/manifest inspected) and 39 unit tests for the pure layer (compiled with Kotlin 1.9.24).

NOT verified here: the full Gradle/Compose build (needs Android SDK + Maven/Google hosts, blocked in
the authoring sandbox) and anything involving live YouTube. The first CI run is the first real compile;
expect the possibility of small compile fixes.

## Feature matrix (spec §36 wording)
| Feature | Status |
|---|---|
| Search (videos, paging) | Implemented, untested against live YouTube |
| Video metadata | Implemented, untested live |
| Quality list from real streams | Implemented; logic unit-tested; live untested |
| Multiple audio tracks / dub detection | Implemented from NewPipe's track id/locale/type fields; logic unit-tested; **live untested. Use "Copy report" on a known dubbed video and record the result** |
| Subtitle tracks (manual vs auto) | Implemented; logic unit-tested; live untested |
| Playback, PiP, background, queue | Not built (Phase 3) |
| Audio/subtitle switching in player | Not built (Phase 4) |
| Downloads, FFmpeg, storage manager | Not built (Phase 5) |
| History, favorites, playlists, Room | Not built (Phase 6) |
| Settings | Engine info + debug log only |

## Known risks
- YouTube may answer "confirm you're not a bot" on some networks (mapped to a clear message).
- Spec deviation: `getVideo` returns metadata + streams + tracks in one fetch (one network call).
