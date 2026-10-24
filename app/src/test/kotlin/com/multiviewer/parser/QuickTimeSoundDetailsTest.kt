package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class QuickTimeSoundDetailsTest {
    @Test fun `raw RGB video is not mistaken for an audio description`() {
        val root=parseFile(File("src/test/resources/mov-details/raw-video.mov"))
        val raw=assertNotNull(findFirst(root) { it.type=="raw " })
        assertTrue(raw.fields.none { it.name in listOf("samplerate", "channelcount", "samplesize") })
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(raw))
    }

    @Test fun `actual v1 and v2 expose packet sizes format flags and original format`() {
        val v1=assertNotNull(findFirst(parseFile(File("src/test/resources/mov-details/keyed.mov"))) { it.type=="mp4a" && it.size>28 })
        assertEquals("1024",v1.fields.find { it.name=="samples_per_packet" }?.value)
        assertEquals("2",v1.fields.find { it.name=="bytes_per_sample" }?.value)
        assertEquals("-2",v1.fields.find { it.name=="compression_id" }?.value)
        val frma=assertNotNull(findFirst(v1) { it.type=="frma" })
        assertEquals("mp4a",frma.fields.find { it.name=="data_format" }?.value)
        val root=parseFile(File("src/test/resources/mov-details/float-audio.mov"))
        val pcm=assertNotNull(findFirst(root) { it.type=="lpcm" })
        assertEquals("96000.0",pcm.fields.find { it.name=="samplerate" }?.value)
        assertEquals("32",pcm.fields.find { it.name=="samplesize" }?.value)
        assertEquals("8",pcm.fields.find { it.name=="const_bytes_per_audio_packet" }?.value)
        assertEquals("1",pcm.fields.find { it.name=="const_lpcm_frames_per_audio_packet" }?.value)
        assertEquals("true",pcm.fields.find { it.name=="pcm_is_float" }?.value)
        assertEquals("true",pcm.fields.find { it.name=="pcm_is_packed" }?.value)
        val channel=assertNotNull(findFirst(pcm) { it.type=="chan" })
        assertEquals("6619138",channel.fields.find { it.name=="channel_layout_tag" }?.value)
        assertEquals("2",channel.fields.find { it.name=="layout_tag_channel_count" }?.value)
    }
    @Test fun `channel descriptions decode coordinates and bounds without invented records`() {
        registerAllDecoders()
        val body=hex("00000000 00000000 00000000 00000001 00000001 00000001 3f800000 40000000 40400000")
        byteReaderOf(body).use { reader ->
            val node=BoxRegistry.decoderFor("chan").decode(reader,"chan",0,0,body.size.toLong(),emptyList())
            val description=node.children.single()
            assertEquals("1",description.fields.find { it.name=="channel_label" }?.value)
            assertEquals("2.0",description.fields.find { it.name=="coordinate[1]" }?.value)
            assertEquals(28L,description.fields.first { it.name=="coordinate[1]" }.offset)
        }
        byteReaderOf(body.copyOf(body.size-1)).use { reader ->
            val node=BoxRegistry.decoderFor("chan").decode(reader,"chan",0,0,reader.length,emptyList())
            assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(node))
            assertTrue(node.children.isEmpty())
        }
        byteReaderOf(hex("0001")).use { reader ->
            val node=BoxRegistry.decoderFor("enda").decode(reader,"enda",0,0,2,emptyList())
            assertEquals("1",node.fields.find { it.name=="little_endian" }?.value)
        }
    }
    @Test fun `v2 structure size directs extension position and short descriptions are explicit failures`() {
        registerAllDecoders()
        val base=ByteArray(64)
        base[9]=2
        base[31]=76
        val rate=hex("40f7700000000000");rate.copyInto(base,32)
        base[43]=2;base[51]=32
        val chan=qtDetailsBox("chan",hex("00000000006500020000000000000000"))
        val bytes=qtDetailsBox("lpcm",base+ByteArray(4)+chan)
        byteReaderOf(bytes).use { reader ->
            val node=parseBoxes(reader,0,reader.length).single()
            assertEquals(76L,node.children.single().offset)
            assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(node))
        }
        byteReaderOf(base.copyOf(28)).use { reader ->
            val node=AudioSampleEntryDecoder.decode(reader,"mp4a",0,0,reader.length,emptyList())
            assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(node))
            assertTrue(node.fields.none { it.name=="samplerate" })
        }
    }
}
