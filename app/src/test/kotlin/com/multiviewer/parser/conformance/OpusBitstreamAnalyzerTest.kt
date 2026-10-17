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

class OpusBitstreamAnalyzerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/opus-conformance/$name")!!.toURI())

    @Test
    fun defaultOpusReportsCheckedCountAndPassingFindings() = runBlocking {
        val file = fixture("default-opus.mp4")
        val report = analyzeOpusBitstream(file, parseFile(file)) { _, _ -> }
        assertTrue(report.complete)
        assertEquals(8, report.checkedSamples)
        assertEquals(8, report.indexedSamples)
        assertTrue(report.findings.any { it.rule.id == "opus.config.dops" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "opus.config.match" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "opus.packet.toc" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "opus.packet.framing" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "opus.sample.framing" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun badDopsVersionReportsFailureFinding() = runBlocking {
        val file = fixture("bad-dops-version.mp4")
        val report = analyzeOpusBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        val finding = report.findings.first { it.rule.id == "opus.config.dops" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
    }

    @Test
    fun badConfigMatchReportsFailureFinding() = runBlocking {
        val file = fixture("bad-config-match.mp4")
        val report = analyzeOpusBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        val finding = report.findings.first { it.rule.id == "opus.config.match" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
    }

    @Test
    fun badMonoStereoFlagReportsFailureFinding() = runBlocking {
        val file = fixture("bad-mono-stereo-flag.mp4")
        val report = analyzeOpusBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        val finding = report.findings.first { it.rule.id == "opus.packet.toc" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertFalse(report.findings.any { it.rule.id == "opus.packet.toc" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun badCode3ZeroFramesReportsFailureFinding() = runBlocking {
        val file = fixture("bad-code3-zero-frames.mp4")
        val report = analyzeOpusBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        val finding = report.findings.first { it.rule.id == "opus.packet.framing" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertFalse(report.findings.any { it.rule.id == "opus.packet.framing" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun truncatedSampleLeavesCompleteFalseAndReportsFramingFail() = runBlocking {
        val file = fixture("truncated-sample.mp4")
        val report = analyzeOpusBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        assertFalse(report.findings.any { it.rule.id == "opus.sample.framing" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun repeatedTocFailuresAreBoundedButCounted() = runBlocking {
        val file = fixture("many-toc-failures.mp4")
        val report = analyzeOpusBitstream(file, parseFile(file)) { _, _ -> }
        assertEquals(101L, report.totalOccurrences["opus.packet.toc"])
        assertEquals(100, report.findings.count { it.rule.id == "opus.packet.toc" && it.status == ConformanceStatus.FAIL })
    }

    @Test
    fun cancellationAfterFirstSampleStopsInspection() = runBlocking {
        val file = fixture("default-opus.mp4")
        assertFailsWith<CancellationException> {
            analyzeOpusBitstream(file, parseFile(file)) { checked, _ ->
                if (checked == 1) throw CancellationException("stop")
            }
        }
    }
}
