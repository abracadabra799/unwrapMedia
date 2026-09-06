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
