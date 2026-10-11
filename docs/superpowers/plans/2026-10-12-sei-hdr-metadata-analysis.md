# SEI / HDR Metadata Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse in-band SEI (H.264/HEVC) and AV1 metadata OBUs — with field-level decoding of mastering display, content light level and HDR10+ (ST 2094-40) — plus `mdcv`/`clli` boxes, and present them per frame in Detail Properties and as a whole-stream analysis window (trend graph, per-frame table, anomaly list, CSV export).

**Architecture:** Pure parser functions in `parser/` (SEI splitting, payload decoders, HDR10+ decoder, AV1 metadata decoder, box decoders, a shared ISO-BMFF sample-table reader, a per-sample metadata walker, a whole-stream scanner and anomaly rules), with no decoding of picture data. The UI adds one lazily-resolved Detail Properties section and one standalone `Window` opened from the Analyze menu.

**Tech Stack:** Kotlin 2.0.21, Compose Desktop (Material3), kotlin.test (JUnit), ffmpeg 8.1.2 / x265 4.2 / libx264 / SVT-AV1 (fixture generation only; nothing is bundled).

**Spec:** `docs/superpowers/specs/2026-10-12-sei-hdr-metadata-analysis-design.md`

## Global Constraints

- Containers: MP4/MOV (ISOBMFF) only. Codecs: H.264, HEVC, AV1.
- No picture decoding and no ffmpeg/ffprobe at runtime for this feature; only NAL/OBU headers and SEI/metadata payloads are read. Per-payload read cap: 64 KB (`MAX_SEI_PAYLOAD_BYTES`, `METADATA_OBU_READ_CAP`); per-SEI-NAL read cap 256 KB.
- Malformed SEI/OBU data never aborts a scan: it becomes a `DecodedMetadata.Malformed` unit with its offset.
- Dolby Vision: HEVC NAL types 62/63 reported as "present" only, never parsed.
- Out of scope: Dolby Vision RPU, HDR Vivid, VVC, Matroska/WebM, raw Annex-B, pic_timing/buffering_period field decoding, Content Check integration, editing metadata, QuickTime `SmDm`/`CoLL` boxes (see Deviations).
- All new Compose text uses `androidx.compose.material3.Text` (Material2 `Text` is invisible on this app's theme).
- User-visible strings are bilingual (KO/EN) via `I18n.kt` or a local `label(korean, english)` helper (the `ContentCheckWindow` pattern).
- Per-frame metadata reads are gated on `tab.type == MediaType.VIDEO` **and** a codec config box being present — selecting a frame in any other file must never trigger a sample read (APV lesson).
- Closing the analysis window must cancel the scan.
- Every test command runs from the repo root: `./gradlew :app:test --tests '<FQN>'`.
- Commit after each task. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Deviations from the spec (decided while planning)

1. **`SmDm`/`CoLL` dropped.** No local muxer writes them, so they cannot be verified against a real file; ffmpeg 8.1.2 writes `mdcv`/`clli` in both `.mp4` and `.mov` (verified). Add later if a real file shows up.
2. **Trend graph is a new `HdrTrendGraph` composable modelled on `MetricGraph`**, not an extension of it — `MetricGraph` is single-series with no reference lines/markers, and changing it would risk the Quality Compare window.
3. **Graph/table X axis is presentation order** (samples sorted by `dts + ctts`), so the "abrupt change" rule compares visually adjacent frames even with B-frames. Each record also keeps its decode-order `sampleIndex`.
4. **"targeted_system_display_maximum_luminance == 0"** is checked even when no MDCV exists (only the "> MDCV max" half needs MDCV).
5. **The shared sample-table readers are extracted from `Av2SampleIndexer.kt`** into `IsoSampleTable.kt` (AV2 indexer refactored to use them; its tests guard the refactor).

## Verified facts (planning-time real-file verification, 2026-10-12)

All byte strings below were dumped from real files produced by the commands in Task 1 and cross-checked against `ffprobe -show_frames` / `-show_streams` side data.

- **HEVC SEI** (x265 4.2): CLL prefix SEI NAL = `4e01 90 04 03e8 0190 80` → MaxCLL 1000, MaxFALL 400. MDCV NAL = `4e01 89 18 33c286c41d4c0bb884d03e803d134042 0098968000000300328000` (contains one emulation-prevention byte) → primaries in **G, B, R** order: G(13250,34500) B(7500,3000) R(34000,16000) WP(15635,16450), max 10000000, min 50 (units 0.00002 / 0.0001 cd/m²). ffprobe maps these the same way (green_x=13250/50000 …).
- **H.264 SEI** (libx264, `-x264-params mastering-display=…:cll=1000,400`): the same payloads with a 1-byte NAL header `06`: `06 89 18 33c2…` and `06 90 04 03e8 0190 80`. MDCV/CLL appear only in IDR samples (sample 0 and 5 of a keyint=5 clip).
- **user_data_unregistered** (x265): `4e01 05 ffffffffffffffffff 51 2ca2de09b51747dbbb55a4fe7fc2fc4e 7832363520…` — the size uses the 0xFF extension (9×255 + 0x51 = 2376); UUID `2ca2de09-b517-47db-bb55-a4fe7fc2fc4e`, ASCII starts `x265 (build 216) - 4.2+1-e444744`.
- **HDR10+ T.35 payload** (crafted by `inject_hdr10plus.py`, decoded identically by ffprobe 8.1.2): `b5003c0001040140001f401f401194073a00fa240800002800285000c8c80321900fa2580c82d01772f82713184650004fa12c0d2c963840` → application_version 1, num_windows 1, targeted 1000, maxscl [4000, 4500, 3700], average_maxrgb 1000, 9 percentiles (1,0),(5,10),(10,50),(25,200),(50,1000),(75,800),(90,1500),(95,2500),(99,4500), fraction_bright_pixels 0, tone_mapping_flag 1, knee (1000, 1200), bezier anchors [300, 600, 900], color_saturation_mapping_flag 0. Unit of maxscl/average_maxrgb/percentiles is 0.1 cd/m² (ffprobe prints value/100000 normalised to 10000 nits).
- **ffprobe makes HDR10+ "sticky"**: on frames 12–14 of the HEVC fixture (which have no HDR10+ SEI) ffprobe reports frame 11's values again. ffprobe therefore cannot detect missing HDR10+ — our parser must, and the integration test asserts it.
- **AV1 metadata OBUs** (SVT-AV1 `mastering-display=…:content-light=1000,400`): `2a 06 01 03e8 0190 80` (HDR_CLL) and `2a 1a 02 ae1451ec 43d7b0a4 26660f5c 500d5439 0003e800 00000052 80` (HDR_MDCV, primaries in **R, G, B** order, 0.16 / 24.8 / 18.14 fixed point → R(0.68,0.32) G(0.265,0.69) B(0.15,0.06) WP(0.3127,0.329) max 1000, min 0.005005). Present only in keyframe samples (0 and 5), each sample's OBUs have `obu_has_size_field=1`, no temporal delimiter inside MP4 samples.
- **`mdcv`/`clli` boxes**: written by ffmpeg's mov/mp4 muxer only when the encoder exports coded side data (re-encode with libx265, not stream copy). Payload layout identical to SEI 137/144: `mdcv` = 24 bytes `33c286c41d4c0bb884d03e803d134042 00989680 00000032`, `clli` = `03e8 0190`. They are children of the `hvc1` sample entry (already walked by `VisualSampleEntryDecoder`).
- **Sample layouts**: HEVC fixture sample 0 = [SEI144, SEI137, SEI5, SEI4, IDR]; samples 12–14 = [TRAIL] only; sample 10 = [SEI144, SEI137, SEI5, SEI4, CRA]. `stss` = (1, 11, 21). VPS/SPS/PPS live in `hvcC`, not in samples. `hevc_box.mp4` has B-frames: `ctts` v0 = (1,1024),(1,2048),(1,1024),(1,0),… with timescale 15360 and `stts` delta 512.

## File structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/com/multiviewer/parser/HdrMetadataModel.kt` (new) | `VideoCodec`, `MetadataSource`, `MasteringDisplay`, `ContentLightLevel`, `Hdr10PlusWindow`, `Hdr10PlusMetadata`, `DecodedMetadata`, `MetadataUnit` |
| `.../parser/Hdr10PlusDecoder.kt` (new) | `isHdr10PlusT35`, `decodeHdr10Plus` |
| `.../parser/SeiParser.kt` (new) | `SeiMessage`, `splitSeiMessages` |
| `.../parser/SeiPayloadDecoders.kt` (new) | `seiPayloadTypeName`, `decodeSeiPayload`, `decodeMasteringDisplayPayload`, `decodeContentLightLevelPayload`, `decodeItuT35` |
| `.../parser/Av1MetadataObu.kt` (new) | `av1MetadataTypeName`, `decodeAv1MetadataPayload`, `decodeAv1Mdcv` |
| `.../parser/MdcvClliBoxDecoder.kt` (new) | `MdcvBoxDecoder`, `ClliBoxDecoder`, `ContainerHdrStatic`, `readContainerHdrStatic` |
| `.../parser/Decoders.kt` (modify) | register `mdcv`, `clli` |
| `.../parser/IsoSampleTable.kt` (new) | shared stts/stsz/stco/stsc/stss/ctts readers, `VideoSample`, `VideoSampleTable`, `buildVideoSampleTable` |
| `.../parser/Av2SampleIndexer.kt` (modify) | use the shared readers |
| `.../parser/FrameMetadataReader.kt` (new) | `detectVideoCodec`, `readFrameMetadataUnits` |
| `.../parser/MetadataFormatting.kt` (new) | `summarizeMetadata`, `describeMetadata` |
| `.../parser/HdrMetadataScanner.kt` (new) | `FrameHdrRecord`, `HdrScanResult`, `scanHdrMetadata` |
| `.../parser/HdrAnomalyRules.kt` (new) | `HdrSeverity`, `HdrRule`, `HdrAnomaly`, `HdrThresholds`, `HdrVerdict`, `detectHdrAnomalies`, `hdrVerdict`, `sameMasteringDisplay` |
| `.../ui/HdrMetadataExport.kt` (new) | `buildHdrFramesCsv`, `buildHdrAnomaliesCsv` |
| `.../ui/HdrTrendGraph.kt` (new) | multi-series graph with reference lines, anomaly markers, click-to-select |
| `.../ui/HdrMetadataWindow.kt` (new) | the analysis window |
| `.../ui/ImageInspectorUI.kt` (modify) | "SEI / Metadata" section in `DetailPropertiesTabContent` |
| `.../ui/I18n.kt`, `.../Main.kt` (modify) | menu item + window wiring |
| `app/src/test/resources/hdr/*.mp4` (new) | 4 real fixtures |
| `docs/validation/hdr-fixtures/` (new) | fixture generator scripts |
| tests under `app/src/test/kotlin/com/multiviewer/parser/` and `.../ui/` | one test file per new source file |

---

### Task 1: Real HDR fixtures and generator scripts

**Files:**
- Create: `docs/validation/hdr-fixtures/inject_hdr10plus.py`
- Create: `docs/validation/hdr-fixtures/make_fixtures.sh`
- Create: `docs/validation/hdr-fixtures/README.md`
- Create (generated): `app/src/test/resources/hdr/hevc_hdr10plus.mp4`, `hevc_box.mp4`, `h264_hdr.mp4`, `av1_hdr.mp4`

**Interfaces:**
- Produces: the four fixture files, loaded by later tests as `File(javaClass.getResource("/hdr/<name>")!!.toURI())`.

- [ ] **Step 1: Write the HDR10+ injector**

`docs/validation/hdr-fixtures/inject_hdr10plus.py`:

```python
"""Inserts a crafted HDR10+ (ST 2094-40) prefix SEI before the first slice of every HEVC access unit.

Usage: python3 -I inject_hdr10plus.py in.hevc out.hevc
Frames 12-14 (decode order) get no HDR10+ SEI; frame 17 gets an abrupt MaxSCL jump. The
bitstream layout was cross-checked field by field with ffprobe 8.1.2 -show_frames.
"""
import re
import sys


class BitWriter:
    def __init__(self):
        self.bits = []

    def u(self, n, v):
        for i in range(n - 1, -1, -1):
            self.bits.append((v >> i) & 1)

    def to_bytes(self):
        b = self.bits + [0] * ((-len(self.bits)) % 8)
        return bytes(int(''.join(map(str, b[i:i + 8])), 2) for i in range(0, len(b), 8))


def t35(maxscl, avg, targeted):
    w = BitWriter()
    w.u(8, 0xB5); w.u(16, 0x003C); w.u(16, 0x0001); w.u(8, 4)  # country, provider, oriented, app id
    w.u(8, 1)          # application_version
    w.u(2, 1)          # num_windows
    w.u(27, targeted)  # targeted_system_display_maximum_luminance
    w.u(1, 0)          # targeted_system_display_actual_peak_luminance_flag
    for v in maxscl:
        w.u(17, v)
    w.u(17, avg)
    pcts = [(1, 0), (5, 10), (10, 50), (25, 200), (50, avg), (75, 800), (90, 1500), (95, 2500), (99, maxscl[1])]
    w.u(4, len(pcts))
    for p, v in pcts:
        w.u(7, p); w.u(17, v)
    w.u(10, 0)         # fraction_bright_pixels
    w.u(1, 0)          # mastering_display_actual_peak_luminance_flag
    w.u(1, 1)          # tone_mapping_flag
    w.u(12, 1000); w.u(12, 1200); w.u(4, 3)
    for a in (300, 600, 900):
        w.u(10, a)
    w.u(1, 0)          # color_saturation_mapping_flag
    return w.to_bytes()


def add_emulation_prevention(rbsp):
    out = bytearray(); zeros = 0
    for b in rbsp:
        if zeros >= 2 and b <= 3:
            out.append(3); zeros = 0
        out.append(b)
        zeros = zeros + 1 if b == 0 else 0
    return bytes(out)


def sei_nal(payload):
    body = bytearray([4])  # payloadType 4 = user_data_registered_itu_t_t35
    n = len(payload)
    while n >= 255:
        body.append(255); n -= 255
    body.append(n)
    body += payload
    body.append(0x80)  # rbsp_trailing_bits
    return b'\x00\x00\x00\x01' + bytes([0x4E, 0x01]) + add_emulation_prevention(bytes(body))


def main(src, dst):
    data = open(src, 'rb').read()
    starts = [m.start() for m in re.finditer(b'\x00\x00\x01', data)]
    nals = []
    for i, s in enumerate(starts):
        b = s - 1 if s > 0 and data[s - 1] == 0 else s
        if i + 1 < len(starts):
            n = starts[i + 1]
            e = n - 1 if data[n - 1] == 0 else n
        else:
            e = len(data)
        nals.append(data[b:e])
    out = bytearray(); frame = 0
    for nal in nals:
        h = nal.index(b'\x00\x00\x01') + 3
        nal_type = (nal[h] >> 1) & 0x3F
        if nal_type < 32 and (nal[h + 2] & 0x80):  # VCL with first_slice_segment_in_pic_flag
            if frame not in (12, 13, 14):
                base = 9000 if frame == 17 else 4000 + frame * 20
                out += sei_nal(t35([base, base + 500, base - 300], base // 4, 1000))
            frame += 1
        out += nal
    open(dst, 'wb').write(out)
    print('frames', frame)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
```

- [ ] **Step 2: Write the fixture script**

`docs/validation/hdr-fixtures/make_fixtures.sh`:

```bash
#!/usr/bin/env bash
# Regenerates app/src/test/resources/hdr/*.mp4. Needs ffmpeg (libx265, libx264, libsvtav1).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/../../../app/src/test/resources/hdr"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"
MD='G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,50)'
SRC='testsrc2=size=320x240:rate=30'

# 1. HEVC + in-band MDCV/CLL SEI + crafted HDR10+ (no B-frames, keyint 10, frames 12-14 missing HDR10+)
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "$SRC" -frames:v 30 -pix_fmt yuv420p10le -c:v libx265 \
  -x265-params "keyint=10:min-keyint=10:bframes=0:master-display=$MD:max-cll=1000,400:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:info=1:log-level=error" \
  -f hevc "$WORK/hdr.hevc"
python3 -I "$HERE/inject_hdr10plus.py" "$WORK/hdr.hevc" "$WORK/hdr10p.hevc"
ffmpeg -hide_banner -loglevel error -y -r 30 -i "$WORK/hdr10p.hevc" -c copy -tag:v hvc1 "$OUT/hevc_hdr10plus.mp4"

# 2. HEVC with container mdcv/clli boxes and B-frames (re-encode so libx265 exports coded side data)
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "$SRC" -frames:v 30 -pix_fmt yuv420p10le -c:v libx265 \
  -x265-params "keyint=10:min-keyint=10:bframes=2:master-display=$MD:max-cll=1000,400:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:log-level=error" \
  -tag:v hvc1 "$WORK/hevc_hdr.mp4"
ffmpeg -hide_banner -loglevel error -y -i "$WORK/hevc_hdr.mp4" -pix_fmt yuv420p10le -c:v libx265 -x265-params "keyint=10:log-level=error" \
  -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc -tag:v hvc1 "$OUT/hevc_box.mp4"

# 3. H.264 with MDCV/CLL SEI
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "$SRC" -frames:v 10 -pix_fmt yuv420p10le -c:v libx264 \
  -x264-params "keyint=5:bframes=0:mastering-display=$MD:cll=1000,400" "$OUT/h264_hdr.mp4"

# 4. AV1 with HDR_MDCV/HDR_CLL metadata OBUs
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "$SRC" -frames:v 10 -pix_fmt yuv420p10le -c:v libsvtav1 \
  -svtav1-params "keyint=5:mastering-display=G(0.265,0.690)B(0.150,0.060)R(0.680,0.320)WP(0.3127,0.3290)L(1000,0.005):content-light=1000,400" \
  -color_primaries bt2020 -color_trc smpte2084 -colorspace bt2020nc "$OUT/av1_hdr.mp4" 2>/dev/null
ls -l "$OUT"
```

`docs/validation/hdr-fixtures/README.md`:

```markdown
# HDR / SEI test fixtures

`make_fixtures.sh` regenerates `app/src/test/resources/hdr/*.mp4` (ffmpeg 8.1.2 with libx265 4.2,
libx264 and libsvtav1 were used originally). Homebrew x265 is built without HDR10+ support, so
`inject_hdr10plus.py` writes the ST 2094-40 SEI itself; its layout was verified against
`ffprobe -show_frames`. Note that ffprobe repeats the previous frame's HDR10+ values on frames
that carry none, so it cannot be used to check for missing HDR10+.

| File | Content |
|---|---|
| hevc_hdr10plus.mp4 | 30 frames, keyint 10, no B-frames; in-band MDCV/CLL on sync samples; HDR10+ on every frame except 12-14; MaxSCL jump at frame 17; no mdcv/clli boxes |
| hevc_box.mp4 | 30 frames with B-frames (ctts); `mdcv`/`clli` boxes in the `hvc1` sample entry |
| h264_hdr.mp4 | 10 frames, keyint 5; MDCV/CLL SEI on IDR samples |
| av1_hdr.mp4 | 10 frames, keyint 5; HDR_MDCV/HDR_CLL metadata OBUs on keyframes |
```

- [ ] **Step 3: Generate and sanity-check**

Run: `chmod +x docs/validation/hdr-fixtures/make_fixtures.sh && docs/validation/hdr-fixtures/make_fixtures.sh`
Expected: prints `frames 30`, then lists 4 files, each under 60 KB.

Run: `ffprobe -v error -show_streams -select_streams v app/src/test/resources/hdr/hevc_box.mp4 | grep -c side_data_type`
Expected: `2` (mastering display + content light level, i.e. `mdcv`/`clli` boxes exist).

- [ ] **Step 4: Commit**

```bash
git add docs/validation/hdr-fixtures app/src/test/resources/hdr
git commit -m "test: add real HDR/SEI fixtures and generator scripts

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Metadata model and HDR10+ decoder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/HdrMetadataModel.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/Hdr10PlusDecoder.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/TestBitWriter.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/Hdr10PlusDecoderTest.kt`

**Interfaces:**
- Produces: every type in `HdrMetadataModel.kt` (below, verbatim); `fun isHdr10PlusT35(t35: ByteArray): Boolean`; `fun decodeHdr10Plus(t35: ByteArray): Hdr10PlusMetadata?` (input starts at `itu_t_t35_country_code`; trailing bytes after the last field are ignored); test helpers `class TestBitWriter { fun u(bits: Int, value: Long): TestBitWriter; fun toBytes(): ByteArray }` and `fun hex(s: String): ByteArray`.

- [ ] **Step 1: Write the model**

`HdrMetadataModel.kt`:

```kotlin
package com.multiviewer.parser

enum class VideoCodec { H264, HEVC, AV1 }

enum class MetadataSource(val label: String) {
    H264_SEI("SEI"),
    HEVC_PREFIX_SEI("Prefix SEI"),
    HEVC_SUFFIX_SEI("Suffix SEI"),
    AV1_METADATA_OBU("Metadata OBU"),
    HEVC_UNSPECIFIED_NAL("NAL"),
    SAMPLE_STRUCTURE("Sample"),
}

// Physical units: CIE 1931 chromaticity and cd/m^2. The *Step fields carry the source encoding's
// quantization step (SEI / mdcv: 0.00002 chroma, 0.0001 cd/m^2; AV1 HDR_MDCV: 0.16 chroma, 24.8 max
// and 18.14 min luminance fixed point) so differently-encoded values can be compared within one
// step of the coarser encoding -- see sameMasteringDisplay.
data class MasteringDisplay(
    val redX: Double, val redY: Double,
    val greenX: Double, val greenY: Double,
    val blueX: Double, val blueY: Double,
    val whiteX: Double, val whiteY: Double,
    val maxLuminance: Double, val minLuminance: Double,
    val chromaStep: Double, val maxLuminanceStep: Double, val minLuminanceStep: Double,
)

data class ContentLightLevel(val maxCll: Int, val maxFall: Int)

// maxscl / averageMaxrgb / distribution percentiles are in the bitstream's 0.1 cd/m^2 units.
data class Hdr10PlusWindow(
    val maxscl: List<Int>,
    val averageMaxrgb: Int,
    val distribution: List<Pair<Int, Int>>,
    val fractionBrightPixels: Int,
    val toneMappingFlag: Boolean,
    val kneePointX: Int?,
    val kneePointY: Int?,
    val bezierCurveAnchors: List<Int>,
) {
    val maxSclNits: Double get() = maxscl.max() / 10.0
    val averageMaxrgbNits: Double get() = averageMaxrgb / 10.0
}

data class Hdr10PlusMetadata(
    val applicationVersion: Int,
    val numWindows: Int,
    val targetedSystemDisplayMaximumLuminance: Long,
    val windows: List<Hdr10PlusWindow>,
    val colorSaturationWeight: Int?,
)

sealed interface DecodedMetadata {
    data class Mastering(val value: MasteringDisplay) : DecodedMetadata
    data class LightLevel(val value: ContentLightLevel) : DecodedMetadata
    data class Hdr10Plus(val value: Hdr10PlusMetadata) : DecodedMetadata
    data class ItuT35(val countryCode: Int, val providerCode: Int?, val hexPreview: String) : DecodedMetadata
    data class UserDataUnregistered(val uuid: String, val asciiPreview: String) : DecodedMetadata
    data class RecoveryPoint(val recoveryCount: Int, val exactMatch: Boolean, val brokenLink: Boolean) : DecodedMetadata
    data class AlternativeTransfer(val preferredTransferCharacteristics: Int) : DecodedMetadata
    data object PresenceOnly : DecodedMetadata
    data class Malformed(val reason: String) : DecodedMetadata
}

// One SEI message, AV1 metadata OBU, or other reported unit. unitOffset/unitSize locate the
// containing NAL unit (from its header byte, length prefix excluded) or the whole OBU, so the hex
// viewer can highlight it -- SEI payload offsets themselves shift once emulation prevention is
// removed, so the containing unit is the honest range to show.
data class MetadataUnit(
    val source: MetadataSource,
    val typeCode: Int,
    val name: String,
    val unitOffset: Long,
    val unitSize: Long,
    val payloadSize: Int,
    val decoded: DecodedMetadata,
)
```

- [ ] **Step 2: Write test helpers**

`app/src/test/kotlin/com/multiviewer/parser/TestBitWriter.kt`:

```kotlin
package com.multiviewer.parser

class TestBitWriter {
    private val bits = ArrayList<Int>()

    fun u(count: Int, value: Long): TestBitWriter {
        for (i in count - 1 downTo 0) bits += ((value shr i) and 1L).toInt()
        return this
    }

    fun toBytes(): ByteArray {
        val padded = bits + List((8 - bits.size % 8) % 8) { 0 }
        return ByteArray(padded.size / 8) { byteIndex ->
            var b = 0
            for (k in 0 until 8) b = (b shl 1) or padded[byteIndex * 8 + k]
            b.toByte()
        }
    }
}

fun hex(s: String): ByteArray {
    val clean = s.filter { !it.isWhitespace() }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
```

- [ ] **Step 3: Write the failing tests**

`Hdr10PlusDecoderTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Hdr10PlusDecoderTest {
    // Real payload from the hevc_hdr10plus.mp4 fixture (frame 0); ffprobe 8.1.2 decodes the same values.
    private val frame0 = hex("b5003c0001040140001f401f401194073a00fa240800002800285000c8c80321900fa2580c82d01772f82713184650004fa12c0d2c963840")

    @Test
    fun `decodes every field of a real single-window payload`() {
        val m = assertNotNull(decodeHdr10Plus(frame0))
        assertEquals(1, m.applicationVersion)
        assertEquals(1, m.numWindows)
        assertEquals(1000L, m.targetedSystemDisplayMaximumLuminance)
        val w = m.windows.single()
        assertEquals(listOf(4000, 4500, 3700), w.maxscl)
        assertEquals(1000, w.averageMaxrgb)
        assertEquals(
            listOf(1 to 0, 5 to 10, 10 to 50, 25 to 200, 50 to 1000, 75 to 800, 90 to 1500, 95 to 2500, 99 to 4500),
            w.distribution,
        )
        assertEquals(0, w.fractionBrightPixels)
        assertTrue(w.toneMappingFlag)
        assertEquals(1000, w.kneePointX)
        assertEquals(1200, w.kneePointY)
        assertEquals(listOf(300, 600, 900), w.bezierCurveAnchors)
        assertNull(m.colorSaturationWeight)
        assertEquals(450.0, w.maxSclNits)
        assertEquals(100.0, w.averageMaxrgbNits)
    }

    @Test
    fun `trailing bytes after the last field are ignored`() {
        assertNotNull(decodeHdr10Plus(frame0 + byteArrayOf(0x80.toByte())))
    }

    @Test
    fun `header check rejects other T35 payloads`() {
        assertTrue(isHdr10PlusT35(frame0))
        val dolby = frame0.copyOf().also { it[2] = 0x3B } // provider 0x003B instead of 0x003C
        assertFalse(isHdr10PlusT35(dolby))
        assertNull(decodeHdr10Plus(dolby))
    }

    @Test
    fun `truncated payload returns null`() {
        assertNull(decodeHdr10Plus(frame0.copyOf(20)))
    }

    @Test
    fun `two windows, peak luminance matrices and saturation weight`() {
        val w = TestBitWriter()
            .u(8, 0xB5).u(16, 0x3C).u(16, 1).u(8, 4)
            .u(8, 1).u(2, 2)
        // window 1 geometry: 6 x u16, u8, 3 x u16, u1
        repeat(6) { w.u(16, 7) }
        w.u(8, 0); repeat(3) { w.u(16, 9) }; w.u(1, 0)
        w.u(27, 4000).u(1, 1).u(5, 2).u(5, 2); repeat(4) { w.u(4, 5) } // targeted + 2x2 matrix
        listOf(listOf(100L, 200L, 300L, 150L), listOf(10L, 20L, 30L, 15L)).forEach { (r, g, b, avg) ->
            w.u(17, r).u(17, g).u(17, b).u(17, avg).u(4, 1).u(7, 99).u(17, b).u(10, 3)
        }
        w.u(1, 1).u(5, 1).u(5, 1).u(4, 2) // mastering_display_actual_peak 1x1
        w.u(1, 0)                          // window 0: no tone mapping
        w.u(1, 1).u(12, 5).u(12, 6).u(4, 1).u(10, 7) // window 1 tone mapping
        w.u(1, 1).u(6, 33)                 // color saturation
        val m = assertNotNull(decodeHdr10Plus(w.toBytes()))
        assertEquals(2, m.numWindows)
        assertEquals(4000L, m.targetedSystemDisplayMaximumLuminance)
        assertEquals(listOf(100, 200, 300), m.windows[0].maxscl)
        assertEquals(15, m.windows[1].averageMaxrgb)
        assertEquals(listOf(99 to 30), m.windows[1].distribution)
        assertFalse(m.windows[0].toneMappingFlag)
        assertNull(m.windows[0].kneePointX)
        assertEquals(listOf(7), m.windows[1].bezierCurveAnchors)
        assertEquals(33, m.colorSaturationWeight)
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.Hdr10PlusDecoderTest'`
Expected: FAIL — compilation error, `decodeHdr10Plus` unresolved.

- [ ] **Step 5: Implement the decoder**

`Hdr10PlusDecoder.kt`:

```kotlin
package com.multiviewer.parser

private const val T35_COUNTRY_USA = 0xB5
private const val T35_PROVIDER_SAMSUNG = 0x003C
private const val HDR10PLUS_PROVIDER_ORIENTED = 0x0001
private const val HDR10PLUS_APPLICATION_IDENTIFIER = 4
private const val HDR10PLUS_HEADER_BYTES = 6

// `t35` starts at itu_t_t35_country_code. HDR10+ is identified by country 0xB5, terminal provider
// 0x003C, provider-oriented code 0x0001 and application_identifier 4 (ST 2094-40 / CTA-861-G).
fun isHdr10PlusT35(t35: ByteArray): Boolean =
    t35.size >= HDR10PLUS_HEADER_BYTES &&
        (t35[0].toInt() and 0xFF) == T35_COUNTRY_USA &&
        u16At(t35, 1) == T35_PROVIDER_SAMSUNG &&
        u16At(t35, 3) == HDR10PLUS_PROVIDER_ORIENTED &&
        (t35[5].toInt() and 0xFF) == HDR10PLUS_APPLICATION_IDENTIFIER

private fun u16At(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

private class WindowStats(val maxscl: List<Int>, val averageMaxrgb: Int, val distribution: List<Pair<Int, Int>>, val fractionBrightPixels: Int)

// Field order follows ST 2094-40 Annex and matches ffmpeg's ff_parse_itu_t_t35_to_dynamic_hdr10_plus;
// verified against ffprobe 8.1.2 on the hevc_hdr10plus.mp4 fixture. Returns null when the header
// doesn't match or the payload ends early (BitReader throws IllegalStateException past its end).
fun decodeHdr10Plus(t35: ByteArray): Hdr10PlusMetadata? {
    if (!isHdr10PlusT35(t35)) return null
    return try {
        val r = BitReader(t35, startByteOffset = HDR10PLUS_HEADER_BYTES)
        val applicationVersion = r.readBits(8)
        val numWindows = r.readBits(2)
        if (numWindows < 1) return null
        repeat(numWindows - 1) {
            repeat(6) { r.readBits(16) } // window corners and ellipse centre
            r.readBits(8)                // rotation_angle
            repeat(3) { r.readBits(16) } // ellipse axes
            r.readBits(1)                // overlap_process_option
        }
        val targeted = r.readBits(27).toLong()
        if (r.readFlag()) skipPeakLuminanceMatrix(r)
        val stats = List(numWindows) {
            val maxscl = List(3) { r.readBits(17) }
            val average = r.readBits(17)
            val count = r.readBits(4)
            val distribution = List(count) {
                val percentage = r.readBits(7)
                percentage to r.readBits(17)
            }
            WindowStats(maxscl, average, distribution, r.readBits(10))
        }
        if (r.readFlag()) skipPeakLuminanceMatrix(r)
        val windows = stats.map { s ->
            val toneMapping = r.readFlag()
            var kneeX: Int? = null
            var kneeY: Int? = null
            var anchors = emptyList<Int>()
            if (toneMapping) {
                kneeX = r.readBits(12)
                kneeY = r.readBits(12)
                val anchorCount = r.readBits(4)
                anchors = List(anchorCount) { r.readBits(10) }
            }
            Hdr10PlusWindow(s.maxscl, s.averageMaxrgb, s.distribution, s.fractionBrightPixels, toneMapping, kneeX, kneeY, anchors)
        }
        val saturation = if (r.readFlag()) r.readBits(6) else null
        Hdr10PlusMetadata(applicationVersion, numWindows, targeted, windows, saturation)
    } catch (e: IllegalStateException) {
        null
    }
}

private fun skipPeakLuminanceMatrix(r: BitReader) {
    val rows = r.readBits(5)
    val cols = r.readBits(5)
    repeat(rows * cols) { r.readBits(4) }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.Hdr10PlusDecoderTest'`
Expected: PASS (5 tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/HdrMetadataModel.kt app/src/main/kotlin/com/multiviewer/parser/Hdr10PlusDecoder.kt app/src/test/kotlin/com/multiviewer/parser/TestBitWriter.kt app/src/test/kotlin/com/multiviewer/parser/Hdr10PlusDecoderTest.kt
git commit -m "feat: decode HDR10+ ST 2094-40 metadata

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: SEI message splitting and payload decoders

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/SeiParser.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/SeiPayloadDecoders.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/SeiParserTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/SeiPayloadDecodersTest.kt`

**Interfaces:**
- Consumes: `removeEmulationPreventionBytes(ByteArray): ByteArray` (existing, `NalEmulationPrevention.kt`); Task 2 model; `decodeHdr10Plus`, `isHdr10PlusT35`.
- Produces:
  - `data class SeiMessage(val payloadType: Int, val payloadSize: Int, val payload: ByteArray, val malformedReason: String? = null)`
  - `const val MAX_SEI_PAYLOAD_BYTES = 65536`
  - `fun splitSeiMessages(nal: ByteArray, nalHeaderSize: Int, maxPayloadBytes: Int = MAX_SEI_PAYLOAD_BYTES): List<SeiMessage>` — `nal` is the full NAL incl. header, emulation prevention still present.
  - `fun seiPayloadTypeName(type: Int): String`
  - `fun decodeSeiPayload(codec: VideoCodec, message: SeiMessage): DecodedMetadata`
  - `fun decodeMasteringDisplayPayload(p: ByteArray): MasteringDisplay?` (24-byte SEI-137/`mdcv` layout)
  - `fun decodeContentLightLevelPayload(p: ByteArray): ContentLightLevel?` (4-byte SEI-144/`clli` layout)
  - `fun decodeItuT35(t35: ByteArray): DecodedMetadata`
  - `fun hexPreview(bytes: ByteArray, maxBytes: Int = 32): String`

- [ ] **Step 1: Write the failing tests**

`SeiParserTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SeiParserTest {
    @Test
    fun `real x265 CLL SEI splits into one message`() {
        val messages = splitSeiMessages(hex("4e01 90 04 03e8 0190 80"), nalHeaderSize = 2)
        val m = messages.single()
        assertEquals(144, m.payloadType)
        assertEquals(4, m.payloadSize)
        assertEquals(hex("03e80190").toList(), m.payload.toList())
        assertNull(m.malformedReason)
    }

    @Test
    fun `emulation prevention is removed before splitting`() {
        val nal = hex("4e01 89 18 33c286c41d4c0bb884d03e803d134042 0098968000000300328000")
        val m = splitSeiMessages(nal, 2).single()
        assertEquals(137, m.payloadType)
        assertEquals(24, m.payload.size)
        assertEquals(hex("0098968000000032").toList(), m.payload.copyOfRange(16, 24).toList())
    }

    @Test
    fun `two messages in one NAL`() {
        val nal = hex("4e01 8918 33c286c41d4c0bb884d03e803d134042 009896800000030032 9004 03e80190 80")
        assertEquals(listOf(137, 144), splitSeiMessages(nal, 2).map { it.payloadType })
    }

    @Test
    fun `H264 one-byte NAL header`() {
        val m = splitSeiMessages(hex("06 90 04 03e8 0190 80"), nalHeaderSize = 1).single()
        assertEquals(144, m.payloadType)
    }

    @Test
    fun `0xFF-extended payloadSize`() {
        val nal = hex("4e01 05 ff2d") + ByteArray(300) { 0x41 } + hex("80")
        val m = splitSeiMessages(nal, 2).single()
        assertEquals(5, m.payloadType)
        assertEquals(300, m.payloadSize)
    }

    @Test
    fun `payload capped but declared size kept`() {
        val nal = hex("4e01 05 ff2d") + ByteArray(300) { 0x41 } + hex("80")
        val m = splitSeiMessages(nal, 2, maxPayloadBytes = 16).single()
        assertEquals(300, m.payloadSize)
        assertEquals(16, m.payload.size)
    }

    @Test
    fun `size past the NAL end is reported as malformed and stops`() {
        val m = splitSeiMessages(hex("4e01 89 18 33c2"), 2).single()
        assertEquals(137, m.payloadType)
        assertNotNull(m.malformedReason)
    }

    @Test
    fun `NAL with only a header yields nothing`() {
        assertEquals(emptyList(), splitSeiMessages(hex("4e01"), 2))
    }
}
```

`SeiPayloadDecodersTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SeiPayloadDecodersTest {
    private fun sei(type: Int, payload: ByteArray) = SeiMessage(type, payload.size, payload)

    @Test
    fun `mastering display uses G B R order and SEI units`() {
        val md = assertNotNull(decodeMasteringDisplayPayload(hex("33c286c41d4c0bb884d03e803d134042 00989680 00000032")))
        assertEquals(0.68, md.redX, 1e-9); assertEquals(0.32, md.redY, 1e-9)
        assertEquals(0.265, md.greenX, 1e-9); assertEquals(0.69, md.greenY, 1e-9)
        assertEquals(0.15, md.blueX, 1e-9); assertEquals(0.06, md.blueY, 1e-9)
        assertEquals(0.3127, md.whiteX, 1e-9); assertEquals(0.329, md.whiteY, 1e-9)
        assertEquals(1000.0, md.maxLuminance, 1e-9); assertEquals(0.005, md.minLuminance, 1e-9)
        assertEquals(0.00002, md.chromaStep); assertEquals(0.0001, md.maxLuminanceStep)
    }

    @Test
    fun `short mastering display payload is malformed`() {
        assertIs<DecodedMetadata.Malformed>(decodeSeiPayload(VideoCodec.HEVC, sei(137, ByteArray(10))))
    }

    @Test
    fun `content light level`() {
        val d = assertIs<DecodedMetadata.LightLevel>(decodeSeiPayload(VideoCodec.H264, sei(144, hex("03e80190"))))
        assertEquals(ContentLightLevel(1000, 400), d.value)
    }

    @Test
    fun `T35 HDR10plus payload decodes to Hdr10Plus`() {
        val t35 = hex("b5003c0001040140001f401f401194073a00fa240800002800285000c8c80321900fa2580c82d01772f82713184650004fa12c0d2c963840")
        val d = assertIs<DecodedMetadata.Hdr10Plus>(decodeSeiPayload(VideoCodec.HEVC, sei(4, t35)))
        assertEquals(1000L, d.value.targetedSystemDisplayMaximumLuminance)
    }

    @Test
    fun `other T35 payload keeps country and provider`() {
        val d = assertIs<DecodedMetadata.ItuT35>(decodeSeiPayload(VideoCodec.HEVC, sei(4, hex("b5 0031 47413934"))))
        assertEquals(0xB5, d.countryCode)
        assertEquals(0x31, d.providerCode)
    }

    @Test
    fun `truncated HDR10plus header is malformed rather than generic T35`() {
        val t35 = hex("b5003c0001040140")
        assertIs<DecodedMetadata.Malformed>(decodeSeiPayload(VideoCodec.HEVC, sei(4, t35)))
    }

    @Test
    fun `user data unregistered formats the x265 UUID and ASCII`() {
        val payload = hex("2ca2de09b51747dbbb55a4fe7fc2fc4e") + "x265 (build 216)".toByteArray() + byteArrayOf(0x00)
        val d = assertIs<DecodedMetadata.UserDataUnregistered>(decodeSeiPayload(VideoCodec.HEVC, sei(5, payload)))
        assertEquals("2ca2de09-b517-47db-bb55-a4fe7fc2fc4e", d.uuid)
        assertEquals("x265 (build 216).", d.asciiPreview)
    }

    @Test
    fun `recovery point differs per codec`() {
        // H.264: recovery_frame_cnt ue(v)=0 ('1'), exact_match=1, broken_link=0, changing_slice_group_idc=00 -> 1 1 0 00 + pad
        val h264 = assertIs<DecodedMetadata.RecoveryPoint>(decodeSeiPayload(VideoCodec.H264, sei(6, byteArrayOf(0b11000000.toByte()))))
        assertEquals(DecodedMetadata.RecoveryPoint(0, exactMatch = true, brokenLink = false), h264)
        // HEVC: recovery_poc_cnt se(v)=-1 (codeNum 2 = '011'), exact_match=0, broken_link=1 -> 011 0 1 + pad
        val hevc = assertIs<DecodedMetadata.RecoveryPoint>(decodeSeiPayload(VideoCodec.HEVC, sei(6, byteArrayOf(0b01101000.toByte()))))
        assertEquals(DecodedMetadata.RecoveryPoint(-1, exactMatch = false, brokenLink = true), hevc)
    }

    @Test
    fun `alternative transfer and unknown types`() {
        assertEquals(DecodedMetadata.AlternativeTransfer(18), decodeSeiPayload(VideoCodec.HEVC, sei(147, byteArrayOf(18))))
        assertEquals(DecodedMetadata.PresenceOnly, decodeSeiPayload(VideoCodec.HEVC, sei(1, byteArrayOf(1, 2))))
        assertEquals("pic_timing", seiPayloadTypeName(1))
        assertEquals("mastering_display_colour_volume", seiPayloadTypeName(137))
        assertEquals("sei_payload_250", seiPayloadTypeName(250))
    }

    @Test
    fun `malformed message stays malformed`() {
        assertIs<DecodedMetadata.Malformed>(decodeSeiPayload(VideoCodec.HEVC, SeiMessage(137, 24, ByteArray(2), "cut")))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.SeiParserTest' --tests 'com.multiviewer.parser.SeiPayloadDecodersTest'`
Expected: FAIL — compilation errors, `splitSeiMessages` / `decodeSeiPayload` unresolved.

- [ ] **Step 3: Implement `SeiParser.kt`**

```kotlin
package com.multiviewer.parser

const val MAX_SEI_PAYLOAD_BYTES = 64 * 1024

// payload holds at most maxPayloadBytes of RBSP (emulation prevention removed); payloadSize is the
// declared size. Compare payload contents with toList() -- ByteArray has identity equality.
data class SeiMessage(
    val payloadType: Int,
    val payloadSize: Int,
    val payload: ByteArray,
    val malformedReason: String? = null,
)

// Splits one SEI NAL (H.264 7.3.2.3 / HEVC 7.3.2.4) into sei_message()s. payloadType and
// payloadSize are each a run of 0xFF bytes (255 each) ended by one byte < 0xFF. Stops at
// rbsp_trailing_bits (0x80 followed only by zero bytes) or at the first size that runs past the NAL.
fun splitSeiMessages(nal: ByteArray, nalHeaderSize: Int, maxPayloadBytes: Int = MAX_SEI_PAYLOAD_BYTES): List<SeiMessage> {
    if (nal.size <= nalHeaderSize) return emptyList()
    val rbsp = removeEmulationPreventionBytes(nal.copyOfRange(nalHeaderSize, nal.size))
    val messages = mutableListOf<SeiMessage>()
    var pos = 0
    while (moreRbspData(rbsp, pos)) {
        var type = 0
        while (pos < rbsp.size && rbsp[pos] == 0xFF.toByte()) { type += 255; pos++ }
        if (pos >= rbsp.size) { messages += SeiMessage(type, 0, ByteArray(0), "payloadType runs past the NAL end"); break }
        type += rbsp[pos++].toInt() and 0xFF
        var size = 0
        while (pos < rbsp.size && rbsp[pos] == 0xFF.toByte()) { size += 255; pos++ }
        if (pos >= rbsp.size) { messages += SeiMessage(type, 0, ByteArray(0), "payloadSize runs past the NAL end"); break }
        size += rbsp[pos++].toInt() and 0xFF
        val available = rbsp.size - pos
        if (size > available) {
            messages += SeiMessage(
                type, size, rbsp.copyOfRange(pos, pos + minOf(available, maxPayloadBytes)),
                "payloadSize $size exceeds the $available bytes left in the NAL",
            )
            break
        }
        messages += SeiMessage(type, size, rbsp.copyOfRange(pos, pos + minOf(size, maxPayloadBytes)))
        pos += size
    }
    return messages
}

private fun moreRbspData(rbsp: ByteArray, pos: Int): Boolean {
    if (pos >= rbsp.size) return false
    if (rbsp[pos] != 0x80.toByte()) return true
    for (i in pos + 1 until rbsp.size) if (rbsp[i] != 0.toByte()) return true
    return false
}
```

- [ ] **Step 4: Implement `SeiPayloadDecoders.kt`**

```kotlin
package com.multiviewer.parser

private val SEI_PAYLOAD_NAMES = mapOf(
    0 to "buffering_period", 1 to "pic_timing", 2 to "pan_scan_rect", 3 to "filler_payload",
    4 to "user_data_registered_itu_t_t35", 5 to "user_data_unregistered", 6 to "recovery_point",
    9 to "scene_info", 45 to "frame_packing_arrangement", 47 to "display_orientation",
    128 to "structure_of_pictures_info", 129 to "active_parameter_sets", 130 to "decoding_unit_info",
    131 to "temporal_sub_layer_zero_index", 132 to "decoded_picture_hash", 133 to "scalable_nesting",
    134 to "region_refresh_info", 137 to "mastering_display_colour_volume", 144 to "content_light_level_info",
    147 to "alternative_transfer_characteristics", 148 to "ambient_viewing_environment",
)

fun seiPayloadTypeName(type: Int): String = SEI_PAYLOAD_NAMES[type] ?: "sei_payload_$type"

fun decodeSeiPayload(codec: VideoCodec, message: SeiMessage): DecodedMetadata {
    message.malformedReason?.let { return DecodedMetadata.Malformed(it) }
    val p = message.payload
    return try {
        when (message.payloadType) {
            137 -> decodeMasteringDisplayPayload(p)?.let { DecodedMetadata.Mastering(it) }
                ?: DecodedMetadata.Malformed("mastering_display_colour_volume needs 24 bytes, got ${p.size}")
            144 -> decodeContentLightLevelPayload(p)?.let { DecodedMetadata.LightLevel(it) }
                ?: DecodedMetadata.Malformed("content_light_level_info needs 4 bytes, got ${p.size}")
            4 -> decodeItuT35(p)
            5 -> decodeUserDataUnregistered(p)
            6 -> decodeRecoveryPoint(codec, p)
            147 -> if (p.isNotEmpty()) DecodedMetadata.AlternativeTransfer(p[0].toInt() and 0xFF)
            else DecodedMetadata.Malformed("alternative_transfer_characteristics is empty")
            else -> DecodedMetadata.PresenceOnly
        }
    } catch (e: IllegalStateException) {
        DecodedMetadata.Malformed(e.message ?: "payload ended early")
    }
}

private fun u16(p: ByteArray, at: Int): Int = ((p[at].toInt() and 0xFF) shl 8) or (p[at + 1].toInt() and 0xFF)
private fun u32(p: ByteArray, at: Int): Long = (u16(p, at).toLong() shl 16) or u16(p, at + 2).toLong()

private const val SEI_CHROMA_STEP = 0.00002
private const val SEI_LUMINANCE_STEP = 0.0001

// SEI 137 and the ISOBMFF mdcv box share this 24-byte layout: three (x, y) u16 display primaries
// in G, B, R order (what x264/x265 write and ffmpeg's parser assumes), white point (x, y) u16,
// max and min luminance u32. Verified against x265/libx264 output and ffprobe.
fun decodeMasteringDisplayPayload(p: ByteArray): MasteringDisplay? {
    if (p.size < 24) return null
    return MasteringDisplay(
        redX = u16(p, 8) * SEI_CHROMA_STEP, redY = u16(p, 10) * SEI_CHROMA_STEP,
        greenX = u16(p, 0) * SEI_CHROMA_STEP, greenY = u16(p, 2) * SEI_CHROMA_STEP,
        blueX = u16(p, 4) * SEI_CHROMA_STEP, blueY = u16(p, 6) * SEI_CHROMA_STEP,
        whiteX = u16(p, 12) * SEI_CHROMA_STEP, whiteY = u16(p, 14) * SEI_CHROMA_STEP,
        maxLuminance = u32(p, 16) * SEI_LUMINANCE_STEP, minLuminance = u32(p, 20) * SEI_LUMINANCE_STEP,
        chromaStep = SEI_CHROMA_STEP, maxLuminanceStep = SEI_LUMINANCE_STEP, minLuminanceStep = SEI_LUMINANCE_STEP,
    )
}

// SEI 144 and the ISOBMFF clli box: max_content_light_level u16, max_pic_average_light_level u16.
fun decodeContentLightLevelPayload(p: ByteArray): ContentLightLevel? =
    if (p.size < 4) null else ContentLightLevel(u16(p, 0), u16(p, 2))

// `t35` starts at itu_t_t35_country_code (shared by SEI payload 4 and AV1 ITU_T_T35 metadata).
fun decodeItuT35(t35: ByteArray): DecodedMetadata {
    if (t35.isEmpty()) return DecodedMetadata.Malformed("empty ITU-T T.35 payload")
    decodeHdr10Plus(t35)?.let { return DecodedMetadata.Hdr10Plus(it) }
    if (isHdr10PlusT35(t35)) return DecodedMetadata.Malformed("HDR10+ payload ends early")
    val country = t35[0].toInt() and 0xFF
    val providerAt = if (country == 0xFF) 2 else 1
    val provider = if (t35.size >= providerAt + 2) u16(t35, providerAt) else null
    return DecodedMetadata.ItuT35(country, provider, hexPreview(t35))
}

fun hexPreview(bytes: ByteArray, maxBytes: Int = 32): String =
    bytes.take(maxBytes).joinToString("") { "%02x".format(it) } + if (bytes.size > maxBytes) "…" else ""

private fun decodeUserDataUnregistered(p: ByteArray): DecodedMetadata {
    if (p.size < 16) return DecodedMetadata.Malformed("user_data_unregistered needs a 16-byte UUID, got ${p.size}")
    val h = p.copyOfRange(0, 16).joinToString("") { "%02x".format(it) }
    val uuid = "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    val ascii = p.copyOfRange(16, minOf(p.size, 16 + 80))
        .map { b -> val c = b.toInt() and 0xFF; if (c in 0x20..0x7E) c.toChar() else '.' }
        .joinToString("")
    return DecodedMetadata.UserDataUnregistered(uuid, ascii)
}

private fun decodeRecoveryPoint(codec: VideoCodec, p: ByteArray): DecodedMetadata {
    val r = BitReader(p)
    val count = if (codec == VideoCodec.H264) r.readUe() else r.readSe()
    val exact = r.readFlag()
    val broken = r.readFlag()
    return DecodedMetadata.RecoveryPoint(count, exact, broken)
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.SeiParserTest' --tests 'com.multiviewer.parser.SeiPayloadDecodersTest'`
Expected: PASS (18 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/SeiParser.kt app/src/main/kotlin/com/multiviewer/parser/SeiPayloadDecoders.kt app/src/test/kotlin/com/multiviewer/parser/SeiParserTest.kt app/src/test/kotlin/com/multiviewer/parser/SeiPayloadDecodersTest.kt
git commit -m "feat: split and decode H.264/HEVC SEI payloads

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: AV1 metadata OBU payload decoder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/Av1MetadataObu.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/Av1MetadataObuTest.kt`

**Interfaces:**
- Consumes: `decodeItuT35`, model types.
- Produces: `fun av1MetadataTypeName(type: Long): String`; `fun decodeAv1MetadataPayload(payload: ByteArray): Pair<Long, DecodedMetadata>` (input = OBU payload after header and `obu_size`, i.e. starting at the `metadata_type` leb128); `fun decodeAv1Mdcv(body: ByteArray): MasteringDisplay?`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Av1MetadataObuTest {
    @Test
    fun `real SVT-AV1 HDR_CLL`() {
        // OBU 2a 06 | payload 01 03e8 0190 80
        val (type, decoded) = decodeAv1MetadataPayload(hex("01 03e8 0190 80"))
        assertEquals(1L, type)
        assertEquals(ContentLightLevel(1000, 400), assertIs<DecodedMetadata.LightLevel>(decoded).value)
    }

    @Test
    fun `real SVT-AV1 HDR_MDCV uses R G B order and AV1 fixed point`() {
        val (type, decoded) = decodeAv1MetadataPayload(hex("02 ae1451ec 43d7b0a4 26660f5c 500d5439 0003e800 00000052 80"))
        assertEquals(2L, type)
        val md = assertIs<DecodedMetadata.Mastering>(decoded).value
        assertEquals(44564 / 65536.0, md.redX, 1e-12)
        assertEquals(17367 / 65536.0, md.greenX, 1e-12)
        assertEquals(9830 / 65536.0, md.blueX, 1e-12)
        assertEquals(20493 / 65536.0, md.whiteX, 1e-12)
        assertEquals(1000.0, md.maxLuminance, 1e-12)
        assertEquals(82 / 16384.0, md.minLuminance, 1e-12)
        assertEquals(1 / 256.0, md.maxLuminanceStep)
    }

    @Test
    fun `ITU_T_T35 with HDR10plus`() {
        val t35 = hex("b5003c0001040140001f401f401194073a00fa240800002800285000c8c80321900fa2580c82d01772f82713184650004fa12c0d2c963840")
        val (type, decoded) = decodeAv1MetadataPayload(hex("04") + t35 + hex("80"))
        assertEquals(4L, type)
        assertIs<DecodedMetadata.Hdr10Plus>(decoded)
    }

    @Test
    fun `short MDCV is malformed and other types are presence-only`() {
        assertIs<DecodedMetadata.Malformed>(decodeAv1MetadataPayload(hex("02 ae14")).second)
        assertEquals(DecodedMetadata.PresenceOnly, decodeAv1MetadataPayload(hex("05 00")).second)
        assertEquals("TIMECODE", av1MetadataTypeName(5))
        assertEquals("metadata_type_9", av1MetadataTypeName(9))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.Av1MetadataObuTest'`
Expected: FAIL — `decodeAv1MetadataPayload` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package com.multiviewer.parser

fun av1MetadataTypeName(type: Long): String = when (type) {
    1L -> "HDR_CLL"
    2L -> "HDR_MDCV"
    3L -> "SCALABILITY"
    4L -> "ITU_T_T35"
    5L -> "TIMECODE"
    else -> "metadata_type_$type"
}

// AV1 spec 5.8.1 metadata_obu(): metadata_type leb128, then the type-specific payload. Trailing
// bits stay in the body; every decoder below reads fixed-size fields and ignores what follows.
fun decodeAv1MetadataPayload(payload: ByteArray): Pair<Long, DecodedMetadata> {
    var type = 0L
    var pos = 0
    while (pos < payload.size && pos < 8) {
        val b = payload[pos].toInt() and 0xFF
        type = type or ((b and 0x7F).toLong() shl (7 * pos))
        pos++
        if (b and 0x80 == 0) break
    }
    val body = payload.copyOfRange(pos, payload.size)
    val decoded = when (type) {
        1L -> decodeContentLightLevelPayload(body)?.let { DecodedMetadata.LightLevel(it) }
            ?: DecodedMetadata.Malformed("HDR_CLL needs 4 bytes, got ${body.size}")
        2L -> decodeAv1Mdcv(body)?.let { DecodedMetadata.Mastering(it) }
            ?: DecodedMetadata.Malformed("HDR_MDCV needs 24 bytes, got ${body.size}")
        4L -> decodeItuT35(body)
        else -> DecodedMetadata.PresenceOnly
    }
    return type to decoded
}

private const val AV1_CHROMA_STEP = 1.0 / 65536   // 0.16 fixed point
private const val AV1_MAX_LUMINANCE_STEP = 1.0 / 256  // 24.8 fixed point
private const val AV1_MIN_LUMINANCE_STEP = 1.0 / 16384 // 18.14 fixed point

// AV1 spec 5.8.4 metadata_hdr_mdcv(): primary_chromaticity_x/y for i = 0..2 in R, G, B order,
// white point, luminance_max u32, luminance_min u32. Verified against SVT-AV1 output and ffprobe.
fun decodeAv1Mdcv(body: ByteArray): MasteringDisplay? {
    if (body.size < 24) return null
    fun u16(at: Int) = ((body[at].toInt() and 0xFF) shl 8) or (body[at + 1].toInt() and 0xFF)
    fun u32(at: Int) = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
    return MasteringDisplay(
        redX = u16(0) * AV1_CHROMA_STEP, redY = u16(2) * AV1_CHROMA_STEP,
        greenX = u16(4) * AV1_CHROMA_STEP, greenY = u16(6) * AV1_CHROMA_STEP,
        blueX = u16(8) * AV1_CHROMA_STEP, blueY = u16(10) * AV1_CHROMA_STEP,
        whiteX = u16(12) * AV1_CHROMA_STEP, whiteY = u16(14) * AV1_CHROMA_STEP,
        maxLuminance = u32(16) * AV1_MAX_LUMINANCE_STEP, minLuminance = u32(20) * AV1_MIN_LUMINANCE_STEP,
        chromaStep = AV1_CHROMA_STEP, maxLuminanceStep = AV1_MAX_LUMINANCE_STEP, minLuminanceStep = AV1_MIN_LUMINANCE_STEP,
    )
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.Av1MetadataObuTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/Av1MetadataObu.kt app/src/test/kotlin/com/multiviewer/parser/Av1MetadataObuTest.kt
git commit -m "feat: decode AV1 HDR metadata OBUs

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `mdcv` / `clli` box decoders and container static metadata

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/MdcvClliBoxDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt` (after the `BoxRegistry.register("colr", ColrBoxDecoder)` line)
- Modify: `app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt` (add `"mdcv", "clli"` to `typesThatMustHaveADecoder`)
- Test: `app/src/test/kotlin/com/multiviewer/parser/MdcvClliBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `decodeMasteringDisplayPayload`, `decodeContentLightLevelPayload`, `findFirst(BoxNode, (BoxNode) -> Boolean)` (existing, `MediaSummaryBuilder.kt`), `parseFile(File)`.
- Produces: `object MdcvBoxDecoder : BoxDecoder`, `object ClliBoxDecoder : BoxDecoder`, `data class ContainerHdrStatic(val masteringDisplay: MasteringDisplay?, val contentLightLevel: ContentLightLevel?)`, `fun readContainerHdrStatic(reader: ByteReader, root: BoxNode): ContainerHdrStatic`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MdcvClliBoxDecoderTest {
    private fun fixture(name: String) = File(javaClass.getResource("/hdr/$name")!!.toURI())

    @Test
    fun `mdcv fields and summary`() {
        val body = hex("33c286c41d4c0bb884d03e803d134042 00989680 00000032")
        val reader = byteReaderOf(body)
        val node = MdcvBoxDecoder.decode(reader, "mdcv", 0, 0, body.size.toLong(), emptyList())
        assertEquals("0.26500, 0.69000", node.fields[0].value)
        assertEquals("display_primaries G (x, y)", node.fields[0].name)
        assertEquals("0.68000, 0.32000", node.fields[2].value)
        assertEquals("1000.0000 cd/m²", node.fields[4].value)
        assertEquals("0.0050 cd/m²", node.fields[5].value)
        assertEquals("max 1000 / min 0.0050 cd/m²", node.summary)
        reader.close()
    }

    @Test
    fun `short mdcv warns`() {
        val body = ByteArray(10)
        val reader = byteReaderOf(body)
        val node = MdcvBoxDecoder.decode(reader, "mdcv", 0, 0, body.size.toLong(), emptyList())
        assertTrue(node.warnings.isNotEmpty())
        reader.close()
    }

    @Test
    fun `clli fields and summary`() {
        val body = hex("03e8 0190")
        val reader = byteReaderOf(body)
        val node = ClliBoxDecoder.decode(reader, "clli", 0, 0, body.size.toLong(), emptyList())
        assertEquals("1000", node.fields[0].value)
        assertEquals("400", node.fields[1].value)
        assertEquals("MaxCLL 1000 / MaxFALL 400", node.summary)
        reader.close()
    }

    @Test
    fun `real file exposes mdcv and clli in the tree and as container static`() {
        val file = fixture("hevc_box.mp4")
        val root = parseFile(file)
        assertNotNull(findFirst(root) { it.type == "mdcv" }?.summary)
        ByteReader.open(file).use { reader ->
            val s = readContainerHdrStatic(reader, root)
            assertEquals(1000.0, assertNotNull(s.masteringDisplay).maxLuminance, 1e-9)
            assertEquals(ContentLightLevel(1000, 400), s.contentLightLevel)
        }
    }

    @Test
    fun `file without boxes has empty container static`() {
        val file = fixture("hevc_hdr10plus.mp4")
        ByteReader.open(file).use { reader ->
            val s = readContainerHdrStatic(reader, parseFile(file))
            assertNull(s.masteringDisplay)
            assertNull(s.contentLightLevel)
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.MdcvClliBoxDecoderTest'`
Expected: FAIL — `MdcvBoxDecoder` unresolved.

- [ ] **Step 3: Implement**

`MdcvClliBoxDecoder.kt`:

```kotlin
package com.multiviewer.parser

import java.util.Locale

private const val MDCV_PAYLOAD_BYTES = 24
private const val CLLI_PAYLOAD_BYTES = 4

// ISO/IEC 23001-8 / ISOBMFF mdcv (plain Box, not FullBox): same 24-byte layout as SEI 137.
object MdcvBoxDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        val payloadStart = offset + headerSize
        val available = size - headerSize
        if (available < MDCV_PAYLOAD_BYTES) {
            return BoxNode(type, offset, headerSize, size, warnings = warnings + "mdcv needs $MDCV_PAYLOAD_BYTES payload bytes, has $available")
        }
        val md = decodeMasteringDisplayPayload(reader.readBytes(payloadStart, MDCV_PAYLOAD_BYTES))!!
        fun xy(x: Double, y: Double) = String.format(Locale.US, "%.5f, %.5f", x, y)
        fun nits(v: Double) = String.format(Locale.US, "%.4f cd/m²", v)
        val fields = listOf(
            BoxField("display_primaries G (x, y)", xy(md.greenX, md.greenY), payloadStart, 4),
            BoxField("display_primaries B (x, y)", xy(md.blueX, md.blueY), payloadStart + 4, 4),
            BoxField("display_primaries R (x, y)", xy(md.redX, md.redY), payloadStart + 8, 4),
            BoxField("white_point (x, y)", xy(md.whiteX, md.whiteY), payloadStart + 12, 4),
            BoxField("max_display_mastering_luminance", nits(md.maxLuminance), payloadStart + 16, 4),
            BoxField("min_display_mastering_luminance", nits(md.minLuminance), payloadStart + 20, 4),
        )
        val summary = String.format(Locale.US, "max %.0f / min %.4f cd/m²", md.maxLuminance, md.minLuminance)
        return BoxNode(type, offset, headerSize, size, fields = fields, warnings = warnings, summary = summary)
    }
}

// ISOBMFF clli: same 4-byte layout as SEI 144.
object ClliBoxDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        val payloadStart = offset + headerSize
        val available = size - headerSize
        if (available < CLLI_PAYLOAD_BYTES) {
            return BoxNode(type, offset, headerSize, size, warnings = warnings + "clli needs $CLLI_PAYLOAD_BYTES payload bytes, has $available")
        }
        val cll = decodeContentLightLevelPayload(reader.readBytes(payloadStart, CLLI_PAYLOAD_BYTES))!!
        val fields = listOf(
            BoxField("max_content_light_level", cll.maxCll.toString(), payloadStart, 2),
            BoxField("max_pic_average_light_level", cll.maxFall.toString(), payloadStart + 2, 2),
        )
        return BoxNode(type, offset, headerSize, size, fields = fields, warnings = warnings, summary = "MaxCLL ${cll.maxCll} / MaxFALL ${cll.maxFall}")
    }
}

data class ContainerHdrStatic(val masteringDisplay: MasteringDisplay?, val contentLightLevel: ContentLightLevel?)

fun readContainerHdrStatic(reader: ByteReader, root: BoxNode): ContainerHdrStatic {
    val mdcv = findFirst(root) { it.type == "mdcv" }?.takeIf { it.size - it.headerSize >= MDCV_PAYLOAD_BYTES }
    val clli = findFirst(root) { it.type == "clli" }?.takeIf { it.size - it.headerSize >= CLLI_PAYLOAD_BYTES }
    return ContainerHdrStatic(
        mdcv?.let { decodeMasteringDisplayPayload(reader.readBytes(it.offset + it.headerSize, MDCV_PAYLOAD_BYTES)) },
        clli?.let { decodeContentLightLevelPayload(reader.readBytes(it.offset + it.headerSize, CLLI_PAYLOAD_BYTES)) },
    )
}
```

In `Decoders.kt`, directly after `BoxRegistry.register("colr", ColrBoxDecoder)` add:

```kotlin
    BoxRegistry.register("mdcv", MdcvBoxDecoder)
    BoxRegistry.register("clli", ClliBoxDecoder)
```

In `DecodersRegistrationTest.kt`, change the last list line `"keys", "ilst", "data", "dvcC", "dvvC",` to `"keys", "ilst", "data", "dvcC", "dvvC", "mdcv", "clli",`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.MdcvClliBoxDecoderTest' --tests 'com.multiviewer.parser.DecodersRegistrationTest'`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MdcvClliBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/Decoders.kt app/src/test/kotlin/com/multiviewer/parser/MdcvClliBoxDecoderTest.kt app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt
git commit -m "feat: decode mdcv and clli boxes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Shared ISO-BMFF sample table (extract from AV2 indexer)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/IsoSampleTable.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Av2SampleIndexer.kt` (delete its private `descendants`, `readTimings`, `readSizes`, `readSampleOffsets`, `boundedRows`; call the shared ones)
- Test: `app/src/test/kotlin/com/multiviewer/parser/IsoSampleTableTest.kt`
- Guard: `app/src/test/kotlin/com/multiviewer/parser/Av2SampleIndexerTest.kt` (unchanged, must still pass)

**Interfaces:**
- Consumes: `ByteReader`, `BoxNode`, `TableData`, `VideoCodec`.
- Produces:
  - `internal fun boxDescendants(node: BoxNode): Sequence<BoxNode>`
  - `internal fun readSampleTimings(reader: ByteReader, node: BoxNode?, warnings: MutableList<String>, label: String, maxSamples: Int): List<Pair<Long, Long>>` (dts, duration)
  - `internal fun readSampleSizes(reader, node: BoxNode?, warnings, label, maxSamples): List<Long>`
  - `internal fun readChunkSampleOffsets(reader, stbl: BoxNode, sizes: List<Long>, warnings, label, maxSamples): List<Long>`
  - `internal fun readSyncSampleNumbers(reader, node: BoxNode?, warnings, label, maxSamples): Set<Long>?` (null = no `stss` = every sample is sync; numbers are 1-based)
  - `internal fun readCompositionOffsets(reader, node: BoxNode?, warnings, label, maxSamples): List<Long>`
  - `data class VideoSample(val index: Int, val offset: Long, val size: Long, val decodeTime: Long, val compositionOffset: Long, val isSync: Boolean)`
  - `data class VideoSampleTable(val codec: VideoCodec, val nalLengthSize: Int?, val timescale: Long, val samples: List<VideoSample>, val warnings: List<String>)`
  - `const val MAX_VIDEO_INDEXED_SAMPLES = 1_000_000`
  - `fun buildVideoSampleTable(reader: ByteReader, root: BoxNode, maxSamples: Int = MAX_VIDEO_INDEXED_SAMPLES): VideoSampleTable?`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsoSampleTableTest {
    private fun table(name: String): VideoSampleTable? {
        val file = File(javaClass.getResource("/hdr/$name")!!.toURI())
        val root = parseFile(file)
        return ByteReader.open(file).use { buildVideoSampleTable(it, root) }
    }

    @Test
    fun `HEVC fixture indexes 30 samples with stss sync flags`() {
        val t = assertNotNull(table("hevc_hdr10plus.mp4"))
        assertEquals(VideoCodec.HEVC, t.codec)
        assertEquals(4, t.nalLengthSize)
        assertEquals(15360L, t.timescale)
        assertEquals(30, t.samples.size)
        assertEquals(listOf(0, 10, 20), t.samples.filter { it.isSync }.map { it.index })
        assertEquals(44L, t.samples[0].offset)
        assertEquals(5823L, t.samples[0].size)
        assertEquals(5867L, t.samples[1].offset)
        assertTrue(t.warnings.isEmpty())
    }

    @Test
    fun `ctts offsets are read`() {
        val t = assertNotNull(table("hevc_box.mp4"))
        assertEquals(listOf(1024L, 2048L, 1024L, 0L), t.samples.take(4).map { it.compositionOffset })
        assertEquals(listOf(0L, 512L, 1024L, 1536L), t.samples.take(4).map { it.decodeTime })
    }

    @Test
    fun `H264 and AV1 codecs`() {
        val h264 = assertNotNull(table("h264_hdr.mp4"))
        assertEquals(VideoCodec.H264, h264.codec)
        assertEquals(4, h264.nalLengthSize)
        assertEquals(listOf(0, 5), h264.samples.filter { it.isSync }.map { it.index })
        val av1 = assertNotNull(table("av1_hdr.mp4"))
        assertEquals(VideoCodec.AV1, av1.codec)
        assertNull(av1.nalLengthSize)
        assertEquals(10, av1.samples.size)
    }

    @Test
    fun `non-video tree yields null`() {
        assertNull(buildVideoSampleTable(byteReaderOf(ByteArray(8)), BoxNode("root", 0, 0, 8)))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.IsoSampleTableTest'`
Expected: FAIL — `buildVideoSampleTable` unresolved.

- [ ] **Step 3: Implement `IsoSampleTable.kt`**

The first five readers are the AV2 indexer's private functions moved verbatim, with the literal `AV2` replaced by `$label` and `MAX_AV2_INDEXED_SAMPLES` replaced by `maxSamples`.

```kotlin
package com.multiviewer.parser

// Shared, bounds-checked ISO-BMFF sample-table readers over an already parsed box tree. Moved out of
// Av2SampleIndexer.kt so the HDR/SEI scanner can index H.264/HEVC/AV1 tracks with the same logic;
// `label` only prefixes warning text, `maxSamples` bounds every table walk.

internal fun boxDescendants(node: BoxNode): Sequence<BoxNode> = sequence {
    yield(node)
    node.children.forEach { yieldAll(boxDescendants(it)) }
}

internal fun readSampleTimings(reader: ByteReader, node: BoxNode?, warnings: MutableList<String>, label: String, maxSamples: Int): List<Pair<Long, Long>> {
    val table = node?.table ?: run { warnings += "$label track has no stts table"; return emptyList() }
    val result = mutableListOf<Pair<Long, Long>>()
    var dts = 0L
    for (row in 0 until boundedTableRows(reader, table, warnings, "stts", maxSamples)) {
        val at = table.entriesStart + row * 8L
        val count = reader.readUInt32(at)
        val delta = reader.readUInt32(at + 4)
        val remaining = maxSamples - result.size
        if (count > remaining) warnings += "stts sample count capped at $maxSamples"
        repeat(minOf(count, remaining.toLong()).toInt()) { result += dts to delta; dts += delta }
        if (result.size == maxSamples) break
    }
    return result
}

internal fun readSampleSizes(reader: ByteReader, node: BoxNode?, warnings: MutableList<String>, label: String, maxSamples: Int): List<Long> {
    if (node == null) { warnings += "$label track has no stsz table"; return emptyList() }
    val uniform = node.fields.firstOrNull { it.name == "sample_size" }?.value?.toLongOrNull() ?: 0L
    val table = node.table
    if (uniform > 0) {
        val count = node.fields.firstOrNull { it.name == "sample_count" }?.value?.toLongOrNull() ?: 0L
        return List(minOf(count, maxSamples.toLong()).toInt()) { uniform }
    }
    if (table == null) { warnings += "$label stsz has no sample-size entries"; return emptyList() }
    return (0 until boundedTableRows(reader, table, warnings, "stsz", maxSamples)).map { reader.readUInt32(table.entriesStart + it * 4L) }
}

internal fun readChunkSampleOffsets(reader: ByteReader, stbl: BoxNode, sizes: List<Long>, warnings: MutableList<String>, label: String, maxSamples: Int): List<Long> {
    val chunkTable = stbl.children.firstOrNull { it.type == "stco" || it.type == "co64" }?.table
        ?: run { warnings += "$label track has no chunk-offset table"; return emptyList() }
    val chunkWidth = if (stbl.children.any { it.type == "co64" && it.table == chunkTable }) 8 else 4
    val chunks = (0 until boundedTableRows(reader, chunkTable, warnings, "chunk offset", maxSamples)).map {
        val at = chunkTable.entriesStart + it * chunkWidth.toLong()
        if (chunkWidth == 8) reader.readUInt64(at) else reader.readUInt32(at)
    }
    val stsc = stbl.children.firstOrNull { it.type == "stsc" }?.table ?: run { warnings += "$label track has no stsc table"; return emptyList() }
    data class Entry(val firstChunk: Long, val samplesPerChunk: Long)
    val entries = (0 until boundedTableRows(reader, stsc, warnings, "stsc", maxSamples)).map {
        val at = stsc.entriesStart + it * 12L
        Entry(reader.readUInt32(at), reader.readUInt32(at + 4))
    }.filter { it.firstChunk > 0 && it.samplesPerChunk > 0 }
    if (entries.isEmpty()) { warnings += "$label stsc has no usable entries"; return emptyList() }
    val offsets = mutableListOf<Long>(); var sample = 0
    for ((chunkIndex, chunkOffset) in chunks.withIndex()) {
        val entry = entries.lastOrNull { it.firstChunk <= chunkIndex + 1L } ?: continue
        var offset = chunkOffset
        repeat(entry.samplesPerChunk.coerceAtMost((sizes.size - sample).toLong()).toInt()) {
            offsets += offset
            offset += sizes[sample++]
        }
        if (sample >= sizes.size || sample >= maxSamples) break
    }
    return offsets
}

internal fun readSyncSampleNumbers(reader: ByteReader, node: BoxNode?, warnings: MutableList<String>, label: String, maxSamples: Int): Set<Long>? {
    val table = node?.table ?: return null
    return (0 until boundedTableRows(reader, table, warnings, "$label stss", maxSamples))
        .map { reader.readUInt32(table.entriesStart + it * 4L) }
        .toSet()
}

// ctts version 0 is unsigned and version 1 signed; reading both as signed 32-bit is correct for
// every realistic offset (< 2^31 ticks).
internal fun readCompositionOffsets(reader: ByteReader, node: BoxNode?, warnings: MutableList<String>, label: String, maxSamples: Int): List<Long> {
    val table = node?.table ?: return emptyList()
    val result = mutableListOf<Long>()
    for (row in 0 until boundedTableRows(reader, table, warnings, "$label ctts", maxSamples)) {
        val at = table.entriesStart + row * 8L
        val count = reader.readUInt32(at)
        val offset = reader.readUInt32(at + 4).toInt().toLong()
        val remaining = maxSamples - result.size
        repeat(minOf(count, remaining.toLong()).toInt()) { result += offset }
        if (result.size == maxSamples) break
    }
    return result
}

private fun boundedTableRows(reader: ByteReader, table: TableData, warnings: MutableList<String>, label: String, maxSamples: Int): Int {
    val width = table.fieldWidths.sum().toLong()
    if (width <= 0 || table.entriesStart < 0 || table.entriesStart > reader.length) { warnings += "$label table is outside file bounds"; return 0 }
    val available = (reader.length - table.entriesStart) / width
    val actual = minOf(table.entryCount, available, maxSamples.toLong())
    if (actual < table.entryCount) warnings += "$label table is truncated or exceeds index limit"
    return actual.toInt()
}

data class VideoSample(
    val index: Int,
    val offset: Long,
    val size: Long,
    val decodeTime: Long,
    val compositionOffset: Long,
    val isSync: Boolean,
)

data class VideoSampleTable(
    val codec: VideoCodec,
    val nalLengthSize: Int?,
    val timescale: Long,
    val samples: List<VideoSample>,
    val warnings: List<String>,
)

const val MAX_VIDEO_INDEXED_SAMPLES = 1_000_000

private val SAMPLE_ENTRY_CODECS = mapOf(
    "avc1" to VideoCodec.H264, "avc3" to VideoCodec.H264,
    "hvc1" to VideoCodec.HEVC, "hev1" to VideoCodec.HEVC,
    "av01" to VideoCodec.AV1,
)

// Indexes the first trak whose sample entry is H.264/HEVC/AV1. nalLengthSize comes from
// avcC (byte 4) / hvcC (byte 21), low two bits + 1; null for AV1.
fun buildVideoSampleTable(reader: ByteReader, root: BoxNode, maxSamples: Int = MAX_VIDEO_INDEXED_SAMPLES): VideoSampleTable? {
    val (trak, entry) = boxDescendants(root).filter { it.type == "trak" }.firstNotNullOfOrNull { trak ->
        boxDescendants(trak).firstOrNull { it.type in SAMPLE_ENTRY_CODECS }?.let { trak to it }
    } ?: return null
    val codec = SAMPLE_ENTRY_CODECS.getValue(entry.type)
    val nalLengthSize = when (codec) {
        VideoCodec.H264 -> entry.children.firstOrNull { it.type == "avcC" }?.takeIf { it.size - it.headerSize >= 5 }
            ?.let { (reader.readUInt8(it.offset + it.headerSize + 4) and 0x03) + 1 }
        VideoCodec.HEVC -> entry.children.firstOrNull { it.type == "hvcC" }?.takeIf { it.size - it.headerSize >= 23 }
            ?.let { (reader.readUInt8(it.offset + it.headerSize + 21) and 0x03) + 1 }
        VideoCodec.AV1 -> null
    }
    if (codec != VideoCodec.AV1 && nalLengthSize == null) return null
    val mdia = trak.children.firstOrNull { it.type == "mdia" } ?: return null
    val stbl = boxDescendants(mdia).firstOrNull { it.type == "stbl" } ?: return null
    val warnings = mutableListOf<String>()
    val label = codec.name
    val timescale = boxDescendants(mdia).firstOrNull { it.type == "mdhd" }
        ?.fields?.firstOrNull { it.name == "timescale" }?.value?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
    fun child(type: String) = stbl.children.firstOrNull { it.type == type }
    val timings = readSampleTimings(reader, child("stts"), warnings, label, maxSamples)
    val sizes = readSampleSizes(reader, child("stsz"), warnings, label, maxSamples)
    val offsets = readChunkSampleOffsets(reader, stbl, sizes, warnings, label, maxSamples)
    val sync = readSyncSampleNumbers(reader, child("stss"), warnings, label, maxSamples)
    val composition = readCompositionOffsets(reader, child("ctts"), warnings, label, maxSamples)
    val count = minOf(timings.size, sizes.size, offsets.size)
    if (count == 0 && (timings.isNotEmpty() || sizes.isNotEmpty() || offsets.isNotEmpty())) warnings += "$label sample tables have inconsistent counts"
    val samples = (0 until count).map { i ->
        VideoSample(
            index = i, offset = offsets[i], size = sizes[i], decodeTime = timings[i].first,
            compositionOffset = composition.getOrElse(i) { 0L }, isSync = sync?.contains(i + 1L) ?: true,
        )
    }
    return VideoSampleTable(codec, nalLengthSize, timescale, samples, warnings)
}
```

- [ ] **Step 4: Refactor `Av2SampleIndexer.kt` onto the shared readers**

Delete the five private functions `descendants`, `readTimings`, `readSizes`, `readSampleOffsets`, `boundedRows` from `Av2SampleIndexer.kt`, and replace the body of `buildAv2SampleIndex` with:

```kotlin
fun buildAv2SampleIndex(file: File, root: BoxNode): Av2SampleIndex? = ByteReader.open(file).use { reader ->
    val trak = boxDescendants(root).firstOrNull { it.type == "trak" && boxDescendants(it).any { child -> child.type == "av02" } } ?: return null
    val mdia = trak.children.firstOrNull { it.type == "mdia" } ?: return null
    val stbl = boxDescendants(mdia).firstOrNull { it.type == "stbl" } ?: return null
    val warnings = mutableListOf<String>()
    val timescale = boxDescendants(mdia).firstOrNull { it.type == "mdhd" }
        ?.fields?.firstOrNull { it.name == "timescale" }?.value?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
    val timings = readSampleTimings(reader, stbl.children.firstOrNull { it.type == "stts" }, warnings, "AV2", MAX_AV2_INDEXED_SAMPLES)
    val sizes = readSampleSizes(reader, stbl.children.firstOrNull { it.type == "stsz" }, warnings, "AV2", MAX_AV2_INDEXED_SAMPLES)
    val offsets = readChunkSampleOffsets(reader, stbl, sizes, warnings, "AV2", MAX_AV2_INDEXED_SAMPLES)
    val sampleCount = minOf(timings.size, sizes.size, offsets.size, MAX_AV2_INDEXED_SAMPLES)
    if (sampleCount < minOf(timings.size, sizes.size, offsets.size)) warnings += "AV2 sample index capped at $MAX_AV2_INDEXED_SAMPLES samples"
    if (sampleCount == 0 && (timings.isNotEmpty() || sizes.isNotEmpty() || offsets.isNotEmpty())) warnings += "AV2 sample tables have inconsistent counts"
    val samples = (0 until sampleCount).map { index ->
        val (dts, duration) = timings[index]
        val parsed = parseAv2SampleObus(reader, offsets[index], sizes[index])
        Av2IndexedSample(index, dts, duration, offsets[index], sizes[index], parsed.obus, parsed.warnings)
    }
    Av2SampleIndex(timescale, samples, warnings)
}
```

Then check nothing else in the file still calls a deleted function:

Run: `grep -n "descendants(\|readTimings\|readSizes\|readSampleOffsets\|boundedRows" app/src/main/kotlin/com/multiviewer/parser/Av2SampleIndexer.kt`
Expected: only `boxDescendants(` matches. If another function in the file (e.g. `parseAv2SampleObus`) used `descendants`, switch it to `boxDescendants`.

- [ ] **Step 5: Run new and guard tests**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.IsoSampleTableTest' --tests 'com.multiviewer.parser.Av2SampleIndexerTest' --tests 'com.multiviewer.parser.Av2*'`
Expected: PASS (IsoSampleTableTest 4 tests; every AV2 test still green).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/IsoSampleTable.kt app/src/main/kotlin/com/multiviewer/parser/Av2SampleIndexer.kt app/src/test/kotlin/com/multiviewer/parser/IsoSampleTableTest.kt
git commit -m "refactor: share ISO-BMFF sample table readers and index H.264/HEVC/AV1 tracks

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Per-sample metadata reader and formatting

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/FrameMetadataReader.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/MetadataFormatting.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/FrameMetadataReaderTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MetadataFormattingTest.kt`

**Interfaces:**
- Consumes: `splitSeiMessages`, `decodeSeiPayload`, `seiPayloadTypeName`, `decodeAv1MetadataPayload`, `av1MetadataTypeName`, `parseObuHeader(ByteReader, Long): ObuHeader`, `readLeb128(ByteReader, Long): Pair<Long, Long>` (existing `Av1Obu.kt`), `findFirst`, `buildVideoSampleTable` (tests only).
- Produces:
  - `fun detectVideoCodec(root: BoxNode): VideoCodec?` — judged from `hvcC`/`avcC`/`av1C` presence only (no reads).
  - `fun readFrameMetadataUnits(file: File, codec: VideoCodec, nalLengthSize: Int?, sampleOffset: Long, sampleSize: Long): List<MetadataUnit>` (opens the file; returns empty list on I/O error)
  - `fun readFrameMetadataUnits(reader: ByteReader, codec: VideoCodec, nalLengthSize: Int?, sampleOffset: Long, sampleSize: Long): List<MetadataUnit>` (may throw on I/O error)
  - `fun summarizeMetadata(unit: MetadataUnit): String`
  - `fun describeMetadata(unit: MetadataUnit): List<Pair<String, String>>`

- [ ] **Step 1: Write the failing tests**

`FrameMetadataReaderTest.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameMetadataReaderTest {
    private fun fixture(name: String) = File(javaClass.getResource("/hdr/$name")!!.toURI())

    private fun unitsOf(name: String, sampleIndex: Int): List<MetadataUnit> {
        val file = fixture(name)
        val root = parseFile(file)
        return ByteReader.open(file).use { reader ->
            val t = assertNotNull(buildVideoSampleTable(reader, root))
            val s = t.samples[sampleIndex]
            readFrameMetadataUnits(reader, t.codec, t.nalLengthSize, s.offset, s.size)
        }
    }

    @Test
    fun `detectVideoCodec from config boxes`() {
        assertEquals(VideoCodec.HEVC, detectVideoCodec(parseFile(fixture("hevc_hdr10plus.mp4"))))
        assertEquals(VideoCodec.H264, detectVideoCodec(parseFile(fixture("h264_hdr.mp4"))))
        assertEquals(VideoCodec.AV1, detectVideoCodec(parseFile(fixture("av1_hdr.mp4"))))
        assertNull(detectVideoCodec(BoxNode("root", 0, 0, 0)))
    }

    @Test
    fun `HEVC sample 0 lists CLL, MDCV, unregistered, HDR10plus in order`() {
        val units = unitsOf("hevc_hdr10plus.mp4", 0)
        assertEquals(listOf(144, 137, 5, 4), units.map { it.typeCode })
        assertTrue(units.all { it.source == MetadataSource.HEVC_PREFIX_SEI })
        assertEquals(ContentLightLevel(1000, 400), assertIs<DecodedMetadata.LightLevel>(units[0].decoded).value)
        assertEquals("2ca2de09-b517-47db-bb55-a4fe7fc2fc4e", assertIs<DecodedMetadata.UserDataUnregistered>(units[2].decoded).uuid)
        val plus = assertIs<DecodedMetadata.Hdr10Plus>(units[3].decoded).value
        assertEquals(listOf(4000, 4500, 3700), plus.windows[0].maxscl)
        // NAL range starts at the NAL header right after the 4-byte length prefix of the sample's first NAL
        assertEquals(44L + 4, units[0].unitOffset)
    }

    @Test
    fun `HEVC sample 12 has no metadata and sample 17 has the jump`() {
        assertEquals(emptyList(), unitsOf("hevc_hdr10plus.mp4", 12))
        val plus = assertIs<DecodedMetadata.Hdr10Plus>(unitsOf("hevc_hdr10plus.mp4", 17).single().decoded).value
        assertEquals(9000, plus.windows[0].maxscl[0])
    }

    @Test
    fun `H264 IDR sample SEIs`() {
        assertEquals(listOf(5, 137, 144), unitsOf("h264_hdr.mp4", 0).map { it.typeCode })
        assertEquals(emptyList(), unitsOf("h264_hdr.mp4", 1))
        assertTrue(unitsOf("h264_hdr.mp4", 5).any { it.decoded is DecodedMetadata.Mastering })
    }

    @Test
    fun `AV1 keyframe metadata OBUs`() {
        val units = unitsOf("av1_hdr.mp4", 0)
        assertEquals(listOf("HDR_CLL", "HDR_MDCV"), units.map { it.name })
        assertTrue(units.all { it.source == MetadataSource.AV1_METADATA_OBU })
        val md = assertIs<DecodedMetadata.Mastering>(units[1].decoded).value
        assertEquals(1000.0, md.maxLuminance, 1e-9)
        assertEquals(emptyList(), unitsOf("av1_hdr.mp4", 1))
    }

    @Test
    fun `bad NAL length is reported, not thrown`() {
        // one length prefix claiming 0x100 bytes inside a 10-byte sample
        val bytes = hex("00000100 4e01 90 04 03e8")
        val units = byteReaderOf(bytes).use { readFrameMetadataUnits(it, VideoCodec.HEVC, 4, 0, bytes.size.toLong()) }
        assertIs<DecodedMetadata.Malformed>(units.single().decoded)
        assertEquals(MetadataSource.SAMPLE_STRUCTURE, units.single().source)
    }

    @Test
    fun `HEVC suffix SEI after a VCL NAL and DV NAL 62 are found`() {
        val vcl = hex("0201 aa bb")                   // TRAIL_R slice (type 1)
        val suffix = hex("5001 90 04 03e8 0190 80")   // type 40 suffix SEI
        val rpu = hex("7c01 19 08")                   // type 62
        fun prefixed(n: ByteArray) = byteArrayOf(0, 0, 0, n.size.toByte()) + n
        val sample = prefixed(vcl) + prefixed(suffix) + prefixed(rpu)
        val units = byteReaderOf(sample).use { readFrameMetadataUnits(it, VideoCodec.HEVC, 4, 0, sample.size.toLong()) }
        assertEquals(listOf(MetadataSource.HEVC_SUFFIX_SEI, MetadataSource.HEVC_UNSPECIFIED_NAL), units.map { it.source })
        assertEquals(62, units[1].typeCode)
    }
}
```

`MetadataFormattingTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetadataFormattingTest {
    private fun unit(decoded: DecodedMetadata, payloadSize: Int = 4) =
        MetadataUnit(MetadataSource.HEVC_PREFIX_SEI, 0, "x", 0, 10, payloadSize, decoded)

    private val plus = Hdr10PlusMetadata(
        1, 1, 1000,
        listOf(Hdr10PlusWindow(listOf(4000, 4500, 3700), 1000, listOf(1 to 0, 99 to 4500), 0, true, 1000, 1200, listOf(300, 600, 900))),
        null,
    )

    @Test
    fun `summaries`() {
        assertEquals("MaxCLL 1000 / MaxFALL 400", summarizeMetadata(unit(DecodedMetadata.LightLevel(ContentLightLevel(1000, 400)))))
        assertEquals("HDR10+ MaxSCL 450.0 nits, avg 100.0 nits, target 1000 nits", summarizeMetadata(unit(DecodedMetadata.Hdr10Plus(plus))))
        assertEquals("12 bytes", summarizeMetadata(unit(DecodedMetadata.PresenceOnly, payloadSize = 12)))
        assertEquals("malformed: cut", summarizeMetadata(unit(DecodedMetadata.Malformed("cut"))))
    }

    @Test
    fun `HDR10plus detail rows`() {
        val rows = describeMetadata(unit(DecodedMetadata.Hdr10Plus(plus))).toMap()
        assertEquals("400.0 / 450.0 / 370.0 nits", rows["maxscl R/G/B"])
        assertEquals("1%: 0.0, 99%: 450.0 nits", rows["distribution_maxrgb"])
        assertEquals("(1000, 1200), anchors 300, 600, 900", rows["tone_mapping"])
    }

    @Test
    fun `mastering detail rows`() {
        val md = decodeMasteringDisplayPayload(hex("33c286c41d4c0bb884d03e803d134042 00989680 00000032"))!!
        val rows = describeMetadata(unit(DecodedMetadata.Mastering(md))).toMap()
        assertEquals("0.68000, 0.32000", rows["R (x, y)"])
        assertEquals("1000.0000 / 0.0050 cd/m²", rows["max / min luminance"])
        assertTrue(describeMetadata(unit(DecodedMetadata.PresenceOnly)).isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.FrameMetadataReaderTest' --tests 'com.multiviewer.parser.MetadataFormattingTest'`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement `FrameMetadataReader.kt`**

```kotlin
package com.multiviewer.parser

import java.io.File

private const val SEI_NAL_READ_CAP = 256 * 1024
private const val METADATA_OBU_READ_CAP = 64 * 1024
private const val AV1_OBU_METADATA = 5
private const val H264_NAL_SEI = 6
private const val HEVC_NAL_PREFIX_SEI = 39
private const val HEVC_NAL_SUFFIX_SEI = 40

// Judged from config boxes only, so UI code can gate per-frame sample reads without touching data.
fun detectVideoCodec(root: BoxNode): VideoCodec? = when {
    findFirst(root) { it.type == "hvcC" } != null -> VideoCodec.HEVC
    findFirst(root) { it.type == "avcC" } != null -> VideoCodec.H264
    findFirst(root) { it.type == "av1C" } != null -> VideoCodec.AV1
    else -> null
}

fun readFrameMetadataUnits(file: File, codec: VideoCodec, nalLengthSize: Int?, sampleOffset: Long, sampleSize: Long): List<MetadataUnit> =
    try {
        ByteReader.open(file).use { readFrameMetadataUnits(it, codec, nalLengthSize, sampleOffset, sampleSize) }
    } catch (e: Exception) {
        emptyList()
    }

// Walks every NAL/OBU of one sample (the whole sample, so HEVC suffix SEI and NAL 62/63 after the
// slices are seen) but reads payload bytes only for SEI NALs and metadata OBUs.
fun readFrameMetadataUnits(reader: ByteReader, codec: VideoCodec, nalLengthSize: Int?, sampleOffset: Long, sampleSize: Long): List<MetadataUnit> {
    val end = minOf(sampleOffset + sampleSize, reader.length)
    return when (codec) {
        VideoCodec.AV1 -> readAv1MetadataUnits(reader, sampleOffset, end)
        VideoCodec.H264, VideoCodec.HEVC -> readNalMetadataUnits(reader, codec, nalLengthSize ?: return emptyList(), sampleOffset, end)
    }
}

private fun readNalMetadataUnits(reader: ByteReader, codec: VideoCodec, lengthSize: Int, start: Long, end: Long): List<MetadataUnit> {
    val units = mutableListOf<MetadataUnit>()
    var pos = start
    while (pos + lengthSize <= end) {
        val nalLength = when (lengthSize) {
            1 -> reader.readUInt8(pos).toLong()
            2 -> reader.readUInt16(pos).toLong()
            4 -> reader.readUInt32(pos)
            else -> return units
        }
        val nalStart = pos + lengthSize
        if (nalLength <= 0 || nalStart + nalLength > end) {
            units += MetadataUnit(
                MetadataSource.SAMPLE_STRUCTURE, -1, "malformed NAL length", pos, end - pos, 0,
                DecodedMetadata.Malformed("NAL length $nalLength at offset $pos runs past the sample end"),
            )
            break
        }
        val headerByte = reader.readUInt8(nalStart)
        if (codec == VideoCodec.H264) {
            if (headerByte and 0x1F == H264_NAL_SEI) {
                units += seiUnits(reader, codec, MetadataSource.H264_SEI, nalStart, nalLength, headerSize = 1)
            }
        } else {
            when (val nalType = (headerByte shr 1) and 0x3F) {
                HEVC_NAL_PREFIX_SEI -> units += seiUnits(reader, codec, MetadataSource.HEVC_PREFIX_SEI, nalStart, nalLength, headerSize = 2)
                HEVC_NAL_SUFFIX_SEI -> units += seiUnits(reader, codec, MetadataSource.HEVC_SUFFIX_SEI, nalStart, nalLength, headerSize = 2)
                62, 63 -> units += MetadataUnit(
                    MetadataSource.HEVC_UNSPECIFIED_NAL, nalType,
                    if (nalType == 62) "Dolby Vision RPU (NAL 62, not parsed)" else "Dolby Vision EL (NAL 63, not parsed)",
                    nalStart, nalLength, nalLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), DecodedMetadata.PresenceOnly,
                )
            }
        }
        pos = nalStart + nalLength
    }
    return units
}

private fun seiUnits(reader: ByteReader, codec: VideoCodec, source: MetadataSource, nalStart: Long, nalLength: Long, headerSize: Int): List<MetadataUnit> {
    val nal = reader.readBytes(nalStart, minOf(nalLength, SEI_NAL_READ_CAP.toLong()).toInt())
    return splitSeiMessages(nal, headerSize).map { m ->
        MetadataUnit(source, m.payloadType, seiPayloadTypeName(m.payloadType), nalStart, nalLength, m.payloadSize, decodeSeiPayload(codec, m))
    }
}

private fun readAv1MetadataUnits(reader: ByteReader, start: Long, end: Long): List<MetadataUnit> {
    val units = mutableListOf<MetadataUnit>()
    var pos = start
    while (pos < end) {
        val header = parseObuHeader(reader, pos)
        var payloadStart = pos + header.headerSize
        val payloadSize: Long
        if (header.hasSizeField) {
            if (payloadStart >= end) break
            val (size, next) = readLeb128(reader, payloadStart)
            payloadSize = size
            payloadStart = next
        } else {
            payloadSize = end - payloadStart
        }
        if (payloadSize < 0 || payloadStart + payloadSize > end) {
            units += MetadataUnit(
                MetadataSource.SAMPLE_STRUCTURE, header.obuType, "malformed OBU size", pos, end - pos, 0,
                DecodedMetadata.Malformed("obu_size $payloadSize at offset $pos runs past the sample end"),
            )
            break
        }
        if (header.obuType == AV1_OBU_METADATA) {
            val bytes = reader.readBytes(payloadStart, minOf(payloadSize, METADATA_OBU_READ_CAP.toLong()).toInt())
            val (type, decoded) = decodeAv1MetadataPayload(bytes)
            units += MetadataUnit(
                MetadataSource.AV1_METADATA_OBU, type.toInt(), av1MetadataTypeName(type),
                pos, payloadStart + payloadSize - pos, payloadSize.toInt(), decoded,
            )
        }
        pos = payloadStart + payloadSize
    }
    return units
}
```

- [ ] **Step 4: Implement `MetadataFormatting.kt`**

```kotlin
package com.multiviewer.parser

import java.util.Locale

private fun f(format: String, vararg args: Any): String = String.format(Locale.US, format, *args)

fun summarizeMetadata(unit: MetadataUnit): String = when (val d = unit.decoded) {
    is DecodedMetadata.Mastering -> f("max %.0f / min %.4f cd/m²", d.value.maxLuminance, d.value.minLuminance)
    is DecodedMetadata.LightLevel -> "MaxCLL ${d.value.maxCll} / MaxFALL ${d.value.maxFall}"
    is DecodedMetadata.Hdr10Plus -> d.value.windows.first().let { w ->
        f("HDR10+ MaxSCL %.1f nits, avg %.1f nits, target %d nits", w.maxSclNits, w.averageMaxrgbNits, d.value.targetedSystemDisplayMaximumLuminance)
    }
    is DecodedMetadata.ItuT35 -> f("T.35 country 0x%02X, provider %s", d.countryCode, d.providerCode?.let { f("0x%04X", it) } ?: "-")
    is DecodedMetadata.UserDataUnregistered -> "${d.uuid} ${d.asciiPreview.take(40)}".trimEnd()
    is DecodedMetadata.RecoveryPoint -> "recovery ${d.recoveryCount}, exact_match=${d.exactMatch}, broken_link=${d.brokenLink}"
    is DecodedMetadata.AlternativeTransfer -> "preferred_transfer_characteristics ${d.preferredTransferCharacteristics}"
    DecodedMetadata.PresenceOnly -> "${unit.payloadSize} bytes"
    is DecodedMetadata.Malformed -> "malformed: ${d.reason}"
}

fun describeMetadata(unit: MetadataUnit): List<Pair<String, String>> = when (val d = unit.decoded) {
    is DecodedMetadata.Mastering -> d.value.let { m ->
        listOf(
            "R (x, y)" to f("%.5f, %.5f", m.redX, m.redY),
            "G (x, y)" to f("%.5f, %.5f", m.greenX, m.greenY),
            "B (x, y)" to f("%.5f, %.5f", m.blueX, m.blueY),
            "white point (x, y)" to f("%.5f, %.5f", m.whiteX, m.whiteY),
            "max / min luminance" to f("%.4f / %.4f cd/m²", m.maxLuminance, m.minLuminance),
        )
    }
    is DecodedMetadata.Hdr10Plus -> d.value.let { m ->
        val w = m.windows.first()
        listOfNotNull(
            "application_version" to m.applicationVersion.toString(),
            "num_windows" to m.numWindows.toString(),
            "targeted_system_display_max" to "${m.targetedSystemDisplayMaximumLuminance} nits",
            "maxscl R/G/B" to w.maxscl.joinToString(" / ") { f("%.1f", it / 10.0) } + " nits",
            "average_maxrgb" to f("%.1f nits", w.averageMaxrgbNits),
            "distribution_maxrgb" to w.distribution.joinToString(", ") { (p, v) -> f("%d%%: %.1f", p, v / 10.0) } + " nits",
            "fraction_bright_pixels" to w.fractionBrightPixels.toString(),
            "tone_mapping" to if (w.toneMappingFlag) "(${w.kneePointX}, ${w.kneePointY}), anchors ${w.bezierCurveAnchors.joinToString(", ")}" else "off",
            m.colorSaturationWeight?.let { "color_saturation_weight" to it.toString() },
        )
    }
    is DecodedMetadata.ItuT35 -> listOf("payload" to d.hexPreview)
    is DecodedMetadata.UserDataUnregistered -> listOf("uuid" to d.uuid, "data" to d.asciiPreview)
    else -> emptyList()
}
```

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.FrameMetadataReaderTest' --tests 'com.multiviewer.parser.MetadataFormattingTest'`
Expected: PASS (10 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/FrameMetadataReader.kt app/src/main/kotlin/com/multiviewer/parser/MetadataFormatting.kt app/src/test/kotlin/com/multiviewer/parser/FrameMetadataReaderTest.kt app/src/test/kotlin/com/multiviewer/parser/MetadataFormattingTest.kt
git commit -m "feat: read per-frame SEI and AV1 metadata units from MP4 samples

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Anomaly rules

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/HdrAnomalyRules.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/HdrMetadataScanner.kt` (only `FrameHdrRecord` in this task; the scanner function arrives in Task 9)
- Test: `app/src/test/kotlin/com/multiviewer/parser/HdrAnomalyRulesTest.kt`

**Interfaces:**
- Consumes: model types, `ContainerHdrStatic`.
- Produces:
  - `data class FrameHdrRecord(val frameNumber: Int, val sampleIndex: Int, val ptsSeconds: Double, val isSync: Boolean, val units: List<MetadataUnit>)` with computed `hdr10Plus: Hdr10PlusMetadata?`, `masteringDisplay: MasteringDisplay?`, `contentLightLevel: ContentLightLevel?`
  - `enum class HdrSeverity { INFO, WARN, ERROR }`, `enum class HdrRule { HDR10PLUS_MISSING, ABRUPT_CHANGE, STATIC_DYNAMIC_MISMATCH, CONTAINER_INBAND_MISMATCH }`, `enum class HdrVerdict { PASS, WARN, FAIL }`
  - `data class HdrAnomaly(val rule: HdrRule, val severity: HdrSeverity, val firstFrame: Int?, val lastFrame: Int?, val message: String)`
  - `data class HdrThresholds(val relativeChange: Double = 0.5, val absoluteChangeNits: Double = 100.0)`
  - `fun detectHdrAnomalies(records: List<FrameHdrRecord>, container: ContainerHdrStatic, thresholds: HdrThresholds = HdrThresholds()): List<HdrAnomaly>`
  - `fun hdrVerdict(anomalies: List<HdrAnomaly>): HdrVerdict`
  - `fun sameMasteringDisplay(a: MasteringDisplay, b: MasteringDisplay): Boolean`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HdrAnomalyRulesTest {
    private val seiMd = decodeMasteringDisplayPayload(hex("33c286c41d4c0bb884d03e803d134042 00989680 00000032"))!!
    private val av1Md = decodeAv1Mdcv(hex("ae1451ec 43d7b0a4 26660f5c 500d5439 0003e800 00000052"))!!
    private val cll = ContentLightLevel(1000, 400)

    private fun plus(maxSclTenths: Int, avgTenths: Int, target: Long = 1000) = MetadataUnit(
        MetadataSource.HEVC_PREFIX_SEI, 4, "t35", 0, 1, 1,
        DecodedMetadata.Hdr10Plus(
            Hdr10PlusMetadata(1, 1, target, listOf(Hdr10PlusWindow(listOf(maxSclTenths, 0, 0), avgTenths, emptyList(), 0, false, null, null, emptyList())), null),
        ),
    )
    private fun mdUnit(md: MasteringDisplay) = MetadataUnit(MetadataSource.HEVC_PREFIX_SEI, 137, "md", 0, 1, 24, DecodedMetadata.Mastering(md))
    private fun cllUnit(c: ContentLightLevel) = MetadataUnit(MetadataSource.HEVC_PREFIX_SEI, 144, "cll", 0, 1, 4, DecodedMetadata.LightLevel(c))
    private fun rec(n: Int, sync: Boolean = false, vararg units: MetadataUnit) = FrameHdrRecord(n, n, n / 30.0, sync, units.toList())
    private val none = ContainerHdrStatic(null, null)

    @Test
    fun `missing HDR10plus ranges, ERROR when a keyframe is missing`() {
        val records = listOf(rec(0, true, plus(4000, 1000)), rec(1), rec(2), rec(3, false, plus(4000, 1000)), rec(4, true), rec(5, false, plus(4000, 1000)))
        val missing = detectHdrAnomalies(records, none).filter { it.rule == HdrRule.HDR10PLUS_MISSING }
        assertEquals(listOf(1 to 2, 4 to 4), missing.map { it.firstFrame to it.lastFrame })
        assertEquals(listOf(HdrSeverity.WARN, HdrSeverity.ERROR), missing.map { it.severity })
    }

    @Test
    fun `no HDR10plus at all or on every frame is not a missing anomaly`() {
        assertTrue(detectHdrAnomalies(listOf(rec(0), rec(1)), none).none { it.rule == HdrRule.HDR10PLUS_MISSING })
        assertTrue(detectHdrAnomalies(listOf(rec(0, true, plus(1, 1)), rec(1, false, plus(1, 1))), none).none { it.rule == HdrRule.HDR10PLUS_MISSING })
    }

    @Test
    fun `abrupt change needs both relative and absolute thresholds`() {
        // 400 -> 600 nits: +50% and +200 nits -> fires; 100 -> 190: +90% but only 90 nits -> no
        val fires = detectHdrAnomalies(listOf(rec(0, true, plus(4000, 0)), rec(1, false, plus(6000, 0))), none)
        assertEquals(HdrSeverity.WARN, fires.single { it.rule == HdrRule.ABRUPT_CHANGE }.severity)
        val quiet = detectHdrAnomalies(listOf(rec(0, true, plus(1000, 0)), rec(1, false, plus(1900, 0))), none)
        assertTrue(quiet.none { it.rule == HdrRule.ABRUPT_CHANGE })
        val belowRelative = detectHdrAnomalies(listOf(rec(0, true, plus(4000, 0)), rec(1, false, plus(5900, 0))), none)
        assertTrue(belowRelative.none { it.rule == HdrRule.ABRUPT_CHANGE })
    }

    @Test
    fun `abrupt change on a keyframe is INFO and thresholds are adjustable`() {
        val records = listOf(rec(0, false, plus(4000, 0)), rec(1, true, plus(6000, 0)))
        assertEquals(HdrSeverity.INFO, detectHdrAnomalies(records, none).single { it.rule == HdrRule.ABRUPT_CHANGE }.severity)
        assertTrue(detectHdrAnomalies(records, none, HdrThresholds(relativeChange = 0.6)).none { it.rule == HdrRule.ABRUPT_CHANGE })
    }

    @Test
    fun `static vs dynamic mismatches`() {
        val records = listOf(
            rec(0, true, mdUnit(seiMd), cllUnit(cll), plus(10500, 4100, target = 2000)),
            rec(1, false, plus(4000, 1000, target = 0)),
        )
        val messages = detectHdrAnomalies(records, none).filter { it.rule == HdrRule.STATIC_DYNAMIC_MISMATCH }
        assertEquals(5, messages.size) // MaxSCL>MDCV, target>MDCV, target==0, peak>MaxCLL, avg>MaxFALL
        assertTrue(messages.all { it.severity == HdrSeverity.WARN })
    }

    @Test
    fun `zero MaxCLL means unknown and is not compared`() {
        val records = listOf(rec(0, true, cllUnit(ContentLightLevel(0, 0)), plus(20000, 9000)))
        assertTrue(detectHdrAnomalies(records, none).none { it.rule == HdrRule.STATIC_DYNAMIC_MISMATCH })
    }

    @Test
    fun `container vs in-band`() {
        val inBandOnly = detectHdrAnomalies(listOf(rec(0, true, mdUnit(seiMd), cllUnit(cll))), none)
            .filter { it.rule == HdrRule.CONTAINER_INBAND_MISMATCH }
        assertEquals(listOf(HdrSeverity.INFO, HdrSeverity.INFO), inBandOnly.map { it.severity })

        val boxOnly = detectHdrAnomalies(listOf(rec(0, true)), ContainerHdrStatic(seiMd, cll)).filter { it.rule == HdrRule.CONTAINER_INBAND_MISMATCH }
        assertEquals(2, boxOnly.size)

        val differs = detectHdrAnomalies(
            listOf(rec(0, true, cllUnit(ContentLightLevel(1000, 401)))), ContainerHdrStatic(null, cll),
        ).filter { it.rule == HdrRule.CONTAINER_INBAND_MISMATCH }
        assertEquals(HdrSeverity.WARN, differs.single().severity)

        val agreeAcrossEncodings = detectHdrAnomalies(listOf(rec(0, true, mdUnit(av1Md))), ContainerHdrStatic(seiMd, null))
            .filter { it.rule == HdrRule.CONTAINER_INBAND_MISMATCH }
        assertTrue(agreeAcrossEncodings.none { it.severity == HdrSeverity.WARN })
    }

    @Test
    fun `in-band static change mid-stream is INFO`() {
        val records = listOf(rec(0, true, cllUnit(cll)), rec(1, true, cllUnit(ContentLightLevel(800, 300))))
        val changed = detectHdrAnomalies(records, ContainerHdrStatic(null, cll)).filter { it.rule == HdrRule.CONTAINER_INBAND_MISMATCH }
        assertEquals(listOf(HdrSeverity.INFO), changed.map { it.severity })
        assertEquals(1, changed.single().firstFrame)
    }

    @Test
    fun `mastering display tolerance across SEI and AV1 encodings`() {
        assertTrue(sameMasteringDisplay(seiMd, av1Md))
        assertFalse(sameMasteringDisplay(seiMd, seiMd.copy(maxLuminance = 1001.0)))
    }

    @Test
    fun `verdict`() {
        assertEquals(HdrVerdict.PASS, hdrVerdict(listOf(HdrAnomaly(HdrRule.ABRUPT_CHANGE, HdrSeverity.INFO, 0, 0, ""))))
        assertEquals(HdrVerdict.WARN, hdrVerdict(listOf(HdrAnomaly(HdrRule.ABRUPT_CHANGE, HdrSeverity.WARN, 0, 0, ""))))
        assertEquals(HdrVerdict.FAIL, hdrVerdict(listOf(HdrAnomaly(HdrRule.HDR10PLUS_MISSING, HdrSeverity.ERROR, 0, 0, ""))))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.HdrAnomalyRulesTest'`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement `FrameHdrRecord` in `HdrMetadataScanner.kt`**

```kotlin
package com.multiviewer.parser

// One frame in presentation order (frameNumber), with its decode-order sample index.
data class FrameHdrRecord(
    val frameNumber: Int,
    val sampleIndex: Int,
    val ptsSeconds: Double,
    val isSync: Boolean,
    val units: List<MetadataUnit>,
) {
    val hdr10Plus: Hdr10PlusMetadata?
        get() = units.firstNotNullOfOrNull { (it.decoded as? DecodedMetadata.Hdr10Plus)?.value }
    val masteringDisplay: MasteringDisplay?
        get() = units.firstNotNullOfOrNull { (it.decoded as? DecodedMetadata.Mastering)?.value }
    val contentLightLevel: ContentLightLevel?
        get() = units.firstNotNullOfOrNull { (it.decoded as? DecodedMetadata.LightLevel)?.value }
}
```

- [ ] **Step 4: Implement `HdrAnomalyRules.kt`**

```kotlin
package com.multiviewer.parser

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

enum class HdrSeverity { INFO, WARN, ERROR }
enum class HdrRule { HDR10PLUS_MISSING, ABRUPT_CHANGE, STATIC_DYNAMIC_MISMATCH, CONTAINER_INBAND_MISMATCH }
enum class HdrVerdict { PASS, WARN, FAIL }

data class HdrAnomaly(val rule: HdrRule, val severity: HdrSeverity, val firstFrame: Int?, val lastFrame: Int?, val message: String)

data class HdrThresholds(val relativeChange: Double = 0.5, val absoluteChangeNits: Double = 100.0)

private fun f(format: String, vararg args: Any): String = String.format(Locale.US, format, *args)

fun detectHdrAnomalies(records: List<FrameHdrRecord>, container: ContainerHdrStatic, thresholds: HdrThresholds = HdrThresholds()): List<HdrAnomaly> =
    missingHdr10Plus(records) + abruptChanges(records, thresholds) + staticDynamicMismatches(records, container) + containerInBandMismatches(records, container)

fun hdrVerdict(anomalies: List<HdrAnomaly>): HdrVerdict = when {
    anomalies.any { it.severity == HdrSeverity.ERROR } -> HdrVerdict.FAIL
    anomalies.any { it.severity == HdrSeverity.WARN } -> HdrVerdict.WARN
    else -> HdrVerdict.PASS
}

private fun missingHdr10Plus(records: List<FrameHdrRecord>): List<HdrAnomaly> {
    val withPlus = records.count { it.hdr10Plus != null }
    if (withPlus == 0 || withPlus == records.size) return emptyList()
    val out = mutableListOf<HdrAnomaly>()
    var i = 0
    while (i < records.size) {
        if (records[i].hdr10Plus != null) { i++; continue }
        val start = i
        while (i < records.size && records[i].hdr10Plus == null) i++
        val range = records.subList(start, i)
        val hasKeyframe = range.any { it.isSync }
        out += HdrAnomaly(
            HdrRule.HDR10PLUS_MISSING, if (hasKeyframe) HdrSeverity.ERROR else HdrSeverity.WARN,
            range.first().frameNumber, range.last().frameNumber,
            "HDR10+ missing on ${range.size} frame(s)" + if (hasKeyframe) " including a keyframe" else "",
        )
    }
    return out
}

private fun abruptChanges(records: List<FrameHdrRecord>, t: HdrThresholds): List<HdrAnomaly> {
    val out = mutableListOf<HdrAnomaly>()
    for (i in 1 until records.size) {
        val prev = records[i - 1].hdr10Plus?.windows?.firstOrNull() ?: continue
        val cur = records[i].hdr10Plus?.windows?.firstOrNull() ?: continue
        val changes = listOfNotNull(
            describeJump("MaxSCL", prev.maxSclNits, cur.maxSclNits, t),
            describeJump("average maxRGB", prev.averageMaxrgbNits, cur.averageMaxrgbNits, t),
        )
        if (changes.isEmpty()) continue
        val keyframe = records[i].isSync
        out += HdrAnomaly(
            HdrRule.ABRUPT_CHANGE, if (keyframe) HdrSeverity.INFO else HdrSeverity.WARN,
            records[i].frameNumber, records[i].frameNumber,
            changes.joinToString("; ") + if (keyframe) " (keyframe, likely scene cut)" else "",
        )
    }
    return out
}

private fun describeJump(name: String, from: Double, to: Double, t: HdrThresholds): String? {
    val delta = abs(to - from)
    val relative = when {
        from > 0.0 -> delta / from
        delta > 0.0 -> Double.POSITIVE_INFINITY
        else -> 0.0
    }
    if (delta < t.absoluteChangeNits || relative < t.relativeChange) return null
    return f("%s %.1f → %.1f nits", name, from, to)
}

private fun staticDynamicMismatches(records: List<FrameHdrRecord>, container: ContainerHdrStatic): List<HdrAnomaly> {
    val plus = records.mapNotNull { r -> r.hdr10Plus?.let { r to it } }
    if (plus.isEmpty()) return emptyList()
    val mdcv = container.masteringDisplay ?: records.firstNotNullOfOrNull { it.masteringDisplay }
    val cll = container.contentLightLevel ?: records.firstNotNullOfOrNull { it.contentLightLevel }
    val out = mutableListOf<HdrAnomaly>()
    fun aggregate(frames: List<FrameHdrRecord>, message: String) {
        if (frames.isNotEmpty()) {
            out += HdrAnomaly(HdrRule.STATIC_DYNAMIC_MISMATCH, HdrSeverity.WARN, frames.first().frameNumber, frames.last().frameNumber, message)
        }
    }
    if (mdcv != null) {
        val over = plus.filter { (_, m) -> m.windows.first().maxSclNits > mdcv.maxLuminance + 1e-9 }
        aggregate(
            over.map { it.first },
            f("MaxSCL exceeds mastering display max %.0f nits on %d frame(s)", mdcv.maxLuminance, over.size),
        )
        val aboveTarget = plus.filter { (_, m) -> m.targetedSystemDisplayMaximumLuminance > mdcv.maxLuminance + 1e-9 }
        aggregate(
            aboveTarget.map { it.first },
            f("targeted_system_display_maximum_luminance above mastering display max %.0f nits on %d frame(s)", mdcv.maxLuminance, aboveTarget.size),
        )
    }
    val zeroTarget = plus.filter { (_, m) -> m.targetedSystemDisplayMaximumLuminance == 0L }
    aggregate(zeroTarget.map { it.first }, "targeted_system_display_maximum_luminance is 0 on ${zeroTarget.size} frame(s)")
    if (cll != null && cll.maxCll > 0) {
        val (peakRecord, peak) = plus.maxBy { it.second.windows.first().maxSclNits }
        val peakNits = peak.windows.first().maxSclNits
        if (peakNits > cll.maxCll) {
            out += HdrAnomaly(
                HdrRule.STATIC_DYNAMIC_MISMATCH, HdrSeverity.WARN, peakRecord.frameNumber, peakRecord.frameNumber,
                f("Stream peak MaxSCL %.1f nits exceeds MaxCLL %d", peakNits, cll.maxCll),
            )
        }
    }
    if (cll != null && cll.maxFall > 0) {
        val (peakRecord, peak) = plus.maxBy { it.second.windows.first().averageMaxrgbNits }
        val peakNits = peak.windows.first().averageMaxrgbNits
        if (peakNits > cll.maxFall) {
            out += HdrAnomaly(
                HdrRule.STATIC_DYNAMIC_MISMATCH, HdrSeverity.WARN, peakRecord.frameNumber, peakRecord.frameNumber,
                f("Stream peak average maxRGB %.1f nits exceeds MaxFALL %d", peakNits, cll.maxFall),
            )
        }
    }
    return out
}

private fun containerInBandMismatches(records: List<FrameHdrRecord>, container: ContainerHdrStatic): List<HdrAnomaly> {
    val out = mutableListOf<HdrAnomaly>()
    compareStatic(
        "Mastering display", container.masteringDisplay,
        records.mapNotNull { r -> r.masteringDisplay?.let { r to it } }, ::sameMasteringDisplay, out,
    )
    compareStatic(
        "Content light level", container.contentLightLevel,
        records.mapNotNull { r -> r.contentLightLevel?.let { r to it } }, { a, b -> a == b }, out,
    )
    return out
}

private fun <T> compareStatic(
    label: String,
    boxValue: T?,
    inBand: List<Pair<FrameHdrRecord, T>>,
    same: (T, T) -> Boolean,
    out: MutableList<HdrAnomaly>,
) {
    val first = inBand.firstOrNull()
    if (boxValue != null && first == null) {
        out += HdrAnomaly(HdrRule.CONTAINER_INBAND_MISMATCH, HdrSeverity.INFO, null, null, "$label present in container box only")
    }
    if (first != null) {
        val frame = first.first.frameNumber
        if (boxValue == null) {
            out += HdrAnomaly(HdrRule.CONTAINER_INBAND_MISMATCH, HdrSeverity.INFO, frame, null, "$label present in-band only (no container box)")
        } else if (!same(boxValue, first.second)) {
            out += HdrAnomaly(
                HdrRule.CONTAINER_INBAND_MISMATCH, HdrSeverity.WARN, frame, frame,
                "$label differs between container box and in-band (frame $frame)",
            )
        }
    }
    if (first != null) {
        val changed = inBand.firstOrNull { !same(it.second, first.second) }
        if (changed != null) {
            out += HdrAnomaly(
                HdrRule.CONTAINER_INBAND_MISMATCH, HdrSeverity.INFO, changed.first.frameNumber, changed.first.frameNumber,
                "In-band $label changes mid-stream at frame ${changed.first.frameNumber}",
            )
        }
    }
}

// Equal within one quantization step of the coarser of the two encodings, per field.
fun sameMasteringDisplay(a: MasteringDisplay, b: MasteringDisplay): Boolean {
    val chroma = max(a.chromaStep, b.chromaStep) + 1e-9
    val maxLum = max(a.maxLuminanceStep, b.maxLuminanceStep) + 1e-9
    val minLum = max(a.minLuminanceStep, b.minLuminanceStep) + 1e-9
    val chromaPairs = listOf(
        a.redX to b.redX, a.redY to b.redY, a.greenX to b.greenX, a.greenY to b.greenY,
        a.blueX to b.blueX, a.blueY to b.blueY, a.whiteX to b.whiteX, a.whiteY to b.whiteY,
    )
    return chromaPairs.all { (x, y) -> abs(x - y) <= chroma } &&
        abs(a.maxLuminance - b.maxLuminance) <= maxLum &&
        abs(a.minLuminance - b.minLuminance) <= minLum
}
```

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.HdrAnomalyRulesTest'`
Expected: PASS (10 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/HdrAnomalyRules.kt app/src/main/kotlin/com/multiviewer/parser/HdrMetadataScanner.kt app/src/test/kotlin/com/multiviewer/parser/HdrAnomalyRulesTest.kt
git commit -m "feat: detect HDR metadata anomalies

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Whole-stream scanner

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/HdrMetadataScanner.kt` (append)
- Test: `app/src/test/kotlin/com/multiviewer/parser/HdrMetadataScannerTest.kt`

**Interfaces:**
- Consumes: `buildVideoSampleTable(ByteReader, BoxNode)`, `readFrameMetadataUnits(ByteReader, …)`, `readContainerHdrStatic`, `detectHdrAnomalies`, `hdrVerdict`.
- Produces: `data class HdrScanResult(val codec: VideoCodec, val records: List<FrameHdrRecord>, val container: ContainerHdrStatic, val warnings: List<String>)`; `fun scanHdrMetadata(file: File, root: BoxNode, isCancelled: () -> Boolean = { false }, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): HdrScanResult?` — null when the tree has no supported video track **or** when cancelled.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HdrMetadataScannerTest {
    private fun fixture(name: String) = File(javaClass.getResource("/hdr/$name")!!.toURI())
    private fun scan(name: String) = fixture(name).let { assertNotNull(scanHdrMetadata(it, parseFile(it))) }

    @Test
    fun `HEVC HDR10plus fixture end to end`() {
        val r = scan("hevc_hdr10plus.mp4")
        assertEquals(VideoCodec.HEVC, r.codec)
        assertEquals(30, r.records.size)
        // ffprobe reports frame 11's values on 12-14 (sticky side data); the parser must not.
        assertEquals(listOf(12, 13, 14), r.records.filter { it.hdr10Plus == null }.map { it.frameNumber })
        assertEquals(listOf(4000, 4500, 3700), r.records[0].hdr10Plus!!.windows[0].maxscl)
        assertEquals(1000.0, r.records[0].masteringDisplay!!.maxLuminance, 1e-9)
        assertEquals(listOf(0, 10, 20), r.records.filter { it.isSync }.map { it.frameNumber })

        val anomalies = detectHdrAnomalies(r.records, r.container)
        val missing = anomalies.single { it.rule == HdrRule.HDR10PLUS_MISSING }
        assertEquals(12 to 14, missing.firstFrame to missing.lastFrame)
        assertEquals(HdrSeverity.WARN, missing.severity)
        assertEquals(listOf(17, 18), anomalies.filter { it.rule == HdrRule.ABRUPT_CHANGE }.map { it.firstFrame })
        assertTrue(anomalies.none { it.rule == HdrRule.STATIC_DYNAMIC_MISMATCH })
        assertEquals(
            listOf(HdrSeverity.INFO, HdrSeverity.INFO),
            anomalies.filter { it.rule == HdrRule.CONTAINER_INBAND_MISMATCH }.map { it.severity },
        )
        assertEquals(HdrVerdict.WARN, hdrVerdict(anomalies))
    }

    @Test
    fun `B-frame file is in presentation order`() {
        val r = scan("hevc_box.mp4")
        assertTrue(r.records.zipWithNext().all { (a, b) -> a.ptsSeconds < b.ptsSeconds })
        assertTrue(r.records.map { it.sampleIndex } != r.records.indices.toList())
        assertEquals(1000.0, r.container.masteringDisplay!!.maxLuminance, 1e-9)
    }

    @Test
    fun `H264 and AV1 static metadata`() {
        val h264 = scan("h264_hdr.mp4")
        assertEquals(listOf(0, 5), h264.records.filter { it.contentLightLevel != null }.map { it.frameNumber })
        val av1 = scan("av1_hdr.mp4")
        assertEquals(VideoCodec.AV1, av1.codec)
        assertTrue(av1.records.first().masteringDisplay != null)
    }

    @Test
    fun `progress and cancellation`() {
        val file = fixture("hevc_hdr10plus.mp4")
        val root = parseFile(file)
        var lastDone = 0
        scanHdrMetadata(file, root, onProgress = { done, total -> lastDone = done; assertEquals(30, total) })
        assertEquals(30, lastDone)
        assertNull(scanHdrMetadata(file, root, isCancelled = { true }))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.HdrMetadataScannerTest'`
Expected: FAIL — `scanHdrMetadata` unresolved.

- [ ] **Step 3: Implement (append to `HdrMetadataScanner.kt`)**

Add `import java.io.File` at the top of the file, then append:

```kotlin
data class HdrScanResult(
    val codec: VideoCodec,
    val records: List<FrameHdrRecord>,
    val container: ContainerHdrStatic,
    val warnings: List<String>,
)

private const val PROGRESS_EVERY_SAMPLES = 64

// Sequential pass over every video sample. Reads only NAL/OBU headers and SEI/metadata payloads.
// Returns null for an unsupported tree or when isCancelled() turns true; one unreadable sample
// becomes a Malformed unit instead of failing the scan.
fun scanHdrMetadata(
    file: File,
    root: BoxNode,
    isCancelled: () -> Boolean = { false },
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): HdrScanResult? = ByteReader.open(file).use { reader ->
    val table = buildVideoSampleTable(reader, root) ?: return@use null
    val container = readContainerHdrStatic(reader, root)
    val total = table.samples.size
    val scanned = ArrayList<Pair<VideoSample, List<MetadataUnit>>>(total)
    for ((i, sample) in table.samples.withIndex()) {
        if (isCancelled()) return@use null
        val units = try {
            readFrameMetadataUnits(reader, table.codec, table.nalLengthSize, sample.offset, sample.size)
        } catch (e: Exception) {
            listOf(
                MetadataUnit(
                    MetadataSource.SAMPLE_STRUCTURE, -1, "unreadable sample", sample.offset, sample.size, 0,
                    DecodedMetadata.Malformed(e.message ?: e.toString()),
                ),
            )
        }
        scanned += sample to units
        if ((i + 1) % PROGRESS_EVERY_SAMPLES == 0 || i == total - 1) onProgress(i + 1, total)
    }
    val records = scanned
        .sortedWith(compareBy({ it.first.decodeTime + it.first.compositionOffset }, { it.first.index }))
        .mapIndexed { frameNumber, (sample, units) ->
            FrameHdrRecord(
                frameNumber, sample.index,
                (sample.decodeTime + sample.compositionOffset).toDouble() / table.timescale,
                sample.isSync, units,
            )
        }
    HdrScanResult(table.codec, records, container, table.warnings)
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.HdrMetadataScannerTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/HdrMetadataScanner.kt app/src/test/kotlin/com/multiviewer/parser/HdrMetadataScannerTest.kt
git commit -m "feat: scan whole streams for HDR/SEI metadata

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: CSV export

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/HdrMetadataExport.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/HdrMetadataExportTest.kt`

**Interfaces:**
- Consumes: `FrameHdrRecord`, `HdrAnomaly`.
- Produces: `fun buildHdrFramesCsv(records: List<FrameHdrRecord>): String`, `fun buildHdrAnomaliesCsv(anomalies: List<HdrAnomaly>): String` (LF line endings, header row first, RFC 4180 quoting).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.multiviewer.ui

import com.multiviewer.parser.ContentLightLevel
import com.multiviewer.parser.DecodedMetadata
import com.multiviewer.parser.FrameHdrRecord
import com.multiviewer.parser.Hdr10PlusMetadata
import com.multiviewer.parser.Hdr10PlusWindow
import com.multiviewer.parser.HdrAnomaly
import com.multiviewer.parser.HdrRule
import com.multiviewer.parser.HdrSeverity
import com.multiviewer.parser.MetadataSource
import com.multiviewer.parser.MetadataUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class HdrMetadataExportTest {
    @Test
    fun `frames csv`() {
        val plus = MetadataUnit(
            MetadataSource.HEVC_PREFIX_SEI, 4, "user_data_registered_itu_t_t35", 0, 1, 1,
            DecodedMetadata.Hdr10Plus(Hdr10PlusMetadata(1, 1, 1000, listOf(Hdr10PlusWindow(listOf(4000, 4500, 3700), 1000, emptyList(), 0, false, null, null, emptyList())), null)),
        )
        val cll = MetadataUnit(MetadataSource.HEVC_PREFIX_SEI, 144, "content_light_level_info", 0, 1, 4, DecodedMetadata.LightLevel(ContentLightLevel(1000, 400)))
        val csv = buildHdrFramesCsv(listOf(FrameHdrRecord(0, 0, 0.0, true, listOf(cll, plus)), FrameHdrRecord(1, 1, 0.033333, false, emptyList())))
        val lines = csv.trimEnd().lines()
        assertEquals(
            "frame,sample,pts_seconds,keyframe,metadata,maxscl_r_nits,maxscl_g_nits,maxscl_b_nits,average_maxrgb_nits,targeted_max_nits,mdcv_max_nits,mdcv_min_nits,max_cll,max_fall",
            lines[0],
        )
        assertEquals("0,0,0.000000,1,content_light_level_info|user_data_registered_itu_t_t35,400.0,450.0,370.0,100.0,1000,,,1000,400", lines[1])
        assertEquals("1,1,0.033333,0,,,,,,,,,,", lines[2])
    }

    @Test
    fun `anomalies csv quotes commas and quotes`() {
        val csv = buildHdrAnomaliesCsv(listOf(HdrAnomaly(HdrRule.ABRUPT_CHANGE, HdrSeverity.WARN, 17, 17, "MaxSCL 1 → 2 nits; \"x\", y")))
        assertEquals("severity,rule,first_frame,last_frame,message\nWARN,ABRUPT_CHANGE,17,17,\"MaxSCL 1 → 2 nits; \"\"x\"\", y\"\n", csv)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.HdrMetadataExportTest'`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement**

```kotlin
package com.multiviewer.ui

import com.multiviewer.parser.FrameHdrRecord
import com.multiviewer.parser.HdrAnomaly
import java.util.Locale

// Hand-written CSV, same approach as QualityExport.kt (no CSV library in this codebase).
private fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

private fun d1(v: Double) = String.format(Locale.US, "%.1f", v)

fun buildHdrFramesCsv(records: List<FrameHdrRecord>): String = buildString {
    append("frame,sample,pts_seconds,keyframe,metadata,maxscl_r_nits,maxscl_g_nits,maxscl_b_nits,average_maxrgb_nits,targeted_max_nits,mdcv_max_nits,mdcv_min_nits,max_cll,max_fall\n")
    for (r in records) {
        val w = r.hdr10Plus?.windows?.firstOrNull()
        val md = r.masteringDisplay
        val cll = r.contentLightLevel
        val cells = listOf(
            r.frameNumber.toString(),
            r.sampleIndex.toString(),
            String.format(Locale.US, "%.6f", r.ptsSeconds),
            if (r.isSync) "1" else "0",
            r.units.joinToString("|") { it.name },
            w?.let { d1(it.maxscl[0] / 10.0) } ?: "",
            w?.let { d1(it.maxscl[1] / 10.0) } ?: "",
            w?.let { d1(it.maxscl[2] / 10.0) } ?: "",
            w?.let { d1(it.averageMaxrgbNits) } ?: "",
            r.hdr10Plus?.targetedSystemDisplayMaximumLuminance?.toString() ?: "",
            md?.let { String.format(Locale.US, "%.4f", it.maxLuminance) } ?: "",
            md?.let { String.format(Locale.US, "%.4f", it.minLuminance) } ?: "",
            cll?.maxCll?.toString() ?: "",
            cll?.maxFall?.toString() ?: "",
        )
        append(cells.joinToString(",") { csvField(it) }).append('\n')
    }
}

fun buildHdrAnomaliesCsv(anomalies: List<HdrAnomaly>): String = buildString {
    append("severity,rule,first_frame,last_frame,message\n")
    for (a in anomalies) {
        val cells = listOf(a.severity.name, a.rule.name, a.firstFrame?.toString() ?: "", a.lastFrame?.toString() ?: "", a.message)
        append(cells.joinToString(",") { csvField(it) }).append('\n')
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.HdrMetadataExportTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/HdrMetadataExport.kt app/src/test/kotlin/com/multiviewer/ui/HdrMetadataExportTest.kt
git commit -m "feat: export HDR metadata frames and anomalies as CSV

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Detail Properties "SEI / Metadata" section

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt` — inside `DetailPropertiesTabContent`: a new resolver right after the `resolvedApvFrameHeader` block (~line 680), and a new render block right after the `resolvedApvFrameHeader?.let { … }` render block (~line 860).

**Interfaces:**
- Consumes: `detectVideoCodec`, `readFrameMetadataUnits(File, …)`, `summarizeMetadata`, `describeMetadata`, `tab.avcLengthSize`, `tab.hevcLengthSize`, `tab.type`, `tab.parameterSetHighlightRange: LongRange?`, `PropertyRow(label, value, onClick)`.
- Produces: no new API.

- [ ] **Step 1: Add the resolver**

Directly after the closing `}` of the `val resolvedApvFrameHeader = if (…) { … } else { null }` expression, add:

```kotlin
        // SEI / AV1 metadata of the selected frame. Gated on a VIDEO tab and a codec config box so
        // selecting a frame in an image (e.g. a HEIC motion photo, whose tree also has hvcC) never
        // reads sample bytes from the wrong offsets.
        val metadataCodec = remember(root) { root?.let { com.multiviewer.parser.detectVideoCodec(it) } }
        val frameMetadataUnits = if (selectedFrame != null && metadataCodec != null && tab.type == MediaType.VIDEO) {
            val byteOffset = selectedFrame.byteOffset
            val nalLengthSize = when (metadataCodec) {
                com.multiviewer.parser.VideoCodec.H264 -> tab.avcLengthSize
                com.multiviewer.parser.VideoCodec.HEVC -> tab.hevcLengthSize
                com.multiviewer.parser.VideoCodec.AV1 -> null
            }
            produceState<List<com.multiviewer.parser.MetadataUnit>?>(null, selectedFrame, metadataCodec, nalLengthSize) {
                value = null
                if (byteOffset != null && (metadataCodec == com.multiviewer.parser.VideoCodec.AV1 || nalLengthSize != null)) {
                    value = withContext(Dispatchers.IO) {
                        com.multiviewer.parser.readFrameMetadataUnits(
                            tab.file, metadataCodec, nalLengthSize, byteOffset, selectedFrame.sizeBytes.toLong(),
                        )
                    }
                }
            }.value
        } else {
            null
        }
```

- [ ] **Step 2: Add the render block**

Directly after the `resolvedApvFrameHeader?.let { frameHeader -> … }` block closes, add:

```kotlin
                            frameMetadataUnits?.let { units ->
                                Spacer(Modifier.height(8.dp))
                                Text("SEI / Metadata", style = AppTypography.labelLarge.copy(color = AppColors.NeonBlue))
                                if (units.isEmpty()) {
                                    PropertyRow("Messages", "None in this frame")
                                }
                                units.forEach { unit ->
                                    PropertyRow(
                                        "${unit.name} [${unit.source.label} ${unit.typeCode}]",
                                        com.multiviewer.parser.summarizeMetadata(unit),
                                        onClick = { tab.parameterSetHighlightRange = unit.unitOffset until unit.unitOffset + unit.unitSize },
                                    )
                                    com.multiviewer.parser.describeMetadata(unit).forEach { (label, value) ->
                                        PropertyRow("    $label", value)
                                    }
                                }
                            }
```

`Text` in this file must already be `androidx.compose.material3.Text`; confirm:

Run: `grep -n "^import androidx.compose.material.Text\|^import androidx.compose.material3.Text" app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`
Expected: only the `material3` import.

- [ ] **Step 3: Compile and run the full test suite**

Run: `./gradlew :app:compileKotlin && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Manual check**

Run: `./gradlew :app:run`, open `app/src/test/resources/hdr/hevc_hdr10plus.mp4`, run frame analysis, select frame 0.
Expected: a "SEI / Metadata" section with 4 rows (content_light_level_info, mastering_display_colour_volume, user_data_unregistered, user_data_registered_itu_t_t35 with "HDR10+ MaxSCL 450.0 nits …") and detail rows. Clicking a row highlights the NAL in the hex viewer. Selecting frame 12 shows "None in this frame". Opening a JPEG/HEIC and selecting anything shows no section.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt
git commit -m "feat: show per-frame SEI and AV1 metadata in Detail Properties

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: Trend graph, analysis window and menu

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/HdrTrendGraph.kt`
- Create: `app/src/main/kotlin/com/multiviewer/ui/HdrMetadataWindow.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/I18n.kt` (after `menuViewFrameIntervals`)
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt` (window flag near line 437, menu item in the Analyze menu after the frame-interval item, window instantiation after the `avSyncWindowOpen` block near line 826)

**Interfaces:**
- Consumes: `scanHdrMetadata`, `detectHdrAnomalies`, `hdrVerdict`, `HdrThresholds`, `summarizeMetadata`, `detectVideoCodec`, `buildHdrFramesCsv`, `buildHdrAnomaliesCsv`, `TabState`, `AppLanguage`, `AppColors`, `MediaType`.
- Produces: `@Composable fun HdrTrendGraph(records: List<FrameHdrRecord>, referenceLines: List<Pair<String, Double>>, markedFrames: Set<Int>, selectedFrame: Int?, onSelectFrame: (Int) -> Unit, modifier: Modifier = Modifier)`; `@Composable fun HdrMetadataWindow(tab: TabState, language: AppLanguage, onCloseRequest: () -> Unit)`; `fun I18n.menuHdrSeiAnalysis(lang: AppLanguage): String`.

- [ ] **Step 1: Add the I18n string**

In `I18n.kt`, after the `menuViewFrameIntervals` line:

```kotlin
    fun menuHdrSeiAnalysis(lang: AppLanguage) = if (lang == AppLanguage.KO) "HDR/SEI 메타데이터 분석" else "HDR/SEI Metadata Analysis"
```

- [ ] **Step 2: Write `HdrTrendGraph.kt`**

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.FrameHdrRecord
import java.util.Locale
import kotlin.math.roundToInt

private const val HDR_GRAPH_Y_TICKS = 4

// Modelled on MetricGraph.kt (discrete drawLine calls, BoxWithConstraints for axis labels), but with
// three HDR10+ series, dashed horizontal reference lines (MDCV max, MaxCLL, MaxFALL), vertical
// markers for anomaly frames and the selected frame, and click-to-select. Series break (no line) on
// frames without HDR10+, so missing ranges are visible as gaps.
@Composable
fun HdrTrendGraph(
    records: List<FrameHdrRecord>,
    referenceLines: List<Pair<String, Double>>,
    markedFrames: Set<Int>,
    selectedFrame: Int?,
    onSelectFrame: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (records.isEmpty()) return
    val maxSclColor = AppColors.NeonOrange
    val averageColor = AppColors.NeonGreen
    val targetColor = AppColors.NeonPurple
    val referenceColor = AppColors.NeonYellow
    val markerColor = AppColors.NeonRed
    val selectedColor = AppColors.NeonBlue
    val gridColor = AppColors.TextMuted.copy(alpha = 0.25f)
    val labelColor = AppColors.TextSecondary

    val series: List<Pair<Color, (FrameHdrRecord) -> Double?>> = listOf(
        maxSclColor to { r -> r.hdr10Plus?.windows?.firstOrNull()?.maxSclNits },
        averageColor to { r -> r.hdr10Plus?.windows?.firstOrNull()?.averageMaxrgbNits },
        targetColor to { r -> r.hdr10Plus?.targetedSystemDisplayMaximumLuminance?.toDouble() },
    )
    val values = records.flatMap { r -> series.mapNotNull { (_, value) -> value(r) } } + referenceLines.map { it.second }
    val maxValue = (values.maxOrNull() ?: 1.0).coerceAtLeast(1.0) * 1.05
    val lastIndex = (records.size - 1).coerceAtLeast(1)

    BoxWithConstraints(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxSize().pointerInput(records.size) {
                detectTapGestures { tap ->
                    onSelectFrame(((tap.x / size.width) * lastIndex).roundToInt().coerceIn(0, records.size - 1))
                }
            },
        ) {
            fun x(i: Int) = size.width * i / lastIndex
            fun y(v: Double) = (size.height - size.height * (v / maxValue)).toFloat()
            for (tick in 0..HDR_GRAPH_Y_TICKS) {
                val ty = size.height - size.height * tick / HDR_GRAPH_Y_TICKS
                drawLine(gridColor, Offset(0f, ty), Offset(size.width, ty), strokeWidth = 1f)
            }
            markedFrames.forEach { f -> drawLine(markerColor.copy(alpha = 0.5f), Offset(x(f), 0f), Offset(x(f), size.height), strokeWidth = 2f) }
            selectedFrame?.let { f -> drawLine(selectedColor, Offset(x(f), 0f), Offset(x(f), size.height), strokeWidth = 2f) }
            referenceLines.forEach { (_, v) ->
                drawLine(
                    referenceColor, Offset(0f, y(v)), Offset(size.width, y(v)), strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                )
            }
            series.forEach { (color, value) ->
                for (i in 0 until records.size - 1) {
                    val a = value(records[i]) ?: continue
                    val b = value(records[i + 1]) ?: continue
                    drawLine(color, Offset(x(i), y(a)), Offset(x(i + 1), y(b)), strokeWidth = 2f)
                }
            }
        }
        for (tick in 0..HDR_GRAPH_Y_TICKS) {
            val value = maxValue * tick / HDR_GRAPH_Y_TICKS
            Text(
                String.format(Locale.US, "%.0f", value),
                color = labelColor,
                fontSize = 10.sp,
                modifier = Modifier.offset(y = maxHeight * (1f - tick.toFloat() / HDR_GRAPH_Y_TICKS)),
            )
        }
        referenceLines.forEach { (label, v) ->
            Text(
                String.format(Locale.US, "%s %.0f", label, v),
                color = referenceColor,
                fontSize = 10.sp,
                modifier = Modifier.offset(x = maxWidth * 0.8f, y = maxHeight * (1f - (v / maxValue).toFloat())),
            )
        }
    }
}
```

- [ ] **Step 3: Write `HdrMetadataWindow.kt`**

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.parser.FrameHdrRecord
import com.multiviewer.parser.HdrScanResult
import com.multiviewer.parser.HdrSeverity
import com.multiviewer.parser.HdrThresholds
import com.multiviewer.parser.HdrVerdict
import com.multiviewer.parser.detectHdrAnomalies
import com.multiviewer.parser.hdrVerdict
import com.multiviewer.parser.scanHdrMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale

@Composable
fun HdrMetadataWindow(tab: TabState, language: AppLanguage, onCloseRequest: () -> Unit) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english

    Window(
        onCloseRequest = onCloseRequest,
        state = rememberWindowState(size = DpSize(1100.dp, 860.dp), position = WindowPosition(Alignment.Center)),
        title = "${I18n.menuHdrSeiAnalysis(language)} - ${tab.file.name}",
    ) {
        var result by remember(tab.file) { mutableStateOf<HdrScanResult?>(null) }
        var progress by remember(tab.file) { mutableStateOf(0f) }
        var scanning by remember(tab.file) { mutableStateOf(true) }
        var error by remember(tab.file) { mutableStateOf<String?>(null) }
        var relativeText by remember { mutableStateOf("50") }
        var absoluteText by remember { mutableStateOf("100") }
        var selectedFrame by remember(tab.file) { mutableStateOf<Int?>(null) }
        val tableState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        // Runs in this Window's composition: closing the window cancels the LaunchedEffect's job,
        // which isCancelled observes between samples.
        LaunchedEffect(tab.file) {
            val root = tab.root
            if (root == null) {
                error = label("파싱된 구조가 없습니다", "No parsed structure")
                scanning = false
                return@LaunchedEffect
            }
            val job = coroutineContext[Job]
            val scanned = withContext(Dispatchers.IO) {
                try {
                    scanHdrMetadata(tab.file, root, isCancelled = { job?.isActive == false }) { done, total ->
                        progress = if (total > 0) done.toFloat() / total else 0f
                    }
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                    null
                }
            }
            result = scanned
            if (scanned == null && error == null) {
                error = label("지원하지 않는 비디오 트랙입니다 (MP4/MOV의 H.264·HEVC·AV1만 지원)", "Unsupported video track (H.264/HEVC/AV1 in MP4/MOV only)")
            }
            scanning = false
        }

        val thresholds = HdrThresholds(
            relativeChange = (relativeText.toDoubleOrNull() ?: 50.0) / 100.0,
            absoluteChangeNits = absoluteText.toDoubleOrNull() ?: 100.0,
        )
        val current = result
        val anomalies = remember(current, thresholds) { current?.let { detectHdrAnomalies(it.records, it.container, thresholds) } ?: emptyList() }

        Column(modifier = Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                scanning -> {
                    Text(label("메타데이터 스캔 중…", "Scanning metadata…"), color = AppColors.TextPrimary)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                error != null -> Text(label("오류: ", "Error: ") + error, color = AppColors.NeonRed)
                current != null -> {
                    val records = current.records
                    val plusCount = records.count { it.hdr10Plus != null }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        VerdictBadge(hdrVerdict(anomalies))
                        Text(
                            String.format(
                                Locale.US, "%s · %d frames · HDR10+ %d/%d (%.1f%%)",
                                current.codec.name, records.size, plusCount, records.size,
                                if (records.isEmpty()) 0.0 else plusCount * 100.0 / records.size,
                            ),
                            color = AppColors.TextPrimary, fontSize = 13.sp,
                        )
                    }
                    val inBandMd = records.firstNotNullOfOrNull { it.masteringDisplay }
                    val inBandCll = records.firstNotNullOfOrNull { it.contentLightLevel }
                    StaticRow(label("컨테이너 MDCV / CLL", "Container MDCV / CLL"), current.container.masteringDisplay, current.container.contentLightLevel)
                    StaticRow(label("비트스트림 MDCV / CLL (첫 값)", "In-band MDCV / CLL (first)"), inBandMd, inBandCll)

                    val mdcv = current.container.masteringDisplay ?: inBandMd
                    val cll = current.container.contentLightLevel ?: inBandCll
                    val references = listOfNotNull(
                        mdcv?.let { "MDCV max" to it.maxLuminance },
                        cll?.takeIf { it.maxCll > 0 }?.let { "MaxCLL" to it.maxCll.toDouble() },
                        cll?.takeIf { it.maxFall > 0 }?.let { "MaxFALL" to it.maxFall.toDouble() },
                    )
                    val marked = anomalies
                        .filter { it.severity != HdrSeverity.INFO }
                        .flatMap { a -> val first = a.firstFrame ?: return@flatMap emptyList(); (first..(a.lastFrame ?: first)).toList() }
                        .toSet()
                    if (plusCount > 0) {
                        HdrTrendGraph(
                            records, references, marked, selectedFrame,
                            onSelectFrame = { f -> selectedFrame = f; scope.launch { tableState.animateScrollToItem(f) } },
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Legend(AppColors.NeonOrange, "MaxSCL")
                            Legend(AppColors.NeonGreen, "average maxRGB")
                            Legend(AppColors.NeonPurple, "targeted display max")
                            Legend(AppColors.NeonYellow, label("기준선 (MDCV/CLL)", "references (MDCV/CLL)"))
                        }
                    } else {
                        Text(label("HDR10+ 메타데이터가 없어 그래프를 생략합니다", "No HDR10+ metadata; graph omitted"), color = AppColors.TextSecondary, fontSize = 12.sp)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label("급변 임계값", "Abrupt-change thresholds"), color = AppColors.TextSecondary, fontSize = 12.sp)
                        OutlinedTextField(relativeText, { relativeText = it }, label = { Text("%") }, singleLine = true, modifier = Modifier.width(90.dp))
                        OutlinedTextField(absoluteText, { absoluteText = it }, label = { Text("nits") }, singleLine = true, modifier = Modifier.width(110.dp))
                        OutlinedButton(onClick = { saveCsv(label("프레임 CSV 저장", "Save frames CSV"), "${tab.file.nameWithoutExtension}-hdr-frames.csv", buildHdrFramesCsv(records)) }) {
                            Text(label("프레임 CSV", "Frames CSV"))
                        }
                        OutlinedButton(onClick = { saveCsv(label("이상 목록 CSV 저장", "Save anomalies CSV"), "${tab.file.nameWithoutExtension}-hdr-anomalies.csv", buildHdrAnomaliesCsv(anomalies)) }) {
                            Text(label("이상 목록 CSV", "Anomalies CSV"))
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LazyColumn(modifier = Modifier.weight(0.4f).fillMaxSize()) {
                            item { Text(label("이상 항목 (${anomalies.size})", "Anomalies (${anomalies.size})"), color = AppColors.NeonBlue, fontWeight = FontWeight.Bold) }
                            itemsIndexed(anomalies) { _, a ->
                                Text(
                                    "[${a.severity}] ${a.firstFrame?.let { f -> if (a.lastFrame != null && a.lastFrame != f) "#$f–${a.lastFrame} " else "#$f " } ?: ""}${a.message}",
                                    color = severityTextColor(a.severity),
                                    fontSize = 12.sp,
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = a.firstFrame != null) {
                                        val f = a.firstFrame ?: return@clickable
                                        selectedFrame = f
                                        scope.launch { tableState.animateScrollToItem(f) }
                                    }.padding(vertical = 3.dp),
                                )
                            }
                        }
                        LazyColumn(state = tableState, modifier = Modifier.weight(0.6f).fillMaxSize()) {
                            itemsIndexed(records) { index, r -> FrameRow(r, selected = index == selectedFrame) { selectedFrame = index } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun severityTextColor(severity: HdrSeverity): Color = when (severity) {
    HdrSeverity.ERROR -> AppColors.NeonRed
    HdrSeverity.WARN -> AppColors.NeonYellow
    HdrSeverity.INFO -> AppColors.TextSecondary
}

@Composable
private fun VerdictBadge(verdict: HdrVerdict) {
    val color = when (verdict) {
        HdrVerdict.PASS -> AppColors.NeonGreen
        HdrVerdict.WARN -> AppColors.NeonYellow
        HdrVerdict.FAIL -> AppColors.NeonRed
    }
    Text(
        verdict.name, color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp,
        modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .border(1.dp, color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun StaticRow(title: String, md: com.multiviewer.parser.MasteringDisplay?, cll: com.multiviewer.parser.ContentLightLevel?) {
    val mdText = md?.let { String.format(Locale.US, "max %.0f / min %.4f cd/m²", it.maxLuminance, it.minLuminance) } ?: "-"
    val cllText = cll?.let { "MaxCLL ${it.maxCll} / MaxFALL ${it.maxFall}" } ?: "-"
    Text("$title: $mdText · $cllText", color = AppColors.TextSecondary, fontSize = 12.sp)
}

@Composable
private fun Legend(color: Color, text: String) {
    Text("━ $text", color = color, fontSize = 11.sp)
}

@Composable
private fun FrameRow(r: FrameHdrRecord, selected: Boolean, onClick: () -> Unit) {
    val w = r.hdr10Plus?.windows?.firstOrNull()
    val line = String.format(
        Locale.US, "#%-5d %9.3fs %s  %-8s %-8s %-6s  %s",
        r.frameNumber, r.ptsSeconds, if (r.isSync) "K" else " ",
        w?.let { String.format(Locale.US, "%.1f", it.maxSclNits) } ?: "-",
        w?.let { String.format(Locale.US, "%.1f", it.averageMaxrgbNits) } ?: "-",
        r.hdr10Plus?.targetedSystemDisplayMaximumLuminance?.toString() ?: "-",
        r.units.joinToString(", ") { it.name },
    )
    Text(
        line,
        color = if (r.hdr10Plus == null) AppColors.TextMuted else AppColors.TextPrimary,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) AppColors.Selection else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp, horizontal = 4.dp),
    )
}

private fun saveCsv(title: String, defaultName: String, content: String) {
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true
    val output = dialog.file?.let { File(dialog.directory, it) }
    dialog.dispose()
    output?.writeText(content)
}
```

- [ ] **Step 4: Wire the menu and window in `Main.kt`**

Next to `var avSyncWindowOpen by remember { mutableStateOf(false) }` add:

```kotlin
        var hdrMetadataWindowOpen by remember { mutableStateOf(false) }
```

In the Analyze menu, right after the `I18n.menuViewFrameIntervals(language)` item block, add:

```kotlin
                val hasMetadataCodec = isVideo && currentTab?.root?.let { com.multiviewer.parser.detectVideoCodec(it) } != null
                item(
                    I18n.menuHdrSeiAnalysis(language),
                    enabled = hasMetadataCodec,
                    onClick = { hdrMetadataWindowOpen = true },
                )
```

After the `if (avSyncWindowOpen) { … }` block, add:

```kotlin
            if (hdrMetadataWindowOpen) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                if (currentTab != null) {
                    HdrMetadataWindow(tab = currentTab, language = language, onCloseRequest = { hdrMetadataWindowOpen = false })
                } else {
                    hdrMetadataWindowOpen = false
                }
            }
```

If `HdrMetadataWindow` is not resolved in `Main.kt`, add `import com.multiviewer.ui.HdrMetadataWindow` beside the existing `com.multiviewer.ui.*` imports (check how `AvSyncAnalysisWindow` is imported and mirror it).

- [ ] **Step 5: Compile and run the full suite**

Run: `./gradlew :app:compileKotlin && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Manual check**

Run `./gradlew :app:run`, open `app/src/test/resources/hdr/hevc_hdr10plus.mp4`, choose Analyze → HDR/SEI Metadata Analysis.
Expected:
- Badge **WARN**. Summary `HEVC · 30 frames · HDR10+ 27/30 (90.0%)`.
- Container row `- · -`; in-band row `max 1000 / min 0.0050 cd/m² · MaxCLL 1000 / MaxFALL 400`.
- Graph: three series with a gap at frames 12–14 and a spike at 17, dashed MDCV max / MaxCLL / MaxFALL lines, red markers at 12–14, 17, 18.
- Anomalies: `[WARN] #12–14 HDR10+ missing on 3 frame(s)`, `[WARN] #17 …`, `[WARN] #18 …`, two INFO rows "present in-band only".
- Clicking an anomaly or the graph selects and scrolls the table. Typing 60 into the % field removes the frame-18 anomaly.
- Both CSV buttons save files that open correctly.
- Closing the window while a large file (any multi-GB HEVC recording) is still scanning stops the scan (CPU drops back immediately).
- The menu item is disabled for an image tab and for an audio file.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/HdrTrendGraph.kt app/src/main/kotlin/com/multiviewer/ui/HdrMetadataWindow.kt app/src/main/kotlin/com/multiviewer/ui/I18n.kt app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "feat: add HDR/SEI metadata analysis window

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Final verification

**Files:** none (verification only; fix-ups go in their own commits).

- [ ] **Step 1: Full test suite**

Run: `./gradlew :app:test`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 2: Cross-check against ffprobe on all fixtures**

Run: `for f in app/src/test/resources/hdr/*.mp4; do echo "== $f"; ffprobe -v error -select_streams v:0 -show_frames -read_intervals "%+#1" "$f" | grep -E "side_data_type|max_luminance|min_luminance|max_content|max_average|maxscl=" ; done`
Expected: every value matches what the Detail Properties section shows for frame 0 of the same file (luminance as rationals: `max_luminance=10000000/10000` = 1000 nits; AV1 `256000/256` = 1000; `maxscl=4000/100000` = 400.0 nits).

- [ ] **Step 3: Optional real-device file**

If the user supplies a Galaxy HDR10+ recording, open it and run the window. Record the codec, frame count, HDR10+ coverage and anomaly list in the PR/merge notes. Do not commit the file.

- [ ] **Step 4: Windows-specific review question**

Confirm no new code builds file paths by string concatenation or passes paths into ffmpeg filter strings (this feature runs no external process). `grep -n "ffmpeg\|ProcessBuilder" app/src/main/kotlin/com/multiviewer/parser/{Sei*,Hdr*,Av1MetadataObu,FrameMetadataReader,IsoSampleTable,MdcvClliBoxDecoder}.kt app/src/main/kotlin/com/multiviewer/ui/Hdr*.kt` → expected: no matches.
