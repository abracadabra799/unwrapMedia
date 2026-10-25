package com.multiviewer.parser.conformance.gif

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.conformanceFixture
import com.multiviewer.parser.conformance.idsWith
import com.multiviewer.parser.conformance.notPassing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ffmpeg 8.1.2 default GIF output, macOS sips (ImageIO) output and byte-level variants. */
class GifFixtureTest {
    private fun run(name: String): ConformanceReport = ByteReader.open(conformanceFixture(name)).use { GifConformanceChecker.check(it) }

    @Test fun `ffmpeg default still and animated GIFs have no FAIL, WARN or NOT_CHECKED`() {
        listOf("ffmpeg-default.gif", "ffmpeg-anim.gif").forEach { name ->
            val r = run(name)
            assertNull(r.inspectionError, name)
            assertEquals(emptySet(), r.idsWith(FAIL, WARN, NOT_CHECKED), "$name: ${r.notPassing()}")
            assertEquals(PASS, r.overall, name)
        }
    }

    @Test fun `macOS sips labels a GIF with a Graphic Control Extension as 87a`() {
        val r = run("sips.gif")
        assertEquals(setOf("gif89a.version"), r.idsWith(FAIL, WARN), r.notPassing().toString())
        assertTrue(r.findings.single { it.rule.id == "gif89a.version" }.details.single().contains("Graphic Control"))
    }

    @Test fun `each variant fails exactly its targeted rule`() {
        val expected = mapOf(
            "gif-89a-plain.gif" to setOf("gif89a.version.earliest"),
            "gif-gce-size.gif" to setOf("gif89a.gce"),
            "gif-two-gce.gif" to setOf("gif89a.gce"),
            "gif-bounds.gif" to setOf("gif89a.image.bounds"),
            "gif-lzw.gif" to setOf("gif89a.lzw"),
            "gif-clear.gif" to setOf("gif89a.lzw.clear"),
            "gif-no-trailer.gif" to setOf("gif89a.trailer"),
            "gif-trailing.gif" to setOf("gif89a.trailing"),
            "gif-reserved.gif" to setOf("gif89a.reserved"),
            "gif-lct-size.gif" to setOf("gif89a.lct.size"),
            "gif-bgindex.gif" to setOf("gif89a.lsd.background"),
            "gif-app-size.gif" to setOf("gif89a.extension"),
            "gif-badblock.gif" to setOf("gif89a.blocks", "gif89a.trailer"),
        )
        expected.forEach { (name, ids) ->
            val r = run(name)
            assertNull(r.inspectionError, name)
            assertEquals(ids, r.idsWith(FAIL, WARN), "$name: ${r.notPassing()}")
        }
    }
}
