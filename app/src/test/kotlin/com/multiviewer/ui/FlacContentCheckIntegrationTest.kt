package com.multiviewer.ui

import com.multiviewer.cli.render
import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.conformance.analyzeFlacBitstream
import com.multiviewer.parser.parseFile
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlacContentCheckIntegrationTest {
    private fun fixture(name: String) = File(javaClass.getResource("/flac-conformance/$name")!!.toURI())

    @Test
    fun defaultFlacIsRunnableInAudioMediaType() {
        val file = fixture("default-flac.mp4")
        val root = parseFile(file)
        assertEquals(CheckAvailability.AVAILABLE, flacCodecAvailability(MediaType.AUDIO, root))
        val rows = contentCheckStages(
            MediaType.AUDIO,
            root,
            false,
            false,
            true,
            codecRunnerAvailable = flacCodecAvailability(MediaType.AUDIO, root) == CheckAvailability.AVAILABLE,
            flacCodecAvailable = flacCodecAvailability(MediaType.AUDIO, root) == CheckAvailability.AVAILABLE,
        )
        assertTrue(rows.any { it.stage == CheckStage.CODEC && it.availability == CheckAvailability.AVAILABLE })
    }

    @Test
    fun imageMediaTypeIsExcluded() {
        val root = parseFile(fixture("default-flac.mp4"))
        assertEquals(CheckAvailability.NOT_APPLICABLE, flacCodecAvailability(MediaType.IMAGE, root))
    }

    @Test
    fun corruptedConfigurationIsUnsupportedAndPartialScanEvidenceIsBounded() = runBlocking {
        val truncated = fixture("truncated-sample.mp4")
        val report = analyzeFlacBitstream(truncated, parseFile(truncated)) { _, _ -> }
        val findings = codecBitstreamFindings(report)
        assertTrue(findings.any { it.id == "flac.sample.framing" })
        assertFalse(report.complete)
        val json = contentCheckWorkflowJson(
            ContentCheckSnapshot(
                null, null, false, null, null, null,
                mediaType = MediaType.AUDIO,
                codecBitstreams = listOf(report),
                stages = mapOf(CheckStage.CODEC to CheckStageState(CheckRunStatus.PARTIAL)),
            ),
            truncated,
        )
        assertContains(json.render(), "\"checkedSamples\"")
    }

    @Test
    fun otherAudioTrackIsNotChecked() = runBlocking {
        val file = fixture("default-flac.mp4")
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
        val report = analyzeFlacBitstream(file, modified) { _, _ -> }
        assertTrue(report.limitations.any { it.contains("other audio track") })
    }

    @Test
    fun fragmentedAndEncryptedSamplesAreUnavailableBeforeRun() {
        val root = parseFile(fixture("default-flac.mp4"))
        val fragment = root.copy(children = root.children + BoxNode("moof", 0, 8, 8))
        assertEquals(CheckAvailability.UNSUPPORTED, flacCodecAvailability(MediaType.AUDIO, fragment))
        fun BoxNode.encrypt(): BoxNode = copy(children = children.map { child ->
            if (child.type == "fLaC") child.copy(children = child.children + BoxNode("sinf", 0, 8, 8))
            else child.encrypt()
        })
        assertEquals(CheckAvailability.UNSUPPORTED, flacCodecAvailability(MediaType.AUDIO, root.encrypt()))
    }

    @Test
    fun malformedDflaBoxWarningCannotBeAdvertisedAsRunnable() {
        val root = parseFile(fixture("default-flac.mp4"))
        fun BoxNode.warnConfig(): BoxNode = copy(children = children.map { child ->
            if (child.type == "dfLa") child.copy(warnings = child.warnings + "dfLa box truncated")
            else child.warnConfig()
        })
        assertEquals(CheckAvailability.UNSUPPORTED, flacCodecAvailability(MediaType.AUDIO, root.warnConfig()))
    }

    @Test
    fun exportRetainsTotalOccurrenceCountBeyondDisplayedFindings() = runBlocking {
        val file = fixture("many-sync-failures.mp4")
        val report = analyzeFlacBitstream(file, parseFile(file)) { _, _ -> }
        val json = contentCheckWorkflowJson(
            ContentCheckSnapshot(
                null, null, false, null, null, null,
                mediaType = MediaType.AUDIO,
                codecBitstreams = listOf(report),
            ),
            file,
        ).render()
        assertContains(json, "\"flac.frame.header\": 101")
    }
}
