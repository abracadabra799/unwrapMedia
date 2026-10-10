package com.multiviewer.ui

import com.multiviewer.cli.AiDiagnosticPromptBuilder
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.parser.integrity.IntegrityCheckItem
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentCheckSnapshotTest {
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
        assertFalse(text.contains("jpeg.soi"), text)
        assertFalse(text.contains("jpeg.app1"), text)
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
    fun decodeLogsAreCappedAtTwenty() {
        val decode = corruptDecode().copy(logs = (1..25).map { "log line $it" })
        val text = contentCheckPromptSection(ContentCheckSnapshot(corruptStructure(), decode, false, null, null, null), file)
        assertTrue(text.contains("log line 20"), text)
        assertFalse(text.contains("log line 21"), text)
        assertTrue(text.contains("5개 생략"), text)
    }

    @Test
    fun motionReportListsNonPassChecksAndError() {
        val report = MotionPhotoIntegrityReport(
            detectedFormats = listOf(MotionPhotoFormat.GOOGLE_XMP),
            sefSection = null,
            googleXmpChecks = listOf(
                SefCheckResult(SefIntegritySeverity.PASS, "XMP present", "ok"),
                SefCheckResult(SefIntegritySeverity.CRITICAL, "Video offset", "declared offset beyond EOF"),
            ),
            appleMpvdChecks = emptyList(),
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
        val snapshot = ContentCheckSnapshot(corruptStructure(), corruptDecode(), false, null, null, null)
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
}
