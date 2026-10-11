package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class Mp3StructureTest {
    @Test
    fun consecutiveMpegFramesPass() {
        assertTrue(check(frames()).all { it.status == CheckStatus.PASS })
    }

    @Test
    fun junkBetweenFramesFails() {
        val first = frame()
        val bytes = first + byteArrayOf(0x55) + frame()
        assertTrue(check(bytes).any { it.id == "mp3.frame.gap" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun truncatedLastFrameFails() {
        assertTrue(check(frame() + frame().dropLast(3).toByteArray()).any { it.id == "mp3.frame.truncated" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun id3v2PrefixAndId3v1TailAreExcludedFromFrameWalk() {
        val id3 = "ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
        val tag = ByteArray(128).also { "TAG".toByteArray(Charsets.US_ASCII).copyInto(it) }
        val items = check(id3 + frames() + tag)
        assertTrue(items.none { it.status == CheckStatus.FAIL }, items.toString())
    }

    @Test
    fun apeFooterIsExcludedFromFrameWalk() {
        val footer = ByteArray(32).also {
            "APETAGEX".toByteArray(Charsets.US_ASCII).copyInto(it)
            le32(2000).copyInto(it, 8)
            le32(32).copyInto(it, 12)
        }
        val items = check(frames() + footer)
        assertTrue(items.none { it.status == CheckStatus.FAIL }, items.toString())
    }

    @Test
    fun incompatibleSampleRateChangeFails() {
        assertTrue(check(frame() + frame(sampleRateIndex = 1)).any { it.id == "mp3.frame.continuity" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun xingAndVbriCountsAreComparedToObservedFrames() {
        val xing = frame().also { bytes ->
            "Xing".toByteArray(Charsets.US_ASCII).copyInto(bytes, 36)
            be32(3).copyInto(bytes, 40)
            be32(99).copyInto(bytes, 44)
            be32(bytes.size * 2).copyInto(bytes, 48)
        }
        assertTrue(check(xing + frame()).any { it.id == "mp3.xing.count" && it.status == CheckStatus.WARN })

        val vbri = frame().also { bytes ->
            "VBRI".toByteArray(Charsets.US_ASCII).copyInto(bytes, 36)
            be16(1).copyInto(bytes, 40)
            be16(0).copyInto(bytes, 42)
            be16(0).copyInto(bytes, 44)
            be32(bytes.size * 2).copyInto(bytes, 46)
            be32(99).copyInto(bytes, 50)
        }
        assertTrue(check(vbri + frame()).any { it.id == "mp3.vbri.count" && it.status == CheckStatus.WARN })
    }

    @Test
    fun freeFormatIsReportedAsUnverified() {
        assertTrue(check(frame(bitrateIndex = 0)).any { it.status == CheckStatus.SKIP || it.status == CheckStatus.WARN })
    }

    @Test
    fun mpeg2LayerTwoUsesItsOwnBitrateTable() {
        val items = check(frame(versionBits = 2, layerBits = 2, bitrateIndex = 1) + frame(versionBits = 2, layerBits = 2, bitrateIndex = 1))
        assertTrue(items.none { it.id == "mp3.frame.truncated" || it.id == "mp3.frame.gap" }, items.toString())
    }

    private fun check(bytes: ByteArray): List<IntegrityCheckItem> = ByteReader.open(
        File.createTempFile("mp3-structure-", ".mp3").also { it.deleteOnExit(); it.writeBytes(bytes) },
    ).use(::checkMp3Structure)

    private fun frames() = frame() + frame() + frame()
    private fun frame(
        versionBits: Int = 3,
        layerBits: Int = 1,
        bitrateIndex: Int = 9,
        sampleRateIndex: Int = 0,
        mode: Int = 0,
    ): ByteArray {
        val rateBase = intArrayOf(44100, 48000, 32000)[sampleRateIndex]
        val rate = when (versionBits) { 3 -> rateBase; 2 -> rateBase / 2; else -> rateBase / 4 }
        val bitrate = when {
            bitrateIndex == 0 -> 0
            versionBits == 3 && layerBits == 1 -> intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)[bitrateIndex] * 1000
            versionBits == 2 && layerBits == 2 -> intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)[bitrateIndex] * 1000
            else -> 128000
        }
        val length = if (bitrate == 0) 64 else when (layerBits) {
            3 -> (12 * bitrate / rate) * 4
            1 -> (if (versionBits == 3) 144 else 72) * bitrate / rate
            else -> 144 * bitrate / rate
        }
        val bytes = ByteArray(if (bitrate == 0) 64 else length)
        bytes[0] = 0xff.toByte()
        bytes[1] = (0xe0 or (versionBits shl 3) or (layerBits shl 1) or 1).toByte()
        bytes[2] = ((bitrateIndex shl 4) or (sampleRateIndex shl 2)).toByte()
        bytes[3] = (mode shl 6).toByte()
        return bytes
    }

    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())
    private fun be16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
    private fun be32(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}
