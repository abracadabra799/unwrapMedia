package com.multiviewer.parser

import java.io.File
import kotlin.test.*

internal fun qtDetailsBox(type: String, payload: ByteArray): ByteArray {
    val n=payload.size+8
    return byteArrayOf((n ushr 24).toByte(),(n ushr 16).toByte(),(n ushr 8).toByte(),n.toByte())+type.toByteArray(Charsets.ISO_8859_1)+payload
}
internal fun qtDetailsData(code: Int, payload: ByteArray, typeSet: Int=0): BoxNode {
    val body=byteArrayOf(typeSet.toByte(),(code ushr 16).toByte(),(code ushr 8).toByte(),code.toByte())+ByteArray(4)+payload
    return byteReaderOf(body).use { QuickTimeDataBoxDecoder.decode(it,"data",0,0,body.size.toLong(),emptyList()) }
}
class QuickTimeValueDetailsTest {
    @Test fun `actual Apple typed values retain precision and derived fields keep exact data ranges`() {
        val root=parseFile(File("src/test/resources/mov-details/typed.mov"))
        val meta=assertNotNull(findFirst(root) { it.type=="Apple QuickTime Metadata" })
        fun field(key: String)=meta.fields.first { it.name=="com.example.$key" }
        assertEquals("0.1",field("float32").value)
        assertEquals("1.234567890123",field("float64").value)
        assertEquals(5832L,field("float64").offset)
        assertEquals(8L,field("float64").length)
        assertEquals("-9223372036854775808",field("int64").value)
        assertEquals("18446744073709551615",field("uint64").value)
    }
    @Test fun `fixed integers UTF16 sort and geometry types decode their defined layouts`() {
        for((code,raw,want) in listOf(
            Triple(65,"f9","-7"),Triple(66,"fed4","-300"),Triple(67,"ffed2979","-1234567"),
            Triple(74,"8000000000000000","-9223372036854775808"),Triple(75,"ff","255"),
            Triple(76,"ffff","65535"),Triple(77,"ffffffff","4294967295"),Triple(78,"ffffffffffffffff","18446744073709551615"),
            Triple(5,"00410042","AB"),Triple(70,"3fa00000c0200000","[1.25, -2.5]"),
            Triple(71,"40a0000040c00000","[5.0, 6.0]"),Triple(72,"000000003f8000004000000040400000","[0.0, 1.0, 2.0, 3.0]"),
        )) {
            val node=qtDetailsData(code,hex(raw))
            assertEquals(want,node.fields.first { it.name=="value" }.value,"type $code")
            assertEquals(ValueInterpretation.PARSED,detailInterpretation(node))
        }
        val matrix=qtDetailsData(79,hex("3ff0000000000000 0000000000000000 0000000000000000 0000000000000000 3ff0000000000000 0000000000000000 0000000000000000 0000000000000000 3ff0000000000000"))
        assertEquals("1.0",matrix.fields.find { it.name=="matrix[2][2]" }?.value)
    }
    @Test fun `unknown types remain uninterpreted while invalid widths encodings and file extents fail`() {
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(qtDetailsData(999,hex("0102ff"))))
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(qtDetailsData(1,"AB".toByteArray(),typeSet=1)))
        for((code,bytes) in listOf(23 to hex("3f80000000000000"),66 to hex("01"),1 to hex("c328"),21 to ByteArray(5))) {
            assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(qtDetailsData(code,bytes)))
        }
        byteReaderOf(ByteArray(8)).use { reader ->
            assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(QuickTimeDataBoxDecoder.decode(reader,"data",0,0,1000,emptyList())))
        }
    }
    @Test fun `enrichment carries opaque value state into the friendly metadata field`() {
        val key="com.example.raw".toByteArray()
        val keys=qtDetailsBox("keys",hex("0000000000000001")+qtDetailsBox("mdta",key))
        val data=qtDetailsBox("data",hex("000003e7000000000102ff"))
        val item=qtDetailsBox(String(byteArrayOf(0,0,0,1),Charsets.ISO_8859_1),data)
        val bytes=qtDetailsBox("meta",ByteArray(4)+keys+qtDetailsBox("ilst",item))
        byteReaderOf(bytes).use { reader ->
            registerAllDecoders()
            val root=parseBoxes(reader,0,reader.length).single()
            val field=root.children.first { it.type=="Apple QuickTime Metadata" }.fields.single()
            assertEquals(ValueInterpretation.UNINTERPRETED,field.interpretation)
            val original=assertNotNull(findFirst(root) { it.type=="data" }).fields.first { it.name=="value" }
            assertEquals(original.offset,field.offset)
            assertEquals(original.length,field.length)
        }
    }
}
