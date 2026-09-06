package com.multiviewer.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class AvSyncVisualizationTest {

    private fun report(
        points: List<SyncPoint>,
        avgSkewMs: Double = points.map { it.deltaMs }.average(),
        driftRateMsPerMin: Double = 0.0,
        videoDurationSec: Double = points.maxOfOrNull { it.timeSeconds } ?: 0.0,
    ) = AvSyncReport(
        file = File("t.mp4"),
        hasVideo = true,
        hasAudio = true,
        videoDurationSec = videoDurationSec,
        audioDurationSec = videoDurationSec,
        durationDeltaSec = 0.0,
        initialSkewMs = points.firstOrNull()?.deltaMs ?: 0.0,
        maxSkewMs = points.maxOfOrNull { it.deltaMs } ?: 0.0,
        minSkewMs = points.minOfOrNull { it.deltaMs } ?: 0.0,
        avgSkewMs = avgSkewMs,
        driftRateMsPerMin = driftRateMsPerMin,
        syncPoints = points,
        diagnoses = emptyList(),
        overallSeverity = SyncSeverity.PASS,
    )

    private fun pts(vararg pairs: Pair<Double, Double>) = pairs.map { (t, d) ->
        SyncPoint(timeSeconds = t, videoPts = t, audioPts = t - d / 1000.0, deltaMs = d, videoFrameIndex = 0, audioPacketIndex = 0)
    }

    @Test
    fun formatMinSec_formatsAndClampsNegative() {
        assertEquals("0:00", formatMinSec(0.0))
        assertEquals("1:05", formatMinSec(65.4))
        assertEquals("59:59", formatMinSec(3599.9))
        assertEquals("0:00", formatMinSec(-3.0))
    }

    @Test
    fun verdict_allWithinComfort_saysGood() {
        val v = avSyncVerdict(report(pts(0.0 to 10.0, 5.0 to -20.0, 10.0 to 35.0)))
        assertTrue(v.contains("양호"), v)
        assertTrue(v.contains("±40ms"), v)
    }

    @Test
    fun verdict_drift_mentionsPerMinuteRate() {
        val v = avSyncVerdict(report(pts(0.0 to 5.0, 30.0 to 60.0, 60.0 to 120.0), driftRateMsPerMin = 12.0))
        assertTrue(v.contains("분당"), v)
        assertTrue(v.contains("12ms"), v)
    }

    @Test
    fun verdict_spiky_callsOutTheWorstWindow() {
        val v = avSyncVerdict(report(pts(0.0 to 5.0, 10.0 to 5.0, 20.0 to 5.0, 30.0 to 210.0, 40.0 to 5.0)))
        assertTrue(v.contains("튑니다"), v)
        assertTrue(v.contains("210"), v)
    }

    @Test
    fun verdict_constantOffset_saysBehindAndItsoffset() {
        val v = avSyncVerdict(report(pts(0.0 to -84.0, 30.0 to -85.0, 60.0 to -86.0), avgSkewMs = -85.0, driftRateMsPerMin = 0.5))
        assertTrue(v.contains("뒤처짐"), v)
        assertTrue(v.contains("-itsoffset"), v)
    }

    @Test
    fun verdict_noPoints_returnsBenignDefault() {
        val v = avSyncVerdict(report(emptyList()))
        assertEquals("동기화 데이터가 부족합니다.", v)
    }

    @Test
    fun segments_allGreen_allPass() {
        // 30 points over a 10s file so every one of the 10 buckets is covered.
        val comfort = (0 until 30).map { it * (10.0 / 30) to 10.0 }.toTypedArray()
        val s = avSyncSegments(report(pts(*comfort), videoDurationSec = 10.0), 10)
        assertEquals(10, s.size)
        assertTrue(s.all { it == SyncSeverity.PASS }, s.toString())
    }

    @Test
    fun segments_redSpikeInMiddle_middleCriticalEdgesPass() {
        val pts = pts(0.0 to 5.0, 2.5 to 5.0, 5.0 to 250.0, 7.5 to 5.0, 10.0 to 5.0)
        val s = avSyncSegments(report(pts, videoDurationSec = 10.0), 4)
        assertEquals(4, s.size)
        assertEquals(SyncSeverity.PASS, s.first())
        assertEquals(SyncSeverity.PASS, s.last())
        assertTrue(s.any { it == SyncSeverity.CRITICAL }, s.toString())
    }

    @Test
    fun segments_gapWithNoPoints_isNull() {
        // points only in the first half of a 10s file
        val s = avSyncSegments(report(pts(0.0 to 5.0, 2.0 to 5.0, 4.0 to 5.0), videoDurationSec = 10.0), 10)
        assertEquals(10, s.size)
        assertNull(s.last())
    }

    @Test
    fun segments_lengthAlwaysEqualsCount() {
        val r = report(pts(0.0 to 5.0), videoDurationSec = 4.0)
        assertEquals(1, avSyncSegments(r, 1).size)
        assertEquals(50, avSyncSegments(r, 50).size)
    }
}
