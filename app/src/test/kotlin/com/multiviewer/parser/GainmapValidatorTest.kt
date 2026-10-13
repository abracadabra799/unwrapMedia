package com.multiviewer.parser

import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.IntegrityCheckItem
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GainmapValidatorTest {
    private val jpeg = File("src/test/resources/c2pa/s26-camera.jpg")
    private val heic = File("src/test/resources/c2pa/s26-motion-photo.heic")

    private fun validate(file: File, baseW: Int? = 4000, baseH: Int? = 3000): List<IntegrityCheckItem> {
        val root = parseFile(file)
        val info = GainmapParser.findGainmapInfo(file, root)!!
        return GainmapValidator.validate(file, root, info, baseW, baseH)
    }

    private fun List<IntegrityCheckItem>.status(id: String) = single { it.id == id }.status

    private fun tampered(src: File, name: String, edit: (ByteArray) -> Unit): File {
        val dir = Files.createTempDirectory("gainmap-validator").toFile().apply { deleteOnExit() }
        return File(dir, name).apply { writeBytes(src.readBytes().also(edit)); deleteOnExit() }
    }

    private fun ByteArray.replaceAscii(old: String, new: String) {
        require(old.length == new.length)
        val o = old.toByteArray(); val n = new.toByteArray()
        val at = (0..size - o.size).first { i -> o.indices.all { this[i + it] == o[it] } }
        n.copyInto(this, at)
    }

    @Test
    fun `S26 JPEG passes every check`() {
        val items = validate(jpeg)
        assertTrue(items.none { it.status == CheckStatus.FAIL || it.status == CheckStatus.WARN }, items.joinToString("\n"))
        assertEquals(CheckStatus.PASS, items.status("gainmap.xmp_iso"))
        assertEquals(CheckStatus.PASS, items.status("gainmap.jpeg.gcontainer_length"))
        assertEquals(CheckStatus.PASS, items.status("gainmap.jpeg.iso_primary"))
        assertEquals(CheckStatus.PASS, items.status("gainmap.aspect"))
    }

    @Test
    fun `S26 HEIC passes every check`() {
        val items = validate(heic)
        assertTrue(items.none { it.status == CheckStatus.FAIL || it.status == CheckStatus.WARN }, items.joinToString("\n"))
        assertEquals(CheckStatus.PASS, items.status("gainmap.heif.tmap_brand"))
        assertEquals(CheckStatus.PASS, items.status("gainmap.heif.tmap_inputs"))
        assertEquals(CheckStatus.PASS, items.status("gainmap.heif.altr"))
    }

    @Test
    fun `XMP and ISO disagreeing is a warning naming the field`() {
        // GainMapMax numerator in the gain map image's ISO payload: 2.3 -> 2.5.
        val f = tampered(jpeg, "iso-max.jpg") { b ->
            val payload = 4014592 + 4 + 28
            byteArrayOf(0x00, 0xA0.toByte(), 0x00, 0x00).copyInto(b, payload + 5 + 8 + 8 + 8)
        }
        val item = validate(f).single { it.id == "gainmap.xmp_iso" }
        assertEquals(CheckStatus.WARN, item.status)
        assertTrue("GainMapMax" in item.detail, item.detail)
    }

    @Test
    fun `GContainer length that disagrees with the real gain map is a failure`() {
        val f = tampered(jpeg, "gcontainer.jpg") { it.replaceAscii("Item:Length=\"228923\"", "Item:Length=\"228924\"") }
        assertEquals(CheckStatus.FAIL, validate(f).status("gainmap.jpeg.gcontainer_length"))
    }

    @Test
    fun `HEIC without the tmap brand is a warning`() {
        val f = tampered(heic, "nobrand.heic") { b -> "mif2".toByteArray().copyInto(b, 24) }
        assertEquals(CheckStatus.WARN, validate(f).status("gainmap.heif.tmap_brand"))
    }

    @Test
    fun `aspect ratio mismatch with the base image is a warning`() {
        assertEquals(CheckStatus.WARN, validate(jpeg, baseW = 4000, baseH = 2250).status("gainmap.aspect"))
        assertEquals(CheckStatus.SKIP, validate(jpeg, baseW = null, baseH = null).status("gainmap.aspect"))
    }

    @Test
    fun `a gain map image without any parameters never passes`() {
        val info = GainmapInfo(hasGainmap = true, hasGainmapImage = true, formatType = GainmapFormatType.GENERIC_GAINMAP, rawXmp = null)
        val items = GainmapValidator.validate(jpeg, parseFile(jpeg), info, null, null)
        assertEquals(CheckStatus.FAIL, items.status("gainmap.metadata"))
        assertTrue(items.none { it.status == CheckStatus.PASS && it.id.startsWith("gainmap.params") }, items.joinToString("\n"))
    }

    @Test
    fun `out-of-range parameters fail`() {
        val bad = GainmapParsedParameters(gainMapMin = 2.0, gainMapMax = 1.0, gamma = 0.0, hdrCapacityMin = 2.0, hdrCapacityMax = 1.0)
        val info = GainmapInfo(hasGainmap = true, formatType = GainmapFormatType.GENERIC_GAINMAP, rawXmp = null, parameters = bad)
        val item = GainmapValidator.validate(jpeg, parseFile(jpeg), info, null, null).single { it.id == "gainmap.params.range" }
        assertEquals(CheckStatus.FAIL, item.status)
        assertTrue("gamma" in item.detail.lowercase(), item.detail)
    }
}
