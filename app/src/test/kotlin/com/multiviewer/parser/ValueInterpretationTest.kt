package com.multiviewer.parser

import com.multiviewer.cli.toJsonValue
import com.multiviewer.cli.renderCompact
import java.io.File
import kotlin.test.*

class ValueInterpretationTest {
    @Test fun `actual opaque SEF value is flagged without replacing raw bytes or source range`() {
        ByteReader.open(File("src/test/resources/c2pa/s26-motion-photo.heic")).use { reader ->
            val sef=SefdBoxDecoder.decode(reader,"sefd",0,0,reader.length,emptyList())
            val color=sef.children.first { it.type=="Color_Display_P3" }
            val raw=color.fields.first { it.name=="raw_payload" }
            assertEquals("0c 06 06",raw.value)
            assertEquals(3L,raw.length)
            assertEquals(ValueInterpretation.UNINTERPRETED, raw.interpretation)
            assertTrue(color.toJsonValue().renderCompact().contains("\"interpretation\":\"UNINTERPRETED\""))
        }
    }
    @Test fun `opaque or failed payload leaves get explicit state while marker and parsed fields do not`() {
        val opaque=BoxNode("unknown",0,8,20)
        assertTrue(opaque.toJsonValue().renderCompact().contains("\"interpretation\":\"UNINTERPRETED\""))
        val failed=opaque.copy(warnings=listOf("Truncated header"))
        assertTrue(failed.toJsonValue().renderCompact().contains("\"interpretation\":\"UNPARSABLE\""))
        val marker=BoxNode("SOI",0,2,2)
        assertFalse(marker.toJsonValue().renderCompact().contains("interpretation"))
        val normal=opaque.copy(fields=listOf(BoxField("reserved","0xff",8,1),BoxField("artist","Unknown Artist",9,5)))
        assertFalse(normal.toJsonValue().renderCompact().contains("interpretation"))
    }
    @Test fun `unknown descriptor is marked without turning semantic notices into structural warnings`() {
        val bytes=hex("00000000 7f020102")
        byteReaderOf(bytes).use { reader ->
            val node=EsdsBoxDecoder.decode(reader,"esds",0,0,bytes.size.toLong(),emptyList())
            assertTrue(node.warnings.isEmpty())
            assertTrue(node.toJsonValue().renderCompact().contains("\"interpretation\":\"UNINTERPRETED\""))
        }
    }
    @Test fun `failed payload parsing remains visible when valid header fields were retained`() {
        for (shortFlac in listOf(hex("664c6143 80000000"), hex("664c6143 80000001 00"))) {
            byteReaderOf(shortFlac).use { reader ->
                val info=parseFlacBlocks(reader,0,reader.length).first { it.type=="STREAMINFO" }
                assertTrue(info.fields.any { it.name=="block_length" })
                assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(info))
            }
        }
        val dfla=hex("00000000 80000001 00")
        byteReaderOf(dfla).use { reader ->
            val info=DflaBoxDecoder.decode(reader,"dfLa",0,0,dfla.size.toLong(),emptyList())
            assertTrue(info.fields.any { it.name=="block_length" })
            assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(info))
        }
        val aux=hex("00000000 616263")
        byteReaderOf(aux).use { reader ->
            val node=AuxCBoxDecoder.decode(reader,"auxC",0,0,aux.size.toLong(),emptyList())
            assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(node))
        }
        val apv=hex("00000000 0101 0101")
        byteReaderOf(apv).use { reader ->
            assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(ApvCBoxDecoder.decode(reader,"apvC",0,0,apv.size.toLong(),emptyList())))
        }
    }
    @Test fun `TIFF unknown tag is uninterpreted but decoded width remains parsed`() {
        val bytes=hex("49492a00 08000000 0200 000104000100000040000000 ffff03000100000002000000 00000000")
        byteReaderOf(bytes).use { reader ->
            val node=decodeTiff(reader,0,reader.length).first()
            assertEquals(ValueInterpretation.PARSED,node.fields.first { it.name=="ImageWidth" }.interpretation)
            assertEquals(ValueInterpretation.UNINTERPRETED,node.fields.first { it.name=="Tag 0xffff" }.interpretation)
        }
    }

}
