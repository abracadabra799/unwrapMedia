package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class OggStructureTest {
    @Test
    fun validOpusPagesHaveMatchingCrcAndFlags() {
        val bytes = page(7, 0, 2, opusHead()) + page(7, 1, 4, byteArrayOf(1, 2, 3))
        assertTrue(check(bytes).none { it.status == CheckStatus.FAIL || it.status == CheckStatus.WARN })
    }

    @Test
    fun crcMismatchFails() {
        val bytes = page(7, 0, 2, opusHead()).also { it[22] = (it[22].toInt() xor 1).toByte() }
        assertTrue(check(bytes).any { it.id == "ogg.page.crc" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun pageSequenceGapFails() {
        val bytes = page(7, 0, 2, opusHead()) + page(7, 2, 4, byteArrayOf(1))
        assertTrue(check(bytes).any { it.id == "ogg.page.sequence" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun multiplexedLogicalStreamsTrackIndependently() {
        val bytes = page(7, 0, 2, opusHead()) + page(8, 0, 2, opusHead()) +
            page(7, 1, 4, byteArrayOf(1)) + page(8, 1, 4, byteArrayOf(2))
        assertTrue(check(bytes).none { it.id == "ogg.page.sequence" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun sequenceNumberWrapsAsUnsigned32Bit() {
        val bytes = page(9, 0xffff_fffeL, 0, byteArrayOf(1)) + page(9, 0xffff_ffffL, 0, byteArrayOf(1)) + page(9, 0, 4, byteArrayOf(1))
        assertTrue(check(bytes).none { it.id == "ogg.page.sequence" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun truncatedPageFails() {
        val bytes = page(7, 0, 2, opusHead()).dropLast(2).toByteArray()
        assertTrue(check(bytes).any { it.id == "ogg.page.bounds" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun unknownCodecSkipsCodecSpecificValidation() {
        assertTrue(check(page(7, 0, 2, "unknown".toByteArray())).any { it.id == "ogg.codec" && it.status == CheckStatus.SKIP })
    }

    private fun check(bytes: ByteArray): List<IntegrityCheckItem> = ByteReader.open(
        File.createTempFile("ogg-structure-", ".ogg").also { it.deleteOnExit(); it.writeBytes(bytes) },
    ).use(::checkOggStructure)

    private fun opusHead() = "OpusHead".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0)

    private fun page(serial: Int, sequence: Long, flags: Int, packet: ByteArray): ByteArray {
        require(packet.size < 255)
        val bytes = ByteArrayOutputStream().apply {
            write("OggS".toByteArray(Charsets.US_ASCII)); write(0); write(flags)
            write(ByteArray(8)); write(le32(serial.toLong())); write(le32(sequence)); write(ByteArray(4))
            write(1); write(packet.size); write(packet)
        }.toByteArray()
        val crc = oggCrc(bytes)
        le32(crc).copyInto(bytes, 22)
        return bytes
    }

    private fun oggCrc(bytes: ByteArray): Long {
        var crc = 0L
        for (i in bytes.indices) {
            val value = if (i in 22..25) 0 else bytes[i].toInt() and 0xff
            crc = crc xor (value.toLong() shl 24)
            repeat(8) { crc = if (crc and 0x8000_0000L != 0L) (crc shl 1) xor 0x04c1_1db7L else crc shl 1 }
            crc = crc and 0xffff_ffffL
        }
        return crc
    }

    private fun le32(value: Long) = byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())
}
