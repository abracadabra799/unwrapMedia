package com.multiviewer.ui

import java.io.File

// Writes one comparison's results as CSV: a per-frame table only (frame_index + one column per
// computed metric). CSV doesn't nest well, and the per-frame series is CSV's natural use case
// (spreadsheet import/plotting); aggregate statistics are shown in the app UI directly and included
// in the JSON export (writeResultsJson) instead, which handles nested structure naturally. A metric
// with fewer frames than the longest one (shouldn't normally happen -- both passes run against the
// same two files -- but handled defensively) leaves its later cells blank rather than misaligning
// rows.
fun writeResultsCsv(destination: File, results: Map<String, MetricRunResult>) {
    val metricNames = results.keys.toList()

    // Build a map for each metric: frameIndex -> value
    val metricMaps = metricNames.associate { name ->
        name to results.getValue(name).perFrame.associate { it.frameIndex to it.value }
    }

    // Get sorted union of all frameIndex values across all metrics
    val allFrameIndices = metricMaps.values
        .flatMap { it.keys }
        .toSet()
        .sorted()

    destination.bufferedWriter().use { writer ->
        writer.write("frame_index," + metricNames.joinToString(",") + "\n")
        for (frameIndex in allFrameIndices) {
            val row = metricNames.joinToString(",") { name ->
                metricMaps.getValue(name)[frameIndex]?.toString() ?: ""
            }
            writer.write("$frameIndex,$row\n")
        }
    }
}

// Writes one comparison's full results (per-frame series + aggregate statistics for every computed
// metric) as JSON. Hand-written rather than pulling in a JSON library: this codebase has no existing
// JSON dependency (no kotlinx.serialization/Gson/Jackson/org.json anywhere in build.gradle.kts), and
// this output's shape is simple and fixed enough (one flat object per metric, no nested user input
// to escape -- metric names come from this app's own fixed set, and values are always finite Doubles
// after QualityMetrics.kt's inf-capping) that a library and its Gradle plugin wiring isn't justified
// for a write-only export.
fun writeResultsJson(destination: File, results: Map<String, MetricRunResult>) {
    destination.bufferedWriter().use { writer ->
        writer.write("{\n")
        val entries = results.entries.toList()
        entries.forEachIndexed { index, (name, result) ->
            writer.write("  \"$name\": {\n")
            writer.write("    \"statistics\": {")
            writer.write(
                "\"min\": ${result.statistics.min}, \"max\": ${result.statistics.max}, " +
                    "\"mean\": ${result.statistics.mean}, \"median\": ${result.statistics.median}",
            )
            writer.write("},\n")
            writer.write("    \"perFrame\": [")
            writer.write(result.perFrame.joinToString(", ") { "{\"frameIndex\": ${it.frameIndex}, \"value\": ${it.value}}" })
            writer.write("]\n")
            writer.write(if (index == entries.lastIndex) "  }\n" else "  },\n")
        }
        writer.write("}\n")
    }
}
