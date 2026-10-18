package com.multiviewer.ui

import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentCheckPresentationTest {
    @Test fun checklistLabelsSeparateExecutionFromVerdict() {
        assertEquals("컨테이너 규격", checkStageLabel(CheckStage.CONTAINER_CONFORMANCE, true))
        assertEquals("Container conformance", checkStageLabel(CheckStage.CONTAINER_CONFORMANCE, false))
        assertEquals("파일 구조 및 무결성", checkStageLabel(CheckStage.STRUCTURE, true))
        assertEquals("File structure & integrity", checkStageLabel(CheckStage.STRUCTURE, false))
        assertEquals("코덱 비트스트림", checkStageLabel(CheckStage.CODEC, true))
        assertEquals("Codec bitstream", checkStageLabel(CheckStage.CODEC, false))
        assertEquals("영상·이미지 디코딩", checkStageLabel(CheckStage.VISUAL_DECODE, true))
        assertEquals("Video or image decode", checkStageLabel(CheckStage.VISUAL_DECODE, false))
        assertEquals("오디오 디코딩", checkStageLabel(CheckStage.AUDIO_DECODE, true))
        assertEquals("Audio decode", checkStageLabel(CheckStage.AUDIO_DECODE, false))
        assertEquals("모션포토", checkStageLabel(CheckStage.MOTION_PHOTO, true))
        assertEquals("Motion photo", checkStageLabel(CheckStage.MOTION_PHOTO, false))
        assertEquals("게인맵", checkStageLabel(CheckStage.GAINMAP, true))
        assertEquals("Gain map", checkStageLabel(CheckStage.GAINMAP, false))

        assertEquals("미검사", checkRunStatusLabel(CheckRunStatus.NOT_RUN, true))
        assertEquals("Running", checkRunStatusLabel(CheckRunStatus.RUNNING, false))
        assertEquals("완료", checkRunStatusLabel(CheckRunStatus.COMPLETED, true))
        assertEquals("실패", checkRunStatusLabel(CheckRunStatus.FAILED, true))
        assertEquals("17 frames", checkProgressLabel(CheckProgress(17, null, "frames")))
        assertEquals("17/20 frames", checkProgressLabel(CheckProgress(17, 20, "frames")))
        assertEquals("다시 실행", checkStageActionLabel(CheckRunStatus.COMPLETED, true))

        assertEquals(ContentTab.CONFORMANCE, checkStageDetailTab(CheckStage.CONTAINER_CONFORMANCE, MediaType.VIDEO))
        assertEquals(ContentTab.STRUCTURE, checkStageDetailTab(CheckStage.STRUCTURE, MediaType.VIDEO))
        assertEquals(ContentTab.CODEC, checkStageDetailTab(CheckStage.CODEC, MediaType.VIDEO))
        assertEquals(ContentTab.VIDEO_DECODE, checkStageDetailTab(CheckStage.VISUAL_DECODE, MediaType.VIDEO))
        assertEquals(ContentTab.IMAGE_DECODE, checkStageDetailTab(CheckStage.VISUAL_DECODE, MediaType.IMAGE))
        assertEquals(ContentTab.AUDIO, checkStageDetailTab(CheckStage.AUDIO_DECODE, MediaType.VIDEO))
        assertEquals(ContentTab.MOTION_PHOTO, checkStageDetailTab(CheckStage.MOTION_PHOTO, MediaType.IMAGE))
        assertEquals(ContentTab.GAINMAP, checkStageDetailTab(CheckStage.GAINMAP, MediaType.IMAGE))
    }

    @Test fun categoryGroupingAndLabelsFollowMediaEngineeringStandard() {
        assertEquals(CheckCategory.FORMAT_AND_BITSTREAM, checkCategory(CheckStage.CONTAINER_CONFORMANCE))
        assertEquals(CheckCategory.FORMAT_AND_BITSTREAM, checkCategory(CheckStage.STRUCTURE))
        assertEquals(CheckCategory.FORMAT_AND_BITSTREAM, checkCategory(CheckStage.CODEC))
        assertEquals(CheckCategory.DECODE_VERIFICATION, checkCategory(CheckStage.VISUAL_DECODE))
        assertEquals(CheckCategory.DECODE_VERIFICATION, checkCategory(CheckStage.AUDIO_DECODE))
        assertEquals(CheckCategory.ADDITIONAL_CHECKS, checkCategory(CheckStage.MOTION_PHOTO))
        assertEquals(CheckCategory.ADDITIONAL_CHECKS, checkCategory(CheckStage.GAINMAP))

        assertEquals("포맷 및 비트스트림", checkCategoryLabel(CheckCategory.FORMAT_AND_BITSTREAM, true))
        assertEquals("Format & Bitstream", checkCategoryLabel(CheckCategory.FORMAT_AND_BITSTREAM, false))
        assertEquals("디코딩 검증", checkCategoryLabel(CheckCategory.DECODE_VERIFICATION, true))
        assertEquals("Decode Verification", checkCategoryLabel(CheckCategory.DECODE_VERIFICATION, false))
        assertEquals("확장 검사", checkCategoryLabel(CheckCategory.ADDITIONAL_CHECKS, true))
        assertEquals("Additional Checks", checkCategoryLabel(CheckCategory.ADDITIONAL_CHECKS, false))
    }

    @Test fun stageDescriptionsAreAccurateAndBilingual() {
        assertEquals("컨테이너 포맷 규격(ISO-BMFF, EXIF 등) 필수 박스 및 구조 제약 조건을 검증합니다.",
            checkStageDescription(CheckStage.CONTAINER_CONFORMANCE, true))
        assertEquals("Validates container format specification requirements (ISO-BMFF, EXIF, etc.).",
            checkStageDescription(CheckStage.CONTAINER_CONFORMANCE, false))
        assertEquals("박스·청크 배치, 오프셋 무결성 및 파서 경고를 검사합니다.",
            checkStageDescription(CheckStage.STRUCTURE, true))
        assertEquals("Checks box and chunk layout, offset integrity, and parser warnings.",
            checkStageDescription(CheckStage.STRUCTURE, false))
        assertEquals("코덱 구문, 값 범위, NAL 헤더 및 파라미터 간 참조를 검사합니다.",
            checkStageDescription(CheckStage.CODEC, true))
        assertEquals("Checks codec syntax, value ranges, NAL headers, and parameter references.",
            checkStageDescription(CheckStage.CODEC, false))
    }

    @Test fun stageTargetFormatResolutionProducesIntuitiveLabels() {
        val mp4Root = com.multiviewer.parser.BoxNode("root", 0, 0, 1000, children = listOf(
            com.multiviewer.parser.BoxNode("ftyp", 0, 8, 32, fields = listOf(
                com.multiviewer.parser.BoxField("major_brand", "mp42", 8, 4)
            )),
            com.multiviewer.parser.BoxNode("moov", 32, 40, 500, children = listOf(
                com.multiviewer.parser.BoxNode("trak", 40, 48, 200, children = listOf(
                    com.multiviewer.parser.BoxNode("mdia", 48, 56, 180, children = listOf(
                        com.multiviewer.parser.BoxNode("minf", 56, 64, 160, children = listOf(
                            com.multiviewer.parser.BoxNode("stbl", 64, 72, 140, children = listOf(
                                com.multiviewer.parser.BoxNode("stsd", 72, 80, 120, children = listOf(
                                    com.multiviewer.parser.BoxNode("hev1", 80, 88, 100, fields = listOf(
                                        com.multiviewer.parser.BoxField("width", "1920", 88, 2),
                                        com.multiviewer.parser.BoxField("height", "1080", 90, 2),
                                    ))
                                ))
                            ))
                        ))
                    ))
                ))
            ))
        ))
        assertEquals("ISO-BMFF (mp42)", checkStageTargetLabel(CheckStage.CONTAINER_CONFORMANCE, MediaType.VIDEO, mp4Root))
        assertEquals("ISO-BMFF (mp42)", checkStageTargetLabel(CheckStage.STRUCTURE, MediaType.VIDEO, mp4Root))
        assertEquals("HEVC (hev1)", checkStageTargetLabel(CheckStage.CODEC, MediaType.VIDEO, mp4Root))
        assertEquals("1920×1080", checkStageTargetLabel(CheckStage.VISUAL_DECODE, MediaType.VIDEO, mp4Root))
        assertEquals("XMP / GCamera", checkStageTargetLabel(CheckStage.MOTION_PHOTO, MediaType.IMAGE))
        assertEquals("ISO 21496-1 / Ultra HDR", checkStageTargetLabel(CheckStage.GAINMAP, MediaType.IMAGE))
    }

    @Test fun manuallySelectedDetailIsNotReplacedWhenNextStageStarts() {
        assertEquals(CheckStage.STRUCTURE, nextSelectedStage(CheckStage.STRUCTURE, true, CheckStage.CODEC))
        assertEquals(CheckStage.CODEC, nextSelectedStage(CheckStage.STRUCTURE, false, CheckStage.CODEC))
    }
    @Test fun actionsPreserveTheExistingSafetyGates() {
        val imageLoading = contentCheckActionState(MediaType.IMAGE, true, false, true, false, false, false)
        assertFalse(imageLoading.canRun)
        assertFalse(imageLoading.canExport)
        val videoLoading = contentCheckActionState(MediaType.VIDEO, true, false, false, false, false, false)
        assertTrue(videoLoading.canRun)
        assertFalse(videoLoading.canExport)
        val changed = contentCheckActionState(MediaType.VIDEO, true, false, true, true, false, false)
        assertFalse(changed.canRun)
        assertFalse(changed.canExport)
        val running = contentCheckActionState(MediaType.VIDEO, true, false, true, false, true, false)
        assertTrue(running.canCancel)
        assertFalse(running.canRun)
        assertFalse(running.canExport)
        val saving = contentCheckActionState(MediaType.VIDEO, true, false, true, false, false, true)
        assertFalse(saving.canRun)
        assertFalse(saving.canExport)
        assertTrue(saving.canDiagnose)
        assertFalse(saving.canCancel)
    }
    @Test fun stageGroupsFollowTheActualChecks() {
        val image = contentCheckStageGroups(contentTabs(MediaType.IMAGE, motionDetected = true, gainmapDetected = true))
        assertEquals(listOf(ContentTab.STRUCTURE, ContentTab.CONFORMANCE, ContentTab.GAINMAP),
            image[ContentCheckStageGroup.ON_OPEN])
        assertEquals(listOf(ContentTab.IMAGE_DECODE, ContentTab.MOTION_PHOTO),
            image[ContentCheckStageGroup.ON_REQUEST])
        val video = contentCheckStageGroups(contentTabs(MediaType.VIDEO, motionDetected = false))
        assertEquals(listOf(ContentTab.VIDEO_DECODE, ContentTab.VIDEO_PACKETS, ContentTab.AUDIO),
            video[ContentCheckStageGroup.ON_REQUEST])
        val audio = contentCheckStageGroups(contentTabs(MediaType.AUDIO, motionDetected = false))
        assertEquals(listOf(ContentTab.AUDIO), audio[ContentCheckStageGroup.ON_REQUEST])
        val unknown = contentCheckStageGroups(contentTabs(MediaType.UNKNOWN, motionDetected = false))
        assertEquals(null, unknown[ContentCheckStageGroup.ON_REQUEST])
    }

    @Test fun runLabelsAndPendingStructureStatusAreSpecific() {
        assertEquals("이미지 디코딩 검사 실행", contentCheckRunLabel(MediaType.IMAGE, false, true))
        assertEquals("Run image decode and motion-photo checks", contentCheckRunLabel(MediaType.IMAGE, true, false))
        assertEquals("영상·패킷·오디오 검사 실행", contentCheckRunLabel(MediaType.VIDEO, false, true))
        assertEquals("Run audio decode check", contentCheckRunLabel(MediaType.AUDIO, false, false))
        assertEquals(null, contentCheckRunLabel(MediaType.UNKNOWN, false, true))
        assertEquals(null, contentCheckRunLabel(MediaType.RAW_PIXEL, false, false))
        assertEquals("검사 중", contentCheckStructureStatusLabel(null, null, true))
        assertEquals("검사 중", contentCheckStructureStatusLabel(null, "The file structure is not loaded yet", true, treePending = true))
        assertEquals("실패", contentCheckStructureStatusLabel(null, "read failed", true))
        assertEquals("정상", contentCheckStructureStatusLabel(CheckStatus.PASS, null, true))
        assertEquals("검사 중", contentCheckConformanceStatusLabel(ConformanceStatus.NOT_CHECKED, true, treePending = true))
    }

    @Test fun emptyStageHintsReferToTheCurrentRunAction() {
        assertEquals("상단의 실행 버튼으로 모션포토 영상 검사를 시작하세요. 영상 전체를 디코딩하므로 시간이 걸릴 수 있습니다.",
            contentCheckStartHint(ContentTab.MOTION_PHOTO, true))
        assertEquals("Use the run button above for video decode and packet checks.",
            contentCheckStartHint(ContentTab.VIDEO_DECODE, false))
    }

    @Test fun conformanceVerdictLabelReflectsProposal2Breakdown() {
        fun dummyFinding(id: String, status: ConformanceStatus) = ConformanceFinding(
            rule = ConformanceRule(id, id, "source", null, "scope"),
            status = status,
            details = listOf(id),
            ranges = emptyList(),
        )

        val pass14NotChecked1 = ConformanceReport(
            format = "MP4", profile = "ISO-BMFF",
            findings = (1..14).map { dummyFinding("rule$it", ConformanceStatus.PASS) } +
                listOf(dummyFinding("rule15", ConformanceStatus.NOT_CHECKED)),
        )
        assertEquals("14/15 항목 통과 (미검증 1건)", conformanceVerdictLabel(pass14NotChecked1, ko = true))
        assertEquals("14/15 passed (1 unverified)", conformanceVerdictLabel(pass14NotChecked1, ko = false))

        val allPass = ConformanceReport(
            format = "MP4", profile = "ISO-BMFF",
            findings = (1..15).map { dummyFinding("rule$it", ConformanceStatus.PASS) },
        )
        assertEquals("규격 통과", conformanceVerdictLabel(allPass, ko = true))
        assertEquals("Conformance pass", conformanceVerdictLabel(allPass, ko = false))

        val withFail = ConformanceReport(
            format = "MP4", profile = "ISO-BMFF",
            findings = listOf(
                dummyFinding("f1", ConformanceStatus.FAIL),
                dummyFinding("p1", ConformanceStatus.PASS),
            ),
        )
        assertEquals("오류 1건", conformanceVerdictLabel(withFail, ko = true))
        assertEquals("1 issues", conformanceVerdictLabel(withFail, ko = false))

        val withWarn = ConformanceReport(
            format = "MP4", profile = "ISO-BMFF",
            findings = listOf(
                dummyFinding("w1", ConformanceStatus.WARN),
                dummyFinding("p1", ConformanceStatus.PASS),
            ),
        )
        assertEquals("경고 1건", conformanceVerdictLabel(withWarn, ko = true))
        assertEquals("1 warnings", conformanceVerdictLabel(withWarn, ko = false))

        val onlyNotChecked = ConformanceReport(
            format = "MP4", profile = "ISO-BMFF",
            findings = listOf(dummyFinding("u1", ConformanceStatus.NOT_CHECKED)),
        )
        assertEquals("미검증 1건", conformanceVerdictLabel(onlyNotChecked, ko = true))
        assertEquals("1 unverified", conformanceVerdictLabel(onlyNotChecked, ko = false))
    }
}

