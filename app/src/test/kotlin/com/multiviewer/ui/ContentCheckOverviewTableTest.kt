package com.multiviewer.ui

import androidx.compose.ui.graphics.Color
import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentCheckOverviewTableTest {
    private fun sampleMp4Root(): BoxNode = BoxNode("root", 0, 0, 1000, children = listOf(
        BoxNode("ftyp", 0, 8, 32, fields = listOf(
            BoxField("major_brand", "mp42", 8, 4)
        )),
        BoxNode("moov", 32, 40, 500, children = listOf(
            BoxNode("trak", 40, 48, 200, children = listOf(
                BoxNode("mdia", 48, 56, 180, children = listOf(
                    BoxNode("minf", 56, 64, 160, children = listOf(
                        BoxNode("stbl", 64, 72, 140, children = listOf(
                            BoxNode("stsd", 72, 80, 120, children = listOf(
                                BoxNode("hev1", 80, 88, 100, fields = listOf(
                                    BoxField("width", "1920", 88, 2),
                                    BoxField("height", "1080", 90, 2),
                                ))
                            ))
                        ))
                    ))
                ))
            ))
        ))
    ))

    @Test
    fun overviewRowsAreGeneratedInStandardStageOrderWithCategories() {
        val root = sampleMp4Root()
        val specs = contentCheckStages(MediaType.VIDEO, root, false, false, false, codecRunnerAvailable = true)
        val states = mapOf(
            CheckStage.CONTAINER_CONFORMANCE to CheckStageState(CheckRunStatus.COMPLETED),
            CheckStage.STRUCTURE to CheckStageState(CheckRunStatus.COMPLETED),
            CheckStage.CODEC to CheckStageState(CheckRunStatus.RUNNING, progress = CheckProgress(45, 100, "samples")),
            CheckStage.VISUAL_DECODE to CheckStageState(CheckRunStatus.QUEUED),
        )

        val rows = buildContentCheckOverviewRows(
            specs = specs,
            states = states,
            type = MediaType.VIDEO,
            root = root,
            summary = null,
            ko = true,
            running = true,
            fileChanged = false,
        ) { stage ->
            when (stage) {
                CheckStage.CONTAINER_CONFORMANCE -> "규격 통과"
                CheckStage.STRUCTURE -> "정상"
                else -> null
            }
        }

        assertEquals(5, rows.size)

        // Row 0: Container Conformance
        val row0 = rows[0]
        assertEquals(CheckStage.CONTAINER_CONFORMANCE, row0.stage)
        assertEquals(CheckCategory.FORMAT_AND_BITSTREAM, row0.category)
        assertEquals("컨테이너 규격", row0.stageLabel)
        assertEquals("ISO-BMFF (mp42)", row0.targetLabel)
        assertEquals("완료", row0.status)
        assertEquals(OverviewRowStatusTone.SUCCESS, row0.statusTone)
        assertEquals("규격 통과", row0.verdict)
        assertFalse(row0.isRunning)
        assertFalse(row0.canAction) // running = true, so disabled

        // Row 1: Structure & Integrity
        val row1 = rows[1]
        assertEquals(CheckStage.STRUCTURE, row1.stage)
        assertEquals(CheckCategory.FORMAT_AND_BITSTREAM, row1.category)
        assertEquals("파일 구조 및 무결성", row1.stageLabel)
        assertEquals("ISO-BMFF (mp42)", row1.targetLabel)
        assertEquals("완료", row1.status)
        assertEquals(OverviewRowStatusTone.SUCCESS, row1.statusTone)
        assertEquals("정상", row1.verdict)

        // Row 2: Codec Bitstream (running)
        val row2 = rows[2]
        assertEquals(CheckStage.CODEC, row2.stage)
        assertEquals(CheckCategory.FORMAT_AND_BITSTREAM, row2.category)
        assertEquals("코덱 비트스트림", row2.stageLabel)
        assertEquals("HEVC (hev1)", row2.targetLabel)
        assertEquals("검사 중", row2.status)
        assertEquals(OverviewRowStatusTone.RUNNING, row2.statusTone)
        assertNull(row2.verdict) // Running does not display finished verdict
        assertTrue(row2.isRunning)
        assertEquals(CheckProgress(45, 100, "samples"), row2.progress)

        // Row 3: Visual Decode (queued)
        val row3 = rows[3]
        assertEquals(CheckStage.VISUAL_DECODE, row3.stage)
        assertEquals(CheckCategory.DECODE_VERIFICATION, row3.category)
        assertEquals("영상·이미지 디코딩", row3.stageLabel)
        assertEquals("1920×1080", row3.targetLabel)
        assertEquals("대기", row3.status)
        assertFalse(row3.isRunning)

        // Row 4: Audio Decode (not applicable)
        val row4 = rows[4]
        assertEquals(CheckStage.AUDIO_DECODE, row4.stage)
        assertEquals(CheckCategory.DECODE_VERIFICATION, row4.category)
        assertEquals("오디오 디코딩", row4.stageLabel)
        assertEquals("해당 없음", row4.status)
        assertEquals(OverviewRowStatusTone.MUTED, row4.statusTone)
        assertFalse(row4.isAvailable)
        assertFalse(row4.canAction)
    }

    @Test
    fun actionEnablementRespectsAvailabilityAndGlobalRunningState() {
        val root = sampleMp4Root()
        val specs = listOf(
            CheckStageSpec(CheckStage.CONTAINER_CONFORMANCE, CheckAvailability.AVAILABLE),
            CheckStageSpec(CheckStage.CODEC, CheckAvailability.UNSUPPORTED, reasonKo = "미지원"),
        )
        val states = mapOf(
            CheckStage.CONTAINER_CONFORMANCE to CheckStageState(CheckRunStatus.NOT_RUN),
            CheckStage.CODEC to CheckStageState(CheckRunStatus.NOT_RUN),
        )

        val idleRows = buildContentCheckOverviewRows(
            specs = specs, states = states, type = MediaType.VIDEO, root = root, summary = null,
            ko = true, running = false, fileChanged = false,
        ) { null }

        assertTrue(idleRows[0].canAction)
        assertEquals("실행", idleRows[0].actionLabel)
        assertFalse(idleRows[1].canAction) // Unsupported
        assertEquals("미지원", idleRows[1].status)

        val runningRows = buildContentCheckOverviewRows(
            specs = specs, states = states, type = MediaType.VIDEO, root = root, summary = null,
            ko = true, running = true, fileChanged = false,
        ) { null }
        assertFalse(runningRows[0].canAction) // Disabled while running

        val fileChangedRows = buildContentCheckOverviewRows(
            specs = specs, states = states, type = MediaType.VIDEO, root = root, summary = null,
            ko = true, running = false, fileChanged = true,
        ) { null }
        assertFalse(fileChangedRows[0].canAction) // Disabled when file changed
    }

    @Test
    fun imageFilesOmitCodecBitstreamAndAudioDecodeStages() {
        val root = sampleMp4Root()
        val specs = listOf(
            CheckStageSpec(CheckStage.CONTAINER_CONFORMANCE, CheckAvailability.AVAILABLE),
            CheckStageSpec(CheckStage.STRUCTURE, CheckAvailability.AVAILABLE),
            CheckStageSpec(CheckStage.CODEC, CheckAvailability.NOT_APPLICABLE),
            CheckStageSpec(CheckStage.VISUAL_DECODE, CheckAvailability.AVAILABLE),
            CheckStageSpec(CheckStage.AUDIO_DECODE, CheckAvailability.NOT_APPLICABLE),
            CheckStageSpec(CheckStage.MOTION_PHOTO, CheckAvailability.AVAILABLE),
        )
        val rows = buildContentCheckOverviewRows(
            specs = specs, states = emptyMap(), type = MediaType.IMAGE, root = root, summary = null,
            ko = true, running = false, fileChanged = false,
        ) { null }

        assertEquals(
            listOf(CheckStage.CONTAINER_CONFORMANCE, CheckStage.STRUCTURE, CheckStage.VISUAL_DECODE, CheckStage.MOTION_PHOTO),
            rows.map { it.stage },
        )
        assertFalse(rows.any { it.stage == CheckStage.CODEC })
        assertFalse(rows.any { it.stage == CheckStage.AUDIO_DECODE })
    }

    @Test
    fun groupOverviewRowsByCategoryMergesContiguousSameCategoryRows() {
        val root = sampleMp4Root()
        val specs = listOf(
            CheckStageSpec(CheckStage.CONTAINER_CONFORMANCE, CheckAvailability.AVAILABLE),
            CheckStageSpec(CheckStage.STRUCTURE, CheckAvailability.AVAILABLE),
            CheckStageSpec(CheckStage.CODEC, CheckAvailability.AVAILABLE),
            CheckStageSpec(CheckStage.VISUAL_DECODE, CheckAvailability.AVAILABLE),
            CheckStageSpec(CheckStage.AUDIO_DECODE, CheckAvailability.AVAILABLE),
            CheckStageSpec(CheckStage.MOTION_PHOTO, CheckAvailability.AVAILABLE),
        )
        val rows = buildContentCheckOverviewRows(
            specs = specs, states = emptyMap(), type = MediaType.VIDEO, root = root, summary = null,
            ko = true, running = false, fileChanged = false,
        ) { null }

        val groups = groupOverviewRowsByCategory(rows)
        assertEquals(3, groups.size)

        // First group: FORMAT_AND_BITSTREAM with 3 stages (CONTAINER_CONFORMANCE, STRUCTURE, CODEC)
        assertEquals(CheckCategory.FORMAT_AND_BITSTREAM, groups[0].first)
        assertEquals(
            listOf(CheckStage.CONTAINER_CONFORMANCE, CheckStage.STRUCTURE, CheckStage.CODEC),
            groups[0].second.map { it.stage },
        )

        // Second group: DECODE_VERIFICATION with 2 stages (VISUAL_DECODE, AUDIO_DECODE)
        assertEquals(CheckCategory.DECODE_VERIFICATION, groups[1].first)
        assertEquals(
            listOf(CheckStage.VISUAL_DECODE, CheckStage.AUDIO_DECODE),
            groups[1].second.map { it.stage },
        )

        // Third group: ADDITIONAL_CHECKS with 1 stage (MOTION_PHOTO)
        assertEquals(CheckCategory.ADDITIONAL_CHECKS, groups[2].first)
        assertEquals(
            listOf(CheckStage.MOTION_PHOTO),
            groups[2].second.map { it.stage },
        )
    }

    @Test
    fun completedRowsReflectFailAndWarnVerdictsInStatusAndVerdictTone() {
        val root = sampleMp4Root()
        val specs = listOf(
            CheckStageSpec(CheckStage.CONTAINER_CONFORMANCE, CheckAvailability.AVAILABLE),
            CheckStageSpec(CheckStage.STRUCTURE, CheckAvailability.AVAILABLE),
            CheckStageSpec(CheckStage.MOTION_PHOTO, CheckAvailability.AVAILABLE),
        )
        val states = mapOf(
            CheckStage.CONTAINER_CONFORMANCE to CheckStageState(CheckRunStatus.COMPLETED),
            CheckStage.STRUCTURE to CheckStageState(CheckRunStatus.COMPLETED),
            CheckStage.MOTION_PHOTO to CheckStageState(CheckRunStatus.COMPLETED),
        )
        val rows = buildContentCheckOverviewRows(
            specs = specs, states = states, type = MediaType.IMAGE, root = root, summary = null,
            ko = true, running = false, fileChanged = false,
        ) { stage ->
            when (stage) {
                CheckStage.CONTAINER_CONFORMANCE -> "규격 통과"
                CheckStage.STRUCTURE -> "경고"
                CheckStage.MOTION_PHOTO -> "실패"
                else -> null
            }
        }

        assertEquals(OverviewRowStatusTone.SUCCESS, rows[0].statusTone)
        assertEquals(OverviewRowStatusTone.SUCCESS, rows[0].verdictTone)

        assertEquals(OverviewRowStatusTone.WARNING, rows[1].statusTone)
        assertEquals(OverviewRowStatusTone.WARNING, rows[1].verdictTone)

        assertEquals(OverviewRowStatusTone.ERROR, rows[2].statusTone)
        assertEquals(OverviewRowStatusTone.ERROR, rows[2].verdictTone)
    }
}

