package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.parseFile
import com.multiviewer.parser.conformance.analyzeVp9Bitstream
import com.multiviewer.cli.render
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Vp9ContentCheckIntegrationTest {
    private fun fixture(name: String) = File(javaClass.getResource("/vp9-conformance/$name")!!.toURI())

    @Test
    fun defaultVp09IsRunnable() {
        val file = fixture("default-vp09.mp4")
        val root = parseFile(file)
        assertEquals(CheckAvailability.AVAILABLE, vp9CodecAvailability(MediaType.VIDEO, root))
        val rows = contentCheckStages(
            MediaType.VIDEO,
            root,
            false,
            false,
            false,
            codecRunnerAvailable = vp9CodecAvailability(MediaType.VIDEO, root) == CheckAvailability.AVAILABLE,
        )
        assertEquals(
            listOf(CheckStage.CONTAINER_CONFORMANCE, CheckStage.STRUCTURE, CheckStage.CODEC, CheckStage.VISUAL_DECODE),
            rows.filter { it.availability == CheckAvailability.AVAILABLE }.map { it.stage },
        )
    }

    @Test
    fun heicEmbeddedVideoIsExcluded() {
        val root = parseFile(fixture("default-vp09.mp4"))
        assertEquals(CheckAvailability.NOT_APPLICABLE, vp9CodecAvailability(MediaType.IMAGE, root))
    }

    @Test
    fun corruptedConfigurationIsUnsupportedAndPartialScanEvidenceIsBounded() = runBlocking {
        val bad = fixture("malformed-vpcc.mp4")
        assertEquals(CheckAvailability.UNSUPPORTED, vp9CodecAvailability(MediaType.VIDEO, parseFile(bad)))
        val truncated = fixture("truncated-frame.mp4")
        val report = analyzeVp9Bitstream(truncated, parseFile(truncated)) { _, _ -> }
        val findings = vp9BitstreamFindings(report)
        assertTrue(findings.any { it.id == "vp9.frame.framing" && it.offset == 44L })
        assertFalse(report.complete)
        val json = contentCheckWorkflowJson(
            ContentCheckSnapshot(
                null, null, false, null, null, null,
                mediaType = MediaType.VIDEO,
                vp9Bitstream = report,
                stages = mapOf(CheckStage.CODEC to CheckStageState(CheckRunStatus.PARTIAL)),
            ),
            truncated,
        )
        assertContains(json.render(), "\"checkedSamples\"")
    }

    @Test
    fun otherVideoTrackIsNotChecked() = runBlocking {
        val file = fixture("default-vp09.mp4")
        val root = parseFile(file)
        val extra = BoxNode(
            "trak", 0, 8, 8,
            children = listOf(
                BoxNode(
                    "mdia", 0, 8, 8,
                    children = listOf(
                        BoxNode(
                            "hdlr", 0, 8, 8,
                            fields = listOf(com.multiviewer.parser.BoxField("handler_type", "vide", 0, 4)),
                        )
                    ),
                )
            ),
        )
        val modified = root.copy(children = root.children.map { child ->
            if (child.type == "moov") child.copy(children = child.children + extra) else child
        })
        val report = analyzeVp9Bitstream(file, modified) { _, _ -> }
        assertTrue(report.limitations.any { it.contains("other video track") })
    }

    @Test
    fun fragmentedAndEncryptedSamplesAreUnavailableBeforeRun() {
        val root = parseFile(fixture("default-vp09.mp4"))
        val fragment = root.copy(children = root.children + BoxNode("moof", 0, 8, 8))
        assertEquals(CheckAvailability.UNSUPPORTED, vp9CodecAvailability(MediaType.VIDEO, fragment))
        fun BoxNode.encrypt(): BoxNode = copy(children = children.map { child ->
            if (child.type == "vp09") child.copy(children = child.children + BoxNode("sinf", 0, 8, 8))
            else child.encrypt()
        })
        assertEquals(CheckAvailability.UNSUPPORTED, vp9CodecAvailability(MediaType.VIDEO, root.encrypt()))
    }

    @Test
    fun malformedVpcCBoxWarningCannotBeAdvertisedAsRunnable() {
        val root = parseFile(fixture("default-vp09.mp4"))
        fun BoxNode.warnConfig(): BoxNode = copy(children = children.map { child ->
            if (child.type == "vpcC") child.copy(warnings = child.warnings + "vpcC box truncated")
            else child.warnConfig()
        })
        assertEquals(CheckAvailability.UNSUPPORTED, vp9CodecAvailability(MediaType.VIDEO, root.warnConfig()))
    }

    @Test
    fun exportRetainsTotalOccurrenceCountBeyondDisplayedFindings() = runBlocking {
        val file = fixture("many-marker-failures.mp4")
        val report = analyzeVp9Bitstream(file, parseFile(file)) { _, _ -> }
        val json = contentCheckWorkflowJson(
            ContentCheckSnapshot(
                null, null, false, null, null, null,
                mediaType = MediaType.VIDEO,
                vp9Bitstream = report,
            ),
            file,
        ).render()
        assertContains(json, "\"vp9.frame.marker\": 101")
    }
}
