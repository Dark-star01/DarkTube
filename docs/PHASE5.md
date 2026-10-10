# Phase 5: Download system (0.5.0)

## Architecture
UI (video page dialog, Downloads tab, local player) -> `DownloadManager` (queue, state machine, retry)
-> `DownloadEngine` (`YtDlpEngine`, yt-dlp via youtubedl-android) -> `DownloadStorage` (work dir ->
MediaStore Downloads/DarkTube or chosen SAF folder). `DownloadService` is a foreground service
(dataSync) that keeps the process alive and shows "DarkTube / Downloading: <title>" + progress.
Pure logic (unit tested): `NamePolicy`, `YtInfoParser`, `FormatPicker`, `YtDlpArgs`, `ProgressTracker`,
`DownloadError`/`RetryPolicy`, `OutputFiles`, `CueDedupe`.

## Flow of one attempt
1. `yt-dlp --dump-single-json` (fresh URLs every attempt, so expired links are refreshed).
2. `FormatPicker` chooses explicit format ids: exact quality (never silently lower), AVC+AAC -> mp4,
   otherwise mkv; requested dub must exist or the job fails (never falls back to original).
3. `yt-dlp -f <video>+<audio> --merge-output-format ...` into `work/<id>/media.*`; FFmpeg is called by
   yt-dlp only to merge and to convert subtitles (`--convert-subs srt|vtt`).
4. Output resolved, subtitles validated, files copied to the final folder with `Title.ext`
   (`Title (2).ext` on a real collision), work files deleted.

## Room schema (v1, table `downloads`)
id, videoId, title, thumbnailUrl, kind, requestedHeight, qualityLabel, audioLanguage, audioKind,
audioLabel, subtitleSpecs, subtitleFormat, state, progress, downloadedBytes, totalBytes, speedBps,
etaSeconds, attempts, createdAt, completedAt, errorKind, errorMessage, note, destinationUri,
destinationName, mimeType, subtitleFiles, resolvedHeight, containerExt. No media bytes.

## Recovery
On app/service start rows left as DOWNLOADING become QUEUED and resume from partial files.

## Device test sheet (run on Infinix X6525)
1. 1080p single video: progress, speed, ETA move; file plays from Downloads tab.
2. 720p and 480p: Downloads row shows the resolved height; check resolution in a player.
3. Audio only: plays.
4. Arabic dub (video with an Arabic track): listen to the downloaded file.
5. Arabic subtitle download: open the .srt, check timing and text.
6. Minimize the app mid-download: notification stays, progress continues.
7. Pause then Resume: continues from the same percentage, final file plays.
8. Turn on airplane mode mid-download: ends Failed after retries; turn off, press Retry.
9. Download the same video twice: `Title.mp4` then `Title (2).mp4`.
10. Kill the app from recents mid-download, reopen: row resumes/queued, no duplicate rows.
Regression: playback, original audio, dubs, subtitles (x-media3-cues), spinner while switching
quality/audio, subtitle shown once (Settings -> Debug log, tag Subtitles).
