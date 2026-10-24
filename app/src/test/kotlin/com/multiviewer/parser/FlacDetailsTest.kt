package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class FlacDetailsTest {
    @Test fun `real audio seek points expose sample numbers stream offsets and frame sizes`() {
        ByteReader.open(File("src/test/resources/detail-parsing/seekpoints.flac")).use { reader ->
            val nodes=parseFlacBlocks(reader,0,reader.length)
            val seek=nodes.first { it.type=="SEEKTABLE" }
            assertEquals(3,seek.children.size)
            val second=seek.children[1]
            assertEquals("4608",second.fields.first { it.name=="sample_number" }.value)
            assertEquals("1260",second.fields.first { it.name=="stream_offset" }.value)
            assertEquals("4608",second.fields.first { it.name=="frame_samples" }.value)
            assertEquals(64L,second.offset)
            assertEquals("3",seek.fields.find { it.name=="block_type" }?.value)
            assertEquals("54",seek.fields.find { it.name=="block_length" }?.value)
            assertEquals("false",seek.fields.find { it.name=="is_last_metadata_block" }?.value)
            assertTrue(seek.warnings.isEmpty())
        }
        val mp4=assertNotNull(findFirst(parseFile(File("src/test/resources/detail-parsing/default-flac.mp4"))) { it.type=="dfLa" })
        assertEquals("bd486fb020ea6bfa1468407e81ecc5b9",mp4.fields.find { it.name=="md5_signature" }?.value)
    }
    @Test fun `seek placeholders retain unsigned values and truncated record warns`() {
        val payload=hex("ffffffffffffffff ffffffffffffffff 0000")
        val bytes=hex("664c6143 83000013")+payload+byteArrayOf(0)
        byteReaderOf(bytes).use { reader ->
            val seek=parseFlacBlocks(reader,0,reader.length).first { it.type=="SEEKTABLE" }
            assertEquals("18446744073709551615",seek.children.single().fields.first { it.name=="sample_number" }.value)
            assertEquals("true",seek.children.single().fields.first { it.name=="placeholder" }.value)
            assertTrue(seek.warnings.isNotEmpty())
        }
    }
    @Test fun `application ID and payload are visible while short dfLa cannot borrow following data`() {
        val bytes=hex("664c6143 82000007 54455354 0102ff")
        byteReaderOf(bytes).use { reader ->
            val app=parseFlacBlocks(reader,0,reader.length).first { it.type=="APPLICATION" }
            assertEquals("TEST",app.fields.find { it.name=="application_id" }?.value)
            assertEquals("01 02 ff",app.fields.find { it.name=="raw_payload" }?.value)
        }
        val bad=hex("00000000 80000001")+ByteArray(34)
        byteReaderOf(bad).use { reader ->
            val node=DflaBoxDecoder.decode(reader,"dfLa",0,0,bad.size.toLong(),emptyList())
            assertTrue(node.fields.none { it.name=="sample_rate" })
            assertTrue(node.warnings.isNotEmpty())
            assertTrue(DflaBoxDecoder.decode(reader,"dfLa",0,0,1000,emptyList()).warnings.isNotEmpty())
        }
    }
}
