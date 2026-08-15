package com.multiviewer.parser

import com.multiviewer.ui.FrameInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Av1FrameHeaderAnalyzerTest {
    // Same real Sequence Header used by Av1FrameHeaderTest -- see that file's fixture comment for
    // provenance (libsvtav1-encoded 64x64, 5-frame, low-delay MP4).
    private val realSeqHeaderBytes = byteArrayOf(
        0x02, 0x00, 0x00, 0x04, 0xd5.toByte(), 0x7f, 0xfc.toByte(), 0x6a, 0xf9.toByte(), 0x80.toByte(), 0x40,
    )
    private val seqHeader = parseAv1SequenceHeader(realSeqHeaderBytes)!!

    // A sample containing: a Temporal Delimiter OBU (obu_type=2, has_size_field=1, obu_size=0 ->
    // header byte 0x12, size byte 0x00), then an OBU_FRAME (obu_type=6, has_size_field=1 -> header
    // byte 0x32, matching this stream's real captured OBU_FRAME header byte) whose leb128 size is
    // set to 9 -- matching Av1FrameHeaderTest's real, truncated Frame-1 payload length, rather than
    // that frame's true 35-byte size. This test is about locating and bounding the OBU, not about
    // re-proving bit-level field values, which Av1FrameHeaderTest already covers with real,
    // untruncated framing.
    private fun sampleBytes(): ByteArray = byteArrayOf(
        0x12, 0x00, // Temporal Delimiter OBU
        0x32, 0x09, // OBU_FRAME header + leb128 size=9
        0x30, 0x02, 0x00, 0x00, 0x00, 0xdb.toByte(), 0x3b, 0x18, 0x00, // real, truncated Frame-1 payload
    )

    private fun fileWithSample(): java.io.File {
        val headerSize = 8 // irrelevant filler, matches Av1ParameterSetExtractionTest's convention
        return fileOf(ByteArray(headerSize) + sampleBytes())
    }

    @Test
    fun `analyzeAv1FrameHeaders locates the OBU_FRAME past a leading Temporal Delimiter and parses it`() {
        val file = fileWithSample()
        val frames = listOf(FrameInfo(index = 0, type = 'P', sizeBytes = sampleBytes().size, ptsSeconds = 0.1, byteOffset = 8L))
        val result = analyzeAv1FrameHeaders(file, frames, seqHeader)
        assertEquals(setOf(8L), result.keys)
        val header = result.getValue(8L)
        assertEquals(Av1FrameType.INTER, header.frameType)
        assertEquals(140, header.baseQIdx)
        assertEquals(1, header.orderHint)
    }

    @Test
    fun `analyzeAv1FrameHeaders skips a frame with no byteOffset`() {
        val file = fileWithSample()
        val frames = listOf(
            FrameInfo(index = 0, type = 'P', sizeBytes = sampleBytes().size, ptsSeconds = 0.0, byteOffset = null),
            FrameInfo(index = 1, type = 'P', sizeBytes = sampleBytes().size, ptsSeconds = 0.1, byteOffset = 8L),
        )
        val result = analyzeAv1FrameHeaders(file, frames, seqHeader)
        assertEquals(setOf(8L), result.keys)
    }

    @Test
    fun `analyzeAv1FrameHeaders omits a frame whose sample has no FRAME or FRAME_HEADER OBU`() {
        // Just a Temporal Delimiter, nothing else -- no OBU_FRAME/OBU_FRAME_HEADER to find.
        val onlyTd = byteArrayOf(0x12, 0x00)
        val file = fileOf(ByteArray(8) + onlyTd)
        val frames = listOf(FrameInfo(index = 0, type = 'P', sizeBytes = onlyTd.size, ptsSeconds = 0.0, byteOffset = 8L))
        val result = analyzeAv1FrameHeaders(file, frames, seqHeader)
        assertTrue(result.isEmpty())
    }
}
