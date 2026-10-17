package com.multiviewer.ui

import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.conformance.ConformanceStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentCheckPresentationTest {
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
}
