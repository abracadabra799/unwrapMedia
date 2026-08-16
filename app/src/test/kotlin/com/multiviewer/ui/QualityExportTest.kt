package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QualityExportTest {
    private val psnrResult = MetricRunResult(
        perFrame = listOf(MetricFrameSample(0, 45.0), MetricFrameSample(1, 46.5)),
        statistics = MetricStatistics(min = 45.0, max = 46.5, mean = 45.75, median = 45.75),
    )
    private val ssimResult = MetricRunResult(
        perFrame = listOf(MetricFrameSample(0, 0.98), MetricFrameSample(1, 0.99)),
        statistics = MetricStatistics(min = 0.98, max = 0.99, mean = 0.985, median = 0.985),
    )

    @Test
    fun `writeResultsCsv writes one header row and one row per frame with a column per metric`() {
        val destination = File.createTempFile("quality-export-csv-test-", ".csv")
        destination.deleteOnExit()

        writeResultsCsv(destination, linkedMapOf("PSNR" to psnrResult, "SSIM" to ssimResult))

        val lines = destination.readLines()
        assertEquals("frame_index,PSNR,SSIM", lines[0])
        assertEquals("0,45.0,0.98", lines[1])
        assertEquals("1,46.5,0.99", lines[2])
        destination.delete()
    }

    @Test
    fun `writeResultsCsv handles metrics with unequal frame counts by leaving missing cells blank`() {
        val shortResult = MetricRunResult(
            perFrame = listOf(MetricFrameSample(0, 1.0)),
            statistics = MetricStatistics(min = 1.0, max = 1.0, mean = 1.0, median = 1.0),
        )
        val destination = File.createTempFile("quality-export-csv-uneven-test-", ".csv")
        destination.deleteOnExit()

        writeResultsCsv(destination, linkedMapOf("PSNR" to psnrResult, "SHORT" to shortResult))

        val lines = destination.readLines()
        assertEquals("0,45.0,1.0", lines[1])
        assertEquals("1,46.5,", lines[2])
        destination.delete()
    }

    @Test
    fun `writeResultsJson writes statistics and per-frame data for every metric`() {
        val destination = File.createTempFile("quality-export-json-test-", ".json")
        destination.deleteOnExit()

        writeResultsJson(destination, linkedMapOf("PSNR" to psnrResult))

        val content = destination.readText()
        assertTrue(content.contains("\"PSNR\""))
        assertTrue(content.contains("\"min\": 45.0"))
        assertTrue(content.contains("\"max\": 46.5"))
        assertTrue(content.contains("\"mean\": 45.75"))
        assertTrue(content.contains("\"median\": 45.75"))
        assertTrue(content.contains("\"frameIndex\": 0, \"value\": 45.0"))
        assertTrue(content.contains("\"frameIndex\": 1, \"value\": 46.5"))
        destination.delete()
    }
}
