package com.multiviewer.parser.integrity

import com.multiviewer.parser.AsfGuids
import com.multiviewer.parser.ByteReader
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

class AsfStructureTest {
    @Test
    fun validHeaderAndDataObjectsPass() {
        assertTrue(check(asf()).all { it.status == CheckStatus.PASS })
    }

    @Test
    fun topLevelObjectPastFileFails() {
        val bytes = asf().also { le64(9999).copyInto(it, 16) }
        assertTrue(check(bytes).any { it.id == "asf.object.bounds" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun headerChildCountMismatchFails() {
        val bytes = asf().also { le32(99).copyInto(it, 24) }
        assertTrue(check(bytes).any { it.id == "asf.header.count" && it.status == CheckStatus.FAIL })
        val badChildSize = asf().also { le64(9999).copyInto(it, 46) }
        assertTrue(check(badChildSize).any { it.id == "asf.header.childBounds" && it.status == CheckStatus.FAIL })
    }

    @Test
    fun requiredDataObjectAndFileSizeAreChecked() {
        assertTrue(check(asf(includeData = false)).any { it.id == "asf.data.missing" && it.status == CheckStatus.FAIL })
        assertTrue(check(asf(includeStreamProperties = false)).any { it.id == "asf.streamProperties.missing" && it.status == CheckStatus.FAIL })
        val bytes = asf().also { le64(1).copyInto(it, 70) }
        assertTrue(check(bytes).any { it.id == "asf.fileSize" && it.status == CheckStatus.FAIL })
    }

    private fun check(bytes: ByteArray): List<IntegrityCheckItem> = ByteReader.open(
        File.createTempFile("asf-structure-", ".wma").also { it.deleteOnExit(); it.writeBytes(bytes) },
    ).use(::checkAsfStructure)

    private fun asf(includeData: Boolean = true, includeStreamProperties: Boolean = true): ByteArray {
        val fileProperties = objectBytes(AsfGuids.FILE_PROPERTIES, ByteArray(80))
        val streamProperties = objectBytes(AsfGuids.STREAM_PROPERTIES,
            guid(AsfGuids.STREAM_TYPE_AUDIO) + ByteArray(16 + 8 + 4 + 4 + 2 + 4))
        val children = fileProperties + if (includeStreamProperties) streamProperties else byteArrayOf()
        val headerPayload = le32(if (includeStreamProperties) 2 else 1) + byteArrayOf(1, 2) + children
        val header = objectBytes(AsfGuids.HEADER, headerPayload)
        val data = if (includeData) objectBytes(AsfGuids.DATA, ByteArray(26)) else byteArrayOf()
        val result = header + data
        le64(result.size.toLong()).copyInto(result, 70)
        return result
    }

    private fun objectBytes(id: UUID, payload: ByteArray) = guid(id) + le64(24L + payload.size) + payload
    private fun guid(uuid: UUID): ByteArray {
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        val bytes = ByteArray(16)
        val d1 = msb ushr 32 and 0xffff_ffffL
        val d2 = msb ushr 16 and 0xffffL
        val d3 = msb and 0xffffL
        for (i in 0..3) bytes[i] = (d1 ushr (i * 8)).toByte()
        bytes[4] = d2.toByte(); bytes[5] = (d2 ushr 8).toByte()
        bytes[6] = d3.toByte(); bytes[7] = (d3 ushr 8).toByte()
        for (i in 0..7) bytes[8 + i] = (lsb ushr (56 - i * 8)).toByte()
        return bytes
    }
    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())
    private fun le64(value: Long) = byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte(), (value ushr 32).toByte(), (value ushr 40).toByte(), (value ushr 48).toByte(), (value ushr 56).toByte())
}
