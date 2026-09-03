package com.multiviewer.parser

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer

class FlvWalkerTest {

    private fun uint32BE(value: Long): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun doubleToBytes(d: Double): ByteArray {
        val buf = ByteBuffer.allocate(8)
        buf.putDouble(d)
        return buf.array()
    }

    @Test
    fun testParseFlvHeaderAndScriptTag() {
        // FLV header (9 bytes)
        // 'FLV', version 1, flags 0x05 (video + audio), offset 9
        val header = byteArrayOf(0x46, 0x4C, 0x56, 0x01, 0x05, 0x00, 0x00, 0x00, 0x09)
        val prevTagSize0 = byteArrayOf(0x00, 0x00, 0x00, 0x00)

        // AMF0 payload:
        // String "onMetaData"
        val onMetaStr = byteArrayOf(0x02, 0x00, 0x0A) + "onMetaData".toByteArray(Charsets.UTF_8)
        // ECMA array (type 8, count 2)
        // "duration" (double 5.0)
        val durKey = byteArrayOf(0x00, 0x08) + "duration".toByteArray(Charsets.UTF_8)
        val durVal = byteArrayOf(0x00) + doubleToBytes(5.0)
        // "width" (double 1920.0)
        val wKey = byteArrayOf(0x00, 0x05) + "width".toByteArray(Charsets.UTF_8)
        val wVal = byteArrayOf(0x00) + doubleToBytes(1920.0)
        // "height" (double 1080.0)
        val hKey = byteArrayOf(0x00, 0x06) + "height".toByteArray(Charsets.UTF_8)
        val hVal = byteArrayOf(0x00) + doubleToBytes(1080.0)
        // Object end: 00 00 09
        val endMarker = byteArrayOf(0x00, 0x00, 0x09)

        val amfPayload = onMetaStr + byteArrayOf(0x08, 0x00, 0x00, 0x00, 0x03) + durKey + durVal + wKey + wVal + hKey + hVal + endMarker

        // Tag header (11 bytes):
        // Type 18 (0x12), DataSize (3 bytes), Timestamp (3 bytes), TimestampExt (1 byte), StreamID (3 bytes)
        val tagHeader = byteArrayOf(
            0x12,
            ((amfPayload.size shr 16) and 0xFF).toByte(),
            ((amfPayload.size shr 8) and 0xFF).toByte(),
            (amfPayload.size and 0xFF).toByte(),
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00,
        )

        val flvBytes = header + prevTagSize0 + tagHeader + amfPayload + uint32BE((11 + amfPayload.size).toLong())

        val reader = byteReaderOf(flvBytes)
        val nodes = parseFlv(reader, 0, reader.length)
        reader.close()

        val flvHeader = nodes.find { it.type == "FLV Header" }
        assertNotNull(flvHeader)
        assertEquals("true", flvHeader?.fields?.find { it.name == "has_video" }?.value)
        assertEquals("true", flvHeader?.fields?.find { it.name == "has_audio" }?.value)

        val scriptTag = nodes.find { it.type == "ScriptTag (onMetaData)" }
        assertNotNull(scriptTag)
        assertEquals("1920.00", scriptTag?.fields?.find { it.name == "width" }?.value)
        assertEquals("1080.00", scriptTag?.fields?.find { it.name == "height" }?.value)
        assertEquals("5.00", scriptTag?.fields?.find { it.name == "duration" }?.value)
    }

    @Test
    fun testBuildFlvMediaSummary() {
        val header = byteArrayOf(0x46, 0x4C, 0x56, 0x01, 0x01, 0x00, 0x00, 0x00, 0x09)
        val prevTagSize0 = byteArrayOf(0x00, 0x00, 0x00, 0x00)

        val onMetaStr = byteArrayOf(0x02, 0x00, 0x0A) + "onMetaData".toByteArray(Charsets.UTF_8)
        val durKey = byteArrayOf(0x00, 0x08) + "duration".toByteArray(Charsets.UTF_8)
        val durVal = byteArrayOf(0x00) + doubleToBytes(10.5)
        val wKey = byteArrayOf(0x00, 0x05) + "width".toByteArray(Charsets.UTF_8)
        val wVal = byteArrayOf(0x00) + doubleToBytes(640.0)
        val hKey = byteArrayOf(0x00, 0x06) + "height".toByteArray(Charsets.UTF_8)
        val hVal = byteArrayOf(0x00) + doubleToBytes(360.0)
        val codecKey = byteArrayOf(0x00, 0x0C) + "videocodecid".toByteArray(Charsets.UTF_8)
        val codecVal = byteArrayOf(0x00) + doubleToBytes(7.0) // 7 = AVC
        val endMarker = byteArrayOf(0x00, 0x00, 0x09)

        val amfPayload = onMetaStr + byteArrayOf(0x08, 0x00, 0x00, 0x00, 0x04) + durKey + durVal + wKey + wVal + hKey + hVal + codecKey + codecVal + endMarker
        val tagHeader = byteArrayOf(
            0x12,
            ((amfPayload.size shr 16) and 0xFF).toByte(),
            ((amfPayload.size shr 8) and 0xFF).toByte(),
            (amfPayload.size and 0xFF).toByte(),
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00,
        )

        val flvBytes = header + prevTagSize0 + tagHeader + amfPayload + uint32BE((11 + amfPayload.size).toLong())

        val tmpFile = File.createTempFile("test-flv-", ".flv").apply {
            writeBytes(flvBytes)
            deleteOnExit()
        }

        val root = parseFile(tmpFile)
        val summary = buildMediaSummary(root, tmpFile)

        assertEquals(MediaCategory.VIDEO, summary.category)
        val general = summary.sections.find { it.title == "General" }
        assertNotNull(general)
        assertEquals("Flash Video (FLV)", general?.fields?.find { it.label == "Format" }?.value)

        val video = summary.sections.find { it.title == "Video" }
        assertNotNull(video)
        assertEquals("640", video?.fields?.find { it.label == "Width" }?.value)
        assertEquals("360", video?.fields?.find { it.label == "Height" }?.value)
        assertEquals("AVC/H.264", video?.fields?.find { it.label == "Format" }?.value)

        tmpFile.delete()
    }
}
