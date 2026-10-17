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

class Vp9BitstreamAnalyzerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/vp9-conformance/$name")!!.toURI())

    @Test
    fun validVp09ReportsCheckedSamplesAndFramingPass() = runBlocking {
        val file = fixture("default-vp09.mp4")
        val report = analyzeVp9Bitstream(file, parseFile(file)) { _, _ -> }
        assertTrue(report.complete)
        assertEquals(8, report.checkedSamples)
        assertEquals(8, report.indexedSamples)
        assertTrue(report.findings.any { it.rule.id == "vp9.frame.framing" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "vp9.frame.marker" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "vp9.frame.sync_code" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "vp9.frame.profile_reserved_bit" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "vp9.config.vpcc" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun badMarkerIsObserved() = runBlocking {
        val file = fixture("bad-marker.mp4")
        val report = analyzeVp9Bitstream(file, parseFile(file)) { _, _ -> }
        val finding = report.findings.first { it.rule.id == "vp9.frame.marker" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertEquals(44L..44L, finding.ranges.first())
        assertTrue(finding.details.first().contains("observed=0"))
    }

    @Test
    fun badSyncCodeIsObserved() = runBlocking {
        val file = fixture("bad-sync-code.mp4")
        val report = analyzeVp9Bitstream(file, parseFile(file)) { _, _ -> }
        val finding = report.findings.first { it.rule.id == "vp9.frame.sync_code" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertEquals(45L..47L, finding.ranges.first())
    }

    @Test
    fun profile3ReservedBitIsObserved() = runBlocking {
        val file = fixture("profile3-reserved-bit.mp4")
        val report = analyzeVp9Bitstream(file, parseFile(file)) { _, _ -> }
        val finding = report.findings.first { it.rule.id == "vp9.frame.profile_reserved_bit" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertEquals(44L..44L, finding.ranges.first())
        assertTrue(finding.details.first().contains("observed=1"))
    }

    @Test
    fun truncatedFrameLeavesFramingUnchecked() = runBlocking {
        val file = fixture("truncated-frame.mp4")
        val report = analyzeVp9Bitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        assertFalse(report.findings.any { it.rule.id == "vp9.frame.framing" && it.status == ConformanceStatus.PASS })
        val failFinding = report.findings.first { it.rule.id == "vp9.frame.framing" && it.status == ConformanceStatus.FAIL }
        assertEquals(44L..44L, failFinding.ranges.first())
    }

    @Test
    fun malformedVpccReportsUnsupported() = runBlocking {
        val file = fixture("malformed-vpcc.mp4")
        val report = analyzeVp9Bitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        assertTrue(report.limitations.any { it.contains("vpcC FullBox version is invalid") })
    }

    @Test
    fun repeatedMarkerFailuresAreBoundedButCounted() = runBlocking {
        val file = fixture("many-marker-failures.mp4")
        val report = analyzeVp9Bitstream(file, parseFile(file)) { _, _ -> }
        assertEquals(101L, report.totalOccurrences["vp9.frame.marker"])
        assertEquals(100, report.findings.count { it.rule.id == "vp9.frame.marker" && it.status == ConformanceStatus.FAIL })
    }

    @Test
    fun cancellationAfterFirstSampleStopsInspection() = runBlocking {
        val file = fixture("default-vp09.mp4")
        assertFailsWith<CancellationException> {
            analyzeVp9Bitstream(file, parseFile(file)) { checked, _ ->
                if (checked == 1) throw CancellationException("stop")
            }
        }
    }

    @Test
    fun incompleteSampleIndexHasIndeterminateProgressTotal() = runBlocking {
        val file = fixture("default-vp09.mp4")
        fun BoxNode.shortenSizes(): BoxNode = copy(children = children.map { child ->
            if (child.type == "stsz") child.copy(table = child.table?.copy(entryCount = 4))
            else child.shortenSizes()
        })
        val totals = mutableListOf<Int?>()
        val report = analyzeVp9Bitstream(file, parseFile(file).shortenSizes()) { _, total -> totals += total }
        assertFalse(report.complete)
        assertTrue(totals.isNotEmpty())
        assertTrue(totals.all { it == null })
    }
}
