# Quality Compare Phase 3: 3-Way Comparison Mode — Design Spec

**Goal:** Extend the "품질 비교" (Quality Compare) window, shipped in Phase 1 with a fixed Reference/Comparison pair, to support the original design's full 3-input-slot model — Raw (optional), Encoded A (required), Encoded B (optional) — running whichever of the three comparison pairs (Raw↔A, Raw↔B, A↔B) the filled slots imply, with real-time resolution-mismatch feedback before the user commits to running anything.

**Context:** This is Phase 3 of the Quality Compare feature (see `docs/superpowers/specs/2026-08-15-quality-comparison-psnr-ssim-vmaf-design.md` for the original full-feature spec, and memory `project-streameye4-benchmarking-roadmap`). Phase 1 (PSNR/SSIM, single fixed pair, `docs/superpowers/plans/2026-08-15-quality-comparison-psnr-ssim.md`) shipped 2026-08-16. The user asked for the 3-way mode next, ahead of Phase 2 (VMAF) — this spec reorders the roadmap accordingly; VMAF remains a separate, later phase.

Phase 1 already built and shipped the reusable core: `QualityMetrics.kt` (`resolutionsMatch`, `runPsnrPass`, `runSsimPass`, all pure/synchronous, ffmpeg-backed), `MetricGraph.kt` (per-frame Canvas graph), `QualityExport.kt` (CSV/JSON writers). This phase's changes are almost entirely in `QualityCompareWindow.kt` (orchestration/UI) plus targeted additions to `QualityExport.kt` for multi-pair export. No changes to `QualityMetrics.kt`'s existing functions are needed — they already operate on one arbitrary `(comparison, reference)` file pair and are called once per pair, exactly as this phase needs.

## Scope

- **Three file slots, replacing Phase 1's two:**
  - **Raw** (optional) — the original/reference source.
  - **Encoded A** (required) — must be filled to run anything, matching Phase 1's existing "you need at least one comparison target" requirement.
  - **Encoded B** (optional) — a second encoded variant.
- **Which pairs run is fully determined by which slots are filled:**

  | Filled | Pairs run |
  |---|---|
  | Encoded A only | none (nothing to compare against) |
  | Raw + Encoded A | Raw↔A |
  | Encoded A + Encoded B | A↔B |
  | Raw + Encoded A + Encoded B | Raw↔A, Raw↔B, A↔B |

- **Real-time resolution pre-check:** whenever a candidate pair has both its files filled, a background `resolutionsMatch` check (Phase 1's existing function, unchanged) runs automatically — not gated behind clicking 비교 시작 — and the window shows a live per-pair status label: "일치" or "불일치 (건너뜀)". Changing any file slot immediately re-triggers the check for every pair that file participates in. This runs on the same dedicated single-thread executor Phase 1 already created (`qualityCompareExecutor`), never on the UI thread, with results marshaled back via `EventQueue.invokeLater` (Phase 1's existing convention).
- **비교 시작 (Run) is enabled once Encoded A is filled and at least one candidate pair currently shows "일치".** Only pairs currently showing "일치" are queued to run; a pair showing "불일치" (or not yet checked / still checking) is simply excluded, with its status visible to the user throughout — no separate confirmation dialog, since the always-visible per-pair status already tells the user what will happen before they click Run.
- **Metrics: PSNR and SSIM only**, unchanged from Phase 1 — no VMAF checkbox or availability-check work in this phase.
- **Sequential execution queue:** for each queued pair (in a fixed order: Raw↔A, Raw↔B, A↔B, skipping any not applicable/not matching), run PSNR then SSIM, exactly reusing Phase 1's `runPsnrPass`/`runSsimPass`. One process at a time, matching Phase 1's single dedicated executor.
- **Progress:** a current-pass label (e.g. "Raw vs Encoded A — PSNR") above a determinate progress bar (frame count, same as Phase 1), advancing automatically to the next pass/metric in the queue when one completes.
- **Cancel:** kills the in-flight ffmpeg process (Phase 1's existing `cancelRequested`/`isCancelled` wiring, unchanged) and skips all remaining queued passes — an all-or-nothing stop, extended from Phase 1's single-pass cancel to the whole queue. Already-completed pairs' results are kept and shown.
- **Results, grouped by pair then metric:** one section per pair that actually ran (in the fixed order above), each containing its PSNR stats+graph and SSIM stats+graph — mirrors Phase 1's per-metric results block, just repeated per pair with a pair-name heading (e.g. "Raw ↔ Encoded A").
- **Export:** one **JSON** file covering every pair that ran, nested `pairLabel → metricName → { statistics, perFrame }`; one **CSV** file per pair (same per-frame-table shape Phase 1 already writes, called once per pair with a pair-derived filename, e.g. `quality_compare_Raw_vs_Encoded_A.csv`).

## Technical Foundation

No new ffmpeg command/log-format verification is needed — this phase calls Phase 1's already-verified `resolutionsMatch`/`runPsnrPass`/`runSsimPass` unchanged, once per pair, exactly as they're already tested to behave for a single pair. The only new technical surface is:

- **Async, event-driven resolution checks** (as opposed to Phase 1's single synchronous check right before running): each relevant file-slot change must trigger a background recheck for every pair involving that slot, without blocking the UI or racing with an in-flight metric run. Implementation approach: a plain Kotlin function (not a Compose `LaunchedEffect`/coroutine — this codebase has no coroutines dependency; stick to Phase 1's `Executors`/`EventQueue.invokeLater` pattern) invoked after each file-picker callback, submitting one `resolutionsMatch` check per affected pair to the existing `qualityCompareExecutor`, posting the boolean result back via `EventQueue.invokeLater` into a per-pair status map (`mutableStateOf<Map<PairId, Boolean?>>`, `null` = not yet checked/checking).
- **Multi-pair JSON export shape:** extend `QualityExport.kt` with a new function alongside the existing single-pair `writeResultsJson`/`writeResultsCsv` (which remain usable as-is, called once per pair for CSV) — a `writeMultiPairResultsJson(destination: File, pairResults: Map<String, Map<String, MetricRunResult>>)` that nests Phase 1's existing per-metric JSON shape one level deeper under each pair's label. No new escaping/library concerns beyond what Phase 1 already established (pair labels come from this app's own fixed set: "Raw ↔ Encoded A", etc., not user input).

## Components

- **`ui/QualityCompareWindow.kt`** (modified) — the three-slot picker UI, per-pair resolution-status display, pair-determination logic, the sequential (pair × metric) run queue and its progress/cancel wiring, and the grouped-by-pair results view. This is the bulk of this phase's work; Phase 1's threading/state-marshaling conventions (dedicated executor, `EventQueue.invokeLater`, `AtomicBoolean` cancellation flag) carry over unchanged, just applied to a queue of passes instead of a fixed two.
- **`ui/QualityExport.kt`** (extended) — add `writeMultiPairResultsJson`; reuse existing `writeResultsCsv` called once per pair.
- **`ui/QualityMetrics.kt`** — unchanged. **`ui/MetricGraph.kt`** — unchanged (reused once per pair per metric).

## Error Handling

Extends Phase 1's established per-function-returns-null convention:
- A pair whose resolution check reports a mismatch is excluded from the run queue entirely — not attempted, not shown as a failure, just shown as "불일치 (건너뜀)" in its status label.
- A pair whose resolution check is still pending (background check not yet completed) is treated as not-yet-runnable — 비교 시작 only considers pairs already resolved to "일치" at the moment it's clicked.
- If a metric pass within the queue fails (`runPsnrPass`/`runSsimPass` returns `null` for a reason other than cancellation — e.g. process crash), that specific pair/metric's result is simply absent from the results view (matches Phase 1's existing single-pair failure handling), and the queue continues to the next pass rather than aborting the whole run.
- Cancellation stops the queue entirely (per the Scope section above) rather than skipping just the current pass.

## Testing

- `QualityExport.kt`'s new `writeMultiPairResultsJson` gets the same kind of synthetic-data unit test Phase 1's `QualityExportTest.kt` already uses for `writeResultsJson`/`writeResultsCsv` — hand-constructed `Map<String, Map<String, MetricRunResult>>` fixtures, asserting on the nested JSON string shape.
- Pair-determination logic (which of the 3 possible pairs are implied by which slots are filled) is a small pure function extractable from the UI layer — testable directly with `File?`/`File?`/`File?` combinations as input, asserting the expected pair-ID set, without needing a live Compose/ffmpeg environment.
- No automated test for the `QualityCompareWindow.kt` UI wiring itself, matching Phase 1's and this codebase's established convention for Compose UI tasks — manual verification only (multi-slot combinations, live status updates on file swap, cancel-mid-queue, multi-pair export).

## Out of Scope (Deferred)

- VMAF (remains its own future phase, per the original full-feature spec).
- Any resolution-mismatch handling beyond skip-and-label (no auto-scaling, no forced confirmation dialog — the always-visible per-pair status already serves that purpose).
- Concurrent/parallel pass execution (still one ffmpeg process at a time, sequential queue).
- Per-pass cancel (cancel remains all-or-nothing for the whole queue, per the design decision above).
- Saving/loading which slots+files were selected as a reusable preset.
