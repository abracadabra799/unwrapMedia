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
