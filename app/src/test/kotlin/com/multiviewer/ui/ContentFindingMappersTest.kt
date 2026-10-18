package com.multiviewer.ui

import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.integrity.AudioStructureReport
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.parser.integrity.IntegrityCheckItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentFindingMappersTest {
    @Test fun mapsStructureItemsAndParserWarningsToSeparateStages() {
        val structure = ImageStructureReport("JPEG", listOf(
            IntegrityCheckItem("jpeg.eoi", "End marker", CheckStatus.FAIL, "EOI missing", 20, 2),
        ))
        val audio = AudioStructureReport("WAVE", emptyList(), listOf(
            IntegrityCheckItem("parser.box", "Parser warning", CheckStatus.WARN, "Unexpected box", 4, 8),
        ))

        val imageFindings = structureFindings(structure, FindingStage.STRUCTURE)
        val audioFindings = audioStructureFindings(audio)

        assertEquals(FindingStage.STRUCTURE, imageFindings.single().stage)
        assertEquals(20L, imageFindings.single().offset)
        assertEquals("EOI missing", imageFindings.single().verifiedFacts.single())
        assertEquals(FindingStage.PARSER, audioFindings.single().stage)
        assertNull(audioFindings.single().hypothesis)
    }

    @Test fun mapsImageDecodeResolutionMismatch() {
        val report = ImageDecodeReport(
            ffmpegStatus = ImageDecodeStatus.CLEAN, decodedFrames = 1, decodedWidth = 640, decodedHeight = 480,
            logs = emptyList(), logsTruncated = false, ffmpegVersion = "7.0", source = "ffmpeg",
            skia = SkiaDecodeResult(true, true, "Decoded 640x480"), declaredWidth = 800, declaredHeight = 600,
        )

        val finding = imageDecodeFindings(report).first { it.id == "image.decode.resolution" }

        assertEquals(CheckStatus.WARN, finding.status)
        assertTrue("Declared width: 800 px" in finding.verifiedFacts)
        assertTrue("Declared height: 600 px" in finding.verifiedFacts)
        assertTrue("Decoded width: 640 px" in finding.verifiedFacts)
        assertTrue("Decoded height: 480 px" in finding.verifiedFacts)
    }

    @Test fun labelsMotionPhotoSeverityAndEvidence() {
        val report = MotionPhotoIntegrityReport(
            detectedFormats = listOf(MotionPhotoFormat.GOOGLE_XMP), sefSection = null,
            googleXmpChecks = listOf(SefCheckResult(SefIntegritySeverity.WARNING, "Offset mismatch", "Declared offset differs by 12 bytes")),
            appleMpvdChecks = emptyList(), decodeChecks = emptyList(), overallSeverity = SefIntegritySeverity.WARNING,
        )

        val finding = motionPhotoFindings(report).single()

        assertEquals(CheckStatus.WARN, finding.status)
        assertEquals("Offset mismatch", finding.title)
        assertEquals("Declared offset differs by 12 bytes", finding.verifiedFacts.single())
        assertNull(finding.hypothesis)
    }

    @Test fun marksVideoLogExplanationAsHypothesisWithoutByteLocation() {
        val report = VideoIntegrityReport(
            decodeStatus = IntegrityStatus.ISSUES, packetStatus = IntegrityStatus.CLEAN, decodedFrames = 2,
            logs = listOf("Invalid NAL unit size"), packetLogs = emptyList(), packets = emptyList(),
        )

        val finding = videoFindings(report).decoderFindings.single()

        assertEquals(CheckStatus.WARN, finding.status)
        assertNull(finding.offset)
        assertEquals("Invalid NAL unit size", finding.verifiedFacts.first())
        assertNotNull(finding.hypothesis)
        assertTrue(finding.hypothesis!!.isNotBlank())
    }

    @Test fun unmatchedDecoderMessageDoesNotReceiveInventedCause() {
        val finding = videoFindings(VideoIntegrityReport(
            decodeStatus = IntegrityStatus.ISSUES, packetStatus = IntegrityStatus.NOT_RUN, decodedFrames = 0,
            logs = listOf("vendor-specific decoder note: retrying"), packetLogs = emptyList(), packets = emptyList(),
        )).decoderFindings.single()

        assertEquals("vendor-specific decoder note: retrying", finding.summary)
        assertNull(finding.hypothesis)
        assertTrue(finding.nextChecks.isEmpty())
        assertEquals("Cause not determined", unknownDecoderCauseLabel(finding, ko = false))
    }

    @Test fun imageAndAudioAdaptersRetainCapturedLogsBeyondTwentyEntries() {
        val logs = (1..25).map { "captured decoder message $it" }
        val image = ImageDecodeReport(
            ffmpegStatus = ImageDecodeStatus.ISSUES, decodedFrames = 1, decodedWidth = 1, decodedHeight = 1,
            logs = logs, logsTruncated = false, ffmpegVersion = null, source = "ffmpeg",
            skia = SkiaDecodeResult(true, true, "decoded"), declaredWidth = 1, declaredHeight = 1,
        )
        val audio = AudioIntegrityReport(IntegrityStatus.ISSUES, emptyList(), logs = logs)

        assertTrue("captured decoder message 25" in imageDecodeFindings(image).first().verifiedFacts)
        assertTrue("captured decoder message 25" in audioFindings(audio).probeFindings.single().verifiedFacts)
    }

    @Test fun preservesPacketLocationAndRange() {
        val report = VideoIntegrityReport(
            decodeStatus = IntegrityStatus.CLEAN, packetStatus = IntegrityStatus.ISSUES, decodedFrames = 2,
            logs = emptyList(), packetLogs = listOf("packet warning"),
            packets = listOf(IntegrityPacket(3, "1.5", "1.4", 128, 24, true)),
        )

        val presentation = videoFindings(report)
        val row = presentation.packetRows.single()

        assertEquals(128L, row.offset)
        assertEquals(24L, row.size)
        assertEquals("1.5", row.pts)
        assertEquals("1.4", row.dts)
        assertTrue((report.packets as Any) === (presentation.packetRows as Any))
    }

    @Test fun mapsAudioMeasurementsAndMismatchesAsVerifiedFacts() {
        val stream = AudioStreamReport(
            stream = AudioStreamMetadata(1, "aac", 44100, 2, 3.0), status = IntegrityStatus.ISSUES,
            decodedFrames = 130, decodedSamples = 132300, decodedDurationSeconds = 3.0,
            observedSampleRates = setOf(48000), observedChannels = setOf(2), durationToleranceSeconds = 0.1,
            mismatches = listOf("Observed sample rate differs from declared rate"), logs = emptyList(),
        )
        val report = AudioIntegrityReport(IntegrityStatus.CLEAN, listOf(stream))

        val presentation = audioFindings(report)
        val finding = presentation.trackFindings.single()
        val row = presentation.trackRows.single()

        assertTrue("Observed sample rate differs from declared rate" in finding.verifiedFacts)
        assertNull(finding.hypothesis)
        assertEquals(44100, row.declaredSampleRate)
        assertEquals(setOf(48000), row.observedSampleRates)
        assertEquals(132300L, row.decodedSamples)
    }

    @Test fun doesNotKeepRawAbsolutePathsInPresentationFindings() {
        val report = VideoIntegrityReport(
            decodeStatus = IntegrityStatus.ISSUES, packetStatus = IntegrityStatus.NOT_RUN, decodedFrames = 0,
            logs = listOf("Could not read /Users/private-user/secret/clip.mp4"), packetLogs = emptyList(), packets = emptyList(),
        )

        val finding = videoFindings(report).decoderFindings.single()

        assertFalse("/Users/private-user/secret/clip.mp4" in finding.summary)
        assertFalse(finding.verifiedFacts.any { "/Users/private-user" in it })
        assertFalse(finding.hypothesis.orEmpty().contains("/Users/private-user"))
    }

    @Test fun mapsConformanceFindingsToNormalizedTabularItems() {
        val rule = com.multiviewer.parser.conformance.ConformanceRule(
            id = "isobmff.ftyp.major_brand",
            title = "Major brand presence",
            source = "ISO/IEC 14496-12:2022",
            reference = "Clause 4.3.1",
            scope = "ftyp box",
        )
        val finding = com.multiviewer.parser.conformance.ConformanceFinding(
            rule = rule,
            status = com.multiviewer.parser.conformance.ConformanceStatus.PASS,
            details = listOf("ftyp major_brand 'mp42' found at offset 8"),
            ranges = listOf(8L..11L),
        )
        val report = com.multiviewer.parser.conformance.ConformanceReport(
            format = "MP4",
            profile = "ISO-BMFF",
            findings = listOf(finding),
        )

        val results = conformanceFindings(report)
        assertEquals(1, results.size)
        val item = results.single()
        assertEquals("isobmff.ftyp.major_brand", item.id)
        assertEquals(FindingStage.CONFORMANCE, item.stage)
        assertEquals(CheckStatus.PASS, item.status)
        assertEquals("Major brand presence", item.title)
        assertEquals("ftyp box", item.location)
        assertEquals(8L, item.offset)
        assertEquals(4L, item.length)
        assertTrue(item.verifiedFacts.any { it.contains("ISO/IEC 14496-12:2022") })
        assertTrue(item.verifiedFacts.any { it.contains("Clause 4.3.1") })
        assertTrue(item.nextChecks.any { it.contains("0x8–0xB") })
    }
}
