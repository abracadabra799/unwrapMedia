package com.multiviewer.parser.c2pa

import com.multiviewer.parser.hex
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CborTest {
    private fun d(h: String) = decodeCbor(hex(h))
    private fun int(v: Long) = CborInt(BigInteger.valueOf(v))

    @Test
    fun `RFC 8949 Appendix A integers`() {
        assertEquals(int(0), d("00"))
        assertEquals(int(23), d("17"))
        assertEquals(int(24), d("1818"))
        assertEquals(int(1000), d("1903e8"))
        assertEquals(int(1000000), d("1a000f4240"))
        assertEquals(int(1000000000000), d("1b000000e8d4a51000"))
        assertEquals(CborInt(BigInteger("18446744073709551615")), d("1bffffffffffffffff"))
        assertEquals(int(-1), d("20"))
        assertEquals(int(-100), d("3863"))
        assertEquals(int(-1000), d("3903e7"))
        assertEquals(CborInt(BigInteger("-18446744073709551616")), d("3bffffffffffffffff"))
    }

    @Test
    fun `RFC 8949 Appendix A floats and simple values`() {
        assertEquals(CborFloat(0.0), d("f90000"))
        assertEquals(-0.0, (d("f98000") as CborFloat).value)
        assertTrue(1.0 / (d("f98000") as CborFloat).value < 0) // negative zero
        assertEquals(CborFloat(1.0), d("f93c00"))
        assertEquals(CborFloat(1.5), d("f93e00"))
        assertEquals(CborFloat(65504.0), d("f97bff"))
        assertEquals(CborFloat(5.960464477539063e-8), d("f90001"))
        assertEquals(CborFloat(0.00006103515625), d("f90400"))
        assertEquals(CborFloat(-4.0), d("f9c400"))
        assertEquals(CborFloat(100000.0), d("fa47c35000"))
        assertEquals(CborFloat(1.1), d("fb3ff199999999999a"))
        assertEquals(CborFloat(Double.POSITIVE_INFINITY), d("f97c00"))
        assertTrue((d("f97e00") as CborFloat).value.isNaN())
        assertEquals(CborBool(false), d("f4"))
        assertEquals(CborBool(true), d("f5"))
        assertEquals(CborNull, d("f6"))
        assertEquals(CborUndefined, d("f7"))
        assertEquals(CborSimple(16), d("f0"))
        assertEquals(CborSimple(255), d("f8ff"))
    }

    @Test
    fun `RFC 8949 Appendix A strings, arrays, maps, tags`() {
        assertEquals(CborBytes(hex("01020304")), d("4401020304"))
        assertEquals(CborText(""), d("60"))
        assertEquals(CborText("IETF"), d("6449455446"))
        assertEquals(CborText("ü"), d("62c3bc"))
        assertEquals(CborText("水"), d("63e6b0b4"))
        assertEquals(CborArray(listOf(int(1), CborArray(listOf(int(2), int(3))), CborArray(listOf(int(4), int(5))))), d("8301820203820405"))
        val m = d("a26161016162820203") as CborMap
        assertEquals(int(1), m["a"])
        assertEquals(CborArray(listOf(int(2), int(3))), m["b"])
        assertEquals(int(2), (d("a201020304") as CborMap)[1L])
        assertEquals(CborTag(BigInteger.ONE, int(1363896240)), d("c11a514b67b0"))
        assertEquals(CborTag(BigInteger.valueOf(23), CborBytes(hex("01020304"))), d("d74401020304"))
    }

    @Test
    fun `RFC 8949 Appendix A indefinite lengths`() {
        assertEquals(CborBytes(hex("0102030405")), d("5f42010243030405ff"))
        assertEquals(CborText("streaming"), d("7f657374726561646d696e67ff"))
        assertEquals(CborArray(emptyList()), d("9fff"))
        assertEquals(
            CborArray(listOf(int(1), CborArray(listOf(int(2), int(3))), CborArray(listOf(int(4), int(5))))),
            d("9f018202039f0405ffff"),
        )
        val m = d("bf61610161629f0203ffff") as CborMap
        assertEquals(CborArray(listOf(int(2), int(3))), m["b"])
    }

    @Test
    fun `trailing bytes after the first item are ignored`() {
        assertEquals(int(1), d("01ff00"))
    }

    @Test
    fun `errors carry the byte offset`() {
        val truncated = assertFailsWith<CborException> { d("1903") }
        assertEquals(1, truncated.offset)
        assertFailsWith<CborException> { d("62c3") }          // text shorter than declared
        assertFailsWith<CborException> { d("ff") }            // lone break
        assertFailsWith<CborException> { d("5f01ff") }        // non-bytes chunk inside indefinite bytes
        assertFailsWith<CborException> { d("1f") }            // indefinite unsigned int
        assertFailsWith<CborException> { d("fc") }            // reserved additional info
        assertFailsWith<CborException> { d("f818") }          // simple value < 32 in two-byte form
        assertFailsWith<CborException> { d("a1") }            // map missing its pair
    }

    @Test
    fun `depth and item limits`() {
        val deep = hex("81".repeat(70) + "00")
        assertFailsWith<CborException> { decodeCbor(deep) }
        assertEquals(int(0), (0 until 70).fold(decodeCbor(deep, limits = CborLimits(maxDepth = 100))) { v, _ -> (v as CborArray).items[0] })
        assertFailsWith<CborException> { decodeCbor(hex("83010203"), limits = CborLimits(maxItems = 3)) }
    }

    @Test
    fun `accessors`() {
        val m = d("a26161016162820203") as CborMap
        assertEquals(1L, m["a"]?.asLong())
        assertEquals(2, m["b"]?.asArray()?.size)
        assertEquals("IETF", d("6449455446").asText())
        assertEquals(null, d("01").asText())
    }

    @Test
    fun `diagnostic notation`() {
        assertEquals("{\"a\": 1, \"b\": [2, 3]}", d("a26161016162820203").toDiagnostic())
        assertEquals("h'01020304'", d("4401020304").toDiagnostic())
        assertEquals("h'0102'…(+2 bytes)", d("4401020304").toDiagnostic(maxBytes = 2))
        assertEquals("1(1363896240)", d("c11a514b67b0").toDiagnostic())
        assertEquals("[true, false, null, undefined, simple(16), 1.5]", d("86f5f4f6f7f0f93e00").toDiagnostic())
        assertEquals("\"a\\\"b\"", CborText("a\"b").toDiagnostic())
    }
    @Test
    fun `invalid UTF8 is rejected instead of replaced`() {
        assertFailsWith<CborException> { d("61ff") }
        assertFailsWith<CborException> { d("7f61c361bcff") }
    }

    @Test
    fun `indefinite strings cannot contain indefinite chunks`() {
        assertFailsWith<CborException> { d("5f5f4101ffff") }
        assertFailsWith<CborException> { d("7f7f6161ffff") }
    }

    @Test
    fun `invalid decode ranges report CBOR errors`() {
        assertFailsWith<CborException> { decodeCbor(hex("00"), start = -1) }
        assertFailsWith<CborException> { decodeCbor(hex("00"), end = 2) }
        assertFailsWith<CborException> { decodeCbor(hex("00"), start = 1, end = 0) }
    }
}
