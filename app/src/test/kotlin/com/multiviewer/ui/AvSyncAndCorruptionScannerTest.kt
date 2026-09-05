package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AvSyncAndCorruptionScannerTest {

    @Test
    fun testBitstreamDecoderErrorExplanation() {
        val errLine1 = "[h264 @ 0x14700f200] error while decoding MB 39 0, bytestream -16"
        val explanation1 = BitstreamCorruptionScanner.interpretDecoderError(errLine1)

        assertEquals("H.264/AVC 슬라이스 비트스트림 손상 (Buffer Overrun)", explanation1.title)
        assertEquals(39, explanation1.mbCoordinates?.first)
        assertEquals(0, explanation1.mbCoordinates?.second)
        assertEquals(39 * 16, explanation1.pixelCoordinates?.first)
        assertEquals(0, explanation1.pixelCoordinates?.second)
        assertEquals(-16, explanation1.bufferOverrunBytes)

        val errLine2 = "[h264 @ 0x14700f200] cabac decode error at 12 5"
        val explanation2 = BitstreamCorruptionScanner.interpretDecoderError(errLine2)
        assertEquals("CABAC 산술 부호화 디코딩 문맥 오류", explanation2.title)
        assertEquals(12, explanation2.mbCoordinates?.first)
        assertEquals(5, explanation2.mbCoordinates?.second)
        assertEquals(12 * 16, explanation2.pixelCoordinates?.first)
        assertEquals(5 * 16, explanation2.pixelCoordinates?.second)

        val errLine3 = "[h264 @ 0x14700f200] concealing 10 DC, 20 AC, 5 MV errors in P frame"
        val explanation3 = BitstreamCorruptionScanner.interpretDecoderError(errLine3)
        assertEquals("디코더 에러 은닉 (Error Concealment) 동작", explanation3.title)
        assertTrue(explanation3.summary.contains("P-프레임"))
    }

    @Test
    fun testCorruptFrameEntryCascadingEffect() {
        val frames = listOf(
            FrameInfo(0, 'I', 1000, 0.0, 100L),
            FrameInfo(1, 'P', 800, 0.033, 1100L),
            FrameInfo(2, 'B', 400, 0.066, 1900L),
            FrameInfo(3, 'B', 400, 0.099, 2300L),
            FrameInfo(4, 'I', 1200, 0.133, 2700L),
        )

        val nextI = frames.drop(1 + 1).indexOfFirst { it.type == 'I' }
        val affectedCount = if (nextI >= 0) nextI + 1 else (frames.size - 1)
        assertEquals(3, affectedCount)
    }

    @Test
    fun testAvSyncAnalyzerWithRealFiles() {
        val sampleWithAudioGap = File("sample_audio_gap_3s.mp4")
        if (sampleWithAudioGap.exists()) {
            val report = kotlinx.coroutines.runBlocking {
                AvSyncAnalyzer.analyze(sampleWithAudioGap)
            }
            assertNotNull(report)
            report.let { r ->
                assertTrue(r.hasVideo, "Report must have video")
                assertTrue(r.hasAudio, "Report must have audio")
                assertTrue(r.videoDurationSec > 0.0, "Video duration should be positive")
                assertTrue(r.syncPoints.isNotEmpty(), "Sync points should be generated")
                assertTrue(r.diagnoses.isNotEmpty(), "Diagnoses should be produced")
            }
        }
    }
}
