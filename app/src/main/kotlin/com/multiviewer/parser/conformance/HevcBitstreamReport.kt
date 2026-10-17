package com.multiviewer.parser.conformance

data class HevcBitstreamReport(
    val findings: List<ConformanceFinding>,
    val checkedSamples: Int,
    val indexedSamples: Int,
    val totalOccurrences: Map<String, Long>,
    val limitations: List<String>,
    val checkedTrack: String,
    val complete: Boolean,
)
