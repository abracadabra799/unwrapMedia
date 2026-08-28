package com.multiviewer.ui

import com.multiviewer.parser.MediaCategory
import com.multiviewer.parser.MediaSummary
import com.multiviewer.parser.SummaryField
import com.multiviewer.parser.SummarySection
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageCompareMetadataTest {
    @Test
    fun `extractMetadataDiffRows does not duplicate File Size or other general fields`() {
        val summaryA = MediaSummary(
            category = MediaCategory.IMAGE,
            sections = listOf(
                SummarySection(
                    title = "General",
                    fields = listOf(
                        SummaryField("File Name", "photo1.jpg"),
                        SummaryField("File Size", "2.5 MB (2,621,440 bytes)"),
                        SummaryField("Format", "JPEG"),
                        SummaryField("Dimensions", "4000x3000"),
                    ),
                ),
            ),
        )

        val summaryB = MediaSummary(
            category = MediaCategory.IMAGE,
            sections = listOf(
                SummarySection(
                    title = "General",
                    fields = listOf(
                        SummaryField("File Name", "photo2.jpg"),
                        SummaryField("File Size", "1.8 MB (1,887,436 bytes)"),
                        SummaryField("Format", "JPEG"),
                        SummaryField("Dimensions", "4000x3000"),
                    ),
                ),
            ),
        )

        val infoA = CompareMediaInfo(
            file = File("photo1.jpg"),
            root = null,
            forensic = null,
            bitmap = null,
            summary = summaryA,
            fileSize = 2621440L,
        )

        val infoB = CompareMediaInfo(
            file = File("photo2.jpg"),
            root = null,
            forensic = null,
            bitmap = null,
            summary = summaryB,
            fileSize = 1887436L,
        )

        val rows = extractMetadataDiffRows(infoA, infoB)
        val generalFileSizeRows = rows.filter { it.category == "General" && it.key == "File Size" }
        assertEquals(1, generalFileSizeRows.size, "File Size in General section should appear exactly once")

        // Verify each (category, key) pair in rows is distinct
        val keys = rows.map { it.category to it.key }
        assertEquals(keys.distinct().size, keys.size, "All (category, key) pairs should be unique")
    }

    @Test
    fun `scanHexDifferences returns empty list for identical files`() {
        val fileA = File.createTempFile("hex-test-a-", ".bin")
        val fileB = File.createTempFile("hex-test-b-", ".bin")
        fileA.deleteOnExit()
        fileB.deleteOnExit()

        val sampleData = ByteArray(1024) { (it % 256).toByte() }
        fileA.writeBytes(sampleData)
        fileB.writeBytes(sampleData)

        val diffs = scanHexDifferences(fileA, fileB)
        assertEquals(0, diffs.size, "Identical files should have 0 diff chunks")
    }

    @Test
    fun `scanHexDifferences accurately identifies difference chunks and offsets`() {
        val fileA = File.createTempFile("hex-diff-test-a-", ".bin")
        val fileB = File.createTempFile("hex-diff-test-b-", ".bin")
        fileA.deleteOnExit()
        fileB.deleteOnExit()

        val dataA = ByteArray(512) { 0 }
        val dataB = ByteArray(512) { 0 }

        // Diff 1: at offset 0x20 (row 2)
        dataB[32] = 0xFF.toByte()

        // Diff 2: at offset 0x100..0x120 (row 16..18)
        for (i in 256 until 288) {
            dataB[i] = 0xAA.toByte()
        }

        fileA.writeBytes(dataA)
        fileB.writeBytes(dataB)

        val diffs = scanHexDifferences(fileA, fileB)
        assertEquals(2, diffs.size, "Should detect 2 distinct difference chunks")

        // First diff
        assertEquals(1, diffs[0].index)
        assertEquals(2, diffs[0].startRow)
        assertEquals(32L, diffs[0].startOffset)

        // Second diff
        assertEquals(2, diffs[1].index)
        assertEquals(16, diffs[1].startRow)
        assertEquals(256L, diffs[1].startOffset)
    }
}
