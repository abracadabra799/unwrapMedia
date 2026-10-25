package com.multiviewer.parser

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TiffDngDetailsTest {
    private fun fields(bytes: ByteArray): List<BoxField> = byteReaderOf(bytes).use {
        decodeTiff(it, 0, bytes.size.toLong()).first().fields
    }

    private fun single(type: Int, count: Long, payload: ByteArray, little: Boolean = true, tag: Int = 341): ByteArray {
        val b = ByteBuffer.allocate(26 + if (payload.size > 4) payload.size else 0)
            .order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        b.put(if (little) "II".toByteArray() else "MM".toByteArray()).putShort(42).putInt(8)
        b.putShort(1).putShort(tag.toShort()).putShort(type.toShort()).putInt(count.toInt())
        if (payload.size <= 4) {
            b.put(payload)
            while (b.position() < 22) b.put(0)
        } else {
            b.putInt(26)
        }
        b.putInt(0)
        if (payload.size > 4) b.put(payload)
        return b.array()
    }

    private fun exifSubIfdBytes(tag: Int, type: Int, count: Long, payload: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        b.put("II".toByteArray()).putShort(42).putInt(8)
        b.putShort(1).putShort(0x8769.toShort()).putShort(4).putInt(1).putInt(26)
        b.putInt(0)
        b.putShort(1).putShort(tag.toShort()).putShort(type.toShort()).putInt(count.toInt())
        if (payload.size <= 4) {
            b.put(payload)
            while (b.position() < 44) b.put(0)
        } else {
            b.putInt(48)
        }
        b.putInt(0)
        if (payload.size > 4) b.put(payload)
        return b.array()
    }

    @Test
    fun `real TIFF DNG and CFA tags decode with verified values and states in both byte orders`() {
        for (name in listOf("le", "be")) {
            val data = javaClass.getResourceAsStream("/tiff-dng/$name.tif")!!.use { it.readBytes() }
            val f = fields(data).associateBy { it.name }

            assertEquals("1.4.0.0", f["DNGVersion"]?.value, "$name DNGVersion")
            assertEquals(ValueInterpretation.PARSED, f["DNGVersion"]?.interpretation)

            assertEquals("1.1.0.0", f["DNGBackwardVersion"]?.value, "$name DNGBackwardVersion")
            assertEquals(ValueInterpretation.PARSED, f["DNGBackwardVersion"]?.interpretation)

            assertEquals("2 x 2", f["CFARepeatPatternDim"]?.value, "$name CFARepeatPatternDim")
            assertEquals(ValueInterpretation.PARSED, f["CFARepeatPatternDim"]?.interpretation)

            assertEquals("0, 1, 1, 2 (Red, Green, Green, Blue)", f["CFAPattern"]?.value, "$name CFAPattern")
            assertEquals(ValueInterpretation.PARSED, f["CFAPattern"]?.interpretation)

            assertEquals("0, 1, 2 (Red, Green, Blue)", f["CFAPlaneColor"]?.value, "$name CFAPlaneColor")
            assertEquals(ValueInterpretation.PARSED, f["CFAPlaneColor"]?.interpretation)

            assertEquals("Rectangular", f["CFALayout"]?.value, "$name CFALayout")
            assertEquals(ValueInterpretation.PARSED, f["CFALayout"]?.interpretation)

            assertEquals("Standard light A", f["CalibrationIlluminant1"]?.value, "$name CalibrationIlluminant1")
            assertEquals(ValueInterpretation.PARSED, f["CalibrationIlluminant1"]?.interpretation)

            assertEquals("D65", f["CalibrationIlluminant2"]?.value, "$name CalibrationIlluminant2")
            assertEquals(ValueInterpretation.PARSED, f["CalibrationIlluminant2"]?.interpretation)

            assertEquals("sRGB", f["ColorimetricReference"]?.value, "$name ColorimetricReference")
            assertEquals(ValueInterpretation.PARSED, f["ColorimetricReference"]?.interpretation)

            assertEquals("allowCopying", f["ProfileEmbedPolicy"]?.value, "$name ProfileEmbedPolicy")
            assertEquals(ValueInterpretation.PARSED, f["ProfileEmbedPolicy"]?.interpretation)

            assertEquals("00 00 00 01", f["OpcodeList1"]?.value, "$name OpcodeList1")
            assertEquals(ValueInterpretation.UNINTERPRETED, f["OpcodeList1"]?.interpretation)
        }
    }

    @Test
    fun `EXIF single-byte enum tags FileSource and SceneType are interpreted`() {
        val fileSourceBytes = exifSubIfdBytes(tag = 0xA300, type = 7, count = 1, payload = byteArrayOf(0x03))
        val reader1 = byteReaderOf(fileSourceBytes)
        val ifd0_1 = decodeTiff(reader1, 0, fileSourceBytes.size.toLong()).first()
        val exifIfd1 = ifd0_1.children.first { it.type == "Exif" }
        val field1 = exifIfd1.fields.first { it.name == "FileSource" }
        assertEquals("Digital Camera", field1.value)
        assertEquals(ValueInterpretation.PARSED, field1.interpretation)
        reader1.close()

        val sceneTypeBytes = exifSubIfdBytes(tag = 0xA301, type = 7, count = 1, payload = byteArrayOf(0x01))
        val reader2 = byteReaderOf(sceneTypeBytes)
        val ifd0_2 = decodeTiff(reader2, 0, sceneTypeBytes.size.toLong()).first()
        val exifIfd2 = ifd0_2.children.first { it.type == "Exif" }
        val field2 = exifIfd2.fields.first { it.name == "SceneType" }
        assertEquals("Directly photographed", field2.value)
        assertEquals(ValueInterpretation.PARSED, field2.interpretation)
        reader2.close()
    }

    @Test
    fun `empty TIFF tags with count 0 report empty value and UNPARSABLE`() {
        val emptyTagBytes = single(type = 3, count = 0, payload = byteArrayOf(), tag = 0x0100)
        val f = fields(emptyTagBytes).first()
        assertEquals("빈 값 / Empty value", f.value)
        assertEquals(ValueInterpretation.UNPARSABLE, f.interpretation)
    }

    @Test
    fun `large arrays exceeding 256 elements are capped with omission notice and UNINTERPRETED`() {
        val shortPayload = ByteArray(257 * 2) { 1 }
        val shortTagBytes = single(type = 3, count = 257, payload = shortPayload, tag = 0x0118)
        val fShort = fields(shortTagBytes).first()
        assertTrue(fShort.value.contains("256/257 표시 / shown; 나머지 미해석 / remainder uninterpreted"))
        assertEquals(ValueInterpretation.UNINTERPRETED, fShort.interpretation)

        val bytePayload = ByteArray(300) { 42 }
        val byteTagBytes = single(type = 1, count = 300, payload = bytePayload, tag = 0x0102)
        val fByte = fields(byteTagBytes).first()
        assertTrue(fByte.value.contains("256/300 표시 / shown; 나머지 미해석 / remainder uninterpreted"))
        assertEquals(ValueInterpretation.UNINTERPRETED, fByte.interpretation)
    }

    @Test
    fun `unknown field types do not crash and report unknown type with UNINTERPRETED`() {
        val unknownTypeBytes = single(type = 99, count = 4, payload = byteArrayOf(1, 2, 3, 4), tag = 0x0100)
        val f = fields(unknownTypeBytes).first()
        assertTrue(f.value.contains("Unknown type (99)"))
        assertEquals(ValueInterpretation.UNINTERPRETED, f.interpretation)
    }

    @Test
    fun `BigTIFF magic 43 produces an unsupported warning root node instead of reading corrupted IFD`() {
        val bigTiffBytes = byteArrayOf(
            0x49, 0x49, 0x2B, 0x00, // "II", magic 43 (BigTIFF)
            0x08, 0x00, 0x00, 0x00, // offset size 8, reserved 0
            0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // 64-bit IFD0 offset
        )
        val reader = byteReaderOf(bigTiffBytes)
        val nodes = decodeTiff(reader, 0, bigTiffBytes.size.toLong())
        assertEquals(1, nodes.size)
        val root = nodes.first()
        assertEquals("BigTIFF", root.type)
        assertTrue(root.warnings.any { it.contains("BigTIFF format is currently unsupported") })
        reader.close()
    }

    @Test
    fun `SubIFD tag enforces recursion cap`() {
        val payload = ByteBuffer.allocate(100 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 100) payload.putInt(0)
        val subIfdBytes = single(type = 4, count = 100, payload = payload.array(), tag = 0x014A)
        val reader = byteReaderOf(subIfdBytes)
        val nodes = decodeTiff(reader, 0, subIfdBytes.size.toLong())
        val ifd0 = nodes.first()
        val subIfds = ifd0.children.filter { it.type.startsWith("SubIFD") }
        assertTrue(subIfds.size <= 64)
        reader.close()
    }
}
