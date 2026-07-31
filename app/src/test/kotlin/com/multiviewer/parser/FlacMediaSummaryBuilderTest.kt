package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class FlacMediaSummaryBuilderTest {
    private fun buildFlacFixture(): BoxNode {
        val streamInfo = BoxNode(
            "STREAMINFO", 0, 0, 0,
            fields = listOf(
                BoxField("sample_rate", "44100", 0, 0),
                BoxField("channels", "2", 0, 0),
                BoxField("bits_per_sample", "16", 0, 0),
                BoxField("total_samples", "88200", 0, 0),
            ),
        )
        val flacMarker = BoxNode("fLaC", 0, 0, 0)
        return BoxNode("root", 0, 0, 0, children = listOf(flacMarker, streamInfo))
    }

    @Test
    fun `a fLaC root is classified as AUDIO`() {
        val root = buildFlacFixture()
        val tmp = File.createTempFile("flac-summary-category-test", ".flac")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(10))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(MediaCategory.AUDIO, summary.category)
    }

    @Test
    fun `a FLAC tree produces General and Audio sections with correct values`() {
        val root = buildFlacFixture()
        val tmp = File.createTempFile("flac-summary-test", ".flac")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(200_000))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(2, summary.sections.size)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("FLAC", general.fields.first { it.label == "Format" }.value)
        assertEquals("0:00:02.000", general.fields.first { it.label == "Duration" }.value)

        val audio = summary.sections.first { it.title == "Audio" }
        assertEquals("44100 Hz", audio.fields.first { it.label == "Sampling Rate" }.value)
        assertEquals("2", audio.fields.first { it.label == "Channel(s)" }.value)
        assertEquals("16-bit", audio.fields.first { it.label == "Bit Depth" }.value)
    }
}
