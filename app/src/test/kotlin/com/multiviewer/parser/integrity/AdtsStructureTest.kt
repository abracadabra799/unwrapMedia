package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AdtsStructureTest {
    @Test
    fun consecutiveAdtsFramesPass() {
        assertTrue(check(frame() + frame()).all { it.status == CheckStatus.PASS })
    }

    @Test
    fun reservedSampleRateIndexFails() {
        val bytes = frame().also { it[2] = (it[2].toInt() or 0x34).toByte() }
        assertTrue(check(bytes).any { it.id == "adts.sampleRate" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun frameLengthShorterThanHeaderFails() {
        val bytes = frame().also { it[4] = 0; it[5] = 0 }
        assertTrue(check(bytes).any { it.id == "adts.frame.length" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun framePastEndAndJunkGapFail() {
        val beyondEnd = frame().also { it[4] = 0x7f; it[5] = 0xe0.toByte() }
        assertTrue(check(beyondEnd).any { it.id == "adts.frame.truncated" && it.status == CheckStatus.FAIL })
        assertTrue(check(frame() + byteArrayOf(7, 8, 9) + frame()).any { it.id == "adts.frame.gap" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun crcPresenceIsReportedWithoutClaimingPayloadVerification() {
        val crcFrame = frame(crcPresent = true)
        assertTrue(check(crcFrame).any { it.id == "adts.crc.unchecked" && it.status == CheckStatus.SKIP })
    }

    private fun check(bytes: ByteArray): List<IntegrityCheckItem> = ByteReader.open(
        File.createTempFile("adts-structure-", ".aac").also { it.deleteOnExit(); it.writeBytes(bytes) },
    ).use(::checkAdtsStructure)

    private fun frame(crcPresent: Boolean = false): ByteArray {
        val length = if (crcPresent) 9 else 10
        return byteArrayOf(
            0xff.toByte(), if (crcPresent) 0xf0.toByte() else 0xf1.toByte(), 0x50,
            0x40, (length shr 3).toByte(), ((length and 7) shl 5 or 0x1f).toByte(), 0xfc.toByte(),
        ) + if (crcPresent) byteArrayOf(0, 0) else byteArrayOf(1, 2, 3)
    }
}
