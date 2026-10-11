package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.parseFile
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class M4aStructureTest {
    @Test
    fun validAudioSampleTablesPass() {
        assertTrue(check(fixture()).all { it.status == CheckStatus.PASS })
    }

    @Test
    fun stcoAndCo64OffsetsPastEofFail() {
        assertTrue(check(fixture(offsetOverride = 0x7fff_ffffL)).any { it.id == "m4a.chunkOffset" && it.status == CheckStatus.FAIL })
        assertTrue(check(fixture(co64 = true, offsetOverride = 0x7fff_ffffL)).any { it.id == "m4a.chunkOffset" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun sampleExtentMustFitInsideMdatPayload() {
        val base = fixture()
        val payloadStart = (base.size - 4).toLong()
        val items = check(fixture(offsetOverride = payloadStart - 1))
        assertTrue(items.any { it.id == "m4a.sampleExtent" && it.status == CheckStatus.FAIL }, items.toString())
    }

    @Test
    fun fragmentedInitializationWithEmptyStscIsNotCorrupt() {
        val items = check(fixture(fragmented = true))
        assertTrue(items.none { it.status == CheckStatus.FAIL }, items.toString())
        assertTrue(items.any { it.status == CheckStatus.SKIP || it.status == CheckStatus.WARN }, items.toString())
    }

    @Test
    fun stszEntryCountPastTableFailsWithoutExpandingCount() {
        assertTrue(check(fixture(variableSizes = true, sampleCount = 0xffff_ffffL, sizes = listOf(4))).any {
            it.id == "m4a.stsz.bounds" && it.status == CheckStatus.FAIL
        })
    }

    @Test
    fun missingSampleMappingIsNotReportedClean() {
        assertTrue(check(fixture(includeStsc = false)).any { it.status == CheckStatus.SKIP || it.status == CheckStatus.WARN })
    }

    @Test
    fun sampleToChunkCoverageMustMatchSampleCount() {
        assertTrue(check(fixture(variableSizes = true, sampleCount = 2, sizes = listOf(4, 4))).any {
            it.id == "m4a.stsc.mapping" && it.status == CheckStatus.FAIL
        })
    }

    @Test
    fun nonAudioTrackSkipsAudioSampleTableChecks() {
        assertTrue(check(fixture(handler = "vide")).any { it.id == "m4a.audioTrack" && it.status == CheckStatus.SKIP })
    }

    private fun check(bytes: ByteArray): List<IntegrityCheckItem> {
        val file = File.createTempFile("m4a-structure-", ".m4a").also { it.deleteOnExit(); it.writeBytes(bytes) }
        val tree = parseFile(file)
        return ByteReader.open(file).use { checkM4aStructure(it, tree) }
    }

    private fun fixture(
        co64: Boolean = false,
        offsetOverride: Long? = null,
        variableSizes: Boolean = false,
        sampleCount: Long = 1,
        sizes: List<Int> = emptyList(),
        includeStsc: Boolean = true,
        handler: String = "soun",
        fragmented: Boolean = false,
    ): ByteArray {
        val ftyp = box("ftyp", "M4A \u0000\u0000\u0000\u0000M4A ".toByteArray(Charsets.ISO_8859_1))
        val mdat = box("mdat", byteArrayOf(1, 2, 3, 4))
        fun moov(offset: Long): ByteArray {
            val sizeEntries = sizes.fold(ByteArray(0)) { bytes, size -> bytes + be32(size) }
            val stsz = box("stsz", byteArrayOf(0, 0, 0, 0) + be32(if (variableSizes) 0 else 4) + be32(sampleCount) + sizeEntries)
            val chunkData = if (co64) be64(offset) else be32(offset)
            val chunkBox = box(if (co64) "co64" else "stco", byteArrayOf(0, 0, 0, 0) + be32(1) + chunkData)
            val stsc = box("stsc", byteArrayOf(0, 0, 0, 0) + be32(if (fragmented) 0 else 1) + if (fragmented) byteArrayOf() else be32(1) + be32(1) + be32(1))
            val stbl = box("stbl", stsz + chunkBox + if (includeStsc) stsc else byteArrayOf())
            val minf = box("minf", stbl)
            val hdlr = box("hdlr", ByteArray(8) + handler.toByteArray(Charsets.US_ASCII) + ByteArray(12))
            val mdia = box("mdia", hdlr + minf)
            return box("moov", box("trak", mdia) + if (fragmented) box("mvex", byteArrayOf()) else byteArrayOf())
        }
        val placeholder = moov(0)
        val mdatOffset = (ftyp.size + placeholder.size + 8).toLong()
        return ftyp + moov(offsetOverride ?: mdatOffset) + mdat
    }

    private fun box(type: String, payload: ByteArray): ByteArray = be32(payload.size + 8) + type.toByteArray(Charsets.US_ASCII) + payload
    private fun be32(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    private fun be32(value: Long) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    private fun be64(value: Long) = byteArrayOf((value ushr 56).toByte(), (value ushr 48).toByte(), (value ushr 40).toByte(), (value ushr 32).toByte(), (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}
