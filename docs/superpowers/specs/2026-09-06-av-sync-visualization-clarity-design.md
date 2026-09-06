# A/V Sync Visualization — Clarity Pass — Design

Date: 2026-09-06
Status: Approved for planning
Scope: `AvSyncAnalysisWindow` only. No change to `AvSyncAnalyzer` measurement logic (one data-model field removal).

## Problem

The A/V sync window (`AvSyncAnalysisWindow.kt`, ~876 lines) presents its findings as
four metric cards, a toggle between two Canvas visualizations, and a list of
diagnosis cards. The two visualizations are:

- **Skew Curve** (`AvSyncGraph`): Δt (Video PTS − Audio PTS) vs time, with
  green/orange/red comfort bands and a 0 ms baseline. It has **no axis labels, no
  tick values, no time markers, and no annotation of the worst point** — a
  correct chart that takes a viewer several seconds to decode ("is up bad? how
  many ms is that peak? where in the file?").
- **Dual-Lane Timeline** (`AvDualLaneTimeline`, ~190 lines): video frames on a
  top rail, audio packets on a bottom rail, cubic-bezier connectors per sync
  point. Novel, but the slant of a bezier connector is a poor encoding of "how
  far out of sync" and there is no evidence it reads faster than the curve.

Benchmark of how other tools show A/V sync:

| Tool | Approach |
|---|---|
| VLC (Track Sync) | A numeric ms slider only — no analysis, no visual |
| DaVinci Resolve / Premiere / PluralEyes | Stacked audio waveforms on a shared time ruler — you *see* transients line up; offset shown as a shift |
| Baton / Telestream Switch (broadcast QC) | A segment-colored bar spanning the whole file (green/yellow/red) + max-offset number + small trend graph |
| Wireshark (RTP/IAX2) | Per-packet jitter/delta line chart ≈ our current skew curve — engineer-facing |
| YouTube stats-for-nerds | A single live ms number |
| EBU R37 / ITU-R BT.1359 | The ±40 ms (audio ahead) / ±60 ms (audio behind) comfort zone, drawn as a green band |

Takeaway: the broadcast-QC **segment-colored bar** is the fastest "is it fine, and
where is it bad" read; the curve is the right *detail* view but needs axes and
annotation. The bezier dual-lane is the weakest of the three and can go.

## Goals

Make the visualization card a three-layer, hierarchical read:

1. **At-a-glance verdict** (non-expert): a segment-colored bar over the whole file
   duration + one plain-language sentence.
2. **Annotated detail** (expert): the skew curve, now with axes, tick values, a
   labeled ideal line, and a worst-point callout.
3. **Root cause** (expert): the existing diagnosis cards, unchanged.

The four metric cards and the diagnosis cards are untouched. No measurement-logic
change.

## Non-Goals

- Waveform-based / content-correlation visualization (the analysis is PTS-based;
  a waveform overlay is a separate, larger feature).
- Changing `AvSyncAnalyzer`'s probing or `SyncPoint` / diagnosis computation.
- New color palette — reuse this window's existing severity hex values verbatim
  (`0xFF2E7D32` green, `0xFFF57F17` orange, `0xFFC62828` red, and the softer
  point variants already in `AvSyncGraph`).
- Localization framework changes — Korean strings inline, matching the file.

## Layer 1: At-a-glance verdict

### Segment-colored bar — `AvSyncSegmentBar`

New `@Composable`, replaces the mode toggle + dual-lane at the top of the
visualization card.

- Divide the file timeline (`maxOf(videoDurationSec, audioDurationSec)`) into
  `segmentCount` equal buckets. `segmentCount` is derived from the Canvas pixel
  width at draw time: `(width / 4f).toInt().coerceIn(24, 160)` (≥ ~4 px per
  segment).
- Each segment's color from the **max |deltaMs|** of the `SyncPoint`s whose
  `timeSeconds` falls in that bucket:
  - `<= 40` → green `0xFF2E7D32`
  - `<= 100` → orange `0xFFF57F17`
  - `> 100` → red `0xFFC62828`
  - no sync points in the bucket → grey `0xFF3A3A3A` ("no data")
- Bar height ~28 dp, rounded corners, drawn as adjacent `drawRect`s.
- Below the bar: an X-axis with 4 evenly spaced time ticks (`0:00`, …, total) in
  `m:ss` format, drawn as short lines + `drawText`.
- Tap anywhere on the bar → `onSelectSyncPoint(nearest SyncPoint by time)` (same
  contract the curve already uses).
- If `selectedSyncPoint != null`, a thin yellow vertical line at that time over
  the bar.
- Empty `syncPoints` → render the bar all-grey with the ticks (no crash).

### Verdict sentence — `avSyncVerdict(report: AvSyncReport): String`

Pure function in the new file. Picks the first matching pattern (priority order):

| # | Condition | Sentence shape |
|---|---|---|
| 1 | `syncPoints` non-empty AND every `abs(deltaMs) <= 40` | `✅ 동기화 양호 — 전 구간 ±40ms 이내로 립싱크 문제 없음` |
| 2 | `abs(driftRateMsPerMin) > 5` | `🔴 시간이 갈수록 편차가 커집니다 — 분당 {rate|%.0f}ms씩 {벌어짐/좁혀짐}. {끝시각 m:ss} 지점에서 {last|%+.0f}ms. 클럭/타임스케일 불일치가 의심됩니다.` |
| 3 | `maxAbs - minAbs > 60` (spiky) where `maxAbs = maxOf(abs(deltaMs))`, `minAbs = minOf(abs(deltaMs))` | `⚠ {lo m:ss}–{hi m:ss} 구간에서 최대 {worst.deltaMs|%+.0f}ms까지 튑니다 — 해당 구간을 집중 확인하세요.` |
| 4 | else (roughly constant offset) | `⚠ 오디오가 영상보다 {avg|일정하게 %+.0f}ms {앞섬/뒤처짐} — 고정 지연이므로 -itsoffset 으로 교정 가능합니다.` |

- Pattern 3's `worst` = the `SyncPoint` with `maxOf(abs(deltaMs))`; `lo` / `hi` =
  `worst.timeSeconds ∓ clamp(totalDuration * 0.05, 1.0, 10.0)` seconds, each
  clamped to `[0, totalDuration]`, formatted with `formatMinSec`.
- Direction words: `deltaMs > 0` ⇒ "오디오 선행 / 앞섬"; `< 0` ⇒ "비디오 선행 / 뒤처짐" (matches the file's existing convention, e.g. line 164). Pattern 4 uses the sign of `report.avgSkewMs`.
- `avg` uses `report.avgSkewMs`; `last` = `syncPoints.last().deltaMs` (fallback `initialSkewMs`); rate = `report.driftRateMsPerMin`.
- If `!report.hasVideo || !report.hasAudio || syncPoints.isEmpty()` → the window
  already shows the "분석 불가" fallback before reaching this content, so the
  function may assume both streams and at least one sync point; still, guard with
  a benign default (`"동기화 데이터가 부족합니다."`) rather than throw.
- Rendered under the bar in `AppTypography.bodyMedium`, color = the window's
  `overallSeverity` color (reuse the `statusColor` when-block).

### Segments as data — `avSyncSegments(report: AvSyncReport, segmentCount: Int): List<SyncSeverity?>`

Pure function; returns `segmentCount` entries. `null` = no data (grey);
`PASS`/`WARNING`/`CRITICAL` map to green/orange/red by the same thresholds.
`AvSyncSegmentBar` calls this and maps to colors. Testable without Compose.

## Layer 2: Annotated skew curve

`AvSyncGraph` (moved to the new file, extended — bands, curve, points, tap-select,
selected-marker all keep working):

- **Y axis:** in the left `padX` gutter, tick labels for `+100, +40, 0, -40, -100`
  ms — but only those whose `toY(v)` lands inside `[padY, h-padY]` (yCeiling can
  be < 100). Small text via `drawText` / `TextMeasurer`. Above the top tick,
  `오디오 선행 ▲`; below the bottom, `비디오 선행 ▼` (tiny, `TextSecondary`).
- **X axis:** 4 evenly spaced ticks along the bottom, `m:ss`, short tick lines +
  labels.
- **Ideal line:** the existing 0 ms white baseline gets a right-aligned label
  `이상 (0ms)`.
- **Worst-point callout:** the `SyncPoint` with `max(abs(deltaMs))` gets a small
  label near its dot — `{deltaMs|%+.0f}ms @ {m:ss}` — placed above the dot,
  flipped below if it would clip the top, and x-clamped to stay in the plot.
  Skipped if `max(abs(deltaMs)) <= 40` (nothing worth calling out).
- `drawText` needs a `TextMeasurer` (`rememberTextMeasurer()`), passed into the
  Canvas draw scope via closure.

Legend under the card header keeps only the curve legend (green ±40ms / orange
±100ms / red >100ms) — the dual-lane legend branch is removed.

## Cleanup / removals

- Delete `AvDualLaneTimeline` (the whole `@Composable`).
- Delete `selectedVisualMode` state, the mode-switch `Row` of two `Surface`
  buttons, and the `if (selectedVisualMode == 0) … else …` branches in the card
  header text, subtitle, legend, and body.
- Delete `AvSyncReport.sampleVideoPackets` and `sampleAudioPackets` (only
  `AvDualLaneTimeline` reads them) and their assignment in
  `AvSyncAnalyzer.analyze` (`AvSyncAnalyzer.kt:255-256`). `analyze` still builds
  `videoPackets` / `audioPackets` locally for `computeSyncPoints`; it just stops
  storing the full lists on the report. Verified no other reader
  (`AiDiagnosticPromptBuilder` uses only the scalar fields + `diagnoses`).

## File structure

New file `app/src/main/kotlin/com/multiviewer/ui/AvSyncVisualization.kt`:

- `fun avSyncVerdict(report: AvSyncReport): String` (pure)
- `fun avSyncSegments(report: AvSyncReport, segmentCount: Int): List<SyncSeverity?>` (pure)
- `@Composable fun AvSyncSegmentBar(report, selectedPoint, onSelectPoint, modifier)`
- `@Composable fun AvSyncGraph(points, selectedPoint, onSelectPoint, modifier)` (moved from `AvSyncAnalysisWindow.kt` + extended)
- `@Composable fun LegendBadge(label, color)` (moved)
- Helper: `fun formatMinSec(seconds: Double): String` → `m:ss`

`AvSyncAnalysisWindow.kt` keeps the `Window` shell, `AvSyncReportContent`,
`MetricCard`, `DiagnosisCard` — expected to drop from ~876 to ~470 lines.

## Testing

New `app/src/test/kotlin/com/multiviewer/ui/AvSyncVisualizationTest.kt` (pure
functions only — no Compose):

- Helper to build a synthetic `AvSyncReport` from a `List<SyncPoint>` + scalar
  overrides.
- `avSyncVerdict`:
  - all points `<= 40 ms` → contains `"양호"` / `"±40ms"`
  - `driftRateMsPerMin = 12.0` → contains `"분당"` and `"12ms"`
  - spiky (points `[5, 5, 5, 210, 5]` ms) → contains `"튑니다"` and `"210"`
  - constant `avgSkewMs = -85, driftRate = 0.5` → contains `"뒤처짐"` and `"-itsoffset"`
  - empty `syncPoints` → the benign default, no exception
- `avSyncSegments`:
  - all-green points, `segmentCount = 10` → 10 entries, all `PASS`
  - a red spike at t≈mid → the middle segment is `CRITICAL`, edges `PASS`
  - a time range with no points → those segments are `null`
  - `segmentCount = 1` and `segmentCount` larger than point count → no
    index-out-of-bounds, length always `== segmentCount`
- `formatMinSec`: `0.0 → "0:00"`, `65.4 → "1:05"`, `3599.9 → "59:59"`

Existing tests: no `AvSync*` test exists today; nothing to update. Full
`./gradlew :app:test` green.

## Rollback

UI-only + one dead-field removal. Revert the commit(s); the toggle + dual-lane
return. No persisted state, no schema.
