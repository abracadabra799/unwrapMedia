package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class QuickTimeLegacyTextTest {
    @Test fun `actual legacy MOV title and encoder expose decoded text and language`() {
        val root=parseFile(File("src/test/resources/mov-details/legacy.mov"))
        val title=assertNotNull(findFirst(root) { it.type=="\u00a9nam" })
        val value=assertNotNull(title.fields.find { it.name=="value" })
        assertEquals("검증 MOV",value.value)
        assertEquals(5431L,value.offset)
        assertEquals(10L,value.length)
        assertEquals("und",title.children.single().fields.find { it.name=="language" }?.value)
        val encoder=assertNotNull(findFirst(root) { it.type=="\u00a9swr" })
        assertEquals("Lavf62.12.102",encoder.fields.find { it.name=="value" }?.value)
        assertEquals(ValueInterpretation.PARSED,detailInterpretation(title))
    }
    private fun decode(bytes: ByteArray): BoxNode=byteReaderOf(bytes).use { reader ->
        registerAllDecoders()
        BoxRegistry.decoderFor("\u00a9nam").decode(reader,"\u00a9nam",0,0,reader.length,emptyList())
    }
    @Test fun `official header inclusive records support multiple languages and MacRoman`() {
        val bytes=hex("000900004361668e21 000855c455544638")
        val node=decode(bytes)
        assertEquals(2,node.children.size)
        assertEquals("Café!",node.children[0].fields.first { it.name=="value" }.value)
        assertEquals("UTF8",node.children[1].fields.first { it.name=="value" }.value)
        assertEquals("header_inclusive",node.fields.find { it.name=="length_encoding" }?.value)
        assertEquals(ValueInterpretation.PARSED,detailInterpretation(node))
    }
    @Test fun `UTF16 BOM uses source span while unknown encodings and broken lengths stay explicit`() {
        val unicode=decode(hex("000855c4feff0041"))
        assertEquals("A",unicode.fields.find { it.name=="value" }?.value)
        val unknown=decode(hex("0006000a8182"))
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(unknown))
        assertTrue(unknown.children.single().fields.none { it.name=="value" && it.value.contains('\uFFFD') })
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(decode(hex("006455c44142"))))
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(decode(hex("000655c4c328"))))
    }
}
