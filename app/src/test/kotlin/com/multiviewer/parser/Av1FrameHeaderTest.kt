package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Av1FrameHeaderTest {
    // Real Sequence Header OBU payload (11 bytes) from the same libsvtav1-encoded 64x64, 5-frame,
    // low-delay (IPPP) MP4 used for every frame fixture below (`ffmpeg -f lavfi -i
    // testsrc=size=64x64:rate=10:duration=0.5 -c:v libsvtav1 -pix_fmt yuv420p -g 5 -svtav1-params
    // pred-struct=1:enable-overlays=0 out.mp4`) -- hand-decoded bit-by-bit against the AV1 spec's
    // sequence_header_obu() syntax, independently cross-checked via a from-scratch Python
    // implementation of the same syntax, and against dav1d's successful independent decode of the
    // raw extracted bitstream (see this plan's intro).
    private val realSeqHeaderBytes = byteArrayOf(
        0x02, 0x00, 0x00, 0x04, 0xd5.toByte(), 0x7f, 0xfc.toByte(), 0x6a, 0xf9.toByte(), 0x80.toByte(), 0x40,
    )
    private val seqHeader = parseAv1SequenceHeader(realSeqHeaderBytes)!!

    @Test
    fun `parseAv1FrameHeader extracts every curated field from a real KEY frame's OBU_FRAME payload`() {
        // Real bytes: Frame 0 of the 5-frame capture (ffprobe: key_frame=1, pict_type=I), truncated
        // to the first 5 bytes -- the bit parser consumes exactly 27 bits (< 4 bytes) to reach
        // base_q_idx for this frame; truncated and full 870-byte payload parse identically (verified
        // during planning).
        val payload = byteArrayOf(0x14, 0x00, 0xa5.toByte(), 0xa0.toByte(), 0x40)
        val header = parseAv1FrameHeader(payload, seqHeader)
        assertNotNull(header)
        assertEquals(Av1FrameType.KEY, header.frameType)
        assertEquals(true, header.showFrame)
        assertEquals(false, header.showableFrame)
        assertEquals(64, header.frameWidth)
        assertEquals(64, header.frameHeight)
        assertEquals(45, header.baseQIdx)
        assertEquals(1, header.tileCols)
        assertEquals(1, header.tileRows)
        assertEquals(255, header.refreshFrameFlags)
        assertEquals(0, header.orderHint)
    }

    @Test
    fun `parseAv1FrameHeader extracts every curated field from a real INTER frame's OBU_FRAME payload`() {
        // Real bytes: Frame 1 of the same capture (ffprobe: key_frame=0, pict_type=P), truncated to
        // the first 9 bytes (63 bits consumed to reach base_q_idx).
        val payload = byteArrayOf(0x30, 0x02, 0x00, 0x00, 0x00, 0xdb.toByte(), 0x3b, 0x18, 0x00)
        val header = parseAv1FrameHeader(payload, seqHeader)
        assertNotNull(header)
        assertEquals(Av1FrameType.INTER, header.frameType)
        assertEquals(true, header.showFrame)
        assertEquals(true, header.showableFrame)
        assertEquals(64, header.frameWidth)
        assertEquals(64, header.frameHeight)
        assertEquals(140, header.baseQIdx)
        assertEquals(1, header.tileCols)
        assertEquals(1, header.tileRows)
        assertEquals(0, header.refreshFrameFlags)
        assertEquals(1, header.orderHint)
    }

    @Test
    fun `parseAv1FrameHeader extracts a second real INTER frame with different quantization and refresh flags`() {
        // Real bytes: Frame 2 of the same capture (ffprobe: key_frame=0, pict_type=P), truncated to
        // the first 9 bytes (63 bits consumed) -- distinct base_q_idx/refresh_frame_flags/order_hint
        // from Frame 1 above, confirming these aren't accidentally hardcoded.
        val payload = byteArrayOf(0x30, 0x04, 0x04, 0x00, 0x00, 0xdb.toByte(), 0x3b, 0x06, 0x00)
        val header = parseAv1FrameHeader(payload, seqHeader)
        assertNotNull(header)
        assertEquals(Av1FrameType.INTER, header.frameType)
        assertEquals(true, header.showFrame)
        assertEquals(true, header.showableFrame)
        assertEquals(64, header.frameWidth)
        assertEquals(64, header.frameHeight)
        assertEquals(131, header.baseQIdx)
        assertEquals(1, header.tileCols)
        assertEquals(1, header.tileRows)
        assertEquals(16, header.refreshFrameFlags)
        assertEquals(2, header.orderHint)
    }

    @Test
    fun `parseAv1FrameHeader returns null for empty input`() {
        assertNull(parseAv1FrameHeader(ByteArray(0), seqHeader))
    }

    @Test
    fun `parseAv1FrameHeader returns null when show_existing_frame is set`() {
        // show_existing_frame=1 -> byte0 = 1000 0000 = 0x80. This frame repeats a previously
        // decoded frame's contents (AV1 spec 7.20) rather than carrying its own header fields.
        assertNull(parseAv1FrameHeader(byteArrayOf(0x80.toByte()), seqHeader))
    }

    @Test
    fun `parseAv1FrameHeader returns null when the sequence header has frame_id_numbers_present_flag set`() {
        // seqHeader with frameIdNumbersPresentFlag forced true (this stream's real sequence header
        // has it false -- Av1SequenceHeader is a data class, so .copy() constructs a variant with
        // just this one field changed). Payload bits: show_existing_frame=0, frame_type=00 (KEY),
        // show_frame=1, disable_cdf_update=0, allow_screen_content_tools=0 (seq_force_screen_content_
        // tools is SELECT in this stream, so this bit is read) -> 0 00 1 0 0 -> 00010000 = 0x10. The
        // parser bails right after reading this prefix, before reading anything else.
        val seqHeaderWithFrameIds = seqHeader.copy(frameIdNumbersPresentFlag = true)
        assertNull(parseAv1FrameHeader(byteArrayOf(0x10), seqHeaderWithFrameIds))
    }

    @Test
    fun `parseAv1FrameHeader returns null for a SWITCH_FRAME (frame_size_override_flag forced true)`() {
        // show_existing_frame=0, frame_type=11 (SWITCH), show_frame=1, disable_cdf_update=0,
        // allow_screen_content_tools=0 -> 0 11 1 0 0 -> 01110000 = 0x70. SWITCH_FRAME forces
        // frame_size_override_flag = 1 without reading a bit for it, so the parser bails
        // immediately once frame_type is known to be SWITCH_FRAME.
        assertNull(parseAv1FrameHeader(byteArrayOf(0x70), seqHeader))
    }

    @Test
    fun `parseAv1FrameHeader returns null when tile_info signals non-uniform tile spacing`() {
        // A full, valid KEY-frame header prefix (show_existing_frame=0, frame_type=00, show_frame=1,
        // disable_cdf_update=0, allow_screen_content_tools=0, frame_size_override_flag=0,
        // order_hint=0000000 [7 bits, OrderHintBits=7 for this stream], render_and_frame_size_
        // different=0, disable_frame_end_update_cdf=0 -- 16 bits total) followed by tile_info()'s
        // uniform_tile_spacing_flag=0 (1 more bit) -- 17 bits, packed as 0x10 0x00 0x00.
        assertNull(parseAv1FrameHeader(byteArrayOf(0x10, 0x00, 0x00), seqHeader))
    }
}
