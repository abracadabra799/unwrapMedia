package com.multiviewer.ui

import com.multiviewer.util.ProcessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StressAndRobustnessTest {

    @Test
    fun testProcessManagerConcurrentStress() {
        val threads = (1..30).map { id ->
            Thread {
                val pb = ProcessBuilder("echo", "test-$id")
                val p = ProcessManager.register(pb.start())
                p.waitFor()
                ProcessManager.unregister(p)
                ProcessManager.terminate(p)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }
    }

    @Test
    fun testCorruptedDecoderLogStress() {
        // Feed various malformed or pathological decoder error strings to scanner
        val pathologicalLogs = listOf(
            "",
            "   ",
            "error while decoding MB -999999999 999999999, bytestream -999999999",
            "cabac decode error at -1 -1",
            "concealing 999999 DC, 999999 AC, 999999 MV errors in ? frame",
            "error while decoding MB 0 0, bytestream 0",
            "unknown log with no pattern match",
            "a".repeat(100000), // Huge line stress
        )

        for (log in pathologicalLogs) {
            val res = BitstreamCorruptionScanner.interpretDecoderError(log)
            assertNotNull(res)
            assertTrue(res.title.isNotEmpty())
        }
    }

    @Test
    fun testHexSearchStress() {
        val temp = File.createTempFile("stress_hex", ".bin")
        try {
            val bytes = ByteArray(5 * 1024 * 1024)
            for (i in bytes.indices) bytes[i] = (i % 256).toByte()
            bytes[1000] = 0xDE.toByte()
            bytes[1001] = 0xAD.toByte()
            bytes[1002] = 0xBE.toByte()
            bytes[1003] = 0xEF.toByte()
            temp.writeBytes(bytes)

            RandomAccessFile(temp, "r").use { raf ->
                val pattern = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
                val hits = searchHex(raf, pattern, maxResults = 10)
                assertEquals(1, hits.size)
                assertEquals(1000L, hits[0])
            }
        } finally {
            temp.delete()
        }
    }

    @Test
    fun testAvSyncAnalyzerStressWithShortFile() {
        val sample = File("sample_clean_3s.mp4")
        if (sample.exists()) {
            runBlocking {
                val deferredList = (1..3).map {
                    async(Dispatchers.IO) {
                        AvSyncAnalyzer.analyze(sample)
                    }
                }
                deferredList.forEach {
                    val report = it.await()
                    assertNotNull(report)
                }
            }
        }
    }
}
