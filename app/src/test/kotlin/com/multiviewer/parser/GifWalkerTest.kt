package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun uint16LE(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

private fun logicalScreenDescriptor(width: Int, height: Int, globalColorTableFlag: Boolean, globalColorTableSize: Int): ByteArray {
    val packed = (if (globalColorTableFlag) 0x80 else 0x00) or (globalColorTableSize and 0x07)
    return uint16LE(width) + uint16LE(height) + byteArrayOf(packed.toByte(), 0x00, 0x00)
}

private fun imageDescriptor(left: Int, top: Int, width: Int, height: Int, imageData: ByteArray): ByteArray =
    byteArrayOf(0x2C) + uint16LE(left) + uint16LE(top) + uint16LE(width) + uint16LE(height) +
        byteArrayOf(0x00) + byteArrayOf(0x02) + imageData

private fun subBlock(data: ByteArray): ByteArray = byteArrayOf(data.size.toByte()) + data

private val SUB_BLOCK_TERMINATOR = byteArrayOf(0x00)

private fun readerOver(bytes: ByteArray, namePrefix: String): ByteReader {
    val tmp = File.createTempFile(namePrefix, ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(bytes)
    return ByteReader.open(tmp)
}

class GifWalkerTest {
    @Test
    fun `decodes Logical Screen Descriptor fields and a WxH summary`() {
        val bytes = logicalScreenDescriptor(width = 320, height = 240, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-lsd").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val lsd = nodes[0]
            assertEquals("LogicalScreenDescriptor", lsd.type)
            assertEquals("320", lsd.fields.first { it.name == "width" }.value)
            assertEquals("240", lsd.fields.first { it.name == "height" }.value)
            assertEquals("0", lsd.fields.first { it.name == "global_color_table_flag" }.value)
            assertEquals("320x240", lsd.summary)
        }
    }

    @Test
    fun `decodes a Global Color Table when the flag is set`() {
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = true, globalColorTableSize = 0) +
            ByteArray(6) + // 3 * 2^(0+1) = 6 bytes
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-gct").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            assertEquals("GlobalColorTable", nodes[1].type)
            assertEquals("6", nodes[1].fields.first { it.name == "size" }.value)
        }
    }

    @Test
    fun `omits the Global Color Table node when the flag is not set`() {
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-no-gct").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            assertEquals(listOf("LogicalScreenDescriptor", "Trailer"), nodes.map { it.type })
        }
    }

    @Test
    fun `decodes an Image Descriptor with no Local Color Table`() {
        val imageData = subBlock(byteArrayOf(0x00)) + SUB_BLOCK_TERMINATOR
        val bytes = logicalScreenDescriptor(width = 10, height = 10, globalColorTableFlag = false, globalColorTableSize = 0) +
            imageDescriptor(left = 0, top = 0, width = 10, height = 10, imageData = imageData) +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-image-descriptor").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val imageDescriptorNode = nodes.first { it.type == "ImageDescriptor" }
            assertEquals("10", imageDescriptorNode.fields.first { it.name == "width" }.value)
            assertEquals("10", imageDescriptorNode.fields.first { it.name == "height" }.value)
            assertEquals("0", imageDescriptorNode.fields.first { it.name == "local_color_table_flag" }.value)
            assertEquals("10x10 at (0,0)", imageDescriptorNode.summary)
            assertTrue(imageDescriptorNode.children.isEmpty())
        }
    }

    @Test
    fun `decodes an Image Descriptor with a Local Color Table child`() {
        val imageData = subBlock(byteArrayOf(0x00)) + SUB_BLOCK_TERMINATOR
        val localColorTableBytes = ByteArray(6) // 3 * 2^(0+1)
        val packed = 0x80 // local color table flag set, size=0
        val imageDescriptorBytes = byteArrayOf(0x2C) + uint16LE(0) + uint16LE(0) + uint16LE(8) + uint16LE(8) +
            byteArrayOf(packed.toByte()) + localColorTableBytes + byteArrayOf(0x02) + imageData
        val bytes = logicalScreenDescriptor(width = 8, height = 8, globalColorTableFlag = false, globalColorTableSize = 0) +
            imageDescriptorBytes +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-local-color-table").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val imageDescriptorNode = nodes.first { it.type == "ImageDescriptor" }
            assertEquals(1, imageDescriptorNode.children.size)
            val localColorTable = imageDescriptorNode.children[0]
            assertEquals("LocalColorTable", localColorTable.type)
            assertEquals("6", localColorTable.fields.first { it.name == "size" }.value)
        }
    }

    @Test
    fun `multiple Image Descriptors (an animated GIF) are all decoded, proving animation needs no special-casing`() {
        val imageData = subBlock(byteArrayOf(0x00)) + SUB_BLOCK_TERMINATOR
        val frame = imageDescriptor(left = 0, top = 0, width = 4, height = 4, imageData = imageData)
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            frame + frame + frame +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-animated").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            assertEquals(3, nodes.count { it.type == "ImageDescriptor" })
        }
    }

    @Test
    fun `a Comment Extension shows as a generic, field-less node`() {
        val commentData = subBlock("hello".toByteArray(Charsets.US_ASCII)) + SUB_BLOCK_TERMINATOR
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x21, 0xFE.toByte()) + commentData +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-comment").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val commentNode = nodes.first { it.type == "CommentExtension" }
            assertTrue(commentNode.fields.isEmpty())
        }
    }

    @Test
    fun `an unrecognized extension label shows as a generic, field-less node`() {
        val extensionData = subBlock(byteArrayOf(0x01, 0x02)) + SUB_BLOCK_TERMINATOR
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x21, 0x99.toByte()) + extensionData +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-unknown-extension").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val extensionNode = nodes.first { it.type.startsWith("Extension_") }
            assertEquals("Extension_0x99", extensionNode.type)
            assertTrue(extensionNode.fields.isEmpty())
        }
    }

    @Test
    fun `the Trailer stops the block loop`() {
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x3B, 0x2C) // garbage after the trailer must be ignored
        readerOver(bytes, "gif-walker-trailer").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            assertEquals(listOf("LogicalScreenDescriptor", "Trailer"), nodes.map { it.type })
        }
    }

    @Test
    fun `a file too short for a Logical Screen Descriptor produces a warning node`() {
        val bytes = byteArrayOf(0x04, 0x00, 0x00) // only 3 of the required 7 bytes
        readerOver(bytes, "gif-walker-truncated").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            assertEquals(1, nodes.size)
            assertTrue(nodes[0].warnings.isNotEmpty())
        }
    }

    @Test
    fun `decodes Graphic Control Extension fields`() {
        // packed = 0x09 = 0b00001001 -> disposal_method (bits 4-2) = 2, transparent_color_flag (bit 0) = 1
        val gceData = subBlock(byteArrayOf(0x09, 0x0A, 0x00, 0x05)) + SUB_BLOCK_TERMINATOR
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x21, 0xF9.toByte()) + gceData +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-gce").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val gce = nodes.first { it.type == "GraphicControlExtension" }
            assertEquals("2", gce.fields.first { it.name == "disposal_method" }.value)
            assertEquals("1", gce.fields.first { it.name == "transparent_color_flag" }.value)
            assertEquals("10", gce.fields.first { it.name == "delay_time" }.value)
            assertEquals("5", gce.fields.first { it.name == "transparent_color_index" }.value)
        }
    }

    @Test
    fun `decodes an Application Extension's Netscape loop count`() {
        val appHeader = subBlock("NETSCAPE2.0".toByteArray(Charsets.US_ASCII)) // 11 bytes
        val loopSubBlock = subBlock(byteArrayOf(0x01, 0x00, 0x00)) // sub-block ID 1, loop count = 0 (infinite)
        val appData = appHeader + loopSubBlock + SUB_BLOCK_TERMINATOR
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x21, 0xFF.toByte()) + appData +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-netscape").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val app = nodes.first { it.type == "ApplicationExtension" }
            assertEquals("NETSCAPE2.0", app.fields.first { it.name == "application_identifier" }.value)
            assertEquals("0", app.fields.first { it.name == "loop_count" }.value)
        }
    }

    @Test
    fun `an Application Extension with a non-Netscape identifier has no loop_count field`() {
        val appHeader = subBlock("XYZAPP1.0X0".toByteArray(Charsets.US_ASCII)) // 11 bytes, arbitrary
        val appData = appHeader + SUB_BLOCK_TERMINATOR
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x21, 0xFF.toByte()) + appData +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-non-netscape").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val app = nodes.first { it.type == "ApplicationExtension" }
            assertEquals("XYZAPP1.0X0", app.fields.first { it.name == "application_identifier" }.value)
            assertEquals(null, app.fields.find { it.name == "loop_count" })
        }
    }
}
