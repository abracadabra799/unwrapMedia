package com.multiviewer.ui.ai

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
import com.multiviewer.parser.hrd.HrdSimulationReport
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
}
