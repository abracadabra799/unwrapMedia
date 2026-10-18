package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoCodec
import com.multiviewer.parser.VideoSample
import com.multiviewer.parser.VideoSampleTable
import com.multiviewer.parser.buildVideoSampleTable
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VideoSampleTableDurationTest {
    private fun table(timescale: Long, vararg decodeTimes: Long) = VideoSampleTable(
        codec = VideoCodec.H264,
        nalLengthSize = 4,
        timescale = timescale,
        samples = decodeTimes.mapIndexed { i, t -> VideoSample(i, 0, 1, t, 0, i == 0) },
        warnings = emptyList(),
    )

    @Test
    fun `duration is last decode time plus one frame, independent of the timescale`() {
        // 3 frames at 30 fps: 0.1 s, at both 90 kHz and 600 Hz (the old +1000-tick formula gave 0.078 s and 2.0 s).
        assertEquals(0.1, videoSampleTableDurationSec(table(90_000, 0, 3_000, 6_000)), 1e-9)
        assertEquals(0.1, videoSampleTableDurationSec(table(600, 0, 20, 40)), 1e-9)
        assertEquals(0.0, videoSampleTableDurationSec(table(600, 0)), 1e-9)
        assertEquals(0.0, videoSampleTableDurationSec(table(600)), 1e-9)
    }

    // ffprobe (independent): video-frame/default.mp4 = 60 frames, time_base 1/15360, duration 2.000000 s;
    // avc-conformance/default-avc1.mp4 = 8 frames, time_base 1/16384, duration 2.000000 s.
    @Test
    fun `real ffmpeg fixtures match ffprobe stream duration`() {
        for (path in listOf("video-frame/default.mp4", "avc-conformance/default-avc1.mp4")) {
            val file = File(assertNotNull(javaClass.classLoader.getResource(path), path).toURI())
            val root = parseFile(file)
            val table = assertNotNull(ByteReader.open(file).use { buildVideoSampleTable(it, root) }, path)
            assertEquals(2.0, videoSampleTableDurationSec(table), 1e-6, path)
        }
    }
}
