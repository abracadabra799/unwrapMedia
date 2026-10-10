package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.ImageIntegrityChecker
import com.multiviewer.parser.integrity.jpegBytes
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ContentCheckPlanTest {
    @Test
    fun tabsPerType() {
        for (m in listOf(false, true)) {
            assertEquals(
                listOf(ContentTab.STRUCTURE, ContentTab.IMAGE_DECODE) + if (m) listOf(ContentTab.MOTION_PHOTO) else emptyList(),
                contentTabs(MediaType.IMAGE, m),
            )
            assertEquals(listOf(ContentTab.STRUCTURE, ContentTab.VIDEO_DECODE, ContentTab.VIDEO_PACKETS), contentTabs(MediaType.VIDEO, m))
            for (t in listOf(MediaType.AUDIO, MediaType.RAW_PIXEL, MediaType.UNKNOWN)) {
                assertEquals(listOf(ContentTab.STRUCTURE), contentTabs(t, m))
            }
        }
    }

    @Test
    fun stepsPerType() {
        for (m in listOf(false, true)) {
            assertEquals(
                listOf(HeavyStep.IMAGE_DECODE) + if (m) listOf(HeavyStep.MOTION_PHOTO) else emptyList(),
                heavySteps(MediaType.IMAGE, m),
            )
            assertEquals(listOf(HeavyStep.VIDEO_INTEGRITY), heavySteps(MediaType.VIDEO, m))
            for (t in listOf(MediaType.AUDIO, MediaType.RAW_PIXEL, MediaType.UNKNOWN)) {
                assertEquals(emptyList(), heavySteps(t, m))
            }
        }
    }

    @Test
    fun nonImageWithWarning() {
        val root = BoxNode("root", 0, 0, 100, children = listOf(BoxNode("moov", 8, 8, 40, warnings = listOf("bad"))))
        val r = contentStructureReport(File("x.mp4"), root, MediaType.VIDEO)
        assertEquals("VIDEO", r.format)
        assertEquals(1, r.items.size)
        val i = r.items[0]
        assertEquals("parser.warning", i.id)
        assertEquals(CheckStatus.WARN, i.status)
        assertEquals(8L, i.offset)
        assertEquals(40L, i.length)
    }

    @Test
    fun nonImageNoWarnings() {
        val r = contentStructureReport(File("x.wav"), BoxNode("root", 0, 0, 10), MediaType.AUDIO)
        assertEquals(1, r.items.size)
        assertEquals("parser.warnings", r.items[0].id)
        assertEquals(CheckStatus.PASS, r.items[0].status)
    }

    @Test
    fun imageDelegatesToChecker() {
        val f = File.createTempFile("ccp-", ".jpg").also { it.deleteOnExit(); it.writeBytes(jpegBytes()) }
        val root = parseFile(f)
        assertEquals(ImageIntegrityChecker.check(f, root), contentStructureReport(f, root, MediaType.IMAGE))
    }
}
