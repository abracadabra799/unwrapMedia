package com.multiviewer.parser.conformance

import com.multiviewer.parser.parseFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VvcBitstreamAnalyzerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/vvc-conformance/$name")!!.toURI())

    @Test
    fun defaultVvcReportsCheckedCountAndPassingFindings() = runBlocking {
        val file = fixture("default-vvc.mp4")
        val report = analyzeVvcBitstream(file, parseFile(file)) { _, _ -> }
        assertTrue(report.complete)
        assertEquals(8, report.checkedSamples)
        assertEquals(8, report.indexedSamples)
        assertTrue(report.findings.any { it.rule.id == "vvc.config.vvcc" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "vvc.nal.framing" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "vvc.nal.forbidden_zero_bit" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "vvc.nal.reserved_zero_bit" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "vvc.nal.temporal_id_plus1" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "vvc.sample.framing" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun badVvccBoxReportsFailureFinding() = runBlocking {
        val file = fixture("bad-vvcc-box.mp4")
        val report = analyzeVvcBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        val finding = report.findings.first { it.rule.id == "vvc.config.vvcc" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
    }

    @Test
    fun badForbiddenBitReportsFailureFinding() = runBlocking {
        val file = fixture("bad-forbidden-bit.mp4")
        val report = analyzeVvcBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        val finding = report.findings.first { it.rule.id == "vvc.nal.forbidden_zero_bit" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertFalse(report.findings.any { it.rule.id == "vvc.nal.forbidden_zero_bit" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun badReservedBitReportsFailureFinding() = runBlocking {
        val file = fixture("bad-reserved-bit.mp4")
        val report = analyzeVvcBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        val finding = report.findings.first { it.rule.id == "vvc.nal.reserved_zero_bit" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertFalse(report.findings.any { it.rule.id == "vvc.nal.reserved_zero_bit" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun badTemporalIdReportsFailureFinding() = runBlocking {
        val file = fixture("bad-temporal-id.mp4")
        val report = analyzeVvcBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        val finding = report.findings.first { it.rule.id == "vvc.nal.temporal_id_plus1" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertFalse(report.findings.any { it.rule.id == "vvc.nal.temporal_id_plus1" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun truncatedSampleLeavesCompleteFalseAndReportsFramingFail() = runBlocking {
        val file = fixture("truncated-sample.mp4")
        val report = analyzeVvcBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        assertFalse(report.findings.any { it.rule.id == "vvc.sample.framing" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun repeatedNalFailuresAreBoundedButCounted() = runBlocking {
        val file = fixture("many-nal-failures.mp4")
        val report = analyzeVvcBitstream(file, parseFile(file)) { _, _ -> }
        assertEquals(101L, report.totalOccurrences["vvc.nal.forbidden_zero_bit"])
        assertEquals(100, report.findings.count { it.rule.id == "vvc.nal.forbidden_zero_bit" && it.status == ConformanceStatus.FAIL })
    }

    @Test
    fun cancellationAfterFirstSampleStopsInspection() = runBlocking {
        val file = fixture("default-vvc.mp4")
        assertFailsWith<CancellationException> {
            analyzeVvcBitstream(file, parseFile(file)) { checked, _ ->
                if (checked == 1) throw CancellationException("stop")
            }
        }
    }
}
