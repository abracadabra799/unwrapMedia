package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class OggMediaSummaryBuilderTest {
    private fun buildVorbisFixture(): BoxNode {
        val header = BoxNode(
            "OggVorbisIdentificationHeader", 0, 0, 0,
            fields = listOf(
                BoxField("channels", "2", 0, 0),
                BoxField("sample_rate", "44100", 0, 0),
                BoxField("bitrate_nominal", "112000", 0, 0),
            ),
        )
        val pages = BoxNode(
            "OggPages", 0, 0, 0,
            fields = listOf(BoxField("final_granule_position", "88200", 0, 0)),
        )
        return BoxNode("root", 0, 0, 0, children = listOf(header, pages))
    }

    private fun buildOpusFixture(): BoxNode {
        val header = BoxNode(
            "OggOpusIdentificationHeader", 0, 0, 0,
            fields = listOf(
                BoxField("channel_count", "2", 0, 0),
                BoxField("pre_skip", "312", 0, 0),
                BoxField("input_sample_rate", "44100", 0, 0),
            ),
        )
        val pages = BoxNode(
            "OggPages", 0, 0, 0,
            fields = listOf(BoxField("final_granule_position", "96312", 0, 0)),
        )
        return BoxNode("root", 0, 0, 0, children = listOf(header, pages))
    }

    @Test
    fun `an Ogg root is classified as AUDIO`() {
        val root = buildVorbisFixture()
        val tmp = File.createTempFile("ogg-summary-category-test", ".ogg")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(10))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(MediaCategory.AUDIO, summary.category)
    }

    @Test
    fun `a Vorbis tree produces General and Audio sections with correct values`() {
        val root = buildVorbisFixture()
        val tmp = File.createTempFile("ogg-vorbis-summary-test", ".ogg")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(200_000))

        val summary = buildMediaSummary(root, tmp)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("Vorbis", general.fields.first { it.label == "Format" }.value)
        assertEquals("0:00:02.000", general.fields.first { it.label == "Duration" }.value)

        val audio = summary.sections.first { it.title == "Audio" }
        assertEquals("44100 Hz", audio.fields.first { it.label == "Sampling Rate" }.value)
        assertEquals("2", audio.fields.first { it.label == "Channel(s)" }.value)
        assertEquals("112.0 Kbps", audio.fields.first { it.label == "Bit Rate" }.value)
    }

    @Test
    fun `an Opus tree produces General and Audio sections using the fixed 48kHz granule rate`() {
        val root = buildOpusFixture()
        val tmp = File.createTempFile("ogg-opus-summary-test", ".opus")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(200_000))

        val summary = buildMediaSummary(root, tmp)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("Opus", general.fields.first { it.label == "Format" }.value)
        // (96312 - 312) / 48000 = 2.0 seconds -- NOT divided by input_sample_rate (44100), which
        // would give a wrong answer. This is the case most likely to regress silently.
        assertEquals("0:00:02.000", general.fields.first { it.label == "Duration" }.value)

        val audio = summary.sections.first { it.title == "Audio" }
        assertEquals("48000 Hz", audio.fields.first { it.label == "Sampling Rate" }.value)
        assertEquals("2", audio.fields.first { it.label == "Channel(s)" }.value)
    }
}
