package com.multiviewer.parser.conformance.webp

import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.idsWith
import com.multiviewer.parser.conformance.notPassing
import com.multiviewer.parser.conformance.statusOf
import com.multiviewer.parser.conformance.webp.SyntheticWebp.A
import com.multiviewer.parser.conformance.webp.SyntheticWebp.E
import com.multiviewer.parser.conformance.webp.SyntheticWebp.L
import com.multiviewer.parser.conformance.webp.SyntheticWebp.X
import com.multiviewer.parser.conformance.webp.SyntheticWebp.alph
import com.multiviewer.parser.conformance.webp.SyntheticWebp.anim
import com.multiviewer.parser.conformance.webp.SyntheticWebp.anmf
import com.multiviewer.parser.conformance.webp.SyntheticWebp.check
import com.multiviewer.parser.conformance.webp.SyntheticWebp.chunk
import com.multiviewer.parser.conformance.webp.SyntheticWebp.riff
import com.multiviewer.parser.conformance.webp.SyntheticWebp.vp8
import com.multiviewer.parser.conformance.webp.SyntheticWebp.vp8l
import com.multiviewer.parser.conformance.webp.SyntheticWebp.vp8x
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebpContainerRulesTest {
    private fun failsOnly(bytes: ByteArray, vararg ids: String) {
        val r = check(bytes)
        assertNull(r.inspectionError)
        assertEquals(ids.toSet(), r.idsWith(FAIL, WARN), r.notPassing().toString())
    }

    @Test fun `simple lossy, simple lossless, extended still and animated files pass`() {
        listOf(riff(vp8()), riff(vp8l()), riff(vp8x(L), alph(), vp8()),
            riff(vp8x(E or X), vp8l(), chunk("EXIF", ByteArray(8)), chunk("XMP ", ByteArray(3))),
            riff(vp8x(A or L), anim(), anmf(0, 0, 4, 4, 0, vp8l()), anmf(2, 2, 2, 2, 0, alph(data = ByteArray(2)), vp8(2, 2)))).forEach { b ->
            val r = check(b)
            assertEquals(emptySet(), r.idsWith(FAIL, WARN, NOT_CHECKED), r.notPassing().toString())
            assertEquals(PASS, r.overall)
        }
        assertEquals(NOT_APPLICABLE, check(riff(vp8())).statusOf("rfc9649.vp8x"))
    }

    @Test fun `RIFF size, chunk extent and padding`() {
        // File Size 2 too small: the chunk runs past it, and its last 2 bytes are "after the RIFF data"
        failsOnly(riff(vp8(), sizeDelta = -2), "rfc9649.riff.size", "rfc9649.chunk.layout", "rfc9649.trailing")
        failsOnly(riff(vp8(), sizeDelta = 1), "rfc9649.riff.size")                          // odd and past EOF
        // ALPH payload 3 bytes (odd): its pad byte at 12 (header) + 18 (VP8X) + 8 + 3 must be 0
        failsOnly(riff(vp8x(L), alph(data = ByteArray(2)), vp8()).also { it[12 + 18 + 8 + 3] = 7 }, "rfc9649.chunk.layout")
        val trailing = check(riff(vp8()) + ByteArray(3))
        assertEquals(setOf("rfc9649.trailing"), trailing.idsWith(FAIL, WARN))
        assertEquals(WARN, trailing.overall)
    }

    @Test fun `layout, order and VP8X fields`() {
        failsOnly(riff(chunk("ICCP", ByteArray(2)), vp8()), "rfc9649.layout")
        failsOnly(riff(vp8x(0), vp8(), vp8()), "rfc9649.layout")
        failsOnly(riff(vp8x(A), anim(), anmf(0, 0, 4, 4, 0, vp8()), vp8()), "rfc9649.layout")
        failsOnly(riff(vp8x(SyntheticWebp.I), vp8(), chunk("ICCP", ByteArray(2))), "rfc9649.order")
        failsOnly(riff(vp8x(A), anmf(0, 0, 4, 4, 0, vp8()), anim()), "rfc9649.order")
        failsOnly(riff(vp8x(0x40), vp8()), "rfc9649.vp8x")                                  // Rsv bit
        failsOnly(riff(vp8x(0, reserved = 1), vp8()), "rfc9649.vp8x")
    }

    @Test fun `VP8X flags must match the chunks`() {
        failsOnly(riff(vp8x(E), vp8()), "rfc9649.vp8x.flags")
        failsOnly(riff(vp8x(0), vp8(), chunk("XMP ", ByteArray(2))), "rfc9649.vp8x.flags")
        failsOnly(riff(vp8x(L), vp8()), "rfc9649.vp8x.flags")                               // alpha flag, no transparency source
        failsOnly(riff(vp8x(A), anmf(0, 0, 4, 4, 0, vp8())), "rfc9649.vp8x.flags")           // animation flag without ANIM
        assertEquals(PASS, check(riff(vp8x(L), vp8l())).statusOf("rfc9649.vp8x.flags"))     // VP8L may carry alpha
    }

    @Test fun `truncated data never reports PASS for rules that need every chunk`() {
        val full = riff(vp8x(L), alph(), vp8())
        val r = check(full.copyOf(full.size - 4))
        assertEquals(setOf("rfc9649.riff.size", "rfc9649.chunk.layout"), r.idsWith(FAIL, WARN), r.notPassing().toString())
        assertEquals(NOT_CHECKED, r.statusOf("rfc9649.vp8x.flags"))
    }
}
