package com.multiviewer.ui

import com.multiviewer.parser.FrameHdrRecord
import com.multiviewer.parser.HdrAnomaly
import java.util.Locale

// Hand-written CSV, same approach as QualityExport.kt (no CSV library in this codebase).
private fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

private fun d1(v: Double) = String.format(Locale.US, "%.1f", v)

fun buildHdrFramesCsv(records: List<FrameHdrRecord>): String = buildString {
    append("frame,sample,pts_seconds,keyframe,metadata,maxscl_r_nits,maxscl_g_nits,maxscl_b_nits,average_maxrgb_nits,targeted_max_nits,mdcv_max_nits,mdcv_min_nits,max_cll,max_fall\n")
    for (r in records) {
        val w = r.hdr10Plus?.windows?.firstOrNull()
        val md = r.masteringDisplay
        val cll = r.contentLightLevel
        val cells = listOf(
            r.frameNumber.toString(),
            r.sampleIndex.toString(),
            String.format(Locale.US, "%.6f", r.ptsSeconds),
            if (r.isSync) "1" else "0",
            r.units.joinToString("|") { it.name },
            w?.let { d1(it.maxscl[0] / 10.0) } ?: "",
            w?.let { d1(it.maxscl[1] / 10.0) } ?: "",
            w?.let { d1(it.maxscl[2] / 10.0) } ?: "",
            w?.let { d1(it.averageMaxrgbNits) } ?: "",
            r.hdr10Plus?.targetedSystemDisplayMaximumLuminance?.toString() ?: "",
            md?.let { String.format(Locale.US, "%.4f", it.maxLuminance) } ?: "",
            md?.let { String.format(Locale.US, "%.4f", it.minLuminance) } ?: "",
            cll?.maxCll?.toString() ?: "",
            cll?.maxFall?.toString() ?: "",
        )
        append(cells.joinToString(",") { csvField(it) }).append('\n')
    }
}

fun buildHdrAnomaliesCsv(anomalies: List<HdrAnomaly>): String = buildString {
    append("severity,rule,first_frame,last_frame,message\n")
    for (a in anomalies) {
        val cells = listOf(a.severity.name, a.rule.name, a.firstFrame?.toString() ?: "", a.lastFrame?.toString() ?: "", a.message)
        append(cells.joinToString(",") { csvField(it) }).append('\n')
    }
}
