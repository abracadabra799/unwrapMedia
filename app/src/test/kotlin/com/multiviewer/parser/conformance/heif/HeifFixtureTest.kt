package com.multiviewer.parser.conformance.heif

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real tool output (ffmpeg libsvtav1 AVIF, macOS sips HEIC) and byte-level corruptions of them, made by
 * docs/validation/heif-conformance-fixtures/generate.sh (+ corrupt.py).
 */
class HeifFixtureTest {
    private fun fixture(name: String) = File(javaClass.getResource("/heif-conformance/$name")!!.toURI())
    private fun check(name: String) = ByteReader.open(fixture(name)).use { HeifConformanceChecker.check(it) }
    private fun ConformanceReport.ids(vararg s: ConformanceStatus) = findings.filter { it.status in s }.map { it.rule.id }.toSet()
    private fun ConformanceReport.dump() = findings.filter { it.status != ConformanceStatus.PASS }
        .map { "${it.status} ${it.rule.id} ${it.details}" }

    @Test
    fun `real tool output has no FAIL and no WARN`() {
        mapOf("still.avif" to "AVIF", "sips.heic" to "HEIF").forEach { (name, format) ->
            val r = check(name)
            assertEquals(format, r.format, name)
            assertNull(r.inspectionError, name)
            assertEquals(emptySet(), r.ids(FAIL, WARN), "$name: ${r.dump()}")
        }
    }

    @Test
    fun `each corrupted file fails exactly its targeted rule`() {
        // target rule, and the FAILs that are a direct consequence of the same edit (none so far).
        val expected = mapOf(
            "no-ispe.heic" to ("heif.props.ispe" to emptySet<String>()),
            "hidden-primary.heic" to ("heif.items.primary" to emptySet()),
            "ipma-index.heic" to ("heif.ipma.refs" to emptySet()),
            "iloc-past-eof.heic" to ("heif.iloc.extents" to emptySet()),
            "no-miaf.avif" to ("avif.ftyp.miaf" to emptySet()),
            "no-av1c.avif" to ("heif.props.codec_config" to emptySet()),
            "pasp-2-1.avif" to ("avif.props.pasp" to emptySet()),
        )
        expected.forEach { (name, e) ->
            val (target, consequences) = e
            val r = check(name)
            assertNull(r.inspectionError, name)
            assertEquals(if (name.endsWith(".avif")) "AVIF" else "HEIF", r.format, name)
            assertEquals(setOf(target) + consequences, r.ids(FAIL), "$name: ${r.dump()}")
            assertEquals(emptySet(), r.ids(WARN), "$name: ${r.dump()}")
            assertEquals(FAIL, r.overall, name)
        }
    }

    @Test
    fun `FAIL findings carry their spec citation`() {
        val heif = check("no-ispe.heic").findings.single { it.rule.id == "heif.props.ispe" }
        val heifCitation = heif.rule.citation!!
        assertEquals("https://www.iso.org/standard/89035.html", heifCitation.url)
        assertEquals(false, heifCitation.verified)
        assertNull(heifCitation.page)

        listOf("no-miaf.avif" to "avif.ftyp.miaf", "pasp-2-1.avif" to "avif.props.pasp").forEach { (name, id) ->
            val citation = check(name).findings.single { it.rule.id == id }.rule.citation!!
            assertTrue(citation.url.startsWith("https://aomediacodec.github.io/av1-avif/v1.2.0.html#"), "$id: ${citation.url}")
            assertEquals(true, citation.verified, id)
        }
        val codecConfig = check("no-av1c.avif").findings.single { it.rule.id == "heif.props.codec_config" }.rule.citation!!
        assertTrue(codecConfig.url.endsWith("#image-item"), codecConfig.toString())
        assertEquals(true, codecConfig.verified, codecConfig.toString())
    }
}
