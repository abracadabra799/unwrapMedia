package com.multiviewer.parser

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppleMakerNoteDecoderTest {

    @Test
    fun `decodes Apple MakerNote with scalar, rational, string, and bplist tags`() {
        val bytes = buildAppleMakerNoteExif()
        val reader = byteReaderOf(bytes)
        val ifds = decodeExif(reader, 0, bytes.size.toLong())

        assertEquals(1, ifds.size)
        val ifd0 = ifds[0]
        assertEquals("Apple", ifd0.fields.first { it.name == "Make" }.value)

        val exifIfd = ifd0.children.first { it.type == "Exif" }
        val makerNote = exifIfd.children.first { it.type == "MakerNote" }
        assertEquals("MakerNote", makerNote.type)

        // MakerNoteVersion (0x0001)
        val versionField = makerNote.fields.find { it.name == "MakerNoteVersion" }
        assertEquals("14", versionField?.value)

        // AFConfidence (0x0008)
        val afConfidence = makerNote.fields.find { it.name == "AFConfidence" }
        assertEquals("3", afConfidence?.value)

        // CameraType (0x000a)
        val cameraType = makerNote.fields.find { it.name == "CameraType" }
        assertTrue(cameraType?.value?.contains("Back") == true || cameraType?.value == "0")

        // ImageCaptureType (0x0014) -> ProRAW (3)
        val captureType = makerNote.fields.find { it.name == "ImageCaptureType" }
        assertTrue(captureType?.value?.contains("ProRAW") == true)

        // HDRGain (0x0021) -> 150/100
        val hdrGain = makerNote.fields.find { it.name == "HDRGain" }
        assertTrue(hdrGain?.value?.contains("150/100") == true || hdrGain?.value?.contains("1.5") == true)

        // HDRHeadroom (0x002d) -> 200/100
        val hdrHeadroom = makerNote.fields.find { it.name == "HDRHeadroom" }
        assertTrue(hdrHeadroom?.value?.contains("200/100") == true || hdrHeadroom?.value?.contains("2") == true)

        // SmartStyle (0x0038) -> child BinaryPlist
        val smartStyleChild = makerNote.children.find { it.type.contains("SmartStyle") || it.type == "BinaryPlist" }
        assertTrue(smartStyleChild != null, "SmartStyle child should be decoded from bplist")

        // Unknown tag 0x7777 fallback
        val unknownTag = makerNote.fields.find { it.name == "Apple Tag 0x7777" }
        assertTrue(unknownTag != null, "Unknown Apple tag should be preserved with Apple Tag 0x7777")

        reader.close()
    }

    @Test
    fun `handles malformed Apple MakerNote without crashing`() {
        // Short MakerNote payload (1 byte)
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00,
            0x02, 0x00,
            0x0f, 0x01, 0x02, 0x00, 0x06, 0x00, 0x00, 0x00, 0x26, 0x00, 0x00, 0x00, // Make="Apple\0"
            0x69, 0x87.toByte(), 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x41, 0x70, 0x70, 0x6c, 0x65, 0x00, // "Apple\0" at offset 38
            0x01, 0x00, // Exif IFD: 1 entry
            0x7c, 0x92.toByte(), 0x07, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // MakerNote count=1
            0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        val makerNote = ifds.firstOrNull()?.children?.firstOrNull()?.children?.firstOrNull()
        assertTrue(makerNote != null)
        assertTrue(makerNote.warnings.isNotEmpty())
        reader.close()
    }

    private fun buildAppleMakerNoteExif(): ByteArray {
        val tiffBaos = ByteArrayOutputStream()
        // We will assemble TIFF structure:
        // Header: "II\x2A\x00" (4 bytes), IFD0 offset = 8
        // IFD0: 2 entries:
        //   - Make (0x010F), ASCII, count=6, offset=makeOffset
        //   - ExifIFDPointer (0x8769), LONG, count=1, offset=exifOffset
        //   - NextIFDOffset = 0
        // Exif IFD: 1 entry:
        //   - MakerNote (0x927C), UNDEFINED, count=makerNoteLen, offset=makerNoteOffset
        //   - NextIFDOffset = 0
        // MakerNote IFD:
        //   - count = 8 entries
        //   - 0x0001 (MakerNoteVersion): SHORT, count=1, value=14
        //   - 0x0008 (AFConfidence): SHORT, count=1, value=3
        //   - 0x000a (CameraType): SHORT, count=1, value=0 (Back)
        //   - 0x0014 (ImageCaptureType): SHORT, count=1, value=3 (ProRAW)
        //   - 0x0021 (HDRGain): RATIONAL, count=1, offset=hdrGainOffset (150/100)
        //   - 0x002d (HDRHeadroom): RATIONAL, count=1, offset=hdrHeadroomOffset (200/100)
        //   - 0x0038 (SmartStyle): UNDEFINED, count=bplistLen, offset=bplistOffset
        //   - 0x7777 (Unknown): SHORT, count=1, value=999

        val bplist = buildSampleBplist()

        val tiffStart = 4 // 4 bytes prefix for tiffHeaderOffsetField = 0
        val tiffHeader = byteArrayOf(0x49, 0x49, 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00)

        // We calculate offsets relative to tiffStart:
        // IFD0 is at offset 8 (size: 2 + 2*12 + 4 = 30 bytes, ends at 38)
        // Make string "Apple\0" at offset 38 (6 bytes, ends at 44)
        // Exif IFD at offset 44 (size: 2 + 1*12 + 4 = 18 bytes, ends at 62)
        // MakerNote IFD at offset 62:
        //   2 bytes count + 8 entries * 12 bytes + 4 bytes nextOffset = 2 + 96 + 4 = 102 bytes (ends at 164)
        // Data area:
        //   HDRGain (8 bytes) at 164 (ends at 172)
        //   HDRHeadroom (8 bytes) at 172 (ends at 180)
        //   bplist (bplist.size bytes) at 180 (ends at 180 + bplist.size)
        val makerNoteLen = 102 + 8 + 8 + bplist.size

        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeInt(0) // tiffHeaderOffsetField = 0
        dos.write(tiffHeader)

        // IFD0
        dos.writeShort(swap16(2)) // 2 entries
        // Entry 1: Make (0x010F), ASCII(2), count=6, offset=38
        writeTiffEntry(dos, 0x010F, 2, 6, 38)
        // Entry 2: ExifIFDPointer (0x8769), LONG(4), count=1, offset=44
        writeTiffEntry(dos, 0x8769, 4, 1, 44)
        dos.writeInt(0) // NextIFD = 0

        // Make string at 38
        dos.write("Apple\u0000".toByteArray(StandardCharsets.US_ASCII))

        // Exif IFD at 44
        dos.writeShort(swap16(1)) // 1 entry
        // Entry 1: MakerNote (0x927C), UNDEFINED(7), count=makerNoteLen, offset=62
        writeTiffEntry(dos, 0x927C, 7, makerNoteLen.toLong(), 62)
        dos.writeInt(0) // NextIFD = 0

        // MakerNote IFD at 62
        dos.writeShort(swap16(8)) // 8 entries
        writeTiffEntry(dos, 0x0001, 3, 1, 14) // MakerNoteVersion
        writeTiffEntry(dos, 0x0008, 3, 1, 3) // AFConfidence
        writeTiffEntry(dos, 0x000A, 3, 1, 0) // CameraType
        writeTiffEntry(dos, 0x0014, 3, 1, 3) // ImageCaptureType = ProRAW
        writeTiffEntry(dos, 0x0021, 5, 1, 164) // HDRGain at 164
        writeTiffEntry(dos, 0x002D, 5, 1, 172) // HDRHeadroom at 172
        writeTiffEntry(dos, 0x0038, 7, bplist.size.toLong(), 180) // SmartStyle at 180
        writeTiffEntry(dos, 0x7777, 3, 1, 999) // Unknown tag
        dos.writeInt(0) // NextIFD = 0

        // HDRGain: 150 / 100
        dos.writeInt(swap32(150))
        dos.writeInt(swap32(100))

        // HDRHeadroom: 200 / 100
        dos.writeInt(swap32(200))
        dos.writeInt(swap32(100))

        // bplist
        dos.write(bplist)

        return out.toByteArray()
    }

    private fun writeTiffEntry(dos: DataOutputStream, tag: Int, type: Int, count: Long, valOrOffset: Long) {
        dos.writeShort(swap16(tag))
        dos.writeShort(swap16(type))
        dos.writeInt(swap32(count.toInt()))
        dos.writeInt(swap32(valOrOffset.toInt()))
    }

    private fun swap16(v: Int): Int {
        return ((v and 0xFF) shl 8) or ((v shr 8) and 0xFF)
    }

    private fun swap32(v: Int): Int {
        return ((v and 0xFF) shl 24) or
            (((v shr 8) and 0xFF) shl 16) or
            (((v shr 16) and 0xFF) shl 8) or
            ((v ushr 24) and 0xFF)
    }

    private fun buildSampleBplist(): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write("bplist00".toByteArray(StandardCharsets.US_ASCII))
        val offsets = mutableListOf<Int>()

        // Obj 0: Dict (1 entry: "cast" -> "Rich Warm")
        offsets.add(baos.size())
        baos.write(0xD1)
        baos.write(1) // key -> obj 1
        baos.write(2) // val -> obj 2

        // Obj 1: "cast"
        offsets.add(baos.size())
        baos.write(0x54)
        baos.write("cast".toByteArray(StandardCharsets.US_ASCII))

        // Obj 2: "Rich Warm"
        offsets.add(baos.size())
        baos.write(0x59)
        baos.write("Rich Warm".toByteArray(StandardCharsets.US_ASCII))

        val tableOffset = baos.size()
        for (off in offsets) baos.write(off)

        val dos = DataOutputStream(baos)
        dos.write(ByteArray(5))
        dos.writeByte(0)
        dos.writeByte(1)
        dos.writeByte(1)
        dos.writeLong(offsets.size.toLong())
        dos.writeLong(0L)
        dos.writeLong(tableOffset.toLong())

        return baos.toByteArray()
    }
}
