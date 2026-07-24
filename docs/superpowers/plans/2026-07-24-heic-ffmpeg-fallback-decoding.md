# HEIC/HEVC Primary Image Decoding via ffmpeg Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the VLC-based HEIC/HEVC fallback decoder (implemented earlier today, but confirmed via manual end-to-end testing to never actually work — this machine's VLC build has no HEIF demuxer at all) with a `ffmpeg`-subprocess-based decoder, verified to correctly decode both simple and grid-tiled real-world HEIC files.

**Architecture:** `FfmpegImageSnapshotDecoder` replaces `VlcImageSnapshotDecoder` with the same public contract (`decodeFirstFrameAsync(file: File, onResult: (ImageBitmap?) -> Unit)`, fires exactly once on the AWT event thread) but a much simpler implementation: shell out to `ffmpeg -y -i <file> -frames:v 1 -update 1 <tempPng>` via `ProcessBuilder`, wait synchronously (on a background thread) with a timeout, then decode the resulting PNG through Skia — the same decoder every other supported image format in this app already uses. Everything else from the original plan (`ImageForensicData` fields, `AppState.openFile` wiring, the UI loading state) is already implemented, reviewed, and merged — this plan only swaps the decoder and updates its two call sites.

**Tech Stack:** Kotlin 2.2.20, Compose Multiplatform Desktop, `org.jetbrains.skia` (existing), `kotlin.test`. No new Gradle dependency — `ffmpeg` is invoked as an external process, not a JVM library.

## Global Constraints

- `ffmpeg` must be resolved from the system `PATH` — no bundling into the packaged app in this iteration (see spec's Non-Goals).
- Timeout: 8000 ms (`TIMEOUT_MS`) — not 5000 ms. Measured: a real 4000×2252, 40-tile grid HEIC took ffmpeg 1.44 s on this machine; 8 s leaves headroom for slower hardware.
- `onResult` fires exactly once, always on the AWT event thread (`EventQueue.invokeLater`), matching the existing app convention.
- Temp PNG file is always deleted after use (in a `finally` block), with `deleteOnExit()` as a backstop.
- All failure modes (ffmpeg not on PATH, non-zero exit, timeout) converge on `onResult(null)` — no differentiated error messaging in the UI (unchanged non-goal from the original plan).
- Spec: `docs/superpowers/specs/2026-07-24-heic-vlc-fallback-decoding-design.md` (same file as the original plan — updated in place to document this revision; filename kept for continuity even though the design is now ffmpeg-based).

---

### Task 1: Replace `VlcImageSnapshotDecoder` with `FfmpegImageSnapshotDecoder`

**Files:**
- Delete: `app/src/main/kotlin/com/multiviewer/ui/VlcImageSnapshotDecoder.kt`
- Delete: `app/src/test/kotlin/com/multiviewer/ui/VlcImageSnapshotDecoderTest.kt`
- Create: `app/src/main/kotlin/com/multiviewer/ui/FfmpegImageSnapshotDecoder.kt`
- Create: `app/src/test/kotlin/com/multiviewer/ui/FfmpegImageSnapshotDecoderTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `FfmpegImageSnapshotDecoder.decodeFirstFrameAsync(file: File, onResult: (ImageBitmap?) -> Unit): Unit` — Task 2 calls this (replacing its old call to `VlcImageSnapshotDecoder.decodeFirstFrameAsync`, same signature).

This task requires `ffmpeg` to be installed and on `PATH` in the environment running it (already confirmed installed on this machine via `brew install ffmpeg`, version 8.1.2) — matching this codebase's existing convention of testing native-dependency-based code against the real dependency rather than mocking it (see `VlcVideoPlayer.kt`, untested, and the original `VlcImageSnapshotDecoderTest.kt`, tested against real `libvlc`).

- [ ] **Step 1: Remove the VLC-based decoder and its test**

```bash
git rm app/src/main/kotlin/com/multiviewer/ui/VlcImageSnapshotDecoder.kt app/src/test/kotlin/com/multiviewer/ui/VlcImageSnapshotDecoderTest.kt
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/kotlin/com/multiviewer/ui/FfmpegImageSnapshotDecoderTest.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FfmpegImageSnapshotDecoderTest {
    @Test
    fun `decodeFirstFrameAsync delivers null for a file ffmpeg cannot decode, within the timeout window`() {
        val file = File.createTempFile("ffmpeg-snapshot-garbage-test", ".heic")
        file.deleteOnExit()
        file.writeBytes(ByteArray(300)) // not a real media file — ffmpeg will exit non-zero quickly

        val latch = CountDownLatch(1)
        var result: ImageBitmap? = ImageBitmap(1, 1) // sentinel, overwritten by onResult

        FfmpegImageSnapshotDecoder.decodeFirstFrameAsync(file) { bitmap ->
            result = bitmap
            latch.countDown()
        }

        val delivered = latch.await(10, TimeUnit.SECONDS)
        assertTrue(delivered, "onResult was not called within 10 seconds")
        assertEquals(null, result)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.ui.FfmpegImageSnapshotDecoderTest" --console=plain`
Expected: Compile error — `FfmpegImageSnapshotDecoder` does not exist yet.

- [ ] **Step 4: Implement `FfmpegImageSnapshotDecoder`**

Create `app/src/main/kotlin/com/multiviewer/ui/FfmpegImageSnapshotDecoder.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Headless, one-shot fallback for images Skia's Image.makeFromEncoded can't decode (HEIC/HEVC and
 * other HEIF-family stills). Shells out to a system `ffmpeg` (must be on PATH) to extract the
 * primary frame as a temporary PNG, then decodes that PNG via Skia like any other supported image.
 */
object FfmpegImageSnapshotDecoder {
    private const val TIMEOUT_MS = 8000L

    fun decodeFirstFrameAsync(file: File, onResult: (ImageBitmap?) -> Unit) {
        Thread {
            val tempPng = try {
                File.createTempFile("ffmpeg-snapshot-", ".png")
            } catch (e: Exception) {
                EventQueue.invokeLater { onResult(null) }
                return@Thread
            }
            tempPng.deleteOnExit()

            val result = try {
                val process = ProcessBuilder(
                    "ffmpeg", "-y", "-i", file.absolutePath,
                    "-frames:v", "1", "-update", "1",
                    tempPng.absolutePath,
                )
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()

                val finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    null
                } else if (process.exitValue() != 0 || tempPng.length() == 0L) {
                    null
                } else {
                    Image.makeFromEncoded(tempPng.readBytes()).toComposeImageBitmap()
                }
            } catch (e: Exception) {
                // ProcessBuilder.start() throws IOException when `ffmpeg` isn't on PATH.
                null
            } finally {
                tempPng.delete()
            }

            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.ui.FfmpegImageSnapshotDecoderTest" --console=plain`
Expected: BUILD SUCCESSFUL, 1 test passed. Should resolve in well under 10 seconds — ffmpeg exits non-zero almost immediately for 300 garbage bytes, unlike the old VLC test which had to exhaust its full internal timeout.

- [ ] **Step 6: Run the full test suite to check for regressions**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests passed. `AppState.kt` will still reference `VlcImageSnapshotDecoder` at this point (Task 2 hasn't run yet) — if this causes a compile failure, that's expected; note it in your report and do not attempt to fix `AppState.kt` yourself, that's Task 2's job. (If `AppState.kt`'s reference to `VlcImageSnapshotDecoder` still compiles because the class hasn't been fully removed from source control tracking, that's also fine — just report what you observe.)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegImageSnapshotDecoder.kt app/src/test/kotlin/com/multiviewer/ui/FfmpegImageSnapshotDecoderTest.kt
git commit -m "Replace VLC-based HEIC fallback decoder with ffmpeg subprocess decoder"
```

(The `git rm` from Step 1 stages the deletions; this commit includes both the deletions and the new files as one commit.)

---

### Task 2: Update call sites, then end-to-end manual verification

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt:107-124`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt:71-79`

**Interfaces:**
- Consumes: `FfmpegImageSnapshotDecoder.decodeFirstFrameAsync(file: File, onResult: (ImageBitmap?) -> Unit)` (Task 1).
- Produces: nothing consumed by later tasks — this is the final piece.

- [ ] **Step 1: Update `AppState.kt` to call the new decoder**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, replace:

```kotlin
                MediaType.IMAGE -> {
                    val forensic = ImageAnalyzer.analyze(file, root)
                    if (forensic.bitmap == null) {
                        // Skia has no HEIF/HEVC decoder — fall back to VLC, async so the UI never blocks.
                        tab.imageForensic = forensic.copy(isDecodingFallback = true)
                        VlcImageSnapshotDecoder.decodeFirstFrameAsync(file) { bitmap ->
                            val current = tab.imageForensic ?: forensic
                            tab.imageForensic = current.copy(
                                bitmap = bitmap,
                                embeddedThumbnail = current.embeddedThumbnail
                                    ?: (bitmap.takeIf { current.hasThumbnailReference }),
                                isDecodingFallback = false,
                            )
                        }
                    } else {
                        tab.imageForensic = forensic
                    }
                }
```

with:

```kotlin
                MediaType.IMAGE -> {
                    val forensic = ImageAnalyzer.analyze(file, root)
                    if (forensic.bitmap == null) {
                        // Skia has no HEIF/HEVC decoder — fall back to ffmpeg, async so the UI never blocks.
                        tab.imageForensic = forensic.copy(isDecodingFallback = true)
                        FfmpegImageSnapshotDecoder.decodeFirstFrameAsync(file) { bitmap ->
                            val current = tab.imageForensic ?: forensic
                            tab.imageForensic = current.copy(
                                bitmap = bitmap,
                                embeddedThumbnail = current.embeddedThumbnail
                                    ?: (bitmap.takeIf { current.hasThumbnailReference }),
                                isDecodingFallback = false,
                            )
                        }
                    } else {
                        tab.imageForensic = forensic
                    }
                }
```

(Only the comment and the object name change — `VlcImageSnapshotDecoder` → `FfmpegImageSnapshotDecoder`. No import needed; same package.)

- [ ] **Step 2: Update the UI loading-state text**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`, replace:

```kotlin
                        forensic.bitmap?.let { 
                            PixelInspectorPreview(it) 
                        } ?: Text(
                            if (forensic.isDecodingFallback) "Decoding via VLC..." else "Primary Image Decoding Failed",
                            color = if (forensic.isDecodingFallback) AppColors.TextSecondary else AppColors.NeonRed,
                            fontSize = 12.sp,
                        )
```

with:

```kotlin
                        forensic.bitmap?.let { 
                            PixelInspectorPreview(it) 
                        } ?: Text(
                            if (forensic.isDecodingFallback) "Decoding via ffmpeg..." else "Primary Image Decoding Failed",
                            color = if (forensic.isDecodingFallback) AppColors.TextSecondary else AppColors.NeonRed,
                            fontSize = 12.sp,
                        )
```

- [ ] **Step 3: Run the full test suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests passed — this confirms `AppState.kt` now compiles cleanly against `FfmpegImageSnapshotDecoder`.

- [ ] **Step 4: Commit the call-site changes**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt
git commit -m "Wire ffmpeg fallback decoder into AppState and UI loading state"
```

- [ ] **Step 5: Build and run the app**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:run`
Expected: The app window opens with no build errors.

- [ ] **Step 6: Manually verify against the real grid-tiled Samsung HEIC**

Open `/Users/dong.kim/Downloads/20260715_223835.heic` in the running app (confirmed during investigation: `grid`-composed primary item made of 40 `hvc1` tiles at full resolution 2252×4000, with a `thmb` `iref` entry — `iref` box shows `thmb (14 bytes) 42 -> 41`).

Expected:
- Right panel ("PRIMARY IMAGE VIEW") briefly shows "Decoding via ffmpeg..." then displays the correctly-assembled, undistorted 2252×4000 photo (already independently verified via a direct `ffmpeg` CLI test outside the app — this step confirms the app's async wiring delivers the same result to the UI).
- Left panel ("EMBEDDED EXIF THUMBNAIL") shows the same decoded image (this file has a `thmb` reference, so `hasThumbnailReference` is `true`).

- [ ] **Step 7: Manually verify against the simple non-tiled macOS sample HEIC**

Open `/Library/User Pictures/Flowers/Whiterose.heic` (confirmed: single `hvc1` item, no `thmb` reference).

Expected: Right panel shows the decoded rose photo; left panel shows "No Embedded Thumbnail" (no `thmb` reference in this file).

- [ ] **Step 8: Manually verify against a normal JPEG (no regression)**

Open any ordinary `.jpg` file with an embedded EXIF thumbnail.

Expected: Both panels render exactly as before this whole feature — Skia's `Image.makeFromEncoded` succeeds, so `bitmap != null` and the ffmpeg fallback path never triggers.
