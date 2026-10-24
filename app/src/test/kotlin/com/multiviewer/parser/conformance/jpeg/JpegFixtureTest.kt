package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real tool output (ffmpeg, libjpeg-turbo cjpeg, macOS sips), the Exif/MPF fixture written by build.py on top of
 * ffmpeg image data, and byte-level variants — docs/validation/jpeg-conformance-fixtures/generate.sh.
 */
class JpegFixtureTest {
    private fun fixture(name: String) = File(javaClass.getResource("/jpeg-conformance/$name")!!.toURI())
    private fun run(name: String): ConformanceReport = ByteReader.open(fixture(name)).use { JpegConformanceChecker.check(it) }

    @Test fun `default ffmpeg output and the spec-complete Exif+MPF file have no FAIL, WARN or NOT_CHECKED`() {
        listOf("ffmpeg-default.jpg", "exif-mpf.jpg").forEach { name ->
            val r = run(name)
            assertNull(r.inspectionError, name)
            assertEquals(emptySet(), r.ids(FAIL, WARN, NOT_CHECKED), "$name: ${r.dump()}")
            assertEquals(PASS, r.overall, name)
        }
        val r = run("exif-mpf.jpg")
        assertTrue(r.findings.single { it.rule.id == "t81.trailing" }.details.any { it.contains("MPF") })
    }

    @Test fun `libjpeg-turbo writes JFIF 1_01, which only warns on jfif version`() {
        listOf("cjpeg-default.jpg", "cjpeg-progressive.jpg", "cjpeg-restart.jpg").forEach { name ->
            val r = run(name)
            assertEquals(emptySet(), r.ids(FAIL), "$name: ${r.dump()}")
            assertEquals(setOf("jfif.version"), r.ids(WARN), "$name: ${r.dump()}")
            assertEquals(WARN, r.overall, name)
        }
        assertTrue(run("cjpeg-restart.jpg").findings.single { it.rule.id == "t81.restart" }.details.single().contains("RST 2"))
    }

    @Test fun `macOS sips (ImageIO) output fails Exif position and mandatory tags`() {
        val r = run("sips.jpg")
        assertEquals(setOf("exif.app1.position", "exif.tags.mandatory"), r.ids(FAIL), r.dump().toString())
        assertEquals(setOf("jfif.version", "exif.tags.recommended"), r.ids(WARN))
        val rec = r.findings.single { it.rule.id == "exif.tags.recommended" }
        assertEquals(1, rec.details.size, rec.details.toString())
        assertTrue(rec.details.single().contains("0th IFD ImageDescription") && rec.details.single().contains("Exif IFD CompositeImage"), rec.details.toString())
    }

    @Test fun `each variant fails exactly its targeted rule`() {
        val cjpeg = setOf("jfif.version")
        val expected = mapOf(
            "no-eoi.jpg" to setOf("t81.eoi"),
            "trailing-garbage.jpg" to setOf("t81.trailing"),
            "no-dqt.jpg" to setOf("t81.tables"),
            "sof-h0.jpg" to setOf("t81.frame.header"),
            "scan-se.jpg" to setOf("t81.scan.header"),
            "jfif-not-first.jpg" to setOf("jfif.position"),
            "jfif-density0.jpg" to setOf("jfif.fields"),
            "dri-zero.jpg" to setOf("t81.restart") + cjpeg,
            "rst-order.jpg" to setOf("t81.restart") + cjpeg,
            "exif-unsorted.jpg" to setOf("exif.ifd.order"),
            "exif-no-colorspace.jpg" to setOf("exif.tags.mandatory"),
            "exif-imagewidth.jpg" to setOf("exif.tags.not_allowed"),
            "exif-ifd-loop.jpg" to setOf("exif.ifd.structure"),
            "exif-ascii-nonull.jpg" to setOf("exif.tag.ascii"),
            "exif-thumb-rst.jpg" to setOf("exif.thumbnail"),
            "mpf-type0.jpg" to setOf("mpf.type"),
            "mpf-size.jpg" to setOf("mpf.images"),
        )
        expected.forEach { (name, ids) ->
            val r = run(name)
            assertNull(r.inspectionError, name)
            assertEquals(ids, r.ids(FAIL, WARN), "$name: ${r.dump()}")
        }
        assertEquals(WARN, run("trailing-garbage.jpg").overall)
    }

    @Test fun `FAIL findings carry a verified citation with the physical PDF page`() {
        val f = run("mpf-type0.jpg").findings.single { it.rule.id == "mpf.type" }
        val c = f.rule.citation!!
        assertTrue(c.verified)
        assertEquals(19, c.page)
        assertTrue(c.url.endsWith("#page=19"), c.url)
    }
}
