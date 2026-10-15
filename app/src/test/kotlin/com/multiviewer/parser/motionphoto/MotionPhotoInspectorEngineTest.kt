package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MotionPhotoInspectorEngineTest {

    private fun tempFileWithFtypAt(ftypBoxStart: Long, totalSize: Long, majorBrand: String = "isom"): File {
        val bytes = ByteArray(totalSize.toInt())
        "ftyp".toByteArray(Charsets.US_ASCII).copyInto(bytes, (ftypBoxStart + 4).toInt())
        majorBrand.toByteArray(Charsets.US_ASCII).copyInto(bytes, (ftypBoxStart + 8).toInt())
        val tmp = File.createTempFile("motion-photo-engine-test", ".bin")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)
        return tmp
    }

    private fun googleDirectoryXmp(declaredLength: Long, mime: String = "video/mp4"): String = """
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description
                xmlns:Container="http://ns.google.com/photos/1.0/container/"
                xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                GCamera:MotionPhoto="1"
                GCamera:MotionPhotoPresentationTimestampUs="500000">
              <Container:Directory>
                <rdf:Seq>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/>
                  </rdf:li>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item Item:Semantic="MotionPhoto" Item:Mime="$mime" Item:Length="$declaredLength"/>
                  </rdf:li>
                </rdf:Seq>
              </Container:Directory>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()

    private fun rootWithXmp(xmpText: String, fileSize: Long): BoxNode = BoxNode(
        type = "root", offset = 0, headerSize = 0, size = fileSize,
        children = listOf(
            BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2),
            BoxNode(
                type = "APP1", offset = 2, headerSize = 4, size = xmpText.length.toLong(),
                fields = listOf(BoxField("xmp", xmpText, 2, xmpText.length.toLong())),
            ),
            BoxNode(type = "SOF0", offset = 100, headerSize = 2, size = 17),
            BoxNode(type = "SOS", offset = 120, headerSize = 2, size = 12),
            BoxNode(type = "EOI", offset = 300, headerSize = 2, size = 2),
        ),
    )

    private fun ByteArray.putUInt16LE(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun ByteArray.putUInt32LE(offset: Int, value: Long) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
        this[offset + 2] = ((value shr 16) and 0xFF).toByte()
        this[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private data class SefTestField(val marker: Int, val name: String, val data: ByteArray)

    private fun buildSefTrailer(fields: List<SefTestField>): ByteArray {
        val blockBytesList = fields.map { f ->
            val nameBytes = f.name.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
            val header = ByteArray(8)
            header.putUInt16LE(2, f.marker)
            header.putUInt32LE(4, nameBytes.size.toLong())
            header + nameBytes + f.data
        }
        val blocksTotalSize = blockBytesList.sumOf { it.size }

        val dirSize = 12 + fields.size * 12
        val dir = ByteArray(dirSize)
        dir[0] = 'S'.code.toByte(); dir[1] = 'E'.code.toByte(); dir[2] = 'F'.code.toByte(); dir[3] = 'H'.code.toByte()
        dir.putUInt32LE(8, fields.size.toLong())
        var remaining = blocksTotalSize.toLong()
        for ((idx, f) in fields.withIndex()) {
            val entryPos = 12 + idx * 12
            dir.putUInt16LE(entryPos + 2, f.marker)
            dir.putUInt32LE(entryPos + 4, remaining)
            dir.putUInt32LE(entryPos + 8, blockBytesList[idx].size.toLong())
            remaining -= blockBytesList[idx].size
        }

        val seft = ByteArray(8)
        seft.putUInt32LE(0, dirSize.toLong())
        seft[4] = 'S'.code.toByte(); seft[5] = 'E'.code.toByte(); seft[6] = 'F'.code.toByte(); seft[7] = 'T'.code.toByte()

        var result = ByteArray(0)
        for (b in blockBytesList) result += b
        result += dir
        result += seft
        return result
    }

    @Test
    fun `test real S26 HEIC motion photo inspection`() {
        val heicFile = File("app/src/test/resources/c2pa/s26-motion-photo.heic")
        if (!heicFile.exists()) return

        val root = parseFile(heicFile)
        val report = MotionPhotoInspectorEngine.inspect(heicFile, root)

        assertNotNull(report)
        assertTrue(report.formatLabel.contains("HEIC") || report.formatLabel.contains("mpvd"))
        assertEquals(CheckStatus.PASS, report.stillImageSection.status)
        assertTrue(report.allSections.size == 5)
    }

    @Test
    fun `test Google XMP offset matching`() {
        val declaredLength = 200L
        val totalSize = 1000L
        val file = tempFileWithFtypAt(800L, totalSize) // 1000 - 200 = 800
        val xmp = googleDirectoryXmp(declaredLength)
        val root = rootWithXmp(xmp, totalSize)

        val report = MotionPhotoInspectorEngine.inspect(file, root)
        val offsetItem = report.formatXmpSection.items.find { it.id == "xmp.offset_match" }
        assertNotNull(offsetItem)
        assertEquals(CheckStatus.PASS, offsetItem.status)
    }

    @Test
    fun `test Google XMP offset mismatch produces warning`() {
        val declaredLength = 200L
        val totalSize = 1000L
        val file = tempFileWithFtypAt(816L, totalSize) // 16 bytes off from 800
        val xmp = googleDirectoryXmp(declaredLength)
        val root = rootWithXmp(xmp, totalSize)

        val report = MotionPhotoInspectorEngine.inspect(file, root)
        val offsetItem = report.formatXmpSection.items.find { it.id == "xmp.offset_match" }
        assertNotNull(offsetItem)
        assertEquals(CheckStatus.WARN, offsetItem.status)
    }

    @Test
    fun `test SEF non-motion entries and overlap validation`() {
        val f1 = SefTestField(0x0A00, "MotionPhoto_Data", ByteArray(32) { 0 })
        val f2 = SefTestField(0x0A01, "MCC_Data", ByteArray(16) { 0 })
        val trailer = buildSefTrailer(listOf(f1, f2))

        val dummyPrefix = ByteArray(100)
        val fullBytes = dummyPrefix + trailer
        val temp = File.createTempFile("sef-test", ".jpg")
        temp.deleteOnExit()
        temp.writeBytes(fullBytes)

        val sefdNode = BoxNode(
            type = "sefd", offset = 100L, headerSize = 0, size = trailer.size.toLong(),
            children = listOf(
                BoxNode(type = "MotionPhoto_Data", offset = 100L, headerSize = 0, size = 32L),
                BoxNode(type = "MCC_Data", offset = 132L, headerSize = 0, size = 16L),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = fullBytes.size.toLong(), children = listOf(sefdNode))

        val report = MotionPhotoInspectorEngine.inspect(temp, root)
        val overlapItem = report.sefSection.items.find { it.id == "sef.overlap_check" }
        assertNotNull(overlapItem)
        assertEquals(CheckStatus.PASS, overlapItem.status)

        val nonMotionItem = report.sefSection.items.find { it.id == "sef.non_motion_entries" }
        assertNotNull(nonMotionItem)
        assertEquals(CheckStatus.PASS, nonMotionItem.status)
    }
}
