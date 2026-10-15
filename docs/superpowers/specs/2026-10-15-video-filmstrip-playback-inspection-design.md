# Video Filmstrip Playback Inspection Design

## Goal

Open a video filmstrip frame in a popup that can play from that frame, step exactly one source frame, and distinguish irregular file timestamps from this app's playback delivery delays and frame drops.

## Interaction

- Clicking a filmstrip thumbnail opens the popup paused at that selected frame. The popup starts in playback view, with Space for play/pause and arrows for adjacent frames. It shows one-based frame number, zero-based index, PTS, and the current source PTS interval.
- A button switches to the existing native-resolution still-frame view with pixel zoom; returning to playback starts paused from the selected frame. The selected filmstrip frame updates on pause/seek, not on every playing frame.
- The popup shows the latest UI delivery interval and recorded player drops separately from the source PTS interval. A bounded recent-event list names affected frame indices. UI delivery timestamps are EDT callback times, not hardware display timestamps.
- The existing frame-interval analysis window opens from the popup; selecting an interval seeks the popup to its frame. The window remains pinned to the same tab.
- While popup playback is open, the inline player is unmounted so the same video does not run through two ffmpeg decoders. Closing the popup restores the inline player and cancels popup work.
- AV2 videos retain the existing still-frame popup until their custom decoder supports seekable playback; the popup states this explicitly.

## Data and ownership

Reuse `tab.gopFrames` as the source frame timeline when analysis is complete. Extend `FfmpegVideoPlayer` with an initial seek, optional known timestamps, playback state and drop callbacks. A small pure tracker compares source PTS intervals to UI callback intervals and keeps a bounded event list. Full-resolution decode runs only in still mode. No new dependencies.

## Verification

Unit-test variable-rate PTS, skipped frame counts, delivery delay classification, reset/seek boundaries, and bounded event retention. Run focused ffmpeg/filmstrip tests and the complete app suite. GUI timing and actual display scanout require manual verification.
