package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeicMetaFixtureTest {
    @Test
    fun `HeicMetaFixture builds a HEIC this app's own parser can read`() {
        val fixture = HeicMetaFixture.build(xmpText = "<x:xmpmeta>fixture self-test</x:xmpmeta>")

        val tmp = java.io.File.createTempFile("heic-fixture-selftest-", ".heic")
        tmp.deleteOnExit()
        tmp.writeBytes(fixture.heicBytes)

        val root = parseFile(tmp)
        val metaNode = findFirst(root) { it.type == "meta" }
        assertTrue(metaNode != null, "Expected this app's parser to find a meta box")

        val xmpField = findFirst(root) { it.fields.any { f -> f.name == "xmp" } }
        assertTrue(xmpField != null, "Expected this app's parser to find the fixture's XMP")
        assertEquals(
            "<x:xmpmeta>fixture self-test</x:xmpmeta>",
            // Mirror MetaBoxDecoder.kt's own trimEnd(' ', Char(0)) exactly -- the fixture's XMP
            // extent may be reported with trailing padding trimmed the same way production does.
            xmpField.fields.find { it.name == "xmp" }!!.value.trimEnd(' ', Char(0)),
        )

        val primaryBytesInFile = fixture.heicBytes.copyOfRange(fixture.primaryItemOffset.toInt(), (fixture.primaryItemOffset + fixture.primaryItemLength).toInt())
        assertTrue(primaryBytesInFile.contentEquals(fixture.primaryItemBytes), "Fixture's own recorded primaryItemBytes must match what's actually at primaryItemOffset")

        // Implementer-note check: verify xmpIlocEntryOffset actually points at the XMP iloc entry's
        // item_ID field (a 2-byte big-endian value) by reading it directly out of heicBytes.
        val offset = fixture.xmpIlocEntryOffset.toInt()
        val readItemId = ((fixture.heicBytes[offset].toInt() and 0xFF) shl 8) or (fixture.heicBytes[offset + 1].toInt() and 0xFF)
        assertEquals(fixture.xmpItemId.toInt(), readItemId, "xmpIlocEntryOffset must point at the XMP item's iloc entry (item_ID field)")

        tmp.delete()
    }

    @Test
    fun `HeicMetaFixture with xmpExtentCount greater than 1 builds without crashing and writes distinct non-overlapping extents`() {
        val xmpText = "<x:xmpmeta>0123456789abcdef0123456789ABCDEF</x:xmpmeta>" // 56 bytes, divides evenly by 2
        val fixture = HeicMetaFixture.build(xmpText = xmpText, xmpExtentCount = 2)
        assertEquals(2, fixture.xmpExtentCount)

        // This app's own MetaBoxDecoder only resolves XMP text for a single-extent item
        // (MetaBoxDecoder.kt: `resolvedExtents.singleOrNull()`) -- a multi-extent item is expected to
        // NOT produce an "xmp" field via parseFile(), which is fine: this fixture shape exists solely
        // to test repointHeicXmpItem's multi-extent fallback path (Task 3), not full-content resolution.
        val tmp = java.io.File.createTempFile("heic-fixture-multiextent-", ".heic")
        tmp.deleteOnExit()
        tmp.writeBytes(fixture.heicBytes)
        val root = parseFile(tmp)
        val metaNode = findFirst(root) { it.type == "meta" }
        assertTrue(metaNode != null, "Expected the parser to still find a well-formed meta box")

        // Read the XMP item's 2 raw extents directly out of iloc and confirm they're distinct,
        // contiguous, non-overlapping sub-ranges that together cover exactly the XMP bytes -- not the
        // same range repeated twice.
        val entryStart = fixture.xmpIlocEntryOffset.toInt()
        // item_ID(2) + construction_method(2) + data_reference_index(2) + extent_count(2) = 8 bytes, then extents.
        val extentsStart = entryStart + 8
        fun readU32(pos: Int): Long {
            val bytes = fixture.heicBytes
            return ((bytes[pos].toLong() and 0xFF) shl 24) or ((bytes[pos + 1].toLong() and 0xFF) shl 16) or
                ((bytes[pos + 2].toLong() and 0xFF) shl 8) or (bytes[pos + 3].toLong() and 0xFF)
        }
        val extent0Offset = readU32(extentsStart)
        val extent0Length = readU32(extentsStart + 4)
        val extent1Offset = readU32(extentsStart + 8)
        val extent1Length = readU32(extentsStart + 12)

        assertEquals(xmpText.toByteArray(Charsets.UTF_8).size.toLong(), extent0Length + extent1Length, "The two extents' lengths must sum to the full XMP byte length")
        assertEquals(extent0Offset + extent0Length, extent1Offset, "The second extent must start exactly where the first ends (contiguous, non-overlapping)")

        val reconstructed = fixture.heicBytes.copyOfRange(extent0Offset.toInt(), (extent1Offset + extent1Length).toInt())
        assertEquals(xmpText, String(reconstructed, Charsets.UTF_8), "Concatenating the two extents' bytes must reproduce the original XMP text exactly")

        tmp.delete()
    }

    @Test
    fun `HeicMetaFixture with xmpText null omits the XMP item entirely`() {
        val fixture = HeicMetaFixture.build(xmpText = null)
        assertEquals(1, fixture.existingItemCount, "Expected only the primary item, no XMP item")

        val tmp = java.io.File.createTempFile("heic-fixture-noxmp-", ".heic")
        tmp.deleteOnExit()
        tmp.writeBytes(fixture.heicBytes)
        val root = parseFile(tmp)
        val xmpField = findFirst(root) { it.fields.any { f -> f.name == "xmp" } }
        assertEquals(null, xmpField)
        tmp.delete()
    }
}
