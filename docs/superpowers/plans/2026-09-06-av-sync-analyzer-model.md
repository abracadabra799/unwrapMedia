# A/V Sync Analyzer Real-Error Model — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `AvSyncAnalyzer.computeSyncPoints`'s nearest-PTS matching (which yields quantization noise, not sync error) with a model that reflects real perceived A/V sync error: a constant start offset + linear drift + a clamped local residual for PTS jumps/gaps.

**Architecture:** One new `internal` pure function `avSyncErrorModel`. `computeSyncPoints` goes `private`→`internal`, gains three model params, and matches audio by elapsed position instead of PTS value. `analyze` computes `totalDurationSec` and redefines `driftRateMsPerMin`. No downstream code changes — the visualization, `avSyncVerdict`, `avSyncSegments`, and the diagnosis cards all consume the improved data unchanged.

**Tech Stack:** Kotlin, JUnit 5. No new dependencies.

## Global Constraints

- Kotlin 2.0.21; do NOT touch `app/build.gradle.kts`.
- Only `app/src/main/kotlin/com/multiviewer/ui/AvSyncAnalyzer.kt` changes in `main`; one new test file.
- `SyncPoint.deltaMs` sign convention unchanged: positive = audio leads (see `AvSyncAnalyzer.kt:126`, `SyncPoint` KDoc).
- Do NOT change `AvSyncVisualization.kt`, `AvSyncAnalysisWindow.kt`, `AvSyncVisualizationTest.kt`, or the three diagnosis-card blocks' logic.
- `./gradlew :app:test` must pass at the end of every task.
- Commit messages END with these two lines exactly:
  `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo`

---

### Task 1: Real sync-error model in `AvSyncAnalyzer`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AvSyncAnalyzer.kt`
- Create: `app/src/test/kotlin/com/multiviewer/ui/AvSyncAnalyzerTest.kt`

**Interfaces:**
- Consumes: `StreamPacket`, `SyncPoint` (same file).
- Produces (`internal`, package `com.multiviewer.ui`):
  - `fun avSyncErrorModel(initialSkewMs: Double, durationDeltaSec: Double, totalDurationSec: Double, elapsedSec: Double): Double`
  - `fun computeSyncPoints(videoPackets: List<StreamPacket>, audioPackets: List<StreamPacket>, initialSkewMs: Double, durationDeltaSec: Double, totalDurationSec: Double, targetSampleCount: Int = 120): List<SyncPoint>`

- [ ] **Step 1: Write `AvSyncAnalyzerTest.kt` (failing)**

```kotlin
package com.multiviewer.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AvSyncAnalyzerTest {

    /** Uniform packet run: [count] packets starting at [startPts], spaced [step] s. */
    private fun run(type: String, startPts: Double, step: Double, count: Int): List<StreamPacket> =
        (0 until count).map { i ->
            StreamPacket(
                mediaType = type,
                ptsSeconds = startPts + i * step,
                dtsSeconds = null,
                durationSeconds = step,
                sizeBytes = null,
                pos = null,
            )
        }

    // ---- avSyncErrorModel ----

    @Test
    fun errorModel_perfect_isZeroEverywhere() {
        assertEquals(0.0, avSyncErrorModel(0.0, 0.0, 60.0, 0.0), 1e-9)
        assertEquals(0.0, avSyncErrorModel(0.0, 0.0, 60.0, 45.0), 1e-9)
    }

    @Test
    fun errorModel_constantOffset_isFlat() {
        assertEquals(119.0, avSyncErrorModel(119.0, 0.0, 60.0, 0.0), 1e-9)
        assertEquals(119.0, avSyncErrorModel(119.0, 0.0, 60.0, 60.0), 1e-9)
    }

    @Test
    fun errorModel_lengthMismatch_isLinear() {
        assertEquals(169.0, avSyncErrorModel(0.0, 0.169, 60.0, 60.0), 0.5)
        assertEquals(84.5, avSyncErrorModel(0.0, 0.169, 60.0, 30.0), 0.5)
    }

    @Test
    fun errorModel_zeroTotalDuration_returnsInitialSkew_noDivideByZero() {
        assertEquals(50.0, avSyncErrorModel(50.0, 0.2, 0.0, 10.0), 1e-9)
    }

    // ---- computeSyncPoints ----

    @Test
    fun syncPoints_cleanAlignedFile_allDeltasNearZero() {
        val v = run("video", 0.0, 1.0 / 30, 300)   // 10 s @ 30 fps
        val a = run("audio", 0.0, 0.021, 476)       // ~10 s
        val pts = computeSyncPoints(v, a, initialSkewMs = 0.0, durationDeltaSec = 0.0, totalDurationSec = 10.0)
        assertTrue(pts.isNotEmpty())
        assertTrue(pts.all { abs(it.deltaMs) < 25.0 }, "max |delta| = ${pts.maxOf { abs(it.deltaMs) }}")
    }

    @Test
    fun syncPoints_constantOffset_allDeltasNearOffset() {
        val v = run("video", 0.12, 1.0 / 30, 300)   // video PTS starts at 0.12
        val a = run("audio", 0.0, 0.021, 476)
        val pts = computeSyncPoints(v, a, initialSkewMs = 120.0, durationDeltaSec = 0.0, totalDurationSec = 10.0)
        assertTrue(pts.all { abs(it.deltaMs - 120.0) < 25.0 }, "deltas: ${pts.map { it.deltaMs.toInt() }}")
    }

    @Test
    fun syncPoints_truncatedAudio_tailIsLinearNotDoubleCounted() {
        val v = run("video", 0.0, 1.0 / 30, 300)     // video 0..10 s
        val a = run("audio", 0.0, 0.021, 466)         // audio ~0..9.8 s
        val pts = computeSyncPoints(v, a, initialSkewMs = 0.0, durationDeltaSec = 0.2, totalDurationSec = 10.0)
        assertTrue(abs(pts.first().deltaMs) < 25.0)
        val last = pts.last().deltaMs
        assertTrue(last in 150.0..260.0, "last delta = $last (expected ~200, NOT ~400 which means the aTarget<=aLast clamp is missing)")
    }

    @Test
    fun syncPoints_uniformDrift_followsLinearBaseline() {
        val v = run("video", 0.0, 1.0 / 30, 300)                 // video 0..10 s
        val a = run("audio", 0.0, 9.83 / 476, 476)               // same count, re-timestamped to 0..9.83
        val pts = computeSyncPoints(v, a, initialSkewMs = 0.0, durationDeltaSec = 0.17, totalDurationSec = 10.0)
        assertTrue(abs(pts.first().deltaMs) < 20.0, "first = ${pts.first().deltaMs}")
        assertTrue(pts.last().deltaMs in 130.0..210.0, "last = ${pts.last().deltaMs}")
        // monotonic-ish ramp: the middle point sits between first and last
        val mid = pts[pts.size / 2].deltaMs
        assertTrue(mid > pts.first().deltaMs && mid < pts.last().deltaMs, "mid = $mid")
    }

    @Test
    fun syncPoints_audioPtsGap_spikesAtTheGap() {
        val v = run("video", 0.0, 1.0 / 30, 300)
        // audio 0..3.0 s, then a 0.5 s hole, then 3.5..10 s
        val a = run("audio", 0.0, 0.021, 143) + run("audio", 3.5, 0.021, 310)
        val pts = computeSyncPoints(v, a, initialSkewMs = 0.0, durationDeltaSec = 0.0, totalDurationSec = 10.0)
        val nearGap = pts.minByOrNull { abs(it.timeSeconds - 3.25) }!!
        val awayFromGap = pts.first { it.timeSeconds > 6.0 }
        assertTrue(abs(nearGap.deltaMs) > 120.0, "at gap: ${nearGap.deltaMs}")
        assertTrue(abs(awayFromGap.deltaMs) < 40.0, "away from gap: ${awayFromGap.deltaMs}")
    }

    @Test
    fun syncPoints_fewerFramesThanSampleCount_onePointPerFrameNoCrash() {
        val v = run("video", 0.0, 1.0 / 30, 10)
        val a = run("audio", 0.0, 0.021, 20)
        val pts = computeSyncPoints(v, a, 0.0, 0.0, 1.0, targetSampleCount = 120)
        assertEquals(10, pts.size)
    }

    @Test
    fun syncPoints_emptyInput_returnsEmpty() {
        assertTrue(computeSyncPoints(emptyList(), run("audio", 0.0, 0.021, 10), 0.0, 0.0, 1.0).isEmpty())
        assertTrue(computeSyncPoints(run("video", 0.0, 0.033, 10), emptyList(), 0.0, 0.0, 1.0).isEmpty())
    }
}
```

- [ ] **Step 2: Run — expect failure**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.AvSyncAnalyzerTest"`
Expected: FAIL — `avSyncErrorModel` unresolved; `computeSyncPoints` is `private` (unresolved from the test) and has the old signature.

- [ ] **Step 3: Add `avSyncErrorModel` to `AvSyncAnalyzer.kt`**

Add it as a top-level `internal` function in the file, above `object AvSyncAnalyzer` (just after the `AvSyncReport` data class):

```kotlin
/**
 * Modelled A/V sync error at a given elapsed position, in ms (positive = audio
 * leads). A constant start offset plus a linear drift derived from the total
 * length mismatch — the two things container PTS analysis can determine.
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

- [ ] **Step 4: Rewrite `computeSyncPoints`**

Replace the entire existing `private fun computeSyncPoints(...) { ... }` (from
`private fun computeSyncPoints(` through its closing `}` — currently lines
~260–322, ending with `return points`) with:

```kotlin
    internal fun computeSyncPoints(
        videoPackets: List<StreamPacket>,
        audioPackets: List<StreamPacket>,
        initialSkewMs: Double,
        durationDeltaSec: Double,
        totalDurationSec: Double,
        targetSampleCount: Int = 120,
    ): List<SyncPoint> {
        if (videoPackets.isEmpty() || audioPackets.isEmpty()) return emptyList()

        val vFirst = videoPackets.first().ptsSeconds
        val aFirst = audioPackets.first().ptsSeconds
        val aLast = audioPackets.last().ptsSeconds

        val step = (videoPackets.size / targetSampleCount).coerceAtLeast(1)
        val points = ArrayList<SyncPoint>(targetSampleCount + 2)

        var audioSearchIdx = 0
        val audioCount = audioPackets.size

        // Modelled error at [vIdx], matching audio by ELAPSED position (not PTS
        // value) so drift and offset actually register, plus a local residual
        // that spikes on PTS discontinuities. The residual is clamped off once
        // aTarget runs past the audio (truncation) so the linear term doesn't
        // double-count the tail.
        fun pointAt(vIdx: Int): SyncPoint {
            val vPts = videoPackets[vIdx].ptsSeconds
            val elapsed = vPts - vFirst
            val aTarget = aFirst + elapsed
            while (audioSearchIdx + 1 < audioCount &&
                abs(audioPackets[audioSearchIdx + 1].ptsSeconds - aTarget) <= abs(audioPackets[audioSearchIdx].ptsSeconds - aTarget)
            ) {
                audioSearchIdx++
            }
            val aPts = audioPackets[audioSearchIdx].ptsSeconds
            val localResidual = if (aTarget <= aLast) (elapsed - (aPts - aFirst)) * 1000.0 else 0.0
            val deltaMs = avSyncErrorModel(initialSkewMs, durationDeltaSec, totalDurationSec, elapsed) + localResidual
            return SyncPoint(
                timeSeconds = vPts,
                videoPts = vPts,
                audioPts = aPts,
                deltaMs = deltaMs,
                videoFrameIndex = vIdx,
                audioPacketIndex = audioSearchIdx,
            )
        }

        for (vIdx in videoPackets.indices step step) {
            points.add(pointAt(vIdx))
        }
        if (videoPackets.size > 1 && (videoPackets.size - 1) % step != 0) {
            points.add(pointAt(videoPackets.size - 1))
        }

        return points
    }
```

- [ ] **Step 5: Wire `analyze` — `totalDurationSec`, the `computeSyncPoints` call, and `driftRateMsPerMin`**

In `analyze`, after `val durationDeltaSec = videoDurationSec - audioDurationSec`, add:

```kotlin
            val totalDurationSec = maxOf(videoDurationSec, audioDurationSec)
```

Change the sync-points call from
`val syncPoints = computeSyncPoints(videoPackets, audioPackets)`
to
`val syncPoints = computeSyncPoints(videoPackets, audioPackets, initialSkewMs, durationDeltaSec, totalDurationSec)`

Replace the drift-rate block (currently):

```kotlin
            // Drift rate calculation (change in delta over duration)
            val effectiveDurationMin = (videoDurationSec.coerceAtLeast(audioDurationSec) / 60.0).coerceAtLeast(0.01)
            val firstDelta = syncPoints.firstOrNull()?.deltaMs ?: initialSkewMs
            val lastDelta = syncPoints.lastOrNull()?.deltaMs ?: initialSkewMs
            val driftRateMsPerMin = (lastDelta - firstDelta) / effectiveDurationMin
```

with:

```kotlin
            // Drift rate: the linear-model slope, per minute (durationDeltaSec is
            // the only reliable drift signal from container PTS).
            val firstDelta = syncPoints.firstOrNull()?.deltaMs ?: initialSkewMs
            val lastDelta = syncPoints.lastOrNull()?.deltaMs ?: initialSkewMs
            val driftRateMsPerMin =
                if (totalDurationSec > 0.0) durationDeltaSec * 1000.0 / (totalDurationSec / 60.0) else 0.0
```

(`firstDelta` / `lastDelta` are still read by the "progressive drift" diagnosis
at `AvSyncAnalyzer.kt:201-203` — keep them.)

- [ ] **Step 6: Run the analyzer test — expect pass**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.AvSyncAnalyzerTest"`
Expected: PASS (14 tests).

- [ ] **Step 7: Full suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`. `AvSyncVisualizationTest` unaffected (synthetic `AvSyncReport`). Confirm no other test constructs `computeSyncPoints` — grep: `grep -rn "computeSyncPoints" app/src` should show only `AvSyncAnalyzer.kt` and the new test.

- [ ] **Step 8: Visual smoke check**

Run: `./gradlew :app:run`. Open each `~/Downloads/avsync_samples/*.mp4` in the A/V sync window:
- `1_perfect_sync.mp4` → PASS, curve flat ~0, green bar, verdict "✅ 양호".
- `2_constant_offset_audio_ahead_120ms.mp4` → curve flat at ~+119 ms (worst-point callout visible), red bar, verdict "…일정하게 119ms 앞섬 … -itsoffset …", "초기 립싱크" diagnosis CRITICAL, 종합 상태 CRITICAL.
- `3_progressive_drift.mp4` → curve ramps 0 → ~+169 ms, bar green→red, verdict = drift ("분당 …ms씩 벌어짐"), "점진적 드리프트" or "트랙 길이" diagnosis fires.

Close the app. Record what you saw (or, if you can't drive the GUI, that it launched clean and the reasoning above is sound).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AvSyncAnalyzer.kt \
        app/src/test/kotlin/com/multiviewer/ui/AvSyncAnalyzerTest.kt
git commit -m "$(cat <<'EOF'
fix: A/V sync analyzer measures real sync error, not PTS quantization noise

computeSyncPoints matched each video packet to the nearest-PTS audio packet, so
deltaMs was always sub-audio-frame — a constant offset or drift never showed in
the curve, the segment bar, or avSyncVerdict (which then contradicted the
CRITICAL diagnosis card). Now: initialSkew + linear drift (durationDelta over
total length) + a local residual (elapsed-position match, clamped past the audio
end) for PTS jumps/gaps. driftRateMsPerMin is the model slope per minute. New
AvSyncAnalyzerTest; no downstream code change.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo
EOF
)"
```

---

## Self-Review

**1. Spec coverage**

| Spec item | Step |
|---|---|
| `avSyncErrorModel` pure fn (formula, zero-total guard) | Step 3 + tests Step 1 |
| `computeSyncPoints` → `internal`, 3 new params | Step 4 |
| elapsed-position audio matching (`aTarget = aFirst + elapsed`) | Step 4 (`pointAt`) |
| `localResidual` clamped when `aTarget > aLast` | Step 4 (`if (aTarget <= aLast) … else 0.0`) |
| `deltaMs = model + residual`; sign convention unchanged | Step 4 |
| `SyncPoint` fields (`timeSeconds = vPts` etc.) | Step 4 |
| trailing final-frame point keeps same math | Step 4 (`pointAt` reused for `size - 1`) |
| `analyze`: `totalDurationSec = maxOf(...)` | Step 5 |
| `analyze`: pass new params to `computeSyncPoints` | Step 5 |
| `driftRateMsPerMin = durationDeltaSec * 1000 / totalMin` | Step 5 |
| keep `firstDelta`/`lastDelta` (diagnosis #3 reads them) | Step 5 |
| `maxSkewMs`/`minSkewMs`/`avgSkewMs` unchanged derivation | not touched — still `syncPoints.maxOf/minOf/average` |
| no change to visualization / verdict / diagnosis logic | no step touches those files |
| tests: `avSyncErrorModel` 4 cases + `computeSyncPoints` clean/offset/truncation/uniform-drift/gap/few-frames/empty | Step 1 |
| full suite green; no other `computeSyncPoints` caller | Step 7 |
| manual verification against the 3 samples | Step 8 |

**2. Placeholder scan** — none. Test bodies and both function bodies are complete. `analyze` edits are quoted verbatim (old → new).

**3. Type consistency**

- `avSyncErrorModel(initialSkewMs, durationDeltaSec, totalDurationSec, elapsedSec): Double` — same 4-arg `Double` shape in Step 3 (def), Step 4 (call inside `pointAt`), Step 1 (tests). ✓
- `computeSyncPoints(videoPackets, audioPackets, initialSkewMs, durationDeltaSec, totalDurationSec, targetSampleCount = 120)` — identical between Step 4 (def), Step 5 (`analyze` call passes the first 5, defaults the 6th), Step 1 (tests pass 5 or 6 positionally). ✓
- `SyncPoint(timeSeconds, videoPts, audioPts, deltaMs, videoFrameIndex, audioPacketIndex)` — matches the existing data class (`AvSyncAnalyzer.kt:25-32`). ✓
- `StreamPacket(mediaType, ptsSeconds, dtsSeconds, durationSeconds, sizeBytes, pos)` — matches `AvSyncAnalyzer.kt:13-20`; the test `run()` helper passes all six. ✓
- `totalDurationSec` is a new local in `analyze`, referenced by the `computeSyncPoints` call and the `driftRateMsPerMin` line — both in Step 5, both after its declaration. ✓
