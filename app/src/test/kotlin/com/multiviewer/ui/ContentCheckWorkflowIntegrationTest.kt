package com.multiviewer.ui

import com.multiviewer.cli.buildCheckJson
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.conformance.ConformanceRule
import com.multiviewer.parser.conformance.ConformanceFinding
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ContentCheckWorkflowIntegrationTest {
    @Test
    fun partialRunExportAndPromptDoNotClaimPass() {
        val file = File("/private/engineering/sample.mp4")
        val snapshot = ContentCheckSnapshot(null, null, false, null, null, null,
            mediaType = MediaType.VIDEO,
            conformance = ConformanceReport("MP4", "unknown", emptyList()),
            stages = mapOf(
                CheckStage.STRUCTURE to CheckStageState(CheckRunStatus.COMPLETED),
                CheckStage.CODEC to CheckStageState(CheckRunStatus.CANCELLED, reason = "cancelled on ${file.absolutePath}"),
                CheckStage.VISUAL_DECODE to CheckStageState(CheckRunStatus.QUEUED),
            ))
        val json = buildCheckJson(file, emptyList(), workflow = contentCheckWorkflowJson(snapshot, file))
        assertContains(json, "\"warningCount\": 0")
        assertContains(json, "\"workflow\"")
        assertContains(json, "\"version\": 1")
        assertContains(json, "\"status\": \"CANCELLED\"")
        assertFalse(json.contains(file.absolutePath))
        val prompt = contentCheckPromptSection(snapshot, file)
        assertContains(prompt, "CODEC: CANCELLED")
        assertContains(prompt, "VISUAL_DECODE: QUEUED")
        assertFalse(prompt.contains("규격 검사 (MP4): PASS"))
        assertFalse(prompt.contains(file.absolutePath))
        assertEquals(ConformanceStatus.NOT_CHECKED, effectiveConformanceStatus(snapshot))
    }

    @Test
    fun completedStructureWithoutCodecCoverageIsStillNotCheckedForVideo() {
        val snapshot = ContentCheckSnapshot(null, null, false, null, null, null,
            mediaType = MediaType.VIDEO,
            conformance = ConformanceReport("MP4", "unknown", emptyList()),
            stages = mapOf(CheckStage.STRUCTURE to CheckStageState(CheckRunStatus.COMPLETED)))
        assertEquals(ConformanceStatus.NOT_CHECKED, effectiveConformanceStatus(snapshot))
    }

    @Test
    fun structuralObservationsCannotPromoteOverallConformance() {
        val report = ConformanceReport("JPEG", "unknown", listOf(ConformanceFinding(
            ConformanceRule("jpeg.soi", "SOI", "unwrapMedia structural check", null, "marker observed"),
            ConformanceStatus.PASS, listOf("present"), emptyList(),
        )))
        val snapshot = ContentCheckSnapshot(null, null, false, null, null, null,
            mediaType = MediaType.IMAGE, conformance = report,
            stages = mapOf(CheckStage.STRUCTURE to CheckStageState(CheckRunStatus.COMPLETED)))
        assertEquals(ConformanceStatus.NOT_CHECKED, effectiveConformanceStatus(snapshot))
    }
}
