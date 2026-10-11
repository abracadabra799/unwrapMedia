package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveStructureTest {
    @Test
    fun validPcmWavePasses() {
        val items = check(wave())
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it.status == CheckStatus.PASS })
    }

    @Test
    fun chunkExtendingPastEndFailsAtItsHeader() {
        val bytes = wave().copyOf().also { putLe32(it, 40, 100) }
        val item = check(bytes).first { it.status == CheckStatus.FAIL }
        assertEquals(36L, item.offset)
    }

    @Test
    fun missingRequiredDataChunkFails() {
        val bytes = riff(listOf(chunk("fmt ", fmt())))
        assertTrue(check(bytes).any { it.status == CheckStatus.FAIL && it.id.contains("data") })
    }

    @Test
    fun inconsistentPcmAlignmentWarns() {
        val bytes = riff(listOf(chunk("fmt ", fmt(channels = 2, blockAlign = 2)), chunk("data", byteArrayOf(1))))
        assertTrue(check(bytes).any { it.status == CheckStatus.WARN && it.id.contains("alignment") })
    }

    @Test
    fun compressedCodecMayOmitBitsPerSample() {
        val compressed = fmt().copyOf().also { it[0] = 0x55; it[1] = 0 }
        compressed[14] = 0
        compressed[15] = 0
        val items = check(riff(listOf(chunk("fmt ", compressed), chunk("data", byteArrayOf(1, 2, 3)))) )
        assertTrue(items.none { it.id == "wave.fmt.fields" && it.status == CheckStatus.FAIL }, items.toString())
        assertTrue(items.any { it.id == "wave.fmt.alignment" && it.status == CheckStatus.SKIP })
    }

    private fun check(bytes: ByteArray): List<IntegrityCheckItem> = ByteReader.open(
        File.createTempFile("wave-", ".wav").also { it.deleteOnExit(); it.writeBytes(bytes) },
    ).use(::checkWaveStructure)

    private fun wave() = riff(listOf(chunk("fmt ", fmt()), chunk("data", byteArrayOf(1, 2, 3, 4))))
    private fun fmt(channels: Int = 1, blockAlign: Int = channels): ByteArray = byteArrayOf(
        1, 0, channels.toByte(), 0, 0x40, 0x1F, 0, 0, 0x40, 0x1F, 0, 0,
        blockAlign.toByte(), 0, 8, 0,
    )
    private fun chunk(id: String, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(id.toByteArray(Charsets.US_ASCII)); write(le32(payload.size)); write(payload)
        if (payload.size % 2 == 1) write(0)
    }.toByteArray()
    private fun riff(chunks: List<ByteArray>): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            write("WAVE".toByteArray(Charsets.US_ASCII)); chunks.forEach(::write)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII)); write(le32(payload.size)); write(payload)
        }.toByteArray()
    }
    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())
    private fun putLe32(bytes: ByteArray, offset: Int, value: Int) { le32(value).copyInto(bytes, offset) }
}
