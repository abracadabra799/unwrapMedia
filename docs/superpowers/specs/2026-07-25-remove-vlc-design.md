# Remove VLC/vlcj, Migrate to FfmpegVideoPlayer (Sub-project C of VLC Removal) — Design

## Background

Sub-project A built `FfmpegVideoPlayer` (same signature as `VlcVideoPlayer`: `(file: File, modifier: Modifier = Modifier)`), sub-project B bundled `ffmpeg`/`ffprobe` into the Windows/Linux packages. `VlcVideoPlayer` still has exactly two call sites (`VideoInspectorUI.kt`, `ImageInspectorUI.kt`'s `MotionPhotoVideoPreview`) and one Gradle dependency (`uk.co.caprica:vlcj:4.12.1`) — confirmed by a full-codebase search for `vlcj`/`VlcVideoPlayer`/`uk.co.caprica`, which returns exactly these two call-site files plus `VlcVideoPlayer.kt` itself and `build.gradle.kts`. No test file references it.

This migration is motivated by a real, still-unexplained Windows regression: video playback reportedly worked on an earlier Windows build (before today's other changes) but failed (mp4 hung indefinitely; the Motion Photo panel's embedded player also never rendered) on today's build. No confirmed root cause was found in `VlcVideoPlayer.kt`'s own code (reviewed, no obvious Windows-specific change), but continued reliance on a native library with environment-dependent, hard-to-diagnose-remotely failure modes is itself the underlying problem — not any one specific bug. Migrating to the already-built, already-tested `FfmpegVideoPlayer` removes libvlc from the picture entirely for playback, the same way it was already removed for HEIC decoding and video probing.

## Goal

`VideoInspectorUI` and the Motion Photo video panel both use `FfmpegVideoPlayer` instead of `VlcVideoPlayer`. `VlcVideoPlayer.kt` and the `vlcj` Gradle dependency are deleted entirely — nothing in the app depends on libvlc/VLC being installed anywhere, on any platform, for any feature.

## Non-Goals

- No behavior change beyond the decoder swap — layout, labels ("LIVE PLAYER", "MOTION PHOTO VIDEO"), and surrounding UI are untouched.
- No investigation into *why* the Windows VLC regression happened — moot once VLC is removed from this code path entirely.
- No change to `FfmpegVideoPlayer.kt` itself (sub-project A, already built/tested) or `FfmpegLocator`/bundling (sub-project B) — this migration only changes what calls it.

## Design

Two one-line call-site swaps (same package, `com.multiviewer.ui`, no import changes needed):

`VideoInspectorUI.kt`: `VlcVideoPlayer(tab.file)` → `FfmpegVideoPlayer(tab.file)`.

`ImageInspectorUI.kt`'s `MotionPhotoVideoPreview`: `VlcVideoPlayer(file, modifier = Modifier.fillMaxSize())` → `FfmpegVideoPlayer(file, modifier = Modifier.fillMaxSize())`.

Then delete `app/src/main/kotlin/com/multiviewer/ui/VlcVideoPlayer.kt` and remove the `implementation("uk.co.caprica:vlcj:4.12.1")` line from `app/build.gradle.kts`'s `dependencies { }` block.

## Testing

- No automated test exists for either call site's Composable (established convention for this project's Compose-layer files) — verification is the full test suite compiling cleanly after the `vlcj` dependency is removed (proves nothing else in the codebase secretly depended on it) plus manual checks.
- Manual: open a standalone video file — confirm `VideoInspectorUI`'s "LIVE PLAYER" panel plays it via ffmpeg (decoding placeholder, then play/pause working) with no VLC-related console output at all. Open a Motion Photo file — confirm the "MOTION PHOTO VIDEO" panel plays the extracted clip the same way. Both checks should be run on macOS first (fast local iteration), then — the real point of this migration — on the same Windows machine that had the VLC regression, with **neither VLC nor a system ffmpeg install present**, relying solely on the bundled binaries from sub-project B.
