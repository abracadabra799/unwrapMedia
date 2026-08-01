package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AiffMediaSummaryBuilderTest {
    private fun buildAiffFixture(formType: String, compressionType: String?): BoxNode {
        val commFields = mutableListOf(
            BoxField("num_channels", "2", 0, 0),
            BoxField("num_sample_frames", "88200", 0, 0),
            BoxField("sample_size", "16", 0, 0),
            BoxField("sample_rate", "44100", 0, 0),
        )
        compressionType?.let { commFields.add(BoxField("compression_type", it, 0, 0)) }
        val comm = BoxNode("COMM", 0, 0, 0, fields = commFields)
        val form = BoxNode("FORM", 0, 0, 0, fields = listOf(BoxField("form_type", formType, 0, 0)))
        return BoxNode("root", 0, 0, 0, children = listOf(form, comm))
    }

    @Test
    fun `an AIFF root is classified as AUDIO`() {
        val root = buildAiffFixture("AIFF", null)
        val tmp = File.createTempFile("aiff-summary-category-test", ".aiff")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(10))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(MediaCategory.AUDIO, summary.category)
    }

    @Test
    fun `a classic AIFF tree produces General and Audio sections with PCM format`() {
        val root = buildAiffFixture("AIFF", null)
        val tmp = File.createTempFile("aiff-summary-test", ".aiff")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(200_000))

        val summary = buildMediaSummary(root, tmp)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("AIFF", general.fields.first { it.label == "Format" }.value)
        assertEquals("0:00:02.000", general.fields.first { it.label == "Duration" }.value)

        val audio = summary.sections.first { it.title == "Audio" }
        assertEquals("PCM", audio.fields.first { it.label == "Format" }.value)
        assertEquals("44100 Hz", audio.fields.first { it.label == "Sampling Rate" }.value)
        assertEquals("2", audio.fields.first { it.label == "Channel(s)" }.value)
        assertEquals("16-bit", audio.fields.first { it.label == "Bit Depth" }.value)
    }

    @Test
    fun `an AIFF-C tree with a compression type shows it as the Audio Format`() {
        val root = buildAiffFixture("AIFF-C", "IMA 4:1 ADPCM")
        val tmp = File.createTempFile("aiffc-summary-test", ".aifc")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(200_000))

        val summary = buildMediaSummary(root, tmp)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("AIFF-C", general.fields.first { it.label == "Format" }.value)

        val audio = summary.sections.first { it.title == "Audio" }
        assertEquals("IMA 4:1 ADPCM", audio.fields.first { it.label == "Format" }.value)
    }
}
