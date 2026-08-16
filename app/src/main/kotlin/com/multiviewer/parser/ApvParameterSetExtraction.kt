package com.multiviewer.parser

import java.io.File

// Enough for any frame_header() (verified: the real fields this parser reads fit well within 40
// bytes -- see docs/superpowers/plans/2026-08-16-apv-codec-support.md's Technical Foundation), not
// the full access unit, which for a high-bitrate intra frame (this codec's primary real-world use
// case) can be tens of MB. Mirrors Av1FrameHeaderAnalyzer.kt's own MAX_FRAME_HEADER_PREFIX_BYTES
// cap for the same reason.
private const val APV_FRAME_HEADER_PREFIX_BYTES = 4096

// Reads one frame's raw MP4 sample bytes (FrameInfo.byteOffset/sizeBytes) and parses its APV frame
// header. Lazy, on-demand, per frame -- no whole-stream pass needed, since every APV frame_header()
// is self-contained (see this plan's Architecture section for why this differs from AV1's approach).
// Reads only a bounded prefix, not the full sample -- findApvPrimaryFramePbuPayload's own
// clamp-to-available-bytes logic (see ApvPbu.kt) already handles this buffer being shorter than
// the frame's declared pbu_size correctly, so no change is needed there.
fun resolveApvFrameHeader(file: File, byteOffset: Long, sizeBytes: Int): ApvFrameHeader? {
    return try {
        ByteReader.open(file).use { reader ->
            val prefixLength = minOf(sizeBytes, APV_FRAME_HEADER_PREFIX_BYTES)
            val accessUnitBytes = reader.readBytes(byteOffset, prefixLength)
            val framePayload = findApvPrimaryFramePbuPayload(accessUnitBytes) ?: return@use null
            parseApvFrameHeader(framePayload)
        }
    } catch (e: Exception) {
        null
    }
}
