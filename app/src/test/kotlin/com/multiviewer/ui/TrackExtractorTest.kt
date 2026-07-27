package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackExtractorTest {
    private fun ffprobeStreamTypes(file: File): List<String> {
        val process = ProcessBuilder(
            "ffprobe", "-v", "error", "-show_entries", "stream=codec_type",
            "-of", "default=noprint_wrappers=1:nokey=1", file.absolutePath,
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        return lines
    }

    private fun generateVideoWithAudio(): File {
        val file = File.createTempFile("track-extractor-av-test-", ".mp4")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "testsrc=duration=1:size=64x48:rate=5",
            "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        return file
    }

    @Test
    fun `extractVideoTrack produces a file with only a video stream, no audio`() {
        val source = generateVideoWithAudio()
        val destination = File.createTempFile("track-extractor-video-out-", ".mp4")
        destination.deleteOnExit()

        val success = extractVideoTrack(source, destination)

        assertTrue(success)
        assertEquals(listOf("video"), ffprobeStreamTypes(destination))
        source.delete()
        destination.delete()
    }

    @Test
    fun `extractAudioTrack produces a playable M4A with only an audio stream, no video`() {
        val source = generateVideoWithAudio()
        val destination = File.createTempFile("track-extractor-audio-out-", ".m4a")
        destination.deleteOnExit()

        val success = extractAudioTrack(source, destination)

        assertTrue(success)
        assertEquals(listOf("audio"), ffprobeStreamTypes(destination))
        source.delete()
        destination.delete()
    }

    @Test
    fun `extractAudioTrack pulls in every audio track, not just the first`() {
        val video = File.createTempFile("track-extractor-multi-audio-test-", ".mp4")
        video.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "testsrc=duration=1:size=64x48:rate=5",
            "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-f", "lavfi", "-i", "sine=duration=1:frequency=880",
            "-map", "0:v", "-map", "1:a", "-map", "2:a",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val destination = File.createTempFile("track-extractor-multi-audio-out-", ".m4a")
        destination.deleteOnExit()

        val success = extractAudioTrack(video, destination)

        assertTrue(success)
        assertEquals(listOf("audio", "audio"), ffprobeStreamTypes(destination))
        video.delete()
        destination.delete()
    }

    @Test
    fun `extractVideoTrack returns false for a source file with no video stream`() {
        val audioOnly = File.createTempFile("track-extractor-audio-only-test-", ".mp4")
        audioOnly.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "sine=duration=1:frequency=440",
            "-c:a", "aac", audioOnly.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val destination = File.createTempFile("track-extractor-no-video-out-", ".mp4")
        destination.deleteOnExit()

        val success = extractVideoTrack(audioOnly, destination)

        assertTrue(!success)
        audioOnly.delete()
        destination.delete()
    }
}
