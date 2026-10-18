package com.multiviewer.ui

import com.multiviewer.cli.render
import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.conformance.analyzeVvcBitstream
import com.multiviewer.parser.parseFile
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VvcContentCheckIntegrationTest {
    private fun fixture(name: String) = File(javaClass.getResource("/vvc-conformance/$name")!!.toURI())

    @Test
    fun defaultVvc1IsRunnable() {
        val file = fixture("default-vvc.mp4")
        val root = parseFile(file)
        assertEquals(CheckAvailability.AVAILABLE, vvcCodecAvailability(MediaType.VIDEO, root))
        val rows = contentCheckStages(
            MediaType.VIDEO,
            root,
            false,
            false,
            false,
            codecRunnerAvailable = vvcCodecAvailability(MediaType.VIDEO, root) == CheckAvailability.AVAILABLE,
        )
        assertEquals(
            listOf(CheckStage.STRUCTURE, CheckStage.CODEC, CheckStage.VISUAL_DECODE),
            rows.filter { it.availability == CheckAvailability.AVAILABLE }.map { it.stage },
        )
    }

    @Test
    fun vvcEmbeddedVideoOrImageIsExcluded() {
        val root = parseFile(fixture("default-vvc.mp4"))
        assertEquals(CheckAvailability.NOT_APPLICABLE, vvcCodecAvailability(MediaType.IMAGE, root))
    }

    @Test
    fun corruptedConfigurationIsUnsupportedAndPartialScanEvidenceIsBounded() = runBlocking {
        val bad = fixture("bad-vvcc-box.mp4")
        assertEquals(CheckAvailability.UNSUPPORTED, vvcCodecAvailability(MediaType.VIDEO, parseFile(bad)))
        val truncated = fixture("truncated-sample.mp4")
        val report = analyzeVvcBitstream(truncated, parseFile(truncated)) { _, _ -> }
        val findings = vvcBitstreamFindings(report)
        assertTrue(findings.any { it.id == "vvc.sample.framing" })
        assertFalse(report.complete)
        val json = contentCheckWorkflowJson(
            ContentCheckSnapshot(
                null, null, false, null, null, null,
                mediaType = MediaType.VIDEO,
                vvcBitstream = report,
                stages = mapOf(CheckStage.CODEC to CheckStageState(CheckRunStatus.PARTIAL)),
            ),
            truncated,
        )
        assertContains(json.render(), "\"checkedSamples\"")
    }

    @Test
    fun otherVideoTrackIsNotChecked() = runBlocking {
        val file = fixture("default-vvc.mp4")
        val root = parseFile(file)
        val extra = BoxNode(
            "trak", 0, 8, 8,
            children = listOf(
                BoxNode(
                    "mdia", 0, 8, 8,
                    children = listOf(
                        BoxNode(
                            "hdlr", 0, 8, 8,
                            fields = listOf(BoxField("handler_type", "vide", 0, 4)),
                        )
                    ),
                )
            ),
        )
        val modified = root.copy(children = root.children.map { child ->
            if (child.type == "moov") child.copy(children = child.children + extra) else child
        })
        val report = analyzeVvcBitstream(file, modified) { _, _ -> }
        assertTrue(report.limitations.any { it.contains("other video track") })
    }

    @Test
    fun fragmentedAndEncryptedSamplesAreUnavailableBeforeRun() {
        val root = parseFile(fixture("default-vvc.mp4"))
        val fragment = root.copy(children = root.children + BoxNode("moof", 0, 8, 8))
        assertEquals(CheckAvailability.UNSUPPORTED, vvcCodecAvailability(MediaType.VIDEO, fragment))
        fun BoxNode.encrypt(): BoxNode = copy(children = children.map { child ->
            if (child.type == "vvc1") child.copy(children = child.children + BoxNode("sinf", 0, 8, 8))
            else child.encrypt()
        })
        assertEquals(CheckAvailability.UNSUPPORTED, vvcCodecAvailability(MediaType.VIDEO, root.encrypt()))
    }

    @Test
    fun malformedVvcCBoxWarningCannotBeAdvertisedAsRunnable() {
        val root = parseFile(fixture("default-vvc.mp4"))
        fun BoxNode.warnConfig(): BoxNode = copy(children = children.map { child ->
            if (child.type == "vvcC") child.copy(warnings = child.warnings + "vvcC box truncated")
            else child.warnConfig()
        })
        assertEquals(CheckAvailability.UNSUPPORTED, vvcCodecAvailability(MediaType.VIDEO, root.warnConfig()))
    }

    @Test
    fun exportRetainsTotalOccurrenceCountBeyondDisplayedFindings() = runBlocking {
        val file = fixture("many-nal-failures.mp4")
        val report = analyzeVvcBitstream(file, parseFile(file)) { _, _ -> }
        val json = contentCheckWorkflowJson(
            ContentCheckSnapshot(
                null, null, false, null, null, null,
                mediaType = MediaType.VIDEO,
                vvcBitstream = report,
            ),
            file,
        ).render()
        assertContains(json, "\"vvc.nal.forbidden_zero_bit\": 101")
    }
}
