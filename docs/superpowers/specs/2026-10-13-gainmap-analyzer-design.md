# Gain Map Analyzer — design

Date: 2026-10-13. Approved direction (user, in Korean): "추천순서대로 진행" — consolidate the gain map menu
and windows, decode ISO 21496-1 binary metadata, add an HDR rendering preview with a headroom slider, add a
gain map conformance check. Delivered in four phases on one branch (`feature/gainmap-analyzer`).

## Problems found (verified on `app/src/test/resources/c2pa/s26-*`)

1. **Two windows for one feature.** `GainmapImagePopupWindow` (⌘G) and `GainmapXmpWindow` (⌘⇧G) each have a
   button that opens the other; the same parameters are shown in the overview summary box, the XMP window's
   Parameters tab and the image popup caption. "Extract & save" is both a menu item and a popup button.
2. **Misleading names.** "게인맵 XMP 메타데이터 보기" opens a window whose first tab is interpreted
   parameters; ISO/Apple metadata is not XMP at all.
3. **Unrelated item in the menu.** "파일 내 XMP 정보" lists all XMP in any file (not gain-map specific).
4. **Misclassification.**
   - S26 JPEG → `APPLE_MPF_JPEG`. Real content: GContainer XMP (`Item:Semantic="GainMap"`), `hdrgm` XMP in
     the gain map image, ISO 21496-1 APP2 in both images, MPF. The MPF path defaults to Apple whenever the
     XMP does not literally contain "21496".
   - S26 HEIC → `APPLE_HEIC`. Real content: `ftyp` compatible brand `tmap`, item 56 of type `tmap`,
     `auxC` = `urn:com:samsung:photo:2024:aux:hdrgainmap`, `iref dimg 56 → [49, 55]`, `grpl altr [56, 49]`.
5. **ISO 21496-1 binary metadata is never decoded.** Parameters come only from `hdrgm` XMP; a file carrying
   only ISO metadata (the Android 15+/iOS 18 direction) shows no parameters.

## Verified byte layouts

ISO 21496-1 gain map metadata (big-endian), as written by S26 (JPEG gain map image APP2 after the
`urn:iso:std:iso:ts:21496:-1\0` identifier, 61 bytes; HEIC `tmap` item = 1 version byte `00` + the same 61
bytes, stored in `idat` after two 8-byte `grid` descriptors):

```
u16 minimum_version = 0, u16 writer_version = 0
u8  flags = 0x40          bit7 multichannel (3 ch), bit6 use_base_colour_space,
                          bit3 common denominator, bit2 backward direction (base is HDR)
-- flags & 0x08 == 0 (S26):
u32 base_hdr_headroom N/D        = 0/1
u32 alternate_hdr_headroom N/D   = 0x933333/0x400000 = 2.3
per channel: s32/u32 gain_map_min N/D = 0/1, s32/u32 gain_map_max N/D = 2.3,
             u32/u32 gamma N/D = 1/1, s32/u32 base_offset N/D = 0/1, s32/u32 alternate_offset N/D = 0/1
-- flags & 0x08 != 0: u32 common_denominator, then the numerators only, same order.
```

The primary JPEG image carries a 4-byte version-only ISO APP2 (`00000000`). Values agree with the `hdrgm`
XMP (`GainMapMax=2.3`, `HDRCapacityMax=2.3`, offsets 0). The common-denominator and multichannel paths are
implemented from the libultrahdr layout and tested with hand-built payloads only (no encoder available
here to produce a real one) — noted as unverified.

Mapping ISO → the existing `GainmapParsedParameters` (log2 units where hdrgm uses log2):
`hdrCapacityMin = base_hdr_headroom`, `hdrCapacityMax = alternate_hdr_headroom`,
`offsetSdr = base_offset`, `offsetHdr = alternate_offset`, `baseRenditionIsHdr = backward` (swap the pairs
when backward).

## Design

### Phase 1 — one window, a smaller menu
- `GainmapAnalyzerWindow(tab, initialPage)` replaces both windows. Pages: **개요**, **게인맵 이미지**,
  **HDR 미리보기** (phase 3), **검사** (phase 4), **메타데이터 원본**.
- Menu 게인맵: `게인맵 분석기…` (⌘G), `게인맵 이미지 저장…`. ⌘⇧G is dropped. `파일 내 XMP 정보` moves to 분석.
- `TabState.gainmapAnalyzerPage: GainmapAnalyzerPage?` replaces the two booleans; the overview summary box
  buttons open the analyzer on the matching page.

### Phase 2 — ISO 21496-1 metadata + correct classification
- `parser/IsoGainmapMetadata.kt`: `IsoGainmapMetadata` + `parseIsoGainmapMetadata(bytes): IsoGainmapMetadata?`
  (null on truncation / unknown minimum_version) + `toParsedParameters()`.
- JPEG: read the ISO APP2 of the gain map image (and note the primary's version block). HEIF/AVIF: read the
  `tmap` item's data via `iloc` (construction_method 1 = `idat`, 0 = file offset).
- `GainmapInfo` gains `isoMetadata`, `xmpParameters` (what hdrgm said), `metadataSources`
  (`HDRGM_XMP`, `ISO_21496_1`, `GCONTAINER`, `MPF`, `HEIF_TMAP`, `HEIF_AUXC`) and `auxType`.
  `parameters` becomes the effective set: ISO when present, otherwise XMP.
- Classification by evidence: ISO binary present → `ISO_21496_1_*`; GContainer/hdrgm → `ULTRA_HDR_JPEG`;
  Apple only when Apple markers exist (auxC `urn:com:apple:photo`, Apple MakerNote/`HDRGainMap` XMP).

### Phase 3 — HDR rendering preview
- Pure math in `parser/GainmapMath.kt` (unit-tested): per pixel, gain map value `g∈[0,1]` →
  `logBoost = lerp(min, max, g^(1/gamma))`, weight `w = clamp((log2(H) − capMin)/(capMax − capMin), 0, 1)`,
  `hdr = (sdr + offsetSdr)·2^(logBoost·w) − offsetHdr` in linear light (sRGB EOTF on the base).
- The display is SDR, so the preview shows `hdr / H` clipped — what a display with headroom `H` shows
  relative to SDR white — plus a **boost heat map** mode and a cursor readout (stops at that pixel).
- Slider: `H` from 1× to `2^capMax` (shown in stops). Rendered off the UI thread at ≤1600 px longest edge,
  gain map sampled bilinearly. Base colour gamut is ignored (stated in the UI).

### Phase 4 — conformance check
- `parser/GainmapValidator.kt` → `List<IntegrityCheckItem>` (existing model), shown in the analyzer's 검사
  page and as a new Content Check tab **게인맵** for images with a gain map.
- Checks: metadata present; XMP ↔ ISO agreement (per field, 1e-3); min ≤ max, capMin < capMax, gamma > 0;
  `capMax < gainMapMax` (boost can't be fully shown) as WARN; gain map aspect ratio vs base (1%); JPEG: MPF
  entries land on SOI inside the file, GContainer `Item:Length` equals the actual gain map length, primary
  ISO version APP2 present when the gain map has ISO; HEIF: `tmap` brand in `ftyp`, `tmap` `dimg` to the
  primary and the gain map, `altr` group containing `tmap` and the primary. Unreadable/unsupported → WARN,
  never PASS (AGENTS rule 7).

## Out of scope
Writing/converting gain maps, gamut conversion for P3/BT.2020 bases, AVIF `tmap` fixtures (no encoder here —
the code path is shared with HEIC).
