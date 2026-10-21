package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoAudioTimelineDataTest {
    @Test
    fun `arrow navigation follows presentation order and stops at the ends`() {
        val frames = listOf(
            FrameInfo(0, 'I', 100, 0.0),
            FrameInfo(2, 'B', 100, 0.04),
            FrameInfo(1, 'P', 100, 0.08),
        )
        assertEquals(2, stepTimelineFrame(frames, frames[0], 1)?.index)
        assertEquals(1, stepTimelineFrame(frames, frames[1], 1)?.index)
        assertEquals(2, stepTimelineFrame(frames, frames[2], -1)?.index)
        assertEquals(1, stepTimelineFrame(frames, frames[2], 1)?.index)
        assertEquals(0, stepTimelineFrame(frames, null, 1)?.index)
        assertNull(stepTimelineFrame(emptyList(), null, 1))
    }

    @Test
    fun `AAC priming packet keeps negative PTS from real ffprobe compact output`() {
        val packet = parseAudioTimelinePacket("pts_time=-0.021333|duration_time=0.021333|size=212|pos=3338|", 0)
        assertNotNull(packet)
        assertEquals(-0.021333, packet.ptsSeconds)
        assertEquals(0.021333, packet.durationSeconds)
        assertEquals(3338L, packet.byteOffset)
    }

    @Test
    fun `missing PTS is not shown as an audio packet`() {
        assertNull(parseAudioTimelinePacket("pts_time=N/A|duration_time=0.021333|size=212|pos=3338", 0))
    }

    @Test
    fun `shared axis includes priming packet but waveform starts at stream start time`() {
        val frames = listOf(FrameInfo(0, 'I', 100, 0.0), FrameInfo(1, 'P', 100, 2.0))
        val info = AudioFileInfo(48_000, 1, 2.0, startTimeSeconds = 0.0)
        val packets = listOf(AudioTimelinePacket(0, -0.021333, 0.021333, 212, 3338L))
        val range = audioVideoTimelineRange(frames, info, packets)
        assertEquals(-0.021333, range.startSeconds)
        assertTrue(range.endSeconds >= 2.0)
        assertEquals(0.0, info.startTimeSeconds)
    }

    @Test
    fun `nearest packet uses presentation time and rejects distant partial coverage`() {
        val packets = listOf(
            AudioTimelinePacket(0, 0.0, 0.02, 100, 1L),
            AudioTimelinePacket(1, 0.02, 0.02, 100, 2L),
            AudioTimelinePacket(2, 0.04, 0.02, 100, 3L),
        )
        assertEquals(1, nearestAudioTimelinePacket(packets, 0.033)?.index)
        assertNull(nearestAudioTimelinePacket(packets, 8.0))
    }

    @Test
    fun `packet beginning exactly at frame PTS wins over previous packet ending there`() {
        val packets = listOf(
            AudioTimelinePacket(0, -0.021333, 0.021333, 212, 3338L),
            AudioTimelinePacket(1, 0.0, 0.021333, 280, 4176L),
        )
        assertEquals(1, nearestAudioTimelinePacket(packets, 0.0)?.index)
    }

    @Test
    fun `real MP4 audio packet probe is bounded and marks partial result`() {
        val file = File("src/test/resources/content-check-codecs/avc-aac.mp4")
        val result = probeAudioTimelinePackets(file, maxPackets = 3)
        assertNotNull(result)
        assertEquals(3, result.packets.size)
        assertTrue(result.truncated)
        assertEquals(-0.021333, result.packets.first().ptsSeconds)
    }

    @Test
    fun `existing waveform decoder accepts the audio stream inside a video file`() {
        val file = File("src/test/resources/content-check-codecs/avc-aac.mp4")
        val info = probeAudioFormat(file)
        assertNotNull(info)
        assertEquals(0.0, info.startTimeSeconds)
        val peaks = computeWaveformPeaks(file, info, bucketCount = 64)
        assertNotNull(peaks)
        assertEquals(64, peaks.bucketCount)
    }

    @Test
    fun `screen coordinate maps through negative axis origin and zoom window`() {
        val range = TimelineTimeRange(-0.02, 9.98)
        val view = AudioViewWindow(2.0, 4.0)
        assertEquals(3.98, timelineTimeAtFraction(0.5f, range, view), 0.000001)
        assertEquals(0.5f, timelineFraction(3.98, range, view), 0.000001f)
    }

    @Test
    fun `stepTimelineCursor continues through trailing audio packets when audio is longer than video and returns cleanly`() {
        val frames = listOf(
            FrameInfo(0, 'I', 100, 0.0),
            FrameInfo(1, 'P', 100, 0.05),
            FrameInfo(2, 'P', 100, 0.10),
        )
        val packets = listOf(
            AudioTimelinePacket(0, 0.0, 0.04, 100, 1L),
            AudioTimelinePacket(1, 0.04, 0.04, 100, 2L),
            AudioTimelinePacket(2, 0.08, 0.04, 100, 3L),
            AudioTimelinePacket(3, 0.12, 0.04, 100, 4L),
            AudioTimelinePacket(4, 0.16, 0.04, 100, 5L),
        )

        // At last video frame (0.10s), stepping right moves onto trailing audio packet #3 (0.12s)
        val step1 = stepTimelineCursor(frames, packets, selectedFrame = frames[2], chosenPacket = null, delta = 1)
        assertNotNull(step1)
        assertEquals(3, step1.packet?.index)
        assertEquals(0.12, step1.cursorPtsSeconds, 1e-6)
        assertEquals(2, step1.frame?.index)

        // Stepping right again reaches the final audio packet #4 (0.16s)
        val step2 = stepTimelineCursor(frames, packets, selectedFrame = step1.frame, chosenPacket = step1.packet, delta = 1)
        assertNotNull(step2)
        assertEquals(4, step2.packet?.index)
        assertEquals(0.16, step2.cursorPtsSeconds, 1e-6)

        // Stepping left from packet #4 goes back to packet #3 (0.12s)
        val stepBack1 = stepTimelineCursor(frames, packets, selectedFrame = step2.frame, chosenPacket = step2.packet, delta = -1)
        assertNotNull(stepBack1)
        assertEquals(3, stepBack1.packet?.index)
        assertEquals(0.12, stepBack1.cursorPtsSeconds, 1e-6)

        // Stepping left from packet #3 (first tail packet) transitions back to video frame #2 (0.10s)
        val stepBackToVideo = stepTimelineCursor(frames, packets, selectedFrame = stepBack1.frame, chosenPacket = stepBack1.packet, delta = -1)
        assertNotNull(stepBackToVideo)
        assertNull(stepBackToVideo.packet)
        assertEquals(2, stepBackToVideo.frame?.index)
        assertEquals(0.10, stepBackToVideo.cursorPtsSeconds, 1e-6)
    }
}
