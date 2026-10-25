package com.multiviewer.parser.conformance.webp

import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.idsWith
import com.multiviewer.parser.conformance.notPassing
import com.multiviewer.parser.conformance.statusOf
import com.multiviewer.parser.conformance.webp.SyntheticWebp.A
import com.multiviewer.parser.conformance.webp.SyntheticWebp.L
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

class WebpBitstreamRulesTest {
    private fun failsOnly(bytes: ByteArray, vararg ids: String) {
        val r = check(bytes)
        assertNull(r.inspectionError)
        assertEquals(ids.toSet(), r.idsWith(FAIL, WARN), r.notPassing().toString())
    }

    @Test fun `ANIM, ANMF and ALPH`() {
        failsOnly(riff(vp8x(A), chunk("ANIM", ByteArray(4)), anmf(0, 0, 4, 4, 0, vp8())), "rfc9649.anim")
        failsOnly(riff(vp8x(A), anim(), anmf(0, 0, 4, 4, 0x04, vp8())), "rfc9649.anim")       // reserved bit
        failsOnly(riff(vp8x(A), anim(), anmf(0, 0, 4, 4, 0, vp8(), vp8())), "rfc9649.anim")   // two bitstreams
        failsOnly(riff(vp8x(A or L), anim(), anmf(0, 0, 4, 4, 0, vp8(), alph())), "rfc9649.anim") // ALPH after bitstream
        failsOnly(riff(vp8x(L), alph(header = 0x02), vp8()), "rfc9649.alph")                 // compression method 2
        failsOnly(riff(vp8x(L), alph(header = 0x00, data = ByteArray(15)), vp8()), "rfc9649.alph") // raw alpha must be 4x4 = 16 bytes
        assertEquals(PASS, check(riff(vp8x(L), alph(header = 0x00, data = ByteArray(16)), vp8())).statusOf("rfc9649.alph"))
        failsOnly(riff(vp8x(L), alph(), vp8l()), "rfc9649.alph.vp8l")
    }

    @Test fun `bitstream headers and canvas size`() {
        failsOnly(riff(vp8(tag = 1 or (2 shl 5))), "rfc6386.frame")                           // interframe
        failsOnly(riff(vp8(tag = (4 shl 1) or (2 shl 5))), "rfc6386.frame")                    // version 4
        failsOnly(riff(vp8(tag = 3 shl 5)), "rfc6386.frame")                                   // first_part_size 3 > 2
        failsOnly(riff(vp8l(version = 1)), "rfc9649.vp8l")
        failsOnly(riff(chunk("VP8L", byteArrayOf(0x2E, 0, 0, 0, 0, 0))), "rfc9649.vp8l")
        failsOnly(riff(vp8x(0, w = 5), vp8()), "rfc9649.canvas")
        failsOnly(riff(vp8x(0, w = 0x10000, h = 0x10000), vp8()), "rfc9649.vp8x", "rfc9649.canvas")
        failsOnly(riff(vp8x(A), anim(), anmf(0, 0, 4, 4, 0, vp8(2, 2))), "rfc9649.canvas")
    }

    @Test fun `truncated data leaves the bitstream rules NOT_CHECKED`() {
        val full = riff(vp8x(L), alph(), vp8())
        val r = check(full.copyOf(full.size - 4))
        assertEquals(NOT_CHECKED, r.statusOf("rfc6386.frame"))
        assertEquals(NOT_CHECKED, r.statusOf("rfc9649.canvas"))
    }
}
