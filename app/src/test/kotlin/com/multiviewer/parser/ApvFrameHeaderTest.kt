package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ApvFrameHeaderTest {
    // Same real access-unit bytes as ApvPbuTest.realAccessUnitPrefix -- see that file's comment for
    // provenance. The frame() payload (what parseApvFrameHeader consumes) starts at byte 16 of that
    // same sequence, defined independently here since each test file in this codebase's convention
    // owns its own fixture bytes rather than sharing them cross-file (see e.g. Av1CBoxDecoderTest's
    // own realAv1CPayload(), independent of Av1ObuTest's fixtures).
    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

    private val realFramePayload = hexToBytes(
        "217b40000f00000870220000000000400002000000000ab900140000000006b300000216000001dc333333009ddd9073",
    )

    @Test
    fun `parseApvFrameHeader extracts every curated field matching the hand-verified real values`() {
        val header = parseApvFrameHeader(realFramePayload)

        assertNotNull(header)
        assertEquals(33, header.profileIdc)
        assertEquals("422-10", header.profileName)
        assertEquals(123, header.levelIdc)
        assertEquals(2, header.bandIdc)
        assertEquals(3840, header.frameWidth)
        assertEquals(2160, header.frameHeight)
        assertEquals(ApvChromaFormat.YUV_422, header.chromaFormat)
        assertEquals(10, header.bitDepth)
        assertNull(header.colorPrimaries) // color_description_present_flag was 0 in this real frame
        assertEquals(16, header.tileWidthInMbs)
        assertEquals(8, header.tileHeightInMbs)
        assertEquals(255, header.tileCount) // TileCols=15 * TileRows=17, per the NumTiles formula
    }

    @Test
    fun `parseApvFrameHeader returns an unnamed profile for an unrecognized profile_idc`() {
        val mutated = realFramePayload.copyOf()
        mutated[0] = 200.toByte() // not in the known profile table
        val header = parseApvFrameHeader(mutated)
        assertNotNull(header)
        assertEquals(200, header.profileIdc)
        assertEquals(null, header.profileName)
    }

    @Test
    fun `parseApvFrameHeader returns null for truncated input`() {
        assertNull(parseApvFrameHeader(realFramePayload.copyOfRange(0, 5)))
    }
}
