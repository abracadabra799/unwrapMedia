package com.multiviewer.ui

import com.multiviewer.parser.integrity.CheckStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ContentCheckPresentationTest {
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
        assertEquals("실패", contentCheckStructureStatusLabel(null, "read failed", true))
        assertEquals("정상", contentCheckStructureStatusLabel(CheckStatus.PASS, null, true))
    }
}
