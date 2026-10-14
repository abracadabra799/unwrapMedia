# Performance and Resource Lifecycle Design

## Intent

Repeated browsing, frame stepping, comparison playback, and window closure must keep memory and external processes bounded. This pass targets concrete resource lifecycle defects found in the current code and records the remaining audit evidence. The work is a maintenance release, with no new heavy dependency and no change to parser output.

## Findings and root causes

1. `FrameThumbnailFilmstrip` adds every range request to a local list and only removes completed futures when the viewport emits another range. `FrameThumbnailDecoder` uses a fixed pool backed by an unbounded queue. Rapid scrolling can retain queued requests and callbacks; cancellation does not eagerly remove queued tasks.
2. `CompareVisualDiffView` requests two full size ffmpeg decodes for each 40 ms playback tick. Its `LaunchedEffect` neither cancels the returned futures nor rejects results from obsolete timestamps. This can queue many decodes and show an old result after a newer one.
3. `FfmpegImageSnapshotDecoder.decodeFirstFrameAsync` and `decodeEmbeddedHevcThumbnailAsync` default to a new daemon thread per request. Compare window fallbacks use that default; closing the window cannot cancel those decodes. Compare media loading also uses raw executor callbacks that can update disposed UI state.
4. Process tracking exists centrally in `ProcessManager`, and the shared process output reader enforces timeouts. We will audit remaining direct process starts for registration, timeout, interruption, and cleanup; change only proven violations.

## Behaviour

- Frame thumbnails stay lazy and keep the existing 8 MiB weighted cache. Viewport changes cancel obsolete batches; only a bounded number of current batches may wait or run. A cancelled batch stops ffmpeg, removes temporary files, and never publishes a stale bitmap. Completion must release pending indices so the visible range can retry.
- A/B video comparison may update at a bounded preview cadence while its 40 ms playback clock remains smooth. It holds the last rendered frame between preview samples. Changing files, leaving the view, or changing sample time cancels earlier decode requests and prevents old callbacks from replacing current bitmaps.
- One shot image fallback work is owned by a bounded worker pool or tab/window lifecycle. Closing the owner cancels it and its ffmpeg. Native image bitmaps and completed task references are released when their owner closes.
- Normal completed processes are reaped and unregistered; timeout and cancellation terminate descendants. Temporary artifacts are deleted after the child releases them, including on Windows.

## Verification

- Unit tests cover request coalescing/cancellation, stale results, and bounded queue behavior without requiring a UI display.
- Integration tests exercise real ffmpeg cancellation and process tracking where feasible, plus repeat open/close stress tests.
- Run focused tests, the full `:app:test` suite, and `:app:compileKotlin`; investigate any failure other than the documented flaky cache test.
- Inspect the final diff and verify the version appears as `1.25.0` in the Gradle package, UI constant, and update manifest. Release notes summarize the fixes in Korean.

## Constraints and scope

- Kotlin Compose Desktop and the existing JDK 21/ffmpeg stack only.
- UI work never waits for process termination or file I/O.
- Do not make a universal claim that every third party or native decoder is leak free. The release claims only the tested lifecycle paths.
- User authorized merge to `main` and push to `origin`; no push to `internal`.
