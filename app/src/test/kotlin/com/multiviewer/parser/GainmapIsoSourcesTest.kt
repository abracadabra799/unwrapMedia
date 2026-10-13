package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GainmapIsoSourcesTest {
    private val jpeg = File("src/test/resources/c2pa/s26-camera.jpg")
    private val heic = File("src/test/resources/c2pa/s26-motion-photo.heic")
    private val s26Payload = hex(
        "0000000040" + "0000000000000001" + "0093333300400000" +
            "0000000000000001" + "0093333300400000" + "0000000100000001" +
            "0000000000000001" + "0000000000000001",
    )

    @Test
    fun `JPEG primary carries the 4-byte version block, the gain map image the full payload`() {
        // Offsets from the byte dump: APP2 at 92893 (primary) and 4014592 (gain map image), each
        // marker(2) + length(2) + "urn:iso:std:iso:ts:21496:-1\0"(28) before the payload.
        val primary = assertNotNull(readJpegIsoApp2(jpeg, 0L, jpeg.length()))
        assertContentEquals(hex("00000000"), primary.payload)
        assertEquals(92893L + 4 + 28, primary.payloadOffset)
        val gainmap = assertNotNull(readJpegIsoApp2(jpeg, 4013983L, 228923L))
        assertContentEquals(s26Payload, gainmap.payload)
        assertEquals(4014592L + 4 + 28, gainmap.payloadOffset)
    }

    @Test
    fun `JPEG without an ISO APP2 returns null`() {
        // The HEIC isn't a JPEG at all: the walk must stop at the missing SOI rather than scan.
        assertNull(readJpegIsoApp2(heic, 0L, heic.length()))
    }

    @Test
    fun `HEIC tmap item is item 56 with version 0 and the same payload`() {
        val tmap = assertNotNull(readHeifTmapItem(heic, parseFile(heic)))
        assertEquals(56L, tmap.itemId)
        assertEquals(0, tmap.version)
        assertContentEquals(s26Payload, tmap.payload)
        assertEquals(listOf(49L, 55L), tmap.inputItemIds) // iref dimg 56 -> [primary grid, gain map grid]
    }

    @Test
    fun `JPEG has no tmap item`() {
        assertNull(readHeifTmapItem(jpeg, parseFile(jpeg)))
    }
}
