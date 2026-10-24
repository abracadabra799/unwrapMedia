package com.multiviewer.parser

import java.io.File
import kotlin.test.*

internal fun id3TestSize(value: Int, syncsafe: Boolean = true): ByteArray =
    if(syncsafe) byteArrayOf((value ushr 21).toByte(),((value ushr 14) and 127).toByte(),((value ushr 7) and 127).toByte(),(value and 127).toByte())
    else byteArrayOf((value ushr 24).toByte(),(value ushr 16).toByte(),(value ushr 8).toByte(),value.toByte())
internal fun id3TestFrame(id: String, payload: ByteArray, version: Int=4, flags: Int=0): ByteArray =
    id.toByteArray(Charsets.US_ASCII)+id3TestSize(payload.size,version==4)+byteArrayOf((flags ushr 8).toByte(),flags.toByte())+payload
internal fun id3TestTag(body: ByteArray,version: Int=4,flags: Int=0): ByteArray =
    "ID3".toByteArray()+byteArrayOf(version.toByte(),0,flags.toByte())+id3TestSize(body.size)+body
internal fun id3Decode(bytes: ByteArray): BoxNode = byteReaderOf(bytes).use { decodeId3v2(it,0,it.length) }

class Id3DetailsTest {
    @Test fun `real v23 and v24 tags expose custom strings with original byte spans`() {
        for(name in listOf("mpeg1-v23.mp3","mpeg2-v24.mp3")) {
            ByteReader.open(File("src/test/resources/mp3-details/$name")).use { reader ->
                val tag=decodeId3v2(reader,0,reader.length)
                assertEquals("검증 제목",tag.children.first { it.type=="TIT2" }.fields.first { it.name=="Title" }.value)
                val custom=tag.children.first { it.type=="TXXX" && it.offset==90L }
                assertEquals("custom_key",custom.fields.find { it.name=="description" }?.value)
                val value=assertNotNull(custom.fields.find { it.name=="value" })
                assertEquals("custom-value",value.value)
                assertEquals(112L,value.offset)
                assertEquals(13L,value.length)
                assertEquals(ValueInterpretation.PARSED,detailInterpretation(custom))
            }
        }
    }
    @Test fun `real APIC exposes picture type description and exact image range`() {
        ByteReader.open(File("src/test/resources/mp3-details/mpeg1-v23.mp3")).use { reader ->
            val cover=decodeId3v2(reader,0,reader.length).children.first { it.type=="APIC" }
            assertEquals("image/png",cover.fields.first { it.name=="mime_type" }.value)
            assertEquals("3",cover.fields.find { it.name=="picture_type" }?.value)
            assertEquals("Cover",cover.fields.find { it.name=="description" }?.value)
            val image=assertNotNull(cover.fields.find { it.name=="image_data_length" })
            assertEquals("77",image.value)
            assertEquals(178L,image.offset)
            assertEquals(77L,image.length)
        }
    }
    @Test fun `comments support aligned UTF16 terminators and user URLs use Latin1`() {
        val comment=byteArrayOf(1)+"eng".toByteArray()+hex("fffe")+"설명".toByteArray(Charsets.UTF_16LE)+hex("0000fffe")+"댓글".toByteArray(Charsets.UTF_16LE)
        val url=byteArrayOf(3)+"Site\u0000https://example.test/path".toByteArray()
        val tag=id3Decode(id3TestTag(id3TestFrame("COMM",comment)+id3TestFrame("WXXX",url)))
        val comm=tag.children.first { it.type=="COMM" }
        assertEquals("eng",comm.fields.first { it.name=="language" }.value)
        assertEquals("설명",comm.fields.find { it.name=="description" }?.value)
        assertEquals("댓글",comm.fields.find { it.name=="text" }?.value)
        val link=tag.children.first { it.type=="WXXX" }
        assertEquals("Site",link.fields.find { it.name=="description" }?.value)
        assertEquals("https://example.test/path",link.fields.find { it.name=="url" }?.value)
    }
    @Test fun `private data stays uninterpreted while owner identifiers and counters decode`() {
        val tag=id3Decode(id3TestTag(
            id3TestFrame("PRIV","owner\u0000".toByteArray()+hex("0102ff"))+
            id3TestFrame("UFID","scope\u0000".toByteArray()+hex("0102"))+
            id3TestFrame("PCNT",hex("0000000100000000"))+
            id3TestFrame("POPM","x@y\u0000".toByteArray()+hex("c80000002a"))))
        val priv=tag.children.first { it.type=="PRIV" }
        assertEquals("owner",priv.fields.find { it.name=="owner" }?.value)
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(priv))
        assertEquals("01 02 ff",priv.fields.find { it.name=="private_data" }?.value)
        assertEquals("01 02",tag.children.first { it.type=="UFID" }.fields.find { it.name=="identifier" }?.value)
        assertEquals("4294967296",tag.children.first { it.type=="PCNT" }.fields.find { it.name=="counter" }?.value)
        assertEquals("200",tag.children.first { it.type=="POPM" }.fields.find { it.name=="rating" }?.value)
        assertEquals("42",tag.children.first { it.type=="POPM" }.fields.find { it.name=="counter" }?.value)
    }
    @Test fun `encrypted compressed unknown and malformed text frames never become trusted text`() {
        for(flags in listOf(4,9)) {
            val frame=id3Decode(id3TestTag(id3TestFrame("TIT2",hex("00000004")+byteArrayOf(3)+"secret".toByteArray(),flags=flags))).children.single()
            assertTrue(frame.fields.none { it.name=="Title" })
            assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(frame))
        }
        for(payload in listOf(hex("07616263"),hex("03c328"))) {
            val frame=id3Decode(id3TestTag(id3TestFrame("TIT2",payload))).children.single()
            assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(frame))
        }
        val unknown=id3Decode(id3TestTag(id3TestFrame("ZZZZ",hex("ff0102")))).children.single()
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(unknown))
        assertEquals("ff 01 02",unknown.fields.find { it.name=="raw_payload" }?.value)
        val short=id3Decode(id3TestTag(id3TestFrame("TXXX",hex("03616263")))).children.single()
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(short))
    }
    @Test fun `v24 frame unsynchronisation maps decoded text back to original stuffed bytes`() {
        val frame=id3Decode(id3TestTag(id3TestFrame("TIT2",hex("00ff00e0"),flags=2))).children.single()
        val text=assertNotNull(frame.fields.find { it.name=="Title" })
        assertEquals("ÿà",text.value)
        assertEquals(21L,text.offset)
        assertEquals(3L,text.length)
    }
    @Test fun `missing UFID owner and APIC image bytes are parsing failures`() {
        val ufid=id3Decode(id3TestTag(id3TestFrame("UFID",hex("0001")))).children.single()
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(ufid))
        assertTrue(ufid.warnings.isNotEmpty())
        val emptyCover=byteArrayOf(0)+"image/png".toByteArray()+hex("000300")
        val apic=id3Decode(id3TestTag(id3TestFrame("APIC",emptyCover))).children.single()
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(apic))
        assertTrue(apic.warnings.isNotEmpty())
    }

}
