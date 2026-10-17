package com.multiviewer.parser.conformance

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.parseFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApvBitstreamAnalyzerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/apv-conformance/$name")!!.toURI())

    @Test
    fun defaultApv1ReportsCheckedCountAndPassingFindings() = runBlocking {
        val file = fixture("default-apv1.mp4")
        val report = analyzeApvBitstream(file, parseFile(file)) { _, _ -> }
        assertTrue(report.complete)
        assertEquals(8, report.checkedSamples)
        assertEquals(8, report.indexedSamples)
        assertTrue(report.limitations.any { it.contains("Tile layout") })
        assertTrue(report.findings.any { it.rule.id == "apv.au.signature" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "apv.pbu.framing" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "apv.pbu.reserved_zero" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "apv.frame.profile" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "apv.config.apvc" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun badSignatureReportsFailureFinding() = runBlocking {
        val file = fixture("bad-signature.mp4")
        val report = analyzeApvBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        val finding = report.findings.first { it.rule.id == "apv.au.signature" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertEquals(693L..696L, finding.ranges.first())
    }

    @Test
    fun badPbuReservedReportsFailureFinding() = runBlocking {
        val file = fixture("bad-pbu-reserved.mp4")
        val report = analyzeApvBitstream(file, parseFile(file)) { _, _ -> }
        val finding = report.findings.first { it.rule.id == "apv.pbu.reserved_zero" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertFalse(report.findings.any { it.rule.id == "apv.pbu.reserved_zero" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun badBandReservedReportsFailureFinding() = runBlocking {
        val file = fixture("bad-band-reserved.mp4")
        val report = analyzeApvBitstream(file, parseFile(file)) { _, _ -> }
        val finding = report.findings.first { it.rule.id == "apv.frame.profile" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertFalse(report.findings.any { it.rule.id == "apv.frame.profile" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun truncatedSampleLeavesCompleteFalseAndReportsFramingFail() = runBlocking {
        val file = fixture("truncated-sample.mp4")
        val report = analyzeApvBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        assertFalse(report.findings.any { it.rule.id == "apv.pbu.framing" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun malformedApvcLeavesCompleteFalse() = runBlocking {
        val file = fixture("malformed-apvc.mp4")
        val report = analyzeApvBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        assertTrue(report.limitations.any { it.contains("apvC") })
    }

    @Test
    fun repeatedSignatureFailuresAreBoundedButCounted() = runBlocking {
        val file = fixture("many-signature-failures.mp4")
        val report = analyzeApvBitstream(file, parseFile(file)) { _, _ -> }
        assertEquals(101L, report.totalOccurrences["apv.au.signature"])
        assertEquals(100, report.findings.count { it.rule.id == "apv.au.signature" && it.status == ConformanceStatus.FAIL })
    }

    @Test
    fun cancellationAfterFirstSampleStopsInspection() = runBlocking {
        val file = fixture("default-apv1.mp4")
        assertFailsWith<CancellationException> {
            analyzeApvBitstream(file, parseFile(file)) { checked, _ ->
                if (checked == 1) throw CancellationException("stop")
            }
        }
    }

    @Test
    fun incompleteSampleIndexHasIndeterminateProgressTotal() = runBlocking {
        val file = fixture("default-apv1.mp4")
        fun BoxNode.shortenSizes(): BoxNode = copy(children = children.map { child ->
            if (child.type == "stsz") child.copy(table = child.table?.copy(entryCount = 4))
            else child.shortenSizes()
        })
        val totals = mutableListOf<Int?>()
        val report = analyzeApvBitstream(file, parseFile(file).shortenSizes()) { _, total -> totals += total }
        assertFalse(report.complete)
        assertTrue(totals.isNotEmpty())
        assertTrue(totals.all { it == null })
    }
}
