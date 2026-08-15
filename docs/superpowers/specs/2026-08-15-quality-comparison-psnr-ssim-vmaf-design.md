# Quality Comparison Tool (PSNR/SSIM/VMAF) — Design Spec

**Goal:** Add a standalone "품질 비교" (Quality Compare) tool that runs ffmpeg-based PSNR/SSIM/VMAF quality metrics between a raw/original media file and one or two encoded versions, producing report-ready statistical summaries and per-frame graphs, for use as encoder benchmarking material (this project's ongoing goal of matching Elecard StreamEye 4-style analysis capability).

**Context:** This is item 2 of the project's benchmarking roadmap (see memory `project-streameye4-benchmarking-roadmap`), independent of the AV1 codec-support work (items 1a/1b, already shipped). No prior spec or plan exists for this feature — this is the first design pass.

## Scope

- **Entry point:** a new top-level `MenuBar` item, "품질 비교" (Quality Compare), always enabled regardless of whether any tab is open — mirrors the existing "비트스트림 추출"/"프레임 간격 분석" menu items. Clicking it opens a standalone pop-out `Window`, fully independent of `AppState.tabs`/`TabState` — same architectural pattern as `FrameIntervalAnalysisWindow` (its own `Window(...)` composable, own state, opened/closed via a boolean flag on `AppState`, no dependency on any tab being open).
- **Unified video/image handling:** no separate "image mode" vs. "video mode" — a still image is just the 1-frame case of the same ffmpeg-based frame comparison. One code path handles both, since ffmpeg's `psnr`/`ssim`/`libvmaf` filters operate identically on a single-frame input (a JPEG/PNG) and a multi-frame input (an MP4).
- **Three input configurations**, exactly as specified:

  | Input | Comparisons run | Meaning |
  |---|---|---|
  | Raw + Encoded A | Raw ↔ A | Quality vs. original |
  | Encoded A + Encoded B | A ↔ B | Difference between two encoded results |
  | Raw + Encoded A + Encoded B | Raw↔A, Raw↔B, A↔B | Comprehensive encoder A/B comparison |

  The compare window has 3 file-picker slots (Raw, Encoded A, Encoded B); Raw and Encoded B are optional, Encoded A is required. Which slots are filled determines which mode runs and which comparison pairs are computed.
- **Selectable metrics:** PSNR, SSIM, VMAF — independently toggleable checkboxes, all on by default. VMAF's checkbox is enabled/disabled based on a lazy, one-time, cached runtime check of whether the resolved ffmpeg binary actually supports the `libvmaf` filter (see Technical Foundation). This check runs only when the Compare window is first opened in a session — **never** at app startup and **never** on the file-open path — so this feature has zero impact on existing app-launch or file-open performance, and requires no new bundled dependency (uses whatever `ffmpeg`/`ffprobe` `FfmpegLocator` already resolves).
- **Resolution/duration mismatch handling:** before running a comparison pair, resolutions are checked via `ffprobe`. A mismatched pair is rejected with a clear inline error and simply excluded from the results (no auto-scaling) — in Raw+A+B mode, if only B's resolution mismatches, Raw↔A and Raw↔B still run normally. Duration mismatches are not separately validated; ffmpeg's metric filters naturally stop at the shorter input's length.
- **Metric execution strategy:** one separate ffmpeg process per selected metric per comparison pair, run sequentially (not a single combined filter-graph pass). Chosen deliberately over a combined single-pass approach for simplicity, testability, and failure isolation — see Technical Foundation for the trade-off. Each pass writes a per-frame stats log; a failure in one pass (e.g. VMAF erroring on an unusual pixel format) doesn't abort the other selected metrics or other comparison pairs.
- **Progress and cancellation:** each running ffmpeg pass reports determinate progress (current frame / total frames, from `-progress` output) with a visible progress bar; a cancel button kills the in-flight process. Total frame count for the progress denominator comes from an `ffprobe` pre-check (already an established pattern in this codebase via `probeFrameTypes`).
- **Results:** per comparison pair, per selected metric: aggregate statistics (min, max, mean, median — not just an average, since results are meant to serve as performance-report material) plus a per-frame line graph (metric value vs. frame index). In Raw+A+B mode, all three pairs' results are shown side by side.
- **Export:** an Export button writes both the per-frame time-series data and the aggregate statistics to CSV and/or JSON, for external reporting/archiving.

## Technical Foundation

**Not yet validated against real ffmpeg output** — unlike this project's codec-parsing specs (H.264/HEVC/AV1), which were validated bit-for-bit against real captured files before their plans were written, this feature's exact ffmpeg command syntax, log file formats, and progress-output format have not yet been run and inspected during this brainstorming pass. The implementation plan for this spec **must** open with a verification pass (mirroring the AV1 plan's own "acquire a real test file and cross-verify" precedent) before any Kotlin code is written:

- Confirm exact `psnr`/`ssim` filter invocation and stats-file line format against a real ffmpeg run (expected shape, to be verified: `ffmpeg -i <distorted> -i <reference> -lavfi "psnr=stats_file=psnr.log" -f null -`, producing one line per frame like `n:1 mse_avg:... psnr_avg:... psnr_y:... psnr_u:... psnr_v:...`; `ssim` analogous, producing `n:1 Y:... U:... V:... All:... (dB)` per frame).
- Confirm exact `libvmaf` filter invocation and its JSON log structure (expected shape: `ffmpeg -i <distorted> -i <reference> -lavfi "libvmaf=log_path=vmaf.json:log_fmt=json" -f null -`, producing a JSON document with a `frames` array, each entry holding a `metrics.vmaf` score).
- Confirm which input is "reference" vs. "distorted" in each filter's argument order — this determines which file must be `-i` index 0 vs. 1, and getting it backwards wouldn't necessarily error, just silently label results wrong.
- Confirm `-progress pipe:1`'s key=value output format (`frame=N`, `out_time_ms=...`, etc.) for progress-bar parsing.
- Confirm a reliable way to detect `libvmaf` availability in the resolved ffmpeg binary (candidate: `ffmpeg -filters` output containing `libvmaf`, or `ffmpeg -version`'s build configuration string containing `--enable-libvmaf` — pick whichever proves more reliable across the bundled Windows/Linux builds and macOS's PATH-resolved Homebrew build).
- Confirm behavior when input resolutions mismatch (expected: ffmpeg exits non-zero with a filter-graph error) so the pre-flight `ffprobe` resolution check can be validated as sufficient to prevent this rather than needing to also parse ffmpeg's own error output.

## Components

- **`ui/QualityCompareWindow.kt`** (new) — the standalone pop-out `Window` composable, opened via a new `qualityCompareWindowOpen: Boolean` flag on `AppState` (mirroring `frameIntervalWindowOpen`). Owns its own local state: 3 file-picker slots, metric checkboxes, run/cancel button, results display, export button. Not backed by `TabState`.
- **`parser/QualityMetrics.kt`** (new) — functions to invoke each ffmpeg metric pass (`runPsnrPass`, `runSsimPass`, `runVmafPass`, each `(distorted: File, reference: File, progressCallback: (Int, Int) -> Unit) -> MetricResult?`), returning `null`/a safe partial result on failure per this codebase's established convention, never throwing to the caller. The exact shared `MetricResult` shape (per-frame series + aggregate stats) is defined at plan-writing time once the Technical Foundation verification pass confirms the real log formats.
- **Log/stats parsers** — one parser per metric's log format (PSNR/SSIM stats-file line format, VMAF's JSON), each producing a common per-frame time-series representation plus computed aggregate statistics (min/max/mean/median).
- **Progress plumbing** — reuses the `ProcessBuilder`/`FfmpegLocator.configureEnvironment` pattern already established (e.g. `TrackExtractor.kt`), but reading `-progress` output incrementally (not `waitFor` + discard, since this needs live progress) and exposing a cancel path (`Process.destroyForcibly()`) wired to the UI's cancel button. Runs on its own dedicated background executor, not the shared 2-thread pool `AppState`'s existing background tasks use (`ui/BackgroundTask.kt`), since these passes are expected to run much longer than existing background work.
- **VMAF availability check** — a small, cached (computed once per app session, on first Compare-window open) function checking the resolved ffmpeg binary for `libvmaf` support, used to enable/disable the VMAF checkbox.
- **Export** — CSV/JSON writers for the per-frame + summary statistics data, triggered by the results view's Export button, using the same native `FileDialog` (SAVE mode) pattern already used elsewhere in this codebase for save dialogs.

## Error Handling

Matches this codebase's established convention throughout: every ffmpeg-invoking function catches its own exceptions and returns `null`/a safe partial result rather than propagating exceptions to its caller or crashing the UI. Specific cases:
- ffprobe resolution pre-check fails or reports a mismatch → that comparison pair is skipped with an inline error message; other pairs/metrics still run.
- An individual metric pass fails (process crash, unexpected exit code, malformed log output) → that specific metric's result is shown as failed/unavailable for that pair; other metrics and other pairs are unaffected.
- User cancels mid-run → the in-flight process is killed; already-completed passes' results are retained and shown; the cancelled pass is marked as cancelled, not as a failure.
- `libvmaf` unavailable → VMAF checkbox disabled with an explanatory tooltip; feature works fully with PSNR/SSIM only.

## Testing

Follows this codebase's established real-fixture testing convention (as used for H.264/HEVC/AV1 parsing work):
- Small real encoded test clips (generated via `ffmpeg`/`libsvtav1` or similar, matching this project's existing test-fixture generation approach) with known PSNR/SSIM/VMAF values, cross-checked against ffmpeg's own CLI output run manually during plan-writing (mirroring the AV1 plan's fixture-derivation rigor).
- Synthetic fixtures for the log-parsing logic specifically: hand-constructed PSNR/SSIM stats-file content and VMAF JSON content with known expected parsed values and aggregate statistics, so the parsing/statistics logic can be unit-tested without invoking real ffmpeg processes for every test run.
- Resolution-mismatch handling: synthetic `ffprobe` output fixtures (or a deliberately mismatched real file pair) confirming the pre-flight check correctly skips a pair rather than letting ffmpeg fail uninformatively.
- Progress-parsing logic: synthetic `-progress` output fixtures, tested independently of a live ffmpeg process.
- No automated test can cover the actual `QualityCompareWindow.kt` UI interaction (matches this codebase's established convention that Compose UI wiring tasks have no automated tests) — manual verification only, same as other UI-wiring work in this codebase.

## Out of Scope (Deferred)

- Auto-scaling mismatched resolutions before comparison (v1 rejects mismatches instead).
- VMAF model selection (custom/4K models) — v1 uses ffmpeg's default VMAF model only.
- Combined single-pass ffmpeg execution (one filter graph computing all selected metrics from one decode) — deferred as a possible future optimization if separate-pass performance proves inadequate in practice.
- Running multiple comparisons concurrently, or multiple Compare windows open simultaneously.
- Saving/loading comparison configurations (which files/metrics were selected) as a reusable preset.
- Any bundling change to include `libvmaf` in packaged builds if it turns out to be missing — v1 ships with graceful degradation (VMAF simply unavailable) rather than blocking on a packaging investigation; the bundling question itself is deferred to a follow-up if it turns out to matter in practice.
