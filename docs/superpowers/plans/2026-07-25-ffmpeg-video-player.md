# ffmpeg-Based Video Player Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `FfmpegVideoPlayer(file: File, modifier: Modifier = Modifier)` — a drop-in, same-signature replacement for `VlcVideoPlayer` that uses an `ffmpeg` subprocess instead of vlcj/libvlc. This is sub-project A of a three-part effort (A: this player · B: bundle ffmpeg into the packaged app · C: migrate call sites and remove vlcj) to replace VLC with ffmpeg everywhere, since bundling VLC's full plugin runtime for Windows/Linux distribution is much larger and more fragile than bundling one ffmpeg binary.

**Architecture:** Task 1 builds and tests, in isolation from any Compose UI, the two riskiest/most novel pieces: parsing `ffprobe`'s frame-rate output (`avg_frame_rate` preferred over the container's nominal `r_frame_rate`, verified empirically to matter for real phone video), and the raw-BGRA-frame-over-a-pipe mechanism itself (`ffmpeg -f rawvideo -pix_fmt bgra -an -r <fps> -`), validated against a synthetic ffmpeg-generated test video so the test has no external file dependency. Task 2 builds the actual `@Composable` on top of those already-validated pieces — `DisposableEffect`-scoped process lifecycle, a background reader thread pacing itself to the target fps, and a play/pause UI matching `VlcVideoPlayer`'s today.

**Tech Stack:** Kotlin 2.2.20, Compose Multiplatform Desktop, `org.jetbrains.skia` (existing), `kotlin.test`. No new dependency — same `ffmpeg`-on-`PATH` approach as `FfmpegImageSnapshotDecoder.kt`.

## Global Constraints

- Same public signature as `VlcVideoPlayer`: `FfmpegVideoPlayer(file: File, modifier: Modifier = Modifier): Unit` (a `@Composable` function) — required for sub-project C to be a one-line swap per call site.
- `avg_frame_rate` is the primary frame-rate source; fall back to `r_frame_rate` if unparseable (`"0/0"`), then a hardcoded `30.0` if both are unparseable. This exact fallback order — verified necessary: a real test file's `r_frame_rate` (`120/1`, container nominal) doesn't match its real ~30fps content.
- ffmpeg's raw/pixel output auto-applies rotation metadata (verified empirically) — no manual rotation logic in this player.
- No seek bar, no audio (`-an`), no frame-exact pause (small pipe-buffered catch-up on resume is accepted), no auto-loop at EOF — all explicit non-goals from the spec, matching `VlcVideoPlayer`'s existing feature set exactly, not exceeding it.
- No ffmpeg path bundling/resolution in this plan — literal `"ffmpeg"`/`"ffprobe"` via `PATH`, exactly like `FfmpegImageSnapshotDecoder.kt`. That's sub-project B's job, for both call sites at once.
- Not wired into any existing UI in this plan (`VideoInspectorUI.kt`, `ImageInspectorUI.kt`'s Motion Photo panel are untouched) — that's sub-project C. This plan's own verification is therefore automated tests only, not a live-app manual check; end-to-end "does it play in the real app" verification happens naturally when C wires it in.
- Spec: `docs/superpowers/specs/2026-07-25-ffmpeg-video-player-design.md`.

---

### Task 1: Frame-rate parsing, video probing, and raw-frame piping (no UI)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt` (this task adds only the non-Composable top-level functions; Task 2 adds the `@Composable` to the same file)
- Create: `app/src/test/kotlin/com/multiviewer/ui/FfmpegVideoPlayerTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `parseFrameRate(fraction: String): Double?`, `data class VideoInfo(val width: Int, val height: Int, val fps: Double)`, `probeVideo(file: File): VideoInfo?` — all top-level in `FfmpegVideoPlayer.kt`, package-visible (no `private` modifier, so `FfmpegVideoPlayerTest.kt` and Task 2's `@Composable` can both use them). Task 2 consumes `VideoInfo`/`probeVideo` directly and reuses the same `ProcessBuilder("ffmpeg", "-i", file.absolutePath, "-f", "rawvideo", "-pix_fmt", "bgra", "-an", "-r", info.fps.toString(), "-")` command shape this task's test already exercises.

This task's test requires `ffmpeg`/`ffprobe` on `PATH` (already confirmed installed on this machine) — matching this codebase's convention of testing subprocess-dependent code for real (`FfmpegImageSnapshotDecoderTest.kt`) rather than mocking.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/FfmpegVideoPlayerTest.kt`:

```kotlin
package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FfmpegVideoPlayerTest {
    @Test
    fun `parseFrameRate reads a simple integer fraction`() {
        assertEquals(30.0, parseFrameRate("30/1"))
    }

    @Test
    fun `parseFrameRate reads a non-integer NTSC-style fraction`() {
        val result = parseFrameRate("24000/1001")
        assertTrue(result != null && Math.abs(result - 23.976) < 0.001)
    }

    @Test
    fun `parseFrameRate returns null for a zero-over-zero fraction`() {
        assertNull(parseFrameRate("0/0"))
    }

    @Test
    fun `parseFrameRate returns null for a malformed string`() {
        assertNull(parseFrameRate("not-a-fraction"))
    }

    @Test
    fun `probeVideo reads width, height, and fps from a real synthetic video`() {
        val video = File.createTempFile("ffmpeg-player-probe-test-", ".mp4")
        video.deleteOnExit()
        val generate = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x48:rate=10",
            video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        generate.waitFor()

        val info = probeVideo(video)

        assertEquals(64, info?.width)
        assertEquals(48, info?.height)
        assertEquals(10.0, info?.fps)
        video.delete()
    }

    @Test
    fun `probeVideo returns null for a nonexistent file`() {
        assertNull(probeVideo(File("/nonexistent/path/does-not-exist.mp4")))
    }

    @Test
    fun `raw BGRA frames can be read from ffmpeg's stdout at the exact expected byte size`() {
        val video = File.createTempFile("ffmpeg-player-frames-test-", ".mp4")
        video.deleteOnExit()
        val generate = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x48:rate=10",
            video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        generate.waitFor()

        val info = probeVideo(video)!!
        val frameSize = info.width * info.height * 4
        val process = ProcessBuilder(
            "ffmpeg", "-i", video.absolutePath,
            "-f", "rawvideo", "-pix_fmt", "bgra", "-an",
            "-r", info.fps.toString(), "-",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()

        val input = process.inputStream
        val buffer = ByteArray(frameSize)
        var framesRead = 0
        repeat(3) {
            var offset = 0
            while (offset < frameSize) {
                val read = input.read(buffer, offset, frameSize - offset)
                if (read < 0) return@repeat
                offset += read
            }
            framesRead++
        }
        process.destroyForcibly()
        video.delete()

        assertEquals(3, framesRead)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.ui.FfmpegVideoPlayerTest" --console=plain`
Expected: Compile error — `parseFrameRate`/`probeVideo`/`VideoInfo` don't exist yet.

- [ ] **Step 3: Implement `parseFrameRate`, `VideoInfo`, and `probeVideo`**

Create `app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt`:

```kotlin
package com.multiviewer.ui

import java.io.File
import java.util.concurrent.TimeUnit

data class VideoInfo(val width: Int, val height: Int, val fps: Double)

fun parseFrameRate(fraction: String): Double? {
    val parts = fraction.split("/")
    val num = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
    val den = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
    if (den == 0.0 || num == 0.0) return null
    return num / den
}

fun probeVideo(file: File): VideoInfo? {
    return try {
        val process = ProcessBuilder(
            "ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height,avg_frame_rate,r_frame_rate",
            "-of", "csv=p=0", file.absolutePath,
        ).redirectErrorStream(false).start()
        val line = process.inputStream.bufferedReader().readLine()
        process.waitFor(5, TimeUnit.SECONDS)
        if (line == null) return null
        val parts = line.split(",")
        if (parts.size < 4) return null
        val width = parts[0].toIntOrNull() ?: return null
        val height = parts[1].toIntOrNull() ?: return null
        val fps = parseFrameRate(parts[2]) ?: parseFrameRate(parts[3]) ?: 30.0
        VideoInfo(width, height, fps)
    } catch (e: Exception) {
        null
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.ui.FfmpegVideoPlayerTest" --console=plain`
Expected: BUILD SUCCESSFUL, 7 tests passed.

- [ ] **Step 5: Run the full test suite to check for regressions**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests passed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt app/src/test/kotlin/com/multiviewer/ui/FfmpegVideoPlayerTest.kt
git commit -m "Add ffprobe-based frame-rate parsing and raw-frame piping for the ffmpeg video player"
```

---

### Task 2: The `FfmpegVideoPlayer` composable

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt` (add the `@Composable` to the file Task 1 created)

**Interfaces:**
- Consumes: `VideoInfo`, `probeVideo(file: File): VideoInfo?` (Task 1, same file).
- Produces: `@Composable fun FfmpegVideoPlayer(file: File, modifier: Modifier = Modifier): Unit` — consumed by sub-project C (not part of this plan) as a drop-in replacement for `VlcVideoPlayer(file, modifier)`.

No automated test for the `@Composable` itself: no Compose UI test infrastructure exists in this project (established convention, confirmed in prior plans), and the subprocess/timing mechanics it depends on were already covered by real, non-mocked tests in Task 1. This task's own verification is a full-suite compile/regression check plus careful self-review of the process-lifecycle and thread-safety reasoning below — there's no live-app manual check available yet since nothing calls this composable until sub-project C wires it in.

- [ ] **Step 1: Add the imports**

In `app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt`, replace:

```kotlin
package com.multiviewer.ui

import java.io.File
import java.util.concurrent.TimeUnit
```

with:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
```

(`org.jetbrains.skia.Image` and `androidx.compose.foundation.Image` both named `Image` — this is the same ambiguity `VlcVideoPlayer.kt` already resolves today via its `import org.jetbrains.skia.*` wildcard, which pulls in `Image` and lets the Compose one win via the explicit `androidx.compose.foundation.Image` import; here we import each Skia type individually instead of a wildcard, which is equally unambiguous — `org.jetbrains.skia.Image.makeFromBitmap(...)` is always fully qualified in the body below, so there's no resolution conflict either way.)

- [ ] **Step 2: Add the `FfmpegVideoPlayer` composable**

In the same file, append after `probeVideo`:

```kotlin

@Composable
fun FfmpegVideoPlayer(file: File, modifier: Modifier = Modifier) {
    var videoBitmap by remember(file) { mutableStateOf<ImageBitmap?>(null, neverEqualPolicy()) }
    var isPlaying by remember(file) { mutableStateOf(false) }
    var loadError by remember(file) { mutableStateOf(false) }

    val info = remember(file) { probeVideo(file) }

    if (info == null) {
        Box(modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
            Text("Could not read video (is ffmpeg installed?)", color = Color.White)
        }
        return
    }

    DisposableEffect(file) {
        val process = try {
            ProcessBuilder(
                "ffmpeg", "-i", file.absolutePath,
                "-f", "rawvideo", "-pix_fmt", "bgra", "-an",
                "-r", info.fps.toString(), "-",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: Exception) {
            null
        }
        // probeVideo() already succeeded, so ffmpeg/ffprobe are known to work in general -- if this
        // second, separate process still fails to start, that's a genuine failure to surface, not
        // silent: without this flag, the UI would otherwise sit on "Decoding stream..." forever.
        if (process == null) loadError = true

        val stopped = AtomicBoolean(false)

        val readerThread = if (process != null) {
            Thread {
                val frameSize = info.width * info.height * 4
                val buffer = ByteArray(frameSize)
                val frameDurationMs = (1000.0 / info.fps).toLong()
                val input = process.inputStream

                fun readFrame(): Boolean {
                    var offset = 0
                    while (offset < frameSize) {
                        val read = input.read(buffer, offset, frameSize - offset)
                        if (read < 0) return false
                        offset += read
                    }
                    return true
                }

                fun deliver() {
                    val bitmap = Bitmap().apply {
                        allocPixels(ImageInfo(ColorInfo(ColorType.BGRA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB), info.width, info.height))
                        installPixels(imageInfo, buffer, info.width * 4)
                    }
                    val snapshot = Image.makeFromBitmap(bitmap).toComposeImageBitmap()
                    EventQueue.invokeLater { videoBitmap = snapshot }
                }

                if (readFrame()) deliver() // first frame, shown immediately while paused
                while (!stopped.get()) {
                    if (!isPlaying) {
                        Thread.sleep(50)
                        continue
                    }
                    val start = System.currentTimeMillis()
                    if (!readFrame()) break // EOF
                    deliver()
                    val remaining = frameDurationMs - (System.currentTimeMillis() - start)
                    if (remaining > 0) Thread.sleep(remaining)
                }
            }.apply { isDaemon = true }.also { it.start() }
        } else {
            null
        }

        onDispose {
            stopped.set(true)
            readerThread?.interrupt()
            process?.destroyForcibly()
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val currentFrame = videoBitmap
        if (loadError) {
            Text("Could not start ffmpeg playback", color = Color.White)
        } else if (currentFrame != null) {
            Image(bitmap = currentFrame, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Decoding stream...", color = Color.Gray)
                Text("File: ${file.name}", color = Color.DarkGray, fontSize = 10.sp)
            }
        }

        if (!isPlaying) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { isPlaying = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(48.dp))
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().clickable { isPlaying = false })
        }
    }
}
```

- [ ] **Step 3: Run the full test suite to check for regressions**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests passed (confirms the whole file, including the new Composable, compiles cleanly).

- [ ] **Step 4: Self-review the process-lifecycle and thread-safety reasoning**

Before committing, trace through and confirm in the report (no code change needed if these all hold — this step is verification, not implementation):
- `onDispose` always runs when the composable leaves composition (tab closed/switched) or `file` changes — confirm `stopped.set(true)` + `readerThread?.interrupt()` + `process?.destroyForcibly()` together guarantee the reader thread's loop exits and the ffmpeg process is killed, with no path that leaves either running.
- The reader thread reads `isPlaying` (a Compose `MutableState`) directly, never writes it — confirm the only writes to `isPlaying` happen in the two `onClick` lambdas (UI/main thread), so there's exactly one writer, keeping this a safe single-writer/multi-reader pattern rather than a real race.
- If `process` is null (`ProcessBuilder.start()` threw — e.g. a resource limit hit even though `probeVideo`'s own separate `ffprobe` call already succeeded), confirm `readerThread` is also null, `onDispose` handles both being null gracefully (no `NullPointerException`, no crash), and `loadError` was set to `true` synchronously right after the failed `start()` call — so the UI shows "Could not start ffmpeg playback" instead of getting stuck on "Decoding stream..." forever.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt
git commit -m "Add the FfmpegVideoPlayer composable"
```
