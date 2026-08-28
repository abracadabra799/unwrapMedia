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
}
