package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JpegJfifRulesTest {
    private fun failsOnly(bytes: ByteArray, vararg ids: String) = assertEquals(ids.toSet(), check(bytes).ids(FAIL, WARN), check(bytes).dump().toString())
    private fun jfxx(code: Int) = seg(0xE0, 0x4A, 0x46, 0x58, 0x58, 0x00, code)

    @Test fun `no JFIF APP0 makes every JFIF rule not applicable`() {
        val r = check(baseline(app = ByteArray(0)))
        listOf("jfif.position", "jfif.fields", "jfif.version", "jfif.frame", "jfif.extension", "jfif.app0").forEach { assertEquals(NOT_APPLICABLE, r.status(it), it) }
    }

    @Test fun `position, fields and version`() {
        failsOnly(baseline(app = seg(0xFE, 0x41) + jfifApp0()), "jfif.position")
        failsOnly(baseline(app = jfifApp0(hd = 0)), "jfif.fields")
        failsOnly(baseline(app = jfifApp0(units = 3)), "jfif.fields")
        // 1.00 / 1.01 (libjpeg-turbo default) = earlier JFIF editions → WARN; anything else ≠ 1.02 → FAIL.
        listOf(0, 1).forEach { minor ->
            val r = check(baseline(app = jfifApp0(minor = minor)))
            assertEquals(WARN, r.status("jfif.version"), r.dump().toString())
            assertEquals(emptySet(), r.ids(FAIL))
            assertTrue(r.findings.single { it.rule.id == "jfif.version" }.details.single().contains("earlier JFIF 1.0$minor edition"))
        }
        assertEquals(FAIL, check(baseline(app = jfifApp0(minor = 3))).status("jfif.version"))
        assertEquals(FAIL, check(baseline(app = jfifApp0(major = 2, minor = 1))).status("jfif.version"))
    }

    @Test fun `frame constraints`() {
        failsOnly(baseline(frame = sof(comps = listOf(intArrayOf(1, 1, 1, 0), intArrayOf(2, 1, 1, 0)))), "jfif.frame")   // Nf = 2
        failsOnly(baseline(frame = sof(comps = listOf(intArrayOf(7, 1, 1, 0))), scan = sos(listOf(intArrayOf(7, 0, 0))) + ecs()), "jfif.frame") // C1 != 1
    }

    @Test fun `extensions and application APP0s`() {
        assertEquals(com.multiviewer.parser.conformance.ConformanceStatus.PASS, check(baseline(app = jfifApp0() + jfxx(0x10))).status("jfif.extension"))
        failsOnly(baseline(app = jfifApp0() + jfxx(0x12)), "jfif.extension")
        failsOnly(baseline(app = jfifApp0() + seg(0xE1, 0x41, 0) + jfxx(0x10)), "jfif.extension")
        failsOnly(baseline(app = jfifApp0() + jfifApp0()), "jfif.app0")
        failsOnly(baseline(app = jfifApp0() + seg(0xE0, 0x41, 0x42)), "jfif.app0")    // no zero-terminated identifier
    }
}
