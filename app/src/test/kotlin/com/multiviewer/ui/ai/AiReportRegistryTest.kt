package com.multiviewer.ui.ai

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ContainerHdrStatic
import com.multiviewer.parser.HdrScanResult
import com.multiviewer.parser.VideoCodec
import com.multiviewer.parser.c2pa.readC2paManifestStore
import com.multiviewer.parser.parseFile
import com.multiviewer.ui.AudioWaveformData
import com.multiviewer.ui.ChannelPeaks
import com.multiviewer.ui.WaveformPeaks
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
        avSync: com.multiviewer.ui.AvSyncReport? = null,
        audio: StoredAnalysis<AudioWaveformData>? = null,
    ) = AiReportInputs(
        file = file, root = root, type = type, avSync = avSync,
        hdr = hdr, hrd = null, timed = null, frames = frames, gainmap = null, gainmapBase = null, audio = audio,
    )

    private fun moovRoot() = root(BoxNode("ftyp", 0, 8, 100), BoxNode("moov", 100, 8, 400))

    private fun avSyncReport() = com.multiviewer.ui.AvSyncReport(
        file, true, true, 10.0, 9.5, 0.5, 20.0, 40.0, 10.0, 25.0, 1.5, emptyList(), emptyList(), com.multiviewer.ui.SyncSeverity.WARNING)

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
        val r = avSyncReport()
        val ready = aiReportSources(inputs(MediaType.VIDEO, avSync = r), label)
        assertEquals(AiReportSource.Ready(renderAvSyncReport(r)), ready[AiReportKind.AVSYNC])
        val missing = aiReportSources(inputs(MediaType.VIDEO), label)[AiReportKind.AVSYNC]
        assertIs<AiReportSource.Unavailable>(missing)
        assertTrue(missing.reason.contains("A/V Sync & Drift Analysis"), missing.reason)
    }

    @Test
    fun `a stored HDR result is ready while current, a stale or missing one runs the scan on request`() {
        val stored = StoredAnalysis(hdrResult, ContentFileIdentity.capture(file))
        assertIs<AiReportSource.Ready>(aiReportSources(inputs(MediaType.VIDEO, hdr = stored), label)[AiReportKind.HDR])

        file.writeBytes(ByteArray(5_000))
        val stale = aiReportSources(inputs(MediaType.VIDEO, root = moovRoot(), hdr = stored), label)[AiReportKind.HDR]
        assertIs<AiReportSource.OnRequest>(stale)
        assertEquals(AUTO_RUN_TIMEOUT_SECONDS, stale.timeoutSeconds)

        val none = aiReportSources(inputs(MediaType.VIDEO, root = moovRoot()), label)[AiReportKind.HDR]
        assertIs<AiReportSource.OnRequest>(none)
        assertEquals("runs the analysis", none.autoRunLabel)
        // Without a tree nothing can be scanned: the menu hint stays.
        val noTree = aiReportSources(inputs(MediaType.VIDEO, root = null), koLabel)[AiReportKind.HDR]
        assertIs<AiReportSource.Unavailable>(noTree)
        assertTrue(noTree.reason.contains("HDR/SEI 메타데이터 분석"), noTree.reason)
    }

    @Test
    fun `interval needs at least two frames and runs on request`() {
        val one = aiReportSources(inputs(MediaType.VIDEO, frames = listOf(FrameInfo(0, 'I', 10, 0.0))), label,
            hooks = AiAutoRunHooks(probeFrames = { listOf(FrameInfo(0, 'I', 10, 0.0)) }))[AiReportKind.INTERVAL]
        assertIs<AiReportSource.OnRequest>(one)
        assertEquals(AUTO_RUN_TIMEOUT_SECONDS, one.timeoutSeconds)
        assertTrue(one.run().contains("fewer than 2 frames"), one.run())
        val three = aiReportSources(inputs(MediaType.VIDEO, frames = listOf(
            FrameInfo(0, 'I', 10, 0.0), FrameInfo(1, 'P', 10, 0.033), FrameInfo(2, 'P', 10, 0.066),
        )), label)[AiReportKind.INTERVAL]
        assertIs<AiReportSource.OnRequest>(three)
        assertTrue(three.run().contains("intervals 2"), three.run())
    }

    @Test
    fun `bitstream is not a report kind - Content Check covers decode and packet integrity`() {
        assertTrue(AiReportKind.entries.none { it.key == "bitstream" })
        assertFalse(renderReportSection(aiReportSources(inputs(MediaType.VIDEO), label), label).contains("bitstream", ignoreCase = true))
        val parsed = parseToolRequests("@@unwrapmedia report bitstream").single()
        assertEquals(null, parsed.request)
    }

    @Test
    fun `a ready render that throws becomes unavailable and leaves other kinds`() {
        val sources = aiReportSources(
            inputs(MediaType.VIDEO, hdr = StoredAnalysis(hdrResult, ContentFileIdentity.capture(file)), avSync = avSyncReport()),
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
        AiToolContext(file, root(), ContentFileIdentity.capture(file), null, { null }, reports)

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
        val sources = aiReportSources(AiReportInputs(c, tree, MediaType.IMAGE, null, null, null, null, null, null, null), label)
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
            AiReportInputs(none, parseFile(none), MediaType.IMAGE, null, null, null, null, null, null, null), label))
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

    private fun frames3() = List(3) { FrameInfo(it, if (it == 0) 'I' else 'P', 1000, it / 30.0) }

    private val waveData = AudioWaveformData(
        WaveformPeaks(1, 4, listOf(ChannelPeaks(FloatArray(4) { -0.5f }, FloatArray(4) { 0.5f }))), 1.0)

    @Test
    fun `gop is on request for a video with frames and auto-runs without them`() {
        var probes = 0
        val hooks = AiAutoRunHooks(probeFrames = { probes++; null })
        val on = aiReportSources(inputs(MediaType.VIDEO, frames = frames3()), label, hooks = hooks)[AiReportKind.GOP]
        assertIs<AiReportSource.OnRequest>(on)
        assertEquals(REPORT_TIMEOUT_SECONDS, on.timeoutSeconds)
        assertNull(on.autoRunLabel)
        assertTrue(on.run().contains("frames 3"))
        assertEquals(0, probes)
        val auto = aiReportSources(inputs(MediaType.VIDEO), label, hooks = hooks)[AiReportKind.GOP]
        assertIs<AiReportSource.OnRequest>(auto)
        assertEquals(AUTO_RUN_TIMEOUT_SECONDS, auto.timeoutSeconds)
    }

    // --- auto-run ---

    private class Recorder {
        var probes = 0
        var scans = 0
        val publishedFrames = mutableListOf<List<FrameInfo>>()
        val publishedHdr = mutableListOf<StoredAnalysis<HdrScanResult>>()
        val running = mutableListOf<String?>()
    }

    private fun hooks(
        r: Recorder,
        frames: List<FrameInfo>? = null,
        hdr: HdrScanResult? = null,
        probeError: Throwable? = null,
    ) = AiAutoRunHooks(
        probeFrames = { r.probes++; probeError?.let { throw it }; frames },
        scanHdr = { _, _, _ -> r.scans++; hdr },
        captureIdentity = { ContentFileIdentity.capture(it) },
        publishFrames = { r.publishedFrames += it },
        publishHdr = { r.publishedHdr += it },
        running = { r.running += it },
    )

    @Test
    fun `without frames gop and interval auto-run one shared probe and publish it once`() {
        val r = Recorder()
        val sources = aiReportSources(inputs(MediaType.VIDEO), label, hooks = hooks(r, frames = frames3()))
        val gop = sources[AiReportKind.GOP]
        val interval = sources[AiReportKind.INTERVAL]
        assertIs<AiReportSource.OnRequest>(gop)
        assertIs<AiReportSource.OnRequest>(interval)
        assertEquals(AUTO_RUN_TIMEOUT_SECONDS, gop.timeoutSeconds)
        assertEquals(AUTO_RUN_TIMEOUT_SECONDS, interval.timeoutSeconds)
        assertTrue(gop.run().contains("frames 3"))
        assertTrue(interval.run().contains("intervals 2"))
        assertEquals(1, r.probes)
        assertEquals(listOf(frames3()), r.publishedFrames)
        assertEquals(listOf<String?>("frame analysis", null), r.running)
    }

    @Test
    fun `a probe with fewer than two frames reports so and publishes nothing`() {
        listOf<List<FrameInfo>?>(null, listOf(FrameInfo(0, 'I', 10, 0.0))).forEach { probed ->
            val r = Recorder()
            val gop = aiReportSources(inputs(MediaType.VIDEO), label, hooks = hooks(r, frames = probed))[AiReportKind.GOP]
            assertIs<AiReportSource.OnRequest>(gop)
            val expected = if (probed == null) "frame analysis could not run (ffprobe failed or timed out)"
                else "frame analysis found fewer than 2 frames"
            assertEquals(expected, gop.run())
            assertTrue(r.publishedFrames.isEmpty(), "$probed must not be published")
        }
    }

    @Test
    fun `a failed probe runs once across gop, interval and gop again`() {
        val r = Recorder()
        val autoRun = AiAutoRunState(null)
        val sources = aiReportSources(inputs(MediaType.VIDEO), label, autoRun = autoRun, hooks = hooks(r, frames = null))
        val gop = sources[AiReportKind.GOP] as AiReportSource.OnRequest
        val interval = sources[AiReportKind.INTERVAL] as AiReportSource.OnRequest
        assertEquals("frame analysis could not run (ffprobe failed or timed out)", gop.run())
        assertTrue(interval.run().startsWith("frame analysis already failed in this question"), "interval")
        assertTrue(gop.run().startsWith("frame analysis already failed in this question"), "gop")
        assertEquals(1, r.probes)
    }

    @Test
    fun `a timed-out probe is not retried by a later request in the same send`() {
        val started = java.util.concurrent.CountDownLatch(1)
        var probes = 0
        val hooks = AiAutoRunHooks(probeFrames = {
            probes++
            started.countDown()
            Thread.sleep(30_000)
            null
        })
        val autoRun = AiAutoRunState(null)
        val sources = aiReportSources(inputs(MediaType.VIDEO), label, autoRun = autoRun, hooks = hooks)
        val gop = sources[AiReportKind.GOP] as AiReportSource.OnRequest
        val (ok, _) = runReportWithTimeout(gop.run, file, 1)
        assertFalse(ok)
        // The interrupted worker records the failure in its finally; give it a moment.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (autoRun.framesFailure.get() == null && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(gop.run().startsWith("frame analysis already failed in this question"))
        assertEquals(1, probes)
        assertTrue(autoRun.frames.get() == null)
    }

    @Test
    fun `a failed HDR scan runs once across two hdr requests`() {
        val r = Recorder()
        val hdr = aiReportSources(inputs(MediaType.VIDEO, root = moovRoot()), label, hooks = hooks(r, hdr = null))[AiReportKind.HDR]
            as AiReportSource.OnRequest
        assertTrue(hdr.run().startsWith("HDR scan: no supported"))
        assertTrue(hdr.run().startsWith("HDR analysis already failed in this question"))
        assertEquals(1, r.scans)
        assertTrue(r.publishedHdr.isEmpty())
    }

    @Test
    fun `hdr auto-run is only offered for an MP4 family tree with moov`() {
        val noMoov = aiReportSources(inputs(MediaType.VIDEO), label, hooks = hooks(Recorder()))[AiReportKind.HDR]
        assertIs<AiReportSource.Unavailable>(noMoov)
        assertTrue(noMoov.reason.contains("HDR/SEI Metadata Analysis") || noMoov.reason.contains("HDR"), noMoov.reason)
    }

    @Test
    fun `a missing HDR result auto-runs the scan once, publishes it with the file identity, and reuses it`() {
        val r = Recorder()
        val autoRun = AiAutoRunState(null)
        val hdr = aiReportSources(inputs(MediaType.VIDEO, root = moovRoot()), label, renderHdr = { _, _ -> "HDR RENDERED" },
            autoRun = autoRun, hooks = hooks(r, hdr = hdrResult))[AiReportKind.HDR]
        assertIs<AiReportSource.OnRequest>(hdr)
        assertEquals(AUTO_RUN_TIMEOUT_SECONDS, hdr.timeoutSeconds)
        assertEquals("HDR RENDERED", hdr.run())
        assertEquals("HDR RENDERED", hdr.run())
        assertEquals(1, r.scans)
        assertEquals(1, r.publishedHdr.size)
        assertEquals(hdrResult, r.publishedHdr.single().value)
        assertEquals(ContentFileIdentity.capture(file), r.publishedHdr.single().identity)
        assertEquals(hdrResult, autoRun.hdr.get())
        assertEquals(listOf<String?>("HDR analysis", null), r.running)
    }

    @Test
    fun `an unsupported HDR scan says so and publishes nothing`() {
        val r = Recorder()
        val hdr = aiReportSources(inputs(MediaType.VIDEO, root = moovRoot()), label, hooks = hooks(r, hdr = null))[AiReportKind.HDR]
        assertIs<AiReportSource.OnRequest>(hdr)
        assertEquals("HDR scan: no supported video sample table (MP4/MOV family only)", hdr.run())
        assertTrue(r.publishedHdr.isEmpty())
    }

    @Test
    fun `the running indicator is cleared even when the probe throws`() {
        val r = Recorder()
        val gop = aiReportSources(inputs(MediaType.VIDEO), label,
            hooks = hooks(r, probeError = IllegalStateException("boom")))[AiReportKind.GOP]
        assertIs<AiReportSource.OnRequest>(gop)
        kotlin.test.assertFailsWith<IllegalStateException> { gop.run() }
        assertEquals(listOf<String?>("frame analysis", null), r.running)
    }

    @Test
    fun `the section tells the model which reports run their analysis on request`() {
        val sources = aiReportSources(inputs(MediaType.VIDEO, root = moovRoot()), label, hooks = hooks(Recorder()))
        val en = renderReportSection(sources, label)
        assertTrue(en.contains("- gop: on request (runs the analysis)"), en)
        assertTrue(en.contains("- hdr: on request (runs the analysis)"), en)
        val ko = renderReportSection(aiReportSources(inputs(MediaType.VIDEO), koLabel, hooks = hooks(Recorder())), koLabel)
        assertTrue(ko.contains("- gop: 요청 가능 (분석 자동 실행)"), ko)
        val withFrames = renderReportSection(aiReportSources(inputs(MediaType.VIDEO, frames = frames3()), label), label)
        assertTrue(withFrames.contains("- gop: on request\n") || withFrames.endsWith("- gop: on request"), withFrames)
    }

    private fun ffprobeAvailable() = runCatching {
        ProcessBuilder(com.multiviewer.ui.FfmpegLocator.ffprobePath(), "-version")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
    }.getOrDefault(false)

    @Test
    fun `a real default mp4 auto-runs ffprobe for the gop report`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(ffprobeAvailable(), "ffprobe not available")
        val mp4 = File(javaClass.classLoader.getResource("video-frame/default.mp4")!!.toURI())
        val sources = aiReportSources(AiReportInputs(mp4, parseFile(mp4), MediaType.VIDEO, null, null, null, null, null, null, null), label)
        val gop = sources[AiReportKind.GOP]
        assertIs<AiReportSource.OnRequest>(gop)
        val (ok, out) = runReportWithTimeout(gop.run, mp4, gop.timeoutSeconds)
        assertTrue(ok, out)
        assertTrue(out.contains("frames "), out)
        assertTrue(out.contains("GOP length"), out)
    }

    @Test
    fun `audio is on request while the stored waveform is current, void when the file changes, unavailable when absent`() {
        val audioFile = File(dir, "a.wav").apply { writeBytes(ByteArray(100)) }
        fun audioInputs(a: StoredAnalysis<AudioWaveformData>?) = inputs(MediaType.AUDIO, audio = a).copy(file = audioFile)
        val stored = StoredAnalysis(waveData, ContentFileIdentity.capture(audioFile))
        val onReq = aiReportSources(audioInputs(stored), label)[AiReportKind.AUDIO]
        assertIs<AiReportSource.OnRequest>(onReq)
        assertTrue(onReq.run().contains("channels 1"), onReq.run())
        assertIs<AiReportSource.Unavailable>(aiReportSources(audioInputs(null), label)[AiReportKind.AUDIO])
        audioFile.writeBytes(ByteArray(200))
        val void = aiReportSources(audioInputs(stored), label)[AiReportKind.AUDIO]
        assertIs<AiReportSource.Unavailable>(void)
        assertTrue(void.reason.contains("changed"), void.reason)
    }

    @Test
    fun `an image tab offers neither gop nor audio`() {
        val sources = aiReportSources(inputs(MediaType.IMAGE, frames = frames3()), label)
        assertFalse(AiReportKind.GOP in sources)
        assertFalse(AiReportKind.AUDIO in sources)
    }

    private fun comparison(a: File, b: File): com.multiviewer.ui.StoredComparison {
        val res = com.multiviewer.ui.MetricRunResult(
            listOf(com.multiviewer.ui.MetricFrameSample(0, 40.0), com.multiviewer.ui.MetricFrameSample(1, 30.0)),
            com.multiviewer.ui.MetricStatistics(30.0, 40.0, 35.0, 35.0))
        val pair = com.multiviewer.ui.ComparisonPair("rawA", "Raw vs A", b, a)
        return com.multiviewer.ui.StoredComparison(
            mapOf("raw" to a, "encodedA" to b),
            mapOf("raw" to ContentFileIdentity.capture(a), "encodedA" to ContentFileIdentity.capture(b)),
            mapOf("Raw vs A" to mapOf("PSNR" to res)), listOf(pair))
    }

    @Test
    fun `quality is on request for a compared file, absent for others, void when a compared file changes`() {
        val other = File(dir, "enc.mp4").apply { writeBytes(ByteArray(50)) }
        val c = comparison(file, other)
        val on = aiReportSources(inputs(MediaType.VIDEO).copy(quality = c), label)[AiReportKind.QUALITY]
        assertIs<AiReportSource.OnRequest>(on)
        assertTrue(on.run().contains("PSNR"))
        val stranger = File(dir, "x.mp4").apply { writeBytes(ByteArray(5)) }
        assertFalse(AiReportKind.QUALITY in aiReportSources(inputs(MediaType.VIDEO).copy(file = stranger, quality = c), label))
        assertFalse(AiReportKind.QUALITY in aiReportSources(inputs(MediaType.VIDEO), label))
        other.writeBytes(ByteArray(99))
        val void = aiReportSources(inputs(MediaType.VIDEO).copy(quality = c), label)[AiReportKind.QUALITY]
        assertIs<AiReportSource.Unavailable>(void)
        assertTrue(void.reason.contains("changed"), void.reason)
    }
}
