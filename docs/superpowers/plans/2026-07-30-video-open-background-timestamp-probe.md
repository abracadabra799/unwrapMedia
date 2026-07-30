# Video Open: Background Frame-Timestamp Probe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop blocking the video player UI on a full-file frame-timestamp scan, so a video tab becomes interactive as soon as the cheap stream probe finishes, instead of freezing on "동영상 정보 분석 중..." for however long the file's full timestamp scan takes.

**Architecture:** Split the single `LaunchedEffect(file)` in `FfmpegVideoPlayer` into two sequential awaits within the same coroutine: flip `probing = false` immediately after the cheap `probeVideo` call resolves, then continue awaiting the expensive `probeFrameTimestamps` scan in the background, assigning its result to `frameTimestamps` whenever it completes.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop (`LaunchedEffect`, `withContext(Dispatchers.IO)`), ffprobe via `ProcessBuilder` (unchanged, already implemented).

## Global Constraints

- No new state variables, no new `LaunchedEffect`, no size/resolution/duration threshold -- applies uniformly to every video (per spec's Non-Goals section).
- `DisposableEffect(file, restartTrigger)` (the block that spawns the real ffmpeg playback process) is NOT modified by this plan -- it already reads `frameTimestamps` once at start and already has a correct average-fps fallback (`fallbackDurationSeconds = 1.0 / info.fps`) for when it's `null`. No hot-swap of `durations` mid-playback (per spec, rejected as YAGNI).
- `probedInfo`/`frameTimestamps`/`probing` keep their exact current names, types, and `remember(file)` scoping -- only the `LaunchedEffect(file)` body's sequencing changes.

---

### Task 1: Decouple the background timestamp probe from the `probing` gate

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt:276-283`

**Interfaces:**
- Consumes: `probeVideo(file: File): VideoInfo?` and `probeFrameTimestamps(file: File): List<Double>?` (both already exist, unchanged, defined earlier in the same file).
- Produces: no new public interface -- this is a body-only change to the existing `LaunchedEffect(file)` block inside the `FfmpegVideoPlayer` composable. `probedInfo`, `frameTimestamps`, and `probing` (all `remember(file) { mutableStateOf(...) }` at lines 270/273/274, unchanged) keep their existing names and types for the rest of the composable (the `if (probing) { ...; return }` gate at line 285, and `DisposableEffect`'s read of `frameTimestamps` at line 347) to keep consuming.

This is a single-file, single-function-body change with no automated test possible for the actual defect (Compose `LaunchedEffect` state-flip *timing* isn't something this project's test setup -- plain `kotlin.test` JVM unit tests, no Compose UI test dependency -- can observe; there is no prior precedent of testing composable state sequencing anywhere in this codebase). Verification here is: (1) the full existing suite must stay green, since `probeVideo`/`probeFrameTimestamps` themselves are untouched, so this is a pure regression check; (2) a source-level structural check that `probing = false` now appears before the `probeFrameTimestamps` call in the function body, not after; (3) manual verification by opening a real, longer video and confirming the player becomes interactive immediately instead of sitting on "동영상 정보 분석 중..." for the full scan duration.

- [ ] **Step 1: Read the current block to confirm line numbers before editing**

Run: `grep -n "LaunchedEffect(file)" -A8 app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt`

Expected output (confirms the exact current text this task edits):
```kotlin
    LaunchedEffect(file) {
        probing = true
        val info = withContext(Dispatchers.IO) { probeVideo(file) }
        val timestamps = if (info != null) withContext(Dispatchers.IO) { probeFrameTimestamps(file) } else null
        probedInfo = info
        frameTimestamps = timestamps
        probing = false
    }
```

If the surrounding text differs from this (e.g. the file has changed since this plan was written), stop and re-read the full file before proceeding -- do not guess at the edit.

- [ ] **Step 2: Replace the block**

Find this exact text in `app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt`:

```kotlin
    LaunchedEffect(file) {
        probing = true
        val info = withContext(Dispatchers.IO) { probeVideo(file) }
        val timestamps = if (info != null) withContext(Dispatchers.IO) { probeFrameTimestamps(file) } else null
        probedInfo = info
        frameTimestamps = timestamps
        probing = false
    }
```

Replace it with:

```kotlin
    LaunchedEffect(file) {
        probing = true
        val info = withContext(Dispatchers.IO) { probeVideo(file) }
        // Flip probing off (and let the player UI render) as soon as this cheap probe resolves --
        // do not wait on the expensive full-file frame-timestamp scan below. The player already
        // has a correct average-fps pacing fallback (nextFrameDurationSeconds's
        // fallbackDurationSeconds) for whenever frameTimestamps is still null, the same fallback
        // already used today if probeFrameTimestamps fails outright. Continuing to await it here,
        // in the same coroutine, still updates frameTimestamps once it completes -- the next
        // replay or seek (both already restart DisposableEffect) picks up the more precise
        // per-frame durations automatically; an in-flight playthrough does not hot-swap mid-play.
        probedInfo = info
        probing = false
        if (info != null) {
            frameTimestamps = withContext(Dispatchers.IO) { probeFrameTimestamps(file) }
        }
    }
```

- [ ] **Step 3: Verify the edit landed correctly**

Run: `grep -n "LaunchedEffect(file)" -A12 app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt`
Expected: the new 12-line block above, with `probing = false` appearing before the `if (info != null)` block that awaits `probeFrameTimestamps`.

- [ ] **Step 4: Compile**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL` (this is a body-only edit inside an existing composable using only already-imported symbols -- `withContext`, `Dispatchers.IO`, `probeVideo`, `probeFrameTimestamps` are all already imported/defined in this file, so no import changes are needed).

- [ ] **Step 5: Run the full test suite (regression check)**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: `BUILD SUCCESSFUL`, same pass count as before this change (this task does not add or remove any test -- `probeVideo`/`probeFrameTimestamps` are unchanged functions, so their existing unit tests in `FfmpegVideoPlayerTest.kt` are unaffected).

- [ ] **Step 6: Manual verification**

Build and run the app (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew run`), open a real video that is long enough or has enough frames that `probeFrameTimestamps` takes a noticeable amount of time (a video at least ~30-60 seconds long is a reasonable test case). Confirm:
- The player shows the first frame and becomes interactive (Play button clickable) quickly, without sitting on "동영상 정보 분석 중..." for the full scan duration.
- Pressing Play immediately still plays back at a reasonable pace (average-fps fallback), not garbled or frozen.
- Closing and reopening the same tab, or replaying after the file has been open a while (letting the background scan finish), still works correctly.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt
git commit -m "Stop blocking video playback UI on the full-file frame-timestamp scan"
```

---
