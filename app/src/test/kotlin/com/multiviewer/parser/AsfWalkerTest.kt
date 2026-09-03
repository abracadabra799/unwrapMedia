package com.multiviewer.parser

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class AsfWalkerTest {

    private fun guidBytes(uuid: UUID): ByteArray {
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        val d1 = (msb shr 32) and 0xFFFFFFFFL
        val d2 = (msb shr 16) and 0xFFFFL
        val d3 = msb and 0xFFFFL

        val bytes = ByteArray(16)
        bytes[0] = (d1 and 0xFF).toByte()
        bytes[1] = ((d1 shr 8) and 0xFF).toByte()
        bytes[2] = ((d1 shr 16) and 0xFF).toByte()
        bytes[3] = ((d1 shr 24) and 0xFF).toByte()
        bytes[4] = (d2 and 0xFF).toByte()
        bytes[5] = ((d2 shr 8) and 0xFF).toByte()
        bytes[6] = (d3 and 0xFF).toByte()
        bytes[7] = ((d3 shr 8) and 0xFF).toByte()

        for (i in 0..7) {
            bytes[8 + i] = ((lsb shr (56 - i * 8)) and 0xFF).toByte()
        }
        return bytes
    }

    private fun uint64LE(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 32) and 0xFF).toByte(),
        ((value shr 40) and 0xFF).toByte(),
        ((value shr 48) and 0xFF).toByte(),
        ((value shr 56) and 0xFF).toByte(),
    )

    private fun uint32LE(value: Long): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun uint16LE(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )

    @Test
    fun testParseAsfHeaderAndFileProperties() {
        // File properties payload (80 bytes without object header)
        // fileId(16), fileSize(8), creationDate(8), dataPackets(8), playDuration100ns(8 = 50,000,000 -> 5.0s)
        // sendDuration(8), preroll(8), flags(4), minPacket(4), maxPacket(4), maxBitrate(4 = 500,000)
        val filePropsPayload = ByteArray(16) + uint64LE(1000000) + ByteArray(8) + uint64LE(500) +
            uint64LE(50_000_000L) + ByteArray(8) + uint64LE(1000) + ByteArray(4) +
            ByteArray(4) + ByteArray(4) + uint32LE(500000)
        val filePropsObjSize = 24L + filePropsPayload.size
        val filePropsObj = guidBytes(AsfGuids.FILE_PROPERTIES) + uint64LE(filePropsObjSize) + filePropsPayload

        // Stream properties for video
        // streamType(16), errorCorrection(16), timeOffset(8), typeDataLen(4), errDataLen(4), flags(2 = stream 1), reserved(4)
        // typeData: encWidth(4 = 1280), encHeight(4 = 720), flags(1), formatDataSize(2 = 40)
        // bmpHeader(40): biSize(4=40), width(4=1280), height(4=720), planes(2=1), bitCount(2=24), compression(4="WMV3"), sizeImage(4)
        val bmpHeader = uint32LE(40) + uint32LE(1280) + uint32LE(720) + uint16LE(1) + uint16LE(24) +
            "WMV3".toByteArray(Charsets.US_ASCII) + uint32LE(1280 * 720 * 3) + ByteArray(16)
        val typeData = uint32LE(1280) + uint32LE(720) + byteArrayOf(0) + uint16LE(bmpHeader.size) + bmpHeader
        val streamPropsPayload = guidBytes(AsfGuids.STREAM_TYPE_VIDEO) + ByteArray(16) + ByteArray(8) +
            uint32LE(typeData.size.toLong()) + uint32LE(0) + uint16LE(1) + ByteArray(4) + typeData
        val streamPropsObjSize = 24L + streamPropsPayload.size
        val streamPropsObj = guidBytes(AsfGuids.STREAM_PROPERTIES) + uint64LE(streamPropsObjSize) + streamPropsPayload

        // Header object payload: sub-objects count (4 bytes = 2) + reserved (2 bytes) + sub-objects
        val headerPayload = uint32LE(2) + uint16LE(0) + filePropsObj + streamPropsObj
        val headerObjSize = 24L + headerPayload.size
        val headerObj = guidBytes(AsfGuids.HEADER) + uint64LE(headerObjSize) + headerPayload

        val reader = byteReaderOf(headerObj)
        val nodes = parseAsf(reader, 0, reader.length)
        reader.close()

        val asfHeader = nodes.find { it.type == "ASF Header Object" }
        assertNotNull(asfHeader)

        val fileProps = asfHeader?.children?.find { it.type == "File Properties Object" }
        assertNotNull(fileProps)
        assertEquals("5.000s", fileProps?.fields?.find { it.name == "play_duration_sec" }?.value)

        val streamProps = asfHeader?.children?.find { it.type == "Stream Properties Object" }
        assertNotNull(streamProps)
        assertEquals("1280", streamProps?.fields?.find { it.name == "width" }?.value)
        assertEquals("720", streamProps?.fields?.find { it.name == "height" }?.value)
        assertEquals("WMV3", streamProps?.fields?.find { it.name == "compression" }?.value)
    }

    @Test
    fun testBuildAsfMediaSummary() {
        val filePropsPayload = ByteArray(16) + uint64LE(500000) + ByteArray(8) + uint64LE(100) +
            uint64LE(30_000_000L) + ByteArray(8) + uint64LE(500) + ByteArray(4) +
            ByteArray(4) + ByteArray(4) + uint32LE(200000)
        val filePropsObj = guidBytes(AsfGuids.FILE_PROPERTIES) + uint64LE(24L + filePropsPayload.size) + filePropsPayload

        val bmpHeader = uint32LE(40) + uint32LE(1920) + uint32LE(1080) + uint16LE(1) + uint16LE(24) +
            "WVC1".toByteArray(Charsets.US_ASCII) + ByteArray(20)
        val typeData = uint32LE(1920) + uint32LE(1080) + byteArrayOf(0) + uint16LE(bmpHeader.size) + bmpHeader
        val streamPropsPayload = guidBytes(AsfGuids.STREAM_TYPE_VIDEO) + ByteArray(16) + ByteArray(8) +
            uint32LE(typeData.size.toLong()) + uint32LE(0) + uint16LE(1) + ByteArray(4) + typeData
        val streamPropsObj = guidBytes(AsfGuids.STREAM_PROPERTIES) + uint64LE(24L + streamPropsPayload.size) + streamPropsPayload

        val headerPayload = uint32LE(2) + uint16LE(0) + filePropsObj + streamPropsObj
        val headerObj = guidBytes(AsfGuids.HEADER) + uint64LE(24L + headerPayload.size) + headerPayload

        val tmpFile = File.createTempFile("test-asf-", ".wmv").apply {
            writeBytes(headerObj)
            deleteOnExit()
        }

        val root = parseFile(tmpFile)
        val summary = buildMediaSummary(root, tmpFile)

        assertEquals(MediaCategory.VIDEO, summary.category)
        val general = summary.sections.find { it.title == "General" }
        assertNotNull(general)
        assertEquals("Windows Media (ASF/WMV)", general?.fields?.find { it.label == "Format" }?.value)

        val video = summary.sections.find { it.title == "Video" }
        assertNotNull(video)
        assertEquals("1920", video?.fields?.find { it.label == "Width" }?.value)
        assertEquals("1080", video?.fields?.find { it.label == "Height" }?.value)
        assertEquals("WVC1", video?.fields?.find { it.label == "Format" }?.value)

        tmpFile.delete()
    }
}
