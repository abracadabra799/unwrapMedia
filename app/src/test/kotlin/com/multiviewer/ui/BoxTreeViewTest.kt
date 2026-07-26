package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoxTreeViewTest {
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
