# A/V Sync Visualization Clarity Pass — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the A/V sync window a three-layer read — an at-a-glance segment-colored bar + plain-language verdict on top, an axis-annotated skew curve below, diagnosis cards unchanged.

**Architecture:** Extract the visualization into a new `AvSyncVisualization.kt` (two pure functions + the Canvas composables). Add `AvSyncSegmentBar` + `avSyncVerdict()`. Extend `AvSyncGraph` with axes/ticks/callout. Delete the dual-lane bezier timeline and its two dead `AvSyncReport` fields. `AvSyncAnalyzer` measurement logic is untouched except removing two field assignments.

**Tech Stack:** Kotlin, Compose for Desktop 1.7.3 (`Canvas`, `rememberTextMeasurer`/`drawText`), JUnit 5.

## Global Constraints

- Kotlin 2.0.21; do NOT touch `app/build.gradle.kts`.
- Reuse this window's existing severity palette verbatim — green `Color(0xFF2E7D32)`, orange `Color(0xFFF57F17)`, red `Color(0xFFC62828)`; the softer point variants already in `AvSyncGraph` (`0xFF81C784` / `0xFFFFB74D` / `0xFFE57373`). No new palette.
- Korean UI strings inline, matching `AvSyncAnalysisWindow.kt`'s terse style.
- `./gradlew :app:test` must pass (`BUILD SUCCESSFUL`) at the end of every task.
- Δt sign convention (unchanged): `deltaMs = Video PTS − Audio PTS`; `> 0` ⇒ "오디오 선행 / 앞섬", `< 0` ⇒ "비디오 선행 / 뒤처짐".
- No measurement-logic change in `AvSyncAnalyzer` beyond removing the `sampleVideoPackets` / `sampleAudioPackets` assignments.
- Commit messages END with these two lines exactly:
  `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo`

---

### Task 1: Pure functions — `avSyncVerdict`, `avSyncSegments`, `formatMinSec`

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/AvSyncVisualization.kt`
- Create: `app/src/test/kotlin/com/multiviewer/ui/AvSyncVisualizationTest.kt`

**Interfaces:**
- Consumes: `AvSyncReport`, `SyncPoint`, `SyncSeverity` from `com.multiviewer.ui.AvSyncAnalyzer` (same package — no import needed).
- Produces (all `internal`, package `com.multiviewer.ui`):
  - `fun formatMinSec(seconds: Double): String` — `"m:ss"`, negative clamped to 0.
  - `fun avSyncVerdict(report: AvSyncReport): String`
  - `fun avSyncSegments(report: AvSyncReport, segmentCount: Int): List<SyncSeverity?>` — always length `segmentCount`; `null` = no sync points in that bucket.

- [ ] **Step 1: Write `AvSyncVisualizationTest.kt` (failing)**

```kotlin
package com.multiviewer.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class AvSyncVisualizationTest {

    private fun report(
        points: List<SyncPoint>,
        avgSkewMs: Double = points.map { it.deltaMs }.average(),
        driftRateMsPerMin: Double = 0.0,
        videoDurationSec: Double = points.maxOfOrNull { it.timeSeconds } ?: 0.0,
    ) = AvSyncReport(
        file = File("t.mp4"),
        hasVideo = true,
        hasAudio = true,
        videoDurationSec = videoDurationSec,
        audioDurationSec = videoDurationSec,
        durationDeltaSec = 0.0,
        initialSkewMs = points.firstOrNull()?.deltaMs ?: 0.0,
        maxSkewMs = points.maxOfOrNull { it.deltaMs } ?: 0.0,
        minSkewMs = points.minOfOrNull { it.deltaMs } ?: 0.0,
        avgSkewMs = avgSkewMs,
        driftRateMsPerMin = driftRateMsPerMin,
        syncPoints = points,
        diagnoses = emptyList(),
        overallSeverity = SyncSeverity.PASS,
    )

    private fun pts(vararg pairs: Pair<Double, Double>) = pairs.map { (t, d) ->
        SyncPoint(timeSeconds = t, videoPts = t, audioPts = t - d / 1000.0, deltaMs = d, videoFrameIndex = 0, audioPacketIndex = 0)
    }

    @Test
    fun formatMinSec_formatsAndClampsNegative() {
        assertEquals("0:00", formatMinSec(0.0))
        assertEquals("1:05", formatMinSec(65.4))
        assertEquals("59:59", formatMinSec(3599.9))
        assertEquals("0:00", formatMinSec(-3.0))
    }

    @Test
    fun verdict_allWithinComfort_saysGood() {
        val v = avSyncVerdict(report(pts(0.0 to 10.0, 5.0 to -20.0, 10.0 to 35.0)))
        assertTrue(v.contains("양호"), v)
        assertTrue(v.contains("±40ms"), v)
    }

    @Test
    fun verdict_drift_mentionsPerMinuteRate() {
        val v = avSyncVerdict(report(pts(0.0 to 5.0, 30.0 to 60.0, 60.0 to 120.0), driftRateMsPerMin = 12.0))
        assertTrue(v.contains("분당"), v)
        assertTrue(v.contains("12ms"), v)
    }

    @Test
    fun verdict_spiky_callsOutTheWorstWindow() {
        val v = avSyncVerdict(report(pts(0.0 to 5.0, 10.0 to 5.0, 20.0 to 5.0, 30.0 to 210.0, 40.0 to 5.0)))
        assertTrue(v.contains("튑니다"), v)
        assertTrue(v.contains("210"), v)
    }

    @Test
    fun verdict_constantOffset_saysBehindAndItsoffset() {
        val v = avSyncVerdict(report(pts(0.0 to -84.0, 30.0 to -85.0, 60.0 to -86.0), avgSkewMs = -85.0, driftRateMsPerMin = 0.5))
        assertTrue(v.contains("뒤처짐"), v)
        assertTrue(v.contains("-itsoffset"), v)
    }

    @Test
    fun verdict_noPoints_returnsBenignDefault() {
        val v = avSyncVerdict(report(emptyList()))
        assertEquals("동기화 데이터가 부족합니다.", v)
    }

    @Test
    fun segments_allGreen_allPass() {
        val s = avSyncSegments(report(pts(0.0 to 10.0, 5.0 to 10.0, 10.0 to 10.0), videoDurationSec = 10.0), 10)
        assertEquals(10, s.size)
        assertTrue(s.all { it == SyncSeverity.PASS })
    }

    @Test
    fun segments_redSpikeInMiddle_middleCriticalEdgesPass() {
        val pts = pts(0.0 to 5.0, 2.5 to 5.0, 5.0 to 250.0, 7.5 to 5.0, 10.0 to 5.0)
        val s = avSyncSegments(report(pts, videoDurationSec = 10.0), 4)
        assertEquals(4, s.size)
        assertEquals(SyncSeverity.PASS, s.first())
        assertEquals(SyncSeverity.PASS, s.last())
        assertTrue(s.any { it == SyncSeverity.CRITICAL }, s.toString())
    }

    @Test
    fun segments_gapWithNoPoints_isNull() {
        // points only in the first half of a 10s file
        val s = avSyncSegments(report(pts(0.0 to 5.0, 2.0 to 5.0, 4.0 to 5.0), videoDurationSec = 10.0), 10)
        assertEquals(10, s.size)
        assertNull(s.last())
    }

    @Test
    fun segments_lengthAlwaysEqualsCount() {
        val r = report(pts(0.0 to 5.0), videoDurationSec = 4.0)
        assertEquals(1, avSyncSegments(r, 1).size)
        assertEquals(50, avSyncSegments(r, 50).size)
    }
}
```

- [ ] **Step 2: Run — expect failure**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.AvSyncVisualizationTest"`
Expected: FAIL — `avSyncVerdict` / `avSyncSegments` / `formatMinSec` unresolved.

- [ ] **Step 3: Create `AvSyncVisualization.kt` with the three functions**

```kotlin
package com.multiviewer.ui

import kotlin.math.abs

/** Seconds → "m:ss". Negative input clamps to 0. */
internal fun formatMinSec(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toInt()
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

private fun severityOf(absDeltaMs: Double): SyncSeverity = when {
    absDeltaMs <= 40.0 -> SyncSeverity.PASS
    absDeltaMs <= 100.0 -> SyncSeverity.WARNING
    else -> SyncSeverity.CRITICAL
}

/**
 * One plain-language sentence describing the sync state. Priority: good →
 * progressive drift → spiky → constant offset.
 */
internal fun avSyncVerdict(report: AvSyncReport): String {
    val points = report.syncPoints
    if (!report.hasVideo || !report.hasAudio || points.isEmpty()) {
        return "동기화 데이터가 부족합니다."
    }

    val deltas = points.map { it.deltaMs }
    val absDeltas = deltas.map { abs(it) }
    val maxAbs = absDeltas.max()
    val minAbs = absDeltas.min()
    val total = maxOf(report.videoDurationSec, report.audioDurationSec)

    // 1. Everything inside the comfort zone.
    if (maxAbs <= 40.0) {
        return "✅ 동기화 양호 — 전 구간 ±40ms 이내로 립싱크 문제 없음"
    }

    // 2. Progressive drift.
    if (abs(report.driftRateMsPerMin) > 5.0) {
        val widening = (report.driftRateMsPerMin > 0) == (deltas.last() >= 0)
        val last = deltas.lastOrNull() ?: report.initialSkewMs
        return "🔴 시간이 갈수록 편차가 커집니다 — 분당 %.0fms씩 %s. %s 지점에서 %+.0fms. 클럭/타임스케일 불일치가 의심됩니다.".format(
            abs(report.driftRateMsPerMin),
            if (widening) "벌어짐" else "좁혀짐",
            formatMinSec(total),
            last,
        )
    }

    // 3. Spiky — a localized excursion.
    if (maxAbs - minAbs > 60.0) {
        val worst = points.maxByOrNull { abs(it.deltaMs) }!!
        val window = (total * 0.05).coerceIn(1.0, 10.0)
        val lo = (worst.timeSeconds - window).coerceIn(0.0, total)
        val hi = (worst.timeSeconds + window).coerceIn(0.0, total)
        return "⚠ %s–%s 구간에서 최대 %+.0fms까지 튑니다 — 해당 구간을 집중 확인하세요.".format(
            formatMinSec(lo), formatMinSec(hi), worst.deltaMs,
        )
    }

    // 4. Roughly constant offset.
    val ahead = report.avgSkewMs > 0
    return "⚠ 오디오가 영상보다 일정하게 %+.0fms %s — 고정 지연이므로 -itsoffset 으로 교정 가능합니다.".format(
        report.avgSkewMs,
        if (ahead) "앞섬" else "뒤처짐",
    )
}

/**
 * Bucket the timeline into [segmentCount] equal slices; each slice's severity is
 * the worst (max |Δt|) of the sync points that fall in it, or null when the
 * slice contains no sync points.
 */
internal fun avSyncSegments(report: AvSyncReport, segmentCount: Int): List<SyncSeverity?> {
    val n = segmentCount.coerceAtLeast(1)
    val total = maxOf(report.videoDurationSec, report.audioDurationSec).coerceAtLeast(0.001)
    val worstAbs = DoubleArray(n) { -1.0 }
    for (p in report.syncPoints) {
        val idx = ((p.timeSeconds / total) * n).toInt().coerceIn(0, n - 1)
        val a = abs(p.deltaMs)
        if (a > worstAbs[idx]) worstAbs[idx] = a
    }
    return worstAbs.map { if (it < 0.0) null else severityOf(it) }
}
```

- [ ] **Step 4: Run — expect pass**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.AvSyncVisualizationTest"`
Expected: PASS (11 tests).

- [ ] **Step 5: Full suite + commit**

Run: `./gradlew :app:test` → `BUILD SUCCESSFUL`

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AvSyncVisualization.kt \
        app/src/test/kotlin/com/multiviewer/ui/AvSyncVisualizationTest.kt
git commit -m "$(cat <<'EOF'
feat: avSyncVerdict / avSyncSegments pure functions for the A/V sync view

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo
EOF
)"
```

---

### Task 2: Move + annotate the skew curve

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AvSyncVisualization.kt` (add composables)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AvSyncAnalysisWindow.kt` (remove the moved functions; keep the toggle working)

**Interfaces:**
- Consumes: `formatMinSec` (Task 1); `SyncPoint`.
- Produces (`internal`, package `com.multiviewer.ui`):
  - `@Composable fun AvSyncGraph(points: List<SyncPoint>, selectedPoint: SyncPoint?, onSelectPoint: (SyncPoint?) -> Unit, modifier: Modifier = Modifier)`
  - `@Composable fun LegendBadge(label: String, color: androidx.compose.ui.graphics.Color)`

- [ ] **Step 1: Move `AvSyncGraph` and `LegendBadge` verbatim into `AvSyncVisualization.kt`**

Cut both functions from `AvSyncAnalysisWindow.kt` (`AvSyncGraph` is around lines 398–527, `LegendBadge` around 383–396). Paste into `AvSyncVisualization.kt`. Change `private fun` → `internal fun` on both. Add the imports they need to `AvSyncVisualization.kt`:

```kotlin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
```

Build (`./gradlew :app:compileKotlin`) — `AvSyncAnalysisWindow.kt` still references `AvSyncGraph` / `LegendBadge` and now resolves them from the same package (no import statement needed — same package). Some imports in `AvSyncAnalysisWindow.kt` may become unused (e.g. `Stroke`, `Path` if only the curve used them — check and remove any the compiler flags as unused; `detectTapGestures`, `Canvas` are still used by `AvDualLaneTimeline`).

- [ ] **Step 2: Add axis + callout drawing to `AvSyncGraph`**

Inside `AvSyncGraph`, add these two lines **at the very top of the function body,
before `if (points.isEmpty()) return`** (a `@Composable` call must not sit after a
conditional `return`):

```kotlin
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = Color(0xFF9AA0A6), fontSize = 9.sp)
```

`textMeasurer` / `axisStyle` are then in scope inside the `Canvas { }` lambda via closure. Inside the draw scope, **after** the existing threshold dotted lines and **before** `// Plot Curve`, add the axis labels:

```kotlin
        // Y-axis tick labels (only those inside the plotted range)
        listOf(100.0, 40.0, 0.0, -40.0, -100.0).forEach { v ->
            val ty = toY(v)
            if (ty in padY..(h - padY)) {
                val label = if (v == 0.0) "0" else "%+.0f".format(v)
                drawText(textMeasurer, label, topLeft = Offset(2f, ty - 6f), style = axisStyle)
            }
        }
        drawText(textMeasurer, "오디오 선행 ▲", topLeft = Offset(2f, padY - 14f), style = axisStyle)
        drawText(textMeasurer, "비디오 선행 ▼", topLeft = Offset(2f, h - padY + 2f), style = axisStyle)

        // X-axis time ticks (4)
        for (i in 0..3) {
            val frac = i / 3f
            val tx = padX + frac * graphW
            drawLine(Color(0x40FFFFFF), Offset(tx, h - padY), Offset(tx, h - padY + 4f), strokeWidth = 1f)
            drawText(
                textMeasurer,
                formatMinSec(frac.toDouble() * maxTime),
                topLeft = Offset(tx - 12f, h - padY + 5f),
                style = axisStyle,
            )
        }

        // Ideal-line label (the 0 ms baseline is already drawn above)
        drawText(
            textMeasurer, "이상 (0ms)",
            topLeft = Offset(w - padX - 52f, yZero - 12f),
            style = axisStyle,
        )
```

Then, **after** the "Plot Points" loop and **before** the "Selected Point Marker" block, add the worst-point callout:

```kotlin
        // Worst-point callout
        val worst = points.maxByOrNull { kotlin.math.abs(it.deltaMs) }
        if (worst != null && kotlin.math.abs(worst.deltaMs) > 40.0) {
            val wx = toX(worst.timeSeconds)
            val wy = toY(worst.deltaMs)
            val text = "%+.0fms @ %s".format(worst.deltaMs, formatMinSec(worst.timeSeconds))
            val layout = textMeasurer.measure(text, axisStyle.copy(fontSize = 10.sp, color = Color(0xFFFFF176)))
            val boxW = layout.size.width + 8f
            val above = wy - 20f > padY
            val bx = (wx - boxW / 2f).coerceIn(padX, w - padX - boxW)
            val by = if (above) wy - 20f else wy + 8f
            drawRoundRect(
                color = Color(0xCC1E1E1E),
                topLeft = Offset(bx, by),
                size = Size(boxW, layout.size.height + 4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
            )
            drawText(layout, topLeft = Offset(bx + 4f, by + 2f))
        }
```

(`drawRoundRect` needs no new import — it's on `DrawScope`. `CornerRadius` is referenced fully-qualified as elsewhere in the file.)

- [ ] **Step 3: Build**

Run: `./gradlew :app:compileKotlin`
Expected: `BUILD SUCCESSFUL`. Fix any unused-import warnings in `AvSyncAnalysisWindow.kt` the compiler surfaces (`w:` lines) by deleting those imports.

- [ ] **Step 4: Full suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL` (Task 1's 11 tests still pass; no new tests — this is Canvas drawing).

- [ ] **Step 5: Visual check**

Run: `./gradlew :app:run`, open a video file, open the A/V sync window (menu / analysis button), switch to the "편차 곡선" mode.
Expected: the curve now shows Y tick values (`+100`/`+40`/`0`/…), `오디오 선행 ▲` / `비디오 선행 ▼`, four `m:ss` ticks on the bottom, an `이상 (0ms)` label on the baseline, and — if any point exceeds 40 ms — a yellow callout box at the worst point. No overlap with the plotted curve that hides data. Close the app.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AvSyncVisualization.kt \
        app/src/main/kotlin/com/multiviewer/ui/AvSyncAnalysisWindow.kt
git commit -m "$(cat <<'EOF'
feat: axes, tick values and worst-point callout on the A/V skew curve

Moves AvSyncGraph + LegendBadge into AvSyncVisualization.kt and adds a Y-axis
(ms ticks + 오디오/비디오 선행 labels), X-axis time ticks, an "이상 (0ms)"
baseline label, and a callout on the worst sync point.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo
EOF
)"
```

---

### Task 3: Segment bar + new layout; remove dual-lane and dead fields

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AvSyncVisualization.kt` (add `AvSyncSegmentBar`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AvSyncAnalysisWindow.kt` (restructure the visualization card; delete `AvDualLaneTimeline`, the toggle, `selectedVisualMode`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AvSyncAnalyzer.kt` (remove two fields + two assignments)

**Interfaces:**
- Consumes: `avSyncVerdict`, `avSyncSegments`, `formatMinSec` (Task 1); `AvSyncGraph`, `LegendBadge` (Task 2).
- Produces: `@Composable fun AvSyncSegmentBar(report: AvSyncReport, selectedPoint: SyncPoint?, onSelectPoint: (SyncPoint?) -> Unit, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Remove the dead fields from `AvSyncReport` + `AvSyncAnalyzer`**

In `AvSyncAnalyzer.kt`:
- Delete the two lines from the `AvSyncReport` data class:
  ```kotlin
      val sampleVideoPackets: List<StreamPacket> = emptyList(),
      val sampleAudioPackets: List<StreamPacket> = emptyList(),
  ```
- Delete the two matching lines from the `AvSyncReport(...)` construction in `analyze` (`sampleVideoPackets = videoPackets,` / `sampleAudioPackets = audioPackets,`).

Build — expect `AvSyncAnalysisWindow.kt` to fail (`AvDualLaneTimeline` reads `report.sampleVideoPackets`). Fixed in Step 3.

- [ ] **Step 2: Add `AvSyncSegmentBar` to `AvSyncVisualization.kt`**

```kotlin
@Composable
internal fun AvSyncSegmentBar(
    report: AvSyncReport,
    selectedPoint: SyncPoint?,
    onSelectPoint: (SyncPoint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = maxOf(report.videoDurationSec, report.audioDurationSec).coerceAtLeast(0.01)
    val syncPoints = report.syncPoints
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = Color(0xFF9AA0A6), fontSize = 9.sp)

    Canvas(
        modifier = modifier.pointerInput(syncPoints) {
            detectTapGestures { offset ->
                if (syncPoints.isEmpty()) return@detectTapGestures
                val frac = (offset.x / size.width).coerceIn(0f, 1f)
                val t = frac * total
                onSelectPoint(syncPoints.minByOrNull { kotlin.math.abs(it.timeSeconds - t) })
            }
        },
    ) {
        val w = size.width
        val barH = 28.dp.toPx()
        val n = (w / 4f).toInt().coerceIn(24, 160)
        val segs = avSyncSegments(report, n)
        val segW = w / n
        segs.forEachIndexed { i, sev ->
            val c = when (sev) {
                SyncSeverity.PASS -> Color(0xFF2E7D32)
                SyncSeverity.WARNING -> Color(0xFFF57F17)
                SyncSeverity.CRITICAL -> Color(0xFFC62828)
                null -> Color(0xFF3A3A3A)
            }
            drawRect(color = c, topLeft = Offset(i * segW, 0f), size = Size(segW + 1f, barH))
        }

        // X-axis ticks
        for (i in 0..3) {
            val frac = i / 3f
            val tx = frac * w
            drawLine(Color(0x40FFFFFF), Offset(tx.coerceIn(0.5f, w - 0.5f), barH), Offset(tx.coerceIn(0.5f, w - 0.5f), barH + 4f), strokeWidth = 1f)
            drawText(textMeasurer, formatMinSec(frac.toDouble() * total), topLeft = Offset((tx - 12f).coerceIn(0f, w - 28f), barH + 5f), style = axisStyle)
        }

        // Selected marker
        if (selectedPoint != null) {
            val sx = ((selectedPoint.timeSeconds / total).toFloat() * w).coerceIn(0f, w)
            drawLine(Color(0xFFFFEB3B), Offset(sx, 0f), Offset(sx, barH), strokeWidth = 1.5f)
        }
    }
}
```

- [ ] **Step 3: Restructure the visualization card in `AvSyncAnalysisWindow.kt`**

In `AvSyncReportContent`, replace the whole "2. Timeline Visualization Card" `Card { … }` block (from `var selectedVisualMode by remember …` through the card's closing brace, roughly lines 187–343 — it ends right before `// 3. Root-cause Diagnosis`) with:

```kotlin
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Layer 1: at-a-glance
                Text(
                    "한눈에 보는 동기화 상태",
                    style = AppTypography.headlineSmall.copy(color = AppColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LegendBadge("양호 (±40ms)", Color(0xFF2E7D32))
                    LegendBadge("주의 (±100ms)", Color(0xFFF57F17))
                    LegendBadge("심각 (>100ms)", Color(0xFFC62828))
                    LegendBadge("데이터 없음", Color(0xFF3A3A3A))
                }
                Spacer(Modifier.height(8.dp))
                AvSyncSegmentBar(
                    report = report,
                    selectedPoint = selectedSyncPoint,
                    onSelectPoint = onSelectSyncPoint,
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                )
                Spacer(Modifier.height(8.dp))
                val verdictColor = when (report.overallSeverity) {
                    SyncSeverity.PASS -> Color(0xFF2E7D32)
                    SyncSeverity.WARNING -> Color(0xFFF57F17)
                    SyncSeverity.CRITICAL -> Color(0xFFC62828)
                }
                Text(
                    avSyncVerdict(report),
                    style = AppTypography.bodyMedium.copy(color = verdictColor, fontWeight = FontWeight.SemiBold)
                )

                Spacer(Modifier.height(20.dp))

                // Layer 2: annotated curve
                Text(
                    "A/V 타임스탬프 편차 곡선 (Δt = Video PTS − Audio PTS)",
                    style = AppTypography.headlineSmall.copy(color = AppColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                )
                Text(
                    "양수: 오디오 선행 / 음수: 비디오 선행",
                    style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary)
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .background(AppColors.Panel, RoundedCornerShape(4.dp))
                        .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                ) {
                    AvSyncGraph(
                        points = report.syncPoints,
                        selectedPoint = selectedSyncPoint,
                        onSelectPoint = onSelectSyncPoint,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (selectedSyncPoint != null) {
                    val p = selectedSyncPoint
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = AppColors.Panel,
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "선택된 위치: 시간 ${String.format(Locale.US, "%.3f", p.timeSeconds)}s  |  Video(#${p.videoFrameIndex}): ${String.format(Locale.US, "%.3f", p.videoPts)}s  |  Audio(#${p.audioPacketIndex}): ${String.format(Locale.US, "%.3f", p.audioPts)}s",
                                style = AppTypography.bodySmall.copy(color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                            )
                            val skewColor = when {
                                abs(p.deltaMs) > 100 -> Color(0xFFEF5350)
                                abs(p.deltaMs) > 40 -> Color(0xFFFFB74D)
                                else -> Color(0xFF81C784)
                            }
                            Text(
                                text = "편차(Δt): ${String.format(Locale.US, "%+.1f", p.deltaMs)} ms (${if (p.deltaMs > 0) "오디오 선행" else if (p.deltaMs < 0) "비디오 선행" else "일치"})",
                                style = AppTypography.bodySmall.copy(color = skewColor, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                            )
                        }
                    }
                }
            }
        }
```

- [ ] **Step 4: Delete `AvDualLaneTimeline`**

Delete the whole `@Composable private fun AvDualLaneTimeline(...) { … }` from `AvSyncAnalysisWindow.kt` (~lines 529–717). Then run `./gradlew :app:compileKotlin` and delete every import it left unused that the compiler flags (`w: … is never used`) — likely candidates: `androidx.compose.ui.graphics.Path`, `androidx.compose.ui.graphics.drawscope.Stroke`, `androidx.compose.foundation.gestures.detectTapGestures`, `androidx.compose.foundation.Canvas`, `androidx.compose.ui.geometry.Size`, `androidx.compose.ui.geometry.Offset` (verify each against remaining uses — do not blind-delete).

- [ ] **Step 5: Full suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`. Task 1's `AvSyncVisualizationTest` (11) still green; no `AvSyncReport` constructor call elsewhere breaks (the two removed params had defaults but were passed positionally by name only in `analyze` — grep `AvSyncReport(` to confirm the analyzer is the only constructor site).

- [ ] **Step 6: Visual check**

Run: `./gradlew :app:run`, open a video, open the A/V sync window.
Expected: no mode toggle; a colored segment bar with a legend and time ticks at the top, a one-line verdict under it in the severity color, then the annotated curve, then the diagnosis cards. Clicking the bar moves the yellow marker and populates the "선택된 위치" box. Close the app.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AvSyncVisualization.kt \
        app/src/main/kotlin/com/multiviewer/ui/AvSyncAnalysisWindow.kt \
        app/src/main/kotlin/com/multiviewer/ui/AvSyncAnalyzer.kt
git commit -m "$(cat <<'EOF'
feat: at-a-glance segment bar + verdict for A/V sync; drop the dual-lane view

The visualization card is now a hierarchy: a broadcast-QC-style segment-colored
bar (green/orange/red/grey per time slice) + a plain-language verdict, then the
annotated skew curve, then the diagnosis cards. Removes the dual-lane bezier
timeline, its mode toggle, and the now-unused AvSyncReport.sample*Packets fields.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo
EOF
)"
```

---

## Self-Review

**1. Spec coverage**

| Spec item | Task |
|---|---|
| Segment-colored bar `AvSyncSegmentBar` (width-derived count, worst-|Δt| per bucket, grey = no data, time ticks, tap→select, selected marker) | Task 3 Step 2 |
| `avSyncSegments` pure fn | Task 1 Step 3 + tests Step 1 |
| Verdict sentence `avSyncVerdict` (4 patterns, priority, benign default) | Task 1 Step 3 + tests Step 1 |
| `formatMinSec` | Task 1 Step 3 + tests |
| Verdict rendered under the bar in the overallSeverity color | Task 3 Step 3 |
| Skew curve Y axis (ms ticks in-range only, 오디오/비디오 선행 labels) | Task 2 Step 2 |
| Skew curve X axis (4 m:ss ticks) | Task 2 Step 2 |
| "이상 (0ms)" baseline label | Task 2 Step 2 |
| Worst-point callout (skipped when maxAbs ≤ 40, flip/clamp) | Task 2 Step 2 |
| `TextMeasurer` plumbing | Task 2 Step 2, Task 3 Step 2 |
| Legend = curve legend only | Task 3 Step 3 (new legend row) |
| Delete `AvDualLaneTimeline` + `selectedVisualMode` + toggle Row + mode branches | Task 3 Steps 3–4 |
| Delete `sampleVideoPackets`/`sampleAudioPackets` + assignments | Task 3 Step 1 |
| New file `AvSyncVisualization.kt` with the two pure fns + composables + `formatMinSec` | Tasks 1–3 |
| `AvSyncAnalysisWindow.kt` keeps Window shell / `AvSyncReportContent` / `MetricCard` / `DiagnosisCard` | untouched by all steps except the card restructure |
| Reuse existing palette verbatim | Task 3 Step 2 colors, Task 2 point colors unchanged (moved verbatim) |
| Tests: verdict 4 patterns + default; segments green/spike/gap/length; formatMinSec | Task 1 Step 1 |
| No `AvSync*` test exists to update | true — Task 1 creates the first |

**2. Placeholder scan** — no "TBD"/"handle edge cases"/"similar to". Pure-function and test code is complete. Canvas steps show the complete NEW draw blocks; the moved code (`AvSyncGraph` body, the `selectedSyncPoint` detail `Surface`) is reproduced where it lands (Task 3 Step 3) or explicitly moved verbatim (Task 2 Step 1).

**3. Type consistency**

- `avSyncSegments(report, segmentCount): List<SyncSeverity?>` — defined Task 1 Step 3, consumed Task 3 Step 2 (`avSyncSegments(report, n)`), tested Task 1 Step 1. ✓
- `avSyncVerdict(report): String` — defined Task 1, consumed Task 3 Step 3 (`avSyncVerdict(report)`), tested Task 1. ✓
- `formatMinSec(seconds: Double): String` — defined Task 1, consumed Task 2 Step 2 + Task 3 Step 2. ✓
- `AvSyncGraph(points, selectedPoint, onSelectPoint, modifier)` — signature preserved through the move (Task 2 Step 1), same call shape in Task 3 Step 3 (`points = report.syncPoints, selectedPoint = selectedSyncPoint, onSelectPoint = onSelectSyncPoint`). ✓
- `AvSyncSegmentBar(report, selectedPoint, onSelectPoint, modifier)` — defined Task 3 Step 2, called Task 3 Step 3 with the same names. ✓
- `LegendBadge(label, color)` — moved Task 2 Step 1, called Task 3 Step 3 (4×) + still referenced by nothing else after the dual-lane legend branch is deleted. ✓
- `SyncSeverity` (`PASS`/`WARNING`/`CRITICAL`) — used consistently; `severityOf` maps `absDeltaMs` with `≤40 / ≤100 / else` exactly as the segment-bar color `when` and the spec thresholds. ✓
- `AvSyncReport` — the two removed fields had defaults; the only constructor call site is `AvSyncAnalyzer.analyze` (Task 3 Step 5 verifies via grep). ✓
