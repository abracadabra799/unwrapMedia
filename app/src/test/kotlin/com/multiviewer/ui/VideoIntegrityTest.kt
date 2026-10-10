package com.multiviewer.ui

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import com.multiviewer.util.ProcessManager
import com.multiviewer.cli.render
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.*

class VideoIntegrityTest {
    @Test
    fun `integrated diagnostics reuse explanations without inventing a packet location`() {
        val diagnostic = explainIntegrityLog("[h264] Invalid NAL unit size (100 > 20)")
        assertEquals("UNAVAILABLE", diagnostic.locationStatus)
        assertTrue(diagnostic.explanation.title.contains("NAL"))
        val report = VideoIntegrityReport(IntegrityStatus.ISSUES, IntegrityStatus.CLEAN, 1,
            listOf(diagnostic.message), emptyList(), listOf(IntegrityPacket(0, "0", "0", 40, 20, true)))
        val json = report.toJsonValue().render()
        assertTrue(json.contains("\"diagnostics\""))
        assertTrue(json.contains("\"locationStatus\": \"UNAVAILABLE\""))
        assertTrue(json.contains("NAL"))
    }

    @Test
    fun `unrecognized and oversized error details still have safe explanations`() {
        for (line in listOf("unknown decoder failure", "error while decoding MB 999999999999 4, bytestream -2")) {
            val diagnostic = explainIntegrityLog(line)
            assertEquals(line, diagnostic.message)
            assertEquals("UNAVAILABLE", diagnostic.locationStatus)
            assertTrue(diagnostic.explanation.summary.isNotBlank())
        }
    }

    @Test
    fun `packet mapping preserves absent timestamps and byte positions`() {
        val packet = parseIntegrityPacket("packet|pts_time=N/A|dts_time=-0.083333|size=42|pos=1234|flags=K__", 0)!!
        assertNull(packet.pts)
        assertEquals("-0.083333", packet.dts)
        assertEquals(1234L, packet.offset)
        assertEquals(42L, packet.size)
        assertTrue(packet.keyframe)
        assertNull(parseIntegrityPacket("[h264] decoder error", 1))
    }

    @Test
    fun `decode status does not report a failed or empty scan as clean`() {
        assertEquals(IntegrityStatus.FAILED, decodeIntegrityStatus(1, 0, emptyList()))
        assertEquals(IntegrityStatus.FAILED, decodeIntegrityStatus(0, 0, emptyList()))
        assertEquals(IntegrityStatus.ISSUES, decodeIntegrityStatus(0, 10, listOf("slice type too large")))
        assertEquals(IntegrityStatus.CLEAN, decodeIntegrityStatus(0, 10, emptyList()))
    }

    @Test
    fun `missing executable produces failed status rather than a clean report`() = runBlocking {
        val report = inspectVideoIntegrity(File("missing.mp4"), ffmpeg = "/nonexistent/ffmpeg", ffprobe = "/nonexistent/ffprobe")
        assertEquals(IntegrityStatus.FAILED, report.decodeStatus)
        assertEquals(IntegrityStatus.FAILED, report.packetStatus)
    }

    @Test
    fun `real decoding distinguishes clean media from damaged slice payload`() = runBlocking {
        val directory = Files.createTempDirectory("video-integrity-test").toFile()
        try {
            val original = File(directory, "clean.mp4")
            val generation = ProcessBuilder(FfmpegLocator.ffmpegPath(), "-v", "error", "-f", "lavfi", "-i",
                "testsrc2=size=64x64:rate=10", "-t", "1", "-c:v", "libx264", "-threads", "1", original.path)
                .also(FfmpegLocator::configureEnvironment).start()
            assertEquals(0, generation.waitFor())
            val clean = inspectVideoIntegrity(original)
            assertEquals(IntegrityStatus.CLEAN, clean.decodeStatus, clean.logs.joinToString())
            assertEquals(IntegrityStatus.CLEAN, clean.packetStatus, clean.packetLogs.joinToString())
            assertEquals(10, clean.packets.size)
            val broken = File(directory, "broken.mp4")
            original.copyTo(broken)
            val packet = clean.packets.first()
            RandomAccessFile(broken, "rw").use { bytes ->
                var pos = packet.offset!!
                val end = pos + packet.size!!
                var damaged = false
                while (pos + 5 < end) {
                    bytes.seek(pos)
                    val size = bytes.readInt().toLong() and 0xffffffffL
                    assertTrue(size > 0 && pos + 4 + size <= end)
                    val type = bytes.readUnsignedByte() and 31
                    if (type == 5) {
                        bytes.write(ByteArray((size - 1).toInt()))
                        damaged = true
                        break
                    }
                    pos += 4 + size
                }
                assertTrue(damaged, "Expected an IDR slice in the first packet")
            }
            val damaged = inspectVideoIntegrity(broken)
            assertNotEquals(IntegrityStatus.CLEAN, damaged.decodeStatus)
            assertTrue(damaged.logs.isNotEmpty())
            assertEquals(clean.packets.map { it.offset }, damaged.packets.map { it.offset })
            val checked = com.multiviewer.cli.checkFile(broken, includeCase = true, integrityReport = damaged)
            assertTrue(checked is com.multiviewer.cli.CheckResult.Success)
            assertTrue(checked.analysisCaseJson!!.contains("\"decodeStatus\": \"${damaged.decodeStatus}\""))
            assertFalse(checked.analysisCaseJson!!.contains(directory.absolutePath))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `cancellation terminates an active decoder process`() = runBlocking {
        val before = ProcessManager.activeCount
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(500) {
                integrityProcess(listOf(FfmpegLocator.ffmpegPath(), "-nostdin", "-v", "error", "-re",
                    "-f", "lavfi", "-i", "color=size=16x16:rate=1", "-t", "60", "-f", "null", "-")) {}
            }
        }
        assertEquals(before, ProcessManager.activeCount)
    }
}
