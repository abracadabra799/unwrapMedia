package com.multiviewer.parser.conformance

import com.multiviewer.parser.BoxNode
import java.io.File

/** Codecs with a sample-level byte checker. [label] is the user/AI-facing name. */
enum class BitstreamCodec(val label: String, val isAudio: Boolean) {
    HEVC("HEVC", false), AVC("AVC", false), AV1("AV1", false), VP9("VP9", false), APV("APV", false), VVC("VVC", false),
    AAC("AAC", true), OPUS("Opus", true), FLAC("FLAC", true),
}

/** One codec's byte-check result over the checked track's indexed samples. */
data class CodecBitstreamReport(
    val codec: BitstreamCodec,
    val findings: List<ConformanceFinding>,
    val checkedSamples: Int,
    val indexedSamples: Int,
    val totalOccurrences: Map<String, Long>,
    val limitations: List<String>,
    val checkedTrack: String,
    val complete: Boolean,
)

suspend fun analyzeCodecBitstream(
    codec: BitstreamCodec,
    file: File,
    root: BoxNode,
    onProgress: (Int, Int?) -> Unit,
): CodecBitstreamReport = when (codec) {
    BitstreamCodec.HEVC -> analyzeHevcBitstream(file, root, onProgress)
    BitstreamCodec.AVC -> analyzeAvcBitstream(file, root, onProgress)
    BitstreamCodec.AV1 -> analyzeAv1Bitstream(file, root, onProgress)
    BitstreamCodec.VP9 -> analyzeVp9Bitstream(file, root, onProgress)
    BitstreamCodec.APV -> analyzeApvBitstream(file, root, onProgress)
    BitstreamCodec.VVC -> analyzeVvcBitstream(file, root, onProgress)
    BitstreamCodec.AAC -> analyzeAacBitstream(file, root, onProgress)
    BitstreamCodec.OPUS -> analyzeOpusBitstream(file, root, onProgress)
    BitstreamCodec.FLAC -> analyzeFlacBitstream(file, root, onProgress)
}
