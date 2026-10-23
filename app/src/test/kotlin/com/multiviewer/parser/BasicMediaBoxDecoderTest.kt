package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BasicMediaBoxDecoderTest {
    private fun decode(type: String, body: ByteArray): BoxNode {
        registerAllDecoders()
        // Nonzero start catches accidentally relative field offsets.
        return byteReaderOf(ByteArray(17) + body).use {
            BoxRegistry.decoderFor(type).decode(it, type, 9, 8, 8L + body.size, emptyList())
        }
    }

    @Test fun `media headers decode nonzero values with absolute byte ranges`() {
        val video = decode("vmhd", hex("00000001 0040 0011 0022 0033"))
        assertEquals(listOf("0", "0x000001", "64", "17", "34", "51"), video.fields.map { it.value })
        assertEquals(listOf(17L, 18L, 21L, 23L, 25L, 27L), video.fields.map { it.offset })
        val audio = decode("smhd", hex("00000000 ff80 0000"))
        assertEquals("-0.5", audio.fields.first { it.name == "balance" }.value)
        assertEquals(21L, audio.fields.first { it.name == "balance" }.offset)
        assertTrue(video.warnings.isEmpty())
        assertTrue(audio.warnings.isEmpty())
    }

    @Test fun `bitrate is unsigned and field order is exposed`() {
        val bitrate = decode("btrt", hex("ffffffff 80000000 00011952"))
        assertEquals(listOf("4294967295", "2147483648", "72018"), bitrate.fields.map { it.value })
        assertEquals(listOf(17L, 21L, 25L), bitrate.fields.map { it.offset })
        assertEquals(listOf("2", "9"), decode("fiel", hex("0209")).fields.map { it.value })
    }

    @Test fun `every truncated prefix warns without reading following data`() {
        for ((type, size) in listOf("vmhd" to 12, "smhd" to 8, "btrt" to 12, "fiel" to 2)) {
            for (length in 0 until size) {
                val node = decode(type, ByteArray(length))
                assertTrue(node.warnings.isNotEmpty(), "$type length=$length")
                assertTrue(node.fields.isEmpty(), "$type length=$length")
            }
        }
    }

    @Test fun `unknown media header versions do not decode version zero fields`() {
        for ((type, size) in listOf("vmhd" to 12, "smhd" to 8)) {
            val node = decode(type, ByteArray(size).also { it[0] = 1 })
            assertTrue(node.warnings.isNotEmpty())
            assertTrue(node.fields.none { it.name == "balance" || it.name == "graphicsmode" })
        }
    }

    @Test fun `default ffmpeg fixture exposes actual media and bitrate headers`() {
        registerAllDecoders()
        val file = File("src/test/resources/detail-parsing/default-avc-aac.mp4")
        ByteReader.open(file).use { reader ->
            fun flatten(nodes: List<BoxNode>): List<BoxNode> = nodes.flatMap { listOf(it) + flatten(it.children) }
            val nodes = flatten(parseBoxes(reader, 0, reader.length))
            val vmhd = nodes.single { it.type == "vmhd" }
            assertEquals(377L, vmhd.offset)
            assertEquals("0", vmhd.fields.firstOrNull { it.name == "graphicsmode" }?.value)
            assertEquals("0.0", nodes.single { it.type == "smhd" }.fields.firstOrNull { it.name == "balance" }?.value)
            assertEquals(listOf("0", "72018", "72018"), nodes.single { it.type == "btrt" && it.offset == 1151L }.fields.map { it.value })
            for (node in nodes.filter { it.type in setOf("vmhd", "smhd", "btrt") }) {
                assertTrue(node.fields.all { it.offset >= node.offset + node.headerSize && it.offset + it.length <= node.offset + node.size })
            }
        }
    }

    @Test fun `existing AV1 fixture exposes field order`() {
        registerAllDecoders()
        val file = File("src/test/resources/hdr/av1_hdr.mp4")
        ByteReader.open(file).use { reader ->
            val node = BoxRegistry.decoderFor("fiel").decode(reader, "fiel", 16600, 8, 10, emptyList())
            assertEquals(listOf("1", "0"), node.fields.map { it.value })
        }
    }
}
