package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.jpegBytes
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionPhotoIntegrationTest {
    @Test
    fun severityMapping() {
        assertEquals(CheckStatus.PASS, SefIntegritySeverity.PASS.toCheckStatus())
        assertEquals(CheckStatus.INFO, SefIntegritySeverity.INFO.toCheckStatus())
        assertEquals(CheckStatus.WARN, SefIntegritySeverity.WARNING.toCheckStatus())
        assertEquals(CheckStatus.FAIL, SefIntegritySeverity.CRITICAL.toCheckStatus())
        assertEquals(CheckStatus.SKIP, SefIntegritySeverity.SKIPPED.toCheckStatus())
    }

    @Test
    fun plainJpegIsNotMotionPhoto() {
        val f = File.createTempFile("plain", ".jpg").apply { deleteOnExit(); writeBytes(jpegBytes()) }
        assertFalse(hasMotionPhotoData(parseFile(f)))
    }

    @Test
    fun mpvdAndEmbeddedVideoDetected() {
        for (type in listOf("mpvd", "EmbeddedVideoData")) {
            val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 10, children = listOf(BoxNode(type, 0, 8, 10)))
            assertTrue(hasMotionPhotoData(root), type)
        }
    }

    private fun rootWith(sefd: BoxNode) = BoxNode(type = "root", offset = 0, headerSize = 0, size = 100, children = listOf(sefd))

    @Test
    fun sefdWithMotionPhotoDataIsDetected() {
        val sefd = BoxNode("sefd", 0, 0, 100, children = listOf(BoxNode("MotionPhoto_Data", 0, 0, 12)))
        assertTrue(hasMotionPhotoData(rootWith(sefd)))
    }

    @Test
    fun plainSefTrailerIsNotMotionPhoto() {
        assertFalse(hasMotionPhotoData(rootWith(BoxNode("sefd", 0, 0, 100))))
    }

    @Test
    fun brokenSefTrailerWithWarningsIsDetected() {
        assertTrue(hasMotionPhotoData(rootWith(BoxNode("sefd", 0, 0, 100, warnings = listOf("bad SEFT magic")))))
    }

    private fun report(formats: List<MotionPhotoFormat>, severity: SefIntegritySeverity) = MotionPhotoIntegrityReport(
        detectedFormats = formats, sefSection = null, googleXmpChecks = emptyList(),
        appleMpvdChecks = emptyList(), decodeChecks = emptyList(), overallSeverity = severity,
    )

    @Test
    fun verdictIsSkipWhenNoFormatConfirmed() {
        assertEquals(CheckStatus.SKIP, report(emptyList(), SefIntegritySeverity.PASS).verdictStatus())
        assertEquals(CheckStatus.PASS, report(listOf(MotionPhotoFormat.GOOGLE_XMP), SefIntegritySeverity.PASS).verdictStatus())
        assertEquals(CheckStatus.FAIL, report(listOf(MotionPhotoFormat.SAMSUNG_SEF), SefIntegritySeverity.CRITICAL).verdictStatus())
    }
}
