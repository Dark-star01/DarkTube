# Status (honest)

Verified in the authoring sandbox: pipeline integration (real Capacitor 7.6.9 project + all APK Maker steps run) and 64 unit tests for the pure layer (Kotlin 1.9.24). Phase 3 was then confirmed working on a device by the owner. Phase 4 is implemented but its device tests (docs/TESTING-PHASE4.md) have NOT been run yet.

NOT verified here: the full Gradle/Compose build (needs Android SDK + Maven/Google hosts, blocked in
the authoring sandbox) and anything involving live YouTube. The first CI run is the first real compile;
expect the possibility of small compile fixes.

## Feature matrix (spec §36 wording)
| Feature | Status |
|---|---|
| Search, metadata, quality list | Working (device-confirmed) |
| Playback, quality, fullscreen, PiP, background, notification, Bluetooth | Working (device-confirmed, Phase 3) |
| Detecting multiple audio tracks and official dubs | **Supported**: device report showed original + 20 dubs with correct languages and track ids |
| Switching audio track during playback | **Partially supported** by design: implemented as a same-position reload (brief rebuffer). Untested on device |
| Subtitle discovery, manual vs auto-generated labelling | **Supported** (device report: 29 tracks incl. auto-generated) |
| Subtitle rendering and switching | Implemented (TTML/VTT/SRT via Media3, instant switch, no reload). Untested on device |
| Subtitle formats other than TTML/VTT/SRT | Not supported (not offered) |
| Resume from last position | Not working: no persistence exists (see KNOWN-ISSUES) |
| Queue | Not implemented in code (see KNOWN-ISSUES) |
| Downloads, FFmpeg, storage manager | Not built (Phase 5) |
| History, favorites, playlists, Room | Not built (Phase 6) |

## Known risks
- YouTube may answer "confirm you're not a bot" on some networks (mapped to a clear message).
- Spec deviation: `getVideo` returns metadata + streams + tracks in one fetch (one network call).
