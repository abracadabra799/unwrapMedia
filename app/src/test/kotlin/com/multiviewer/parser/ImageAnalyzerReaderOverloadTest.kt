package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageAnalyzerReaderOverloadTest {
    @Test
    fun `reader-accepting overload produces the same forensic data as the file-only overload`() {
        // A minimal but real baseline JPEG (SOI, a DQT with a recognizable quality-estimate
        // field, EOI) -- rich enough that ImageAnalyzer.analyze does real work (quality
        // estimate, thumbnail-extraction attempt) rather than trivially returning defaults.
        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val dqtBody = byteArrayOf(0x00) + ByteArray(64) { 8 } // table 0, all quant values = 8 (~high quality)
        val dqtLength = (dqtBody.size + 2).toShort()
        val dqt = byteArrayOf(0xFF.toByte(), 0xDB.toByte()) +
            byteArrayOf((dqtLength.toInt() shr 8).toByte(), (dqtLength.toInt() and 0xFF).toByte()) +
            dqtBody
        val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val bytes = soi + dqt + eoi

        val tmp = File.createTempFile("multiviewer-reader-overload", ".jpg")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)
        val root = parseFile(tmp)

        val viaFileOnly = ImageAnalyzer.analyze(tmp, root)
        val viaSharedReader = ByteReader.open(tmp).use { reader -> ImageAnalyzer.analyze(tmp, root, reader) }

        assertEquals(viaFileOnly, viaSharedReader)
    }
}
