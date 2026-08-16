package com.multiviewer.parser

import java.io.File

// Reads one frame's raw MP4 sample bytes (FrameInfo.byteOffset/sizeBytes) and parses its APV frame
// header. Lazy, on-demand, per frame -- no whole-stream pass needed, since every APV frame_header()
// is self-contained (see this plan's Architecture section for why this differs from AV1's approach).
fun resolveApvFrameHeader(file: File, byteOffset: Long, sizeBytes: Int): ApvFrameHeader? {
    return try {
        ByteReader.open(file).use { reader ->
            val accessUnitBytes = reader.readBytes(byteOffset, sizeBytes)
            val framePayload = findApvPrimaryFramePbuPayload(accessUnitBytes) ?: return@use null
            parseApvFrameHeader(framePayload)
        }
    } catch (e: Exception) {
        null
    }
}
