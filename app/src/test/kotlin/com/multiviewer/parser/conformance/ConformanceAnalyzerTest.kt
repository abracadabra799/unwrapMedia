package com.multiviewer.parser.conformance

import com.multiviewer.parser.byteReaderOf
import com.multiviewer.parser.integrity.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConformanceAnalyzerTest {
    @Test
    fun `valid PNG retains checked evidence and absent conditional rule stays unchecked`() {
        val items = byteReaderOf(pngBytes()).use { PngIntegrity.check(it).items }
        val report = analyzeConformance("PNG", items)
        assertEquals(ConformanceStatus.NOT_CHECKED, report.overall)
        assertEquals(5, report.findings.count { it.status == ConformanceStatus.PASS })
        assertEquals(1, report.findings.count { it.status == ConformanceStatus.NOT_CHECKED })
        assertEquals(ConformanceStatus.NOT_CHECKED, report.findings.single { it.rule.id == "png.truncated" }.status)
        assertEquals(listOf(8L..32L), report.findings.single { it.rule.id == "png.ihdr" }.ranges)
        assertEquals(listOf(70L..81L), report.findings.single { it.rule.id == "png.iend" }.ranges)
    }

    @Test
    fun `truncated PNG remains failure with passing checks and exact available byte extent`() {
        val full = pngBytes()
        val items = byteReaderOf(full.copyOf(full.size - 8)).use { PngIntegrity.check(it).items }
        val report = analyzeConformance("PNG", items)
        assertEquals(ConformanceStatus.FAIL, report.overall)
        assertEquals(5, report.findings.count { it.status != ConformanceStatus.NOT_CHECKED })
        assertEquals(1, report.findings.count { it.status == ConformanceStatus.NOT_CHECKED })
        assertEquals(listOf(70L..73L), report.findings.single { it.rule.id == "png.truncated" }.ranges)
    }

    @Test
    fun `empty unsupported and all skipped inspections never pass`() {
        assertEquals(ConformanceStatus.NOT_CHECKED, analyzeConformance("UNKNOWN", emptyList()).overall)
        assertEquals(ConformanceStatus.NOT_CHECKED, analyzeConformance("UNKNOWN", listOf(item("custom", CheckStatus.PASS))).overall)
        val skipped = analyzeConformance("PNG", listOf(item("png.ihdr", CheckStatus.SKIP)))
        assertEquals(6, skipped.findings.count { it.status == ConformanceStatus.NOT_CHECKED })
        assertEquals(ConformanceStatus.NOT_CHECKED, skipped.overall)
        assertEquals(CheckStatus.SKIP, ImageStructureReport("PNG", emptyList()).overall)
        assertEquals(CheckStatus.SKIP, ImageStructureReport("PNG", listOf(item("png.ihdr", CheckStatus.SKIP))).overall)
        assertEquals(CheckStatus.PASS, ImageStructureReport("PNG", listOf(item("png.ihdr", CheckStatus.INFO))).overall)
    }

    @Test
    fun `duplicate rules preserve all observations and select worst status`() {
        val report = analyzeConformance("PNG", listOf(
            item("png.crc", CheckStatus.WARN, "first", 10, 2),
            item("png.crc", CheckStatus.PASS, "second", 20, 3),
            item("png.crc", CheckStatus.FAIL, "third", 30, 1),
        ))
        val finding = report.findings.single { it.rule.id == "png.crc" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertEquals(listOf("first", "second", "third"), finding.details)
        assertEquals(listOf(10L..11L, 20L..22L, 30L..30L), finding.ranges)
        assertEquals(ConformanceStatus.FAIL, report.overall)
    }

    @Test
    fun `unknown rule remains visible as structural evidence and INFO maps to pass`() {
        val report = analyzeConformance("PNG", listOf(item("new.check", CheckStatus.INFO, "recognized extra", 2, 1)))
        val extra = report.findings.single { it.rule.id == "new.check" }
        assertEquals("unwrapMedia structural check", extra.rule.source)
        assertEquals(null, extra.rule.reference)
        assertEquals(ConformanceStatus.PASS, extra.status)
        assertEquals(listOf("recognized extra"), extra.details)
        assertEquals(listOf(2L..2L), extra.ranges)
        assertEquals(7, report.findings.size)
    }

    @Test
    fun `parser warnings remain separate evidence and cannot supply a spec pass`() {
        val report = analyzeConformance("UNKNOWN", listOf(
            item("parser.warnings", CheckStatus.PASS, "No parser warnings"),
            item("parser.warning", CheckStatus.WARN, "truncated node", 40, 2),
        ))
        assertTrue(report.findings.isEmpty())
        assertEquals(ConformanceStatus.NOT_CHECKED, report.overall)
        assertTrue(report.limitations.any { it.contains("truncated node") && it.contains("40") })
        assertTrue(report.limitations.any { it.contains("Codec bitstream syntax") })
    }

    @Test
    fun `inspection error prevents pass without discarding completed checks`() {
        val items = listOf("png.truncated", "png.crc", "png.ihdr", "png.idat", "png.iend", "png.trailing")
            .map { item(it, CheckStatus.PASS) }
        assertEquals(ConformanceStatus.PASS, analyzeConformance("PNG", items).overall)
        val report = analyzeConformance("PNG", items, "read interrupted")
        assertEquals(ConformanceStatus.NOT_CHECKED, report.overall)
        assertEquals(6, report.findings.count { it.status == ConformanceStatus.PASS })
        assertTrue(report.limitations.any { it.contains("read interrupted") })
        assertTrue(report.limitations.any { it.contains("Codec bitstream syntax") })
    }

    @Test
    fun `invalid or overflowing byte extents do not create selectable ranges`() {
        val observations = listOf(
            item("new.check", CheckStatus.PASS, "no offset", null, 2),
            item("new.check", CheckStatus.PASS, "zero", 1, 0),
            item("new.check", CheckStatus.PASS, "negative", -1, 2),
            item("new.check", CheckStatus.PASS, "overflow", Long.MAX_VALUE, 2),
            item("new.check", CheckStatus.PASS, "last byte", Long.MAX_VALUE, 1),
        )
        assertEquals(listOf(Long.MAX_VALUE..Long.MAX_VALUE),
            analyzeConformance("PNG", observations).findings.single { it.rule.id == "new.check" }.ranges)
    }

    private fun item(id: String, status: CheckStatus, detail: String = "observed", offset: Long? = null, length: Long? = null) =
        IntegrityCheckItem(id, "검사 / Check", status, detail, offset, length)
}
