package com.multiviewer.ui.ai

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ContainerHdrStatic
import com.multiviewer.parser.HdrScanResult
import com.multiviewer.parser.VideoCodec
import com.multiviewer.parser.c2pa.readC2paManifestStore
import com.multiviewer.parser.parseFile
import com.multiviewer.ui.ContentFileIdentity
import com.multiviewer.ui.FrameInfo
import com.multiviewer.ui.MediaType
import com.multiviewer.ui.StoredAnalysis
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiReportRegistryTest {
    private val dir = Files.createTempDirectory("ai-reports").toFile()
    private val file = File(dir, "clip.mp4").apply { writeBytes(ByteArray(4_000) { (it % 251).toByte() }) }
    private val label: (String, String) -> String = { _, en -> en }
    private val koLabel: (String, String) -> String = { ko, _ -> ko }

    private fun root(vararg children: BoxNode) = BoxNode("root", 0, 0, 4_000, children.toList())

    private fun inputs(
        type: MediaType,
        root: BoxNode? = root(BoxNode("ftyp", 0, 8, 100)),
        hdr: StoredAnalysis<HdrScanResult>? = null,
        frames: List<FrameInfo>? = null,
        avSync: String? = null,
        bitstream: String? = null,
    ) = AiReportInputs(
        file = file, root = root, type = type, avSync = avSync, bitstream = bitstream,
        hdr = hdr, hrd = null, timed = null, frames = frames, gainmap = null, gainmapBase = null,
    )

    private val hdrResult = HdrScanResult(VideoCodec.HEVC, emptyList(), ContainerHdrStatic(null, null), emptyList())

    @AfterTest
    fun cleanup() { dir.deleteRecursively() }

    @Test
    fun `an image tab lists only reports that apply to images`() {
        val sources = aiReportSources(inputs(MediaType.IMAGE), label)
        listOf(AiReportKind.SEF, AiReportKind.GAINMAP, AiReportKind.HDR, AiReportKind.HRD, AiReportKind.AVSYNC,
            AiReportKind.INTERVAL, AiReportKind.C2PA).forEach { assertFalse(it in sources, "$it should not apply: $sources") }
    }

    @Test
    fun `a video tab lists the video reports, timed and spatial only with their boxes`() {
        val sources = aiReportSources(inputs(MediaType.VIDEO), label)
        listOf(AiReportKind.HDR, AiReportKind.HRD, AiReportKind.INTERVAL, AiReportKind.AVSYNC)
            .forEach { assertTrue(it in sources, "$it missing: $sources") }
        assertFalse(AiReportKind.TIMED in sources)
        assertFalse(AiReportKind.SPATIAL in sources)

        val withBoxes = aiReportSources(inputs(MediaType.VIDEO, root = root(
            BoxNode("moov", 0, 8, 400, listOf(BoxNode("camm", 8, 8, 40), BoxNode("lhvC", 48, 8, 40))),
        )), label)
        assertIs<AiReportSource.Unavailable>(withBoxes[AiReportKind.TIMED])
        assertIs<AiReportSource.OnRequest>(withBoxes[AiReportKind.SPATIAL])
    }

    @Test
    fun `a sefd node makes the SEF report available on request`() {
        val sources = aiReportSources(inputs(MediaType.IMAGE, root = root(BoxNode("sefd", 3_000, 0, 1_000))), label)
        assertIs<AiReportSource.OnRequest>(sources[AiReportKind.SEF])
    }

    @Test
    fun `avsync keeps its rendered text and asks for the window when missing`() {
        val ready = aiReportSources(inputs(MediaType.VIDEO, avSync = "skew 12 ms"), label)
        assertEquals(AiReportSource.Ready("skew 12 ms"), ready[AiReportKind.AVSYNC])
        val missing = aiReportSources(inputs(MediaType.VIDEO), label)[AiReportKind.AVSYNC]
        assertIs<AiReportSource.Unavailable>(missing)
        assertTrue(missing.reason.contains("A/V Sync & Drift Analysis"), missing.reason)
    }

    @Test
    fun `a stored HDR result is ready while current, stale once the file changes, and points at the menu when missing`() {
        val stored = StoredAnalysis(hdrResult, ContentFileIdentity.capture(file))
        assertIs<AiReportSource.Ready>(aiReportSources(inputs(MediaType.VIDEO, hdr = stored), label)[AiReportKind.HDR])

        file.writeBytes(ByteArray(5_000))
        val stale = aiReportSources(inputs(MediaType.VIDEO, hdr = stored), label)[AiReportKind.HDR]
        assertIs<AiReportSource.Unavailable>(stale)
        assertTrue(stale.reason.contains("file changed"), stale.reason)

        val none = aiReportSources(inputs(MediaType.VIDEO), label)[AiReportKind.HDR]
        assertIs<AiReportSource.Unavailable>(none)
        assertTrue(none.reason.contains("HDR/SEI Metadata Analysis"), none.reason)
        val noneKo = aiReportSources(inputs(MediaType.VIDEO), koLabel)[AiReportKind.HDR]
        assertIs<AiReportSource.Unavailable>(noneKo)
        assertTrue(noneKo.reason.contains("HDR/SEI 메타데이터 분석"), noneKo.reason)
    }

    @Test
    fun `interval needs at least two frames and runs on request`() {
        val one = aiReportSources(inputs(MediaType.VIDEO, frames = listOf(FrameInfo(0, 'I', 10, 0.0))), label)
        assertIs<AiReportSource.Unavailable>(one[AiReportKind.INTERVAL])
        val three = aiReportSources(inputs(MediaType.VIDEO, frames = listOf(
            FrameInfo(0, 'I', 10, 0.0), FrameInfo(1, 'P', 10, 0.033), FrameInfo(2, 'P', 10, 0.066),
        )), label)[AiReportKind.INTERVAL]
        assertIs<AiReportSource.OnRequest>(three)
        assertTrue(three.run().contains("intervals 2"), three.run())
    }

    @Test
    fun `bitstream is listed only when a result exists`() {
        val none = aiReportSources(inputs(MediaType.VIDEO), label)
        assertFalse(AiReportKind.BITSTREAM in none)
        assertFalse(renderReportSection(none, label).contains("bitstream", ignoreCase = true))
        val some = aiReportSources(inputs(MediaType.VIDEO, bitstream = "corrupt frames: 0"), label)
        assertIs<AiReportSource.Ready>(some[AiReportKind.BITSTREAM])
    }

    @Test
    fun `a ready render that throws becomes unavailable and leaves other kinds`() {
        val sources = aiReportSources(
            inputs(MediaType.VIDEO, hdr = StoredAnalysis(hdrResult, ContentFileIdentity.capture(file)), avSync = "ok"),
            label, renderHdr = { _, _ -> throw IllegalStateException("secret Jane Doe") },
        )
        val hdr = sources[AiReportKind.HDR]
        assertIs<AiReportSource.Unavailable>(hdr)
        assertTrue(hdr.reason.contains("IllegalStateException") && !hdr.reason.contains("Jane"), hdr.reason)
        assertIs<AiReportSource.Ready>(sources[AiReportKind.AVSYNC])
    }

    @Test
    fun `a ready result is refused when the file changed after the context was captured`() {
        val c = ctx(mapOf(AiReportKind.AVSYNC to AiReportSource.Ready("skew 12 ms")))
        file.writeBytes(ByteArray(4_001))
        val r = executeToolRequest(AiToolRequest.Report("avsync"), c)
        assertFalse(r.ok)
        assertTrue(r.text.contains("changed"), r.text)
        assertFalse(r.text.contains("skew"))
    }

    @Test
    fun `failure text of a foreign exception carries only the class name`() {
        val (ok, text) = runReportWithTimeout({ throw NumberFormatException("For input string: \"Jane Doe secret\"") }, file)
        assertFalse(ok)
        assertTrue(text.contains("NumberFormatException"), text)
        assertFalse(text.contains("Jane Doe"), text)
    }

    // --- bridge ---

    private fun ctx(reports: Map<AiReportKind, AiReportSource>) =
        AiToolContext(file, root(), ContentFileIdentity.capture(file), null, null, reports)

    @Test
    fun `the bridge answers ready, refuses unavailable and inapplicable kinds`() {
        val ctx = ctx(mapOf(
            AiReportKind.AVSYNC to AiReportSource.Ready("skew 12 ms"),
            AiReportKind.HDR to AiReportSource.Unavailable("run HDR first"),
        ))
        val ok = executeToolRequest(AiToolRequest.Report("avsync"), ctx)
        assertTrue(ok.ok && ok.text.contains("12 ms"), ok.text)
        val un = executeToolRequest(AiToolRequest.Report("hdr"), ctx)
        assertFalse(un.ok)
        assertTrue(un.text.contains("run HDR first"), un.text)
        val na = executeToolRequest(AiToolRequest.Report("c2pa"), ctx)
        assertFalse(na.ok)
        assertTrue(na.text.contains("does not apply"), na.text)
    }

    @Test
    fun `on-request reports run, time out, and fail without paths`() {
        val ctx = ctx(mapOf(
            AiReportKind.INTERVAL to AiReportSource.OnRequest { "intervals 2" },
            AiReportKind.SEF to AiReportSource.OnRequest { error("boom ${file.absolutePath}") },
        ))
        val ok = executeToolRequest(AiToolRequest.Report("interval"), ctx)
        assertTrue(ok.ok && ok.text.contains("intervals 2"), ok.text)
        val failed = executeToolRequest(AiToolRequest.Report("sef"), ctx)
        assertFalse(failed.ok)
        assertTrue(failed.text.contains("IllegalStateException") && !failed.text.contains("boom"), failed.text)
        assertFalse(failed.text.contains(dir.absolutePath), failed.text)

        val started = System.nanoTime()
        val slow = runReportWithTimeout({ Thread.sleep(10_000); "late" }, file, timeoutSeconds = 1)
        assertFalse(slow.first)
        assertTrue(slow.second.contains("timed out"), slow.second)
        assertTrue(System.nanoTime() - started < 5_000_000_000L, "timeout did not cut the run short")
    }

    @Test
    fun `on-request reports are refused once the file changed`() {
        val context = ctx(mapOf(AiReportKind.SEF to AiReportSource.OnRequest { "should not run" }))
        file.writeBytes(ByteArray(5_000))
        val r = executeToolRequest(AiToolRequest.Report("sef"), context)
        assertFalse(r.ok)
        assertTrue(r.text.contains("changed"), r.text)
    }

    @Test
    fun `a timed-out run is interrupted`() {
        val interrupted = java.util.concurrent.CountDownLatch(1)
        runReportWithTimeout({
            try { Thread.sleep(10_000) } catch (e: InterruptedException) { interrupted.countDown(); throw e }
            "late"
        }, file, timeoutSeconds = 1)
        assertTrue(interrupted.await(3, java.util.concurrent.TimeUnit.SECONDS), "worker was not interrupted")
    }

    @Test
    fun `every report kind parses and unknown kinds are refused`() {
        assertEquals(AiToolRequest.Report("c2pa"), parseToolRequests("@@unwrapmedia report c2pa").single().request)
        AiReportKind.entries.forEach {
            assertEquals(AiToolRequest.Report(it.key), parseToolRequests("@@unwrapmedia report ${it.key}").single().request)
        }
        assertNull(parseToolRequests("@@unwrapmedia report nope").single().request)
        assertTrue(TOOL_PROTOCOL_HELP.contains("gainmap"))
    }

    @Test
    fun `the context section lists each report's state`() {
        assertEquals("", renderReportSection(emptyMap(), label))
        val sources = mapOf(
            AiReportKind.AVSYNC to AiReportSource.Ready("x"),
            AiReportKind.INTERVAL to AiReportSource.OnRequest { "y" },
            AiReportKind.HDR to AiReportSource.Unavailable("run it first"),
        )
        val en = renderReportSection(sources, label)
        assertTrue(en.contains("@@unwrapmedia report <kind>"), en)
        assertTrue(en.contains("- avsync: available"), en)
        assertTrue(en.contains("- interval: on request"), en)
        assertTrue(en.contains("- hdr: run it first"), en)
        val ko = renderReportSection(sources, koLabel)
        assertTrue(ko.contains("- avsync: 실행됨") && ko.contains("- interval: 요청 가능"), ko)
    }

    // --- real files ---

    private fun fixture(name: String) = File(javaClass.classLoader.getResource("c2pa/$name")!!.toURI())

    @Test
    fun `a real C2PA file gives an on-request report without the certificate subject`() {
        val c = fixture("C.jpg")
        val tree = parseFile(c)
        val sources = aiReportSources(AiReportInputs(c, tree, MediaType.IMAGE, null, null, null, null, null, null, null, null), label)
        val source = sources[AiReportKind.C2PA]
        assertIs<AiReportSource.OnRequest>(source)
        val out = source.run()
        assertTrue(out.contains("hard binding:"), out)
        assertTrue(out.contains("assertions:"), out)
        val subject = readC2paManifestStore(c, tree)?.manifests?.mapNotNull { it.signature?.certificateSubject }?.firstOrNull()
        assertNotNull(subject)
        assertFalse(out.contains(subject), out)

        val none = fixture("no_manifest.jpg")
        assertFalse(AiReportKind.C2PA in aiReportSources(
            AiReportInputs(none, parseFile(none), MediaType.IMAGE, null, null, null, null, null, null, null, null), label))
    }

    @Test
    fun `report error text keeps messages only for this app's exceptions`() {
        val jdk = reportErrorText(java.io.IOException("secret content in message"), file)
        assertFalse(jdk.contains("secret"), jdk)
        assertEquals("IOException", jdk)
    }

    @Test
    fun `a changed file turns every offered report into unavailable`() {
        val src = mapOf(
            AiReportKind.HDR to AiReportSource.Ready("x"),
            AiReportKind.C2PA to AiReportSource.OnRequest { "y" },
            AiReportKind.HRD to AiReportSource.Unavailable("not run"),
        )
        val out = markReportsStale(src, label)
        assertTrue(out.values.all { it is AiReportSource.Unavailable })
        assertEquals("not run", (out[AiReportKind.HRD] as AiReportSource.Unavailable).reason)
        val section = renderReportSection(out, label)
        assertFalse(section.contains("available\n") || section.contains("on request"), section)
    }
}
