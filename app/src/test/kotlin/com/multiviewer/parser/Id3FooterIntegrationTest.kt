package com.multiviewer.parser

import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.checkMp3Structure
import com.multiviewer.parser.integrity.checkAdtsStructure
import java.io.File
import kotlin.test.*

class Id3FooterIntegrationTest {
    @Test fun `valid MP3 footer is not reported as junk before the first frame`() {
        val original=File("src/test/resources/mp3-details/mpeg2-v24.mp3").readBytes()
        val tag=original.copyOfRange(0,160).also { it[5]=0x10 }
        val footer="3DI".toByteArray()+tag.copyOfRange(3,10)
        val bytes=tag+footer+original.copyOfRange(160,original.size)
        byteReaderOf(bytes).use { reader ->
            assertTrue(checkMp3Structure(reader).none { it.id=="mp3.frame.leading" })
        }
        val bad=bytes.copyOf().also { it[160]=0 }
        byteReaderOf(bad).use { reader ->
            assertTrue(checkMp3Structure(reader).any { it.id=="mp3.id3.footer" && it.status==CheckStatus.FAIL })
        }
    }
    @Test fun `AAC with a valid ID3 footer stays AAC and its first ADTS frame starts after the footer`() {
        val tag=id3TestTag(ByteArray(0),flags=0x10)
        val footer="3DI".toByteArray()+tag.copyOfRange(3,10)
        val aac=File("src/test/resources/detail-parsing/default-adts.aac").readBytes()
        val bytes=tag+footer+aac
        byteReaderOf(bytes).use { reader ->
            assertEquals(ContainerFormat.AAC,sniffFormat(reader))
            assertTrue(isConfidentSniff(ContainerFormat.AAC,reader))
            assertTrue(checkAdtsStructure(reader).none { it.status==CheckStatus.FAIL })
            assertEquals(20L,parseAac(reader,0,reader.length).first { it.type.startsWith("ADTS Frame") }.offset)
        }
    }
}
