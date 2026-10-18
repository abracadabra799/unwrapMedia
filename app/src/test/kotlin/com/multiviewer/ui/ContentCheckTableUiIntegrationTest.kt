package com.multiviewer.ui

import com.multiviewer.cli.buildCheckJson
import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceRule
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.integrity.CheckStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContentCheckTableUiIntegrationTest {
    private fun sampleVideoRoot(): BoxNode = BoxNode("root", 0, 0, 1000, children = listOf(
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
                                    BoxField("width", "3840", 88, 2),
                                    BoxField("height", "2160", 90, 2),
                                ))
                            ))
                        ))
                    ))
                ))
            ))
        ))
    ))

    @Test
    fun tableRowSelectionTransitionsToCorrespondingDetailTab() {
        val type = MediaType.VIDEO
        assertEquals(ContentTab.CONFORMANCE, checkStageDetailTab(CheckStage.CONTAINER_CONFORMANCE, type))
        assertEquals(ContentTab.STRUCTURE, checkStageDetailTab(CheckStage.STRUCTURE, type))
        assertEquals(ContentTab.CODEC, checkStageDetailTab(CheckStage.CODEC, type))
        assertEquals(ContentTab.VIDEO_DECODE, checkStageDetailTab(CheckStage.VISUAL_DECODE, type))
        assertEquals(ContentTab.AUDIO, checkStageDetailTab(CheckStage.AUDIO_DECODE, type))

        val imgType = MediaType.IMAGE
        assertEquals(ContentTab.IMAGE_DECODE, checkStageDetailTab(CheckStage.VISUAL_DECODE, imgType))
        assertEquals(ContentTab.MOTION_PHOTO, checkStageDetailTab(CheckStage.MOTION_PHOTO, imgType))
        assertEquals(ContentTab.GAINMAP, checkStageDetailTab(CheckStage.GAINMAP, imgType))
    }

    @Test
    fun containerConformanceFindingsRenderUniformlyWithTabularProperties() {
        val report = ConformanceReport(
            format = "MP4",
            profile = "ISO-BMFF",
            findings = listOf(
                ConformanceFinding(
                    rule = ConformanceRule("isobmff.ftyp", "File Type Box", "ISO/IEC 14496-12", "Clause 4.3", "root"),
                    status = ConformanceStatus.PASS,
                    details = listOf("ftyp box valid"),
                    ranges = listOf(0L..31L),
                ),
                ConformanceFinding(
                    rule = ConformanceRule("isobmff.moov", "Movie Box", "ISO/IEC 14496-12", "Clause 4.4", "root"),
                    status = ConformanceStatus.FAIL,
                    details = listOf("moov box missing"),
                    ranges = emptyList(),
                ),
            ),
        )

        val findings = conformanceFindings(report)
        assertEquals(2, findings.size)

        val f0 = findings[0]
        assertEquals("isobmff.ftyp", f0.id)
        assertEquals(FindingStage.CONFORMANCE, f0.stage)
        assertEquals(CheckStatus.PASS, f0.status)
        assertEquals(0L, f0.offset)
        assertEquals(32L, f0.length)
        assertTrue(f0.verifiedFacts.any { it.contains("ISO/IEC 14496-12") })

        val f1 = findings[1]
        assertEquals("isobmff.moov", f1.id)
        assertEquals(CheckStatus.FAIL, f1.status)
        assertEquals(null, f1.offset)
    }

    @Test
    fun fileChangeInvalidatesAllOverviewRowsAndDisablesActions() {
        val root = sampleVideoRoot()
        val specs = contentCheckStages(MediaType.VIDEO, root, false, false, false, codecRunnerAvailable = true)
        val states = mapOf(
            CheckStage.CONTAINER_CONFORMANCE to CheckStageState(CheckRunStatus.COMPLETED),
            CheckStage.STRUCTURE to CheckStageState(CheckRunStatus.COMPLETED),
        )

        // After fileChanged is detected:
        val rows = buildContentCheckOverviewRows(
            specs = specs,
            states = states,
            type = MediaType.VIDEO,
            root = root,
            summary = null,
            ko = true,
            running = false,
            fileChanged = true, // File changed!
        ) { null }

        // All action buttons must be disabled
        assertTrue(rows.all { !it.canAction })
    }

    @Test
    fun workflowJsonAndSnapshotPreserveBackwardCompatibility() {
        val file = File("/tmp/test-video.mp4")
        val snapshot = ContentCheckSnapshot(
            structure = null,
            imageDecode = null,
            motionDetected = false,
            motion = null,
            motionError = null,
            video = null,
            mediaType = MediaType.VIDEO,
            stages = mapOf(
                CheckStage.CONTAINER_CONFORMANCE to CheckStageState(CheckRunStatus.COMPLETED),
                CheckStage.STRUCTURE to CheckStageState(CheckRunStatus.COMPLETED),
                CheckStage.CODEC to CheckStageState(CheckRunStatus.COMPLETED),
            ),
            conformance = ConformanceReport("MP4", "ISO-BMFF", emptyList()),
        )

        val json = buildCheckJson(file, emptyList(), workflow = contentCheckWorkflowJson(snapshot, file))
        assertContains(json, "\"workflow\"")
        assertContains(json, "\"CONTAINER_CONFORMANCE\"")
        assertContains(json, "\"STRUCTURE\"")
        assertContains(json, "\"CODEC\"")
    }
}
