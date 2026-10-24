package com.multiviewer.parser.conformance.isobmff

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.fileOf
import kotlin.test.Test
import kotlin.test.assertEquals

class IsoBmffTableCursorTest {
    private val prefix = ByteArray(5) { 0x55 } // table does not start at offset 0

    private fun <T> withReader(table: ByteArray, block: (ByteReader) -> T): T =
        ByteReader.open(fileOf(prefix + table + ByteArray(3) { 0x66 })).use(block)

    private fun be64(v: Long) = be32(v ushr 32) + be32(v)

    @Test
    fun `32-bit three-field entries read correctly across refill boundaries`() {
        val n = 10
        val bytes = (0 until n).fold(ByteArray(0)) { acc, i -> acc + be32(i + 1L) + be32(100L + i) + be32(0xFFFFFFF0L + (i % 4)) }
        withReader(bytes) { r ->
            val c = TableCursor(r, prefix.size.toLong(), n.toLong(), 96, bufEntries = 3)
            for (i in 0 until n) {
                assertEquals(i + 1L, c.u32(i.toLong(), 0), "first $i")
                // lookahead one entry, as the stsc walk does
                if (i + 1 < n) assertEquals(i + 2L, c.u32(i + 1L, 0), "next $i")
                assertEquals(100L + i, c.u32(i.toLong(), 1), "spc $i")
                assertEquals(0xFFFFFFF0L + (i % 4), c.u32(i.toLong(), 2), "sdi $i")
            }
            // backward access after the window moved still returns the right value
            assertEquals(1L, c.u32(0, 0))
            assertEquals(109L, c.u32(9, 1))
        }
    }

    @Test
    fun `co64 entries read as 64-bit values across refills`() {
        val values = listOf(0L, 1L, 0x1_0000_0000L, 0x7FFF_FFFF_FFFF_FFFFL, -1L, 0x1234_5678_9ABCL, 42L)
        withReader(values.fold(ByteArray(0)) { a, v -> a + be64(v) }) { r ->
            val c = TableCursor(r, prefix.size.toLong(), values.size.toLong(), 64, bufEntries = 2)
            values.forEachIndexed { i, v -> assertEquals(v, c.value(i.toLong()), "co64 $i") }
        }
    }

    @Test
    fun `stco, stz2 16 and 8 bit entries`() {
        withReader(be32(7) + be32(0xFFFFFFFFL) + be32(9)) { r ->
            val c = TableCursor(r, prefix.size.toLong(), 3, 32, bufEntries = 2)
            assertEquals(listOf(7L, 0xFFFFFFFFL, 9L), (0L until 3).map(c::value))
        }
        withReader(be16(1) + be16(0xFFFF) + be16(3) + be16(0x8000) + be16(5)) { r ->
            val c = TableCursor(r, prefix.size.toLong(), 5, 16, bufEntries = 2)
            assertEquals(listOf(1L, 0xFFFFL, 3L, 0x8000L, 5L), (0L until 5).map(c::value))
        }
        withReader(byteArrayOf(1, 0xFF.toByte(), 3, 0x80.toByte(), 5)) { r ->
            val c = TableCursor(r, prefix.size.toLong(), 5, 8, bufEntries = 2)
            assertEquals(listOf(1L, 0xFFL, 3L, 0x80L, 5L), (0L until 5).map(c::value))
        }
    }

    @Test
    fun `stz2 4-bit entries, high nibble first, odd count, across refills`() {
        val nibbles = listOf(1, 15, 0, 7, 8, 2, 9, 3, 14)
        val bytes = nibbles.chunked(2).map { p -> ((p[0] shl 4) or (p.getOrElse(1) { 0 })).toByte() }.toByteArray()
        withReader(bytes) { r ->
            listOf(1, 2, 3, 4096).forEach { buf ->
                val c = TableCursor(r, prefix.size.toLong(), nibbles.size.toLong(), 4, bufEntries = buf)
                assertEquals(nibbles.map { it.toLong() }, (0L until nibbles.size).map(c::value), "buf $buf")
            }
        }
    }
}
