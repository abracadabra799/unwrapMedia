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

class FlacBitstreamAnalyzerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/flac-conformance/$name")!!.toURI())

    @Test
    fun defaultFlacReportsCheckedCountAndPassingFindings() = runBlocking {
        val file = fixture("default-flac.mp4")
        val report = analyzeFlacBitstream(file, parseFile(file)) { _, _ -> }
        assertTrue(report.complete)
        assertEquals(8, report.checkedSamples)
        assertEquals(8, report.indexedSamples)
        assertTrue(report.findings.any { it.rule.id == "flac.config.dfla" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "flac.config.match" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "flac.frame.header" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "flac.frame.crc" && it.status == ConformanceStatus.PASS })
        assertTrue(report.findings.any { it.rule.id == "flac.sample.framing" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun badDflaVersionReportsFailureFinding() = runBlocking {
        val file = fixture("bad-dfla-version.mp4")
        val report = analyzeFlacBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        val finding = report.findings.first { it.rule.id == "flac.config.dfla" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
    }

    @Test
    fun badStreaminfoSizeReportsFailureFinding() = runBlocking {
        val file = fixture("bad-streaminfo-size.mp4")
        val report = analyzeFlacBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        val finding = report.findings.first { it.rule.id == "flac.config.dfla" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
    }

    @Test
    fun badConfigMatchReportsFailureFinding() = runBlocking {
        val file = fixture("bad-config-match.mp4")
        val report = analyzeFlacBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        val finding = report.findings.first { it.rule.id == "flac.config.match" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
    }

    @Test
    fun badFrameSyncReportsFailureFinding() = runBlocking {
        val file = fixture("bad-frame-sync.mp4")
        val report = analyzeFlacBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        val finding = report.findings.first { it.rule.id == "flac.frame.header" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertFalse(report.findings.any { it.rule.id == "flac.frame.header" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun badFrameCrc8ReportsFailureFinding() = runBlocking {
        val file = fixture("bad-frame-crc8.mp4")
        val report = analyzeFlacBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        val finding = report.findings.first { it.rule.id == "flac.frame.header" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertFalse(report.findings.any { it.rule.id == "flac.frame.header" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun badFrameCrc16ReportsFailureFinding() = runBlocking {
        val file = fixture("bad-frame-crc16.mp4")
        val report = analyzeFlacBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        val finding = report.findings.first { it.rule.id == "flac.frame.crc" }
        assertEquals(ConformanceStatus.FAIL, finding.status)
        assertFalse(report.findings.any { it.rule.id == "flac.frame.crc" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun truncatedSampleLeavesCompleteFalseAndReportsFramingFail() = runBlocking {
        val file = fixture("truncated-sample.mp4")
        val report = analyzeFlacBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        assertFalse(report.findings.any { it.rule.id == "flac.sample.framing" && it.status == ConformanceStatus.PASS })
    }

    @Test
    fun repeatedSyncFailuresAreBoundedButCounted() = runBlocking {
        val file = fixture("many-sync-failures.mp4")
        val report = analyzeFlacBitstream(file, parseFile(file)) { _, _ -> }
        assertEquals(101L, report.totalOccurrences["flac.frame.header"])
        assertEquals(100, report.findings.count { it.rule.id == "flac.frame.header" && it.status == ConformanceStatus.FAIL })
    }

    @Test
    fun cancellationAfterFirstSampleStopsInspection() = runBlocking {
        val file = fixture("default-flac.mp4")
        assertFailsWith<CancellationException> {
            analyzeFlacBitstream(file, parseFile(file)) { checked, _ ->
                if (checked == 1) throw CancellationException("stop")
            }
        }
    }

    // FLAC-in-ISOBMFF: a rate above 65535 Hz cannot fit the 16.16 samplerate field, so the entry carries an integer
    // divisor of it (ffmpeg writes 48000 for 96000). Real ffmpeg output, see docs/validation/flac-conformance-fixtures.
    @Test
    fun highSampleRateEntryDivisorMatchesStreaminfo() = runBlocking {
        val file = fixture("ffmpeg-96k.mp4")
        val report = analyzeFlacBitstream(file, parseFile(file)) { _, _ -> }
        assertTrue(report.findings.any { it.rule.id == "flac.config.match" && it.status == ConformanceStatus.PASS },
            report.findings.filter { it.rule.id == "flac.config.match" }.toString())
        assertFalse(report.findings.any { it.status == ConformanceStatus.FAIL }, report.findings.filter { it.status == ConformanceStatus.FAIL }.toString())
        assertTrue(report.complete)
    }
}
