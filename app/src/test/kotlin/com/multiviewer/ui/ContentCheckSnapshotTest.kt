package com.multiviewer.ui

import com.multiviewer.parser.motionphoto.SpecRequirement
import com.multiviewer.parser.motionphoto.GoogleSpecFinding
import com.multiviewer.cli.AiDiagnosticPromptBuilder
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.conformance.BitstreamCodec
import com.multiviewer.parser.conformance.CodecBitstreamReport
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.AudioStructureReport
import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.parser.integrity.IntegrityCheckItem
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentCheckSnapshotTest {
    @Test
    fun audioStructureFactsAndParserWarningsReachPromptSeparately() {
        val structure = AudioStructureReport(
            "WAVE",
            listOf(IntegrityCheckItem("wave.data.missing", "Audio data chunk", CheckStatus.FAIL, "Required data chunk missing", 12, 8)),
            listOf(IntegrityCheckItem("parser.warning", "Parser warning (RIFF)", CheckStatus.WARN, "Chunk extends past file", 20, 4)),
        )
        val snapshot = ContentCheckSnapshot(null, null, false, null, null, null, MediaType.AUDIO, audioStructure = structure)

        val text = contentCheckPromptSection(snapshot, file)

        assertTrue(text.contains("오디오 구조 검사 (WAVE): FAIL"), text)
        assertTrue(text.contains("wave.data.missing"), text)
        assertTrue(text.contains("파서 경고"), text)
        assertTrue(text.contains("Chunk extends past file"), text)
    }

    // Rule 7: a partial codec byte check must never read as clean to the AI. Only HEVC and VVC used to
    // give the prompt their coverage (complete=false) and limitations.
    @Test
    fun partialCodecByteChecksReachPromptWithCoverageAndLimitationsForEveryCodec() {
        val base = ContentCheckSnapshot(null, null, false, null, null, null, MediaType.VIDEO)
        val limitation = "stopped at sample cap"
        val cases = listOf(
            "AVC" to base.copy(codecBitstreams = listOf(CodecBitstreamReport(BitstreamCodec.AVC, emptyList(), 3, 10, emptyMap(), listOf(limitation), "track 1 (avc1)", false))),
            "AV1" to base.copy(codecBitstreams = listOf(CodecBitstreamReport(BitstreamCodec.AV1, emptyList(), 3, 10, emptyMap(), listOf(limitation), "track 1 (av01)", false))),
            "VP9" to base.copy(codecBitstreams = listOf(CodecBitstreamReport(BitstreamCodec.VP9, emptyList(), 3, 10, emptyMap(), listOf(limitation), "track 1 (vp09)", false))),
            "APV" to base.copy(codecBitstreams = listOf(CodecBitstreamReport(BitstreamCodec.APV, emptyList(), 3, 10, emptyMap(), listOf(limitation), "track 1 (apv1)", false))),
            "AAC" to base.copy(mediaType = MediaType.AUDIO, codecBitstreams = listOf(CodecBitstreamReport(BitstreamCodec.AAC, emptyList(), 3, 10, emptyMap(), listOf(limitation), "track 1 (mp4a)", false))),
            "Opus" to base.copy(mediaType = MediaType.AUDIO, codecBitstreams = listOf(CodecBitstreamReport(BitstreamCodec.OPUS, emptyList(), 3, 10, emptyMap(), listOf(limitation), "track 1 (Opus)", false))),
            "FLAC" to base.copy(mediaType = MediaType.AUDIO, codecBitstreams = listOf(CodecBitstreamReport(BitstreamCodec.FLAC, emptyList(), 3, 10, emptyMap(), listOf(limitation), "track 1 (fLaC)", false))),
        )
        for ((codec, snapshot) in cases) {
            val text = contentCheckPromptSection(snapshot, file)
            assertTrue(text.contains("- $codec 바이트 검사: 3/10 samples; complete=false; track 1"), "$codec:\n$text")
            assertTrue(text.contains("  • $limitation"), "$codec:\n$text")
        }
    }

    @Test
    fun skippedAudioStructureIsNotPresentedAsClean() {
        val structure = AudioStructureReport(
            "UNKNOWN",
            listOf(IntegrityCheckItem("audio.format", "Audio format", CheckStatus.SKIP, "Unrecognized audio format")),
            emptyList(),
        )
        val snapshot = ContentCheckSnapshot(null, null, false, null, null, null, MediaType.AUDIO, audioStructure = structure)

        val text = contentCheckPromptSection(snapshot, file)

        assertTrue(text.contains("오디오 구조 검사 (UNKNOWN): SKIP"), text)
        assertTrue(text.contains("[SKIP] audio.format"), text)
        assertFalse(text.contains("오디오 구조 검사 (UNKNOWN): PASS"), text)
    }

    @Test
    fun audioFactsAndFailuresReachPromptWithoutPaths() {
        val measurements = AudioMeasurements().apply { add(AudioFrame(48000, 2, 4800)) }
        val report = AudioIntegrityReport(IntegrityStatus.CLEAN, listOf(measurements.report(
            AudioStreamMetadata(2, "aac", 44100, 1, 2.0), 0, listOf("bad ${file.absolutePath}"))))
        val snapshot = ContentCheckSnapshot(null, null, false, null, null, null, MediaType.AUDIO, audio = report)
        val text = contentCheckPromptSection(snapshot, file)
        assertTrue(text.contains("오디오 디코딩"), text)
        assertTrue(text.contains("4800"), text)
        assertTrue(text.contains("48000"), text)
        assertTrue(text.contains("44100"), text)
        assertFalse(text.contains(file.absolutePath), text)
        val failed = contentCheckPromptSection(snapshot.copy(audio = null, audioError = "failed ${file.absolutePath}"), file)
        assertTrue(failed.contains("오디오 디코딩: 검사 실패"), failed)
        assertFalse(failed.contains(file.absolutePath), failed)
        val absent = contentCheckPromptSection(snapshot.copy(audio = AudioIntegrityReport(IntegrityStatus.CLEAN, emptyList())), file)
        assertTrue(absent.contains("오디오 트랙 없음"), absent)
    }

    private val file: File = File.createTempFile("cc-snapshot-", ".jpg").apply {
        writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0, 4, 0, 0))
    }

    @AfterTest
    fun cleanup() {
        file.delete()
    }

    private val header = "### [컨텐츠 검사 결과 (앱이 직접 검증한 사실)]"

    private fun corruptStructure() = ImageStructureReport(
        format = "JPEG",
        items = listOf(
            IntegrityCheckItem("jpeg.soi", "SOI marker", CheckStatus.PASS, "SOI present", offset = 0, length = 2),
            IntegrityCheckItem("jpeg.app1", "Extra APP segment", CheckStatus.INFO, "recognized APP1", offset = 2, length = 10),
            IntegrityCheckItem("jpeg.dqt", "Quantization table", CheckStatus.WARN, "DQT length short", offset = 0x14, length = 4),
            IntegrityCheckItem("jpeg.eoi", "EOI marker", CheckStatus.FAIL, "EOI missing (file truncated)", offset = 0x1F40, length = null),
        ),
        declaredWidth = 4000,
        declaredHeight = 3000,
    )

    private fun corruptDecode() = ImageDecodeReport(
        ffmpegStatus = ImageDecodeStatus.ISSUES,
        decodedFrames = 1,
        decodedWidth = 4000,
        decodedHeight = 3000,
        logs = listOf(
            "[mjpeg @ 0x7f] error count: 64",
            "[mjpeg @ 0x7f] overread 8 reading ${file.absolutePath}",
        ),
        logsTruncated = false,
        ffmpegVersion = "ffmpeg version 7.1",
        source = file.absolutePath,
        skia = SkiaDecodeResult(attempted = true, ok = false, detail = "Skia could not decode the image"),
        declaredWidth = 4000,
        declaredHeight = 3000,
    )

    @Test
    fun structureListsOnlyWarnAndFailWithOffsets() {
        val text = contentCheckPromptSection(ContentCheckSnapshot(corruptStructure(), null, false, null, null, null), file)
        assertTrue(text.startsWith(header), text)
        assertTrue(text.contains("jpeg.dqt"), text)
        assertTrue(text.contains("jpeg.eoi"), text)
        assertTrue(text.contains("0x14"), text)
        assertTrue(text.contains("0x1F40"), text)
        assertTrue(text.contains("EOI missing (file truncated)"), text)
        assertTrue(text.contains("[PASS] structure.jpeg.soi"), text)
        assertTrue(text.contains("[INFO] structure.jpeg.app1"), text)
    }

    @Test
    fun structureListIsCappedAtThirty() {
        val items = (1..35).map { IntegrityCheckItem("fail.$it", "Fail $it", CheckStatus.FAIL, "broken $it", offset = it.toLong()) }
        val text = contentCheckPromptSection(
            ContentCheckSnapshot(ImageStructureReport("JPEG", items), null, false, null, null, null), file,
        )
        assertTrue(text.contains("fail.30 "), text)
        assertFalse(text.contains("fail.31 "), text)
        assertTrue(text.contains("5개 생략"), text)
    }

    @Test
    fun stepsNotRunAreStated() {
        val text = contentCheckPromptSection(ContentCheckSnapshot(corruptStructure(), null, true, null, null, null), file)
        assertTrue(text.contains("이미지 디코딩: 미실행"), text)
        assertTrue(text.contains("모션포토: 미실행"), text)
        assertTrue(text.contains("영상 디코딩: 미실행"), text)
    }

    @Test
    fun structureNotLoadedIsStated() {
        val text = contentCheckPromptSection(ContentCheckSnapshot(null, null, false, null, null, null), file)
        assertTrue(text.contains("구조 검사: 미실행"), text)
    }

    @Test
    fun pathsAreScrubbedFromLogs() {
        val tmp = System.getProperty("java.io.tmpdir").trimEnd('/', '\\')
        val video = VideoIntegrityReport(
            decodeStatus = IntegrityStatus.ISSUES,
            packetStatus = IntegrityStatus.CLEAN,
            decodedFrames = 120,
            logs = listOf("[h264 @ 0x1] error while decoding $tmp/x.mp4", "Error at ${file.absolutePath}"),
            packetLogs = emptyList(),
            packets = emptyList(),
        )
        val text = contentCheckPromptSection(ContentCheckSnapshot(corruptStructure(), corruptDecode(), false, null, null, video), file)
        assertFalse(text.contains(tmp), text)
        assertFalse(text.contains(file.absolutePath), text)
        assertTrue(text.contains(file.name), text)
        assertTrue(text.contains("x.mp4"), text)
        assertTrue(text.contains("120"), text)
    }

    @Test
    fun decodeLogsRemainBoundedInDetailedPromptSection() {
        val decode = corruptDecode().copy(logs = (1..25).map { "log line $it" })
        val text = contentCheckPromptSection(ContentCheckSnapshot(corruptStructure(), decode, false, null, null, null), file)
        assertTrue(text.contains("log line 20"), text)
        assertTrue(text.contains("log line 21"), text)
        assertTrue(text.contains("5개 생략"), text)
    }

    @Test
    fun aggregatedCapturedLogsAreBoundedAndReportOmissionsInPromptFacts() {
        val logs = (1..500).map { "probe log $it" }
        val snapshot = ContentCheckSnapshot(
            corruptStructure(), null, false, null, null, null, MediaType.AUDIO,
            audio = AudioIntegrityReport(IntegrityStatus.ISSUES, emptyList(), logs = logs),
        )

        val text = contentCheckPromptSection(snapshot, file)

        assertTrue(text.contains("probe log 20"), text)
        assertFalse(text.contains("probe log 500"), text)
        assertTrue(text.contains("근거 일부 생략"), text)
        assertTrue(text.length < 20_000, "Prompt section should remain bounded (${text.length} chars)")
    }

    @Test
    fun motionReportListsNonPassChecksAndError() {
        val report = MotionPhotoIntegrityReport(
            detectedFormats = listOf(MotionPhotoFormat.GOOGLE_XMP),
            sefSection = null,
            googleSpec = listOf(
                GoogleSpecFinding("gmp.camera.motionphoto", "3.1", SpecRequirement.MUST, SefIntegritySeverity.PASS, "XMP present", "ok"),
                GoogleSpecFinding("gmp.layout.video_start", "3.3", SpecRequirement.MUST, SefIntegritySeverity.CRITICAL, "Video offset", "declared offset beyond EOF"),
            ),
            decodeChecks = emptyList(),
            overallSeverity = SefIntegritySeverity.CRITICAL,
        )
        val text = contentCheckPromptSection(ContentCheckSnapshot(corruptStructure(), corruptDecode(), true, report, null, null), file)
        assertTrue(text.contains("GOOGLE_XMP"), text)
        assertTrue(text.contains("Video offset"), text)
        assertFalse(text.contains("XMP present"), text)

        val err = contentCheckPromptSection(
            ContentCheckSnapshot(corruptStructure(), null, true, null, "ffmpeg failed on ${file.absolutePath}", null), file,
        )
        assertTrue(err.contains("모션포토: 분석 실패"), err)
        assertFalse(err.contains(file.absolutePath), err)
    }

    @Test
    fun buildPromptIncludesSectionOnlyWhenSnapshotGiven() {
        val snapshot = ContentCheckSnapshot(
            corruptStructure(), corruptDecode(), false, null, null, null, fileIdentity = ContentFileIdentity.capture(file),
        )
        val with = AiDiagnosticPromptBuilder.buildPrompt(file, null, emptyList(), contentCheck = snapshot)
        val without = AiDiagnosticPromptBuilder.buildPrompt(file, null, emptyList())
        assertTrue(with.contains(header), with)
        assertFalse(without.contains(header), without)
    }

    @Test
    fun mediaTypeNotApplicableBranches() {
        val text = contentCheckPromptSection(
            ContentCheckSnapshot(corruptStructure(), null, false, null, null, null, mediaType = MediaType.VIDEO), file,
        )
        assertTrue(text.contains("이미지 디코딩: 해당 없음"), text)
        assertFalse(text.contains("영상 디코딩: 해당 없음"), text)
        val img = contentCheckPromptSection(
            ContentCheckSnapshot(corruptStructure(), null, false, null, null, null, mediaType = MediaType.IMAGE), file,
        )
        assertTrue(img.contains("영상 디코딩: 해당 없음"), img)
    }

    @Test
    fun failedStepsAreNotReportedAsNotRun() {
        val text = contentCheckPromptSection(
            ContentCheckSnapshot(
                null, null, false, null, null, null,
                structureError = "bad tree ${file.absolutePath}", imageDecodeError = "ffmpeg crashed ${file.absolutePath}",
                videoError = "probe failed ${file.absolutePath}",
                audioError = "audio failed ${file.absolutePath}",
            ),
            file,
        )
        assertTrue(text.contains("구조 검사: 검사 실패 — bad tree"), text)
        assertTrue(text.contains("이미지 디코딩: 검사 실패 — ffmpeg crashed"), text)
        assertTrue(text.contains("영상 디코딩: 검사 실패 — probe failed"), text)
        assertTrue(text.contains("오디오 디코딩: 검사 실패 — audio failed"), text)
        assertFalse(text.contains("미실행"), text)
        assertFalse(text.contains(file.absolutePath), text)
    }

    @Test
    fun runningStepsShowInProgress() {
        val text = contentCheckPromptSection(
            ContentCheckSnapshot(corruptStructure(), null, true, null, null, null, running = true), file,
        )
        assertTrue(text.contains("이미지 디코딩: 진행 중"), text)
        assertTrue(text.contains("모션포토: 진행 중"), text)
        assertTrue(text.contains("영상 디코딩: 진행 중"), text)
        assertFalse(text.contains("미실행"), text)
    }

    @Test
    fun everyMediaTypePublishesFindingFactsToPrompt() {
        val video = VideoIntegrityReport(
            IntegrityStatus.CLEAN, IntegrityStatus.ISSUES, 1, emptyList(), listOf("packet timestamp discontinuity"),
            listOf(IntegrityPacket(0, "0.0", "0.0", 12, 8, true)),
        )
        val audio = AudioIntegrityReport(IntegrityStatus.CLEAN, listOf(AudioStreamReport(
            AudioStreamMetadata(0, "aac", 48000, 2, 1.0), IntegrityStatus.CLEAN,
            1, 48000, 1.0, setOf(48000), setOf(2), 0.1, emptyList(), emptyList(),
        )))
        val motion = MotionPhotoIntegrityReport(
            listOf(MotionPhotoFormat.GOOGLE_XMP), null,
            listOf(GoogleSpecFinding("gmp.layout.video_start", "3.3", SpecRequirement.MUST, SefIntegritySeverity.WARNING, "Motion offset", "Offset is 12 bytes late")),
            emptyList(), SefIntegritySeverity.WARNING,
        )
        val snapshot = ContentCheckSnapshot(
            corruptStructure(), corruptDecode(), true, motion, null, video, MediaType.IMAGE,
            audio = audio,
            audioStructure = AudioStructureReport("WAVE", listOf(
                IntegrityCheckItem("wave.data", "Data chunk", CheckStatus.FAIL, "Data chunk missing"),
            ), emptyList()),
        )

        val text = contentCheckPromptSection(snapshot, file)

        listOf("jpeg.eoi", "image.decode", "Motion offset", "video.packet.log", "audio.track", "wave.data")
            .forEach { assertTrue(it in text, text) }
    }

    @Test
    fun hypothesesAreNotListedAsVerifiedFacts() {
        val report = VideoIntegrityReport(
            IntegrityStatus.ISSUES, IntegrityStatus.CLEAN, 0, listOf("Invalid NAL unit size"), emptyList(), emptyList(),
        )

        val text = contentCheckPromptSection(ContentCheckSnapshot(null, null, false, null, null, report), file)
        val factsEnd = text.indexOf("### [가능성")

        assertTrue(text.contains("### [검증된 사실]"), text)
        assertTrue(text.contains("### [가능성 / 가설]"), text)
        assertTrue(factsEnd >= 0, text)
        val verifiedFacts = text.substringAfter("### [검증된 사실]").substringBefore("### [가능성 / 가설]")
        assertFalse(verifiedFacts.contains("가능한 원인"), text)
        assertTrue(text.substring(factsEnd).contains("video.decode.log.0: NAL Unit"), text)
    }

    @Test
    fun contentFindingPromptContinuesToScrubPaths() {
        val report = VideoIntegrityReport(
            IntegrityStatus.ISSUES, IntegrityStatus.CLEAN, 0,
            listOf("decoder failed at ${file.absolutePath}"), emptyList(), emptyList(),
        )

        val text = contentCheckPromptSection(ContentCheckSnapshot(null, null, false, null, null, report), file)

        assertFalse(file.absolutePath in text, text)
        assertTrue("decoder failed" in text, text)
    }

    @Test
    fun structureAndPacketRangesRemainSelectable() {
        val structureFinding = structureFindings(corruptStructure(), FindingStage.STRUCTURE)
            .first { it.id == "structure.jpeg.dqt" }
        val packet = videoFindings(VideoIntegrityReport(
            IntegrityStatus.CLEAN, IntegrityStatus.CLEAN, 1, emptyList(), emptyList(),
            listOf(IntegrityPacket(0, "0", "0", 12, 8, false)),
        )).packetRows.single()
        val packetFinding = ContentFinding("packet", FindingStage.VIDEO_PACKETS, CheckStatus.WARN,
            "Packet", null, packet.offset, packet.size, "packet", emptyList())

        assertTrue(isSelectableContentRange(structureFinding, 100))
        assertTrue(isSelectableContentRange(packetFinding, 100))
        assertEquals(12L, packet.offset)
        assertEquals(8L, packet.size)
    }

    @Test
    fun pendingSkippedAndFailedStatesRemainDistinct() {
        val skipped = ImageDecodeReport(
            ImageDecodeStatus.NOT_RUN, 0, null, null, emptyList(), false, null, "ffmpeg",
            SkiaDecodeResult(false, false, "not run"), null, null,
        )
        val text = contentCheckPromptSection(
            ContentCheckSnapshot(null, skipped, false, null, null, null, running = true), file,
        )

        assertTrue("미실행" in text || "진행 중" in text, text)
        assertTrue("SKIP" in text, text)
        val failed = contentCheckPromptSection(
            ContentCheckSnapshot(null, null, false, null, null, null, imageDecodeError = "decoder failed"), file,
        )
        assertTrue("검사 실패" in failed, failed)
        assertFalse("이미지 디코딩: 미실행" in failed, failed)
    }

    @Test
    fun fileIdentityTracksLengthAndModificationTime() {
        val identity = ContentFileIdentity.capture(file)
        assertTrue(identity.matches(file))
        assertTrue(file.setLastModified(identity.lastModified - 5_000))
        assertFalse(identity.matches(file))
        val resized = ContentFileIdentity.capture(file)
        file.appendBytes(byteArrayOf(0))
        assertTrue(file.setLastModified(resized.lastModified))
        assertFalse(resized.matches(file))
        assertFalse(identity.matches(File(file.path + ".missing")))
    }

    @Test
    fun promptDropsContentCheckEvidenceAfterTheFileChanges() {
        val pass = ImageStructureReport("PNG", listOf(IntegrityCheckItem("png.crc", "CRC", CheckStatus.PASS, "CRC ok", 33, 4)))
        val snapshot = ContentCheckSnapshot(
            pass, null, false, null, null, null, MediaType.IMAGE,
            conformance = contentConformanceReport(MediaType.IMAGE, structure = pass),
            fileIdentity = ContentFileIdentity.capture(file),
        )
        assertTrue(AiDiagnosticPromptBuilder.buildPrompt(file, null, emptyList(), contentCheck = snapshot).contains(header))

        file.appendBytes(byteArrayOf(1, 2, 3))
        val stale = AiDiagnosticPromptBuilder.buildPrompt(file, null, emptyList(), contentCheck = snapshot)
        assertFalse(stale.contains(header), stale)
        assertFalse(stale.contains("png.crc"), stale)
        assertFalse(stale.contains("0x21"), stale)
        assertTrue(stale.contains(STALE_CONTENT_CHECK_NOTICE), stale)

        val unbound = AiDiagnosticPromptBuilder.buildPrompt(file, null, emptyList(), contentCheck = snapshot.copy(fileIdentity = null))
        assertFalse(unbound.contains(header), unbound)
        assertTrue(unbound.contains(UNBOUND_CONTENT_CHECK_NOTICE), unbound)
        assertFalse(unbound.contains(STALE_CONTENT_CHECK_NOTICE), unbound)
    }


    @Test
    fun promptShowsCitationSourceForNonPassConformanceRowsOnly() {
        val failUrl = "https://aomediacodec.github.io/av1-avif/v1.2.0.html#file-constraints"
        val passUrl = "https://aomediacodec.github.io/av1-avif/v1.2.0.html#image-item"
        fun rule(id: String, url: String) = com.multiviewer.parser.conformance.ConformanceRule(
            id, "title $id", "AVIF v1.2.0 (AOMedia)", "AVIF v1.2.0 §7", "AVIF",
            com.multiviewer.parser.conformance.SpecCitation("AVIF v1.2.0 (AOMedia, 2025-10-16)", "§7 General constraints", null, url, true))
        val report = com.multiviewer.parser.conformance.ConformanceReport("AVIF", "p", listOf(
            com.multiviewer.parser.conformance.ConformanceFinding(rule("avif.ftyp.miaf", failUrl), com.multiviewer.parser.conformance.ConformanceStatus.FAIL, listOf("no miaf"), emptyList()),
            com.multiviewer.parser.conformance.ConformanceFinding(rule("avif.ok", passUrl), com.multiviewer.parser.conformance.ConformanceStatus.PASS, emptyList(), emptyList()),
        ))
        val text = contentCheckPromptSection(
            ContentCheckSnapshot(null, null, false, null, null, null, MediaType.IMAGE, conformance = report), file,
        )
        val failLine = text.lines().single { it.contains("avif.ftyp.miaf") && it.contains("[FAIL]") && it.contains("title") }
        assertTrue(failLine.contains("출처: AVIF v1.2.0 (AOMedia, 2025-10-16) §7 General constraints $failUrl"), failLine)
        val passLine = text.lines().single { it.contains("avif.ok") && it.contains("[PASS]") && it.contains("title") }
        assertFalse(passLine.contains("출처:"), passLine)
        assertFalse(text.contains(passUrl), text)
    }

    @Test
    fun promptShowsCitationForNonPassMotionPhotoSpecRows() {
        val url = "https://developer.android.com/media/platform/motion-photo-format#camera-metadata"
        val report = MotionPhotoIntegrityReport(
            detectedFormats = listOf(MotionPhotoFormat.GOOGLE_XMP), sefSection = null,
            googleSpec = listOf(GoogleSpecFinding("gmp.camera.motionphoto", "3.1", SpecRequirement.MUST,
                SefIntegritySeverity.CRITICAL, "MotionPhoto flag", "missing",
                com.multiviewer.parser.conformance.SpecCitation("Google Motion Photo 1.0", "Camera metadata", null, url, true))),
            decodeChecks = emptyList(), overallSeverity = SefIntegritySeverity.CRITICAL,
        )
        val text = contentCheckPromptSection(ContentCheckSnapshot(corruptStructure(), corruptDecode(), true, report, null, null), file)
        assertTrue(text.contains("출처: Google Motion Photo 1.0 Camera metadata $url"), text)
    }
}
