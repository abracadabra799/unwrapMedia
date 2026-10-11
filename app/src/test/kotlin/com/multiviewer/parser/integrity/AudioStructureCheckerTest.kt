package com.multiviewer.parser.integrity

import com.multiviewer.parser.BoxNode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioStructureCheckerTest {
    @Test
    fun unknownAudioBytesAreNotReportedClean() {
        val file = tempAudio(byteArrayOf(1, 2, 3, 4, 5))
        val report = AudioStructureChecker.check(file, BoxNode("root", 0, 0, file.length()))

        assertEquals("UNKNOWN", report.format)
        assertTrue(report.items.any { it.status == CheckStatus.SKIP })
        assertEquals(CheckStatus.SKIP, report.overall)
    }

    @Test
    fun recognizedButIncompleteWaveReportsStructuralFailure() {
        val file = tempAudio("RIFF\u0004\u0000\u0000\u0000WAVE".toByteArray(Charsets.ISO_8859_1))
        val report = AudioStructureChecker.check(file, BoxNode("root", 0, 0, file.length()))

        assertEquals("WAVE", report.format)
        assertEquals(CheckStatus.FAIL, report.overall)
        assertTrue(report.items.any { it.id == "wave.fmt.missing" && it.status == CheckStatus.FAIL })
    }

    private fun tempAudio(bytes: ByteArray): File = File.createTempFile("audio-structure-", ".bin").also {
        it.deleteOnExit()
        it.writeBytes(bytes)
    }
}
