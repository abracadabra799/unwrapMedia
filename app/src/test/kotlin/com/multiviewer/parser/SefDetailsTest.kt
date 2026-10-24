package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class SefDetailsTest {
    @Test fun `device SEF exposes header directory and block source fields`() {
        for ((name, count, size) in listOf(Triple("s26-motion-photo.heic", "11", "144"), Triple("s26-camera.jpg", "10", "132"))) {
            ByteReader.open(File("src/test/resources/c2pa/$name")).use { reader ->
                val node = SefdBoxDecoder.decode(reader, "sefd", 0, 0, reader.length, emptyList())
                assertEquals("107", node.fields.find { it.name == "version" }?.value)
                assertEquals(count, node.fields.find { it.name == "field_count" }?.value)
                assertEquals(size, node.fields.find { it.name == "sef_size" }?.value)
                val entry = assertNotNull(node.fields.find { it.name == "directory[0].offset" })
                assertEquals(if (count == "11") "508" else "440", entry.value)
                val first = node.children.first()
                assertEquals("22", first.fields.find { it.name == "name_size" }?.value)
                assertEquals("Original_Path_Hash_Key", first.fields.find { it.name == "name" }?.value)
                assertEquals(first.offset + 8, first.fields.first { it.name == "name" }.offset)
            }
        }
    }
    @Test fun `unknown binary payload has raw preview and explicit unparsed state`() {
        val bytes = hex("00003412 02000000 7800 ff01 53454648 01000000 01000000 00003412 0c000000 0c000000 18000000 53454654")
        byteReaderOf(bytes).use { reader ->
            val node = SefdBoxDecoder.decode(reader, "sefd", 0, 0, bytes.size.toLong(), emptyList())
            val child = node.children.single()
            assertEquals("ff 01", child.fields.find { it.name == "raw_payload" }?.value)
            assertNotNull(child.fields.find { it.name == "parsing_status" })
            assertEquals("2", child.fields.find { it.name == "data_length" }?.value)
        }
    }
    @Test fun `file bounds are checked before reading trailer`() {
        byteReaderOf(ByteArray(20)).use { reader ->
            assertTrue(SefdBoxDecoder.decode(reader, "sefd", 0, 0, 200, emptyList()).warnings.isNotEmpty())
        }
    }
    @Test fun `directory cannot consume trailer and blocks cannot overlap SEFH`() {
        val partialDirectory = hex("53454648 01000000 01000000 00003412 00000000 14000000 53454654")
        byteReaderOf(partialDirectory).use { reader ->
            val node = SefdBoxDecoder.decode(reader, "sefd", 0, 0, partialDirectory.size.toLong(), emptyList())
            assertTrue(node.warnings.isNotEmpty())
            assertTrue(node.children.isEmpty())
            assertTrue(node.fields.none { it.name.startsWith("directory[") })
        }
        val overlapping = hex("00003412 02000000 7800 ff01 53454648 01000000 01000000 00003412 00000000 0c000000 18000000 53454654")
        byteReaderOf(overlapping).use { reader ->
            val node = SefdBoxDecoder.decode(reader, "sefd", 0, 0, overlapping.size.toLong(), emptyList())
            assertTrue(node.children.isEmpty())
            assertTrue(node.warnings.isNotEmpty())
        }
    }

}
