package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.parseFile
import com.multiviewer.parser.conformance.analyzeAv1Bitstream
import com.multiviewer.cli.render
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Av1ContentCheckIntegrationTest {
    private fun fixture(name: String) = File(javaClass.getResource("/av1-conformance/$name")!!.toURI())

    @Test
    fun defaultAv01IsRunnable() {
        val file = fixture("default-av01.mp4")
        val root = parseFile(file)
        assertEquals(CheckAvailability.AVAILABLE, av1CodecAvailability(MediaType.VIDEO, root))
        val rows = contentCheckStages(
            MediaType.VIDEO,
            root,
            false,
            false,
            false,
            codecRunnerAvailable = av1CodecAvailability(MediaType.VIDEO, root) == CheckAvailability.AVAILABLE,
        )
        assertEquals(
            listOf(CheckStage.CONTAINER_CONFORMANCE, CheckStage.STRUCTURE, CheckStage.CODEC, CheckStage.VISUAL_DECODE),
            rows.filter { it.availability == CheckAvailability.AVAILABLE }.map { it.stage },
        )
    }

    @Test
    fun heicEmbeddedVideoIsExcluded() {
        val root = parseFile(fixture("default-av01.mp4"))
        assertEquals(CheckAvailability.NOT_APPLICABLE, av1CodecAvailability(MediaType.IMAGE, root))
    }

    @Test
    fun corruptedConfigurationIsUnsupportedAndPartialScanEvidenceIsBounded() = runBlocking {
        val bad = fixture("malformed-av1c.mp4")
        assertEquals(CheckAvailability.UNSUPPORTED, av1CodecAvailability(MediaType.VIDEO, parseFile(bad)))
        val truncated = fixture("truncated-obu.mp4")
        val report = analyzeAv1Bitstream(truncated, parseFile(truncated)) { _, _ -> }
        val findings = codecBitstreamFindings(report)
        assertTrue(findings.any { it.id == "av1.obu.framing" && it.offset == 61L })
        assertFalse(report.complete)
        val json = contentCheckWorkflowJson(
            ContentCheckSnapshot(
                null, null, false, null, null, null,
                mediaType = MediaType.VIDEO,
                codecBitstreams = listOf(report),
                stages = mapOf(CheckStage.CODEC to CheckStageState(CheckRunStatus.PARTIAL)),
            ),
            truncated,
        )
        assertContains(json.render(), "\"checkedSamples\"")
    }

    @Test
    fun otherVideoTrackIsNotChecked() = runBlocking {
        val file = fixture("default-av01.mp4")
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
        val report = analyzeAv1Bitstream(file, modified) { _, _ -> }
        assertTrue(report.limitations.any { it.contains("other video track") })
    }

    @Test
    fun fragmentedAndEncryptedSamplesAreUnavailableBeforeRun() {
        val root = parseFile(fixture("default-av01.mp4"))
        val fragment = root.copy(children = root.children + BoxNode("moof", 0, 8, 8))
        assertEquals(CheckAvailability.UNSUPPORTED, av1CodecAvailability(MediaType.VIDEO, fragment))
        fun BoxNode.encrypt(): BoxNode = copy(children = children.map { child ->
            if (child.type == "av01") child.copy(children = child.children + BoxNode("sinf", 0, 8, 8))
            else child.encrypt()
        })
        assertEquals(CheckAvailability.UNSUPPORTED, av1CodecAvailability(MediaType.VIDEO, root.encrypt()))
    }

    @Test
    fun malformedAv1CBoxWarningCannotBeAdvertisedAsRunnable() {
        val root = parseFile(fixture("default-av01.mp4"))
        fun BoxNode.warnConfig(): BoxNode = copy(children = children.map { child ->
            if (child.type == "av1C") child.copy(warnings = child.warnings + "av1C box truncated")
            else child.warnConfig()
        })
        assertEquals(CheckAvailability.UNSUPPORTED, av1CodecAvailability(MediaType.VIDEO, root.warnConfig()))
    }

    @Test
    fun exportRetainsTotalOccurrenceCountBeyondDisplayedFindings() = runBlocking {
        val file = fixture("many-forbidden-headers.mp4")
        val report = analyzeAv1Bitstream(file, parseFile(file)) { _, _ -> }
        val json = contentCheckWorkflowJson(
            ContentCheckSnapshot(
                null, null, false, null, null, null,
                mediaType = MediaType.VIDEO,
                codecBitstreams = listOf(report),
            ),
            file,
        ).render()
        assertContains(json, "\"av1.obu.forbidden_bit\": 101")
    }
}
