# Dependencies (each one has a job)

| Dependency | Why |
|---|---|
| Compose BOM 2024.06.00 / material3 / activity-compose | UI (added by APK Maker's `compose` step) |
| navigation-compose 2.7.7 | screen navigation |
| lifecycle-runtime-compose / viewmodel-compose 2.8.3 | state collection, ViewModels |
| coil-compose 2.7.0 | thumbnails |
| kotlinx-coroutines-android 1.8.1 | async extraction |
| NewPipeExtractor v0.26.5 (JitPack) | YouTube extraction (own signature/`n` solving, no JS runtime needed) |
| okhttp 4.12.0 | HTTP bridge the extractor requires |
| desugar_jdk_libs_nio 2.1.5 | required by the extractor on minSdk < 33 |
| junit 4.13.2 (test) | unit tests |

Deliberately NOT added yet (added with the phase that needs them): Media3, Room (+kapt), WorkManager,
FFmpeg, yt-dlp.
