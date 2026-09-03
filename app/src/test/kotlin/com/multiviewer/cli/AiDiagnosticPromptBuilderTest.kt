package com.multiviewer.cli

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.WarningEntry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileOutputStream

class AiDiagnosticPromptBuilderTest {

    @Test
    fun testFindNodePath() {
        val stsz = BoxNode("stsz", offset = 100, headerSize = 8, size = 32)
        val stbl = BoxNode("stbl", offset = 80, headerSize = 8, size = 80, children = listOf(stsz))
        val minf = BoxNode("minf", offset = 70, headerSize = 8, size = 100, children = listOf(stbl))
        val hdlr = BoxNode(
            "hdlr", offset = 50, headerSize = 8, size = 20,
            fields = listOf(BoxField("handler_type", "vide", 58, 4))
        )
        val mdia = BoxNode("mdia", offset = 40, headerSize = 8, size = 140, children = listOf(hdlr, minf))
        val tkhd = BoxNode(
            "tkhd", offset = 20, headerSize = 8, size = 20,
            fields = listOf(BoxField("track_id", "1", 28, 4))
        )
        val trak = BoxNode("trak", offset = 10, headerSize = 8, size = 180, children = listOf(tkhd, mdia))
        val moov = BoxNode("moov", offset = 0, headerSize = 8, size = 200, children = listOf(trak))
        val root = BoxNode("root", offset = 0, headerSize = 0, size = 200, children = listOf(moov))

        val path = AiDiagnosticPromptBuilder.findNodePath(root, stsz)
        assertNotNull(path)
        assertEquals(listOf("root", "moov", "trak(#1, vide)", "mdia", "minf", "stbl", "stsz"), path)
    }

    @Test
    fun testBuildTrackSampleTableContext() {
        val stts = BoxNode("stts", 10, 8, 24, fields = listOf(BoxField("entry_count", "120", 18, 4)))
        val stsz = BoxNode("stsz", 40, 8, 32, fields = listOf(BoxField("sample_count", "0", 48, 4), BoxField("sample_size", "0", 52, 4)))
        val stco = BoxNode("stco", 80, 8, 20, fields = listOf(BoxField("entry_count", "120", 88, 4)))
        val stbl = BoxNode("stbl", 0, 8, 120, children = listOf(stts, stsz, stco))
        val minf = BoxNode("minf", 0, 8, 130, children = listOf(stbl))
        val hdlr = BoxNode("hdlr", 0, 8, 20, fields = listOf(BoxField("handler_type", "vide", 8, 4)))
        val mdhd = BoxNode("mdhd", 0, 8, 24, fields = listOf(BoxField("timescale", "30000", 8, 4), BoxField("duration", "120000", 12, 4)))
        val mdia = BoxNode("mdia", 0, 8, 180, children = listOf(mdhd, hdlr, minf))
        val trak = BoxNode("trak", 0, 8, 200, children = listOf(mdia))

        val context = AiDiagnosticPromptBuilder.buildTrackSampleTableContext(trak)
        assertEquals("vide", context["Handler Type"])
        assertTrue(context["mdhd Duration"]?.contains("4.00 sec") == true)
        assertEquals("120 entries", context["stts (Time-to-Sample)"])
        assertEquals("sample_count: 0, fixed_sample_size: 0", context["stsz (Sample Size)"])
        assertEquals("120 chunks", context["stco (Chunk Offset)"])
    }

    @Test
    fun testReadHexDumpSnippet() {
        val tempFile = File.createTempFile("test-hexdump-", ".bin")
        tempFile.deleteOnExit()
        val dummyBytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x20, 0x73, 0x74, 0x73, 0x7A, // ....stsz
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        FileOutputStream(tempFile).use { it.write(dummyBytes) }

        val snippet = AiDiagnosticPromptBuilder.readHexDumpSnippet(tempFile, 0L, 16)
        assertNotNull(snippet)
        assertTrue(snippet!!.contains("0x00000000:"))
        assertTrue(snippet.contains("73 74 73 7A"))
        assertTrue(snippet.contains("stsz"))

        tempFile.delete()
    }

    @Test
    fun testBuildPromptIncludesRichContext() {
        val tempFile = File.createTempFile("test-rich-prompt-", ".mp4")
        tempFile.deleteOnExit()
        FileOutputStream(tempFile).use { it.write(ByteArray(64)) }

        val stsz = BoxNode(
            "stsz", offset = 16, headerSize = 8, size = 20,
            fields = listOf(
                BoxField("version", "0", 24, 1),
                BoxField("sample_count", "0", 28, 4)
            ),
        )
        val stbl = BoxNode("stbl", offset = 8, headerSize = 8, size = 30, children = listOf(stsz))
        val minf = BoxNode("minf", offset = 8, headerSize = 8, size = 30, children = listOf(stbl))
        val mdia = BoxNode("mdia", offset = 8, headerSize = 8, size = 30, children = listOf(minf))
        val trak = BoxNode("trak", offset = 8, headerSize = 8, size = 30, children = listOf(mdia))
        val moov = BoxNode("moov", offset = 8, headerSize = 8, size = 30, children = listOf(trak))
        val root = BoxNode("root", offset = 0, headerSize = 0, size = 64, children = listOf(moov))

        val warnings = listOf(
            WarningEntry(stsz, "sample_count 0 but size > 20")
        )

        val prompt = AiDiagnosticPromptBuilder.buildPrompt(tempFile, root, warnings)

        // Verify that rich context elements are present
        assertTrue(prompt.contains("분석 대상 미디어 종합 프로필"), "Should contain media profile section")
        assertTrue(prompt.contains("root > moov > trak > mdia > minf > stbl > stsz"), "Should contain breadcrumb path")
        assertTrue(prompt.contains("sample_count = 0"), "Should contain parsed field")
        assertTrue(prompt.contains("원본 바이트 헥사 덤프"), "Should contain hex dump section")
        assertTrue(prompt.contains("근본 원인 및 규격 위반 메커니즘 분석"), "Should contain root cause prompt question")

        tempFile.delete()
    }
}
