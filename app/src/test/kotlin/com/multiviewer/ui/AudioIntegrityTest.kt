package com.multiviewer.ui

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import com.multiviewer.util.ProcessManager
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AudioIntegrityTest {
    @Test
    fun `audio decoder cancellation terminates its child process`() = runBlocking {
        val before = ProcessManager.activeCount
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(500) {
                integrityProcess(listOf(FfmpegLocator.ffmpegPath(), "-nostdin", "-v", "error", "-re",
                    "-f", "lavfi", "-i", "sine=duration=60", "-af", "ashowinfo", "-f", "null", "-")) {}
            }
        }
        assertEquals(before, ProcessManager.activeCount)
    }

    @Test
    fun `probe preserves absolute stream index and unknown duration`() {
        val stream = parseAudioStream("stream|index=3|codec_name=aac|sample_rate=48000|channels=2|duration=N/A")!!
        assertEquals(3, stream.index)
        assertEquals(48000, stream.sampleRate)
        assertEquals(2, stream.channels)
        assertNull(stream.durationSeconds)
        assertNull(parseAudioStream("[error] broken header"))
    }

    @Test
    fun `decoded observations distinguish metadata mismatch from unknown values`() {
        val frame = parseAudioFrame("[Parsed_ashowinfo_0 @ 0x123] [info] n:0 pts:0 pts_time:0 fmt:s16 channels:2 chlayout:stereo rate:48000 nb_samples:4800 checksum:FFFF")!!
        val measurements = AudioMeasurements()
        repeat(10) { measurements.add(frame) }
        val unknown = measurements.report(AudioStreamMetadata(0, "pcm_s16le", 48000, 2, null), 0, emptyList())
        assertEquals(IntegrityStatus.CLEAN, unknown.status)
        assertEquals(48000L, unknown.decodedSamples)
        assertEquals(1.0, unknown.decodedDurationSeconds, 0.00001)
        assertEquals(0.2, unknown.durationToleranceSeconds, 0.00001)
        val mismatch = measurements.report(AudioStreamMetadata(0, "pcm_s16le", 44100, 1, 2.0), 0, emptyList())
        assertEquals(IntegrityStatus.ISSUES, mismatch.status)
        assertEquals(3, mismatch.mismatches.size)
        assertEquals(setOf(48000), mismatch.observedSampleRates)
        assertEquals(setOf(2), mismatch.observedChannels)
    }

    @Test
    fun `padding is tolerated but empty decoding never passes`() {
        val metadata = AudioStreamMetadata(0, "aac", 48000, 2, 1.0)
        val measurements = AudioMeasurements()
        assertEquals(IntegrityStatus.FAILED, measurements.report(metadata, 0, emptyList()).status)
        repeat(48) { measurements.add(AudioFrame(48000, 2, 1024)) }
        assertEquals(IntegrityStatus.CLEAN, measurements.report(metadata, 0, emptyList()).status)
        assertEquals(IntegrityStatus.FAILED, measurements.report(metadata, 1, emptyList()).status)
        assertEquals(IntegrityStatus.ISSUES, measurements.report(metadata, 0, listOf("decoder warning")).status)
    }

    @Test
    fun `missing executable is a failed probe not absent audio`() = runBlocking {
        val report = inspectAudioIntegrity(File("missing.wav"), ffprobe = "/nonexistent/ffprobe")
        assertEquals(IntegrityStatus.FAILED, report.status)
        assertFalse(report.noAudio)
        assertTrue(report.logs.isNotEmpty())
    }

    @Test
    fun `real WAV decoding reports samples and detects truncation`() = runBlocking {
        val directory = Files.createTempDirectory("audio-integrity-").toFile()
        try {
            val clean = File(directory, "clean.wav")
            generate("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1", "-c:a", "pcm_s16le", clean.path)
            val result = inspectAudioIntegrity(clean)
            assertEquals(IntegrityStatus.CLEAN, result.status, result.toString())
            assertEquals(48000L, result.streams.single().decodedSamples)
            assertEquals(setOf(48000), result.streams.single().observedSampleRates)
            val broken = File(directory, "truncated.wav")
            clean.copyTo(broken)
            RandomAccessFile(broken, "rw").use { it.setLength(it.length() / 2) }
            val damaged = inspectAudioIntegrity(broken)
            assertNotEquals(IntegrityStatus.CLEAN, damaged.status, damaged.toString())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `all tracks keep their own rates and video without audio is not applicable`() = runBlocking {
        val directory = Files.createTempDirectory("audio-tracks-").toFile()
        try {
            val multi = File(directory, "two.mkv")
            generate("-f", "lavfi", "-i", "sine=sample_rate=44100:duration=0.5",
                "-f", "lavfi", "-i", "sine=sample_rate=48000:duration=0.5",
                "-map", "0:a", "-map", "1:a", "-c:a", "pcm_s16le", multi.path)
            val result = inspectAudioIntegrity(multi)
            assertEquals(IntegrityStatus.CLEAN, result.status, result.toString())
            assertEquals(listOf(0, 1), result.streams.map { it.stream.index })
            assertEquals(listOf(22050L, 24000L), result.streams.map { it.decodedSamples })
            val silent = File(directory, "silent.mp4")
            generate("-f", "lavfi", "-i", "color=size=16x16:duration=0.2", "-c:v", "mpeg4", silent.path)
            val absent = inspectAudioIntegrity(silent)
            assertTrue(absent.noAudio)
            assertTrue(absent.streams.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `audio file extension without audio streams is a failure`() = runBlocking {
        val directory = Files.createTempDirectory("audio-no-stream-").toFile()
        try {
            val video = File(directory, "silent.mp4")
            generate("-f", "lavfi", "-i", "color=size=16x16:duration=0.2", "-c:v", "mpeg4", video.path)
            val disguisedAudio = File(directory, "silent.m4a")
            video.copyTo(disguisedAudio)
            val report = inspectAudioIntegrity(disguisedAudio)
            assertEquals(IntegrityStatus.FAILED, report.status)
            assertFalse(report.noAudio)
            assertTrue(report.logs.any { it.contains("No audio streams") })
        } finally { directory.deleteRecursively() }
    }

    private fun generate(vararg args: String) {
        val process = ProcessBuilder(listOf(FfmpegLocator.ffmpegPath(), "-nostdin", "-v", "error") + args)
            .redirectErrorStream(true).also(FfmpegLocator::configureEnvironment).start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Fixture generation timed out")
            assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
        } finally { process.destroyForcibly() }
    }
}
