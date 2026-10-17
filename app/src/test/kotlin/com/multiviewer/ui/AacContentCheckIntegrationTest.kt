package com.multiviewer.ui

import com.multiviewer.cli.render
import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.conformance.analyzeAacBitstream
import com.multiviewer.parser.parseFile
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AacContentCheckIntegrationTest {
    private fun fixture(name: String) = File(javaClass.getResource("/aac-conformance/$name")!!.toURI())

    @Test
    fun defaultMp4aIsRunnableInAudioMediaType() {
        val file = fixture("default-mp4a.m4a")
        val root = parseFile(file)
        assertEquals(CheckAvailability.AVAILABLE, aacCodecAvailability(MediaType.AUDIO, root))
        val rows = contentCheckStages(
            MediaType.AUDIO,
            root,
            false,
            false,
            true,
            codecRunnerAvailable = aacCodecAvailability(MediaType.AUDIO, root) == CheckAvailability.AVAILABLE,
            aacCodecAvailable = aacCodecAvailability(MediaType.AUDIO, root) == CheckAvailability.AVAILABLE,
        )
        assertTrue(rows.any { it.stage == CheckStage.CODEC && it.availability == CheckAvailability.AVAILABLE })
    }

    @Test
    fun imageMediaTypeIsExcluded() {
        val root = parseFile(fixture("default-mp4a.m4a"))
        assertEquals(CheckAvailability.NOT_APPLICABLE, aacCodecAvailability(MediaType.IMAGE, root))
    }

    @Test
    fun corruptedConfigurationIsUnsupportedAndPartialScanEvidenceIsBounded() = runBlocking {
        val truncated = fixture("truncated-sample.m4a")
        val report = analyzeAacBitstream(truncated, parseFile(truncated)) { _, _ -> }
        val findings = aacBitstreamFindings(report)
        assertTrue(findings.any { it.id == "aac.sample.framing" })
        assertFalse(report.complete)
        val json = contentCheckWorkflowJson(
            ContentCheckSnapshot(
                null, null, false, null, null, null,
                mediaType = MediaType.AUDIO,
                aacBitstream = report,
                stages = mapOf(CheckStage.CODEC to CheckStageState(CheckRunStatus.PARTIAL)),
            ),
            truncated,
        )
        assertContains(json.render(), "\"checkedSamples\"")
    }

    @Test
    fun otherAudioTrackIsNotChecked() = runBlocking {
        val file = fixture("default-mp4a.m4a")
        val root = parseFile(file)
        val extra = BoxNode(
            "trak", 0, 8, 8,
            children = listOf(
                BoxNode(
                    "mdia", 0, 8, 8,
                    children = listOf(
                        BoxNode(
                            "hdlr", 0, 8, 8,
                            fields = listOf(BoxField("handler_type", "soun", 0, 4)),
                        )
                    ),
                )
            ),
        )
        val modified = root.copy(children = root.children.map { child ->
            if (child.type == "moov") child.copy(children = child.children + extra) else child
        })
        val report = analyzeAacBitstream(file, modified) { _, _ -> }
        assertTrue(report.limitations.any { it.contains("other audio track") })
    }

    @Test
    fun fragmentedAndEncryptedSamplesAreUnavailableBeforeRun() {
        val root = parseFile(fixture("default-mp4a.m4a"))
        val fragment = root.copy(children = root.children + BoxNode("moof", 0, 8, 8))
        assertEquals(CheckAvailability.UNSUPPORTED, aacCodecAvailability(MediaType.AUDIO, fragment))
        fun BoxNode.encrypt(): BoxNode = copy(children = children.map { child ->
            if (child.type == "mp4a") child.copy(children = child.children + BoxNode("sinf", 0, 8, 8))
            else child.encrypt()
        })
        assertEquals(CheckAvailability.UNSUPPORTED, aacCodecAvailability(MediaType.AUDIO, root.encrypt()))
    }

    @Test
    fun malformedEsdsBoxWarningCannotBeAdvertisedAsRunnable() {
        val root = parseFile(fixture("default-mp4a.m4a"))
        fun BoxNode.warnConfig(): BoxNode = copy(children = children.map { child ->
            if (child.type == "esds") child.copy(warnings = child.warnings + "esds box truncated")
            else child.warnConfig()
        })
        assertEquals(CheckAvailability.UNSUPPORTED, aacCodecAvailability(MediaType.AUDIO, root.warnConfig()))
    }

    @Test
    fun exportRetainsTotalOccurrenceCountBeyondDisplayedFindings() = runBlocking {
        val file = fixture("many-adts-failures.m4a")
        val report = analyzeAacBitstream(file, parseFile(file)) { _, _ -> }
        val json = contentCheckWorkflowJson(
            ContentCheckSnapshot(
                null, null, false, null, null, null,
                mediaType = MediaType.AUDIO,
                aacBitstream = report,
            ),
            file,
        ).render()
        assertContains(json, "\"aac.frame.adts_guard\": 101")
    }
}
