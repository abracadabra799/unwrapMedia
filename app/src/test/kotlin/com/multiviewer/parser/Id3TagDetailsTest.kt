package com.multiviewer.parser

import kotlin.test.*

class Id3TagDetailsTest {
    @Test fun `v23 and v24 extended headers advance to actual frame boundaries`() {
        val title=id3TestFrame("TIT2",hex("005469746c6500"),version=3)
        val v3=id3Decode(id3TestTag(hex("00000006000000000000")+title,version=3,flags=0x40))
        assertEquals("Title",v3.children.first { it.type=="TIT2" }.fields.first { it.name=="Title" }.value)
        assertEquals(20L,v3.children.first { it.type=="TIT2" }.offset)
        val ext=hex("0000000f017000050001020304015a")
        val v4=id3Decode(id3TestTag(ext+id3TestFrame("TIT2",hex("035469746c6500")),flags=0x40))
        val header=v4.children.first { it.type=="ID3ExtendedHeader" }
        assertEquals("2130308",header.fields.find { it.name=="crc32" }?.value)
        assertEquals("90",header.fields.find { it.name=="restrictions" }?.value)
        assertEquals(25L,v4.children.first { it.type=="TIT2" }.offset)
        assertEquals(ValueInterpretation.PARSED,detailInterpretation(v4))
    }
    @Test fun `v24 footer is validated and excluded from the next audio header`() {
        val headerAndBody=id3TestTag(id3TestFrame("TIT2",hex("034100")),flags=0x10)
        val footer="3DI".toByteArray()+headerAndBody.copyOfRange(3,10)
        val bytes=headerAndBody+footer+hex("fffb94c0")
        byteReaderOf(bytes).use { reader ->
            val nodes=parseMp3(reader,0,reader.length)
            val tag=nodes.first { it.type=="ID3v2" }
            assertEquals((headerAndBody.size+10).toLong(),tag.size)
            assertTrue(tag.children.any { it.type=="ID3Footer" })
            assertEquals(tag.size,nodes.first { it.type=="AudioFrames" }.offset)
            assertEquals("true",tag.fields.find { it.name=="footer_present" }?.value)
        }
        val bad=id3Decode(headerAndBody+ByteArray(10))
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(bad))
    }
    @Test fun `tag unsync and unsupported legacy version stay explicit instead of decoding guessed text`() {
        val bytes=id3TestTag(id3TestFrame("TIT2",hex("00ff00e0"),version=3),version=3,flags=0x80)
        val tag=id3Decode(bytes)
        assertTrue(tag.children.isEmpty())
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(tag))
        val legacy=id3Decode(id3TestTag(hex("5454320000020041"),version=2))
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(legacy))
    }
    @Test fun `bad syncsafe sizes extended headers and truncated tag data warn without file overreads`() {
        val badSize=id3TestTag(ByteArray(0)).also { it[6]=0x80.toByte() }
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(id3Decode(badSize)))
        val badExt=id3TestTag(hex("0000007f0100"),flags=0x40)
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(id3Decode(badExt)))
        val truncated=id3TestTag(id3TestFrame("TIT2",hex("03414243"))).dropLast(2).toByteArray()
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(id3Decode(truncated)))
        byteReaderOf(byteArrayOf(1,2,3)).use { reader ->
            assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(decodeId3v2(reader,0,1000)))
        }
    }
    @Test fun `frame expansion limit is disclosed and valid empty tags remain parsed`() {
        val frame=id3TestFrame("TIT2",hex("0341"))
        val body=ByteArray(frame.size*4100) { frame[it%frame.size] }
        val tag=id3Decode(id3TestTag(body))
        assertEquals(4096,tag.children.size)
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(tag))
        assertNotNull(tag.fields.find { it.name=="unparsed_tag_bytes" })
        assertEquals(ValueInterpretation.PARSED,detailInterpretation(id3Decode(id3TestTag(ByteArray(0)))))
    }
}
