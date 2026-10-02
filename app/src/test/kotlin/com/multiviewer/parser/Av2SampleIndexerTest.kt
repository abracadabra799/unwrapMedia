package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Av2SampleIndexerTest {
    @Test
    fun `parse AV2 sample OBUs respects each length-delimited boundary`() {
        val reader = byteReaderOf(byteArrayOf(1, 0x10, 1, 0x1c))

        val result = parseAv2SampleObus(reader, 0, 4)

        assertEquals(listOf("OBU_CLOSED_LOOP_KEY", "OBU_REGULAR_TILE_GROUP"), result.obus.map { it.typeName })
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `parse AV2 sample OBUs warns when an OBU crosses its sample boundary`() {
        val result = parseAv2SampleObus(byteReaderOf(byteArrayOf(2, 0x04)), 0, 2)

        assertTrue(result.obus.isEmpty())
        assertTrue(result.warnings.any { it.contains("extends") })
    }

    @Test
    fun `build AV2 sample index reads timing chunk layout and bounded OBU lists`() {
        val bytes = ByteArray(128)
        putUInt32(bytes, 0, 2)
        putUInt32(bytes, 4, 100)
        putUInt32(bytes, 16, 2)
        putUInt32(bytes, 20, 2)
        putUInt32(bytes, 24, 1)
        putUInt32(bytes, 28, 2)
        putUInt32(bytes, 32, 1)
        putUInt32(bytes, 40, 80)
        bytes[80] = 1; bytes[81] = 0x10
        bytes[82] = 1; bytes[83] = 0x1c
        val file = fileOf(bytes)

        val index = buildAv2SampleIndex(file, av2TrackRoot())

        assertNotNull(index)
        assertEquals(1_000L, index.timescale)
        assertEquals(listOf(0L, 100L), index.samples.map { it.decodeTime })
        assertEquals(listOf(100L, 100L), index.samples.map { it.duration })
        assertEquals(listOf(80L, 82L), index.samples.map { it.offset })
        assertEquals(listOf("OBU_CLOSED_LOOP_KEY", "OBU_REGULAR_TILE_GROUP"), index.samples.flatMap { it.obus }.map { it.typeName })
        assertTrue(index.warnings.isEmpty())
    }

    @Test
    fun `build AV2 sample index keeps truncated OBU as a warning`() {
        val bytes = ByteArray(96)
        putUInt32(bytes, 0, 1); putUInt32(bytes, 4, 100)
        putUInt32(bytes, 16, 1)
        putUInt32(bytes, 20, 1)
        putUInt32(bytes, 24, 1); putUInt32(bytes, 28, 1); putUInt32(bytes, 32, 1)
        putUInt32(bytes, 40, 80)
        bytes[80] = 0x80.toByte()

        val index = buildAv2SampleIndex(fileOf(bytes), av2TrackRoot())

        assertNotNull(index)
        assertTrue(index.samples.first().warnings.any { it.contains("leb128") })
    }

    private fun av2TrackRoot(): BoxNode {
        val stbl = BoxNode("stbl", 0, 0, 0, children = listOf(
            BoxNode("stts", 0, 0, 0, table = TableData(listOf("sample_count", "sample_delta"), listOf(4, 4), 0, 1)),
            BoxNode("stsz", 0, 0, 0, fields = listOf(BoxField("sample_size", "0", 0, 4)), table = TableData(listOf("sample_size"), listOf(4), 16, 2)),
            BoxNode("stsc", 0, 0, 0, table = TableData(listOf("first_chunk", "samples_per_chunk", "sample_description_index"), listOf(4, 4, 4), 24, 1)),
            BoxNode("stco", 0, 0, 0, table = TableData(listOf("chunk_offset"), listOf(4), 40, 1)),
        ))
        return BoxNode("root", 0, 0, 0, children = listOf(
            BoxNode("trak", 0, 0, 0, children = listOf(
                BoxNode("mdia", 0, 0, 0, children = listOf(
                    BoxNode("mdhd", 0, 0, 0, fields = listOf(BoxField("timescale", "1000", 0, 4))),
                    BoxNode("minf", 0, 0, 0, children = listOf(stbl)),
                )),
                BoxNode("av02", 0, 0, 0),
            )),
        ))
    }

    private fun putUInt32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
