package com.multiviewer.parser.conformance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConformanceRuleCatalogTest {
    // Hand-checked against the emitted checker IDs, including IDs formed dynamically.
    private val expectedIds = mapOf(
        "JPEG" to "soi frame dqt dht eoi trailing",
        "PNG" to "truncated crc ihdr idat iend trailing",
        "GIF" to "lsd blocks frames trailer trailing",
        "BMP" to "header size offset pixels",
        "WEBP" to "riff chunks image canvas",
        "HEIF" to "ftyp meta hdlr primary grid iloc",
        "TIFF" to "header data ifd loop",
        "WAVE" to "signature riff.size riff.trailing chunk.header chunk.bounds fmt.size fmt.present fmt.extensible data.bounds chunk.padding fmt.missing fmt.duplicate data.missing data.duplicate fmt.fields fmt.alignment data.alignment structure",
        "AIFF" to "signature formType form.size form.trailing chunk.header chunk.bounds comm.size comm.present comm.fields comm.name ssnd.size ssnd.offset ssnd.bounds chunk.padding comm.missing comm.duplicate ssnd.missing ssnd.duplicate comm.frames structure",
        "FLAC" to "frame.findingsTruncated signature metadata.header metadata.bounds streaminfo.size metadata.reserved metadata.last streaminfo.first streaminfo.missing streaminfo.unique frame.missing frame.resync frame.headerCrc frame.boundary frame.header frame.truncated frame.crc",
        "MP3" to "id3.header id3.size id3.bounds ape.bounds freeFormat frame.header frame.leading frame.gap frame.truncated frame.continuity frames xing.count vbri.count",
        "ADTS" to "id3.header id3.size id3.bounds frame.missing frame.truncated frame.header frame.gap sampleRate frame.length crc.unchecked frames",
        "OGG" to "page.bounds page.capture page.version page.flags page.crc streams.limit page.sequence stream.bos stream.afterEos page.missing stream.eos codec.header codec pages findings.truncated",
        "M4A" to "audioTrack sampleMapping table.limit findings.truncated structure stsz.bounds chunkTable.bounds chunkTable.count chunkOffset stsc.bounds stsc.fragmented stsc.mapping sampleExtent",
        "ASF" to "header signature object.bounds data.size data.bounds header.missing header.duplicate fileProperties.missing fileProperties.size fileSize streamProperties.missing audioStream streamProperties data.missing objects header.size header.count header.reserved header.childBounds streamProperties.size header.trailing",
    )

    @Test
    fun `catalog covers existing checker IDs without duplicates or parser warnings`() {
        for ((format, suffixes) in expectedIds) {
            val rules = rulesFor(format)
            val expected = suffixes.split(' ').map { "${format.lowercase()}.$it" }.toSet()
            assertEquals(expected, rules.map { it.id }.toSet(), format)
            assertEquals(expected.size, rules.size, "Duplicate IDs in $format")
            assertFalse(rules.any { it.id.startsWith("parser.") })
            assertTrue(rules.all { it.title.isNotBlank() && it.scope.isNotBlank() })
        }
    }

    @Test
    fun `AIFC shares AIFF checks and unknown formats have no inventory`() {
        assertEquals(rulesFor("AIFF"), rulesFor("AIFC"))
        assertTrue(rulesFor("UNKNOWN").isEmpty())
        assertTrue(rulesFor("MP4").isEmpty())
    }

    @Test
    fun `PNG rules name published source while other checks remain application rules`() {
        assertTrue(rulesFor("PNG").all {
            it.source == "W3C PNG Third Edition" && it.reference == "https://www.w3.org/TR/png-3/"
        })
        for (format in expectedIds.keys - "PNG") {
            assertTrue(rulesFor(format).all {
                it.source == "unwrapMedia structural check" && it.reference == null
            }, format)
        }
    }

    @Test
    fun `empty report has no pass verdict`() {
        assertEquals(ConformanceStatus.NOT_CHECKED, report().overall)
    }

    @Test
    fun `default report limitations disclose unsupported codec syntax`() {
        assertTrue(ConformanceReport("JPEG", "Structural checks", emptyList()).limitations.any {
            it.contains("Codec bitstream syntax")
        })
    }

    @Test
    fun `all skipped or inapplicable rules have no pass verdict`() {
        assertEquals(ConformanceStatus.NOT_CHECKED, report(ConformanceStatus.NOT_CHECKED).overall)
        assertEquals(ConformanceStatus.NOT_CHECKED, report(ConformanceStatus.NOT_APPLICABLE).overall)
    }

    @Test
    fun `failure outranks warnings and passing rules`() {
        assertEquals(ConformanceStatus.FAIL, report(ConformanceStatus.PASS, ConformanceStatus.WARN, ConformanceStatus.FAIL).overall)
    }

    @Test
    fun `warning outranks unchecked rules`() {
        assertEquals(ConformanceStatus.WARN, report(ConformanceStatus.WARN, ConformanceStatus.NOT_CHECKED).overall)
    }

    @Test
    fun `passing checks with an unchecked rule do not yield pass`() {
        assertEquals(ConformanceStatus.NOT_CHECKED, report(ConformanceStatus.PASS, ConformanceStatus.NOT_CHECKED).overall)
    }

    @Test
    fun `complete applicable checks yield pass`() {
        assertEquals(ConformanceStatus.PASS, report(ConformanceStatus.PASS, ConformanceStatus.PASS).overall)
        assertEquals(ConformanceStatus.PASS, report(ConformanceStatus.PASS, ConformanceStatus.NOT_APPLICABLE).overall)
    }

    private fun report(vararg statuses: ConformanceStatus) = ConformanceReport(
        format = "TEST", profile = "Structural checks",
        findings = statuses.mapIndexed { index, status ->
            ConformanceFinding(
                ConformanceRule("test.$index", "Test rule", "Test source", null, "Test scope"),
                status, emptyList(), emptyList(),
            )
        },
        limitations = listOf("Codec bitstream syntax is not checked"),
    )
}
