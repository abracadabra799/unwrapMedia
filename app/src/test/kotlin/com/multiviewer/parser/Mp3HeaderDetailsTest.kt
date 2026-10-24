package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class Mp3HeaderDetailsTest {
    @Test fun `real MPEG1 MPEG2 and MPEG25 expose correct rates and bitrates`() {
        for ((name,version,rate,bitrate) in listOf(
            listOf("mpeg1-v23.mp3","MPEG1","48000 Hz","128 kbps"),
            listOf("mpeg2-v24.mp3","MPEG2","24000 Hz","64 kbps"),
            listOf("mpeg25-v24.mp3","MPEG2.5","12000 Hz","32 kbps"),
        )) {
            ByteReader.open(File("src/test/resources/mp3-details/$name")).use { reader ->
                val node=parseMp3(reader,0,reader.length).first { it.type=="AudioFrames" }
                assertEquals(version,node.fields.find { it.name=="mpeg_version" }?.value)
                assertEquals(rate,node.fields.find { it.name=="sample_rate" }?.value)
                assertEquals(bitrate,node.fields.find { it.name=="bitrate" }?.value)
                assertEquals("1",node.fields.find { it.name=="sample_rate_index" }?.value)
                assertEquals("false",node.fields.find { it.name=="crc_present" }?.value)
            }
        }
    }
    @Test fun `Layer II and raw flags share the existing integrity header interpretation`() {
        val bytes=hex("fffd85ed")
        byteReaderOf(bytes).use { reader ->
            val node=parseMp3(reader,0,reader.length).single()
            assertEquals("Layer II",node.fields.find { it.name=="layer" }?.value)
            assertEquals("128 kbps",node.fields.find { it.name=="bitrate" }?.value)
            assertEquals("1",node.fields.find { it.name=="private_bit" }?.value)
            assertEquals("1",node.fields.find { it.name=="copyright" }?.value)
            assertEquals(2L,node.fields.first { it.name=="bitrate_index" }.offset)
        }
    }
    @Test fun `reserved headers are skipped and free format bitrate is not fabricated`() {
        byteReaderOf(hex("fffbfcc0")).use { reader -> assertTrue(parseMp3(reader,0,reader.length).isEmpty()) }
        byteReaderOf(hex("fffb04c0")).use { reader ->
            val node=parseMp3(reader,0,reader.length).single()
            assertEquals(ValueInterpretation.UNINTERPRETED,node.fields.first { it.name=="bitrate" }.interpretation)
            assertFalse(node.fields.first { it.name=="bitrate" }.value.contains("0 kbps"))
        }
    }
}
