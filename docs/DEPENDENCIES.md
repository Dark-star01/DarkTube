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
| media3-exoplayer / -session / -ui 1.5.1 | playback, MediaSession service + notification, PlayerView |
| media3-exoplayer-hls 1.5.1 | live streams (HLS) |
| guava 33.3.1-android | `ListenableFuture` returned by `MediaController.buildAsync()` |
| junit 4.13.2 (test) | unit tests |

Added in Phase 5 (0.5.0):
| io.github.junkfood02.youtubedl-android library + ffmpeg 0.18.1 (GPL-3.0) | yt-dlp, Python, QuickJS, FFmpeg 7.0.1 (merge/convert only). Needs minSdk 24, extracted native libs, abiFilters arm64-v8a + armeabi-v7a |
| androidx.room 2.6.1 (runtime, ktx, kapt compiler) | persistent download queue (metadata only) |
| androidx.documentfile 1.0.1 | writing into a user-chosen folder (SAF) |
| org.json:json (test) | android.jar's org.json is a stub in unit tests |

Still NOT added: WorkManager, a second HTTP stack, any analytics.
