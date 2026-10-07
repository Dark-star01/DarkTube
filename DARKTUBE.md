# DarkTube (انظر أيضاً README.md الخاص بأداة APK Maker)

Personal, ad-free, native Android YouTube client (Kotlin + Jetpack Compose). No WebView, no
accounts, no analytics. Built through the **APK Maker 2.0** pipeline (unchanged `.github/`).

**Current phase: 1 + 2 (foundation + extraction).** See `docs/STATUS.md` for exactly what works.

## Build
1. Push this repo to GitHub on `main` (the workflow triggers on `native/**`, `webapp/**`, `config.json`).
2. Download the APK from the run's Artifacts. No Android Studio needed.

`webapp/darktube-stub.zip` is only a placeholder the pipeline requires; the WebView is never shown.
`config.json` pins `capacitorVersion` to **7.6.9** (AGP 8.7.2, Gradle 8.11.1, compileSdk 35). Do not
use `"latest"`: it now resolves to Capacitor 8, which needs a different toolchain than the pipeline's
Kotlin 1.9.24 / Compose 1.5.14 defaults.

## Layout
```
native/android/
  build.gradle, gradle.properties        merged into the generated project (JitPack, bigger heap)
  app/build.gradle                       dependencies, desugaring (merged)
  app/src/main/AndroidManifest.xml       replaces Capacitor's (no BridgeActivity)
  app/src/main/java/__APP_ID_PATH__/
    core/extraction/   engine-neutral models + VideoExtractor interface + StreamCatalog (pure Kotlin)
    core/extraction/newpipe/   the ONLY code that touches NewPipeExtractor
    core/log/          in-memory, URL-redacted diagnostics
    ui/                Compose screens (home, search, video, settings)
  app/src/test/...     unit tests for the pure layer
```
`__APP_ID__` / `__APP_ID_PATH__` are expanded by APK Maker from `config.json`.

## Architecture rule
UI, player, downloads and DB depend only on `VideoExtractor` and the models in `core/extraction`.
To replace or update the engine: change the version in `app/build.gradle` (and `ENGINE_VERSION`),
or add another `VideoExtractor` implementation (e.g. yt-dlp) and swap it in `AppContainer`.

## License note
NewPipeExtractor is GPL-3.0. Fine for a private, undistributed build; if you ever distribute
DarkTube, the whole app must be GPL-3.0-compatible.
