package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EsdsBoxDecoderTest {
    private fun decode(body: ByteArray): BoxNode {
        registerAllDecoders()
        return byteReaderOf(body).use {
            BoxRegistry.decoderFor("esds").decode(it, "esds", 0, 0, body.size.toLong(), emptyList())
        }
    }

    @Test fun `default AAC fixture exposes descriptor and audio configuration fields`() {
        registerAllDecoders()
        ByteReader.open(File("src/test/resources/detail-parsing/default-avc-aac.mp4")).use { reader ->
            val node = BoxRegistry.decoderFor("esds").decode(reader, "esds", 1097, 8, 54, emptyList())
            fun value(name: String) = node.fields.firstOrNull { it.name == name }?.value
            assertEquals("2", value("ES_ID"))
            assertEquals("64", value("objectTypeIndication"))
            assertEquals("5", value("streamType"))
            assertEquals("72018", value("avgBitrate"))
            assertEquals("118856e500", value("decoderSpecificInfo"))
            assertEquals("2", value("audioObjectType"))
            assertEquals("48000", value("sampleRate"))
            assertEquals("1", value("channelConfiguration"))
            assertTrue(node.warnings.isEmpty(), node.warnings.toString())
            assertTrue(node.fields.all { it.offset >= 1105 && it.offset + it.length <= 1151 })
        }
    }

    @Test fun `optional ES fields and non AAC decoder configuration are exposed without AAC interpretation`() {
        // ES flags=e3: dependency, URL, OCR and priority 3; MPEG-4 visual OTI=20.
        val node = decode(hex("00000000 031d 0001e3 0002 03616263 0003 040d 20110000100000010000000080 060102"))
        fun value(name: String) = node.fields.firstOrNull { it.name == name }?.value
        assertEquals("2", value("dependsOn_ES_ID"))
        assertEquals("abc", value("URLstring"))
        assertEquals("3", value("OCR_ES_Id"))
        assertEquals("3", value("streamPriority"))
        assertEquals("32", value("objectTypeIndication"))
        assertTrue(node.fields.none { it.name == "audioObjectType" })
        assertTrue(node.warnings.isEmpty(), node.warnings.toString())
    }

    @Test fun `malformed and truncated descriptors warn instead of reading past bounds`() {
        for (text in listOf("", "000000", "00000000", "00000000 0380808080", "00000000 038080808100", "00000000 030300", "00000000 03030001e0")) {
            assertTrue(decode(hex(text)).warnings.isNotEmpty(), text)
        }
        val node = decode(hex("01000000 0303000100"))
        assertTrue(node.warnings.isNotEmpty())
        assertTrue(node.fields.none { it.name == "ES_ID" })
    }

    @Test fun `unknown descriptor payload is available as raw bytes`() {
        val node = decode(hex("00000000 7f03010203"))
        assertTrue(node.fields.any { it.value == "010203" })
        assertTrue(node.fields.any { it.name.endsWith("status") && it.value.contains("Unsupported") })
        assertTrue(node.warnings.isEmpty())
    }
    @Test fun `valid AAC with unknown additional descriptor retains conformance results`() = kotlinx.coroutines.runBlocking {
        val original = File("src/test/resources/detail-parsing/default-avc-aac.mp4")
        val root = parseFile(original)
        val baseline = com.multiviewer.parser.conformance.analyzeAacBitstream(original, root) { _, _ -> }
        val esds = requireNotNull(findFirst(root) { it.type == "esds" })
        val slTag = esds.fields.first { it.name.endsWith(".tag") && it.value == "0x06" }
        val bytes = original.readBytes().also { it[slTag.offset.toInt()] = 0x7f }
        val file = kotlin.io.path.createTempFile(suffix = ".m4a").toFile()
        try {
            file.writeBytes(bytes)
            val report = com.multiviewer.parser.conformance.analyzeAacBitstream(file, parseFile(file)) { _, _ -> }
            assertEquals(baseline.complete, report.complete, report.limitations.toString())
            assertEquals(baseline.checkedSamples, report.checkedSamples)
            assertTrue(report.findings.any { it.rule.id == "aac.config.asc" &&
                it.status == com.multiviewer.parser.conformance.ConformanceStatus.PASS })
        } finally { file.delete() }
    }

}
