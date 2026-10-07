# Phase 4 device test sheet

Mark each line. Anything that fails: tap Settings > View debug log > Copy, and send it.

## Audio
- [ ] **Single track**: open any ordinary short video (no dub). Page shows `Audio  <language>` as plain text, no dropdown.
- [ ] **Multiple tracks**: open MrBeast `plN7JMbadRg`. `Audio` dropdown lists English (original) first, then the dubs.
- [ ] **Official dub**: pick Arabic (dub). After a short rebuffer you hear Arabic speech; position is about where you were.
- [ ] Switch again (Spanish, then back to English): each time the speech language actually changes.
- [ ] Picking a track while paused keeps it paused.
- [ ] The dropdown label always shows the track you're hearing (also after rotating / fullscreen and back).
- [ ] Quality 360p: both audio and video switch fine. (If a height exists only muxed, the page shows the "built-in audio only" note.)
- [ ] Try 2 or 3 other videos with dubs; note which have none.

## Subtitles
- [ ] **None**: open a video without captions. Row reads `Subtitles  None available`.
- [ ] **Multiple**: MrBeast video lists 29 entries incl. `English (auto-generated)` and disambiguated names (English (Canada), Portuguese (Brazil), Chinese (...)).
- [ ] Choose Arabic: captions appear within a second or two, text is Arabic, timing matches speech.
- [ ] Switch to English while playing: video does NOT restart or rebuffer; captions change.
- [ ] Choose `English (auto-generated)`: appears (and is labelled as auto-generated).
- [ ] Off: captions disappear.
- [ ] Seek forward/back with captions on: still in sync.
- [ ] Long video (an hour) and a short one.
- [ ] CC button inside the player (also in fullscreen) lists the same tracks; choosing there updates the page dropdown.
- [ ] Captions stay selected after changing quality; after changing audio track (note if they reset).

## Regression
- [ ] Normal playback, seek, speed (player gear)
- [ ] Quality menu changes resolution and keeps position
- [ ] Fullscreen enter/exit (button + back)
- [ ] PiP (Home while playing); closing the PiP window stops playback
- [ ] Background audio with screen locked; notification play/pause/seek
- [ ] Bluetooth / headset play-pause
- [ ] Leaving the video screen stops playback
