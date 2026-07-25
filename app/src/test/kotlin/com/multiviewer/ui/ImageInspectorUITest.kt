package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageInspectorUITest {
    @Test
    fun `collectWarnings flattens warnings from every depth, sorted by offset`() {
        val deepChild = BoxNode(
            type = "DHT", offset = 100, headerSize = 4, size = 10,
            warnings = listOf("Huffman table truncated"),
        )
        val midChild = BoxNode(
            type = "APP1", offset = 20, headerSize = 4, size = 50,
            warnings = listOf("Declared length extends past the end of the file"),
            children = listOf(deepChild),
        )
        val cleanChild = BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2)
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 200,
            children = listOf(cleanChild, midChild),
        )

        val warnings = collectWarnings(root)

        assertEquals(2, warnings.size)
        assertEquals("APP1", warnings[0].node.type)
        assertEquals("Declared length extends past the end of the file", warnings[0].warning)
        assertEquals("DHT", warnings[1].node.type)
        assertEquals("Huffman table truncated", warnings[1].warning)
    }

    @Test
    fun `collectWarnings returns an empty list for a tree with no warnings anywhere`() {
        val child = BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 2, children = listOf(child))

        assertEquals(emptyList(), collectWarnings(root))
    }

    @Test
    fun `collectWarnings includes a node with multiple warnings once per warning`() {
        val child = BoxNode(
            type = "DQT", offset = 5, headerSize = 4, size = 10,
            warnings = listOf("first issue", "second issue"),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 20, children = listOf(child))

        val warnings = collectWarnings(root)

        assertEquals(2, warnings.size)
        assertEquals("first issue", warnings[0].warning)
        assertEquals("second issue", warnings[1].warning)
    }
}
