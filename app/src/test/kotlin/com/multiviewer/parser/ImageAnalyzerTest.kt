package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageAnalyzerTest {
    @Test
    fun `hasThumbnailReference is true when iref has a thmb entry, even if that item's bytes are not JPEG`() {
        // File content is all zero bytes — the "thumbnail item" payload (at offset 40, length 150)
        // is non-JPEG (no 0xFF 0xD8 anywhere), so Strategy 1's magic-byte check will reject it,
        // and Strategies 2/3 have nothing to find either. hasThumbnailReference must still be true
        // because it reflects the iref/thmb *structure*, not decode success.
        val file = File.createTempFile("image-analyzer-thumb-ref-test", ".heic")
        file.deleteOnExit()
        file.writeBytes(ByteArray(300))

        val extent = BoxNode(
            type = "extent", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("offset", "40", 0, 0), BoxField("length", "150", 0, 0)),
        )
        val ilocItem1 = BoxNode(
            type = "item_1", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("construction_method", "0", 0, 0)),
            children = listOf(extent),
        )
        val iloc = BoxNode(type = "iloc", offset = 0, headerSize = 0, size = 0, children = listOf(ilocItem1))
        val infe = BoxNode(
            type = "infe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("item_ID", "1", 0, 0), BoxField("item_type", "hvc1", 0, 0)),
        )
        val iinf = BoxNode(type = "iinf", offset = 0, headerSize = 0, size = 0, children = listOf(infe))
        val thmb = BoxNode(
            type = "thmb", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("from_item_ID", "1", 0, 0), BoxField("to_item_ID[0]", "99", 0, 0)),
        )
        val iref = BoxNode(type = "iref", offset = 0, headerSize = 0, size = 0, children = listOf(thmb))
        val pitm = BoxNode(type = "pitm", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("primary_item_ID", "99", 0, 0)))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(pitm, iloc, iinf, iref))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = file.length(), children = listOf(meta))

        val forensic = ImageAnalyzer.analyze(file, root)

        assertTrue(forensic.hasThumbnailReference)
        assertEquals(null, forensic.embeddedThumbnail)
    }

    @Test
    fun `hasThumbnailReference is false when there is no iref box`() {
        val file = File.createTempFile("image-analyzer-no-thumb-ref-test", ".heic")
        file.deleteOnExit()
        file.writeBytes(ByteArray(300))

        val extent = BoxNode(
            type = "extent", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("offset", "40", 0, 0), BoxField("length", "150", 0, 0)),
        )
        val ilocItem1 = BoxNode(
            type = "item_1", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("construction_method", "0", 0, 0)),
            children = listOf(extent),
        )
        val iloc = BoxNode(type = "iloc", offset = 0, headerSize = 0, size = 0, children = listOf(ilocItem1))
        val infe = BoxNode(
            type = "infe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("item_ID", "1", 0, 0), BoxField("item_type", "hvc1", 0, 0)),
        )
        val iinf = BoxNode(type = "iinf", offset = 0, headerSize = 0, size = 0, children = listOf(infe))
        val pitm = BoxNode(type = "pitm", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("primary_item_ID", "99", 0, 0)))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(pitm, iloc, iinf))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = file.length(), children = listOf(meta))

        val forensic = ImageAnalyzer.analyze(file, root)

        assertEquals(false, forensic.hasThumbnailReference)
        assertEquals(null, forensic.embeddedThumbnail)
    }

    @Test
    fun `brute-force magic-byte scan over a large file is correct and completes well under a few seconds`() {
        // Regression guard: Strategy 3's scan used to call reader.readUInt8() twice per byte
        // position (a seek+read syscall pair each) -- up to ~8 million calls on its 4MB cap, which
        // took over 3 seconds on a real HEIC file in manual testing. It now reads the region once
        // and scans it in memory, so a multi-MB scan should finish in well under a second.
        val size = 3_000_000
        val bytes = ByteArray(size) { 0x00 }
        // Scatter a few JPEG magic-byte pairs through the buffer so the scan has real matches to
        // find and attempt to decode (and reject, since none is followed by valid JPEG data),
        // rather than a best case of finding nothing at all.
        for (offset in listOf(500_000, 1_500_000, 2_500_000)) {
            bytes[offset] = 0xFF.toByte()
            bytes[offset + 1] = 0xD8.toByte()
        }
        val file = File.createTempFile("image-analyzer-bruteforce-scan-test", ".heic")
        file.deleteOnExit()
        file.writeBytes(bytes)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = file.length())

        val start = System.nanoTime()
        val forensic = ImageAnalyzer.analyze(file, root)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        assertEquals(null, forensic.embeddedThumbnail)
        assertTrue(
            elapsedMs < 3000,
            "Expected the brute-force scan over a 3MB file to complete well under 3s, took ${elapsedMs}ms",
        )
    }
}
