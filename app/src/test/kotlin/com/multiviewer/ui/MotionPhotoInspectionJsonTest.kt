package com.multiviewer.ui

import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.motionphoto.MotionPhotoInspectionItem
import com.multiviewer.parser.motionphoto.MotionPhotoInspectionReport
import com.multiviewer.parser.motionphoto.MotionPhotoInspectionSection
import com.multiviewer.ui.ai.parseJsonLoose
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MotionPhotoInspectionJsonTest {
    private val tricky = "a\"b\\c\nd\t한글"

    private fun section(id: String, items: List<MotionPhotoInspectionItem> = emptyList()) =
        MotionPhotoInspectionSection(id, "제목", "Title $tricky", CheckStatus.PASS, items)

    @Test
    fun `export escapes quotes backslashes control chars and keeps Korean`() {
        val item = MotionPhotoInspectionItem(
            id = "item1",
            status = CheckStatus.WARN,
            label = tricky,
            expected = tricky,
            actual = null,
            detail = "C:\\Users\\x\r\n$tricky",
            offset = 42L,
            length = null,
            remediation = tricky,
        )
        val report = MotionPhotoInspectionReport(
            formatLabel = "Samsung \"SEF\" \\ $tricky",
            overallStatus = CheckStatus.WARN,
            stillImageSection = section("still", listOf(item)),
            formatXmpSection = section("xmp"),
            sefSection = section("sef"),
            videoCodecSection = section("video"),
            avSyncSection = section("avsync"),
        )
        val json = buildMotionPhotoInspectionJson(File("/tmp/mo\"tion\\ 사진\n.jpg"), report)
        val root = assertNotNull(parseJsonLoose(json) as? Map<*, *>, "export must parse:\n$json")
        assertEquals(listOf("file", "format", "overallStatus", "sections"), root.keys.toList())
        assertEquals("mo\"tion\\ 사진\n.jpg", root["file"])
        assertEquals(report.formatLabel, root["format"])
        assertEquals("WARN", root["overallStatus"])
        val sections = root["sections"] as List<*>
        assertEquals(5, sections.size)
        val s0 = sections[0] as Map<*, *>
        assertEquals(listOf("id", "title", "status", "items"), s0.keys.toList())
        assertEquals("Title $tricky", s0["title"])
        val i0 = (s0["items"] as List<*>)[0] as Map<*, *>
        assertEquals(
            listOf("id", "status", "label", "expected", "actual", "detail", "remediation", "offset", "length"),
            i0.keys.toList(),
        )
        assertEquals(tricky, i0["label"])
        assertEquals(tricky, i0["expected"])
        assertTrue(i0.containsKey("actual") && i0["actual"] == null)
        assertEquals(item.detail, i0["detail"])
        assertEquals(tricky, i0["remediation"])
        assertEquals(42.0, i0["offset"])
        assertTrue(i0.containsKey("length") && i0["length"] == null)
        assertEquals(emptyList<Any?>(), (sections[1] as Map<*, *>)["items"])
    }
}
