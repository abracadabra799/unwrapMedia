package com.multiviewer.parser

import kotlin.test.*

class Id3v1DetailsTest {
    @Test fun `ID3v11 track byte does not leak into the comment text`() {
        val bytes=ByteArray(128)
        "TAG".toByteArray().copyInto(bytes)
        "note".toByteArray().copyInto(bytes,97)
        bytes[126]=7
        bytes[127]=13
        byteReaderOf(bytes).use { reader ->
            val node=parseMp3(reader,0,reader.length).single()
            val comment=node.fields.first { it.name=="comment" }
            assertEquals("note",comment.value)
            assertEquals(28L,comment.length)
            val track=assertNotNull(node.fields.find { it.name=="track" })
            assertEquals("7",track.value)
            assertEquals(126L,track.offset)
        }
    }
    @Test fun `legacy comment remains thirty bytes without a nonzero track marker`() {
        val bytes=ByteArray(128)
        "TAG".toByteArray().copyInto(bytes)
        "legacy".toByteArray().copyInto(bytes,97)
        byteReaderOf(bytes).use { reader ->
            val node=parseMp3(reader,0,reader.length).single()
            assertEquals(30L,node.fields.first { it.name=="comment" }.length)
            assertTrue(node.fields.none { it.name=="track" })
        }
    }
}
