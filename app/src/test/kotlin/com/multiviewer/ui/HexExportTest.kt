package com.multiviewer.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexExportTest {
    private val dir = Files.createTempDirectory("hex-export-test").toFile().apply { deleteOnExit() }
    private val bytes = ByteArray(200_000) { (it * 31).toByte() }
    private val source = File(dir, "source.bin").apply { writeBytes(bytes) }

    // FileOutputStream truncates before a single byte is read: exporting onto the opened file
    // itself used to destroy it.
    @Test
    fun `refuses to overwrite the source file`() {
        val result = exportRangeBytesToFile(source, 0L..9L, File(dir, "./source.bin"))
        assertTrue(result.isFailure)
        assertContentEquals(bytes, source.readBytes())
    }

    @Test
    fun `a failed export leaves no partial file behind`() {
        val dest = File(dir, "partial.bin")
        val result = exportRangeBytesToFile(source, 100L..(bytes.size + 50_000L), dest)
        assertTrue(result.isFailure)
        assertFalse(dest.exists())
    }
}
