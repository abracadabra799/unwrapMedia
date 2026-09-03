package com.multiviewer.parser

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class AacWalkerTest {

    private fun buildSyntheticAdtsFrame(
        profile: Int = 1, // LC
        sampleRateIdx: Int = 4, // 44100 Hz
        channelConfig: Int = 2, // Stereo
        frameLength: Int = 200,
    ): ByteArray {
        val b0 = 0xFF.toByte()
        // ID: 0 (MPEG-4), Layer: 00, ProtectionAbsent: 1 (no CRC) -> 0xF1
        val b1 = 0xF1.toByte()
        // profile(2) | sampleRateIdx(4) | private(1) | channelConfig_hi(1)
        val b2 = (((profile and 0x03) shl 6) or ((sampleRateIdx and 0x0F) shl 2) or ((channelConfig shr 2) and 0x01)).toByte()
        // channelConfig_lo(2) | orig(1) | home(1) | copy(1) | copyStart(1) | frameLen_hi(2)
        val b3 = (((channelConfig and 0x03) shl 6) or ((frameLength shr 11) and 0x03)).toByte()
        val b4 = ((frameLength shr 3) and 0xFF).toByte()
        val b5 = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
        val b6 = 0xFC.toByte()

        val header = byteArrayOf(b0, b1, b2, b3, b4, b5, b6)
        return header + ByteArray(frameLength - 7)
    }

    @Test
    fun testParseAacAdtsFrames() {
        val frame1 = buildSyntheticAdtsFrame(profile = 1, sampleRateIdx = 4, channelConfig = 2, frameLength = 150)
        val frame2 = buildSyntheticAdtsFrame(profile = 1, sampleRateIdx = 4, channelConfig = 2, frameLength = 180)
        val aacBytes = frame1 + frame2

        val reader = byteReaderOf(aacBytes)
        val nodes = parseAac(reader, 0, reader.length)
        reader.close()

        assertTrue(nodes.isNotEmpty())
        val firstNode = nodes[0]
        assertEquals("ADTS Frame #0", firstNode.type)
        assertEquals("44100Hz", firstNode.fields.find { it.name == "sample_rate" }?.value)
        assertEquals("2 channels (Stereo)", firstNode.fields.find { it.name == "channels" }?.value)
        assertEquals("LC (Low Complexity)", firstNode.fields.find { it.name == "profile" }?.value)
    }

    @Test
    fun testBuildAacMediaSummary() {
        val frame1 = buildSyntheticAdtsFrame(profile = 1, sampleRateIdx = 3, channelConfig = 2, frameLength = 200) // 48000Hz
        val frame2 = buildSyntheticAdtsFrame(profile = 1, sampleRateIdx = 3, channelConfig = 2, frameLength = 200)
        val aacBytes = frame1 + frame2

        val tmpFile = File.createTempFile("test-aac-", ".aac").apply {
            writeBytes(aacBytes)
            deleteOnExit()
        }

        val root = parseFile(tmpFile)
        val summary = buildMediaSummary(root, tmpFile)

        assertEquals(MediaCategory.AUDIO, summary.category)
        val general = summary.sections.find { it.title == "General" }
        assertNotNull(general)
        assertEquals("AAC (ADTS)", general?.fields?.find { it.label == "Format" }?.value)

        val audio = summary.sections.find { it.title == "Audio" }
        assertNotNull(audio)
        assertEquals("48000Hz", audio?.fields?.find { it.label == "Sampling Rate" }?.value)
        assertEquals("2 channels (Stereo)", audio?.fields?.find { it.label == "Channel(s)" }?.value)

        tmpFile.delete()
    }
}
