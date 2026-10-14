package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.ImageIntegrityChecker
import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.parser.integrity.IntegrityCheckItem
import com.multiviewer.parser.integrity.jpegBytes
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ContentCheckPlanTest {
    @Test
    fun `every media type exposes conformance coverage including unsupported files`() {
        for (type in listOf(MediaType.IMAGE, MediaType.VIDEO, MediaType.AUDIO, MediaType.UNKNOWN, MediaType.RAW_PIXEL)) {
            assertTrue(contentTabs(type, motionDetected = false).any { it.name == "CONFORMANCE" }, "$type has no conformance tab")
        }
    }

    @Test
    fun tabsPerType() {
        for (m in listOf(false, true)) {
            assertEquals(
                listOf(ContentTab.STRUCTURE, ContentTab.CONFORMANCE, ContentTab.IMAGE_DECODE) + if (m) listOf(ContentTab.MOTION_PHOTO) else emptyList(),
                contentTabs(MediaType.IMAGE, m),
            )
            assertEquals(listOf(ContentTab.STRUCTURE, ContentTab.CONFORMANCE, ContentTab.VIDEO_DECODE, ContentTab.VIDEO_PACKETS, ContentTab.AUDIO), contentTabs(MediaType.VIDEO, m))
            assertEquals(listOf(ContentTab.STRUCTURE, ContentTab.CONFORMANCE, ContentTab.AUDIO), contentTabs(MediaType.AUDIO, m))
            for (t in listOf(MediaType.RAW_PIXEL, MediaType.UNKNOWN)) {
                assertEquals(listOf(ContentTab.STRUCTURE, ContentTab.CONFORMANCE), contentTabs(t, m))
            }
        }
    }

    @Test
    fun stepsPerType() {
        for (m in listOf(false, true)) {
            assertEquals(
                listOf(HeavyStep.IMAGE_DECODE) + if (m) listOf(HeavyStep.MOTION_PHOTO) else emptyList(),
                heavySteps(MediaType.IMAGE, m),
            )
            assertEquals(listOf(HeavyStep.VIDEO_INTEGRITY, HeavyStep.AUDIO_INTEGRITY), heavySteps(MediaType.VIDEO, m))
            assertEquals(listOf(HeavyStep.AUDIO_INTEGRITY), heavySteps(MediaType.AUDIO, m))
            for (t in listOf(MediaType.RAW_PIXEL, MediaType.UNKNOWN)) {
                assertEquals(emptyList(), heavySteps(t, m))
            }
        }
    }

    @Test
    fun nonImageWithWarning() {
        val root = BoxNode("root", 0, 0, 100, children = listOf(BoxNode("moov", 8, 8, 40, warnings = listOf("bad"))))
        val r = contentStructureReport(File("x.mp4"), root, MediaType.VIDEO)
        assertEquals("VIDEO", r.format)
        assertEquals(1, r.items.size)
        val i = r.items[0]
        assertEquals("parser.warning", i.id)
        assertEquals(CheckStatus.WARN, i.status)
        assertEquals(8L, i.offset)
        assertEquals(40L, i.length)
    }

    @Test
    fun nonImageNoWarnings() {
        val r = contentStructureReport(File("x.wav"), BoxNode("root", 0, 0, 10), MediaType.AUDIO)
        assertEquals(1, r.items.size)
        assertEquals("parser.warnings", r.items[0].id)
        assertEquals(CheckStatus.PASS, r.items[0].status)
    }

    @Test
    fun audioStructureUsesDedicatedReport() {
        val f = File.createTempFile("audio-structure-", ".wav").also {
            it.deleteOnExit()
            it.writeBytes("RIFF\u0004\u0000\u0000\u0000WAVE".toByteArray(Charsets.ISO_8859_1))
        }

        val report = contentAudioStructureReport(f, BoxNode("root", 0, 0, f.length()))

        assertEquals("WAVE", report.format)
        assertEquals(CheckStatus.FAIL, report.overall)
        val conformance = contentConformanceReport(MediaType.AUDIO, audioStructure = report)
        assertEquals("WAVE", conformance.format)
        assertEquals(ConformanceStatus.FAIL, conformance.overall)
        assertTrue(conformance.findings.any { it.rule.id == "wave.fmt.missing" && it.status == ConformanceStatus.FAIL })
        assertFalse(conformance.findings.any { it.rule.id.startsWith("parser.") })
    }

    @Test
    fun imageDelegatesToChecker() {
        val f = File.createTempFile("ccp-", ".jpg").also { it.deleteOnExit(); it.writeBytes(jpegBytes()) }
        val root = parseFile(f)
        assertEquals(ImageIntegrityChecker.check(f, root), contentStructureReport(f, root, MediaType.IMAGE))
    }

    @Test
    fun `images with a gain map get the gain map tab after motion photo`() {
        assertEquals(
            listOf(ContentTab.STRUCTURE, ContentTab.CONFORMANCE, ContentTab.IMAGE_DECODE, ContentTab.MOTION_PHOTO, ContentTab.GAINMAP),
            contentTabs(MediaType.IMAGE, motionDetected = true, gainmapDetected = true),
        )
        assertEquals(listOf(ContentTab.STRUCTURE, ContentTab.CONFORMANCE, ContentTab.IMAGE_DECODE), contentTabs(MediaType.IMAGE, motionDetected = false))
    }

    @Test
    fun `video and unsupported types cannot promote structure or parser observations into conformance`() {
        val observations = ImageStructureReport("JPEG", listOf(
            IntegrityCheckItem("jpeg.soi", "SOI", CheckStatus.PASS, "present", 0, 2),
            IntegrityCheckItem("parser.warning", "Parser", CheckStatus.WARN, "container warning", 4, 8),
        ))
        for (type in listOf(MediaType.VIDEO, MediaType.UNKNOWN, MediaType.RAW_PIXEL)) {
            val report = contentConformanceReport(type, structure = observations)
            assertEquals(ConformanceStatus.NOT_CHECKED, report.overall)
            assertTrue(report.findings.isEmpty())
            assertTrue(report.limitations.any { it.contains("container warning") })
        }
    }

    @Test
    fun `inspection failure and absent structure cannot produce conformance pass`() {
        val observed = ImageStructureReport("PNG", listOf(IntegrityCheckItem("png.crc", "CRC", CheckStatus.PASS, "matches")))
        val failed = contentConformanceReport(MediaType.IMAGE, structure = observed, inspectionError = "cancelled")
        assertEquals(ConformanceStatus.NOT_CHECKED, failed.overall)
        assertEquals("cancelled", failed.inspectionError)
        assertEquals(ConformanceStatus.NOT_CHECKED, contentConformanceReport(MediaType.IMAGE).overall)
        assertEquals(ConformanceStatus.NOT_CHECKED, contentConformanceReport(MediaType.AUDIO).overall)
    }

    @Test
    fun `AI receives deterministic conformance sources and byte evidence separately from decoder and parser`() {
        val file = File("/private/engineering/photo.png")
        val structure = ImageStructureReport("PNG", listOf(
            IntegrityCheckItem("png.crc", "CRC", CheckStatus.FAIL, "CRC mismatch ${file.absolutePath}", 20, 4),
            IntegrityCheckItem("parser.warning", "Parser", CheckStatus.WARN, "parse warning", 8, 4),
        ))
        val report = contentConformanceReport(MediaType.IMAGE, structure = structure)
        val snapshot = ContentCheckSnapshot(structure, null, false, null, null, null, MediaType.IMAGE, conformance = report)
        val prompt = contentCheckPromptSection(snapshot, file)
        val section = prompt.substringAfter("### [규격 적합성 — 구현된 검사와 범위]").substringBefore("### [검증된 사실]")
        assertTrue(section.contains("[FAIL] png.crc"), prompt)
        assertTrue(section.contains("W3C PNG Third Edition"), prompt)
        assertTrue(section.contains("https://www.w3.org/TR/png-3/"), prompt)
        assertTrue(section.contains("0x14"), prompt)
        assertTrue(section.contains("4B"), prompt)
        assertTrue(section.contains("NOT_CHECKED"), prompt)
        assertTrue(section.contains("파서 근거"), prompt)
        assertFalse(section.contains("[WARN] parser.warning"), prompt)
        assertFalse(section.contains("이미지 디코딩"), prompt)
        assertFalse(prompt.contains(file.absolutePath), prompt)
        assertTrue(prompt.contains("이미지 디코딩: 미실행"), prompt)
    }
}
