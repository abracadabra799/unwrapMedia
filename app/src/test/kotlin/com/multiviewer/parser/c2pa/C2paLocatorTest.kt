package com.multiviewer.parser.c2pa

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.byteReaderOf
import com.multiviewer.parser.hex
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class C2paLocatorTest {
    private fun fixture(name: String) = File(javaClass.getResource("/c2pa/$name")!!.toURI())
    private fun locate(name: String): C2paStoreLocation? {
        val f = fixture(name)
        val root = parseFile(f)
        return ByteReader.open(f).use { locateC2paStore(it, root) }
    }
    private fun topLabel(loc: C2paStoreLocation) = parseJumbfBoxes(loc.bytes).single().label

    @Test
    fun `single APP11 JPEG`() {
        val loc = assertNotNull(locate("C.jpg"))
        assertEquals(C2paContainer.JPEG, loc.container)
        assertEquals(45872, loc.bytes.size)
        assertEquals(listOf(20L), loc.anchorOffsets)
        assertEquals(32L, loc.offsetMap.toFile(0))
        assertEquals("c2pa", topLabel(loc))
        assertTrue(loc.warnings.isEmpty())
    }

    @Test
    fun `two APP11 segments are reassembled with a piecewise offset map`() {
        val loc = assertNotNull(locate("CA.jpg"))
        assertEquals(117241, loc.bytes.size)
        assertEquals(listOf(20L, 64032L), loc.anchorOffsets)
        assertEquals(32L, loc.offsetMap.toFile(0))
        assertEquals(64031L, loc.offsetMap.toFile(63999))
        assertEquals(64052L, loc.offsetMap.toFile(64000))
        assertTrue(loc.offsetMap.crossesSpan(63990, 20))
        assertFalse(loc.offsetMap.crossesSpan(0, 64000))
        assertEquals("c2pa", topLabel(loc))
        assertTrue(loc.warnings.isEmpty())
    }

    @Test
    fun `BMFF uuid store`() {
        val mp4 = assertNotNull(locate("video1.mp4"))
        assertEquals(C2paContainer.BMFF, mp4.container)
        assertEquals(30617, mp4.bytes.size)
        assertEquals(69L, mp4.offsetMap.toFile(0))
        assertEquals(listOf(24L), mp4.anchorOffsets)
        val heif = assertNotNull(locate("signed.heif"))
        assertEquals(13514, heif.bytes.size)
        assertEquals(69L, heif.offsetMap.toFile(0))
        assertEquals("c2pa", topLabel(heif))
    }

    @Test
    fun `WebP chunk store`() {
        val loc = assertNotNull(locate("signed_small.webp"))
        assertEquals(C2paContainer.WEBP, loc.container)
        assertEquals(13438, loc.bytes.size)
        assertEquals(50416L, loc.offsetMap.toFile(0))
        assertEquals(listOf(50408L), loc.anchorOffsets)
    }

    @Test
    fun `no manifest`() {
        assertNull(locate("no_manifest.jpg"))
    }

    // A 38-byte 'c2pa' superbox (jumb + jumd labelled "c2pa") split 30 + 8 across two JP segments.
    private val superbox = hex("000000266a756d62") + hex("0000001e6a756d64") +
        hex("6332706100110010800000aa00389b71") + hex("03") + hex("6332706100")

    private fun seg(z: Int, data: ByteArray): ByteArray {
        val payload = "JP".toByteArray() + hex("0001") + byteArrayOf(0, 0, 0, z.toByte()) + data
        val len = payload.size + 2
        return hex("ffeb") + byteArrayOf((len shr 8).toByte(), len.toByte()) + payload
    }

    @Test
    fun `synthetic APP11 sequence gap warns but still reassembles`() {
        val file = hex("ffd8") + seg(1, superbox.copyOfRange(0, 30)) + seg(3, superbox.copyOfRange(0, 8) + superbox.copyOfRange(30, 38))
        val reader = byteReaderOf(file)
        val loc = assertNotNull(readJpegC2paStore(reader, listOf(2L, 44L)))
        assertEquals(superbox.toList(), loc.bytes.toList())
        assertTrue(loc.warnings.single().contains("gap"))
        reader.close()
    }

    @Test
    fun `synthetic APP11 length mismatch and non-c2pa store`() {
        // two stray bytes after the superbox: reassembled 40 bytes vs LBox 38
        val padded = hex("ffd8") + seg(1, superbox + hex("0000"))
        val reader = byteReaderOf(padded)
        val loc = assertNotNull(readJpegC2paStore(reader, listOf(2L)))
        assertTrue(loc.warnings.single().contains("declares 38"))
        reader.close()
        val otherLabel = superbox.copyOf().also { it[33] = 'x'.code.toByte() } // label "x2pa"
        val r2 = byteReaderOf(hex("ffd8") + seg(1, otherLabel))
        assertNull(readJpegC2paStore(r2, listOf(2L)))
        r2.close()
    }
    @Test
    fun `missing initial sequence and multiple groups warn`() {
        byteReaderOf(hex("ffd8") + seg(2, superbox)).use { reader ->
            val loc = assertNotNull(readJpegC2paStore(reader, listOf(2L)))
            assertTrue(loc.warnings.any { it.contains("sequence") })
        }
        val first = seg(1, superbox)
        val second = seg(1, superbox).also { it[7] = 2 }
        byteReaderOf(hex("ffd8") + first + second).use { reader ->
            val loc = assertNotNull(readJpegC2paStore(reader, listOf(2L, 2L + first.size)))
            assertTrue(loc.warnings.any { it.contains("groups") })
        }
    }

    @Test
    fun `truncated store remains visible with warning`() {
        val declaredLarger = superbox.copyOf().also { it[3] = 100 }
        byteReaderOf(hex("ffd8") + seg(1, declaredLarger)).use { reader ->
            val loc = assertNotNull(readJpegC2paStore(reader, listOf(2L)))
            assertTrue(loc.warnings.any { it.contains("declares 100") })
        }
    }

    @Test
    fun `truncated WebP payload is bounded to available bytes`() {
        byteReaderOf("C2PA".toByteArray() + hex("64000000") + superbox).use { reader ->
            val loc = assertNotNull(readWebpC2paStore(reader, 0, 100))
            assertEquals(38, loc.bytes.size)
            assertTrue(loc.warnings.isNotEmpty())
        }
    }

    @Test
    fun `duplicate APP11 sequence retains first and warns`() {
        val first = seg(1, superbox)
        byteReaderOf(hex("ffd8") + first + first).use { reader ->
            val loc = assertNotNull(readJpegC2paStore(reader, listOf(2L, 2L + first.size)))
            assertEquals(superbox.toList(), loc.bytes.toList())
            assertTrue(loc.warnings.any { it.contains("Duplicate") })
        }
    }
}
