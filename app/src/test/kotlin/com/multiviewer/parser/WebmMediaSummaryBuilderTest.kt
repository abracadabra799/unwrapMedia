package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class WebmMediaSummaryBuilderTest {
    private fun webmLeaf(type: String, value: String): BoxNode =
        BoxNode(type, 0, 0, 0, fields = listOf(BoxField("value", value, 0, 0)))

    private fun buildWebmFixture(includeAudioTrack: Boolean): BoxNode {
        val info = BoxNode(
            "Info", 0, 0, 0,
            children = listOf(
                webmLeaf("TimecodeScale", "1000000"),
                webmLeaf("Duration", "20000.0"),
            ),
        )
        val videoTrackEntry = BoxNode(
            "TrackEntry", 0, 0, 0,
            children = listOf(
                webmLeaf("TrackType", "1"),
                webmLeaf("CodecID", "V_VP9"),
                BoxNode("Video", 0, 0, 0, children = listOf(webmLeaf("PixelWidth", "1920"), webmLeaf("PixelHeight", "1080"))),
            ),
        )
        val trackEntries = mutableListOf(videoTrackEntry)
        if (includeAudioTrack) {
            trackEntries.add(
                BoxNode(
                    "TrackEntry", 0, 0, 0,
                    children = listOf(
                        webmLeaf("TrackType", "2"),
                        webmLeaf("CodecID", "A_OPUS"),
                        BoxNode("Audio", 0, 0, 0, children = listOf(webmLeaf("SamplingFrequency", "48000.0"), webmLeaf("Channels", "2"))),
                    ),
                ),
            )
        }
        val tracks = BoxNode("Tracks", 0, 0, 0, children = trackEntries)
        val segment = BoxNode("Segment", 0, 0, 0, children = listOf(info, tracks))
        val ebml = BoxNode("EBML", 0, 0, 0)
        return BoxNode("root", 0, 0, 0, children = listOf(ebml, segment))
    }

    @Test
    fun `an EBML root is classified as VIDEO`() {
        val root = buildWebmFixture(includeAudioTrack = false)
        val tmp = File.createTempFile("webm-summary-category-test", ".webm")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(10))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(MediaCategory.VIDEO, summary.category)
    }

    @Test
    fun `a full WebM tree produces General, Track List, Video, and Audio sections with correct values`() {
        val root = buildWebmFixture(includeAudioTrack = true)
        val tmp = File.createTempFile("webm-summary-test", ".webm")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(1_250_000))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(4, summary.sections.size)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("0:00:20.000", general.fields.first { it.label == "Duration" }.value)
        assertEquals("WebM", general.fields.first { it.label == "Format" }.value)
        assertEquals("500.0 Kbps", general.fields.first { it.label == "Overall Bit Rate" }.value)

        val trackList = summary.sections.first { it.title == "Track List" }
        assertEquals("1", trackList.fields.first { it.label == "Video Tracks" }.value)
        assertEquals("1", trackList.fields.first { it.label == "Audio Tracks" }.value)

        val videoDetail = summary.sections.first { it.title == "Video" }
        assertEquals("VP9", videoDetail.fields.first { it.label == "Format" }.value)
        assertEquals("1920", videoDetail.fields.first { it.label == "Width" }.value)
        assertEquals("1080", videoDetail.fields.first { it.label == "Height" }.value)

        val audioDetail = summary.sections.first { it.title == "Audio" }
        assertEquals("Opus", audioDetail.fields.first { it.label == "Format" }.value)
        assertEquals("48000.0 Hz", audioDetail.fields.first { it.label == "Sampling Rate" }.value)
        assertEquals("2", audioDetail.fields.first { it.label == "Channel(s)" }.value)
    }

    @Test
    fun `a WebM tree with no audio track omits the Audio section`() {
        val root = buildWebmFixture(includeAudioTrack = false)
        val tmp = File.createTempFile("webm-summary-video-only-test", ".webm")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(1_250_000))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(3, summary.sections.size)
        assertEquals(null, summary.sections.find { it.title == "Audio" })
    }
}
