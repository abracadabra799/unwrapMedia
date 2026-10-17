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

class AvcBitstreamAnalyzerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/avc-conformance/$name")!!.toURI())

    @Test fun validAvc1ReportsCheckedSamplesAndFramingPass() = runBlocking {
        val file = fixture("default-avc1.mp4")
        val report = analyzeAvcBitstream(file, parseFile(file)) { _, _ -> }
        assertTrue(report.complete)
        assertEquals(8, report.checkedSamples)
        assertEquals(8, report.indexedSamples)
        assertTrue(report.findings.any { it.rule.id == "avc.nal.framing" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "avc.nal.forbidden_zero_bit" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "avc.nal.idr_ref_idc" && it.status == ConformanceStatus.PASS })
    }

    @Test fun corruptedForbiddenZeroBitIsObserved() = runBlocking {
        val file = fixture("forbidden-bit.mp4")
        val report = analyzeAvcBitstream(file, parseFile(file)) { _, _ -> }
        val finding = report.findings.first { it.rule.id == "avc.nal.forbidden_zero_bit" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertEquals(52L..52L, finding.ranges.first())
        assertTrue(finding.details.first().contains("observed=1"))
    }

    @Test fun idrZeroRefIsObserved() = runBlocking {
        val file = fixture("idr-zero-ref.mp4")
        val report = analyzeAvcBitstream(file, parseFile(file)) { _, _ -> }
        val finding = report.findings.first { it.rule.id == "avc.nal.idr_ref_idc" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertEquals(741L..741L, finding.ranges.first())
        assertTrue(finding.details.first().contains("observed=0"))
    }

    @Test fun zeroLengthNalLeavesFramingUnchecked() = runBlocking {
        val file = fixture("zero-length-nal.mp4")
        val report = analyzeAvcBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.findings.any { it.rule.id == "avc.nal.framing" && it.status == ConformanceStatus.PASS })
        assertTrue(report.limitations.any { it.contains("zero-length NAL") })
    }

    @Test fun repeatedHeaderFailuresAreBoundedButCounted() = runBlocking {
        val file = fixture("many-forbidden-headers.mp4")
        val report = analyzeAvcBitstream(file, parseFile(file)) { _, _ -> }
        assertEquals(101L, report.totalOccurrences["avc.nal.forbidden_zero_bit"])
        assertEquals(100, report.findings.count { it.rule.id == "avc.nal.forbidden_zero_bit" && it.status == ConformanceStatus.FAIL })
    }

    @Test fun cancellationAfterFirstSampleStopsInspection() = runBlocking {
        val file = fixture("default-avc1.mp4")
        assertFailsWith<CancellationException> {
            analyzeAvcBitstream(file, parseFile(file)) { checked, _ -> if (checked == 1) throw CancellationException("stop") }
        }
    }

    @Test fun incompleteSampleIndexHasIndeterminateProgressTotal() = runBlocking {
        val file = fixture("default-avc1.mp4")
        fun BoxNode.shortenSizes(): BoxNode = copy(children = children.map { child ->
            if (child.type == "stsz") child.copy(table = child.table?.copy(entryCount = 4))
            else child.shortenSizes()
        })
        val totals = mutableListOf<Int?>()
        val report = analyzeAvcBitstream(file, parseFile(file).shortenSizes()) { _, total -> totals += total }
        assertFalse(report.complete)
        assertTrue(totals.isNotEmpty())
        assertTrue(totals.all { it == null })
    }
}
