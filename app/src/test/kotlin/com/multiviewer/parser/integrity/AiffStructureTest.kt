package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AiffStructureTest {
    @Test
    fun validPcmAiffPasses() {
        val items = check(aiff())
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it.status == CheckStatus.PASS })
    }

    @Test
    fun formSizeExtendingPastFileFails() {
        val bytes = aiff().also { putBe32(it, 4, 0x1000) }
        assertTrue(check(bytes).any { it.status == CheckStatus.FAIL && it.offset == 4L })
    }

    @Test
    fun sampleFrameCountMismatchWarns() {
        val bytes = aiff(declaredFrames = 5)
        assertTrue(check(bytes).any { it.status == CheckStatus.WARN && it.id.contains("frames") })
    }

    @Test
    fun unsupportedCompressionDoesNotClaimSampleCountPass() {
        val bytes = aiff(form = "AIFC", compression = "ima4", declaredFrames = 5)
        assertTrue(check(bytes).any { it.status == CheckStatus.SKIP })
    }

    @Test
    fun truncatedAifcCompressionNameLengthByteFailsWithoutReadingPastComm() {
        val bytes = aiff(form = "AIFC", compression = null).let { original ->
            // Replace COMM with the standard fixed 22-byte AIFC fields and end the file there.
            val commStart = 12
            val payload = original.copyOfRange(commStart + 8, commStart + 8 + 18) + "NONE".toByteArray(Charsets.US_ASCII)
            val comm = chunk("COMM", payload)
            val formPayload = "AIFC".toByteArray(Charsets.US_ASCII) + comm
            "FORM".toByteArray(Charsets.US_ASCII) + be32(formPayload.size) + formPayload
        }
        assertTrue(check(bytes).any { it.id == "aiff.comm.size" && it.status == CheckStatus.FAIL })
    }

    private fun check(bytes: ByteArray): List<IntegrityCheckItem> = ByteReader.open(
        File.createTempFile("aiff-", ".aif").also { it.deleteOnExit(); it.writeBytes(bytes) },
    ).use(::checkAiffStructure)

    private fun aiff(form: String = "AIFF", compression: String? = null, declaredFrames: Int = 4): ByteArray {
        val comm = ByteArrayOutputStream().apply {
            write(be16(1)); write(be32(declaredFrames)); write(be16(16)); write(byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0))
            if (compression != null) { write(compression.toByteArray(Charsets.US_ASCII)); write(0) }
        }.toByteArray()
        val ssnd = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0) + ByteArray(8)
        val chunks = chunk("COMM", comm) + chunk("SSND", ssnd)
        val payload = form.toByteArray(Charsets.US_ASCII) + chunks
        return "FORM".toByteArray(Charsets.US_ASCII) + be32(payload.size) + payload
    }

    private fun chunk(id: String, data: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(id.toByteArray(Charsets.US_ASCII)); write(be32(data.size)); write(data)
        if (data.size % 2 == 1) write(0)
    }.toByteArray()
    private fun be16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
    private fun be32(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    private fun putBe32(bytes: ByteArray, offset: Int, value: Int) { be32(value).copyInto(bytes, offset) }
}
