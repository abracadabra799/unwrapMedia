package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.parseFile
import com.multiviewer.parser.conformance.analyzeHevcBitstream
import com.multiviewer.cli.render
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HevcContentCheckIntegrationTest {
    private fun fixture(name: String) = File(javaClass.getResource("/hevc-conformance/$name")!!.toURI())

    @Test fun defaultHev1AndExplicitHvc1AreRunnable() {
        for (name in listOf("default-hev1.mp4", "explicit-hvc1.mp4")) {
            val file = fixture(name)
            val root = parseFile(file)
            assertEquals(CheckAvailability.AVAILABLE, hevcCodecAvailability(MediaType.VIDEO, root))
            val rows = contentCheckStages(MediaType.VIDEO, root, false, false, false,
                hevcCodecAvailability(MediaType.VIDEO, root) == CheckAvailability.AVAILABLE)
            assertEquals(listOf(CheckStage.STRUCTURE, CheckStage.CODEC, CheckStage.VISUAL_DECODE),
                rows.filter { it.availability == CheckAvailability.AVAILABLE }.map { it.stage })
        }
    }

    @Test fun heicEmbeddedVideoIsExcluded() {
        val root = parseFile(fixture("default-hev1.mp4"))
        assertEquals(CheckAvailability.NOT_APPLICABLE, hevcCodecAvailability(MediaType.IMAGE, root))
    }

    @Test fun corruptedConfigurationIsUnsupportedAndPartialScanEvidenceIsBounded() = runBlocking {
        val bad = fixture("malformed-hvcc.mp4")
        assertEquals(CheckAvailability.UNSUPPORTED, hevcCodecAvailability(MediaType.VIDEO, parseFile(bad)))
        val truncated = fixture("truncated-prefix.mp4")
        val report = analyzeHevcBitstream(truncated, parseFile(truncated)) { _, _ -> }
        val findings = hevcBitstreamFindings(report)
        assertTrue(findings.any { it.id == "hevc.nal.framing" && it.offset == 44L })
        assertFalse(report.complete)
        val json = contentCheckWorkflowJson(ContentCheckSnapshot(null, null, false, null, null, null,
            mediaType = MediaType.VIDEO, hevcBitstream = report,
            stages = mapOf(CheckStage.CODEC to CheckStageState(CheckRunStatus.PARTIAL))), truncated)
        assertContains(json.render(), "\"checkedSamples\"")
    }

    @Test fun otherVideoTrackIsNotChecked() = runBlocking {
        val file = fixture("default-hev1.mp4")
        val root = parseFile(file)
        val extra = BoxNode("trak", 0, 8, 8,
            children = listOf(BoxNode("mdia", 0, 8, 8, children = listOf(BoxNode("hdlr", 0, 8, 8,
                fields = listOf(com.multiviewer.parser.BoxField("handler_type", "vide", 0, 4)))))))
        val modified = root.copy(children = root.children.map { child ->
            if (child.type == "moov") child.copy(children = child.children + extra) else child
        })
        val report = analyzeHevcBitstream(file, modified) { _, _ -> }
        assertTrue(report.limitations.any { it.contains("other video track") })
    }

    @Test fun fragmentedAndEncryptedSamplesAreUnavailableBeforeRun() {
        val root = parseFile(fixture("default-hev1.mp4"))
        val fragment = root.copy(children = root.children + BoxNode("moof", 0, 8, 8))
        assertEquals(CheckAvailability.UNSUPPORTED, hevcCodecAvailability(MediaType.VIDEO, fragment))
        fun BoxNode.encrypt(): BoxNode = copy(children = children.map { child ->
            if (child.type == "hev1") child.copy(children = child.children + BoxNode("sinf", 0, 8, 8))
            else child.encrypt()
        })
        assertEquals(CheckAvailability.UNSUPPORTED, hevcCodecAvailability(MediaType.VIDEO, root.encrypt()))
    }

    @Test fun malformedHvcCArrayWarningCannotBeAdvertisedAsRunnable() {
        val root = parseFile(fixture("default-hev1.mp4"))
        fun BoxNode.warnConfig(): BoxNode = copy(children = children.map { child ->
            if (child.type == "hvcC") child.copy(warnings = child.warnings + "Declared 255 NAL arrays but only found 3")
            else child.warnConfig()
        })
        assertEquals(CheckAvailability.UNSUPPORTED, hevcCodecAvailability(MediaType.VIDEO, root.warnConfig()))
    }

    @Test fun exportRetainsTotalOccurrenceCountBeyondDisplayedFindings() = runBlocking {
        val file = fixture("many-forbidden-headers.mp4")
        val report = analyzeHevcBitstream(file, parseFile(file)) { _, _ -> }
        val json = contentCheckWorkflowJson(ContentCheckSnapshot(null, null, false, null, null, null,
            mediaType = MediaType.VIDEO, hevcBitstream = report), file).render()
        assertContains(json, "\"hevc.nal.forbidden_zero_bit\": 101")
    }
}
