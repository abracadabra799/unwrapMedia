package com.multiviewer.ui

import com.multiviewer.parser.conformance.BitstreamCodec
import com.multiviewer.parser.conformance.CodecBitstreamReport
import com.multiviewer.parser.conformance.analyzeCodecBitstream
import com.multiviewer.parser.parseFile
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun report(codec: BitstreamCodec, complete: Boolean = true) =
        CodecBitstreamReport(codec, emptyList(), 1, 1, emptyMap(), emptyList(), "track", complete)

    @Test fun unsupportedVideoCodecLeavesOnlyTheAudioCheckAndIsAGap() {
        val root = parseFile(fixture("content-check-codecs/mpeg4-aac.mp4"))
        val plan = codecBitstreamPlan(MediaType.VIDEO, root)
        assertEquals(listOf(BitstreamCodec.AAC), plan)
        val gaps = codecCoverageGaps(MediaType.VIDEO, root, plan, listOf(report(BitstreamCodec.AAC)))
        assertEquals(1, gaps.size, gaps.toString())
        assertTrue(gaps.single().contains("영상"), gaps.toString())
    }

    @Test fun unsupportedAudioCodecOnAVideoIsAGap() {
        val root = parseFile(fixture("content-check-codecs/hevc-ac3.mp4"))
        val plan = codecBitstreamPlan(MediaType.VIDEO, root)
        assertEquals(listOf(BitstreamCodec.HEVC), plan)
        val gaps = codecCoverageGaps(MediaType.VIDEO, root, plan, listOf(report(BitstreamCodec.HEVC)))
        assertEquals(1, gaps.size, gaps.toString())
        assertTrue(gaps.single().contains("오디오"), gaps.toString())
    }

    @Test fun fullyCheckedVideoAndAudioHaveNoGap() {
        val root = parseFile(fixture("content-check-codecs/hevc-aac.mp4"))
        val plan = codecBitstreamPlan(MediaType.VIDEO, root)
        assertEquals(emptyList(), codecCoverageGaps(MediaType.VIDEO, root, plan, plan.map { report(it) }))
        val videoOnly = parseFile(fixture("hevc-conformance/default-hev1.mp4"))
        assertEquals(emptyList(), codecCoverageGaps(MediaType.VIDEO, videoOnly, listOf(BitstreamCodec.HEVC), listOf(report(BitstreamCodec.HEVC))))
    }

    @Test fun aPlannedCodecThatNeverRanAndPartialCoverageAreBothReported() {
        val root = parseFile(fixture("content-check-codecs/hevc-aac.mp4"))
        val plan = codecBitstreamPlan(MediaType.VIDEO, root)
        val gaps = codecCoverageGaps(MediaType.VIDEO, root, plan, listOf(report(BitstreamCodec.HEVC, complete = false)))
        assertEquals(2, gaps.size, gaps.toString())
        assertTrue(gaps.any { it.contains("AAC") }, gaps.toString())
        assertTrue(gaps.any { it.contains("일부 샘플") }, gaps.toString())
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
        val gapText = contentCheckPromptSection(snapshot.copy(codecBitstreams = reports.take(1),
            codecGaps = codecCoverageGaps(MediaType.VIDEO, root, reports.map { it.codec }, reports.take(1))), file)
        assertTrue(gapText.contains("- 코덱 바이트 검사 범위 밖: AAC check did not run / AAC 검사 미실행"), gapText)
    }
}
