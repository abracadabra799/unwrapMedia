package com.multiviewer.parser

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BinaryPlistDecoderTest {

    @Test
    fun `decodes simple bplist with dictionary containing integer and string`() {
        val bytes = buildSimpleDictPlist()
        val reader = byteReaderOf(bytes)
        val node = decodeBinaryPlist(reader, 0, bytes.size.toLong())

        assertEquals("BinaryPlist", node.type)
        assertEquals(0L, node.offset)
        assertEquals(bytes.size.toLong(), node.size)
        assertTrue(node.warnings.isEmpty())

        val ageField = node.fields.find { it.name == "Age" }
        assertEquals("42", ageField?.value)

        val nameField = node.fields.find { it.name == "Name" }
        assertEquals("Apple", nameField?.value)
        reader.close()
    }

    @Test
    fun `decodes nested arrays and dictionaries with CMTime interpretation`() {
        val bytes = buildNestedCmTimePlist()
        val reader = byteReaderOf(bytes)
        val node = decodeBinaryPlist(reader, 0, bytes.size.toLong())

        assertEquals("BinaryPlist", node.type)
        val timeChild = node.children.find { it.type == "time" }
        assertTrue(timeChild != null, "time node should exist")
        assertTrue(timeChild.fields.any { it.name == "value" && it.value == "1000" })
        assertTrue(timeChild.fields.any { it.name == "timescale" && it.value == "600" })
        assertTrue(timeChild.summary?.contains("1000/600") == true)
        reader.close()
    }

    @Test
    fun `handles invalid magic or short payload with warning`() {
        val bytes = "not_a_plist".toByteArray()
        val reader = byteReaderOf(bytes)
        val node = decodeBinaryPlist(reader, 0, bytes.size.toLong())

        assertEquals("BinaryPlist", node.type)
        assertTrue(node.warnings.any { it.contains("magic") || it.contains("short") })
        reader.close()
    }

    @Test
    fun `handles cyclic object references gracefully without infinite loop`() {
        val bytes = buildCyclicArrayPlist()
        val reader = byteReaderOf(bytes)
        val node = decodeBinaryPlist(reader, 0, bytes.size.toLong(), BinaryPlistLimits(maxDepth = 5))

        assertEquals("BinaryPlist", node.type)
        assertTrue(node.warnings.isNotEmpty() || node.children.isNotEmpty())
        reader.close()
    }

    @Test
    fun `enforces limits on maxDepth and maxObjects`() {
        val bytes = buildDeeplyNestedArrayPlist(10)
        val reader = byteReaderOf(bytes)
        val node = decodeBinaryPlist(reader, 0, bytes.size.toLong(), BinaryPlistLimits(maxDepth = 3))

        assertEquals("BinaryPlist", node.type)
        assertTrue(node.warnings.any { it.contains("depth") })
        reader.close()
    }

    private fun buildSimpleDictPlist(): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write("bplist00".toByteArray(StandardCharsets.US_ASCII))

        val offsets = mutableListOf<Int>()

        // Object 0: Dict (2 entries)
        offsets.add(baos.size())
        baos.write(0xD2) // Dict with 2 items
        baos.write(1) // key 0 -> object 1 ("Age")
        baos.write(2) // key 1 -> object 2 ("Name")
        baos.write(3) // val 0 -> object 3 (42)
        baos.write(4) // val 1 -> object 4 ("Apple")

        // Object 1: "Age" (ASCII)
        offsets.add(baos.size())
        baos.write(0x53) // ASCII, len 3
        baos.write("Age".toByteArray(StandardCharsets.US_ASCII))

        // Object 2: "Name" (ASCII)
        offsets.add(baos.size())
        baos.write(0x54) // ASCII, len 4
        baos.write("Name".toByteArray(StandardCharsets.US_ASCII))

        // Object 3: 42 (int, 1 byte)
        offsets.add(baos.size())
        baos.write(0x10) // int, 2^0 = 1 byte
        baos.write(42)

        // Object 4: "Apple" (ASCII)
        offsets.add(baos.size())
        baos.write(0x55) // ASCII, len 5
        baos.write("Apple".toByteArray(StandardCharsets.US_ASCII))

        // Offset table
        val offsetTableOffset = baos.size()
        for (offset in offsets) {
            baos.write(offset)
        }

        // 32-byte trailer
        val dos = DataOutputStream(baos)
        dos.write(ByteArray(5)) // unused
        dos.writeByte(0) // sortVersion
        dos.writeByte(1) // offsetIntSize = 1
        dos.writeByte(1) // objectRefSize = 1
        dos.writeLong(offsets.size.toLong()) // numObjects
        dos.writeLong(0L) // topObject = 0
        dos.writeLong(offsetTableOffset.toLong()) // offsetTableOffset

        return baos.toByteArray()
    }

    private fun buildNestedCmTimePlist(): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write("bplist00".toByteArray(StandardCharsets.US_ASCII))

        val offsets = mutableListOf<Int>()

        // Object 0: Root Dict (1 entry: "time" -> CMTime dict)
        offsets.add(baos.size())
        baos.write(0xD1) // dict 1 entry
        baos.write(1) // key 0 -> obj 1 ("time")
        baos.write(2) // val 0 -> obj 2 (CMTime dict)

        // Object 1: "time"
        offsets.add(baos.size())
        baos.write(0x54)
        baos.write("time".toByteArray(StandardCharsets.US_ASCII))

        // Object 2: CMTime Dict (2 entries: "value", "timescale")
        offsets.add(baos.size())
        baos.write(0xD2)
        baos.write(3) // key "value" -> obj 3
        baos.write(4) // key "timescale" -> obj 4
        baos.write(5) // val 1000 -> obj 5
        baos.write(6) // val 600 -> obj 6

        // Object 3: "value"
        offsets.add(baos.size())
        baos.write(0x55)
        baos.write("value".toByteArray(StandardCharsets.US_ASCII))

        // Object 4: "timescale"
        offsets.add(baos.size())
        baos.write(0x59)
        baos.write("timescale".toByteArray(StandardCharsets.US_ASCII))

        // Object 5: 1000 (int, 2 bytes)
        offsets.add(baos.size())
        baos.write(0x11) // int, 2^1 = 2 bytes
        baos.write((1000 shr 8) and 0xFF)
        baos.write(1000 and 0xFF)

        // Object 6: 600 (int, 2 bytes)
        offsets.add(baos.size())
        baos.write(0x11)
        baos.write((600 shr 8) and 0xFF)
        baos.write(600 and 0xFF)

        // Offset table
        val offsetTableOffset = baos.size()
        for (offset in offsets) {
            baos.write(offset)
        }

        // Trailer
        val dos = DataOutputStream(baos)
        dos.write(ByteArray(5))
        dos.writeByte(0)
        dos.writeByte(1)
        dos.writeByte(1)
        dos.writeLong(offsets.size.toLong())
        dos.writeLong(0L)
        dos.writeLong(offsetTableOffset.toLong())

        return baos.toByteArray()
    }

    private fun buildCyclicArrayPlist(): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write("bplist00".toByteArray(StandardCharsets.US_ASCII))

        val offsets = mutableListOf<Int>()

        // Object 0: Array containing object 0 (self reference)
        offsets.add(baos.size())
        baos.write(0xA1) // array 1 item
        baos.write(0) // ref to obj 0

        val offsetTableOffset = baos.size()
        for (offset in offsets) {
            baos.write(offset)
        }

        val dos = DataOutputStream(baos)
        dos.write(ByteArray(5))
        dos.writeByte(0)
        dos.writeByte(1)
        dos.writeByte(1)
        dos.writeLong(offsets.size.toLong())
        dos.writeLong(0L)
        dos.writeLong(offsetTableOffset.toLong())

        return baos.toByteArray()
    }

    private fun buildDeeplyNestedArrayPlist(depth: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write("bplist00".toByteArray(StandardCharsets.US_ASCII))

        val offsets = mutableListOf<Int>()

        for (i in 0 until depth) {
            offsets.add(baos.size())
            baos.write(0xA1)
            baos.write(i + 1)
        }
        // Leaf object
        offsets.add(baos.size())
        baos.write(0x10)
        baos.write(1)

        val offsetTableOffset = baos.size()
        for (offset in offsets) {
            baos.write(offset)
        }

        val dos = DataOutputStream(baos)
        dos.write(ByteArray(5))
        dos.writeByte(0)
        dos.writeByte(1)
        dos.writeByte(1)
        dos.writeLong(offsets.size.toLong())
        dos.writeLong(0L)
        dos.writeLong(offsetTableOffset.toLong())

        return baos.toByteArray()
    }
}
