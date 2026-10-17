package com.multiviewer.parser.conformance

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.parseFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Av1BitstreamAnalyzerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/av1-conformance/$name")!!.toURI())

    @Test
    fun validAv01ReportsCheckedSamplesAndFramingPass() = runBlocking {
        val file = fixture("default-av01.mp4")
        val report = analyzeAv1Bitstream(file, parseFile(file)) { _, _ -> }
        assertTrue(report.complete)
        assertEquals(8, report.checkedSamples)
        assertEquals(8, report.indexedSamples)
        assertTrue(report.findings.any { it.rule.id == "av1.obu.framing" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "av1.obu.forbidden_bit" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "av1.obu.reserved_bit" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "av1.obu.extension_reserved_bits" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "av1.config.sequence_header" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun corruptedForbiddenBitIsObserved() = runBlocking {
        val file = fixture("forbidden-bit.mp4")
        val report = analyzeAv1Bitstream(file, parseFile(file)) { _, _ -> }
        val finding = report.findings.first { it.rule.id == "av1.obu.forbidden_bit" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertEquals(48L..48L, finding.ranges.first())
        assertTrue(finding.details.first().contains("observed=1"))
    }

    @Test
    fun corruptedReservedBitIsObserved() = runBlocking {
        val file = fixture("reserved-bit.mp4")
        val report = analyzeAv1Bitstream(file, parseFile(file)) { _, _ -> }
        val finding = report.findings.first { it.rule.id == "av1.obu.reserved_bit" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertEquals(48L..48L, finding.ranges.first())
        assertTrue(finding.details.first().contains("observed=1"))
    }

    @Test
    fun extensionReservedBitsAreObserved() = runBlocking {
        val file = fixture("extension-reserved-bit.mp4")
        val report = analyzeAv1Bitstream(file, parseFile(file)) { _, _ -> }
        val finding = report.findings.first { it.rule.id == "av1.obu.extension_reserved_bits" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertEquals(48L..49L, finding.ranges.first())
        assertTrue(finding.details.first().contains("observed=7"))
    }

    @Test
    fun truncatedObuLeavesFramingUnchecked() = runBlocking {
        val file = fixture("truncated-obu.mp4")
        val report = analyzeAv1Bitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        assertFalse(report.findings.any { it.rule.id == "av1.obu.framing" && it.status == ConformanceStatus.PASS })
        val failFinding = report.findings.first { it.rule.id == "av1.obu.framing" && it.status == ConformanceStatus.FAIL }
        assertEquals(61L..61L, failFinding.ranges.first())
    }

    @Test
    fun malformedAv1cReportsUnsupported() = runBlocking {
        val file = fixture("malformed-av1c.mp4")
        val report = analyzeAv1Bitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        assertTrue(report.limitations.any { it.contains("av1C marker bit or version is invalid") })
    }

    @Test
    fun repeatedHeaderFailuresAreBoundedButCounted() = runBlocking {
        val file = fixture("many-forbidden-headers.mp4")
        val report = analyzeAv1Bitstream(file, parseFile(file)) { _, _ -> }
        assertEquals(101L, report.totalOccurrences["av1.obu.forbidden_bit"])
        assertEquals(100, report.findings.count { it.rule.id == "av1.obu.forbidden_bit" && it.status == ConformanceStatus.FAIL })
    }

    @Test
    fun cancellationAfterFirstSampleStopsInspection() = runBlocking {
        val file = fixture("default-av01.mp4")
        assertFailsWith<CancellationException> {
            analyzeAv1Bitstream(file, parseFile(file)) { checked, _ ->
                if (checked == 1) throw CancellationException("stop")
            }
        }
    }

    @Test
    fun incompleteSampleIndexHasIndeterminateProgressTotal() = runBlocking {
        val file = fixture("default-av01.mp4")
        fun BoxNode.shortenSizes(): BoxNode = copy(children = children.map { child ->
            if (child.type == "stsz") child.copy(table = child.table?.copy(entryCount = 4))
            else child.shortenSizes()
        })
        val totals = mutableListOf<Int?>()
        val report = analyzeAv1Bitstream(file, parseFile(file).shortenSizes()) { _, total -> totals += total }
        assertFalse(report.complete)
        assertTrue(totals.isNotEmpty())
        assertTrue(totals.all { it == null })
    }
}
