package com.multiviewer.cli

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.MediaCategory
import com.multiviewer.parser.MediaSummary
import com.multiviewer.parser.SummaryField
import com.multiviewer.parser.SummarySection
import com.multiviewer.parser.WarningEntry
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalysisCaseTest {
    @Test
    fun `case json contains reproducible file identity summary and evidence without local path`() {
        val file = File.createTempFile("private-case-", ".jpg")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        val warning = WarningEntry(
            BoxNode(type = "Exif", offset = 12, headerSize = 2, size = 8),
            "Declared size 130 extends 32 byte(s) past the end of its parent",
        )
        val summary = MediaSummary(
            category = MediaCategory.IMAGE,
            sections = listOf(
                SummarySection("General", listOf(SummaryField("Format", "JPEG"))),
                SummarySection("GPS Location", listOf(SummaryField("Latitude", "37.5"))),
            ),
        )

        val json = buildAnalysisCaseJson(file, listOf(warning), summary, appVersion = "test-version")
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("\"version\": \"test-version\""))
        assertTrue(json.contains("\"name\": \"${file.name}\""))
        assertTrue(json.contains("\"sizeBytes\": 4"))
        assertTrue(json.contains("\"sha256\": \"$expectedHash\""))
        assertTrue(json.contains("\"category\": \"IMAGE\""))
        assertTrue(json.contains("\"title\": \"General\""))
        assertFalse(json.contains("GPS Location"))
        assertFalse(json.contains("37.5"))
        assertTrue(json.contains("\"offset\": 12"))
        assertTrue(json.contains("\"severity\": \"CRITICAL\""))
        assertFalse(json.contains(file.parentFile.absolutePath))
        file.delete()
    }
}
