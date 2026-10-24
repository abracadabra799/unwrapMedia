package com.multiviewer.ui

import com.multiviewer.cli.imageIntegrityJson
import com.multiviewer.cli.motionPhotoJson
import com.multiviewer.cli.render
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefDirectoryEntryRow
import com.multiviewer.parser.SefIntegrityReport
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.conformance.BitstreamCodec
import com.multiviewer.parser.conformance.CodecBitstreamReport
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceRule
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.conformance.SpecCitation
import com.multiviewer.parser.conformance.label
import com.multiviewer.parser.integrity.AudioStructureReport
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.parser.integrity.IntegrityCheckItem
import com.multiviewer.parser.motionphoto.GoogleSpecFinding
import com.multiviewer.parser.motionphoto.SpecRequirement
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** User requirement: no WARN/FAIL row without a source line; rows with no public spec get a plain label, never a link. */
class FindingSourceLabelTest {
    private val citation = SpecCitation("ISO/IEC 14496-12:2026", "§4.3 (ftyp)", null, "https://www.iso.org/standard/85596.html", false)
    private val file = File("sample.heic")

    private fun rule(id: String, source: String, cited: Boolean) =
        ConformanceRule(id, "t / t", source, "ref", "scope", if (cited) citation else null)

    private val conformance = ConformanceReport("HEIF", "p", listOf(
        ConformanceFinding(rule("bmff.ftyp.position", "ISO/IEC 14496-12 (공개 자료 기반, 원문 미대조)", true), ConformanceStatus.WARN, listOf("d"), emptyList()),
        ConformanceFinding(rule("x.uncited", "ISO/IEC 14496-14 (공개 자료 기반, 원문 미대조)", false), ConformanceStatus.FAIL, listOf("d"), emptyList()),
        ConformanceFinding(rule("x.structural", "unwrapMedia structural check", false), ConformanceStatus.FAIL, listOf("d"), emptyList()),
    ))

    private val codec = CodecBitstreamReport(BitstreamCodec.AVC, listOf(
        ConformanceFinding(rule("avc.sps", "unwrapMedia structural observation; AVC header fields", false), ConformanceStatus.WARN, listOf("d"), emptyList()),
    ), 1, 1, emptyMap(), emptyList(), "track 1", true)

    private val structure = ImageStructureReport("HEIF", listOf(
        IntegrityCheckItem("heif.ftyp", "File type", CheckStatus.FAIL, "dup ftyp", 10, 24),
        IntegrityCheckItem("heif.meta", "Meta", CheckStatus.PASS, "ok", 0, 8),
    ))

    private val sef = SefIntegrityReport(
        overallSeverity = SefIntegritySeverity.CRITICAL,
        structuralChecks = listOf(SefCheckResult(SefIntegritySeverity.WARNING, "SEFT", "bad")),
        semanticChecks = listOf(SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #11 MotionPhoto_Data bounds", "bad")),
        declaredEntryCount = 2,
        directoryEntries = listOf(SefDirectoryEntryRow(1, "0xA01", "MotionPhoto_Data", 100, 16, 1000, 1016, false, false, SefIntegritySeverity.CRITICAL)),
        declaredEntryCountSeverity = SefIntegritySeverity.CRITICAL,
    )

    private val motion = MotionPhotoIntegrityReport(
        detectedFormats = listOf(MotionPhotoFormat.SAMSUNG_SEF, MotionPhotoFormat.GOOGLE_XMP), sefSection = sef,
        googleSpec = listOf(
            GoogleSpecFinding("gmp.mpvd.present", "4", SpecRequirement.MUST, SefIntegritySeverity.CRITICAL, "mpvd", "missing",
                SpecCitation("Google Motion Photo Format 1.0 (Android Developers)", "§4 ISOBMFF-image-specific behavior", null,
                    "https://developer.android.com/media/platform/motion-photo-format#isobmff-image-specific-behavior", true)),
            GoogleSpecFinding("gmp.unknown", "9", SpecRequirement.SHOULD, SefIntegritySeverity.WARNING, "uncited", "x"),
        ),
        decodeChecks = listOf(SefCheckResult(SefIntegritySeverity.CRITICAL, "Embedded video decode", "errors")),
        overallSeverity = SefIntegritySeverity.CRITICAL,
    )

    private val imageDecode = ImageDecodeReport(
        ffmpegStatus = ImageDecodeStatus.ISSUES, decodedFrames = 1, decodedWidth = 640, decodedHeight = 480,
        logs = listOf("corrupt"), logsTruncated = false, ffmpegVersion = "7.0", source = "ffmpeg",
        skia = SkiaDecodeResult(true, false, "failed"), declaredWidth = 800, declaredHeight = 600,
    )
    private val video = VideoIntegrityReport(
        decodeStatus = IntegrityStatus.ISSUES, packetStatus = IntegrityStatus.ISSUES, decodedFrames = 2,
        logs = listOf("Invalid NAL unit size"), packetLogs = listOf("packet warning"), packets = emptyList(),
    )
    private val audio = AudioIntegrityReport(IntegrityStatus.ISSUES, emptyList(), logs = listOf("audio error"))
    private val audioStructure = AudioStructureReport("WAVE",
        listOf(IntegrityCheckItem("wave.data", "data", CheckStatus.FAIL, "missing", 12, 8)),
        listOf(IntegrityCheckItem("parser.w", "Parser warning", CheckStatus.WARN, "past end", 20, 4)))
    private val gainmap = listOf(IntegrityCheckItem("gainmap.x", "Gain map", CheckStatus.WARN, "odd", null, null))

    private fun allFindings(): List<ContentFinding> = buildList {
        addAll(conformanceFindings(conformance))
        addAll(codecBitstreamFindings(codec))
        addAll(structureFindings(structure, FindingStage.STRUCTURE))
        addAll(audioStructureFindings(audioStructure))
        addAll(imageDecodeFindings(imageDecode))
        addAll(motionPhotoFindings(motion))
        videoFindings(video).let { addAll(it.decoderFindings); addAll(it.packetFindings) }
        audioFindings(audio).let { addAll(it.probeFindings); addAll(it.trackFindings) }
        addAll(gainmapFindings(gainmap))
    }

    @Test fun labelsAreExact() {
        assertEquals("unwrapMedia 자체 관찰 / unwrapMedia structural observation (규격 아님)", STRUCTURAL_OBSERVATION_LABEL)
        assertEquals("Samsung SEF (비공개 형식, 공개 규격 없음) / Samsung SEF (proprietary format, no public spec)", SAMSUNG_SEF_SOURCE_LABEL)
        assertEquals("디코더 결과 (ffmpeg) / Decoder result (ffmpeg), 규격 조항 아님", DECODER_RESULT_SOURCE_LABEL)
        assertEquals("X (링크 준비 중 / link pending)", linkPendingSourceLabel("X"))
    }

    @Test fun everyWarnOrFailFromEveryStageHasASourceLine() {
        val issues = allFindings().filter { it.status == CheckStatus.WARN || it.status == CheckStatus.FAIL }
        val stages = issues.map { it.stage }.toSet()
        assertTrue(stages.containsAll(listOf(FindingStage.CONFORMANCE, FindingStage.CODEC, FindingStage.STRUCTURE,
            FindingStage.PARSER, FindingStage.AUDIO_STRUCTURE, FindingStage.IMAGE_DECODE, FindingStage.MOTION_PHOTO,
            FindingStage.VIDEO_DECODE, FindingStage.VIDEO_PACKETS, FindingStage.AUDIO_DECODE, FindingStage.GAINMAP)), stages.toString())
        issues.forEach { f ->
            val line = assertNotNull(contentFindingSourceLine(f), f.id)
            if (f.citation != null) assertEquals(CitationSourceLine.Link(f.citation!!.label(), f.citation!!.url), line, f.id)
            else {
                assertNotNull(f.sourceNote, "${f.id}: mapper must set sourceNote")
                assertEquals(CitationSourceLine.Plain(f.sourceNote!!), line, f.id)
            }
        }
        // A WARN/FAIL finding built without a note still never renders without a source line, at any stage.
        FindingStage.entries.forEach { stage ->
            listOf(CheckStatus.WARN, CheckStatus.FAIL).forEach { status ->
                val bare = ContentFinding("bare", stage, status, "t", null, null, null, "s", emptyList())
                assertEquals(CitationSourceLine.Plain(STRUCTURAL_OBSERVATION_LABEL), contentFindingSourceLine(bare), "$stage $status")
            }
        }
    }

    @Test fun mappersSetTheRightNote() {
        val byId = allFindings().associateBy { it.id }
        assertNull(byId.getValue("bmff.ftyp.position").sourceNote)
        assertEquals("ISO/IEC 14496-14 (공개 자료 기반, 원문 미대조) (링크 준비 중 / link pending)", byId.getValue("x.uncited").sourceNote)
        assertEquals(STRUCTURAL_OBSERVATION_LABEL, byId.getValue("x.structural").sourceNote)
        assertEquals("unwrapMedia structural observation; AVC header fields (링크 준비 중 / link pending)", byId.getValue("avc.sps").sourceNote)
        assertEquals(STRUCTURAL_OBSERVATION_LABEL, byId.getValue("structure.heif.ftyp").sourceNote)
        assertEquals(STRUCTURAL_OBSERVATION_LABEL, byId.getValue("structure.wave.data").sourceNote)
        assertEquals(STRUCTURAL_OBSERVATION_LABEL, byId.getValue("check.gainmap.x").sourceNote)
        listOf("motion.sef.structural.0", "motion.sef.semantic.0", "motion.sef.entry_count", "motion.sef.directory.1").forEach {
            assertEquals(SAMSUNG_SEF_SOURCE_LABEL, byId.getValue(it).sourceNote, it)
        }
        assertNotNull(byId.getValue("motion.gmp.mpvd.present").citation)
        assertEquals("Google Motion Photo Format 1.0 (링크 준비 중 / link pending)", byId.getValue("motion.gmp.unknown").sourceNote)
        listOf("motion.decode.0", "image.decode.ffmpeg", "image.decode.skia", "image.decode.resolution",
            "video.decode.log.0", "video.packet.log.0", "audio.decode.probe").forEach {
            assertEquals(DECODER_RESULT_SOURCE_LABEL, byId.getValue(it).sourceNote, it)
        }
    }

    @Test fun jsonRowsWithoutCitationCarryASourceNote() {
        val motionJson = motionPhotoJson(true, motion, file)!!.render()
        assertTrue(motionJson.contains("\"sourceNote\": \"$SAMSUNG_SEF_SOURCE_LABEL\""), motionJson)
        assertTrue(motionJson.contains("\"sourceNote\": \"$DECODER_RESULT_SOURCE_LABEL\""), motionJson)
        assertTrue(motionJson.contains("\"sourceNote\": \"Google Motion Photo Format 1.0 (링크 준비 중 / link pending)\""), motionJson)
        val structureJson = imageIntegrityJson(structure, imageDecode).render()
        assertTrue(structureJson.contains("\"sourceNote\": \"$STRUCTURAL_OBSERVATION_LABEL\""), structureJson)
        assertTrue(structureJson.contains("\"sourceNote\": \"$DECODER_RESULT_SOURCE_LABEL\""), structureJson)
        // The PASS structure row stays unlabelled.
        val passRow = structureJson.substringAfter("\"heif.meta\"").substringBefore("}")
        assertTrue("sourceNote" !in passRow, passRow)
    }

    @Test fun promptNamesTheSourceOfUncitedNonPassRows() {
        val snapshot = ContentCheckSnapshot(structure, imageDecode, true, motion, null, null, MediaType.IMAGE,
            conformance = conformance, codecBitstreams = listOf(codec))
        val text = contentCheckPromptSection(snapshot, file)
        assertTrue(text.contains("출처: $SAMSUNG_SEF_SOURCE_LABEL"), text)
        assertTrue(text.contains("출처: $DECODER_RESULT_SOURCE_LABEL"), text)
        assertTrue(text.contains("출처: ISO/IEC 14496-14 (공개 자료 기반, 원문 미대조) (링크 준비 중 / link pending)"), text)
        assertTrue(text.contains("출처: $STRUCTURAL_OBSERVATION_LABEL"), text)
        assertTrue(text.contains("출처: unwrapMedia structural observation; AVC header fields (링크 준비 중 / link pending)"), text)
    }
}
