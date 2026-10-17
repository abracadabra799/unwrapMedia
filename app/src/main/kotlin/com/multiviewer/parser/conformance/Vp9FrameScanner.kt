package com.multiviewer.parser.conformance

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoSample

data class Vp9FrameObservation(
    val sampleIndex: Int,
    val frameIndex: Int,
    val offset: Long,
    val size: Long,
    val frameMarker: Int,
    val profile: Int,
    val showExistingFrame: Boolean,
    val frameType: Int?,
    val showFrame: Boolean?,
    val errorResilientMode: Boolean?,
    val syncCode: Int?,
    val bitDepth: Int?,
    val colorSpace: Int?,
    val width: Int?,
    val height: Int?,
    val profileReservedBit: Int?,
    val issue: String?,
)

data class Vp9SampleScan(
    val checkedFrames: Int,
    val consumedBytes: Long,
    val coverageReason: String?,
)

private class BoundedBitReader(
    private val reader: ByteReader,
    private val base: Long,
    private val limit: Long,
) {
    private var bitPos = 0L

    private fun availableBits(): Long = (limit - base) * 8 - bitPos

    fun readBit(): Int {
        if (availableBits() < 1) return -1
        val byteIndex = bitPos / 8
        val shift = 7 - (bitPos % 8).toInt()
        bitPos++
        return (reader.readUInt8(base + byteIndex) shr shift) and 1
    }

    fun readBits(n: Int): Long {
        var v = 0L
        repeat(n) {
            val b = readBit()
            if (b < 0) return -1L
            v = (v shl 1) or b.toLong()
        }
        return v
    }
}

/**
 * Scans VP9 frames from a sample index without allocating sample-sized buffers.
 * Detects superframe index if present, parses uncompressed frame headers.
 */
fun scanVp9Sample(
    reader: ByteReader,
    sample: VideoSample,
    onFrame: (Vp9FrameObservation) -> Unit,
): Vp9SampleScan {
    if (sample.offset < 0 || sample.size < 0 || sample.offset > Long.MAX_VALUE - sample.size) {
        return Vp9SampleScan(0, 0, "sample_extent_overflow")
    }
    val end = sample.offset + sample.size
    if (end > reader.length) return Vp9SampleScan(0, 0, "sample_outside_file")
    if (sample.size == 0L) return Vp9SampleScan(0, 0, null)

    // Check for superframe index at end of sample
    val footer = reader.readUInt8(end - 1)
    val hasSuperframe = (footer and 0xE0) == 0xC0
    var frameSizes = listOf(sample.size)
    if (hasSuperframe) {
        val bytesPerFramesize = ((footer shr 3) and 0x03) + 1
        val framesInSuperframe = (footer and 0x07) + 1
        val indexSize = 2L + framesInSuperframe * bytesPerFramesize
        if (indexSize <= sample.size) {
            val header = reader.readUInt8(end - indexSize)
            if (header == footer) {
                var p = end - indexSize + 1
                val sizes = mutableListOf<Long>()
                repeat(framesInSuperframe) {
                    var sz = 0L
                    repeat(bytesPerFramesize) { shift ->
                        sz = sz or (reader.readUInt8(p++).toLong() shl (shift * 8))
                    }
                    sizes += sz
                }
                val totalFramesSize = sizes.sum()
                if (totalFramesSize + indexSize <= sample.size) {
                    frameSizes = sizes
                }
            }
        }
    }

    var curOffset = sample.offset
    var frameIndex = 0
    var checkedFrames = 0

    for (frameSize in frameSizes) {
        if (frameSize <= 0 || curOffset + frameSize > end) {
            onFrame(
                Vp9FrameObservation(
                    sampleIndex = sample.index,
                    frameIndex = frameIndex,
                    offset = curOffset,
                    size = frameSize,
                    frameMarker = 0,
                    profile = 0,
                    showExistingFrame = false,
                    frameType = null,
                    showFrame = null,
                    errorResilientMode = null,
                    syncCode = null,
                    bitDepth = null,
                    colorSpace = null,
                    width = null,
                    height = null,
                    profileReservedBit = null,
                    issue = "truncated_frame_header",
                )
            )
            return Vp9SampleScan(checkedFrames, curOffset - sample.offset, "truncated_frame_header")
        }

        val br = BoundedBitReader(reader, curOffset, curOffset + frameSize)
        val frameMarker = br.readBits(2).toInt()
        val profileLow = br.readBit()
        val profileHigh = br.readBit()
        if (frameMarker < 0 || profileLow < 0 || profileHigh < 0) {
            onFrame(
                Vp9FrameObservation(
                    sampleIndex = sample.index,
                    frameIndex = frameIndex,
                    offset = curOffset,
                    size = frameSize,
                    frameMarker = if (frameMarker >= 0) frameMarker else 0,
                    profile = 0,
                    showExistingFrame = false,
                    frameType = null,
                    showFrame = null,
                    errorResilientMode = null,
                    syncCode = null,
                    bitDepth = null,
                    colorSpace = null,
                    width = null,
                    height = null,
                    profileReservedBit = null,
                    issue = "truncated_frame_header",
                )
            )
            return Vp9SampleScan(checkedFrames, curOffset - sample.offset, "truncated_frame_header")
        }

        val profile = (profileHigh shl 1) or profileLow
        var profileReserved: Int? = null
        if (profile == 3) {
            profileReserved = br.readBit()
            if (profileReserved < 0) {
                onFrame(
                    Vp9FrameObservation(
                        sampleIndex = sample.index,
                        frameIndex = frameIndex,
                        offset = curOffset,
                        size = frameSize,
                        frameMarker = frameMarker,
                        profile = profile,
                        showExistingFrame = false,
                        frameType = null,
                        showFrame = null,
                        errorResilientMode = null,
                        syncCode = null,
                        bitDepth = null,
                        colorSpace = null,
                        width = null,
                        height = null,
                        profileReservedBit = null,
                        issue = "truncated_frame_header",
                    )
                )
                return Vp9SampleScan(checkedFrames, curOffset - sample.offset, "truncated_frame_header")
            }
        }

        val showExisting = br.readBit()
        if (showExisting < 0) {
            onFrame(
                Vp9FrameObservation(
                    sampleIndex = sample.index,
                    frameIndex = frameIndex,
                    offset = curOffset,
                    size = frameSize,
                    frameMarker = frameMarker,
                    profile = profile,
                    showExistingFrame = false,
                    frameType = null,
                    showFrame = null,
                    errorResilientMode = null,
                    syncCode = null,
                    bitDepth = null,
                    colorSpace = null,
                    width = null,
                    height = null,
                    profileReservedBit = profileReserved,
                    issue = "truncated_frame_header",
                )
            )
            return Vp9SampleScan(checkedFrames, curOffset - sample.offset, "truncated_frame_header")
        }

        if (showExisting == 1) {
            val frameToShow = br.readBits(3)
            if (frameToShow < 0) {
                onFrame(
                    Vp9FrameObservation(
                        sampleIndex = sample.index,
                        frameIndex = frameIndex,
                        offset = curOffset,
                        size = frameSize,
                        frameMarker = frameMarker,
                        profile = profile,
                        showExistingFrame = true,
                        frameType = null,
                        showFrame = true,
                        errorResilientMode = null,
                        syncCode = null,
                        bitDepth = null,
                        colorSpace = null,
                        width = null,
                        height = null,
                        profileReservedBit = profileReserved,
                        issue = "truncated_frame_header",
                    )
                )
                return Vp9SampleScan(checkedFrames, curOffset - sample.offset, "truncated_frame_header")
            }
            onFrame(
                Vp9FrameObservation(
                    sampleIndex = sample.index,
                    frameIndex = frameIndex++,
                    offset = curOffset,
                    size = frameSize,
                    frameMarker = frameMarker,
                    profile = profile,
                    showExistingFrame = true,
                    frameType = null,
                    showFrame = true,
                    errorResilientMode = null,
                    syncCode = null,
                    bitDepth = null,
                    colorSpace = null,
                    width = null,
                    height = null,
                    profileReservedBit = profileReserved,
                    issue = null,
                )
            )
            checkedFrames++
            curOffset += frameSize
            continue
        }

        val frameType = br.readBit()
        val showFrame = br.readBit()
        val errorResilient = br.readBit()
        if (frameType < 0 || showFrame < 0 || errorResilient < 0) {
            onFrame(
                Vp9FrameObservation(
                    sampleIndex = sample.index,
                    frameIndex = frameIndex,
                    offset = curOffset,
                    size = frameSize,
                    frameMarker = frameMarker,
                    profile = profile,
                    showExistingFrame = false,
                    frameType = null,
                    showFrame = null,
                    errorResilientMode = null,
                    syncCode = null,
                    bitDepth = null,
                    colorSpace = null,
                    width = null,
                    height = null,
                    profileReservedBit = profileReserved,
                    issue = "truncated_frame_header",
                )
            )
            return Vp9SampleScan(checkedFrames, curOffset - sample.offset, "truncated_frame_header")
        }

        var syncCode: Int? = null
        var bitDepth: Int? = null
        var colorSpace: Int? = null
        var width: Int? = null
        var height: Int? = null

        if (frameType == 0) { // KEY_FRAME
            val sync = br.readBits(24)
            if (sync < 0) {
                onFrame(
                    Vp9FrameObservation(
                        sampleIndex = sample.index,
                        frameIndex = frameIndex,
                        offset = curOffset,
                        size = frameSize,
                        frameMarker = frameMarker,
                        profile = profile,
                        showExistingFrame = false,
                        frameType = frameType,
                        showFrame = showFrame == 1,
                        errorResilientMode = errorResilient == 1,
                        syncCode = null,
                        bitDepth = null,
                        colorSpace = null,
                        width = null,
                        height = null,
                        profileReservedBit = profileReserved,
                        issue = "truncated_frame_header",
                    )
                )
                return Vp9SampleScan(checkedFrames, curOffset - sample.offset, "truncated_frame_header")
            }
            syncCode = sync.toInt()

            bitDepth = if (profile >= 2) {
                val tenOrTwelve = br.readBit()
                if (tenOrTwelve < 0) {
                    onFrame(
                        Vp9FrameObservation(
                            sampleIndex = sample.index,
                            frameIndex = frameIndex,
                            offset = curOffset,
                            size = frameSize,
                            frameMarker = frameMarker,
                            profile = profile,
                            showExistingFrame = false,
                            frameType = frameType,
                            showFrame = showFrame == 1,
                            errorResilientMode = errorResilient == 1,
                            syncCode = syncCode,
                            bitDepth = null,
                            colorSpace = null,
                            width = null,
                            height = null,
                            profileReservedBit = profileReserved,
                            issue = "truncated_frame_header",
                        )
                    )
                    return Vp9SampleScan(checkedFrames, curOffset - sample.offset, "truncated_frame_header")
                }
                if (tenOrTwelve == 1) 12 else 10
            } else 8

            val cs = br.readBits(3)
            if (cs < 0) {
                onFrame(
                    Vp9FrameObservation(
                        sampleIndex = sample.index,
                        frameIndex = frameIndex,
                        offset = curOffset,
                        size = frameSize,
                        frameMarker = frameMarker,
                        profile = profile,
                        showExistingFrame = false,
                        frameType = frameType,
                        showFrame = showFrame == 1,
                        errorResilientMode = errorResilient == 1,
                        syncCode = syncCode,
                        bitDepth = bitDepth,
                        colorSpace = null,
                        width = null,
                        height = null,
                        profileReservedBit = profileReserved,
                        issue = "truncated_frame_header",
                    )
                )
                return Vp9SampleScan(checkedFrames, curOffset - sample.offset, "truncated_frame_header")
            }
            colorSpace = cs.toInt()

            if (colorSpace != 7) { // not CS_RGB
                val colorRange = br.readBit()
                if (colorRange < 0) {
                    onFrame(
                        Vp9FrameObservation(
                            sampleIndex = sample.index,
                            frameIndex = frameIndex,
                            offset = curOffset,
                            size = frameSize,
                            frameMarker = frameMarker,
                            profile = profile,
                            showExistingFrame = false,
                            frameType = frameType,
                            showFrame = showFrame == 1,
                            errorResilientMode = errorResilient == 1,
                            syncCode = syncCode,
                            bitDepth = bitDepth,
                            colorSpace = colorSpace,
                            width = null,
                            height = null,
                            profileReservedBit = profileReserved,
                            issue = "truncated_frame_header",
                        )
                    )
                    return Vp9SampleScan(checkedFrames, curOffset - sample.offset, "truncated_frame_header")
                }
                if (profile in listOf(1, 3)) {
                    if (br.readBits(3) < 0) {
                        onFrame(
                            Vp9FrameObservation(
                                sampleIndex = sample.index,
                                frameIndex = frameIndex,
                                offset = curOffset,
                                size = frameSize,
                                frameMarker = frameMarker,
                                profile = profile,
                                showExistingFrame = false,
                                frameType = frameType,
                                showFrame = showFrame == 1,
                                errorResilientMode = errorResilient == 1,
                                syncCode = syncCode,
                                bitDepth = bitDepth,
                                colorSpace = colorSpace,
                                width = null,
                                height = null,
                                profileReservedBit = profileReserved,
                                issue = "truncated_frame_header",
                            )
                        )
                        return Vp9SampleScan(checkedFrames, curOffset - sample.offset, "truncated_frame_header")
                    }
                }
            } else if (profile in listOf(1, 3)) {
                if (br.readBit() < 0) {
                    onFrame(
                        Vp9FrameObservation(
                            sampleIndex = sample.index,
                            frameIndex = frameIndex,
                            offset = curOffset,
                            size = frameSize,
                            frameMarker = frameMarker,
                            profile = profile,
                            showExistingFrame = false,
                            frameType = frameType,
                            showFrame = showFrame == 1,
                            errorResilientMode = errorResilient == 1,
                            syncCode = syncCode,
                            bitDepth = bitDepth,
                            colorSpace = colorSpace,
                            width = null,
                            height = null,
                            profileReservedBit = profileReserved,
                            issue = "truncated_frame_header",
                        )
                    )
                    return Vp9SampleScan(checkedFrames, curOffset - sample.offset, "truncated_frame_header")
                }
            }

            val w = br.readBits(16)
            val h = br.readBits(16)
            if (w < 0 || h < 0) {
                onFrame(
                    Vp9FrameObservation(
                        sampleIndex = sample.index,
                        frameIndex = frameIndex,
                        offset = curOffset,
                        size = frameSize,
                        frameMarker = frameMarker,
                        profile = profile,
                        showExistingFrame = false,
                        frameType = frameType,
                        showFrame = showFrame == 1,
                        errorResilientMode = errorResilient == 1,
                        syncCode = syncCode,
                        bitDepth = bitDepth,
                        colorSpace = colorSpace,
                        width = null,
                        height = null,
                        profileReservedBit = profileReserved,
                        issue = "truncated_frame_header",
                    )
                )
                return Vp9SampleScan(checkedFrames, curOffset - sample.offset, "truncated_frame_header")
            }
            width = w.toInt() + 1
            height = h.toInt() + 1
        }

        onFrame(
            Vp9FrameObservation(
                sampleIndex = sample.index,
                frameIndex = frameIndex++,
                offset = curOffset,
                size = frameSize,
                frameMarker = frameMarker,
                profile = profile,
                showExistingFrame = false,
                frameType = frameType,
                showFrame = showFrame == 1,
                errorResilientMode = errorResilient == 1,
                syncCode = syncCode,
                bitDepth = bitDepth,
                colorSpace = colorSpace,
                width = width,
                height = height,
                profileReservedBit = profileReserved,
                issue = null,
            )
        )
        checkedFrames++
        curOffset += frameSize
    }

    return Vp9SampleScan(checkedFrames, curOffset - sample.offset, null)
}
