package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import kotlin.test.Test
import kotlin.test.assertEquals

class JpegT81RulesTest {
    private fun failsOnly(bytes: ByteArray, vararg ids: String) {
        val r = check(bytes)
        assertEquals(ids.toSet(), r.ids(FAIL, WARN), r.dump().toString())
    }

    @Test fun `minimal baseline JFIF image passes every rule`() {
        val r = check(baseline())
        assertEquals(emptySet(), r.ids(FAIL, WARN, NOT_CHECKED), r.dump().toString())
        assertEquals("JPEG", r.format)
    }

    @Test fun `bytes that are not a marker fail marker, and the cut-off stream fails eoi`() {
        val r = check(SOI + jfifApp0() + byteArrayOf(0x12, 0x34) + dqt())
        assertEquals(FAIL, r.status("t81.marker"))
        assertEquals(FAIL, r.status("t81.eoi"))
        assertEquals(NOT_CHECKED, r.status("t81.frame.structure"))
        val len = check(SOI + jfifApp0() + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x00))
        assertEquals(FAIL, len.status("t81.segment.length"))
    }

    @Test fun `file not starting with SOI fails soi`() {
        val r = check(byteArrayOf(0xFF.toByte(), 0xE0.toByte()) + baseline().copyOfRange(4, baseline().size))
        assertEquals(FAIL, r.status("t81.soi"))
    }

    @Test fun `reserved marker warns`() = failsOnly(baseline(tables = seg(0xF3, 0) + dqt() + dht(0, 0) + dht(1, 0)), "t81.marker.reserved")

    @Test fun `frame header fields`() {
        failsOnly(baseline(app = ByteArray(0), frame = sof(p = 12)), "t81.frame.header")                  // baseline P=8 only (no JFIF)
        failsOnly(baseline(frame = sof(comps = listOf(intArrayOf(1, 5, 1, 0)))), "t81.frame.header")      // H 1-4
        failsOnly(baseline(frame = sof(x = 0)), "t81.frame.header")                                      // X >= 1
    }

    @Test fun `scan header fields`() {
        failsOnly(baseline(scan = sos(se = 62) + ecs()), "t81.scan.header")
        failsOnly(baseline(scan = sos(listOf(intArrayOf(9, 0, 0))) + ecs()), "t81.scan.header")          // Cs not in frame
        failsOnly(baseline(tables = dqt() + dht(0, 0) + dht(1, 0) + dht(0, 2) + dht(1, 2), scan = sos(listOf(intArrayOf(1, 2, 2))) + ecs()),
            "t81.scan.header", "t81.dht")                                                                // baseline Td/Ta, Th 0-1
    }

    @Test fun `frame structure`() {
        failsOnly(baseline(frame = sof() + sof()), "t81.frame.structure")
        failsOnly(baseline(frame = sof(y = 0)), "t81.frame.structure")                                   // Y=0 without DNL
        assertEquals(NOT_CHECKED, check(baseline(frame = sof(0xC5))).status("t81.frame.structure"))       // differential → hierarchical
    }

    @Test fun `table definitions and references`() {
        failsOnly(baseline(tables = dht(0, 0) + dht(1, 0)), "t81.tables")                                // no DQT
        failsOnly(baseline(tables = dqt() + dht(0, 0)), "t81.tables")                                    // no AC table
        failsOnly(baseline(tables = dqt(tq = 4) + dqt() + dht(0, 0) + dht(1, 0)), "t81.dqt")
        failsOnly(baseline(tables = dqt(pq = 1) + dht(0, 0) + dht(1, 0)), "t81.dqt")                     // Pq=1 with P=8
        failsOnly(baseline(tables = dqt() + dht(2, 0) + dht(0, 0) + dht(1, 0)), "t81.dht")
    }

    @Test fun `restart interval and markers`() {
        // 16x16, one component 1x1 → 4 MCUs. Ri=1 → 3 RSTs.
        val ok = sos() + ecs() + rst(0) + ecs() + rst(1) + ecs() + rst(2) + ecs()
        assertEquals(PASS, check(baseline(tables = dqt() + dht(0, 0) + dht(1, 0) + dri(1), scan = ok)).status("t81.restart"))
        failsOnly(baseline(tables = dqt() + dht(0, 0) + dht(1, 0) + dri(1), scan = sos() + ecs() + rst(0) + ecs()), "t81.restart") // count
        failsOnly(baseline(scan = ok), "t81.restart")                                                    // RST while Ri=0
        failsOnly(baseline(tables = dqt() + dht(0, 0) + dht(1, 0) + dri(1),
            scan = sos() + ecs() + rst(0) + ecs() + rst(2) + ecs() + rst(3) + ecs()), "t81.restart")    // order
        failsOnly(baseline(tables = dqt() + dht(0, 0) + dht(1, 0) + seg(0xDD, 0, 1, 0)), "t81.restart") // Lr != 4
    }

    @Test fun `MCU count follows interleaving`() {
        val f = JpegFrame(JpegSegment(0xC0, 0, 17), 8, 48, 64, 3, listOf(FrameComponent(1, 2, 2, 0), FrameComponent(2, 1, 1, 1), FrameComponent(3, 1, 1, 1)))
        fun s(vararg ids: Int) = JpegScanHeader(JpegSegment(0xDA, 0, 12), ids.size, ids.map { ScanComponent(it, 0, 0) }, 0, 63, 0, 0, true)
        assertEquals(12L, JpegT81Rules.mcuCount(f, s(1, 2, 3), 48))   // 4 x 3 MCUs of 16x16
        assertEquals(48L, JpegT81Rules.mcuCount(f, s(1), 48))         // 8 x 6 luma blocks
        assertEquals(12L, JpegT81Rules.mcuCount(f, s(2), 48))         // 4 x 3 chroma blocks
    }

    @Test fun `missing EOI fails and trailing data is judged only after EOI`() {
        failsOnly(baseline(tail = ByteArray(0)), "t81.eoi")
        assertEquals(NOT_CHECKED, check(baseline(tail = ByteArray(0))).status("t81.trailing"))
        failsOnly(baseline(tail = EOI + ByteArray(8)), "t81.trailing")
    }
}
