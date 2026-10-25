package com.multiviewer.parser

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TiffNumericDetailsTest {
    private fun fields(bytes: ByteArray): List<BoxField> = byteReaderOf(bytes).use {
        decodeTiff(it, 0, bytes.size.toLong()).first().fields
    }

    private fun single(type: Int, count: Long, payload: ByteArray, little: Boolean = true): ByteArray {
        val b = ByteBuffer.allocate(26 + if (payload.size > 4) payload.size else 0)
            .order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        b.put(if (little) "II".toByteArray() else "MM".toByteArray()).putShort(42).putInt(8)
        b.putShort(1).putShort(341).putShort(type.toShort()).putInt(count.toInt())
        if (payload.size <= 4) { b.put(payload); while (b.position() < 22) b.put(0) }
        else b.putInt(26)
        b.putInt(0)
        if (payload.size > 4) b.put(payload)
        return b.array()
    }

    @Test fun `real TIFF numeric arrays decode with original spans in both byte orders`() {
        for (name in listOf("le", "be")) {
            val data = javaClass.getResourceAsStream("/tiff-numeric/$name.tif")!!.use { it.readBytes() }
            val f = fields(data).associateBy { it.name }
            for ((key, value, offset, length) in listOf(
                listOf("SMinSampleValue", "-128, -1, 0, 127", 126L, 4L),
                listOf("SMaxSampleValue", "-1.5, 0.25, 65536.0", 182L, 12L),
                listOf("NoiseProfile", "0.125, -1.0E-6", 194L, 16L)
            )) {
                assertEquals(value, f[key]!!.value, "$name $key")
                assertEquals(offset, f[key]!!.offset)
                assertEquals(length, f[key]!!.length)
                assertEquals(ValueInterpretation.PARSED, f[key]!!.interpretation)
            }
            assertEquals("1.25", f["Tag 0xfde8"]!!.value)
            assertEquals(ValueInterpretation.UNINTERPRETED, f["Tag 0xfde8"]!!.interpretation)
        }
    }

    @Test fun `IEEE special values and inline floats retain their numeric representation`() {
        for (little in listOf(true, false)) {
            val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            val inline = ByteBuffer.allocate(4).order(order).putFloat(-0.0f).array()
            assertEquals("-0.0", fields(single(11, 1, inline, little)).single().value)
            val doubles = ByteBuffer.allocate(32).order(order)
                .putDouble(-0.0).putDouble(Double.POSITIVE_INFINITY)
                .putDouble(Double.NEGATIVE_INFINITY).putDouble(Double.NaN).array()
            assertEquals("-0.0, Infinity, -Infinity, NaN", fields(single(12, 4, doubles, little)).single().value)
        }
    }

    @Test fun `long numeric arrays report omitted values and preserve source span`() {
        val f = fields(single(6, 257, ByteArray(257) { 1 })).single()
        assertEquals(ValueInterpretation.UNINTERPRETED, f.interpretation)
        assertTrue(f.value.contains("256/257"))
        assertEquals(257L, f.length)
        assertTrue(f.value.length < 1100)
    }

    @Test fun `out of bounds doubles and unsigned huge counts are not parsed`() {
        val data = single(12, 2, ByteArray(16)).dropLast(1).toByteArray()
        assertEquals(ValueInterpretation.UNPARSABLE, fields(data).single().interpretation)
        assertEquals(ValueInterpretation.UNPARSABLE, fields(single(12, 0xffffffffL, ByteArray(8))).single().interpretation)
    }

    @Test fun `undefined data stays uninterpreted and default TIFF still decodes`() {
        assertEquals(ValueInterpretation.UNINTERPRETED, fields(single(7, 2, byteArrayOf(1, 2))).single().interpretation)
        val data = javaClass.getResourceAsStream("/tiff-numeric/default.tif")!!.use { it.readBytes() }
        assertEquals("2", fields(data).first { it.name == "ImageWidth" }.value)
    }
}
