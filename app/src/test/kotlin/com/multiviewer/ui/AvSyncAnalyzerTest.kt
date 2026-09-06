package com.multiviewer.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AvSyncAnalyzerTest {

    /** Uniform packet run: [count] packets starting at [startPts], spaced [step] s. */
    private fun run(type: String, startPts: Double, step: Double, count: Int): List<StreamPacket> =
        (0 until count).map { i ->
            StreamPacket(
                mediaType = type,
                ptsSeconds = startPts + i * step,
                dtsSeconds = null,
                durationSeconds = step,
                sizeBytes = null,
                pos = null,
            )
        }

    // ---- avSyncErrorModel ----

    @Test
    fun errorModel_perfect_isZeroEverywhere() {
        assertEquals(0.0, avSyncErrorModel(0.0, 0.0, 60.0, 0.0), 1e-9)
        assertEquals(0.0, avSyncErrorModel(0.0, 0.0, 60.0, 45.0), 1e-9)
    }

    @Test
    fun errorModel_constantOffset_isFlat() {
        assertEquals(119.0, avSyncErrorModel(119.0, 0.0, 60.0, 0.0), 1e-9)
        assertEquals(119.0, avSyncErrorModel(119.0, 0.0, 60.0, 60.0), 1e-9)
    }

    @Test
    fun errorModel_lengthMismatch_isLinear() {
        assertEquals(169.0, avSyncErrorModel(0.0, 0.169, 60.0, 60.0), 0.5)
        assertEquals(84.5, avSyncErrorModel(0.0, 0.169, 60.0, 30.0), 0.5)
    }

    @Test
    fun errorModel_zeroTotalDuration_returnsInitialSkew_noDivideByZero() {
        assertEquals(50.0, avSyncErrorModel(50.0, 0.2, 0.0, 10.0), 1e-9)
    }

    // ---- avSyncIsProgressiveDrift ----

    @Test
    fun progressiveDrift_shortClipHighRateButTinyTotal_isNotFlagged() {
        // A perfectly-synced 15 s clip: ~55 ms of AAC encoder padding extrapolates
        // to ~-218 ms/min, but only 55 ms ever accumulates. Not real drift.
        assertFalse(avSyncIsProgressiveDrift(driftRateMsPerMin = -218.0, totalAccumulatedDriftMs = -55.0))
    }

    @Test
    fun progressiveDrift_genuineDrift_isFlagged() {
        // 169 ms accumulated at 169 ms/min over a real minute.
        assertTrue(avSyncIsProgressiveDrift(169.0, 169.0))
    }

    @Test
    fun progressiveDrift_bigOffsetButFlatRate_isNotFlagged() {
        // constant offset, no slope — belongs to the initial-skew check, not drift.
        assertFalse(avSyncIsProgressiveDrift(2.0, 400.0))
    }

    @Test
    fun progressiveDrift_thresholdsAreStrictlyGreaterThan() {
        assertFalse(avSyncIsProgressiveDrift(25.0, 100.0)) // exactly 100 ms total
        assertFalse(avSyncIsProgressiveDrift(20.0, 150.0)) // exactly 20 ms/min
        assertTrue(avSyncIsProgressiveDrift(21.0, 101.0))
    }

    // ---- computeSyncPoints ----

    @Test
    fun syncPoints_cleanAlignedFile_allDeltasNearZero() {
        val v = run("video", 0.0, 1.0 / 30, 300)   // 10 s @ 30 fps
        val a = run("audio", 0.0, 0.021, 476)       // ~10 s
        val pts = computeSyncPoints(v, a, initialSkewMs = 0.0, durationDeltaSec = 0.0, totalDurationSec = 10.0)
        assertTrue(pts.isNotEmpty())
        assertTrue(pts.all { abs(it.deltaMs) < 25.0 }, "max |delta| = ${pts.maxOf { abs(it.deltaMs) }}")
    }

    @Test
    fun syncPoints_constantOffset_allDeltasNearOffset() {
        val v = run("video", 0.12, 1.0 / 30, 300)   // video PTS starts at 0.12
        val a = run("audio", 0.0, 0.021, 476)
        val pts = computeSyncPoints(v, a, initialSkewMs = 120.0, durationDeltaSec = 0.0, totalDurationSec = 10.0)
        assertTrue(pts.all { abs(it.deltaMs - 120.0) < 25.0 }, "deltas: ${pts.map { it.deltaMs.toInt() }}")
    }

    @Test
    fun syncPoints_truncatedAudio_tailIsLinearNotDoubleCounted() {
        val v = run("video", 0.0, 1.0 / 30, 300)     // video 0..10 s
        val a = run("audio", 0.0, 0.021, 466)         // audio ~0..9.8 s
        val pts = computeSyncPoints(v, a, initialSkewMs = 0.0, durationDeltaSec = 0.2, totalDurationSec = 10.0)
        assertTrue(abs(pts.first().deltaMs) < 25.0)
        val last = pts.last().deltaMs
        assertTrue(last in 150.0..260.0, "last delta = $last (expected ~200, NOT ~400 which means the aTarget<=aLast clamp is missing)")
    }

    @Test
    fun syncPoints_uniformDrift_followsLinearBaseline() {
        val v = run("video", 0.0, 1.0 / 30, 300)                 // video 0..10 s
        val a = run("audio", 0.0, 9.83 / 476, 476)               // same count, re-timestamped to 0..9.83
        val pts = computeSyncPoints(v, a, initialSkewMs = 0.0, durationDeltaSec = 0.17, totalDurationSec = 10.0)
        assertTrue(abs(pts.first().deltaMs) < 20.0, "first = ${pts.first().deltaMs}")
        assertTrue(pts.last().deltaMs in 130.0..210.0, "last = ${pts.last().deltaMs}")
        // monotonic-ish ramp: the middle point sits between first and last
        val mid = pts[pts.size / 2].deltaMs
        assertTrue(mid > pts.first().deltaMs && mid < pts.last().deltaMs, "mid = $mid")
    }

    @Test
    fun syncPoints_audioPtsGap_spikesAtTheGap() {
        val v = run("video", 0.0, 1.0 / 30, 300)
        // audio 0..3.0 s, then a 0.5 s hole, then 3.5..10 s
        val a = run("audio", 0.0, 0.021, 143) + run("audio", 3.5, 0.021, 310)
        val pts = computeSyncPoints(v, a, initialSkewMs = 0.0, durationDeltaSec = 0.0, totalDurationSec = 10.0)
        val nearGap = pts.minByOrNull { abs(it.timeSeconds - 3.25) }!!
        val awayFromGap = pts.first { it.timeSeconds > 6.0 }
        assertTrue(abs(nearGap.deltaMs) > 120.0, "at gap: ${nearGap.deltaMs}")
        assertTrue(abs(awayFromGap.deltaMs) < 40.0, "away from gap: ${awayFromGap.deltaMs}")
    }

    @Test
    fun syncPoints_fewerFramesThanSampleCount_onePointPerFrameNoCrash() {
        val v = run("video", 0.0, 1.0 / 30, 10)
        val a = run("audio", 0.0, 0.021, 20)
        val pts = computeSyncPoints(v, a, 0.0, 0.0, 1.0, targetSampleCount = 120)
        assertEquals(10, pts.size)
    }

    @Test
    fun syncPoints_emptyInput_returnsEmpty() {
        assertTrue(computeSyncPoints(emptyList(), run("audio", 0.0, 0.021, 10), 0.0, 0.0, 1.0).isEmpty())
        assertTrue(computeSyncPoints(run("video", 0.0, 0.033, 10), emptyList(), 0.0, 0.0, 1.0).isEmpty())
    }

    @Test
    fun syncPoints_videoFpsNotDividingAudioRate_noGridSawtoothOnACleanFile() {
        // 30 fps video vs ~46.9 packet/s audio (48 kHz AAC): the grids beat, so
        // nearest-packet matching would zigzag +-10 ms end to end. The deadband
        // must flatten that to the baseline (here a constant +21 ms skew).
        val v = run("video", 0.0, 1.0 / 30, 450)              // 15 s
        val a = run("audio", 0.0, 1024.0 / 48000, 703)        // ~15 s
        val pts = computeSyncPoints(v, a, initialSkewMs = 21.0, durationDeltaSec = 0.0, totalDurationSec = 15.0)
        assertTrue(
            pts.all { abs(it.deltaMs - 21.0) < 5.0 },
            "sawtooth not suppressed: ${pts.map { it.deltaMs.toInt() }.distinct().sorted()}",
        )
    }
}
