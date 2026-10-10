package com.multiviewer.cli

import com.multiviewer.parser.integrity.jpegBytes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ImageIntegrityJsonTest {
    private fun tempJpeg(bytes: ByteArray): File =
        File.createTempFile("cli-integrity-", ".jpg").apply { deleteOnExit(); writeBytes(bytes) }

    @Test
    fun `check json for an image includes the structure report and NOT_RUN decode`() {
        val result = checkFile(tempJpeg(jpegBytes(withEoi = false))) as CheckResult.Success
        assertTrue(result.json.contains("\"imageIntegrity\""), result.json)
        assertTrue(result.json.contains("\"id\": \"jpeg.eoi\""), result.json)
        assertTrue(result.json.contains("\"overall\": \"FAIL\""), result.json)
        assertTrue(result.json.contains("\"status\": \"NOT_RUN\""), result.json)
    }

    @Test
    fun `analysis case json carries file identity and integrity`() {
        val file = tempJpeg(jpegBytes())
        val parsed = com.multiviewer.parser.parseFile(file)
        val structure = com.multiviewer.parser.integrity.ImageIntegrityChecker.check(file, parsed)
        val case = buildImageIntegrityCaseJson(file, structure, null)
        assertTrue(case.contains("\"sha256\""), case)
        assertTrue(case.contains("\"imageIntegrity\""), case)
        assertTrue(!case.contains(file.parent), "case must not contain the absolute path")
    }
}
