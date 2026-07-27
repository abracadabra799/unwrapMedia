package com.multiviewer.parser

import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ByteReaderTest {
    @Test
    fun `reads big-endian integers and fourcc at given offsets`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x18, // uint32 = 24
                0x66, 0x74, 0x79, 0x70, // "ftyp"
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2A, // uint64 = 42
                0x01, 0x02, // uint16 = 258
                0x7F,       // uint8 = 127
            )
        )
        assertEquals(24L, reader.readUInt32(0))
        assertEquals("ftyp", reader.readFourCC(4))
        assertEquals(42L, reader.readUInt64(8))
        assertEquals(258, reader.readUInt16(16))
        assertEquals(127, reader.readUInt8(18))
        assertEquals(19L, reader.length)
        reader.close()
    }

    @Test
    fun `reads a byte range`() {
        val reader = byteReaderOf(byteArrayOf(1, 2, 3, 4, 5))
        val bytes = reader.readBytes(1, 3)
        assertEquals(listOf<Byte>(2, 3, 4), bytes.toList())
        reader.close()
    }

    // The read-ahead cache is internal (see ByteReader.kt's CACHE_CHUNK_SIZE = 65536) -- these
    // pin down that reads still return correct data regardless of where they land relative to
    // that window, since that's exactly the kind of off-by-one a caching layer can get wrong
    // silently.
    @Test
    fun `reads correctly when the requested range straddles the cache chunk boundary`() {
        val bytes = ByteArray(70000) { (it % 256).toByte() }
        val reader = byteReaderOf(bytes)

        // 65536 is the cache chunk size -- read 8 bytes starting a few bytes before it, so the
        // first cache fetch covers only part of this read and a second fetch is needed for the
        // rest.
        val straddling = reader.readBytes(65530, 8)
        assertEquals((65530..65537).map { (it % 256).toByte() }, straddling.toList())
        reader.close()
    }

    @Test
    fun `reads correctly after a prior read populated a different, non-overlapping cache window`() {
        val bytes = ByteArray(200000) { (it % 256).toByte() }
        val reader = byteReaderOf(bytes)

        reader.readUInt8(0) // populates the cache with [0, 65536)
        val farAway = reader.readBytes(150000, 4) // forces a cache miss, well outside that window
        assertEquals(bytes.slice(150000 until 150004), farAway.toList())
        reader.close()
    }

    @Test
    fun `a read larger than the cache chunk size still returns correct data`() {
        val bytes = ByteArray(200000) { (it % 256).toByte() }
        val reader = byteReaderOf(bytes)

        val big = reader.readBytes(10, 100000) // larger than CACHE_CHUNK_SIZE -- bypasses the cache
        assertEquals((10 until 100010).map { (it % 256).toByte() }, big.toList())
        reader.close()
    }

    @Test
    fun `reading past end of file still throws EOFException, same as before caching existed`() {
        val reader = byteReaderOf(byteArrayOf(1, 2, 3))
        assertFailsWith<EOFException> { reader.readBytes(1, 10) }
        reader.close()
    }
}
