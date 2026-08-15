package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Av1SequenceHeaderTest {
    // Real Sequence Header OBU payload (11 bytes, OBU header/leb128-size prefix already stripped),
    // captured from a libsvtav1-encoded 320x240 MP4. Hand-decoded bit-by-bit against the AV1
    // spec's sequence_header_obu() syntax -- every field asserted below was traced this way, and
    // maxFrameWidth/maxFrameHeight were independently confirmed against the source encode's actual
    // 320x240 dimensions (also cross-checked via `ffmpeg -v verbose -i out.mp4 -f null -`, which
    // decodes via libdav1d and independently reported Main profile / 320x240 / yuv420p for the same
    // file -- see the design spec's Testing section and this plan's own intro).
    private val realSeqHeader = byteArrayOf(
        0x02, 0x00, 0x00, 0x05, 0x61, 0xe7.toByte(), 0xfd.toByte(), 0xe0.toByte(), 0x17, 0xc0.toByte(), 0x02,
    )

    @Test
    fun `parseAv1SequenceHeader extracts every curated field correctly from a real Sequence Header OBU`() {
        val seqHeader = parseAv1SequenceHeader(realSeqHeader)
        assertNotNull(seqHeader)
        assertEquals(0, seqHeader.seqProfile)
        assertFalse(seqHeader.stillPicture)
        assertEquals(0, seqHeader.seqLevelIdx0)
        assertEquals(0, seqHeader.seqTierIdx0)
        assertEquals(8, seqHeader.bitDepth)
        assertFalse(seqHeader.monochrome)
        assertEquals(1, seqHeader.chromaSubsamplingX)
        assertEquals(1, seqHeader.chromaSubsamplingY)
        assertEquals(2, seqHeader.colorPrimaries) // CP_UNSPECIFIED -- color_description_present_flag=0 in this encode
        assertEquals(2, seqHeader.transferCharacteristics) // TC_UNSPECIFIED
        assertEquals(2, seqHeader.matrixCoefficients) // MC_UNSPECIFIED
        assertEquals(320, seqHeader.maxFrameWidth)
        assertEquals(240, seqHeader.maxFrameHeight)
        assertFalse(seqHeader.use128x128Superblock)
        assertFalse(seqHeader.filmGrainParamsPresent)
    }

    @Test
    fun `parseAv1SequenceHeader returns null for empty input`() {
        assertNull(parseAv1SequenceHeader(ByteArray(0)))
    }

    @Test
    fun `parseAv1SequenceHeader returns null when reduced_still_picture_header is set`() {
        // seq_profile=000, still_picture=0, reduced_still_picture_header=1 -> byte0 = 00001000 = 0x08.
        assertNull(parseAv1SequenceHeader(byteArrayOf(0x08)))
    }

    @Test
    fun `parseAv1SequenceHeader returns null when timing_info_present_flag is set`() {
        // seq_profile=000, still_picture=0, reduced_still_picture_header=0, timing_info_present_flag=1
        // -> byte0 = 00000100 = 0x04.
        assertNull(parseAv1SequenceHeader(byteArrayOf(0x04)))
    }
}
