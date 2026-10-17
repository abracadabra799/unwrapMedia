package com.multiviewer.parser.conformance

import com.multiviewer.parser.parseFile
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HevcBitstreamAnalyzerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/hevc-conformance/$name")!!.toURI())

    @Test fun bothRealSampleEntryTagsScanEightSamplesWithExactEvidence() = runBlocking {
        for (name in listOf("default-hev1.mp4", "explicit-hvc1.mp4")) {
            val file = fixture(name)
            val progress = mutableListOf<Int>()
            val report = analyzeHevcBitstream(file, parseFile(file)) { checked, _ -> progress += checked }
            assertEquals(8, report.indexedSamples)
            assertEquals(8, report.checkedSamples)
            assertTrue(report.complete)
            assertEquals(8, progress.last())
            assertTrue(report.checkedTrack.contains(if (name.startsWith("default")) "hev1" else "hvc1"))
            assertTrue(report.findings.any { it.rule.id == "hevc.nal.forbidden_zero_bit" && it.status == ConformanceStatus.PASS })
        }
    }

    @Test fun headerMutationCarriesStableRuleAndByteRange() = runBlocking {
        val file = fixture("forbidden-bit.mp4")
        val report = analyzeHevcBitstream(file, parseFile(file)) { _, _ -> }
        val finding = report.findings.single { it.rule.id == "hevc.nal.forbidden_zero_bit" && it.status == ConformanceStatus.FAIL }
        assertEquals(listOf(48L..49L), finding.ranges)
        assertTrue(finding.details.any { it.contains("sample=0") && it.contains("observed=1") })
        val temporalFile = fixture("zero-temporal-id.mp4")
        val temporal = analyzeHevcBitstream(temporalFile, parseFile(temporalFile)) { _, _ -> }
        assertTrue(temporal.findings.any { it.rule.id == "hevc.nal.temporal_id_plus1" && it.status == ConformanceStatus.FAIL })
    }

    @Test fun unsupportedParameterSyntaxAndMalformedConfigurationNeverPass() = runBlocking {
        val file = fixture("malformed-hvcc.mp4")
        val report = analyzeHevcBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        assertTrue(report.limitations.any { it.contains("hvcC") })
        assertFalse(report.findings.any { it.status == ConformanceStatus.PASS })
    }

    @Test fun shortSampleProducesPartialCoverageAndNoFramingPass() = runBlocking {
        val file = fixture("truncated-prefix.mp4")
        val report = analyzeHevcBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        assertTrue(report.findings.any { it.rule.id == "hevc.nal.framing" && it.status == ConformanceStatus.FAIL })
        assertFalse(report.findings.any { it.rule.id == "hevc.nal.framing" && it.status == ConformanceStatus.PASS })
    }

    @Test fun zeroLengthNalNeverProducesFramingPass() = runBlocking {
        val file = fixture("zero-length-nal.mp4")
        val report = analyzeHevcBitstream(file, parseFile(file)) { _, _ -> }
        assertFalse(report.complete)
        assertFalse(report.findings.any { it.rule.id == "hevc.nal.framing" && it.status == ConformanceStatus.PASS })
        assertTrue(report.limitations.any { it.contains("zero-length NAL") })
    }
}
