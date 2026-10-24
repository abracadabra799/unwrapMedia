package com.multiviewer.parser

import kotlin.test.*

class QuickTimeContainerDetailsTest {
    private fun decode(type: String, bytes: ByteArray)=byteReaderOf(bytes).use {
        registerAllDecoders()
        BoxRegistry.decoderFor(type).decode(it,type,0,0,bytes.size.toLong(),emptyList())
    }
    @Test fun `invalid key counts strings versions and short item extents are explicit failures`() {
        for(bytes in listOf(hex("00000000ffffffff"),hex("00000000000000010000000a6d647461c328"),hex("0100000000000000"))) {
            assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(decode("keys",bytes)))
        }
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(decode("ilst",hex("00000064000000010000"))))
    }
    @Test fun `64 bit item headers preserve exact data child boundaries`() {
        val data=qtDetailsBox("data",hex("00000001000000004142"))
        val item=hex("000000010000000100000000")+byteArrayOf(0,0,0,(16+data.size).toByte())+data
        val node=decode("ilst",item)
        assertEquals(1,node.children.size)
        assertEquals(16,node.children.single().headerSize)
        val value=node.children.single().children.single().fields.first { it.name=="value" }
        assertEquals("AB",value.value)
        assertEquals(32L,value.offset)
    }
    @Test fun `key and item expansion limits disclose remaining raw bytes`() {
        val key=qtDetailsBox("mdta","a".toByteArray())
        val keys=hex("0000000000001001")+ByteArray(key.size*4097) { key[it%key.size] }
        val parsed=decode("keys",keys)
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(parsed))
        assertNotNull(parsed.fields.find { it.name=="unparsed_keys_bytes" })
        val data=qtDetailsBox("data",hex("000000010000000041"))
        val item=qtDetailsBox(String(byteArrayOf(0,0,0,1),Charsets.ISO_8859_1),data)
        val many=decode("ilst",ByteArray(item.size*4097) { item[it%item.size] })
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(many))
        assertTrue(many.children.size<4097)
        assertNotNull(many.fields.find { it.name=="unparsed_ilst_bytes" })
    }
}
