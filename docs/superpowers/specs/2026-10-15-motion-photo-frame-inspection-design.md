# Motion Photo Frame Inspection Design

## Goal

Find the exact embedded-video frame around a visible motion-photo playback hitch, and distinguish a timestamp gap in the file from a player-only delay.

## UI and behavior

- The embedded-video pane gains a bilingual popup action. The popup plays the same extracted embedded video at a useful size; the inline player is unmounted while it is open so two ffmpeg decoders do not run for the same file.
- The popup displays a one-based frame number, total frame count when known, and the actual presentation timestamp (PTS). Left/right arrows pause playback and move exactly one entry through the probed frame timestamp list, including variable-rate clips and boundaries. A short loading state is shown until the timestamp probe finishes; no FPS-based approximation is labeled an exact frame.
- The popup offers the existing motion-photo frame-interval window. Selecting an interval row jumps the popup to that frame's PTS. Its analysis stays pinned to the originating tab.
- Closing the popup or switching tabs releases its player process. The extracted temporary file remains owned by the preview and is deleted when the preview leaves composition.

## Data flow

`FfmpegVideoPlayer` reports the displayed frame's zero-based source index and PTS using its probed timestamp list. The popup owns the requested frame index and sends the corresponding PTS through the player's existing seek request. The interval window sends a selected `FrameInfo` back to the popup. No new video parser or dependency is added.

## Verification

Test exact adjacent-frame selection on variable-rate timestamps, first/last boundaries, and interval-to-PTS mapping. Run existing ffmpeg video-player and frame-interval tests, then the full app test suite. Manual GUI playback on macOS/Windows is a separate visual check.
