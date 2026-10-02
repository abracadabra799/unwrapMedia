package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Av2SampleIndexerTest {
    @Test
    fun `parse AV2 sample OBUs respects each length-delimited boundary`() {
        val reader = byteReaderOf(byteArrayOf(1, 0x04, 2, 0xc3.toByte(), 0xb1.toByte()))

        val result = parseAv2SampleObus(reader, 0, 5)

        assertEquals(listOf("OBU_SEQUENCE_HEADER", "OBU_LAYER_CONFIGURATION_RECORD"), result.obus.map { it.typeName })
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `parse AV2 sample OBUs warns when an OBU crosses its sample boundary`() {
        val result = parseAv2SampleObus(byteReaderOf(byteArrayOf(2, 0x04)), 0, 2)

        assertTrue(result.obus.isEmpty())
        assertTrue(result.warnings.any { it.contains("extends") })
    }
}
