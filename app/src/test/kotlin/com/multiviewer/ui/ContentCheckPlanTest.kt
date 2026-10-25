package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.MediaCategory
import com.multiviewer.parser.MediaSummary
import com.multiviewer.parser.SummaryField
import com.multiviewer.parser.SummarySection
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.ImageIntegrityChecker
import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.parser.integrity.IntegrityCheckItem
import com.multiviewer.parser.integrity.jpegBytes
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ContentCheckPlanTest {
    @Test
    fun stageDescriptionsExplainTheActualWorkInBothLanguages() {
        assertEquals("컨테이너 포맷 규격(ISO-BMFF, EXIF 등) 필수 박스 및 구조 제약 조건을 검증합니다.", checkStageDescription(CheckStage.CONTAINER_CONFORMANCE, true))
        assertEquals("Validates container format specification requirements (ISO-BMFF, EXIF, etc.).", checkStageDescription(CheckStage.CONTAINER_CONFORMANCE, false))
        assertEquals("박스·청크 배치, 오프셋 무결성 및 파서 경고를 검사합니다.", checkStageDescription(CheckStage.STRUCTURE, true))
        assertEquals("Checks box and chunk layout, offset integrity, and parser warnings.", checkStageDescription(CheckStage.STRUCTURE, false))
        assertEquals("코덱 구문, 값 범위, NAL 헤더 및 파라미터 간 참조를 검사합니다.", checkStageDescription(CheckStage.CODEC, true))
        assertEquals("Checks codec syntax, value ranges, NAL headers, and parameter references.", checkStageDescription(CheckStage.CODEC, false))
        assertEquals("압축 데이터를 실제로 디코딩하고 오류를 확인합니다.", checkStageDescription(CheckStage.VISUAL_DECODE, true))
        assertEquals("Decodes compressed data and checks for errors.", checkStageDescription(CheckStage.VISUAL_DECODE, false))
        assertEquals("오디오 트랙을 디코딩하고 오류를 확인합니다.", checkStageDescription(CheckStage.AUDIO_DECODE, true))
        assertEquals("Decodes audio tracks and checks for errors.", checkStageDescription(CheckStage.AUDIO_DECODE, false))
        assertEquals("정지 이미지와 포함된 영상의 연결·시점을 확인합니다.", checkStageDescription(CheckStage.MOTION_PHOTO, true))
        assertEquals("Checks the link and timing between the still image and embedded video.", checkStageDescription(CheckStage.MOTION_PHOTO, false))
        assertEquals("게인맵 데이터와 메타데이터의 구조·값을 확인합니다.", checkStageDescription(CheckStage.GAINMAP, true))
        assertEquals("Checks gain-map data and metadata structure and values.", checkStageDescription(CheckStage.GAINMAP, false))
    }

    @Test
    fun videoStageOrderAndUnsupportedCodecAreExplicit() {
        val root = BoxNode("root", 0, 0, 100)
        val rows = contentCheckStages(MediaType.VIDEO, root, false, false, true, false)
        assertEquals(listOf(CheckStage.CONTAINER_CONFORMANCE, CheckStage.STRUCTURE, CheckStage.CODEC, CheckStage.VISUAL_DECODE, CheckStage.AUDIO_DECODE), rows.map { it.stage })
        assertEquals(CheckAvailability.UNSUPPORTED, rows[2].availability)
        assertEquals(CheckAvailability.AVAILABLE, rows[4].availability)
        assertTrue(rows[2].reasonKo?.isNotBlank() == true)
    }

    @Test
    fun audioOnlyMp4DoesNotOfferVisualDecode() {
        // An audio-only .mp4 opens as VIDEO by extension; decoding a video stream it lacks must not FAIL.
        val root = BoxNode("root", 0, 0, 100)
        val rows = contentCheckStages(MediaType.VIDEO, root, false, false, true, false, hasVideoTrack = false)
        assertEquals(CheckAvailability.NOT_APPLICABLE, rows.single { it.stage == CheckStage.VISUAL_DECODE }.availability)
        assertEquals(CheckAvailability.AVAILABLE, rows.single { it.stage == CheckStage.AUDIO_DECODE }.availability)
    }

    @Test
    fun videoTrackPresenceIsOnlyRuledOutByAKnownAudioOnlySummary() {
        val audioOnly = MediaSummary(MediaCategory.VIDEO, listOf(SummarySection("Audio", listOf(SummaryField("Codec", "aac")))))
        val withVideo = MediaSummary(MediaCategory.VIDEO, listOf(SummarySection("Video", listOf(SummaryField("Codec", "h264"))),
            SummarySection("Audio", listOf(SummaryField("Codec", "aac")))))
        assertFalse(summaryMayHaveVideoTrack(audioOnly))
        assertTrue(summaryMayHaveVideoTrack(withVideo))
        // Unknown (not loaded / failed) must keep the decode stage rather than hide it.
        assertTrue(summaryMayHaveVideoTrack(null))
        assertTrue(summaryMayHaveVideoTrack(MediaSummary(MediaCategory.VIDEO, emptyList())))
    }

    @Test
    fun realAudioOnlyMp4SummaryRulesOutVideoButAvMp4DoesNot() {
        fun summary(name: String): MediaSummary {
            val file = File(javaClass.getResource("/content-check-false-positives/$name")!!.toURI())
            return com.multiviewer.parser.buildMediaSummary(parseFile(file), file)
        }
        assertFalse(summaryMayHaveVideoTrack(summary("aac-only.mp4")))
        assertTrue(summaryMayHaveVideoTrack(summary("avc-aac.mp4")))
    }

    @Test
    fun heicMotionPhotoDoesNotOfferVideoCodec() {
        val root = BoxNode("root", 0, 0, 100, children = listOf(BoxNode("moov", 0, 0, 100,
            children = listOf(BoxNode("hvcC", 8, 8, 40)))))
        val rows = contentCheckStages(MediaType.IMAGE, root, true, false, false, true)
        assertEquals(CheckAvailability.NOT_APPLICABLE, rows.single { it.stage == CheckStage.CODEC }.availability)
        assertEquals(CheckStage.MOTION_PHOTO, rows.last().stage)
        assertFalse(rows.any { it.stage == CheckStage.GAINMAP })
    }

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

    private fun renamed(fixture: String, name: String): File {
        val dir = kotlin.io.path.createTempDirectory("content-check-ext").toFile().apply { deleteOnExit() }
        return File(dir, name).also {
            File(ContentCheckPlanTest::class.java.getResource("/format-sniff/$fixture")!!.toURI()).copyTo(it)
            it.deleteOnExit()
        }
    }

    @Test
    fun `structure report warns when the extension does not match the content`() {
        val file = renamed("a.heic", "photo.jpg")
        val report = contentStructureReport(file, com.multiviewer.parser.parseFile(file), MediaType.IMAGE)
        val item = report.items.first()
        assertEquals("file.extension_mismatch", item.id)
        assertEquals(com.multiviewer.parser.integrity.CheckStatus.WARN, item.status)
        assertTrue(item.detail.contains(".jpg") && item.detail.contains("HEIF"), item.detail)

        val audio = renamed("a.m4a", "song.bin")
        assertEquals("file.extension_mismatch", contentAudioStructureReport(audio, com.multiviewer.parser.parseFile(audio)).items.first().id)
    }

    @Test
    fun `no extension item when the extension matches or content is unrecognized`() {
        val file = renamed("a.jpg", "photo.jpeg")
        assertNull(extensionMismatchItem(file))
        assertNull(extensionMismatchItem(File.createTempFile("ccp-unrecognized", ".mp4").apply { deleteOnExit(); writeBytes(ByteArray(4)) }))
    }

    private fun movieFile(name: String, spec: com.multiviewer.parser.conformance.isobmff.MovieSpec =
        com.multiviewer.parser.conformance.isobmff.MovieSpec()): File {
        val dir = kotlin.io.path.createTempDirectory("isobmff-ccp-").toFile().also { it.deleteOnExit() }
        return File(dir, name).also { it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.conformance.isobmff.buildMovie(spec)) }
    }

    @Test
    fun isoBmffReportReplacesTheParserOnlyVideoReport() {
        val report = containerConformanceFor(movieFile("x.mp4"))!!
        val conformance = contentConformanceReport(MediaType.VIDEO, container = report)
        assertEquals("ISO-BMFF", conformance.format)
        assertEquals(report.findings, conformance.findings)
        assertTrue(conformance.findings.any { it.rule.id == "bmff.moov.count" && it.status == ConformanceStatus.PASS })
        assertTrue(conformance.limitations.any { it.contains("Samsung") })
        assertTrue(conformance.limitations.containsAll(report.limitations))
    }

    @Test
    fun containerConformanceIsOnlyComputedForIsoBmffHeifJpegPngWebpAndGif() {
        val bmp = File.createTempFile("ccp-iso-", ".bmp").also { it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.bmpBytes()) }
        assertNull(containerConformanceFor(bmp))
        // Content decides: a PNG saved as .gif is still checked as PNG.
        val png = File.createTempFile("ccp-iso-", ".gif").also { it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.pngBytes()) }
        assertEquals(com.multiviewer.parser.conformance.png.PngConformanceChecker.PROFILE, containerConformanceFor(png)?.profile)
        assertEquals("PNG", containerConformanceFor(png)?.format)
        val webp = File.createTempFile("ccp-iso-", ".webp").also { it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.webpBytes()) }
        assertEquals("WebP", containerConformanceFor(webp)?.format)
        val gif = File.createTempFile("ccp-iso-", ".gif").also { it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.gifBytes()) }
        assertEquals("GIF", containerConformanceFor(gif)?.format)
        val jpeg = File.createTempFile("ccp-iso-", ".jpg").also { it.deleteOnExit(); it.writeBytes(jpegBytes()) }
        assertEquals("JPEG", containerConformanceFor(jpeg)?.format)
        assertEquals(com.multiviewer.parser.conformance.jpeg.JpegConformanceChecker.PROFILE, containerConformanceFor(jpeg)?.profile)
        val mp4 = containerConformanceFor(movieFile("x.mp4"))
        assertEquals("ISO-BMFF", mp4?.format)
        // Content decides, not the extension.
        assertEquals("ISO-BMFF", containerConformanceFor(movieFile("x.bin"))?.format)
        assertNull(containerConformanceFor(File("/definitely/missing/file.mp4")))
    }

    @Test
    fun isoBmffFailFindingIsNormativeAndListedInThePrompt() {
        val file = movieFile("nomoov.mp4", com.multiviewer.parser.conformance.isobmff.MovieSpec(dropBox = "moov"))
        val report = contentConformanceReport(MediaType.VIDEO, container = containerConformanceFor(file))
        val fail = report.findings.first { it.rule.id == "bmff.moov.count" }
        assertEquals(ConformanceStatus.FAIL, fail.status)
        val snapshot = ContentCheckSnapshot(null, null, false, null, null, null, MediaType.VIDEO, conformance = report)
        assertEquals(ConformanceStatus.FAIL, effectiveConformanceStatus(snapshot))
        val prompt = contentCheckPromptSection(snapshot, file)
        assertTrue(prompt.contains("[FAIL] bmff.moov.count — ${fail.rule.title}"), prompt)
        assertTrue(prompt.contains("공개 자료 기반, 원문 미대조"), prompt)
    }
    @Test
    fun isoBmffReportKeepsParserEvidenceLinesFromTheOldReport() {
        val iso = containerConformanceFor(movieFile("x.mp4"))!!
        val parserItem = IntegrityCheckItem("parser.warning", "Parser warning", CheckStatus.WARN, "stsz truncated", offset = 16, length = 4)
        val video = contentConformanceReport(MediaType.VIDEO,
            structure = ImageStructureReport("MP4", listOf(parserItem)), container = iso)
        assertEquals(iso.findings, video.findings)
        assertTrue(video.limitations.contains("파서 근거 / Parser evidence [WARN]: stsz truncated (바이트 / bytes 16–19)"), video.limitations.toString())
        val audio = contentConformanceReport(MediaType.AUDIO,
            audioStructure = com.multiviewer.parser.integrity.AudioStructureReport("M4A", emptyList(), listOf(parserItem)), container = iso)
        assertTrue(audio.limitations.any { it.startsWith("파서 근거 / Parser evidence [WARN]: stsz truncated") }, audio.limitations.toString())
        assertEquals(1, audio.limitations.count { it.startsWith("파서 근거") })
    }

    private fun bmffFixture(name: String, asName: String): File {
        val src = File(javaClass.getResource("/isobmff-conformance/$name")!!.toURI())
        val dir = kotlin.io.path.createTempDirectory("isobmff-gate-").toFile().also { it.deleteOnExit() }
        return src.copyTo(File(dir, asName)).also { it.deleteOnExit() }
    }

    @Test
    fun isoBmffGateAdmitsQuickTimeMovAndM4a() {
        listOf("v.mov", "clip.bin").forEach { asName ->
            val mov = containerConformanceFor(bmffFixture("v.mov", asName))
            assertEquals("QuickTime", mov?.format, asName)
            assertEquals(emptyList(), mov!!.findings.filter { it.status == ConformanceStatus.FAIL }, asName)
        }
        listOf("a.m4a", "song.bin").forEach { asName ->
            val m4a = containerConformanceFor(bmffFixture("a.m4a", asName))
            assertEquals("ISO-BMFF", m4a?.format, asName)
            assertEquals(emptyList(), m4a!!.findings.filter { it.status == ConformanceStatus.FAIL }, asName)
        }
    }

    @Test
    fun `HEIF and AVIF images get the HEIF report with parser evidence kept`() {
        val heic = containerConformanceFor(renamed("a.heic", "photo.heic"))!!
        assertEquals("HEIF", heic.format)
        assertEquals(com.multiviewer.parser.conformance.heif.HeifConformanceChecker.HEIF_PROFILE, heic.profile)
        // Content decides, not the extension.
        assertEquals("HEIF", containerConformanceFor(renamed("a.heic", "photo.jpg"))?.format)
        assertEquals("AVIF", containerConformanceFor(renamed("a.avif", "a.avif"))?.format)

        val parserItem = com.multiviewer.parser.integrity.IntegrityCheckItem("parser.0", "Parser", com.multiviewer.parser.integrity.CheckStatus.WARN, "odd box")
        val structural = com.multiviewer.parser.integrity.IntegrityCheckItem("heif.ftyp", "ftyp", com.multiviewer.parser.integrity.CheckStatus.PASS, "ok")
        val report = contentConformanceReport(MediaType.IMAGE,
            structure = ImageStructureReport("HEIF", listOf(structural, parserItem)), container = heic)
        assertEquals("HEIF", report.format)
        assertEquals(heic.findings, report.findings)
        assertTrue(report.limitations.any { it.startsWith("파서 근거 / Parser evidence") && it.contains("odd box") }, report.limitations.toString())
        assertTrue(report.limitations.any { it.contains("Samsung") })

        // IMAGE still needs a completed STRUCTURE stage before a green summary.
        val pending = ContentCheckSnapshot(null, null, false, null, null, null, MediaType.IMAGE, conformance = report)
        assertEquals(ConformanceStatus.NOT_CHECKED, effectiveConformanceStatus(pending))
    }
}
