package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class TkhdBoxDecoderTest {
    @Test
    fun `decodes version 0 track_ID, duration, width and height`() {
        val body = ByteArray(4 + 4 + 4 + 4 + 4 + 4 + 8 + 2 + 2 + 2 + 2 + 36 + 4 + 4)
        // version/flags = 0 (already zero-filled)
        // creation_time, modification_time = 0 (already zero-filled)
        writeUInt32(body, 12, 7L)          // track_ID = 7
        // reserved (4 bytes) already zero
        writeUInt32(body, 20, 10L)         // duration = 10
        // reserved[2] + layer + alternate_group + volume + reserved + matrix = 52 bytes, already zero
        writeUInt32(body, 20 + 4 + 52, 1920L * 65536L) // width = 1920.0 as 16.16 fixed point
        writeUInt32(body, 20 + 4 + 52 + 4, 1080L * 65536L) // height = 1080.0

        val reader = byteReaderOf(body)
        val node = TkhdBoxDecoder.decode(reader, "tkhd", 0, 0, body.size.toLong(), emptyList())
        assertEquals("track_ID=7, 1920x1080", node.summary)
        reader.close()
    }
}

private fun writeUInt32(bytes: ByteArray, offset: Int, value: Long) {
    bytes[offset] = ((value shr 24) and 0xFF).toByte()
    bytes[offset + 1] = ((value shr 16) and 0xFF).toByte()
    bytes[offset + 2] = ((value shr 8) and 0xFF).toByte()
    bytes[offset + 3] = (value and 0xFF).toByte()
}
