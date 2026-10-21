package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
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
 * The [origin, span] time window the sync points actually cover, in seconds —
 * the single X domain shared by the segment bar and the skew curve so their
 * time axes line up. `timeSeconds` is a raw PTS that can start well above 0
 * (MPEG-TS), so everything time-relative must be measured from `origin`.
 */
internal fun avSyncTimeSpan(points: List<SyncPoint>): Pair<Double, Double> {
    if (points.isEmpty()) return 0.0 to 0.01
    val t0 = points.minOf { it.timeSeconds }
    val t1 = points.maxOf { it.timeSeconds }
    return t0 to (t1 - t0).coerceAtLeast(0.01)
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
    val maxAbs = deltas.maxOf { abs(it) }
    val (t0, span) = avSyncTimeSpan(points)

    // 1. Everything inside the comfort zone.
    if (maxAbs <= 40.0) {
        return "✅ 동기화 양호 — 전 구간 ±40ms 이내로 립싱크 문제 없음"
    }

    // 2. Progressive drift.
    if (abs(report.driftRateMsPerMin) > 5.0) {
        val last = deltas.last()
        val widening = (report.driftRateMsPerMin > 0) == (last >= 0)
        val prefix = if (widening) "🔴 시간이 갈수록 편차가 커집니다" else "⚠ 편차가 점차 줄어듭니다 (끝부분에서 개선)"
        return "%s — 분당 %.0fms씩 %s. %s 지점에서 %+.0fms. 클럭/타임스케일 불일치가 의심됩니다.".format(
            java.util.Locale.US,
            prefix,
            abs(report.driftRateMsPerMin),
            if (widening) "벌어짐" else "좁혀짐",
            formatMinSec(span),
            last,
        )
    }

    // 3. Spiky — a localized excursion (measured on SIGNED deltas so a symmetric swing counts).
    if (deltas.max() - deltas.min() > 60.0) {
        val worst = points.maxByOrNull { abs(it.deltaMs) }!!
        val window = (span * 0.05).coerceIn(1.0, 10.0)
        val worstElapsed = worst.timeSeconds - t0
        val lo = (worstElapsed - window).coerceIn(0.0, span)
        val hi = (worstElapsed + window).coerceIn(0.0, span)
        return "⚠ %s–%s 구간에서 최대 %+.0fms까지 튑니다 — 해당 구간을 집중 확인하세요.".format(
            java.util.Locale.US, formatMinSec(lo), formatMinSec(hi), worst.deltaMs,
        )
    }

    // 4. Roughly constant offset.
    val avg = deltas.average()
    return "⚠ 오디오가 영상보다 일정하게 %.0fms %s — 고정 지연이므로 -itsoffset 으로 교정 가능합니다.".format(
        java.util.Locale.US, abs(avg), if (avg > 0) "앞섬" else "뒤처짐",
    )
}

/**
 * Bucket the timeline into [segmentCount] equal slices; each slice's severity is
 * the worst (max |Δt|) of the sync points that fall in it, or null when the
 * slice contains no sync points.
 */
internal fun avSyncSegments(report: AvSyncReport, segmentCount: Int): List<SyncSeverity?> {
    val n = segmentCount.coerceAtLeast(1)
    val (t0, span) = avSyncTimeSpan(report.syncPoints)
    val worstAbs = DoubleArray(n) { -1.0 }
    for (p in report.syncPoints) {
        val idx = (((p.timeSeconds - t0) / span) * n).toInt().coerceIn(0, n - 1)
        val a = abs(p.deltaMs)
        if (a > worstAbs[idx]) worstAbs[idx] = a
    }
    return worstAbs.map { if (it < 0.0) null else severityOf(it) }
}

@Composable
internal fun LegendBadge(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(4.dp))
        )
        Text(label, style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 11.sp))
    }
}

@Composable
internal fun AvSyncGraph(
    points: List<SyncPoint>,
    selectedPoint: SyncPoint?,
    onSelectPoint: (SyncPoint?) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = Color(0xFF9AA0A6), fontSize = 9.sp)

    if (points.isEmpty()) return

    val maxAbsDelta = points.maxOf { abs(it.deltaMs) }.coerceAtLeast(120.0)
    val yCeiling = (maxAbsDelta * 1.25)
    val (t0, span) = avSyncTimeSpan(points)

    Canvas(
        modifier = modifier
            .pointerInput(points) {
                detectTapGestures { offset ->
                    val w = size.width
                    val paddingX = 40f
                    val graphW = w - paddingX * 2
                    val clickFraction = ((offset.x - paddingX) / graphW).coerceIn(0f, 1f)
                    val targetTime = t0 + clickFraction * span
                    val nearest = points.minByOrNull { abs(it.timeSeconds - targetTime) }
                    onSelectPoint(nearest)
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val padX = 40f
        val padY = 24f
        val graphW = w - padX * 2
        val graphH = h - padY * 2

        fun toY(deltaMs: Double): Float {
            val fraction = (deltaMs / yCeiling).toFloat()
            return padY + (graphH / 2f) - (fraction * (graphH / 2f))
        }

        fun toX(timeSec: Double): Float {
            return padX + (((timeSec - t0) / span).toFloat() * graphW)
        }

        val yPos100 = toY(100.0)
        val yPos40 = toY(40.0)
        val yNeg40 = toY(-40.0)
        val yNeg100 = toY(-100.0)
        val yZero = toY(0.0)

        // Orange Bands (40ms ~ 100ms)
        drawRect(
            color = Color(0x1AF57F17),
            topLeft = Offset(padX, yPos100),
            size = Size(graphW, yPos40 - yPos100)
        )
        drawRect(
            color = Color(0x1AF57F17),
            topLeft = Offset(padX, yNeg40),
            size = Size(graphW, yNeg100 - yNeg40)
        )

        // Green Band (-40ms ~ +40ms)
        drawRect(
            color = Color(0x1A2E7D32),
            topLeft = Offset(padX, yPos40),
            size = Size(graphW, yNeg40 - yPos40)
        )

        // Center Baseline (0ms)
        drawLine(
            color = Color(0x80FFFFFF),
            start = Offset(padX, yZero),
            end = Offset(w - padX, yZero),
            strokeWidth = 1.5f
        )

        // Threshold Dotted Lines
        drawLine(
            color = Color(0x40F57F17),
            start = Offset(padX, yPos40),
            end = Offset(w - padX, yPos40),
            strokeWidth = 1f
        )
        drawLine(
            color = Color(0x40F57F17),
            start = Offset(padX, yNeg40),
            end = Offset(w - padX, yNeg40),
            strokeWidth = 1f
        )

        // Y-axis tick labels (only those inside the plotted range)
        listOf(100.0, 40.0, 0.0, -40.0, -100.0).forEach { v ->
            val ty = toY(v)
            if (ty in padY..(h - padY)) {
                val label = if (v == 0.0) "0 (이상)" else "%+.0f".format(java.util.Locale.US, v)
                drawText(textMeasurer, label, topLeft = Offset(2f, ty - 6f), style = axisStyle)
            }
        }
        drawText(textMeasurer, "오디오 선행 ▲", topLeft = Offset(2f, padY + 2f), style = axisStyle)
        drawText(textMeasurer, "비디오 선행 ▼", topLeft = Offset(2f, h - padY - 12f), style = axisStyle)

        // X-axis time ticks (4)
        for (i in 0..3) {
            val frac = i / 3f
            val tx = padX + frac * graphW
            drawLine(Color(0x40FFFFFF), Offset(tx, h - padY), Offset(tx, h - padY + 4f), strokeWidth = 1f)
            drawText(
                textMeasurer,
                formatMinSec(frac.toDouble() * span),
                topLeft = Offset(tx - 12f, h - padY + 5f),
                style = axisStyle,
            )
        }

        // Plot Curve
        val path = Path()
        points.forEachIndexed { index, p ->
            val x = toX(p.timeSeconds)
            val y = toY(p.deltaMs)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = Color(0xFF64B5F6),
            style = Stroke(width = 2.5f)
        )

        // Plot Points
        points.forEach { p ->
            val x = toX(p.timeSeconds)
            val y = toY(p.deltaMs)
            val ptColor = when {
                abs(p.deltaMs) > 100 -> Color(0xFFE57373)
                abs(p.deltaMs) > 40 -> Color(0xFFFFB74D)
                else -> Color(0xFF81C784)
            }
            drawCircle(color = ptColor, radius = 3.5f, center = Offset(x, y))
        }

        // Worst-point callout
        val worst = points.maxByOrNull { kotlin.math.abs(it.deltaMs) }
        if (worst != null && kotlin.math.abs(worst.deltaMs) > 40.0) {
            val wx = toX(worst.timeSeconds)
            val wy = toY(worst.deltaMs)
            val text = "%+.0fms @ %s".format(java.util.Locale.US, worst.deltaMs, formatMinSec(worst.timeSeconds - t0))
            val layout = textMeasurer.measure(text, axisStyle.copy(fontSize = 10.sp, color = Color(0xFFFFF176)))
            val boxW = layout.size.width + 8f
            if (w > 2 * padX + boxW) {
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
        }

        // Selected Point Marker
        if (selectedPoint != null) {
            val x = toX(selectedPoint.timeSeconds)
            val y = toY(selectedPoint.deltaMs)
            drawLine(
                color = Color(0xFFFFEB3B),
                start = Offset(x, padY),
                end = Offset(x, h - padY),
                strokeWidth = 1.5f
            )
            drawCircle(color = Color(0xFFFFEB3B), radius = 7f, center = Offset(x, y))
            drawCircle(color = Color(0xFF1E1E1E), radius = 3f, center = Offset(x, y))
        }
    }
}

@Composable
internal fun AvSyncSegmentBar(
    report: AvSyncReport,
    selectedPoint: SyncPoint?,
    onSelectPoint: (SyncPoint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (t0, span) = avSyncTimeSpan(report.syncPoints)
    val syncPoints = report.syncPoints
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = Color(0xFF9AA0A6), fontSize = 9.sp)

    Canvas(
        modifier = modifier.pointerInput(syncPoints) {
            detectTapGestures { offset ->
                if (syncPoints.isEmpty()) return@detectTapGestures
                val frac = (offset.x / size.width).coerceIn(0f, 1f)
                val t = t0 + frac * span
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
            val txc = tx.coerceIn(0.5f, (w - 0.5f).coerceAtLeast(0.5f))
            drawLine(Color(0x40FFFFFF), Offset(txc, barH), Offset(txc, barH + 4f), strokeWidth = 1f)
            drawText(
                textMeasurer,
                formatMinSec(frac.toDouble() * span),
                topLeft = Offset((tx - 12f).coerceIn(0f, (w - 28f).coerceAtLeast(0f)), barH + 5f),
                style = axisStyle,
            )
        }

        // Selected marker
        if (selectedPoint != null) {
            val sx = ((((selectedPoint.timeSeconds - t0) / span).toFloat()) * w).coerceIn(0f, w)
            drawLine(Color(0xFFFFEB3B), Offset(sx, 0f), Offset(sx, barH), strokeWidth = 1.5f)
        }
    }
}

/**
 * Visual dual-track alignment bar showing exact Video vs Audio start/end boundaries,
 * head offset, and tail overhang/truncation on a shared time axis.
 */
@Composable
internal fun AvTrackAlignmentDiagram(
    report: AvSyncReport,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color(0xFFE8EAED), fontSize = 10.sp)
    val subStyle = TextStyle(color = Color(0xFF9AA0A6), fontSize = 9.sp)

    val vStart = report.videoStartPtsSec
    val vEnd = report.videoEndPtsSec.coerceAtLeast(vStart)
    val aStart = report.audioStartPtsSec
    val aEnd = report.audioEndPtsSec.coerceAtLeast(aStart)

    val axisMin = minOf(0.0, vStart, aStart, report.rawVideoFirstPtsSec, report.rawAudioFirstPtsSec)
    val axisMax = maxOf(vEnd, aEnd, axisMin + 0.01)
    val axisSpan = (axisMax - axisMin).coerceAtLeast(0.01)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padLeft = 74f
        val padRight = 18f
        val trackW = (w - padLeft - padRight).coerceAtLeast(10f)

        fun toX(sec: Double): Float = padLeft + (((sec - axisMin) / axisSpan).toFloat().coerceIn(0f, 1f) * trackW)

        val vRowTop = 10f
        val barH = 22f
        val aRowTop = 44f

        // Labels on the left
        drawText(textMeasurer, "VIDEO (v:0)", topLeft = Offset(4f, vRowTop + 3f), style = labelStyle.copy(color = Color(0xFF64B5F6)))
        drawText(textMeasurer, "AUDIO (a:0)", topLeft = Offset(4f, aRowTop + 3f), style = labelStyle.copy(color = Color(0xFF81C784)))

        // Track background lanes
        drawRoundRect(
            color = Color(0xFF1F242B),
            topLeft = Offset(padLeft, vRowTop),
            size = Size(trackW, barH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
        )
        drawRoundRect(
            color = Color(0xFF1F242B),
            topLeft = Offset(padLeft, aRowTop),
            size = Size(trackW, barH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
        )

        val vx0 = toX(vStart)
        val vx1 = toX(vEnd).coerceAtLeast(vx0 + 2f)
        val ax0 = toX(aStart)
        val ax1 = toX(aEnd).coerceAtLeast(ax0 + 2f)

        // Head offset highlight region if |initialSkewMs| > 10ms
        if (abs(report.initialSkewMs) > 10.0) {
            val hx0 = minOf(vx0, ax0)
            val hx1 = maxOf(vx0, ax0).coerceAtLeast(hx0 + 3f)
            val headColor = if (abs(report.initialSkewMs) > 100.0) Color(0x55EF5350) else Color(0x55FFB74D)
            drawRect(color = headColor, topLeft = Offset(hx0, vRowTop), size = Size(hx1 - hx0, aRowTop + barH - vRowTop))
        }

        // Tail mismatch highlight region if |tailOverhangMs| > 20ms
        if (abs(report.tailOverhangMs) > 20.0) {
            val tx0 = minOf(vx1, ax1)
            val tx1 = maxOf(vx1, ax1).coerceAtLeast(tx0 + 3f)
            val tailColor = if (abs(report.tailOverhangMs) > 250.0) Color(0x55EF5350) else Color(0x55FFB74D)
            drawRect(color = tailColor, topLeft = Offset(tx0, vRowTop), size = Size(tx1 - tx0, aRowTop + barH - vRowTop))
        }

        // Video bar
        drawRoundRect(
            color = Color(0xFF1976D2),
            topLeft = Offset(vx0, vRowTop),
            size = Size(vx1 - vx0, barH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
        )
        val vFpsStr = if (report.nominalVideoStepMs > 0) String.format(java.util.Locale.US, "%.1ffps", 1000.0 / report.nominalVideoStepMs) else ""
        val vText = String.format(
            java.util.Locale.US,
            "%.3fs ~ %.3fs  (길이 %.3fs · %df %s)",
            vStart, vEnd, report.videoDurationSec, report.videoPacketCount, vFpsStr,
        )
        drawText(textMeasurer, vText, topLeft = Offset((vx0 + 6f).coerceAtMost(w - 220f), vRowTop + 4f), style = labelStyle)

        // Raw AAC priming marker if before audio start
        if (report.rawAudioFirstPtsSec < aStart - 0.002) {
            val rawAx = toX(report.rawAudioFirstPtsSec)
            drawRect(
                color = Color(0x88FFB74D),
                topLeft = Offset(rawAx, aRowTop + 2f),
                size = Size((ax0 - rawAx).coerceAtLeast(2f), barH - 4f),
            )
        }

        // Audio bar
        drawRoundRect(
            color = Color(0xFF388E3C),
            topLeft = Offset(ax0, aRowTop),
            size = Size(ax1 - ax0, barH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
        )
        val aPrimingNote = if (abs(report.rawAudioFirstPtsSec - aStart) > 0.002) {
            String.format(java.util.Locale.US, " [raw첫PTS %+.1fms]", report.rawAudioFirstPtsSec * 1000.0)
        } else ""
        val aText = String.format(
            java.util.Locale.US,
            "%.3fs ~ %.3fs  (길이 %.3fs · %d pkts)%s",
            aStart, aEnd, report.audioDurationSec, report.audioPacketCount, aPrimingNote,
        )
        drawText(textMeasurer, aText, topLeft = Offset((ax0 + 6f).coerceAtMost(w - 260f), aRowTop + 4f), style = labelStyle)

        // Bottom summary line for Head & Tail delta
        val footerY = aRowTop + barH + 6f
        if (footerY + 12f <= h) {
            val headDesc = String.format(
                java.util.Locale.US,
                "◀ 시작 차이(ΔStart): %+.1f ms (%s)",
                report.initialSkewMs,
                when {
                    abs(report.initialSkewMs) <= 5.0 -> "시작점 일치"
                    report.initialSkewMs > 0 -> "오디오가 먼저 시작"
                    else -> "비디오가 먼저 시작"
                },
            )
            val tailDesc = String.format(
                java.util.Locale.US,
                "종료 차이(ΔEnd): %+.1f ms (%s) ▶",
                report.tailOverhangMs,
                when {
                    abs(report.tailOverhangMs) <= 20.0 -> "종료점 일치"
                    report.tailOverhangMs > 0 -> "비디오 종료 후 오디오 잔류"
                    else -> "오디오 조기 종료 / 비디오 잔류"
                },
            )
            drawText(textMeasurer, headDesc, topLeft = Offset(padLeft, footerY), style = subStyle)
            val tailMeasured = textMeasurer.measure(tailDesc, subStyle)
            drawText(
                tailMeasured,
                topLeft = Offset((w - padRight - tailMeasured.size.width).coerceAtLeast(padLeft + 220f), footerY),
            )
        }
    }
}

/**
 * ITU-R BT.1359 Lip-Sync acceptability gauge (-200ms to +200ms).
 * Asymmetric thresholds: Undetectable [-45ms, +15ms], Acceptable [-125ms, +45ms].
 */
@Composable
internal fun ItuLipSyncGauge(
    initialSkewMs: Double,
    minSkewMs: Double,
    maxSkewMs: Double,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = Color(0xFF9AA0A6), fontSize = 9.sp)
    val badgeStyle = TextStyle(color = Color.White, fontSize = 9.5.sp)

    Canvas(modifier = modifier) {
        val w = size.width
        val padX = 24f
        val barW = (w - padX * 2).coerceAtLeast(10f)
        val barTop = 18f
        val barH = 14f
        val minScale = -200.0
        val maxScale = 200.0

        fun toX(ms: Double): Float {
            val clamped = ms.coerceIn(minScale, maxScale)
            return padX + (((clamped - minScale) / (maxScale - minScale)).toFloat() * barW)
        }

        // Unacceptable background (-200..+200)
        drawRoundRect(
            color = Color(0x44C62828),
            topLeft = Offset(padX, barTop),
            size = Size(barW, barH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f),
        )
        // Acceptable zone (-125ms .. +45ms)
        val xAccLeft = toX(-125.0)
        val xAccRight = toX(45.0)
        drawRect(
            color = Color(0x55F57F17),
            topLeft = Offset(xAccLeft, barTop),
            size = Size(xAccRight - xAccLeft, barH),
        )
        // Undetectable / ideal comfort zone (-45ms .. +15ms)
        val xIdealLeft = toX(-45.0)
        val xIdealRight = toX(15.0)
        drawRect(
            color = Color(0x882E7D32),
            topLeft = Offset(xIdealLeft, barTop),
            size = Size(xIdealRight - xIdealLeft, barH),
        )

        // Span bar [minSkewMs .. maxSkewMs]
        val xMin = toX(minSkewMs)
        val xMax = toX(maxSkewMs)
        drawLine(
            color = Color(0xFF64B5F6),
            start = Offset(xMin, barTop + barH / 2f),
            end = Offset(xMax.coerceAtLeast(xMin + 2f), barTop + barH / 2f),
            strokeWidth = 4f,
        )

        // 0ms center line
        val xZero = toX(0.0)
        drawLine(Color.White.copy(alpha = 0.7f), Offset(xZero, barTop - 3f), Offset(xZero, barTop + barH + 3f), strokeWidth = 1.5f)

        // Initial Skew Marker
        val xInit = toX(initialSkewMs)
        drawCircle(color = Color(0xFFFFEB3B), radius = 6f, center = Offset(xInit, barTop + barH / 2f))
        drawCircle(color = Color(0xFF1E1E1E), radius = 2.5f, center = Offset(xInit, barTop + barH / 2f))

        // Top labels
        drawText(textMeasurer, "◀ 비디오 선행 (오디오 지연)", topLeft = Offset(padX, 2f), style = axisStyle)
        val rightLabel = textMeasurer.measure("오디오 선행 (비디오 지연) ▶", axisStyle)
        drawText(rightLabel, topLeft = Offset((w - padX - rightLabel.size.width).coerceAtLeast(padX + 120f), 2f))

        val initTag = String.format(java.util.Locale.US, "시작 %+.1fms", initialSkewMs)
        val initLayout = textMeasurer.measure(initTag, badgeStyle)
        drawText(
            initLayout,
            topLeft = Offset((xInit - initLayout.size.width / 2f).coerceIn(padX, (w - padX - initLayout.size.width).coerceAtLeast(padX)), 2f),
        )

        // Bottom threshold ticks
        listOf(-200.0 to "-200", -125.0 to "-125(한계)", -45.0 to "-45", 0.0 to "0ms", 45.0 to "+45(한계)", 200.0 to "+200").forEach { (ms, txt) ->
            val tx = toX(ms)
            drawLine(Color(0x55FFFFFF), Offset(tx, barTop + barH), Offset(tx, barTop + barH + 3f), strokeWidth = 1f)
            val m = textMeasurer.measure(txt, axisStyle)
            drawText(m, topLeft = Offset((tx - m.size.width / 2f).coerceIn(2f, (w - m.size.width - 2f).coerceAtLeast(2f)), barTop + barH + 4f))
        }
    }
}

/**
 * Plots actual frame-to-frame / packet-to-packet PTS intervals (ΔPTS in ms)
 * to make frame drops, VFR stalls, and audio packet gaps immediately visible.
 */
@Composable
internal fun AvIntervalJitterGraph(
    report: AvSyncReport,
    selectedPoint: SyncPoint?,
    onSelectPoint: (SyncPoint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = Color(0xFF9AA0A6), fontSize = 9.sp)
    val vSamples = report.videoIntervalSamples
    val aSamples = report.audioIntervalSamples
    if (vSamples.isEmpty() && aSamples.isEmpty()) return

    val allTimes = (vSamples.map { it.timeSeconds } + aSamples.map { it.timeSeconds })
    val t0 = allTimes.minOrNull() ?: 0.0
    val span = ((allTimes.maxOrNull() ?: 1.0) - t0).coerceAtLeast(0.01)

    val maxPlottedMs = maxOf(
        vSamples.maxOfOrNull { it.deltaPtsMs } ?: 0.0,
        aSamples.maxOfOrNull { it.deltaPtsMs } ?: 0.0,
        report.nominalVideoStepMs * 2.5,
        60.0,
    ) * 1.15

    Canvas(
        modifier = modifier.pointerInput(report.syncPoints) {
            detectTapGestures { offset ->
                if (report.syncPoints.isEmpty()) return@detectTapGestures
                val padX = 42f
                val graphW = (size.width - padX * 2).coerceAtLeast(1f)
                val frac = ((offset.x - padX) / graphW).coerceIn(0f, 1f)
                val targetTime = t0 + frac * span
                onSelectPoint(report.syncPoints.minByOrNull { abs(it.timeSeconds - targetTime) })
            }
        }
    ) {
        val w = size.width
        val h = size.height
        val padX = 42f
        val padY = 22f
        val graphW = (w - padX * 2).coerceAtLeast(1f)
        val graphH = (h - padY * 2).coerceAtLeast(1f)

        fun toX(sec: Double): Float = padX + (((sec - t0) / span).toFloat().coerceIn(0f, 1f) * graphW)
        fun toY(ms: Double): Float {
            val clamped = ms.coerceIn(0.0, maxPlottedMs)
            return padY + graphH - ((clamped / maxPlottedMs).toFloat() * graphH)
        }

        // Baseline & nominal interval reference lines
        val yZero = toY(0.0)
        drawLine(Color(0x55FFFFFF), Offset(padX, yZero), Offset(w - padX, yZero), strokeWidth = 1f)

        val yNomV = toY(report.nominalVideoStepMs)
        drawLine(Color(0x5564B5F6), Offset(padX, yNomV), Offset(w - padX, yNomV), strokeWidth = 1f)
        drawText(
            textMeasurer,
            String.format(java.util.Locale.US, "V 정상 %.1fms", report.nominalVideoStepMs),
            topLeft = Offset(2f, (yNomV - 6f).coerceIn(padY, h - padY - 12f)),
            style = axisStyle.copy(color = Color(0xFF64B5F6)),
        )

        val yNomA = toY(report.nominalAudioStepMs)
        drawLine(Color(0x5581C784), Offset(padX, yNomA), Offset(w - padX, yNomA), strokeWidth = 1f)

        // Y-axis top tick
        drawText(
            textMeasurer,
            String.format(java.util.Locale.US, "%.0fms", maxPlottedMs),
            topLeft = Offset(2f, padY),
            style = axisStyle,
        )

        // Plot Audio interval line
        if (aSamples.isNotEmpty()) {
            val aPath = Path()
            aSamples.forEachIndexed { idx, s ->
                val x = toX(s.timeSeconds)
                val y = toY(s.deltaPtsMs)
                if (idx == 0) aPath.moveTo(x, y) else aPath.lineTo(x, y)
            }
            drawPath(aPath, color = Color(0xCC66BB6A), style = Stroke(width = 1.8f))
        }

        // Plot Video interval line
        if (vSamples.isNotEmpty()) {
            val vPath = Path()
            vSamples.forEachIndexed { idx, s ->
                val x = toX(s.timeSeconds)
                val y = toY(s.deltaPtsMs)
                if (idx == 0) vPath.moveTo(x, y) else vPath.lineTo(x, y)
            }
            drawPath(vPath, color = Color(0xFF42A5F5), style = Stroke(width = 2.2f))
        }

        // Highlight detected anomalies (frame drops / audio gaps) with red markers
        report.anomalies.take(15).forEach { anomaly ->
            val ax = toX(anomaly.ptsSeconds)
            val ay = toY(anomaly.gapMs.coerceAtLeast(0.0))
            drawCircle(
                color = if (anomaly.severity == SyncSeverity.CRITICAL) Color(0xFFEF5350) else Color(0xFFFFB74D),
                radius = 5f,
                center = Offset(ax, ay),
            )
        }

        // X-axis time ticks
        for (i in 0..3) {
            val frac = i / 3f
            val tx = padX + frac * graphW
            drawLine(Color(0x40FFFFFF), Offset(tx, h - padY), Offset(tx, h - padY + 4f), strokeWidth = 1f)
            drawText(
                textMeasurer,
                formatMinSec(frac.toDouble() * span),
                topLeft = Offset(tx - 12f, h - padY + 5f),
                style = axisStyle,
            )
        }

        // Selected point cursor
        if (selectedPoint != null) {
            val sx = toX(selectedPoint.timeSeconds)
            drawLine(Color(0xFFFFEB3B), Offset(sx, padY), Offset(sx, h - padY), strokeWidth = 1.5f)
        }
    }
}
