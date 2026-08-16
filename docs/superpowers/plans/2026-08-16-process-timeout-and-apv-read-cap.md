# Process Timeout Enforcement + APV Frame-Header Read Cap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make this app's already-declared ffmpeg/ffprobe timeout values actually enforceable (today, an unbounded blocking read happens before the timeout-bearing `waitFor()` call at 7 confirmed call sites, so the timeout can only fire after it's already too late), and cap `resolveApvFrameHeader`'s per-frame read to a small bounded prefix instead of an entire multi-MB MP4 sample.

**Architecture:** One new generic utility function, `readProcessOutputWithTimeout`, runs a caller-supplied read block (whatever that call site already does — `readLines()`, `readText()`, or a custom streaming loop) on a background thread bounded by `Future.get(timeout)`, force-killing the process if the block doesn't finish in time. Every affected call site wraps its existing read logic in this function — no timeout values change, no call site's downstream logic changes beyond handling a `null` result the same way it already handles other failure paths. Separately, `ApvParameterSetExtraction.kt` gets a small, independent read-size cap.

**Tech Stack:** Kotlin, JVM `Executors`/`Future`. No new dependencies.

## Global Constraints

- No timeout VALUES change anywhere in this plan — every existing `waitFor(N, TimeUnit.SECONDS)` call's `N` is preserved exactly, just made to actually mean something.
- Every call site's existing null/failure handling convention is preserved: on a `null` result from `readProcessOutputWithTimeout`, the calling function returns `null` (or `false` for `isVmafAvailable`, which already returns `Boolean` on failure) — matching how it already handles every other failure path in the same function.
- `readProcessOutputWithTimeout` lives in the `com.multiviewer.ui` package — every one of the 7 affected files is already in this same package, so no new imports are needed anywhere.
- `ApvPbu.kt`/`ApvFrameHeader.kt` are NOT modified by the APV read-cap fix — `findApvPrimaryFramePbuPayload`'s existing clamp-to-available-bytes logic (added during APV's own final review) already handles a shorter-than-declared buffer correctly; only the one read call in `ApvParameterSetExtraction.kt` changes.
- `APV_FRAME_HEADER_PREFIX_BYTES = 4096`, matching `Av1FrameHeaderAnalyzer.kt`'s existing `MAX_FRAME_HEADER_PREFIX_BYTES` precedent exactly.

---

### Task 1: `ProcessOutputReader.kt` — shared timeout-enforcing read utility

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/ProcessOutputReader.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ProcessOutputReaderTest.kt`

**Interfaces:**
- Consumes: nothing new (plain JVM `Process`/`Executors`/`Future`).
- Produces: `fun <T> readProcessOutputWithTimeout(process: Process, timeoutSeconds: Long, read: () -> T): T?` — Tasks 2 and 3 call this from every affected probe/decode function.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/ProcessOutputReaderTest.kt`:

```kotlin
package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessOutputReaderTest {
    @Test
    fun `readProcessOutputWithTimeout returns the read result for a fast-completing process`() {
        val process = ProcessBuilder("echo", "hello").start()

        val result = readProcessOutputWithTimeout(process, timeoutSeconds = 5) {
            process.inputStream.bufferedReader().readLines()
        }

        assertEquals(listOf("hello"), result)
    }

    @Test
    fun `readProcessOutputWithTimeout returns null and force-kills the process when the read exceeds the timeout`() {
        // Produces no stdout output and doesn't exit until 30s -- read() blocks the entire time,
        // exactly reproducing the real bug's shape (an unbounded blocking read on a stuck process).
        val process = ProcessBuilder("sleep", "30").start()

        val result = readProcessOutputWithTimeout(process, timeoutSeconds = 1) {
            process.inputStream.bufferedReader().readLines()
        }

        assertNull(result)
        Thread.sleep(200) // brief grace period for destroyForcibly() to take effect
        assertTrue(!process.isAlive)
    }

    @Test
    fun `readProcessOutputWithTimeout returns null when the read block itself throws`() {
        val process = ProcessBuilder("echo", "hello").start()

        val result = readProcessOutputWithTimeout(process, timeoutSeconds = 5) {
            throw RuntimeException("boom")
        }

        assertNull(result)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.ProcessOutputReaderTest"`
Expected: FAIL — compile error, `readProcessOutputWithTimeout` doesn't exist yet.

- [ ] **Step 3: Create `ProcessOutputReader.kt`**

```kotlin
package com.multiviewer.ui

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// Runs `read` (the caller's own stdout-consuming logic -- readLines(), readText(), a custom
// streaming loop, whatever the call site already does) on a background thread, bounded by
// `timeoutSeconds`. Unlike calling `process.waitFor(timeout, ...)` AFTER an unbounded blocking
// read (this codebase's prior, buggy pattern at several call sites -- see
// docs/superpowers/specs/2026-08-16-process-timeout-and-apv-read-cap-design.md), this makes the
// timeout actually enforceable: if `read` hasn't returned within `timeoutSeconds`, the process is
// force-killed and this returns null, restoring the meaning the timeout value at each call site
// already implied but couldn't deliver.
fun <T> readProcessOutputWithTimeout(process: Process, timeoutSeconds: Long, read: () -> T): T? {
    val executor = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
    val future = executor.submit(Callable(read))
    return try {
        val result = future.get(timeoutSeconds, TimeUnit.SECONDS)
        if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        result
    } catch (e: TimeoutException) {
        future.cancel(true)
        process.destroyForcibly()
        null
    } catch (e: Exception) {
        process.destroyForcibly()
        null
    } finally {
        executor.shutdownNow()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.ProcessOutputReaderTest"`
Expected: PASS (3/3 tests). The timeout test takes slightly over 1 second (bounded by its own 1s timeout, not the full 30s sleep) — this is expected and correct, not a hang.

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/ProcessOutputReader.kt \
        app/src/test/kotlin/com/multiviewer/ui/ProcessOutputReaderTest.kt
git commit -m "Add timeout-enforcing process-output-read utility"
```

---

### Task 2: Apply the wrapper to the 6 `readLines()`/`readText()` call sites

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt` (two sites: `probeFrameTimestamps`, `probeVideo`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FrameTypeAnalyzer.kt` (`probeFrameTypes`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/StreamCodecDetails.kt` (`probeStreamDetails`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt` (`probeAudioFormat`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/QualityMetrics.kt` (`isVmafAvailable`)

**Interfaces:**
- Consumes: `readProcessOutputWithTimeout` (Task 1).
- Produces: nothing new — every function's existing signature and return type are unchanged.

No new automated tests in this task — these are mechanical wrapper-substitutions with no behavior change on the success path (already covered by each function's existing usage elsewhere in the app/its own indirect test coverage), and the newly-*reachable* timeout path is already covered in isolation by Task 1's `ProcessOutputReaderTest`. Adding a live-hang integration test for each of these 6 functions would mean spawning a real slow/stuck ffmpeg process per test, which is disproportionate to what's being verified here (the wrapper's correctness, already tested once in Task 1) — full test suite regression coverage is the right gate for this task.

- [ ] **Step 1: `FfmpegVideoPlayer.kt` — `probeFrameTimestamps`**

Find this exact block (currently at or near line 92-96):

```kotlin
        val lines = process.inputStream.bufferedReader().readLines()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
```

Replace with:

```kotlin
        val lines = readProcessOutputWithTimeout(process, 10) { process.inputStream.bufferedReader().readLines() }
            ?: return null
```

- [ ] **Step 2: `FfmpegVideoPlayer.kt` — `probeVideo`**

Find this exact block (currently at or near line 201-202):

```kotlin
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(5, TimeUnit.SECONDS)
```

Replace with:

```kotlin
        val lines = readProcessOutputWithTimeout(process, 5) { process.inputStream.bufferedReader().readLines() }
            ?: return null
```

- [ ] **Step 3: `FrameTypeAnalyzer.kt` — `probeFrameTypes`**

Find this exact block (currently at or near line 27-28):

```kotlin
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(120, TimeUnit.SECONDS)
```

Replace with:

```kotlin
        val lines = readProcessOutputWithTimeout(process, 120) { process.inputStream.bufferedReader().readLines() }
            ?: return null
```

- [ ] **Step 4: `StreamCodecDetails.kt` — `probeStreamDetails`**

Find this exact block (currently at or near line 26-27):

```kotlin
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(30, TimeUnit.SECONDS)
```

Replace with:

```kotlin
        val lines = readProcessOutputWithTimeout(process, 30) { process.inputStream.bufferedReader().readLines() }
            ?: return null
```

- [ ] **Step 5: `FfmpegAudioPlayer.kt` — `probeAudioFormat`**

Find this exact block (currently at or near line 67-68):

```kotlin
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(5, TimeUnit.SECONDS)
```

Replace with:

```kotlin
        val lines = readProcessOutputWithTimeout(process, 5) { process.inputStream.bufferedReader().readLines() }
            ?: return null
```

- [ ] **Step 6: `QualityMetrics.kt` — `isVmafAvailable`**

Find this exact block (currently at or near line 287-289):

```kotlin
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        output.contains("libvmaf")
```

Replace with:

```kotlin
        val output = readProcessOutputWithTimeout(process, 30) { process.inputStream.bufferedReader().readText() }
            ?: return false
        output.contains("libvmaf")
```

- [ ] **Step 7: Check for now-unused `TimeUnit` imports**

For each of the 5 files touched in this task, check whether `java.util.concurrent.TimeUnit` is still referenced elsewhere in that same file (`grep -n "TimeUnit" <file>`). If a file's only `TimeUnit` usage was the line just replaced, remove its now-unused `import java.util.concurrent.TimeUnit` line. Do not remove the import from any file where `TimeUnit` is still used elsewhere (expected for most/all of these files, which have other timeout-bearing `waitFor` calls not touched by this plan).

- [ ] **Step 8: Compile and run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions — this exercises every touched function's existing success-path test coverage (real ffmpeg/ffprobe calls throughout this codebase's existing test suite).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegVideoPlayer.kt \
        app/src/main/kotlin/com/multiviewer/ui/FrameTypeAnalyzer.kt \
        app/src/main/kotlin/com/multiviewer/ui/StreamCodecDetails.kt \
        app/src/main/kotlin/com/multiviewer/ui/FfmpegAudioPlayer.kt \
        app/src/main/kotlin/com/multiviewer/ui/QualityMetrics.kt
git commit -m "Enforce ffprobe/ffmpeg timeouts at 6 blocking readLines/readText call sites"
```

---

### Task 3: Apply the wrapper to `AudioWaveformPeaks.kt`'s streaming read loop

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt`

**Interfaces:**
- Consumes: `readProcessOutputWithTimeout` (Task 1).
- Produces: nothing new — `computeWaveformPeaks`'s signature and return type are unchanged.

This is its own task (not folded into Task 2) because the transformation is structurally different: a multi-line streaming loop with side-effecting mutable state, not a single `readLines()`/`readText()` call — worth its own review gate.

No new automated tests — same rationale as Task 2 (the wrapper's own correctness is covered by Task 1; this task only changes how the existing loop's result flows through the wrapper, not the loop's own per-sample logic).

- [ ] **Step 1: Replace `computeWaveformPeaks`'s body**

In `app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt`, replace this entire block (currently lines 76-125, the full `return try { ... } catch ... finally ...` at the end of `computeWaveformPeaks`):

```kotlin
    return try {
        val input = process.inputStream
        val readBuffer = ByteArray(65536)
        var carry = ByteArray(0)
        var frameIndex = 0L

        while (true) {
            val bytesRead = input.read(readBuffer)
            if (bytesRead < 0) break
            val chunk = if (carry.isEmpty()) readBuffer.copyOf(bytesRead) else carry + readBuffer.copyOf(bytesRead)
            val usableFrames = chunk.size / frameSizeBytes
            val usableBytes = usableFrames * frameSizeBytes
            var offset = 0
            repeat(usableFrames) {
                val bucket = (frameIndex / framesPerBucket).coerceAtMost((bucketCount - 1).toLong()).toInt()
                for (c in 0 until channels) {
                    val sample = (((chunk[offset + 1].toInt() shl 8) or (chunk[offset].toInt() and 0xFF))).toShort().toFloat() / 32768f
                    if (sample < minPerChannel[c][bucket]) minPerChannel[c][bucket] = sample
                    if (sample > maxPerChannel[c][bucket]) maxPerChannel[c][bucket] = sample
                    offset += 2
                }
                frameIndex++
            }
            carry = chunk.copyOfRange(usableBytes, chunk.size)
        }
        val finished = process.waitFor(30, TimeUnit.SECONDS)

        if (!finished || frameIndex == 0L) {
            null
        } else {
            for (c in 0 until channels) {
                for (b in 0 until bucketCount) {
                    if (minPerChannel[c][b] == Float.MAX_VALUE) {
                        minPerChannel[c][b] = 0f
                        maxPerChannel[c][b] = 0f
                    }
                }
            }
            WaveformPeaks(
                channelCount = channels,
                bucketCount = bucketCount,
                channels = (0 until channels).map { ChannelPeaks(minPerChannel[it], maxPerChannel[it]) },
            )
        }
    } catch (e: Exception) {
        null
    } finally {
        process.destroyForcibly()
        if (inputFile != file) inputFile.delete()
    }
```

With:

```kotlin
    return try {
        val input = process.inputStream
        val readBuffer = ByteArray(65536)
        var carry = ByteArray(0)
        var frameIndex = 0L

        val completedFrameCount = readProcessOutputWithTimeout(process, 30) {
            while (true) {
                val bytesRead = input.read(readBuffer)
                if (bytesRead < 0) break
                val chunk = if (carry.isEmpty()) readBuffer.copyOf(bytesRead) else carry + readBuffer.copyOf(bytesRead)
                val usableFrames = chunk.size / frameSizeBytes
                val usableBytes = usableFrames * frameSizeBytes
                var offset = 0
                repeat(usableFrames) {
                    val bucket = (frameIndex / framesPerBucket).coerceAtMost((bucketCount - 1).toLong()).toInt()
                    for (c in 0 until channels) {
                        val sample = (((chunk[offset + 1].toInt() shl 8) or (chunk[offset].toInt() and 0xFF))).toShort().toFloat() / 32768f
                        if (sample < minPerChannel[c][bucket]) minPerChannel[c][bucket] = sample
                        if (sample > maxPerChannel[c][bucket]) maxPerChannel[c][bucket] = sample
                        offset += 2
                    }
                    frameIndex++
                }
                carry = chunk.copyOfRange(usableBytes, chunk.size)
            }
            frameIndex
        }

        if (completedFrameCount == null || completedFrameCount == 0L) {
            null
        } else {
            for (c in 0 until channels) {
                for (b in 0 until bucketCount) {
                    if (minPerChannel[c][b] == Float.MAX_VALUE) {
                        minPerChannel[c][b] = 0f
                        maxPerChannel[c][b] = 0f
                    }
                }
            }
            WaveformPeaks(
                channelCount = channels,
                bucketCount = bucketCount,
                channels = (0 until channels).map { ChannelPeaks(minPerChannel[it], maxPerChannel[it]) },
            )
        }
    } catch (e: Exception) {
        null
    } finally {
        process.destroyForcibly()
        if (inputFile != file) inputFile.delete()
    }
```

(`frameIndex`, `minPerChannel`, `maxPerChannel`, `channels`, `bucketCount`, `framesPerBucket`, `frameSizeBytes` are all already in scope as locals/params defined earlier in `computeWaveformPeaks`, before this block — the lambda captures them by closure exactly as the original inline loop did. The only structural change is that the loop's final `frameIndex` value is now the lambda's explicit return value, read via `completedFrameCount`, rather than read directly off the captured `var` after an inline `waitFor` call.)

- [ ] **Step 2: Check for now-unused `TimeUnit` import**

`grep -n "TimeUnit" app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt` — if this file's only use was the removed `process.waitFor(30, TimeUnit.SECONDS)` line, remove its `import java.util.concurrent.TimeUnit` line too.

- [ ] **Step 3: Compile and run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AudioWaveformPeaks.kt
git commit -m "Enforce ffmpeg timeout in the waveform-peak streaming read loop"
```

---

### Task 4: APV frame-header read cap

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ApvParameterSetExtraction.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing new — `resolveApvFrameHeader`'s signature and return type are unchanged.

Independent of Tasks 1-3 (different package, different concern — a read-size cap, not a timeout fix). No automated test changes needed: `ApvFrameHeaderTest.kt`'s existing real-fixture test already exercises a `framePayload` shorter than any full access unit (the fixture is a 48-byte prefix), so the underlying assumption this cap depends on (a bounded prefix is enough to parse the header) is already proven by that existing test.

- [ ] **Step 1: Add the cap constant and apply it**

Replace the entire current contents of `app/src/main/kotlin/com/multiviewer/parser/ApvParameterSetExtraction.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File

// Enough for any frame_header() (verified: the real fields this parser reads fit well within 40
// bytes -- see docs/superpowers/plans/2026-08-16-apv-codec-support.md's Technical Foundation), not
// the full access unit, which for a high-bitrate intra frame (this codec's primary real-world use
// case) can be tens of MB. Mirrors Av1FrameHeaderAnalyzer.kt's own MAX_FRAME_HEADER_PREFIX_BYTES
// cap for the same reason.
private const val APV_FRAME_HEADER_PREFIX_BYTES = 4096

// Reads one frame's raw MP4 sample bytes (FrameInfo.byteOffset/sizeBytes) and parses its APV frame
// header. Lazy, on-demand, per frame -- no whole-stream pass needed, since every APV frame_header()
// is self-contained (see this plan's Architecture section for why this differs from AV1's approach).
// Reads only a bounded prefix, not the full sample -- findApvPrimaryFramePbuPayload's own
// clamp-to-available-bytes logic (see ApvPbu.kt) already handles this buffer being shorter than
// the frame's declared pbu_size correctly, so no change is needed there.
fun resolveApvFrameHeader(file: File, byteOffset: Long, sizeBytes: Int): ApvFrameHeader? {
    return try {
        ByteReader.open(file).use { reader ->
            val prefixLength = minOf(sizeBytes, APV_FRAME_HEADER_PREFIX_BYTES)
            val accessUnitBytes = reader.readBytes(byteOffset, prefixLength)
            val framePayload = findApvPrimaryFramePbuPayload(accessUnitBytes) ?: return@use null
            parseApvFrameHeader(framePayload)
        }
    } catch (e: Exception) {
        null
    }
}
```

- [ ] **Step 2: Compile and run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.ApvFrameHeaderTest" --tests "com.multiviewer.parser.ApvPbuTest" --tests "com.multiviewer.parser.ApvCBoxDecoderTest"`
Expected: PASS, same counts as before this change (the existing real-fixture test already uses a 48-byte payload, well under the 4096-byte cap, so its assertions are unaffected).

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ApvParameterSetExtraction.kt
git commit -m "Cap APV frame-header reads to a bounded prefix instead of the full MP4 sample"
```
