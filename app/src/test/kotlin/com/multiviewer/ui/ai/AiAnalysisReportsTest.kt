package com.multiviewer.ui.ai

import com.multiviewer.ui.AudioWaveformData
import com.multiviewer.ui.ChannelPeaks
import com.multiviewer.ui.WaveformPeaks
import com.multiviewer.parser.ContainerHdrStatic
import com.multiviewer.parser.ContentLightLevel
import com.multiviewer.parser.DecodedMetadata
import com.multiviewer.parser.FrameHdrRecord
import com.multiviewer.parser.GainmapFormatType
import com.multiviewer.parser.GainmapInfo
import com.multiviewer.parser.Hdr10PlusMetadata
import com.multiviewer.parser.Hdr10PlusWindow
import com.multiviewer.parser.HdrScanResult
import com.multiviewer.parser.IsoGainmapChannel
import com.multiviewer.parser.IsoGainmapMetadata
import com.multiviewer.parser.MetadataSource
import com.multiviewer.parser.MetadataUnit
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefDirectoryEntryRow
import com.multiviewer.parser.SefIntegrityReport
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.VideoCodec
import com.multiviewer.parser.c2pa.BindingStatus
import com.multiviewer.parser.c2pa.C2paAction
import com.multiviewer.parser.c2pa.C2paAssertion
import com.multiviewer.parser.c2pa.C2paAssertionContent
import com.multiviewer.parser.c2pa.C2paClaim
import com.multiviewer.parser.c2pa.C2paContainer
import com.multiviewer.parser.c2pa.C2paHardBindingReport
import com.multiviewer.parser.c2pa.C2paIngredient
import com.multiviewer.parser.c2pa.C2paManifest
import com.multiviewer.parser.c2pa.C2paManifestStore
import com.multiviewer.parser.c2pa.C2paOffsetMap
import com.multiviewer.parser.c2pa.C2paSignatureInfo
import com.multiviewer.parser.c2pa.C2paSpan
import com.multiviewer.parser.c2pa.C2paStoreLocation
import com.multiviewer.parser.c2pa.CborMap
import com.multiviewer.parser.c2pa.CborText
import com.multiviewer.parser.c2pa.EditImpactAssessment
import com.multiviewer.parser.c2pa.HashComparison
import com.multiviewer.parser.hrd.HrdBufferPoint
import com.multiviewer.parser.hrd.HrdSimulationReport
import com.multiviewer.ui.AvSyncReport
import com.multiviewer.ui.SyncDiagnosis
import com.multiviewer.ui.SyncPoint
import com.multiviewer.ui.SyncSeverity
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.IntegrityCheckItem
import com.multiviewer.parser.metadata.SensorSamplePoint
import com.multiviewer.parser.metadata.SensorType
import com.multiviewer.parser.metadata.TimedMetadataReport
import com.multiviewer.parser.spatial.SpatialAnomaly
import com.multiviewer.parser.spatial.SpatialSeverity
import com.multiviewer.parser.spatial.SpatialVideoMetadata
import com.multiviewer.parser.spatial.SpatialVideoReport
import com.multiviewer.ui.FrameInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiAnalysisReportsTest {
    private val file = File("/Users/someone/private-dir/clip.mp4")
    private val secretPath = "/Users/someone/private-dir"

    private fun assertAbsent(out: String, vararg needles: String) {
        for (n in needles) assertFalse(out.contains(n), "output must not contain \"$n\":\n$out")
    }

    // ---- HDR ----

    private fun plus(maxSclTenths: Int, avgTenths: Int) = MetadataUnit(
        MetadataSource.HEVC_PREFIX_SEI, 4, "t35", 0, 1, 1,
        DecodedMetadata.Hdr10Plus(
            Hdr10PlusMetadata(1, 1, 1000, listOf(Hdr10PlusWindow(listOf(maxSclTenths, 0, 0), avgTenths, emptyList(), 0, false, null, null, emptyList())), null),
        ),
    )

    @Test
    fun `hdr report counts HDR10plus frames and lists default-threshold anomalies with scrubbed text`() {
        val records = listOf(
            FrameHdrRecord(0, 0, 0.0, true, listOf(plus(4000, 1000))),
            FrameHdrRecord(1, 1, 1 / 30.0, false, listOf(plus(6000, 1000))),
        )
        val r = HdrScanResult(VideoCodec.HEVC, records, ContainerHdrStatic(null, ContentLightLevel(1000, 400)), listOf("could not read $secretPath/clip.mp4 sample 9"))
        val out = renderHdrReport(r, file)
        assertTrue(out.contains("codec: HEVC"), out)
        assertTrue(out.contains("frames scanned: 2"), out)
        assertTrue(out.contains("frames with HDR10+: 2"), out)
        assertTrue(out.contains("container clli: MaxCLL 1000 · MaxFALL 400"), out)
        assertTrue(out.contains("container mdcv: absent"), out)
        assertTrue(out.contains("ABRUPT_CHANGE"), out)
        assertTrue(out.contains("[WARN]"), out)
        assertTrue(out.contains("targeted max luminance 1000"), out)
        assertTrue(out.contains("could not read clip.mp4 sample 9"), out)
        assertAbsent(out, secretPath)
    }

    // ---- HRD ----

    @Test
    fun `hrd report caps underflow frames at 30`() {
        val r = HrdSimulationReport(
            cpbSizeBytes = 1_000_000, bitrateBps = 8_000_000, initialDelaySeconds = 0.5, totalFrames = 100,
            minOccupancyPercent = 0.0, maxOccupancyPercent = 98.5, avgOccupancyPercent = 40.25,
            underflowCount = 40, underflowFrames = (0 until 40).toList(), warningFrames = listOf(41, 42), points = emptyList(),
        )
        val out = renderHrdReport(r)
        assertTrue(out.contains("cpb 1000000 B · bitrate 8000000 bps · initial delay 0.500 s · frames 100"), out)
        assertTrue(out.contains("occupancy min/avg/max 0.00/40.25/98.50 %"), out)
        val line = out.lines().first { it.startsWith("underflows 40") }
        assertTrue(line.contains("29 … 10 more"), line)
        assertFalse(line.contains("30"), line)
        assertTrue(out.contains("warnings at frames 41, 42"), out)
    }

    // ---- interval ----

    @Test
    fun `interval report puts the biggest gap first`() {
        val frames = listOf(0.0, 0.033, 0.066, 0.3).mapIndexed { i, p -> FrameInfo(i, if (i == 0) 'I' else 'P', 100, p) }
        val out = renderIntervalReport(frames)
        assertTrue(out.contains("frames 4 · intervals 3"), out)
        val lines = out.lines()
        val gapsAt = lines.indexOfFirst { it.startsWith("largest gaps") }
        assertTrue(gapsAt >= 0, out)
        assertTrue(lines[gapsAt + 1].trim().startsWith("#3"), out)
        val stats = lines.first { it.startsWith("interval ms") }
        assertTrue(stats.contains("/234.000/") || stats.contains("max 234.000"), stats)
    }

    @Test
    fun `interval report with fewer than 2 frames says so`() {
        assertTrue(renderIntervalReport(listOf(FrameInfo(0, 'I', 1, 0.0))).contains("fewer than 2 frames"))
    }

    // ---- timed ----

    @Test
    fun `timed report withholds GPS coordinates but keeps gyro statistics`() {
        val r = TimedMetadataReport(
            trackId = 3, trackFormat = "camm", totalSamples = 3, durationSeconds = 2.0, sampleRateHz = 1.5,
            samples = listOf(
                SensorSamplePoint(0, 0.0, SensorType.GPS, 37.5665, 126.978, 30.0, label = "Seoul City Hall"),
                SensorSamplePoint(1, 0.5, SensorType.GYRO, 0.1, 0.2, 0.3),
                SensorSamplePoint(2, 1.0, SensorType.GYRO, 0.3, 0.4, 0.5),
            ),
        )
        val out = renderTimedReport(r)
        assertTrue(out.contains("track camm id 3 · samples 3 · duration 2.000 s · rate 1.500 Hz"), out)
        assertTrue(out.contains("GPS: 1 points (coordinates withheld)"), out)
        assertTrue(out.contains("GYRO: 2 samples"), out)
        assertTrue(out.contains("x min/mean/max 0.1000/0.2000/0.3000"), out)
        assertAbsent(out, "37.5665", "126.978", "37.57", "126.98", "37.6", "127.0", "Seoul")
    }

    // ---- spatial ----

    @Test
    fun `spatial report lists anomalies with severity code frame and scrubbed message`() {
        val r = SpatialVideoReport(
            isSpatialVideo = true,
            metadata = SpatialVideoMetadata(true, "left", 64.0, 71.59, 0.02, "stereo", "lhvC v1"),
            totalFrames = 10, layer0TotalBytes = 5000, layer1TotalBytes = 1200,
            layer0KeyframeCount = 1, layer1KeyframeCount = 1, layerParityMatch = true,
            anomalies = listOf(SpatialAnomaly("LAYER1_EMPTY", SpatialSeverity.WARN, "layer 1 empty in $secretPath/clip.mp4", 5)),
            frames = emptyList(),
        )
        val out = renderSpatialReport(r, file)
        assertTrue(out.contains("spatial video: yes"), out)
        assertTrue(out.contains("hero eye left"), out)
        assertTrue(out.contains("- [WARN] LAYER1_EMPTY @5: layer 1 empty in clip.mp4"), out)
        assertAbsent(out, secretPath)
    }

    // ---- C2PA ----

    private fun c2paStore(): C2paManifestStore {
        val loc = C2paStoreLocation(C2paContainer.JPEG, ByteArray(0), C2paOffsetMap(listOf(C2paSpan(0, 0, 0))), emptyList(), emptyList())
        val claim = C2paClaim(
            label = "c2pa.claim.v2", version = 2, title = "Jane's holiday", format = "image/jpeg", instanceId = "xmp:iid:123",
            claimGenerator = "TestCam/1.0", generatorInfo = listOf("TestCam 1.0"), signatureRef = null,
            assertionRefs = emptyList(), alg = "sha256", raw = CborMap(emptyList()),
        )
        val assertions = listOf(
            C2paAssertion(
                "c2pa.actions.v2", "c2cb",
                C2paAssertionContent.Actions(listOf(C2paAction("c2pa.created", "TestCam", "2026-01-02T03:04:05Z", "http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture", null))),
                0, 10,
            ),
            C2paAssertion("c2pa.ingredient.v3", "c2cb", C2paAssertionContent.Ingredient(C2paIngredient("Jane's holiday original", "image/jpeg", "parentOf", "xmp:iid:456", null, null)), 0, 10),
            C2paAssertion("stds.schema-org.CreativeWork", "c2cb", C2paAssertionContent.Cbor(CborMap(listOf(CborText("author") to CborText("Jane Doe")))), 0, 10, diagnostic = "{\"author\": \"Jane Doe\"}"),
        )
        val sig = C2paSignatureInfo("ES256", "CN=Jane Doe,O=Private", "CN=Issuer Person", 2, null)
        val manifest = C2paManifest("urn:c2pa:abc", claim, null, null, assertions, sig, null, listOf("odd box at $secretPath/clip.mp4"))
        return C2paManifestStore(loc, listOf(manifest), listOf("store note"))
    }

    @Test
    fun `c2pa report keeps labels and binding but withholds title instanceId certificate and contents`() {
        val binding = C2paHardBindingReport(
            HashComparison("sha256", BindingStatus.MATCH, "aa", "aa", 120, 9000),
            emptyList(), EditImpactAssessment(true, "", true, "", true, ""),
        )
        val out = renderC2paReport(c2paStore(), binding, null, file)
        assertTrue(out.contains("location: JPEG"), out)
        assertTrue(out.contains("manifests: 1 (active = last)"), out)
        assertTrue(out.contains("claim generator: TestCam/1.0"), out)
        assertTrue(out.contains("c2pa.actions.v2"), out)
        assertTrue(out.contains("stds.schema-org.CreativeWork"), out)
        assertTrue(out.contains("c2pa.created by TestCam (http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture)"), out)
        assertTrue(out.contains("ingredients: 1"), out)
        assertTrue(out.contains("signature: alg ES256 · chain 2 certs · certificate subject/issuer present (withheld)"), out)
        assertTrue(out.contains("hard binding: MATCH · alg sha256 · hashed 9000 B · excluded 120 B"), out)
        assertTrue(out.contains("odd box at clip.mp4"), out)
        assertAbsent(out, "Jane's holiday", "xmp:iid:123", "xmp:iid:456", "CN=Jane Doe", "Jane Doe", "Issuer Person", "2026-01-02", "03:04:05", secretPath)
    }

    @Test
    fun `c2pa report says binding was not verified when it failed`() {
        val out = renderC2paReport(c2paStore(), null, "read failed at $secretPath/clip.mp4", file)
        assertTrue(out.contains("hard binding: not verified (read failed at clip.mp4)"), out)
        assertAbsent(out, secretPath)
    }

    // ---- SEF ----

    @Test
    fun `sef report drops semantic check details but keeps structural details`() {
        val r = SefIntegrityReport(
            overallSeverity = SefIntegritySeverity.PASS,
            structuralChecks = listOf(SefCheckResult(SefIntegritySeverity.PASS, "Field block gaps", "No gaps between consecutive field blocks")),
            semanticChecks = listOf(SefCheckResult(SefIntegritySeverity.PASS, "Entry #1 MCC", "MCC 450 (Korea) [ITU E.212 Annex A]")),
            declaredEntryCount = 1,
            directoryEntries = listOf(SefDirectoryEntryRow(1, "0xA01", "MCC_Data", 120, 16, 1000, 1016, true, true, SefIntegritySeverity.PASS)),
            declaredEntryCountSeverity = SefIntegritySeverity.PASS,
        )
        val out = renderSefReport(r, file)
        assertTrue(out.contains("overall: PASS"), out)
        assertTrue(out.contains("declared entries: 1 (PASS)"), out)
        assertTrue(out.contains("- [PASS] Field block gaps: No gaps between consecutive field blocks"), out)
        assertTrue(out.contains("- [PASS] Entry #1 MCC"), out)
        assertTrue(out.contains("#1 marker 0xA01 MCC_Data @120 +16 in-bounds y marker-match y [PASS]"), out)
        assertAbsent(out, "450", "Korea", "E.212")
    }

    // ---- gainmap ----

    @Test
    fun `gainmap report sends XMP length only and scrubs check details`() {
        val xmp = ("<x:xmpmeta hdrgm:Version=\"1.0\" secretTag=\"owner\">").padEnd(1000, 'z')
        val info = GainmapInfo(
            hasGainmap = true, hasGainmapImage = true, formatType = GainmapFormatType.ULTRA_HDR_JPEG, rawXmp = xmp,
            imageWidth = 1024, imageHeight = 768, imageFormat = "JPEG", byteOffset = 5000, byteLength = 40000,
            summaryDescription = "Ultra HDR found in $secretPath/clip.mp4",
            isoMetadata = IsoGainmapMetadata(0, 0, false, true, false, true, 0.0, 2.3, listOf(IsoGainmapChannel(0.0, 2.3, 1.0, 0.015625, 0.015625))),
        )
        val checks = listOf(IntegrityCheckItem("gm.dims", "Gain map dimensions", CheckStatus.PASS, "1024x768 matches $secretPath/clip.mp4"))
        val out = renderGainmapReport(info, checks, file)
        assertTrue(out.contains("format: ${GainmapFormatType.ULTRA_HDR_JPEG.displayName}"), out)
        assertTrue(out.contains("gain map image: 1024x768 JPEG @5000 +40000"), out)
        assertTrue(out.contains("XMP: 1000 chars (not sent)"), out)
        assertTrue(out.contains("alternate headroom 2.3000"), out)
        assertTrue(out.contains("- [PASS] Gain map dimensions: 1024x768 matches clip.mp4"), out)
        assertTrue(out.contains("summary: Ultra HDR found in clip.mp4"), out)
        assertAbsent(out, "xmpmeta", "secretTag", "zzzz", secretPath)
    }

    @Test
    fun `long lists are capped with a more marker`() {
        val r = SpatialVideoReport(
            true, SpatialVideoMetadata(true, null, null, null, null, null, null), 100, 0, 0, 0, 0, false,
            (0 until 45).map { SpatialAnomaly("C$it", SpatialSeverity.INFO, "m", it) }, emptyList(),
        )
        val out = renderSpatialReport(r, file)
        assertEquals(MAX_REPORT_ITEMS, out.lines().count { it.startsWith("- [INFO]") })
        assertTrue(out.contains("… 15 more"), out)
    }

    // ---- fix pass 1 ----

    private fun manifestWith(label: String, claimGenerator: String? = "G", actionParams: String? = null, sig: C2paSignatureInfo? = null, generatorInfo: List<String> = emptyList()): C2paManifest {
        val claim = C2paClaim(
            label = "c2pa.claim.v2", version = 2, title = "t", format = "image/jpeg", instanceId = "i",
            claimGenerator = claimGenerator, generatorInfo = generatorInfo, signatureRef = null,
            assertionRefs = emptyList(), alg = "sha256", raw = CborMap(emptyList()),
        )
        val a = C2paAssertion("c2pa.actions.v2", "c2cb", C2paAssertionContent.Actions(listOf(C2paAction("c2pa.edited", null, null, null, actionParams))), 0, 10)
        return C2paManifest(label, claim, null, null, listOf(a), sig, null, emptyList())
    }

    private fun storeOf(vararg ms: C2paManifest): C2paManifestStore {
        val loc = C2paStoreLocation(C2paContainer.JPEG, ByteArray(0), C2paOffsetMap(listOf(C2paSpan(0, 0, 0))), emptyList(), emptyList())
        return C2paManifestStore(loc, ms.toList(), emptyList())
    }

    @Test
    fun `c2pa manifest label keeps its prefix but withholds the uuid`() {
        val out = renderC2paReport(storeOf(manifestWith("contentauth:urn:uuid:1b2c3d4e-0000-4abc-8def-123456789abc")), null, null, file)
        assertTrue(out.contains("contentauth:urn:uuid:<id withheld>"), out)
        assertAbsent(out, "1b2c3d4e")
    }

    @Test
    fun `c2pa long hex runs are withheld`() {
        val out = renderC2paReport(storeOf(manifestWith("urn:c2pa:0123456789abcdef0123456789abcdef")), null, null, file)
        assertTrue(out.contains("urn:c2pa:<id withheld>"), out)
        assertAbsent(out, "0123456789abcdef")
    }

    @Test
    fun `c2pa action parameters are never emitted`() {
        val out = renderC2paReport(storeOf(manifestWith("m", actionParams = "{\"name\":\"Jane Doe\",\"where\":\"Seoul\"}")), null, null, file)
        assertAbsent(out, "Jane Doe", "Seoul")
    }

    @Test
    fun `c2pa signature error text is replaced by a fixed message`() {
        val sig = C2paSignatureInfo(null, null, null, 0, "bad cert for CN=Jane Doe")
        val out = renderC2paReport(storeOf(manifestWith("m", sig = sig)), null, null, file)
        assertTrue(out.contains("signature: could not be parsed"), out)
        assertAbsent(out, "Jane Doe")
    }

    @Test
    fun `c2pa more than five manifests keeps only the last five`() {
        val ms = (1..7).map { manifestWith("lab$it:urn:uuid:1b2c3d4e-0000-4abc-8def-12345678900$it") }
        val out = renderC2paReport(storeOf(*ms.toTypedArray()), null, null, file)
        assertTrue(out.contains("… 2 earlier manifests omitted"), out)
        assertAbsent(out, "lab1:", "lab2:")
        for (i in 3..7) assertTrue(out.contains("lab$i:"), out)
    }

    @Test
    fun `free text strings are clipped to 200 chars`() {
        val out = renderC2paReport(storeOf(manifestWith("m", claimGenerator = "Z".repeat(5000))), null, null, file)
        val line = out.lines().first { it.contains("claim generator:") }
        assertTrue(line.count { it == 'Z' } <= 200, "len ${line.count { it == 'Z' }}")
        assertTrue(line.contains("…"), line)
    }

    @Test
    fun `timed GENERIC samples never leak their values`() {
        val r = TimedMetadataReport(
            trackId = 1, trackFormat = "camm", totalSamples = 1, durationSeconds = 1.0, sampleRateHz = 1.0,
            samples = listOf(SensorSamplePoint(0, 0.0, SensorType.GENERIC, 37.5665, 126.978, 12.3456)),
        )
        val out = renderTimedReport(r)
        assertTrue(out.contains("GENERIC: 1 samples"), out)
        assertAbsent(out, "37.5665", "126.978", "12.3456")
    }

    private fun sefReport(names: List<String?>, semantic: List<SefCheckResult>) = SefIntegrityReport(
        overallSeverity = SefIntegritySeverity.PASS,
        structuralChecks = emptyList(),
        semanticChecks = semantic,
        declaredEntryCount = names.size.toLong(),
        directoryEntries = names.mapIndexed { i, n -> SefDirectoryEntryRow(i + 1, "0xA01", n, 100, 16, 1000, 1016, true, true, SefIntegritySeverity.PASS) },
        declaredEntryCountSeverity = SefIntegritySeverity.PASS,
    )

    @Test
    fun `sef names that are clean stay and value-bearing or oversized names are withheld everywhere`() {
        val bad = "Image_UTC_Data\u0000\u00001697412345678"
        val long = (0 until 500).map { ('a' + it % 26) }.joinToString("")
        val r = sefReport(
            listOf("Image_UTC_Data", bad, long),
            listOf(
                SefCheckResult(SefIntegritySeverity.PASS, "Entry #1 (Image_UTC_Data) JSON syntax", "d"),
                SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #2 ($bad) JSON syntax", "d"),
                SefCheckResult(SefIntegritySeverity.WARNING, "Entry #3 ($long) encoding", "d"),
                SefCheckResult(SefIntegritySeverity.WARNING, "Entry #3 weird \u0001 label", "d"),
            ),
        )
        val out = renderSefReport(r, file)
        assertTrue(out.contains("#1 marker 0xA01 Image_UTC_Data @"), out)
        assertTrue(out.contains("#2 marker 0xA01 <name withheld> @"), out)
        assertTrue(out.contains("#3 marker 0xA01 <name withheld> @"), out)
        assertTrue(out.contains("- [PASS] Entry #1 JSON syntax"), out)
        assertTrue(out.contains("- [WARNING] Entry check"), out)
        assertAbsent(out, "1697412345678", long.take(60), "\u0001")
    }

    @Test
    fun `sef name swallowing value bytes without NUL is withheld in rows and labels`() {
        val swallowed = "Image_UTC_Data1697412345678"
        val r = sefReport(
            listOf("Image_UTC_Data", swallowed),
            listOf(SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #2 ($swallowed) JSON syntax", "d")),
        )
        val out = renderSefReport(r, file)
        assertTrue(out.contains("Entry #2 JSON syntax"), out)
        assertTrue(out.contains("#2 marker 0xA01 <name withheld> @"), out)
        assertTrue(out.contains("#1 marker 0xA01 Image_UTC_Data @"), out)
        assertAbsent(out, "1697412345678")
    }

    @Test
    fun `sef overlapping crafted names cannot bypass label handling`() {
        val r = sefReport(
            listOf("Entry #2 (x", "x 16974123"),
            listOf(SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #2 (x 16974123) JSON syntax", "d")),
        )
        val out = renderSefReport(r, file)
        assertAbsent(out, "16974123")
    }

    @Test
    fun `sef normal names and non-parenthesised labels remain`() {
        val r = sefReport(
            listOf("Image_UTC_Data", "MotionPhoto_Data"),
            listOf(SefCheckResult(SefIntegritySeverity.PASS, "Entry #1 MotionPhoto_Data bounds", "d")),
        )
        val out = renderSefReport(r, file)
        assertTrue(out.contains("#1 marker 0xA01 Image_UTC_Data @"), out)
        assertTrue(out.contains("#2 marker 0xA01 MotionPhoto_Data @"), out)
        assertTrue(out.contains("- [PASS] Entry #1 MotionPhoto_Data bounds"), out)
    }

    @Test
    fun `c2pa generator info is capped`() {
        val out = renderC2paReport(storeOf(manifestWith("m", generatorInfo = (1..50).map { "gen$it" })), null, null, file)
        val line = out.lines().first { it.contains("generator info:") }
        assertEquals(MAX_REPORT_ITEMS, Regex("gen\\d+").findAll(line).count(), line)
        assertTrue(line.contains("… 20 more"), line)
    }

    // ---- trends ----

    @Test
    fun `hdr report appends luminance and MaxCLL trends`() {
        val records = listOf(
            FrameHdrRecord(0, 0, 0.0, true, listOf(plus(4000, 1000))),
            FrameHdrRecord(1, 1, 1 / 30.0, false, listOf(plus(6000, 1000))),
        )
        val r = HdrScanResult(VideoCodec.HEVC, records, ContainerHdrStatic(null, null), emptyList())
        val out = renderHdrReport(r, file)
        assertTrue(out.contains("trend:"), out)
        assertTrue(out.contains("HDR10+ targeted max luminance (nits) over s, 2 points:"), out)
        assertFalse(out.contains("SEI MaxCLL"), out)
    }

    @Test
    fun `hrd report appends occupancy trend`() {
        val pts = listOf(HrdBufferPoint(0, 0.0, 10, 10, 50.0, false, false, true), HrdBufferPoint(1, 0.04, 10, 10, 60.0, false, false, false))
        val r = HrdSimulationReport(1000, 1000, 0.1, 2, 50.0, 60.0, 55.0, 0, emptyList(), emptyList(), pts)
        val out = renderHrdReport(r)
        assertTrue(out.contains("trend:"), out)
        assertTrue(out.contains("buffer occupancy (%) over s, 2 points:"), out)
    }

    @Test
    fun `interval report appends frame interval trend`() {
        val frames = listOf(0.0, 0.033, 0.066, 0.3).mapIndexed { i, p -> FrameInfo(i, if (i == 0) 'I' else 'P', 100, p) }
        val out = renderIntervalReport(frames)
        assertTrue(out.contains("trend:"), out)
        assertTrue(out.contains("frame interval (ms) over s, 3 points:"), out)
    }

    @Test
    fun `timed trend has gyro magnitude and never GPS or GENERIC values`() {
        val r = TimedMetadataReport(
            trackId = 3, trackFormat = "camm", totalSamples = 4, durationSeconds = 2.0, sampleRateHz = 1.5,
            samples = listOf(
                SensorSamplePoint(0, 0.0, SensorType.GPS, 37.5665, 126.978, 30.0),
                SensorSamplePoint(1, 0.5, SensorType.GYRO, 0.1, 0.2, 0.3),
                SensorSamplePoint(2, 1.0, SensorType.GYRO, 0.3, 0.4, 0.5),
                SensorSamplePoint(3, 1.5, SensorType.GENERIC, 12.3456, 7.7777, 1.0),
            ),
        )
        val out = renderTimedReport(r)
        assertTrue(out.contains("trend:"), out)
        assertTrue(out.contains("GYRO magnitude over s, 2 points:"), out)
        assertFalse(out.contains("()"), out)
        assertFalse(out.contains("GPS magnitude") || out.contains("GENERIC magnitude"), out)
        assertAbsent(out, "37.5665", "126.978", "12.3456", "7.7777")
    }

    @Test
    fun `avsync report keeps the legacy first line and appends a skew trend`() {
        val diag = SyncDiagnosis("drift", SyncSeverity.WARNING, "audio drifts", emptyList(), "", "", "")
        val r = AvSyncReport(
            file, true, true, 10.0, 9.5, 0.5, 20.0, 40.0, 10.0, 25.0, 1.5,
            listOf(SyncPoint(0.0, 0.0, 0.0, 20.0, 0, 0), SyncPoint(1.0, 1.0, 1.0, 40.0, 1, 1)),
            listOf(diag), SyncSeverity.WARNING,
        )
        val out = renderAvSyncReport(r)
        assertEquals(
            "video 10.0 s, audio 9.5 s, initial skew 20.0 ms, max 40.0 ms, avg 25.0 ms, drift 1.5 ms/min; [WARNING] drift: audio drifts",
            out.lines().first(),
        )
        assertTrue(out.contains("trend:"), out)
        assertTrue(out.contains("A/V skew (ms) over s, 2 points:"), out)
    }

    private fun bucketLines(out: String, title: String): Int =
        out.lines().dropWhile { !it.startsWith(title) }.drop(1).takeWhile { it.startsWith("  ") }
            .count { !it.trim().startsWith("overall:") && !it.trim().startsWith("largest:") }

    private fun cll(v: Int) = MetadataUnit(
        MetadataSource.HEVC_PREFIX_SEI, 144, "cll", 0, 1, 1, DecodedMetadata.LightLevel(ContentLightLevel(v, 400)),
    )

    private fun bigHdr(n: Int) = HdrScanResult(
        VideoCodec.HEVC,
        (0 until n).map { FrameHdrRecord(it, it, it / 30.0, it == 0, listOf(plus(1000 + it % 3000, 1000), cll(500 + it % 500))) },
        ContainerHdrStatic(null, null), emptyList(),
    )

    @Test
    fun `hdr report with two series uses at most 30 buckets each`() {
        val out = renderHdrReport(bigHdr(10_000), file)
        assertTrue(bucketLines(out, "HDR10+ targeted max luminance") in 1..30, out)
        assertTrue(bucketLines(out, "SEI MaxCLL") in 1..30, out)
    }

    @Test
    fun `hdr report with 10000 records keeps both overall lines inside the 8000 char bridge limit`() {
        val out = renderHdrReport(bigHdr(10_000), file)
        val head = out.take(8000)
        val overalls = Regex("(?m)^  overall:").findAll(head).count()
        assertEquals(2, overalls, "length ${out.length}\n$out")
    }

    @Test
    fun `timed report with three sensor types uses at most 30 buckets each`() {
        val samples = SensorType.entries.filter { it == SensorType.GYRO || it == SensorType.ACCEL || it == SensorType.ORIENTATION }
            .flatMap { t -> (0 until 500).map { SensorSamplePoint(it, it / 100.0, t, it * 0.01, 0.2, 0.3) } }
        val out = renderTimedReport(TimedMetadataReport(3, "camm", samples.size, 5.0, 100.0, samples))
        for (t in listOf("GYRO", "ACCEL", "ORIENTATION")) assertTrue(bucketLines(out, "$t magnitude") in 1..30, out)
    }

    @Test
    fun `single series keeps 60 buckets`() {
        val records = (0 until 1000).map { FrameHdrRecord(it, it, it / 30.0, it == 0, listOf(plus(1000 + it, 1000))) }
        val out = renderHdrReport(HdrScanResult(VideoCodec.HEVC, records, ContainerHdrStatic(null, null), emptyList()), file)
        assertEquals(60, bucketLines(out, "HDR10+ targeted max luminance"), out)
    }

    @Test
    fun `reports whose only series is empty have no trend line`() {
        val hrd = renderHrdReport(HrdSimulationReport(1000, 1000, 0.1, 0, 0.0, 0.0, 0.0, 0, emptyList(), emptyList(), emptyList()))
        assertFalse(hrd.contains("trend:"), hrd)
        assertFalse(hrd.contains("no data"), hrd)
        val av = renderAvSyncReport(AvSyncReport(file, true, true, 10.0, 9.5, 0.5, 20.0, 40.0, 10.0, 25.0, 1.5, emptyList(), emptyList(), SyncSeverity.WARNING))
        assertFalse(av.contains("trend:"), av)
        assertFalse(av.contains("no data"), av)
        val hdr = renderHdrReport(HdrScanResult(VideoCodec.HEVC, emptyList(), ContainerHdrStatic(null, null), emptyList()), file)
        assertFalse(hdr.contains("trend:"), hdr)
        val timed = renderTimedReport(TimedMetadataReport(3, "camm", 0, 1.0, 1.0, emptyList()))
        assertFalse(timed.contains("trend:"), timed)
    }

    @Test
    fun `gop report counts types, GOP lengths and average bitrate`() {
        val types = "IPBBPIPB"
        val sizes = listOf(9000, 3000, 1000, 1000, 3000, 9000, 3000, 1000)
        val frames = types.mapIndexed { i, t -> FrameInfo(i, t, sizes[i], i / 30.0) }
        val out = renderGopReport(frames)
        assertTrue(out.contains("frames 8 · I 2 · P 3 · B 3 · other 0"), out)
        assertTrue(out.contains("GOP length (frames): min/mean/max 5.0/5.0/5.0 over 1 GOPs (last GOP open: 3 frames)"), out)
        assertTrue(out.contains("I 9000/9000.0/9000"), out)
        // 30000 bytes * 8 / (8/30 s) / 1000 = 900 kbps
        assertTrue(out.contains("average bitrate 900.0 kbps over 0.267 s"), out)
        assertTrue(out.contains("trend:\nbitrate (kbps) over s, 8 points:"), out)
    }

    @Test
    fun `gop report without I-frames says so`() {
        val out = renderGopReport(List(3) { FrameInfo(it, 'P', 100, it / 30.0) })
        assertTrue(out.contains("no I-frames"), out)
    }

    @Test
    fun `audio report finds clipping, silence and dBFS`() {
        val n = 100
        val c0min = FloatArray(n) { -0.5f }; val c0max = FloatArray(n) { 0.5f }
        c0max[5] = 1.0f
        val c1min = FloatArray(n) { -0.5f }; val c1max = FloatArray(n) { 0.5f }
        for (i in 20..29) { c1min[i] = 0f; c1max[i] = 0f }
        val data = AudioWaveformData(WaveformPeaks(2, n, listOf(ChannelPeaks(c0min, c0max), ChannelPeaks(c1min, c1max))), 10.0)
        val out = renderAudioReport(data)
        assertTrue(out.contains("channels 2 · duration 10.000 s · buckets 100 (100.0 ms each)"), out)
        val ch0 = out.lines().first { it.startsWith("channel 0:") }
        val ch1 = out.lines().first { it.startsWith("channel 1:") }
        assertTrue(ch0.contains("peak 0.0 dBFS") && ch0.contains("clipped buckets 1 (first at 0.500 s)"), ch0)
        assertTrue(ch0.contains("silent spans ≥0.5 s below −60 dBFS: 0"), ch0)
        assertTrue(ch1.contains("peak -6.0 dBFS") && ch1.contains("clipped buckets 0"), ch1)
        assertTrue(ch1.contains("silent spans ≥0.5 s below −60 dBFS: 1 (2.000–3.000 s)"), ch1)
        assertTrue(out.contains("channel 1 peak (dBFS) over s"), out)
    }

    @Test
    fun `audio report of a very long waveform is cheap and pooled`() {
        val n = 1_800_000
        fun ch(seed: Int) = ChannelPeaks(FloatArray(n) { -(0.1f + ((it + seed) % 100) / 200f) }, FloatArray(n) { 0.1f + ((it + seed) % 100) / 200f })
        val data = AudioWaveformData(WaveformPeaks(2, n, listOf(ch(0), ch(7))), 6000.0)
        val t0 = System.nanoTime()
        val out = renderAudioReport(data)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 2000, "took $ms ms")
        for (c in 0..1) {
            val lines = out.lines().dropWhile { !it.startsWith("channel $c peak (dBFS)") }.drop(1)
                .takeWhile { it.startsWith("  ") }
                .filter { it.contains("–") && it.contains("min ") && it.contains("mean ") && !it.contains("overall") }
            assertTrue(lines.size in 1..60, "channel $c lines ${lines.size}")
        }
    }

    @Test
    fun `audio report detects a silence span of exactly half a second`() {
        val n = 100
        val mn = FloatArray(n) { -0.5f }; val mx = FloatArray(n) { 0.5f }
        for (i in 10..14) { mn[i] = 0f; mx[i] = 0f }
        val out = renderAudioReport(AudioWaveformData(WaveformPeaks(1, n, listOf(ChannelPeaks(mn, mx))), 10.0))
        assertTrue(out.contains("silent spans ≥0.5 s below −60 dBFS: 1 (1.000–1.500 s)"), out)
    }

    @Test
    fun `quality report lists metrics, ascending worst frames and file names only`() {
        val ref = File("/secret/dir/raw.yuv")
        val enc = File("/secret/dir/enc.mp4")
        fun r(vararg v: Double) = com.multiviewer.ui.MetricRunResult(
            v.mapIndexed { i, x -> com.multiviewer.ui.MetricFrameSample(i, x) },
            com.multiviewer.ui.MetricStatistics(v.min(), v.max(), v.average(), v.sorted()[v.size / 2]))
        val c = com.multiviewer.ui.StoredComparison(
            mapOf("raw" to ref, "encodedA" to enc), emptyMap(),
            mapOf("Raw vs A" to mapOf("PSNR" to r(40.0, 30.0, 35.0), "SSIM" to r(0.99, 0.90, 0.95))),
            listOf(com.multiviewer.ui.ComparisonPair("rawA", "Raw vs A", enc, ref)))
        val text = renderQualityReport(c)
        assertTrue(text.contains("pair Raw vs A: reference raw \"raw.yuv\" · comparison encodedA \"enc.mp4\""), text)
        assertTrue(text.contains("PSNR: min 30.000"), text)
        assertTrue(text.contains("SSIM:"), text)
        assertTrue(text.contains("worst frames: #1=30.000, #2=35.000, #0=40.000"), text)
        assertTrue(text.contains("trend:"), text)
        assertFalse(text.contains("/secret"), text)
    }

    private fun qr(n: Int, seed: Int) = com.multiviewer.ui.MetricRunResult(
        List(n) { com.multiviewer.ui.MetricFrameSample(it, 30.0 + ((it * 7 + seed) % 1000) / 100.0) },
        com.multiviewer.ui.MetricStatistics(30.0, 40.0, 35.0, 35.0))

    @Test
    fun `quality report of 3 pairs x 3 metrics stays within the bridge limit and keeps every summary`() {
        val raw = File("/s/raw.yuv"); val a = File("/s/a.mp4"); val b = File("/s/b.mp4")
        val metrics = listOf("PSNR", "SSIM", "VMAF")
        val labels = listOf("Raw vs A", "Raw vs B", "A vs B")
        val c = com.multiviewer.ui.StoredComparison(
            mapOf("raw" to raw, "encodedA" to a, "encodedB" to b), emptyMap(),
            labels.mapIndexed { i, l -> l to metrics.associateWith { qr(5000, i) } }.toMap(),
            listOf(com.multiviewer.ui.ComparisonPair("rawA", "Raw vs A", a, raw),
                com.multiviewer.ui.ComparisonPair("rawB", "Raw vs B", b, raw),
                com.multiviewer.ui.ComparisonPair("AB", "A vs B", b, a)))
        val text = renderQualityReport(c)
        assertTrue(text.length <= 8000, "length ${text.length}")
        val summaryLines = text.lines().filter { it.contains("worst frames:") }
        assertEquals(9, summaryLines.size, text)
        labels.forEach { assertTrue(text.contains("pair $it"), text) }
        assertFalse(text.contains("largest:"), text)
        assertTrue(text.indexOf("trend:") > text.lastIndexOf("worst frames:"), text)
    }

    @Test
    fun `quality report lists incomplete pair metric combinations`() {
        val c = com.multiviewer.ui.StoredComparison(
            mapOf("raw" to File("r.yuv")), emptyMap(),
            mapOf("Raw vs A" to mapOf("PSNR" to qr(5, 0))), emptyList(),
            missing = listOf("Raw vs A SSIM", "Raw vs B PSNR"))
        val text = renderQualityReport(c)
        assertTrue(text.contains("incomplete: Raw vs A SSIM, Raw vs B PSNR"), text)
    }

    @Test
    fun `series with zero spikes has no largest line`() {
        val pts = (0..20).map { SeriesPoint(it.toDouble(), it.toDouble()) }
        assertFalse(renderSeries("t", "", "x", pts, spikes = 0).contains("largest:"))
        assertTrue(renderSeries("t", "", "x", pts).contains("largest:"))
    }
}
