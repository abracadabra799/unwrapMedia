package com.multiviewer.parser.conformance.webp

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

/** libwebp 1.6.0 tool output (cwebp default = default-settings fixture, img2webp, gif2webp) and byte-level variants. */
class WebpFixtureTest {
    private fun run(name: String): ConformanceReport = ByteReader.open(conformanceFixture(name)).use { WebpConformanceChecker.check(it) }

    @Test fun `libwebp output has no FAIL, WARN or NOT_CHECKED`() {
        listOf("cwebp-default.webp", "cwebp-lossless.webp", "cwebp-alpha.webp", "img2webp-anim.webp", "gif2webp-anim.webp").forEach { name ->
            val r = run(name)
            assertNull(r.inspectionError, name)
            assertEquals(emptySet(), r.idsWith(FAIL, WARN, NOT_CHECKED), "$name: ${r.notPassing()}")
            assertEquals(PASS, r.overall, name)
        }
    }

    @Test fun `each variant fails exactly its targeted rule`() {
        val expected = mapOf(
            "webp-riff-size.webp" to setOf("rfc9649.riff.size"),
            "webp-trailing.webp" to setOf("rfc9649.trailing"),
            "webp-pad.webp" to setOf("rfc9649.chunk.layout"),
            "webp-simple-extra.webp" to setOf("rfc9649.layout"),
            "webp-flags-icc.webp" to setOf("rfc9649.vp8x.flags"),
            "webp-flags-alpha.webp" to setOf("rfc9649.vp8x.flags"),
            "webp-vp8x-reserved.webp" to setOf("rfc9649.vp8x"),
            "webp-order.webp" to setOf("rfc9649.order"),
            "webp-alph-rsv.webp" to setOf("rfc9649.alph"),
            "webp-vp8l-version.webp" to setOf("rfc9649.vp8l"),
            "webp-vp8-start.webp" to setOf("rfc6386.frame"),
            "webp-canvas.webp" to setOf("rfc9649.canvas"),
            "webp-anmf-region.webp" to setOf("rfc9649.anim"),
            "webp-anmf-noflag.webp" to setOf("rfc9649.layout", "rfc9649.anmf.flag"),
            "webp-alph-vp8l.webp" to setOf("rfc9649.alph.vp8l"),
            "webp-iccp-dup.webp" to setOf("rfc9649.metadata.count"),
        )
        expected.forEach { (name, ids) ->
            val r = run(name)
            assertNull(r.inspectionError, name)
            assertEquals(ids, r.idsWith(FAIL, WARN), "$name: ${r.notPassing()}")
        }
        listOf("webp-trailing.webp", "webp-alph-vp8l.webp", "webp-iccp-dup.webp").forEach { assertEquals(WARN, run(it).overall, it) }
    }
}
