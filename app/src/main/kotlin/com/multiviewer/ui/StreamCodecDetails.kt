package com.multiviewer.ui

import com.multiviewer.parser.SummaryField
import java.io.File

data class StreamCodecDetails(
    val videoFields: List<SummaryField>,
    val audioFields: List<SummaryField>,
)

// One ffprobe call, cost independent of video length (unlike -show_frames, used for GOP
// analysis) -- safe to run automatically on every video open. Output is flat "key=value" lines
// grouped per stream, each group starting with an "index=" line (verified directly against a
// real HEVC+AAC file and a synthetic video -- no multi-line fields are requested here, so unlike
// a bare -show_streams there's no side_data/displaymatrix block to worry about).
fun probeStreamDetails(file: File): StreamCodecDetails? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error",
            "-show_entries",
            "stream=index,codec_type,profile,level,pix_fmt,color_space,color_transfer,color_primaries,color_range,bit_rate,r_frame_rate,avg_frame_rate,channel_layout,nb_frames",
            "-of", "default=noprint_wrappers=1", file.absolutePath,
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()
        val lines = readProcessOutputWithTimeout(process, 30) { process.inputStream.bufferedReader().readLines() }
            ?: return null

        val videoFields = mutableListOf<SummaryField>()
        val audioFields = mutableListOf<SummaryField>()
        var values = mutableMapOf<String, String>()

        fun finalizeStream() {
            when (values["codec_type"]) {
                "video" -> videoFields.addAll(buildVideoFields(values))
                "audio" -> audioFields.addAll(buildAudioFields(values))
            }
        }

        for (line in lines) {
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            if (key == "index" && values.isNotEmpty()) {
                finalizeStream()
                values = mutableMapOf()
            }
            values[key] = value
        }
        if (values.isNotEmpty()) finalizeStream()

        if (videoFields.isEmpty() && audioFields.isEmpty()) null else StreamCodecDetails(videoFields, audioFields)
    } catch (e: Exception) {
        null
    }
}

private fun buildVideoFields(values: Map<String, String>): List<SummaryField> {
    val fields = mutableListOf<SummaryField>()
    values["profile"]?.let { fields.add(SummaryField("Profile", it)) }
    values["level"]?.let { fields.add(SummaryField("Level", it)) }
    values["nb_frames"]?.toIntOrNull()?.let { fields.add(SummaryField("Frame Count", it.toString())) }
    values["bit_rate"]?.toDoubleOrNull()?.let { fields.add(SummaryField("Bit Rate", formatCodecBitrate(it))) }
    values["pix_fmt"]?.let { pixFmt ->
        fields.add(SummaryField("Chroma Subsampling", chromaSubsamplingFrom(pixFmt)))
        fields.add(SummaryField("Bit Depth", bitDepthFrom(pixFmt)))
    }
    values["color_primaries"]?.let { fields.add(SummaryField("Color Primaries", it)) }
    values["color_transfer"]?.let { fields.add(SummaryField("Transfer Characteristics", it)) }
    values["color_space"]?.let { fields.add(SummaryField("Matrix Coefficients", it)) }
    values["color_range"]?.let { fields.add(SummaryField("Color Range", it)) }
    val rFrameRate = values["r_frame_rate"]
    val avgFrameRate = values["avg_frame_rate"]
    if (rFrameRate != null && avgFrameRate != null) {
        fields.add(SummaryField("Frame Rate Mode", if (rFrameRate == avgFrameRate) "Constant" else "Variable"))
    }
    return fields
}

private fun buildAudioFields(values: Map<String, String>): List<SummaryField> {
    val fields = mutableListOf<SummaryField>()
    values["profile"]?.let { fields.add(SummaryField("Profile", it)) }
    values["nb_frames"]?.toIntOrNull()?.let { fields.add(SummaryField("Frame Count", it.toString())) }
    values["bit_rate"]?.toDoubleOrNull()?.let { fields.add(SummaryField("Bit Rate", formatCodecBitrate(it))) }
    values["channel_layout"]?.let { fields.add(SummaryField("Channel Layout", it)) }
    return fields
}

private fun formatCodecBitrate(bitsPerSecond: Double): String = when {
    bitsPerSecond >= 1_000_000 -> "%.1f Mbps".format(bitsPerSecond / 1_000_000)
    bitsPerSecond >= 1_000 -> "%.1f Kbps".format(bitsPerSecond / 1_000)
    else -> "%.0f bps".format(bitsPerSecond)
}

private fun chromaSubsamplingFrom(pixFmt: String): String = when {
    pixFmt.contains("420") -> "4:2:0"
    pixFmt.contains("422") -> "4:2:2"
    pixFmt.contains("444") -> "4:4:4"
    else -> pixFmt
}

private fun bitDepthFrom(pixFmt: String): String = when {
    pixFmt.contains("10") -> "10 bit"
    pixFmt.contains("12") -> "12 bit"
    pixFmt.contains("16") -> "16 bit"
    else -> "8 bit"
}
