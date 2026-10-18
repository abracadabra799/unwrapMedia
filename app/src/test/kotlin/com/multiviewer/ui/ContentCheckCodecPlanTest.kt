package com.multiviewer.ui

import com.multiviewer.parser.conformance.BitstreamCodec
import com.multiviewer.parser.conformance.analyzeCodecBitstream
import com.multiviewer.parser.parseFile
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real video+audio MP4s (ffmpeg defaults, see docs/validation/content-check-codec-fixtures). */
class ContentCheckCodecPlanTest {
    private fun fixture(path: String) = File(javaClass.getResource("/$path")!!.toURI())

    @Test fun videoWithAacChecksTheVideoCodecAndThenTheAudioCodec() {
        assertEquals(listOf(BitstreamCodec.HEVC, BitstreamCodec.AAC),
            codecBitstreamPlan(MediaType.VIDEO, parseFile(fixture("content-check-codecs/hevc-aac.mp4"))))
        assertEquals(listOf(BitstreamCodec.AVC, BitstreamCodec.AAC),
            codecBitstreamPlan(MediaType.VIDEO, parseFile(fixture("content-check-codecs/avc-aac.mp4"))))
    }

    @Test fun videoOnlyAndAudioOnlyFilesCheckTheirOneCodec() {
        assertEquals(listOf(BitstreamCodec.HEVC),
            codecBitstreamPlan(MediaType.VIDEO, parseFile(fixture("hevc-conformance/default-hev1.mp4"))))
        assertEquals(listOf(BitstreamCodec.AAC),
            codecBitstreamPlan(MediaType.AUDIO, parseFile(fixture("aac-conformance/default-mp4a.m4a"))))
    }

    @Test fun unsupportedVideoCodecLeavesOnlyTheAudioCheckAndIsFlaggedAsAGap() {
        val root = parseFile(fixture("content-check-codecs/mpeg4-aac.mp4"))
        val plan = codecBitstreamPlan(MediaType.VIDEO, root)
        assertEquals(listOf(BitstreamCodec.AAC), plan)
        assertTrue(videoCodecUnchecked(MediaType.VIDEO, plan))
        assertFalse(videoCodecUnchecked(MediaType.AUDIO, listOf(BitstreamCodec.AAC)))
        assertFalse(videoCodecUnchecked(MediaType.VIDEO, listOf(BitstreamCodec.HEVC, BitstreamCodec.AAC)))
    }

    @Test fun imagesHaveNoCodecByteCheck() {
        assertEquals(emptyList(), codecBitstreamPlan(MediaType.IMAGE, parseFile(fixture("hevc-conformance/default-hev1.mp4"))))
    }

    @Test fun bothTracksOfARealFileAreByteChecked() = runBlocking {
        val file = fixture("content-check-codecs/hevc-aac.mp4")
        val root = parseFile(file)
        val reports = codecBitstreamPlan(MediaType.VIDEO, root).map { analyzeCodecBitstream(it, file, root) { _, _ -> } }
        assertEquals(listOf(BitstreamCodec.HEVC, BitstreamCodec.AAC), reports.map { it.codec })
        for (r in reports) {
            assertTrue(r.checkedSamples > 0 && r.checkedSamples == r.indexedSamples, "${r.codec}: ${r.checkedSamples}/${r.indexedSamples}")
            assertTrue(r.complete, "${r.codec} ${r.limitations}")
        }
        val snapshot = ContentCheckSnapshot(null, null, false, null, null, null, MediaType.VIDEO, codecBitstreams = reports)
        val text = contentCheckPromptSection(snapshot, file)
        assertTrue(text.contains("- HEVC 바이트 검사: "), text)
        assertTrue(text.contains("- AAC 바이트 검사: "), text)
    }
}
