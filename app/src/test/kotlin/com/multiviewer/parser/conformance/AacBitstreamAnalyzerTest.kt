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

class AacBitstreamAnalyzerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/aac-conformance/$name")!!.toURI())

    @Test
    fun defaultMp4aReportsCheckedCountAndPassingFindings() = runBlocking {
        val file = fixture("default-mp4a.m4a")
        val report = analyzeAacBitstream(file, parseFile(file)) { _, _ -> }
        assertTrue(report.complete)
        assertEquals(8, report.checkedSamples)
        assertEquals(8, report.indexedSamples)
        assertTrue(report.findings.any { it.rule.id == "aac.config.esds" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "aac.config.asc" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "aac.config.match" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "aac.frame.adts_guard" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "aac.frame.element" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "aac.frame.ics_reserved" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "aac.sample.framing" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun badAdtsLeakedReportsFailureFinding() = runBlocking {
        val file = fixture("bad-adts-leaked.m4a")
        val report = analyzeAacBitstream(file, parseFile(file)) { _, _ -> }
        val finding = report.findings.first { it.rule.id == "aac.frame.adts_guard" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertEquals(649L..650L, finding.ranges.first())
        assertFalse(report.findings.any { it.rule.id == "aac.frame.adts_guard" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun badAscSamplingReportsFailureFinding() = runBlocking {
        val file = fixture("bad-asc-sampling.m4a")
        val report = analyzeAacBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        val finding = report.findings.first { it.rule.id == "aac.config.asc" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
    }

    @Test
    fun badIcsReservedReportsFailureFinding() = runBlocking {
        val file = fixture("bad-ics-reserved.m4a")
        val report = analyzeAacBitstream(file, parseFile(file)) { _, _ -> }
        val finding = report.findings.first { it.rule.id == "aac.frame.ics_reserved" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertFalse(report.findings.any { it.rule.id == "aac.frame.ics_reserved" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun malformedEsdsLeavesCompleteFalse() = runBlocking {
        val file = fixture("malformed-esds.m4a")
        val report = analyzeAacBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        assertTrue(report.limitations.any { it.contains("esds") })
    }

    @Test
    fun truncatedSampleLeavesCompleteFalseAndReportsFramingFail() = runBlocking {
        val file = fixture("truncated-sample.m4a")
        val report = analyzeAacBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        assertFalse(report.findings.any { it.rule.id == "aac.sample.framing" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun repeatedAdtsFailuresAreBoundedButCounted() = runBlocking {
        val file = fixture("many-adts-failures.m4a")
        val report = analyzeAacBitstream(file, parseFile(file)) { _, _ -> }
        assertEquals(101L, report.totalOccurrences["aac.frame.adts_guard"])
        assertEquals(100, report.findings.count { it.rule.id == "aac.frame.adts_guard" && it.status == ConformanceStatus.FAIL })
    }

    @Test
    fun cancellationAfterFirstSampleStopsInspection() = runBlocking {
        val file = fixture("default-mp4a.m4a")
        assertFailsWith<CancellationException> {
            analyzeAacBitstream(file, parseFile(file)) { checked, _ ->
                if (checked == 1) throw CancellationException("stop")
            }
        }
    }

    @Test
    fun incompleteSampleIndexHasIndeterminateProgressTotal() = runBlocking {
        val file = fixture("default-mp4a.m4a")
        fun BoxNode.shortenSizes(): BoxNode = copy(children = children.map { child ->
            if (child.type == "stsz") child.copy(table = child.table?.copy(entryCount = 4))
            else child.shortenSizes()
        })
        val totals = mutableListOf<Int?>()
        val report = analyzeAacBitstream(file, parseFile(file).shortenSizes()) { _, total -> totals += total }
        assertFalse(report.complete)
        assertTrue(totals.isNotEmpty())
        assertTrue(totals.all { it == null })
    }
}
