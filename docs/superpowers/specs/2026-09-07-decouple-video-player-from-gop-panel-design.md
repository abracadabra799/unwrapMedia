# Decouple the Live Video Player from the GOP / Filmstrip Panel — Design

**Date:** 2026-09-07
**Status:** Approved

## Problem

In the video inspector, the left "LIVE PLAYER" (`FfmpegVideoPlayer`) and the right
GOP-analysis / frame-thumbnail-filmstrip panel are cross-wired through shared `tab`
state:

- `onElapsedChanged` pushes the player's playback position into
  `tab.playbackElapsedSeconds`, which the right panel reads.
- `seekRequestSeconds` / `seekRequestTick` let a frame click in GOP / the filmstrip /
  the frame popups seek the player.
- `onStepFrame` makes the player's arrow keys drive `tab.selectedFrame` and issue a
  seek, so single-frame stepping in the player walks the GOP frame list.

The user wants the two to operate independently: playing, seeking, or frame-stepping
the left player must not move the right panel, and vice versa.

## Change

All the wiring lives at one call site — the `FfmpegVideoPlayer(...)` invocation in
`VideoInspectorUI.kt`. Remove `onElapsedChanged`, `seekRequestSeconds`,
`seekRequestTick`, and `onStepFrame` from that call. Keep `onProbeComplete` (it only
flips `tab.videoReadyForAnalysis` to enable the "analyze frames" button — not
interactive linkage).

`FfmpegVideoPlayer` already has internal fallbacks for exactly this standalone mode
(the `ImageInspectorUI` call site already uses it with none of these params):

- progress-bar drag seek → internal `startFromSeconds` + `restartTrigger`
- Left/Right arrow → `stepSingleFrame`'s `onStepFrame == null` branch, which steps
  by the player's own probed per-frame `frameTimestamps` list

So no change to `FfmpegVideoPlayer` itself. Its now-unused `onElapsedChanged` /
`seekRequest*` / `onStepFrame` parameters keep their harmless defaults (no-op / 0 /
null) and stay on the signature — they match how `ImageInspectorUI` already calls it
and may be reused later.

## Result

- **Left player:** self-contained. Play / pause / progress-bar seek / arrow-key
  single-frame step all act on its own ffmpeg pipe and state only.
- **Right panel** (GOP view, filmstrip, `FrameFullSizePreviewWindow`,
  `CodecViewPreviewWindow`): frame selection still updates `tab.selectedFrame`, which
  drives the detail panel, the hex viewer jump, and the codec-view preview — none of
  which is the player. Their `tab.seekTargetSeconds = …; tab.seekRequestTick++`
  writes become inert (nothing consumes them now); left in place to keep the change
  to a single file.
- `tab.playbackElapsedSeconds` is no longer written by anything and no longer read by
  anything; left as a dead field for now.

## Non-goals

- Removing the dead `seekTargetSeconds` / `seekRequestTick` / `playbackElapsedSeconds`
  state or the dead writes in GOP / filmstrip / popups (separate cleanup, larger
  blast radius).
- Changing `FfmpegVideoPlayer`'s parameter list.

## Testing

No behavioral unit tests exist for this composition wiring. Verify by build + the
existing suite (must stay green) and a manual check: in the video inspector, play /
seek / arrow-step the left player and confirm the GOP timeline selection and
filmstrip highlight do not move; click frames in the GOP / filmstrip and confirm the
left player does not jump.

## Files

- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt` (the
  `FfmpegVideoPlayer(...)` call — ~8 lines removed)

## Global constraints

- Kotlin 2.0.21; do not touch `app/build.gradle.kts`.
- Commit message ends with:
  `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo`
