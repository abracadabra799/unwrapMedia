# A/V Sync Analyzer — Real Sync-Error Model — Design

Date: 2026-09-06
Status: Approved for planning
Scope: `AvSyncAnalyzer.kt` — the sync-point computation and drift-rate derivation. No UI change (the visualization + `avSyncVerdict` consume the improved data unchanged).

## Problem

`AvSyncAnalyzer.computeSyncPoints` pairs each sampled video packet with the audio
packet whose **PTS value** is closest, then reports `deltaMs = (vPts - aPts) * 1000`.
Because it picks the nearest PTS, that residual is always bounded by roughly one
audio-frame duration (~10–23 ms) — it measures PTS quantization, not A/V sync
error.

Consequences, verified with `~/Downloads/avsync_samples/`:

| Sample | What the tool shows now |
|---|---|
| `1_perfect_sync.mp4` | curve flat ~0, verdict "✅ 양호" — correct |
| `2_constant_offset_audio_ahead_120ms.mp4` (video track PTS shifted +0.12 s) | `initialSkewMs` metric card correctly shows **+119 ms** and the "초기 립싱크" diagnosis is CRITICAL, **but** the skew curve is flat ~0, `avSyncSegments` is all green, and `avSyncVerdict` says "✅ 양호" — directly contradicting the CRITICAL card below it |
| `3_progressive_drift.mp4` (audio clock 0.3 % fast → audio 169 ms shorter) | curve flat then a ramp only at the very end (where video PTS outruns the audio range); drift diagnosis fires but for the wrong reason |

The diagnosis cards (initial-skew / duration-mismatch / drift) and `overallSeverity`
already use the raw scalars (`initialSkewMs`, `durationDeltaSec`, `driftRateMsPerMin`)
correctly. The broken piece is `syncPoints` (the curve, the segment bar, and
`avSyncVerdict`, which all read from it) and `driftRateMsPerMin` (derived from the
broken deltas).

**What container-level PTS analysis can actually determine:** (a) the start
offset (`initialSkewMs`), (b) the total-length mismatch (`durationDeltaSec`),
(c) PTS discontinuities — jumps, gaps, non-monotonic runs. A stream that was
re-timestamped uniformly (e.g. by `atempo`) looks perfectly regular; its drift is
only visible as the total-length difference.

## Goal

Redefine `SyncPoint.deltaMs` to a model that reflects real, perceived A/V sync
error over the file, using only what PTS analysis can determine:

```
deltaMs(elapsed) = initialSkewMs                                        (start offset, constant)
                 + (durationDeltaSec * 1000 / totalDurationSec) * elapsed   (linear drift)
                 + localResidual                                        (PTS jump/gap spikes)
```

- `elapsed` = `vPts - vFirst` for the sampled video packet.
- `totalDurationSec = maxOf(videoDurationSec, audioDurationSec)`.
- `localResidual` = match the audio packet by **elapsed position**, not PTS value:
  target `aTarget = aFirst + elapsed`; take the audio packet nearest `aTarget`;
  `localResidual = (elapsed - (aMatchPts - aFirst)) * 1000`. ~0 for a clean file
  and for a uniformly re-timestamped (drift) stream — the linear term carries
  drift, so no double-count. It spikes only where audio PTS is discontinuous
  (a mid-file jump or gap).
  **Clamp:** compute `localResidual` only while `aTarget <= aLast` (`aLast` =
  `audioPackets.last().ptsSeconds`). Once `aTarget` runs past the end of the
  audio (truncated audio), set `localResidual = 0` — the linear drift term
  already accounts for the full tail mismatch; without the clamp the tail would
  double-count (baseline ramp + "no audio to match" residual).
- Sign convention unchanged: positive = audio leads (`SyncPoint.deltaMs` KDoc,
  `AvSyncAnalyzer.kt:126` "Audio Leads Video").

Expected per sample: #1 flat ~0; #2 **flat +119 ms**; #3 **linear 0 → +169 ms**;
a file with a mid-file audio gap → baseline + a spike there.

## Non-Goals

- Detecting sub-frame drift in a uniformly re-timestamped stream (impossible from PTS).
- Any change to the visualization (`AvSyncVisualization.kt`), `avSyncVerdict`,
  `avSyncSegments`, or `AvSyncAnalysisWindow.kt` — they consume the improved
  `syncPoints` / `driftRateMsPerMin` unchanged and start producing correct output.
- Content-based (waveform-correlation) sync measurement.
- Deduping the overlap between the "duration mismatch" (#2) and "progressive
  drift" (#3) diagnoses — pre-existing, out of scope.

## Changes

### `AvSyncAnalyzer.kt`

**New pure function** (`internal`, package `com.multiviewer.ui`):

```kotlin
/**
 * Modelled A/V sync error at a given elapsed position, in ms (positive = audio
 * leads). A constant start offset plus a linear drift derived from the
 * total-length mismatch — the two things container PTS analysis can determine.
 */
internal fun avSyncErrorModel(
    initialSkewMs: Double,
    durationDeltaSec: Double,
    totalDurationSec: Double,
    elapsedSec: Double,
): Double {
    val slopeMsPerSec = if (totalDurationSec > 0.0) durationDeltaSec * 1000.0 / totalDurationSec else 0.0
    return initialSkewMs + slopeMsPerSec * elapsedSec
}
```

**`computeSyncPoints`** — `private` → `internal`. New signature:

```kotlin
internal fun computeSyncPoints(
    videoPackets: List<StreamPacket>,
    audioPackets: List<StreamPacket>,
    initialSkewMs: Double,
    durationDeltaSec: Double,
    totalDurationSec: Double,
    targetSampleCount: Int = 120,
): List<SyncPoint>
```

Body: keep the `step` sampling over `videoPackets` and the trailing final-frame
point. For each sampled `vIdx`:

```kotlin
val vFirst = videoPackets.first().ptsSeconds
val aFirst = audioPackets.first().ptsSeconds
val aLast = audioPackets.last().ptsSeconds
// ... in the loop:
val vPts = videoPackets[vIdx].ptsSeconds
val elapsed = vPts - vFirst
// advance audioSearchIdx toward the ELAPSED target, not toward vPts
val aTarget = aFirst + elapsed
while (audioSearchIdx + 1 < audioCount &&
    abs(audioPackets[audioSearchIdx + 1].ptsSeconds - aTarget) <= abs(audioPackets[audioSearchIdx].ptsSeconds - aTarget)
) { audioSearchIdx++ }
val aPts = audioPackets[audioSearchIdx].ptsSeconds
val localResidual = if (aTarget <= aLast) (elapsed - (aPts - aFirst)) * 1000.0 else 0.0
val deltaMs = avSyncErrorModel(initialSkewMs, durationDeltaSec, totalDurationSec, elapsed) + localResidual
```

`SyncPoint` fields: `timeSeconds = vPts` (unchanged — the visualization's
`avSyncTimeSpan` already subtracts `t0`), `videoPts = vPts`, `audioPts = aPts`,
`deltaMs`, `videoFrameIndex = vIdx`, `audioPacketIndex = audioSearchIdx`. The
trailing final-frame block does the same math.

Note: `audioSearchIdx` is now monotonic against `aTarget` (which is monotonic in
`elapsed`), so the single forward scan is still valid — same as today.

**`analyze`** — three edits:

1. After `durationDeltaSec` is computed, add `val totalDurationSec = maxOf(videoDurationSec, audioDurationSec)`.
2. `computeSyncPoints(videoPackets, audioPackets)` → `computeSyncPoints(videoPackets, audioPackets, initialSkewMs, durationDeltaSec, totalDurationSec)`.
3. Replace the drift-rate block:
   ```kotlin
   val effectiveDurationMin = ...
   val firstDelta = syncPoints.firstOrNull()?.deltaMs ?: initialSkewMs
   val lastDelta = syncPoints.lastOrNull()?.deltaMs ?: initialSkewMs
   val driftRateMsPerMin = (lastDelta - firstDelta) / effectiveDurationMin
   ```
   with
   ```kotlin
   val driftRateMsPerMin =
       if (totalDurationSec > 0.0) durationDeltaSec * 1000.0 / (totalDurationSec / 60.0) else 0.0
   val firstDelta = syncPoints.firstOrNull()?.deltaMs ?: initialSkewMs
   val lastDelta = syncPoints.lastOrNull()?.deltaMs ?: initialSkewMs
   ```
   (`firstDelta` / `lastDelta` are still needed — the "progressive drift"
   diagnosis at `AvSyncAnalyzer.kt:201-203` prints them; they are now meaningful
   because `syncPoints` are.)

`maxSkewMs` / `minSkewMs` / `avgSkewMs` keep their existing derivation from
`syncPoints` — now meaningful.

### Downstream — no code change, corrected behaviour

- **Skew curve** / **segment bar**: now plot the real error. Sample 2 → flat
  +119 ms, bar red; sample 3 → ramp, bar green→orange→red.
- **`avSyncVerdict`**: sample 2 → all deltas ≈ 119, `driftRateMsPerMin` ≈ 0,
  signed range ≈ 0 → **pattern 4** ("오디오가 영상보다 일정하게 119ms 앞섬 —
  … -itsoffset …"). Sample 3 → `driftRateMsPerMin` ≈ 169 → **pattern 2** (drift).
  Sample 1 → pattern 1. Matches the diagnosis cards.
- **Diagnosis cards**: unchanged logic; the "progressive drift" card's
  first/last-delta lines now read correctly.

## Testing

New `app/src/test/kotlin/com/multiviewer/ui/AvSyncAnalyzerTest.kt` (pure — no
ffprobe):

- `avSyncErrorModel`:
  - `(0, 0, 60, e)` → 0 for any `e`.
  - `(119, 0, 60, e)` → 119 for any `e` (constant offset).
  - `(0, 0.169, 60, 60)` → ~169; `(0, 0.169, 60, 30)` → ~84.5 (linear).
  - `totalDurationSec = 0` → returns `initialSkewMs`, no divide-by-zero.
- `computeSyncPoints` with synthetic `StreamPacket` lists (helper to build a
  uniform packet run at a given start PTS / rate / count):
  - **Clean** (video + audio both 0..10 s, aligned): every `deltaMs` within ±5 ms
    of `initialSkewMs` (which is ~0).
  - **Constant offset** (video PTS starts at 0.12, audio at 0.0, same length):
    every `deltaMs` ≈ +120 ms (± one audio frame).
  - **Truncated audio** (video 0..10 s, audio 0..9.8 s uniform): first `deltaMs`
    ≈ 0, last `deltaMs` ≈ +200 ms (from the linear term only — assert it is NOT
    ~+400 ms, which is what a missing `aTarget <= aLast` clamp would give).
  - **Uniform drift** (video 0..10 s at 30 fps; audio re-timestamped uniformly to
    0..9.83 s but same packet count — i.e. `durationDeltaSec ≈ 0.17`, no gap):
    every `localResidual` ≈ 0, so `deltaMs` follows the pure linear baseline —
    first ≈ 0, last ≈ +170 ms.
  - **Audio PTS gap** (audio packets present 0..10 s but skip 3.0–3.5 s): the
    sync point nearest elapsed 3.25 s has `|deltaMs|` ≳ 150 ms while its
    neighbours are near baseline.
  - **Fewer video frames than `targetSampleCount`**: returns one point per frame,
    no crash, `step == 1`.
- No existing `AvSyncAnalyzer` test to update (there are none).
  `AvSyncVisualizationTest` is unaffected (synthetic `AvSyncReport`).

Full `./gradlew :app:test` green.

## Manual verification (owed)

Open each `~/Downloads/avsync_samples/` file in the running app's A/V sync window:
- #1 → PASS, flat curve, green bar, "✅ 양호".
- #2 → CRITICAL, curve flat at ~+119 ms with the worst-point callout, red bar,
  verdict "…일정하게 119ms 앞섬 … -itsoffset …", "초기 립싱크" diagnosis CRITICAL.
- #3 → curve ramps 0 → ~+169 ms, bar transitions green→red, verdict = drift,
  "점진적 드리프트" diagnosis fires.

## Rollback

Single file, additive + one function-signature change. Revert the commit(s); the
nearest-PTS behaviour returns. No schema, no persisted state.
