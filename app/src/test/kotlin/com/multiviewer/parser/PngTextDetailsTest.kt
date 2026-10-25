package com.multiviewer.parser

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.test.*

class PngTextDetailsTest {
    private fun compressed(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(bytes) } }.toByteArray()
    private fun node(type: String, body: ByteArray) = pngDetailNodes(pngDetailChunk(type, body)).single()
    private fun value(n: BoxNode, name: String) = pngDetailValue(n, name)
    private fun ztext(data: ByteArray) = node("zTXt", hex("4b0000") + data)
    private fun itext(text: ByteArray, flag: Int=0, method: Int=0, lang: ByteArray=byteArrayOf(), translated: ByteArray=byteArrayOf()) =
        node("iTXt", byteArrayOf(75,0,flag.toByte(),method.toByte()) + lang + byteArrayOf(0) + translated + byteArrayOf(0) + text)
    @Test fun `actual text fields preserve source ranges and expose compression headers`() {
        val nodes = ByteReader.open(File("src/test/resources/png-text/metadata.png")).use { parsePngChunks(it,8,it.length) }
        assertEquals("Cafe Latin-1: café",value(nodes.first { it.type=="tEXt" },"text").value)
        val z = nodes.first { it.type=="zTXt" }
        assertEquals("0",value(z,"compression_method").value)
        assertEquals("Compressed Latin-1: déjà vu",value(z,"text").value)
        assertEquals(477L,value(z,"text").offset)
        assertEquals(35L,value(z,"text").length)
        val international = nodes.filter { it.type=="iTXt" }
        assertEquals("검증 이미지",value(international[0],"text").value)
        assertEquals(542L,value(international[0],"text").offset)
        assertEquals("1",value(international[1],"compression_flag").value)
        assertEquals("ko-KR",value(international[1],"language_tag").value)
        assertEquals("설명",value(international[1],"translated_keyword").value)
        assertEquals("압축된 설명",value(international[1],"text").value)
        assertEquals(593L,value(international[1],"text").offset)
        assertEquals(25L,value(international[1],"text").length)
    }
    @Test fun `ICC derived fields point to compressed bytes and opaque tag data stays explicit`() {
        val nodes = ByteReader.open(File("src/test/resources/png-text/metadata.png")).use { parsePngChunks(it,8,it.length) }
        val icc = nodes.first { it.type=="iCCP" }
        assertEquals("588 bytes",value(icc,"profile_size").value)
        assertEquals("RGB",value(icc,"data_colour_space").value)
        for (name in listOf("profile_size","profile_id","pcs_illuminant","cmm_type")) {
            assertEquals(54L,value(icc,name).offset,name)
            assertEquals(361L,value(icc,name).length,name)
        }
        assertEquals("588",value(icc,"decompressed_size").value)
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(icc))
        assertNotNull(icc.fields.find { it.name=="profile_source" })
        assertTrue(icc.fields.all { it.offset>=41 && it.offset+it.length<=415 })
    }
    @Test fun `incomplete or corrupt zlib never exposes partial decoded text`() {
        val encoded=compressed("partial text must not appear parsed".toByteArray())
        for (bytes in listOf(encoded.dropLast(1).toByteArray(),encoded.dropLast(4).toByteArray(),encoded.copyOf().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() },byteArrayOf())) {
            val n=ztext(bytes)
            assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(n))
            assertTrue(n.fields.none { it.name=="text" && it.interpretation==ValueInterpretation.PARSED })
            assertEquals("K",value(n,"keyword").value)
        }
    }
    @Test fun `dictionary stream fails and complete empty text succeeds`() {
        val deflater=Deflater()
        val bytes=try {
            deflater.setDictionary("dictionary".toByteArray());deflater.setInput("dictionary text".toByteArray());deflater.finish()
            val out=ByteArray(100);out.copyOf(deflater.deflate(out))
        } finally { deflater.end() }
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(ztext(bytes)))
        val empty=ztext(compressed(byteArrayOf()))
        assertEquals("",value(empty,"text").value)
        assertEquals(ValueInterpretation.PARSED,detailInterpretation(empty))
        assertEquals(ValueInterpretation.PARSED,detailInterpretation(itext(byteArrayOf())))
    }
    @Test fun `unknown flags or methods remain uninterpreted while uncompressed method is ignored`() {
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(node("zTXt",hex("4b0001ff"))))
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(node("iCCP",hex("4b0001ff"))))
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(itext("data".toByteArray(),flag=2)))
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(itext("data".toByteArray(),flag=1,method=9)))
        val plain=itext("readable".toByteArray(),method=9)
        assertEquals("readable",value(plain,"text").value)
        assertEquals("9",value(plain,"compression_method").value)
        assertEquals(ValueInterpretation.PARSED,detailInterpretation(plain))
    }
    @Test fun `invalid text and keyword bytes are explicit failures without replacement success`() {
        val nodes=listOf(
            itext(hex("c328")),itext("ok".toByteArray(),translated=hex("c328")),
            itext("ok".toByteArray(),lang=hex("ff")),itext(hex("410042")),
            node("tEXt",hex("4b00410042")),ztext(compressed(hex("410042"))),
            node("tEXt",hex("0041")),node("tEXt"," Bad\u0000text".toByteArray()),
            node("tEXt",ByteArray(80) {65}+hex("0041")),node("tEXt",hex("41a0420043")),
        )
        for (n in nodes) {
            assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(n))
            assertTrue(n.fields.none { it.interpretation==ValueInterpretation.PARSED && '\uFFFD' in it.value })
        }
    }
    @Test fun `oversized encoded or inflated text remains bounded and explicitly uninterpreted`() {
        val giant=ByteArray(1_048_577) {65}
        for (n in listOf(node("tEXt",hex("4b00")+giant),ztext(compressed(giant)))) {
            assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(n))
            assertTrue(n.fields.sumOf { it.value.length }<1000)
        }
        val encoded=pngDetailChunk("zTXt",hex("4b0000")+compressed(ByteArray(900_000) {65}))
        val all=pngDetailNodes(ByteArray(encoded.size*10) { encoded[it%encoded.size] })
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(all.last()))
        assertTrue(all.sumOf { n -> n.fields.filter { it.name=="text" }.sumOf { it.value.length } }<=8*1024*1024)
    }
    @Test fun `trailing bytes remain uninterpreted after a valid compressed text stream`() {
        val encoded=compressed("good".toByteArray())
        val n=ztext(encoded+hex("deadbeef"))
        assertEquals("good",value(n,"text").value)
        assertEquals(encoded.size.toLong(),value(n,"text").length)
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(n))
        assertEquals(4L,value(n,"unparsed_bytes").length)
    }
    @Test fun `unsigned chunk length is capped before narrowing or allocating`() {
        val file=File.createTempFile("png-large-metadata", ".bin")
        try {
            java.io.RandomAccessFile(file,"rw").use {
                it.writeInt(Int.MIN_VALUE);it.writeBytes("tEXt");it.setLength(2_147_483_648L+12)
            }
            val n=ByteReader.open(file).use { parsePngChunks(it,0,it.length).single() }
            assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(n))
            assertTrue(n.fields.sumOf { it.value.length }<1000)
        } finally { file.delete() }
    }
    @Test fun `ICC header without mandatory tag count preserves fields but is unparsable`() {
        val profile=File("src/test/resources/png-text/reference.icc").readBytes()
        for (length in 128..131) {
            val header=profile.copyOf(length).also { it[0]=0;it[1]=0;it[2]=0;it[3]=length.toByte() }
            val n=node("iCCP",hex("4b0000")+compressed(header))
            assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(n),"length=$length")
            assertEquals("RGB",value(n,"data_colour_space").value)
        }
    }
    @Test fun `short ICC and inconsistent declared size cannot look fully parsed`() {
        val short=node("iCCP",hex("4b0000")+compressed(ByteArray(127)))
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(short))
        val profile=File("src/test/resources/png-text/reference.icc").readBytes()
        val wrong=profile.copyOf().also { it[3]=(it[3].toInt() xor 1).toByte() }
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(node("iCCP",hex("4b0000")+compressed(wrong))))
        val signature=profile.copyOf().also { it[36]=0 }
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(node("iCCP",hex("4b0000")+compressed(signature))))
    }
}
