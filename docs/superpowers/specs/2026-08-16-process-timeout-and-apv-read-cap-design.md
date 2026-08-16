# Process Timeout Enforcement + APV Frame-Header Read Cap — Design Spec

**Goal:** Fix a real bug where this app's ffmpeg/ffprobe timeout values are silently unenforceable (a blocking, unbounded stdout read happens before the timeout-bearing `waitFor()` call, so the timeout can only fire after it's already too late to matter), and cap `resolveApvFrameHeader`'s per-frame read to a small bounded prefix instead of the entire MP4 sample — both surfaced by analyzing how this app would behave against very large (hundreds-of-GB, high-bitrate intra-only) APV files.

**Context:** During a user-requested architecture review of large-file handling (see chat history, no separate research doc), grep confirmed the exact affected call sites (not the initially-estimated "8", precisely 7 real ones — see Scope). All are pre-existing, codec-agnostic bugs/gaps, not new regressions from the recent APV work — APV large files simply make them concretely reachable in a way 4K H.264 test clips never did.

## Scope

**Part A — Process timeout enforcement.** Confirmed real affected call sites (`grep -rn "readLines()\|readText()" app/src/main/kotlin/` plus manual inspection of streaming-read call sites, current as of this writing):

| File:line | Function | Current shape |
|---|---|---|
| `FfmpegVideoPlayer.kt:92` | `probeFrameTimestamps` | `process.inputStream.bufferedReader().readLines()` then `waitFor(10s)` |
| `FfmpegVideoPlayer.kt:201` | `probeVideo` (stream-only ffprobe) | same shape, `waitFor(5s)` |
| `FrameTypeAnalyzer.kt:27` | `probeFrameTypes` (GOP/frame-type analysis) | same shape, `waitFor(120s)` |
| `StreamCodecDetails.kt:26` | `probeStreamDetails` | same shape, `waitFor(30s)` |
| `FfmpegAudioPlayer.kt:67` | (audio stream probe) | same shape |
| `QualityMetrics.kt:287` | `isVmafAvailable` | `process.inputStream.bufferedReader().readText()` then `waitFor(30s)` |
| `AudioWaveformPeaks.kt:82-101` | waveform peak extraction | a manual `while (true) { input.read(buffer) }` streaming loop (not `readLines()`/`readText()`, but the same underlying defect: no bound on the loop itself, `waitFor(30s)` only called after the loop's own blocking read returns EOF) |

**Explicitly NOT affected, verified, left untouched:**
- `probeResolution`/`probeFrameCount` (`QualityMetrics.kt`) and `CodecViewFrameDecoder.kt:27` use `readLine()` (singular) — this returns after just the first line, so the real `waitFor(timeout)` that follows genuinely has a chance to fire if the process stalls afterward. Already correct.
- `FfmpegImageSnapshotDecoder.kt` and `RawPixelDecoder.kt` call `waitFor(timeout)` **before** reading their output (from a temp file, not `process.inputStream`) — already correctly gated.
- `TrackExtractor.kt` (300s) and `QualityMetrics.kt`'s metric passes (600s) are a **different** problem (a timeout that's too short for a genuinely large full-decode job, not one that fails to fire) — out of scope for this spec; see Out of Scope.

One shared, generic utility fixes all 7: a function that runs the existing read logic (whether `readLines()`, `readText()`, or a custom streaming loop) on a background thread, bounded by `Future.get(timeout)`, and calls `process.destroyForcibly()` when the timeout is actually exceeded — restoring the timeout values already declared at each call site to their intended meaning, without changing any of those values.

**Part B — APV frame-header read cap.** `ApvParameterSetExtraction.kt`'s `resolveApvFrameHeader` currently reads a selected frame's entire MP4 sample (`reader.readBytes(byteOffset, sizeBytes)`) just to parse the ~40-byte `frame_header()` prefix — for an 8K intra frame at high bitrate, this can be tens of MB per frame selection. Cap the read to a small bounded prefix, matching the existing `Av1FrameHeaderAnalyzer.kt`'s `MAX_FRAME_HEADER_PREFIX_BYTES = 4096` precedent. `ApvPbu.kt`'s `findApvPrimaryFramePbuPayload` already clamps its returned payload to whatever bytes are actually available (added during APV's own final review, for exactly this "shorter buffer than the declared `pbu_size`" scenario) — so this fix touches only the one read call in `ApvParameterSetExtraction.kt`, nothing in `ApvPbu.kt`/`ApvFrameHeader.kt`.

## Components

### 1. `ProcessOutputReader.kt` (new) — shared timeout-enforcing read utility

```kotlin
package com.multiviewer.ui

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// Runs `read` (the caller's own stdout-consuming logic -- readLines(), readText(), a custom
// streaming loop, whatever the call site already does) on a background thread, bounded by
// `timeoutSeconds`. Unlike calling `process.waitFor(timeout, ...)` AFTER an unbounded blocking
// read, this makes the timeout actually enforceable: if `read` hasn't returned within
// `timeoutSeconds`, the process is force-killed and this returns null, restoring the meaning the
// timeout value at each call site already implied but couldn't deliver.
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

Placed in the `com.multiviewer.ui` package (matches `FfmpegLocator.kt`'s package, since every affected call site already imports from that package and this is the natural shared home for ffmpeg-process-adjacent utilities).

### 2. Seven call-site updates

Each of the 7 sites in the Scope table wraps its existing read logic in `readProcessOutputWithTimeout(process, existingTimeoutValue) { existingReadLogic }`, replacing the separate `readLines()`/`readText()`/loop call plus its now-redundant trailing `waitFor(timeout)` call. **No timeout value changes** — this fix restores the existing declared timeouts to actually working, it doesn't re-tune them. `AudioWaveformPeaks.kt`'s loop (which mutates `minPerChannel`/`maxPerChannel` arrays and tracks `frameIndex` as side effects, then uses `frameIndex` afterward) wraps its whole loop body as the lambda, returning `frameIndex` as `T`.

### 3. APV read cap

In `ApvParameterSetExtraction.kt`:

```kotlin
private const val APV_FRAME_HEADER_PREFIX_BYTES = 4096 // mirrors Av1FrameHeaderAnalyzer.kt's own cap
```

Change `resolveApvFrameHeader`'s `reader.readBytes(byteOffset, sizeBytes)` to `reader.readBytes(byteOffset, minOf(sizeBytes, APV_FRAME_HEADER_PREFIX_BYTES))`.

## Error Handling

`readProcessOutputWithTimeout` returns `null` on timeout or any exception during the read — every call site already treats a `null`/failed read as "probe failed, show nothing extra" (this app's established convention), so no call site's error-handling logic needs to change beyond adopting the new wrapper. The APV read cap doesn't change error handling at all — `findApvPrimaryFramePbuPayload`'s existing clamp-to-available-bytes logic already handles a shorter-than-declared buffer correctly (verified during APV's own implementation).

## Testing

- `readProcessOutputWithTimeout` gets real-process unit tests: a fast-completing process (e.g. `echo`) returns its output normally within the timeout; a genuinely slow/hanging process (e.g. `sleep 30` with a 1-2 second test timeout) returns `null` and the process is confirmed killed (e.g. checking it's no longer running, or that a sentinel file it would have written doesn't appear). Matches this codebase's existing convention of testing process-invoking code against real (not mocked) processes.
- No test changes needed for the 7 call sites themselves — their existing behavior (successful probe path) is unchanged; only the previously-unreachable timeout path is now reachable, which is exactly what `readProcessOutputWithTimeout`'s own tests cover in isolation.
- No new test for the APV read cap specifically — `ApvFrameHeaderTest.kt`'s existing real-fixture test already exercises a `framePayload` shorter than `frame_header()`'s implied full data size (the fixture is a 48-byte prefix), so the cap's core assumption ("a bounded prefix is enough to parse the header") is already implicitly proven; no new fixture needed.

## Out of Scope (Deferred)

- Re-tuning `TrackExtractor.kt`'s 300s / `QualityMetrics.kt`'s 600s timeouts to scale with file size — a separate, later concern (these timeouts DO fire correctly today; they may just be too short for very large files, which is a tuning question, not a bug fix).
- File-size-based UI warnings/thresholds (the "50GB 이상 경고" idea raised during the architecture discussion) — a separate, later feature with its own UI/UX design questions, not a bug fix.
- `BackgroundTask.kt`'s shared 2-thread pool contention for large-file work — a separate, later architecture change.
- moov-atom-position detection / "faststart" warnings — separate, later feature.
