package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoxTreeViewTest {
    // Hex viewer -> tree sync: a byte the user clicked has to resolve to the box that actually
    // contains it. The innermost one, because every byte inside a child is also inside its
    // ancestors and naming the root would tell the user nothing.
    @Test
    fun `findNodeAtOffset returns the innermost box containing the byte`() {
        val leaf = BoxNode(type = "hvcC", offset = 40, headerSize = 8, size = 20)
        val mid = BoxNode(type = "stsd", offset = 20, headerSize = 8, size = 60, children = listOf(leaf))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 200, children = listOf(mid))

        assertEquals(leaf, findNodeAtOffset(root, 45))
        // Inside mid but before the leaf starts.
        assertEquals(mid, findNodeAtOffset(root, 25))
        // Inside root but outside mid.
        assertEquals(root, findNodeAtOffset(root, 150))
    }

    @Test
    fun `findNodeAtOffset includes a box's first and last byte but not the byte after it`() {
        val child = BoxNode(type = "mdat", offset = 100, headerSize = 8, size = 50)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 200, children = listOf(child))

        assertEquals(child, findNodeAtOffset(root, 100))
        assertEquals(child, findNodeAtOffset(root, 149))
        assertEquals(root, findNodeAtOffset(root, 150))
    }

    @Test
    fun `findNodeAtOffset returns null for an offset outside the tree`() {
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 100)

        assertNull(findNodeAtOffset(root, 100))
        assertNull(findNodeAtOffset(root, -1))
    }

    // JPEG markers and some boxes are recorded with size 0 (a marker with no payload). Such a node
    // contains no bytes, so it must never swallow the offset and hide the real container.
    @Test
    fun `findNodeAtOffset skips zero-sized nodes`() {
        val empty = BoxNode(type = "SOI", offset = 10, headerSize = 0, size = 0)
        val real = BoxNode(type = "APP1", offset = 10, headerSize = 4, size = 30)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 100, children = listOf(empty, real))

        assertEquals(real, findNodeAtOffset(root, 10))
    }


    // Clicking a structural warning selects its node; the tree then has to scroll that node into
    // view, not just expand and highlight it. A defect is usually deep in a large file, so without
    // a row index to scroll to it stays off-screen and the click looks like it did nothing.
    @Test
    fun `visibleRowIndexOf finds a node's row once its ancestors are expanded`() {
        val target = BoxNode(type = "DHT", offset = 100, headerSize = 4, size = 10)
        val sibling = BoxNode(type = "DQT", offset = 60, headerSize = 4, size = 10)
        val middle = BoxNode(type = "APP1", offset = 20, headerSize = 4, size = 50, children = listOf(sibling, target))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 200, children = listOf(middle))

        // Rows when everything is open: root(0), APP1(1), DQT(2), DHT(3)
        assertEquals(3, visibleRowIndexOf(root, setOf(root, middle), target))
        assertEquals(1, visibleRowIndexOf(root, setOf(root, middle), middle))
        assertEquals(0, visibleRowIndexOf(root, setOf(root, middle), root))
    }

    // A collapsed ancestor means the node has no row at all -- the caller must not scroll to a
    // stale index in that case.
    @Test
    fun `visibleRowIndexOf returns -1 for a node hidden under a collapsed parent`() {
        val target = BoxNode(type = "DHT", offset = 100, headerSize = 4, size = 10)
        val middle = BoxNode(type = "APP1", offset = 20, headerSize = 4, size = 50, children = listOf(target))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 200, children = listOf(middle))

        assertEquals(-1, visibleRowIndexOf(root, setOf(root), target))
    }

    @Test
    fun `visibleRowIndexOf returns -1 for a node from a different tree`() {
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 200)
        val stranger = BoxNode(type = "mdat", offset = 0, headerSize = 8, size = 40)

        assertEquals(-1, visibleRowIndexOf(root, setOf(root), stranger))
    }

    // Two boxes can be structurally identical (same type/offset/size) at different places in the
    // tree -- the row found has to be the one actually selected, matched by identity.
    @Test
    fun `visibleRowIndexOf distinguishes structurally identical sibling nodes`() {
        val first = BoxNode(type = "free", offset = 10, headerSize = 8, size = 8)
        val second = BoxNode(type = "free", offset = 10, headerSize = 8, size = 8)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 200, children = listOf(first, second))

        assertEquals(1, visibleRowIndexOf(root, setOf(root), first))
        assertEquals(2, visibleRowIndexOf(root, setOf(root), second))
    }

    @Test
    fun `findAncestors returns the chain of parents down to (not including) a deeply nested target`() {
        val target = BoxNode(type = "DHT", offset = 100, headerSize = 4, size = 10)
        val middle = BoxNode(type = "APP1", offset = 20, headerSize = 4, size = 50, children = listOf(target))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 200, children = listOf(middle))

        val ancestors = findAncestors(root, target, emptyList())

        assertEquals(listOf(root, middle), ancestors)
    }

    @Test
    fun `findAncestors returns an empty list when target is the root itself`() {
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 200)

        assertEquals(emptyList(), findAncestors(root, root, emptyList()))
    }

    @Test
    fun `findAncestors returns null when target is not in this tree`() {
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 200)
        val notInTree = BoxNode(type = "DHT", offset = 100, headerSize = 4, size = 10)

        assertNull(findAncestors(root, notInTree, emptyList()))
    }

    @Test
    fun `findAncestors does not confuse two structurally-identical but distinct sibling nodes`() {
        // Two boxes with identical type/offset/size (a data class, so they're `equal`) but they
        // are different object instances at different tree positions -- findAncestors must use
        // reference equality (matching how `selected` is compared elsewhere in this file) so it
        // finds the actual clicked instance, not just any structurally-equal lookalike.
        val targetA = BoxNode(type = "DQT", offset = 5, headerSize = 4, size = 10)
        val targetB = BoxNode(type = "DQT", offset = 5, headerSize = 4, size = 10)
        val branchA = BoxNode(type = "branchA", offset = 1, headerSize = 0, size = 20, children = listOf(targetA))
        val branchB = BoxNode(type = "branchB", offset = 2, headerSize = 0, size = 20, children = listOf(targetB))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 200, children = listOf(branchA, branchB))

        assertEquals(listOf(root, branchB), findAncestors(root, targetB, emptyList()))
    }
}
