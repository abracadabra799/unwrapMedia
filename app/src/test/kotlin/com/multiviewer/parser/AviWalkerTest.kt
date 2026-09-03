package com.multiviewer.parser

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class AviWalkerTest {

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
    fun testParseAviRiffAndHeader() {
        // Build synthetic avih chunk (56 bytes payload)
        // microSecPerFrame: 33333 (~30fps), maxBytesPerSec: 1000000, totalFrames: 300, streams: 2, width: 1920, height: 1080
        val avihPayload = uint32LE(33333) + uint32LE(1000000) + ByteArray(8) +
            uint32LE(300) + ByteArray(4) + uint32LE(2) + ByteArray(4) +
            uint32LE(1920) + uint32LE(1080) + ByteArray(16)
        val avihChunk = "avih".toByteArray(Charsets.US_ASCII) + uint32LE(avihPayload.size.toLong()) + avihPayload

        // Build synthetic hdrl LIST
        val hdrlPayload = "hdrl".toByteArray(Charsets.US_ASCII) + avihChunk
        val hdrlChunk = "LIST".toByteArray(Charsets.US_ASCII) + uint32LE(hdrlPayload.size.toLong()) + hdrlPayload

        // Build RIFF AVI container
        val riffPayload = "AVI ".toByteArray(Charsets.US_ASCII) + hdrlChunk
        val aviBytes = "RIFF".toByteArray(Charsets.US_ASCII) + uint32LE(riffPayload.size.toLong()) + riffPayload

        val reader = byteReaderOf(aviBytes)
        val nodes = parseAviChunks(reader, 0, reader.length)
        reader.close()

        assertEquals(1, nodes.size)
        val riff = nodes[0]
        assertEquals("RIFF", riff.type)

        val hdrl = riff.children.find { it.type == "LIST (hdrl)" }
        assertNotNull(hdrl)

        val avih = hdrl?.children?.find { it.type == "avih" }
        assertNotNull(avih)
        assertEquals("1920", avih?.fields?.find { it.name == "width" }?.value)
        assertEquals("1080", avih?.fields?.find { it.name == "height" }?.value)
        assertEquals("300", avih?.fields?.find { it.name == "total_frames" }?.value)
        assertEquals("30.00", avih?.fields?.find { it.name == "fps" }?.value)
    }

    @Test
    fun testBuildAviMediaSummary() {
        val avihPayload = uint32LE(33333) + uint32LE(1000000) + ByteArray(8) +
            uint32LE(300) + ByteArray(4) + uint32LE(1) + ByteArray(4) +
            uint32LE(1280) + uint32LE(720) + ByteArray(16)
        val avihChunk = "avih".toByteArray(Charsets.US_ASCII) + uint32LE(avihPayload.size.toLong()) + avihPayload

        // Build strh for video stream
        // fccType: vids, fccHandler: H264, scale: 1, rate: 30, length: 300
        val strhPayload = "vids".toByteArray(Charsets.US_ASCII) + "H264".toByteArray(Charsets.US_ASCII) +
            ByteArray(12) + uint32LE(1) + uint32LE(30) + uint32LE(0) + uint32LE(300) + ByteArray(16)
        val strhChunk = "strh".toByteArray(Charsets.US_ASCII) + uint32LE(strhPayload.size.toLong()) + strhPayload

        val strlPayload = "strl".toByteArray(Charsets.US_ASCII) + strhChunk
        val strlChunk = "LIST".toByteArray(Charsets.US_ASCII) + uint32LE(strlPayload.size.toLong()) + strlPayload

        val hdrlPayload = "hdrl".toByteArray(Charsets.US_ASCII) + avihChunk + strlChunk
        val hdrlChunk = "LIST".toByteArray(Charsets.US_ASCII) + uint32LE(hdrlPayload.size.toLong()) + hdrlPayload

        val riffPayload = "AVI ".toByteArray(Charsets.US_ASCII) + hdrlChunk
        val aviBytes = "RIFF".toByteArray(Charsets.US_ASCII) + uint32LE(riffPayload.size.toLong()) + riffPayload

        val tmpFile = File.createTempFile("test-avi-", ".avi").apply {
            writeBytes(aviBytes)
            deleteOnExit()
        }

        val root = parseFile(tmpFile)
        val summary = buildMediaSummary(root, tmpFile)

        assertEquals(MediaCategory.VIDEO, summary.category)
        val general = summary.sections.find { it.title == "General" }
        assertNotNull(general)
        assertEquals("AVI", general?.fields?.find { it.label == "Format" }?.value)

        val video = summary.sections.find { it.title == "Video" }
        assertNotNull(video)
        assertEquals("H264", video?.fields?.find { it.label == "Format" }?.value)
        assertEquals("1280", video?.fields?.find { it.label == "Width" }?.value)
        assertEquals("720", video?.fields?.find { it.label == "Height" }?.value)

        tmpFile.delete()
    }
}
