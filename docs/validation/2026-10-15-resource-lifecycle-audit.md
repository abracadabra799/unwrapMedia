# Resource lifecycle audit — 2026-10-15

## Scope and method

Searched all `ProcessBuilder` call sites (42 textual occurrences), streaming stdout readers, worker pools, thumbnail caches, and comparison window callbacks. Traced each selected request from UI ownership through worker cancellation and process teardown. Reproduced the silent-process and queued-work cases with tests before changing code.

## Confirmed findings and changes

| Path | Root cause | Change | Verification |
|---|---|---|---|
| GOP filmstrip | Every viewport change left older range futures in a list until another viewport emission; fixed pool queue kept canceled work | One owned range per filmstrip; cancel previous range and reject late UI callbacks | `FrameThumbnailDecoderTest` |
| A/B video comparison | 40 ms playback tick launched two native frame decodes and allowed late results | At most four preview timestamps per second; dispose cancels both jobs and rejects late results | `ComparisonPreviewCadenceTest` |
| A/B image loading | Raw comparison worker and fallback daemon thread had no window owner | File-slot `CompareLoadSession` cancels parse and nested ffmpeg jobs on change/close | `CompareLoadSessionTest`, `FfmpegImageSnapshotDecoderTest` |
| Fixed decode pools | `Executors.newFixedThreadPool` retains canceled queued `Future`s until a worker dequeues them | Purge canceled queue entries before each new thumbnail/frame/image request | `DecodeWorkerPoolTest` |
| Thumbnail disk cache | Filesystem timestamp ties and clock skew could evict a recently read thumbnail, causing repeat decodes | Track successful accesses within the cache instance and retain timestamp order for untouched persisted entries | `ThumbnailDiskCacheTest` |
| Streaming analysis | A timeout after blocking `readLine` cannot fire if the child never writes output | `ProcessManager.watchCancellation` has a 30 minute deadline; metric pass uses its 10 minute limit before reading | `ProcessManagerTest`, `QualityMetricsTest` |
| Incomplete ffprobe scan | A timeout or failed process exit could cache partial frames or report an empty bitstream scan as PASS | Require exit code zero before caching frame/packet results; incomplete bitstream coverage is WARNING | `FrameTypeAnalyzerTest`, `AvSyncAndCorruptionScannerTest` |
| Slow comparison decode | Canceling every sample could prevent any frame from appearing if decode time exceeds 250 ms | Keep one in-flight decode per side, publish its result, then request only the latest pending timestamp | `ComparisonPreviewCadenceTest` |
| Native image | One shot ffmpeg snapshot did not close the encoded Skia `Image` after conversion | Close it immediately after creating the Compose bitmap | Existing image decode tests |

## Process classification

- Media probes and decoders use `ProcessManager` registration, timeout or cancellation, and cleanup. The streaming paths previously using cancellation watchers now also have deadlines.
- Browser, terminal, and installer launch commands in `AiCliDetector` and `UpdateChecker` intentionally start an external application that outlives the launching command; treating that application as an orphan media process would be incorrect.
- Live playback and interactive terminal sessions are intentionally long lived and are owned by their window/session teardown. The tests cover media child shutdown and terminal session teardown separately.

## Limits

Automated tests on macOS cover the changed cancellation paths. Windows file-lock behavior and GUI playback responsiveness require a Windows/manual run. This audit does not prove that every third-party native decoder or external application is leak free.
