package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class AacHeaderDetailsTest {
    @Test fun `actual ADTS header exposes fullness indices and raw block count`() {
        ByteReader.open(File("src/test/resources/detail-parsing/default-adts.aac")).use { reader ->
            val frame=parseAac(reader,0,reader.length).first { it.type.startsWith("ADTS Frame") }
            assertEquals("3",frame.fields.find { it.name=="sampling_frequency_index" }?.value)
            assertEquals("1",frame.fields.find { it.name=="channel_configuration" }?.value)
            assertEquals("2047",frame.fields.find { it.name=="adts_buffer_fullness" }?.value)
            assertEquals("0",frame.fields.find { it.name=="number_of_raw_data_blocks_in_frame" }?.value)
            assertEquals(5L,frame.fields.first { it.name=="adts_buffer_fullness" }.offset)
            assertTrue(frame.warnings.isEmpty())
        }
    }
    @Test fun `single block CRC and copyright flags expose stored header values`() {
        val bytes=hex("fff04c7c013ffc1234")
        byteReaderOf(bytes).use { reader ->
            val frame=parseAac(reader,0,reader.length).first()
            assertEquals("4660",frame.fields.find { it.name=="crc_check" }?.value)
            assertEquals("1",frame.fields.find { it.name=="original_copy" }?.value)
            assertEquals("1",frame.fields.find { it.name=="home" }?.value)
            assertEquals("1",frame.fields.find { it.name=="copyright_identification_bit" }?.value)
            assertEquals("1",frame.fields.find { it.name=="copyright_identification_start" }?.value)
        }
    }
    @Test fun `reserved frequency index does not invent a sample rate`() {
        val bytes=hex("fff1744000fffc")
        byteReaderOf(bytes).use { reader ->
            val frame=parseAac(reader,0,reader.length).first()
            assertTrue(frame.fields.first { it.name=="sample_rate" }.value.contains("Unknown"))
            assertTrue(frame.warnings.isNotEmpty())
        }
    }
    @Test fun `protected multi block frames do not label a raw data position as CRC`() {
        val bytes=hex("fff04c4001bffd")+ByteArray(6)
        byteReaderOf(bytes).use { reader ->
            val frame=parseAac(reader,0,reader.length).first()
            assertEquals("1",frame.fields.first { it.name=="number_of_raw_data_blocks_in_frame" }.value)
            assertTrue(frame.fields.none { it.name=="crc_check" })
        }
    }

}
