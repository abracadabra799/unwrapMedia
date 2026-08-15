package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitReaderTest {
    // Real H.264 SPS bytes (25 bytes, NAL header 0x67 at index 0) -- from an x264-encoded file,
    // field values cross-verified by hand against `ffmpeg -bsf:v trace_headers` output (see the
    // design spec). profile_idc=244, level_idc=13 confirmed at byte offsets 1 and 3.
    private val realSps = byteArrayOf(
        0x67, 0xf4.toByte(), 0x00, 0x0d, 0x91.toByte(), 0x9b.toByte(), 0x28, 0x28,
        0x3f, 0x60, 0x22, 0x00, 0x00, 0x03, 0x00, 0x02,
        0x00, 0x00, 0x03, 0x00, 0x64, 0x1e, 0x28, 0x53.toByte(), 0x2c,
    )

    @Test
    fun `readBits reads MSB-first fixed-width values, matching real SPS profile_idc and level_idc`() {
        // Skip the 1-byte NAL header (startByteOffset=1) -- profile_idc is the next full byte.
        val reader = BitReader(realSps, startByteOffset = 1)
        assertEquals(244, reader.readBits(8)) // profile_idc
        reader.readBits(8) // constraint_set0..5_flag (6 bits) + reserved_zero_2bits (2 bits)
        assertEquals(13, reader.readBits(8)) // level_idc
    }

    // Real H.264 slice header prefix (first IDR slice, NAL header 0x65 at index 0) -- same source
    // file, verified against trace_headers: first_mb_in_slice=0, slice_type=7,
    // pic_parameter_set_id=0, decoded here as three sequential ue(v) reads on the same reader.
    private val realSliceHeaderPrefix = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00)

    @Test
    fun `readUe decodes three sequential Exp-Golomb values from a real slice header`() {
        val reader = BitReader(realSliceHeaderPrefix, startByteOffset = 1)
        assertEquals(0, reader.readUe()) // first_mb_in_slice
        assertEquals(7, reader.readUe()) // slice_type
        assertEquals(0, reader.readUe()) // pic_parameter_set_id
    }

    @Test
    fun `readSe maps Exp-Golomb codeNum to signed values per the H264 spec's table 9-3`() {
        // codeNum 0->1 bit "1", 1->3 bits "010", 2->"011", 3->"00100", 4->"00101"
        // ue(v) mapping: codeNum even -> -(codeNum/2), odd -> (codeNum+1)/2
        assertEquals(0, BitReader(byteArrayOf(0b10000000.toByte())).readSe())
        assertEquals(1, BitReader(byteArrayOf(0b01000000.toByte())).readSe())
        assertEquals(-1, BitReader(byteArrayOf(0b01100000.toByte())).readSe())
        assertEquals(2, BitReader(byteArrayOf(0b00100000.toByte())).readSe())
        assertEquals(-2, BitReader(byteArrayOf(0b00101000.toByte())).readSe())
    }

    @Test
    fun `readFlag reads a single bit as a boolean`() {
        val reader = BitReader(byteArrayOf(0b10100000.toByte()))
        assertTrue(reader.readFlag())
        assertFalse(reader.readFlag())
        assertTrue(reader.readFlag())
    }

    @Test
    fun `readBits32 assembles a full unsigned 32-bit value without sign overflow`() {
        // 0xFFFFFFFF as a plain Int would be -1 -- readBits32 must return it as a positive Long.
        val reader = BitReader(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        assertEquals(4294967295L, reader.readBits32())
    }

    @Test
    fun `bitsRemaining reflects consumed bits`() {
        val reader = BitReader(byteArrayOf(0x00, 0x00))
        assertEquals(16, reader.bitsRemaining())
        reader.readBits(5)
        assertEquals(11, reader.bitsRemaining())
    }

    @Test
    fun `readBits throws once past the end of the data instead of returning garbage`() {
        val reader = BitReader(byteArrayOf(0x00))
        reader.readBits(8)
        assertTrue(runCatching { reader.readBits(1) }.isFailure)
    }
}
