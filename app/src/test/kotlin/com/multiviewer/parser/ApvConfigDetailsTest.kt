package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class ApvConfigDetailsTest {
    private fun decode(bytes: ByteArray, size: Long = bytes.size.toLong()) = byteReaderOf(bytes).use {
        ApvCBoxDecoder.decode(it,"apvC",0,0,size,emptyList())
    }
    @Test fun `actual APV config exposes headers and capture flags with source positions`() {
        val node=assertNotNull(findFirst(parseFile(File("src/test/resources/apv-conformance/default-apv1.mp4"))) { it.type=="apvC" })
        assertEquals("1",node.fields.find { it.name=="configuration_version" }?.value)
        assertEquals("1",node.fields.find { it.name=="number_of_configuration_entry" }?.value)
        assertEquals("3840",node.fields.first { it.name=="frame_width" }.value)
        val frame=node.children.single().children.single()
        assertEquals("true",frame.fields.find { it.name=="capture_time_distance_ignored" }?.value)
        assertEquals(515L,frame.fields.first { it.name=="capture_time_distance_ignored" }.offset)
        assertEquals("0",frame.fields.find { it.name=="capture_time_distance" }?.value)
        assertTrue(node.warnings.isEmpty())
    }
    @Test fun `multiple frame records and optional color fields advance by their encoded lengths`() {
        val first=hex("03 217b02 00000f00 00000870 22 00 09091080")
        val second=hex("01 217b01 00000780 00000438 22 02")
        val bytes=hex("00000000 0102 0101")+first+hex("0201")+second
        val node=decode(bytes)
        assertEquals(2,node.children.size)
        val a=node.children[0].children.single()
        assertEquals("9",a.fields.find { it.name=="color_primaries" }?.value)
        assertEquals("true",a.fields.find { it.name=="full_range_flag" }?.value)
        val b=node.children[1].children.single()
        assertEquals("1920",b.fields.find { it.name=="frame_width" }?.value)
        assertEquals("2",b.fields.find { it.name=="capture_time_distance" }?.value)
        assertTrue(b.fields.none { it.name=="color_primaries" })
        assertTrue(node.warnings.isEmpty())
    }
    @Test fun `truncated optional data and unsupported records warn without fabricated frames`() {
        val valid=hex("00000000 0101 0101 03 217b02 00000f00 00000870 22 00 09091080")
        assertTrue(decode(valid + byteArrayOf(0)).warnings.isNotEmpty())
        assertTrue(decode(valid.copyOf(valid.size-2)).warnings.isNotEmpty())
        assertTrue(decode(valid,1000).warnings.isNotEmpty())
        assertTrue(decode(valid.copyOf().also { it[4]=2 }).warnings.isNotEmpty())
        assertTrue(decode(hex("00000000 0101 0101")).warnings.isNotEmpty())
    }
}
