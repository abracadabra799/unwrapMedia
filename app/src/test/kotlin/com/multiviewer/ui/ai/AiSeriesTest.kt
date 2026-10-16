package com.multiviewer.ui.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiSeriesTest {
    @Test
    fun `empty input says no data`() {
        assertEquals("T: no data", renderSeries("T", "ms", "s", emptyList()))
    }

    @Test
    fun `few points give one line per point plus largest and overall`() {
        val pts = listOf(1.0, 5.0, 3.0, 2.0, 4.0).mapIndexed { i, y -> SeriesPoint(i.toDouble(), y) }
        val lines = renderSeries("T", "ms", "s", pts).lines()
        assertEquals("T (ms) over s, 5 points:", lines[0])
        assertEquals("  0.000: 1.000", lines[1])
        assertEquals("  4.000: 4.000", lines[5])
        assertTrue(lines[6].startsWith("  largest: 1.000=5.000, "), lines[6])
        assertEquals("  overall: min 1.000 · mean 3.000 · max 5.000", lines[7])
        assertEquals(8, lines.size)
    }

    @Test
    fun `huge series is bucketed to a bounded size`() {
        val pts = (0 until 100_000).map { SeriesPoint(it / 1000.0, (it % 97).toDouble()) }
        val out = renderSeries("T", "ms", "s", pts)
        assertTrue(out.lines().size <= 70, "${out.lines().size}")
        assertTrue(out.contains("100000 points"), out)
        assertTrue(out.contains("min 0.000 · mean"), out)
    }

    @Test
    fun `spikes are sorted descending and capped`() {
        val pts = (0 until 100).map { SeriesPoint(it.toDouble(), it.toDouble()) }
        val line = renderSeries("T", "", "s", pts, spikes = 3).lines().first { it.trim().startsWith("largest:") }
        assertEquals("  largest: 99.000=99.000, 98.000=98.000, 97.000=97.000", line)
    }

    @Test
    fun `number format switches at 100`() {
        val out = renderSeries("T", "", "s", listOf(SeriesPoint(1.0, 1234.5), SeriesPoint(2.0, 0.12345)))
        assertTrue(out.contains("1234.5"), out)
        assertTrue(out.contains("0.123"), out)
        assertFalse(out.contains("0.12345"), out)
    }

    @Test
    fun `NaN and infinite points are dropped`() {
        val out = renderSeries("T", "", "s", listOf(SeriesPoint(0.0, 1.0), SeriesPoint(1.0, Double.NaN), SeriesPoint(2.0, Double.POSITIVE_INFINITY), SeriesPoint(Double.NaN, 3.0)))
        assertTrue(out.contains("1 points"), out)
        assertFalse(out.contains("NaN") || out.contains("Infinity"), out)
        assertEquals("T: no data", renderSeries("T", "", "s", listOf(SeriesPoint(0.0, Double.NaN))))
    }
}
