package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.idsWith
import com.multiviewer.parser.conformance.notPassing
import com.multiviewer.parser.conformance.png.SyntheticPng.acTL
import com.multiviewer.parser.conformance.png.SyntheticPng.check
import com.multiviewer.parser.conformance.png.SyntheticPng.chunk
import com.multiviewer.parser.conformance.png.SyntheticPng.fcTL
import com.multiviewer.parser.conformance.png.SyntheticPng.fdAT
import com.multiviewer.parser.conformance.png.SyntheticPng.idat
import com.multiviewer.parser.conformance.png.SyntheticPng.iend
import com.multiviewer.parser.conformance.png.SyntheticPng.ihdr
import com.multiviewer.parser.conformance.png.SyntheticPng.png
import com.multiviewer.parser.conformance.png.SyntheticPng.rgb
import com.multiviewer.parser.conformance.statusOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PngApngRulesTest {
    private fun failsOnly(bytes: ByteArray, vararg ids: String) {
        val r = check(bytes)
        assertNull(r.inspectionError)
        assertEquals(ids.toSet(), r.idsWith(FAIL, WARN), r.notPassing().toString())
    }

    @Test fun `APNG acTL, sequence numbers, frame regions and fdAT`() {
        fun apng(vararg c: ByteArray) = png(ihdr(), *c, iend())
        val ok = apng(acTL(2), fcTL(0), idat(), fcTL(1, w = 1, h = 1, x = 1, y = 1), fdAT(2))
        val r = check(ok)
        assertEquals(emptySet(), r.idsWith(FAIL, WARN, NOT_CHECKED), r.notPassing().toString())
        failsOnly(apng(acTL(0), fcTL(0), idat()), "png3.actl")
        failsOnly(apng(acTL(2), fcTL(0), idat(), fcTL(2), fdAT(3)), "png3.sequence")
        failsOnly(apng(acTL(1), fcTL(0, w = 1), idat()), "png3.fctl")                            // default image must use the IHDR size
        failsOnly(apng(acTL(2), fcTL(0), idat(), fcTL(1, dispose = 3), fdAT(2)), "png3.fctl")
        failsOnly(apng(acTL(2), fcTL(0), fdAT(1), idat(), fcTL(2)), "png3.fdat")                // fdAT before IDAT; last frame without fdAT
        // fdAT shorter than its sequence number: no frame_data, so the frame has no zlib header either
        failsOnly(apng(acTL(1), idat(), fcTL(0), chunk("fdAT", byteArrayOf(0, 0))), "png3.fdat", "zlib.header")
        assertEquals(NOT_APPLICABLE, check(SyntheticPng.rgb()).statusOf("png3.actl"))
        val noActl = check(apng(idat(), fcTL(0), fdAT(1)))
        assertEquals(NOT_CHECKED, noActl.statusOf("png3.actl"))
        assertEquals(emptySet(), noActl.idsWith(FAIL, WARN))
    }
}
