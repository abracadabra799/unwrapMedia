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
