package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertTrue

class FlacStructureTest {
    @Test
    fun validStreamInfoAndFrameCrcsPass() {
        assertTrue(check(flac()).all { it.status == CheckStatus.PASS })
    }

    @Test
    fun metadataBlockPastEofFails() {
        val bytes = "fLaC".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertTrue(check(bytes).any { it.id == "flac.metadata.bounds" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun duplicateStreamInfoFails() {
        val blocks = listOf(block(0, streamInfo()), block(0, streamInfo(), last = true))
        assertTrue(check(flac(blocks, emptyList())).any { it.id == "flac.streaminfo.unique" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun reservedMetadataTypeWarns() {
        val blocks = listOf(block(0, streamInfo()), block(7, byteArrayOf(), last = true))
        assertTrue(check(flac(blocks, emptyList())).any { it.id == "flac.metadata.reserved" && it.status == CheckStatus.WARN })
    }

    @Test
    fun badFrameHeaderCrcFails() {
        val frames = listOf(frame(0), frame(1)).toMutableList()
        frames[0] = frames[0].copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertTrue(check(flac(frames = frames)).any { it.id == "flac.frame.headerCrc" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun badFrameCrcFails() {
        val frames = listOf(frame(0), frame(1)).toMutableList()
        frames[0] = frames[0].copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertTrue(check(flac(frames = frames)).any { it.id == "flac.frame.crc" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun truncatedFinalFrameFails() {
        val frames = listOf(frame(0), frame(1).dropLast(1).toByteArray())
        assertTrue(check(flac(frames = frames)).any { it.id == "flac.frame.truncated" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun junkBetweenFramesIsNotAbsorbedIntoPreviousCrc() {
        val frames = frame(0) + byteArrayOf(0, 0) + frame(1)
        assertTrue(check(flac(frames = listOf(frames))).any {
            it.id == "flac.frame.resync" && it.status == CheckStatus.WARN
        })
    }

    @Test
    fun laterValidCrcBoundaryWinsOverCrcZeroInsideFrame() {
        val items = check(flac(frames = listOf(frameWithIntermediateCrcZero() + frame(1))))
        assertTrue(items.any { it.id == "flac.frame.crc" && it.status == CheckStatus.PASS && it.offset == 51L }, items.toString())
        assertTrue(items.none { it.id == "flac.frame.boundary" }, items.toString())
    }

    @Test
    fun interruptedScanStopsPromptly() {
        val file = File.createTempFile("flac-interrupt-", ".flac").also { it.deleteOnExit(); it.writeBytes(flac()) }
        Thread.currentThread().interrupt()
        try {
            kotlin.test.assertFailsWith<java.util.concurrent.CancellationException> {
                ByteReader.open(file).use(::checkFlacStructure)
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun successfulFrameFindingsAreCapped() {
        val items = check(flac(frames = List(1100) { frame(it % 100) }))
        assertTrue(items.count { it.id == "flac.frame.crc" && it.status == CheckStatus.PASS } <= 1000)
        assertTrue(items.any { it.id == "flac.frame.findingsTruncated" && it.status == CheckStatus.WARN })
    }

    @Test
    fun largePaddingMetadataIsWalkedWithoutAllocatingItsPayload() {
        val file = File.createTempFile("flac-sparse-", ".flac").also { it.deleteOnExit() }
        val totalLength = 4L * 1024 * 1024
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(totalLength)
            raf.seek(0)
            raf.write("fLaC".toByteArray(Charsets.US_ASCII))
            val payloadLength = totalLength - 8
            raf.write(byteArrayOf(0x81.toByte(), (payloadLength ushr 16).toByte(), (payloadLength ushr 8).toByte(), payloadLength.toByte()))
        }

        val items = ByteReader.open(file).use(::checkFlacStructure)

        assertTrue(items.none { it.id == "flac.metadata.bounds" && it.status == CheckStatus.FAIL })
    }

    private fun check(bytes: ByteArray): List<IntegrityCheckItem> = ByteReader.open(
        File.createTempFile("flac-structure-", ".flac").also { it.deleteOnExit(); it.writeBytes(bytes) },
    ).use(::checkFlacStructure)

    private fun flac(blocks: List<ByteArray> = listOf(block(0, streamInfo(), last = true)), frames: List<ByteArray> = listOf(frame(0), frame(1))) =
        "fLaC".toByteArray(Charsets.US_ASCII) + blocks.fold(ByteArray(0)) { a, b -> a + b } +
            frames.fold(ByteArray(0)) { a, b -> a + b }

    private fun streamInfo() = ByteArray(34)
    private fun block(type: Int, payload: ByteArray, last: Boolean = false): ByteArray =
        byteArrayOf(((if (last) 0x80 else 0) or type).toByte(), 0, 0, payload.size.toByte()) + payload

    private fun frame(number: Int): ByteArray {
        val prefix = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x10, 0, number.toByte())
        val header = prefix + byteArrayOf(crc8(prefix).toByte())
        val body = header + byteArrayOf(0)
        val crc = crc16(body)
        return body + byteArrayOf((crc ushr 8).toByte(), crc.toByte())
    }

    private fun frameWithIntermediateCrcZero(): ByteArray {
        val prefix = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x10, 0, 0)
        val header = prefix + byteArrayOf(crc8(prefix).toByte())
        val crcMarker = (0..0xffff).first { value -> crc16(header + byteArrayOf((value ushr 8).toByte(), value.toByte())) == 0 }
        val body = header + byteArrayOf((crcMarker ushr 8).toByte(), crcMarker.toByte(), 0x55)
        val crc = crc16(body)
        return body + byteArrayOf((crc ushr 8).toByte(), crc.toByte())
    }

    private fun crc8(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xff)
            repeat(8) { crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) and 0xff else (crc shl 1) and 0xff }
        }
        return crc
    }
    private fun crc16(data: ByteArray): Int {
        var crc = 0
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xff) shl 8)
            repeat(8) { crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x8005) and 0xffff else (crc shl 1) and 0xffff }
        }
        return crc
    }
}
