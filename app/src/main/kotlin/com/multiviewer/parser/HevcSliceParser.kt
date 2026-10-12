package com.multiviewer.parser

import com.multiviewer.ui.FfmpegLocator
import com.multiviewer.ui.readProcessOutputWithTimeout
import java.io.File

data class HevcSliceInfo(
    val sliceType: String,
    val sliceQp: Int,
    val sliceQpDelta: Int,
    val isIdr: Boolean,
)

/**
 * Parses an IDR (IRAP) HEVC slice segment header to extract slice_type and slice_qp_delta,
 * computing the final Slice QP as `26 + init_qp_minus26 + slice_qp_delta`.
 */
fun parseHevcIdrSliceHeader(nalBytes: ByteArray, pps: HevcPps?): HevcSliceInfo? {
    if (nalBytes.size < 3) return null

    // HEVC NAL header: 2 bytes
    // byte 0: forbidden_zero_bit (1), nal_unit_type (6), nuh_layer_id high (1)
    val nalUnitType = (nalBytes[0].toInt() and 0x7E) shr 1
    val isIdr = nalUnitType in 19..20 // IDR_W_RADL (19), IDR_N_LP (20)
    val isIrap = nalUnitType in 16..23
    if (!isIrap) return null

    return try {
        val payload = removeEmulationPreventionBytes(nalBytes)
        val reader = BitReader(payload, startByteOffset = 2)

        val firstSliceSegmentInPicFlag = reader.readFlag()
        if (!firstSliceSegmentInPicFlag) return null

        val noOutputOfPriorPicsFlag = reader.readFlag() // for IRAP
        val slicePicParameterSetId = reader.readUe()

        // slice_type: 0 = B, 1 = P, 2 = I
        val sliceTypeCode = reader.readUe()
        val sliceType = when (sliceTypeCode) {
            0 -> "B-Slice"
            1 -> "P-Slice"
            2 -> "I-Slice"
            else -> "Slice($sliceTypeCode)"
        }

        // For pure IDR without optional flags, slice_qp_delta is encoded as se(v)
        val sliceQpDelta = reader.readSe()
        val initQp = pps?.initQpMinus26 ?: 0
        val finalSliceQp = (26 + initQp + sliceQpDelta).coerceIn(0, 51)

        HevcSliceInfo(
            sliceType = sliceType,
            sliceQp = finalSliceQp,
            sliceQpDelta = sliceQpDelta,
            isIdr = isIdr,
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * Probes slice information for any HEVC frame using ffmpeg's trace_headers bitstream filter.
 * Cheap and accurate for single-frame lookup.
 */
fun probeHevcFrameSliceQpWithFfmpeg(file: File, ptsSeconds: Double): HevcSliceInfo? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffmpegPath(), "-v", "info",
            "-ss", ptsSeconds.toString(),
            "-i", file.absolutePath,
            "-c:v", "copy",
            "-bsf:v", "trace_headers",
            "-frames:v", "1",
            "-f", "null", "-",
        ).redirectErrorStream(true)
            .also { FfmpegLocator.configureEnvironment(it) }.start()

        val output = readProcessOutputWithTimeout(process, 10) {
            process.inputStream.bufferedReader().readText()
        } ?: return null

        var sliceType: String? = null
        var sliceQpDelta: Int? = null

        for (line in output.lines()) {
            if (sliceType == null && line.contains("slice_type")) {
                val code = line.substringAfter("=").trim().toIntOrNull()
                sliceType = when (code) {
                    0 -> "B-Slice"
                    1 -> "P-Slice"
                    2 -> "I-Slice"
                    else -> "Slice($code)"
                }
            }
            if (sliceQpDelta == null && line.contains("slice_qp_delta")) {
                sliceQpDelta = line.substringAfter("=").trim().toIntOrNull()
            }
            if (sliceType != null && sliceQpDelta != null) break
        }

        if (sliceType != null && sliceQpDelta != null) {
            val qp = (26 + sliceQpDelta).coerceIn(0, 51)
            HevcSliceInfo(
                sliceType = sliceType,
                sliceQp = qp,
                sliceQpDelta = sliceQpDelta,
                isIdr = sliceType == "I-Slice",
            )
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}
