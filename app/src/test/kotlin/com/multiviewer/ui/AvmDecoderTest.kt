package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvmDecoderTest {
    @Test
    fun `AV2 track dispatch requires av02 entry and av2C configuration`() {
        val av2Track = com.multiviewer.parser.BoxNode(
            "root", 0, 0, 0,
            children = listOf(com.multiviewer.parser.BoxNode(
                "trak", 0, 0, 0,
                children = listOf(com.multiviewer.parser.BoxNode(
                    "av02", 0, 0, 0,
                    children = listOf(com.multiviewer.parser.BoxNode("av2C", 0, 0, 0)),
                )),
            )),
        )
        val incompleteTrack = com.multiviewer.parser.BoxNode(
            "root", 0, 0, 0,
            children = listOf(com.multiviewer.parser.BoxNode("av02", 0, 0, 0)),
        )

        assertTrue(isAv2VideoTrack(av2Track))
        assertFalse(isAv2VideoTrack(incompleteTrack))
        assertFalse(isAv2VideoTrack(null))
    }

    @Test
    fun `command decodes OBU input to Y4M on stdout`() {
        val input = File("C:/video with spaces/stream.obu")
        assertEquals(
            listOf("C:/tools/avmdec.exe", "-o", "-", input.path),
            avmDecoderCommand("C:/tools/avmdec.exe", input.path),
        )
    }

    @Test
    fun `missing decoder is reported as an actionable decode error`() {
        val input = File("missing-input.obu")

        val result = decodeAv2Bitstream("missing-avmdec-executable", input, onFrame = { true })

        assertFalse(result.succeeded)
        assertTrue(result.error.orEmpty().contains("missing-avmdec-executable"))
    }

    @Test
    fun `decoder rejects non 8 bit Y4M output before bitmap conversion`() {
        val format = parseY4mHeader("YUV4MPEG2 W8 H8 F24:1 C420p10")

        assertEquals(
            "AV2 playback supports 8-bit 4:2:0 Y4M output only (got 420p10, 10-bit)",
            av2OutputFormatError(format),
        )
    }
}
