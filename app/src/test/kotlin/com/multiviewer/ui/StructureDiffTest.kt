package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import kotlin.test.Test
import kotlin.test.assertEquals

class StructureDiffTest {

    @Test
    fun `inserting a marker in the middle does not cause subsequent markers to be marked as modified`() {
        // File A: SOI (0), APP0 (2), DQT (18), SOF0 (150), SOS (167), EOI (1167)
        val rootA = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 1169,
            children = listOf(
                BoxNode(type = "SOI", offset = 0, headerSize = 0, size = 2),
                BoxNode(type = "APP0", offset = 2, headerSize = 0, size = 16),
                BoxNode(type = "DQT", offset = 18, headerSize = 0, size = 132),
                BoxNode(type = "SOF0", offset = 150, headerSize = 0, size = 17),
                BoxNode(type = "SOS", offset = 167, headerSize = 0, size = 1000),
                BoxNode(type = "EOI", offset = 1167, headerSize = 0, size = 2),
            ),
        )

        // File B: Insert APP1 (size 500) after APP0. All subsequent offsets shift by +500.
        val rootB = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 1669,
            children = listOf(
                BoxNode(type = "SOI", offset = 0, headerSize = 0, size = 2),
                BoxNode(type = "APP0", offset = 2, headerSize = 0, size = 16),
                BoxNode(type = "APP1", offset = 18, headerSize = 0, size = 500),
                BoxNode(type = "DQT", offset = 518, headerSize = 0, size = 132),
                BoxNode(type = "SOF0", offset = 650, headerSize = 0, size = 17),
                BoxNode(type = "SOS", offset = 667, headerSize = 0, size = 1000),
                BoxNode(type = "EOI", offset = 1667, headerSize = 0, size = 2),
            ),
        )

        val diff = computeStructureDiff(rootA, rootB)

        assertEquals(7, diff.size)
        assertEquals("SOI", diff[0].path)
        assertEquals(DiffStatus.MATCH, diff[0].status)

        assertEquals("APP0", diff[1].path)
        assertEquals(DiffStatus.MATCH, diff[1].status)

        assertEquals("APP1", diff[2].path)
        assertEquals(DiffStatus.ADDED_IN_B, diff[2].status)
        assertEquals(null, diff[2].offsetA)
        assertEquals(18L, diff[2].offsetB)

        // DQT, SOF0, SOS, EOI must ALL remain MATCH despite shifted offsets!
        assertEquals("DQT", diff[3].path)
        assertEquals(DiffStatus.MATCH, diff[3].status)
        assertEquals(18L, diff[3].offsetA)
        assertEquals(518L, diff[3].offsetB)

        assertEquals("SOF0", diff[4].path)
        assertEquals(DiffStatus.MATCH, diff[4].status)
        assertEquals(150L, diff[4].offsetA)
        assertEquals(650L, diff[4].offsetB)

        assertEquals("SOS", diff[5].path)
        assertEquals(DiffStatus.MATCH, diff[5].status)

        assertEquals("EOI", diff[6].path)
        assertEquals(DiffStatus.MATCH, diff[6].status)
    }

    @Test
    fun `deleting a marker marks it as removed in B while others remain match`() {
        val rootA = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 100,
            children = listOf(
                BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 20),
                BoxNode(type = "meta", offset = 20, headerSize = 0, size = 50),
                BoxNode(type = "mdat", offset = 70, headerSize = 0, size = 30),
            ),
        )

        val rootB = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 50,
            children = listOf(
                BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 20),
                BoxNode(type = "mdat", offset = 20, headerSize = 0, size = 30),
            ),
        )

        val diff = computeStructureDiff(rootA, rootB)

        assertEquals(3, diff.size)
        assertEquals("ftyp", diff[0].path)
        assertEquals(DiffStatus.MATCH, diff[0].status)

        assertEquals("meta", diff[1].path)
        assertEquals(DiffStatus.REMOVED_IN_B, diff[1].status)

        assertEquals("mdat", diff[2].path)
        assertEquals(DiffStatus.MATCH, diff[2].status)
    }

    @Test
    fun `modified box content or size is marked as modified`() {
        val rootA = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 100,
            children = listOf(
                BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 20, summary = "heic"),
            ),
        )

        val rootB = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 100,
            children = listOf(
                BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 24, summary = "mif1"),
            ),
        )

        val diff = computeStructureDiff(rootA, rootB)

        assertEquals(1, diff.size)
        assertEquals("ftyp", diff[0].path)
        assertEquals(DiffStatus.MODIFIED, diff[0].status)
    }
}
