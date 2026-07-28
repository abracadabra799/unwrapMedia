# JPEG Marker Detail Enrichment (JPEGsnoop-style) Design

## Goal

Make the Detailed Properties panel show richer, more interpreted information for JPEG markers when selected in the structure tree, benchmarked against JPEGsnoop's level of detail -- without taking on JPEGsnoop's much larger scope (its own DC-only IDCT decoder, quantization overflow/"clipping" detection, or its 3347-entry camera compression-signature database, all explicitly out of scope).

## Background

unwrapMedia's JPEG parser (`JpegWalker.kt`) already decodes APP0/DQT/SOF/DHT/SOS/COM segments into `BoxNode`/`BoxField` structures, rendered generically by `DetailedPropertiesPanel` (type/offset/size, then every field as a name/value row, then grid/table/warnings). Comparing this against a real JPEGsnoop 1.8.0 output (provided by the user) surfaced two categories of gaps:

1. **Interpretation gaps in already-parsed fields** -- we parse the raw bytes correctly but display them as raw numbers/hex where JPEGsnoop shows a human-readable label (e.g. `destination_id=1` vs `1 (Chrominance)`).
2. **A genuinely new feature**: JPEGsnoop's "Decoding SCAN Data" section reports whole-image pixel statistics (average luminance, brightest pixel location) computed by actually decoding the compressed scan data. unwrapMedia never runs its own JPEG entropy decoder (it defers to Skia for the real pixel decode) -- but the primary bitmap Skia already decodes for the preview can be reused to compute the same statistics without writing a decoder.

JPEGsnoop's "YCC/RGB clipping" statistics are explicitly excluded: those measure quantization-coefficient overflow in JPEGsnoop's own simplified DC-only IDCT approximation, a defect signal about the *encoding*. Counting saturated (0/255) pixels in Skia's final, fully-decoded RGB output is a different, weaker signal (it just reflects image content -- a black background isn't a defect) and would misrepresent what JPEGsnoop's number means if labeled the same way. Not included.

## Scope

### A. Marker-level parsing enrichments (pure parser layer, `JpegWalker.kt`)

No new architecture -- these are additional derived `BoxField`s computed from bytes the walker already reads.

- **DQT** (`decodeDqt`): `destination_id` field's value gains a label suffix: `"0 (Luminance)"` / `"1 (Chrominance)"` (any other ID stays a bare number -- JPEG only defines 0/1 by convention, not by spec).
- **SOF0/SOF1/SOF2/... (SOF_MARKERS)** (`decodeSof`): for each component:
  - `component_id` value gains a conventional name suffix when it matches the common 1/2/3 = Y/Cb/Cr convention: `"1 (Y)"`, `"2 (Cb)"`, `"3 (Cr)"` (bare number otherwise).
  - `sampling_factors` value gains a decoded subsampling suffix, e.g. `"0x22 (2x2)"` -- high nibble = horizontal, low nibble = vertical.
  - `quantization_table` value gains the same Luminance/Chrominance label as DQT's `destination_id` (0 → Luminance, 1 → Chrominance).
  - New segment-level field `orientation`: `"Portrait"` if height > width, `"Landscape"` if width > height, `"Square"` if equal -- derived from the same width/height already parsed.
- **DHT** (`decodeDht`): new fields `codes_length_01` .. `codes_length_16`, one per bit-length that has at least one code, each value a comma-separated list of that length's symbol bytes in hex (2 uppercase hex digits, e.g. `"06, 07"`) -- lengths with zero codes get no field (matches JPEGsnoop's own "000 total" rows being informational, not data-bearing; we skip emitting redundant empty fields, staying consistent with this codebase's "only emit a field when there's something to say" convention elsewhere). The symbol bytes are already present in the table's payload (`totalCodes` bytes immediately following the 16 bit-count bytes, ordered by length); the walker doesn't need to read anything it doesn't already read, only track and group it.
- **SOS** (`decodeSos`): `dc_table` and `ac_table` field values gain the same component name suffix as SOF's `component_id`, matched via that scan component's `component_selector`. (No cross-reference into a sibling SOF node is needed -- 1/2/3 convention only.)

None of the above changes `BoxField.name` (existing tests/consumers that key off field names by name are unaffected) -- only the `value` string gains a suffix. This keeps the change additive and low-risk.

### B. New feature: Scan Statistics (SOS-node-triggered, UI layer)

Computed lazily, once, when the user selects the SOS node in the structure tree -- not during file open (would cost every JPEG a full pixel scan even when nobody looks at SOS).

**Trigger and data source**: `DetailedPropertiesPanel` (already receives `tab: TabState`) adds a branch: when `selectedNode?.type == "SOS"`, render a new `SosScanStatistics(tab)` composable below the existing field list. That composable:
- Reads `tab.imageForensic?.bitmap` (the already-decoded primary preview bitmap -- same one the center panel displays, same one `ImageAnalyzer.calculateHistogram` already samples from).
- If null and `tab.imageForensic?.isDecodingFallback == true`: show `DecodingIndicator("이미지 디코딩 대기 중...")` (matches this app's established async-decode UI pattern).
- If null and not decoding: show nothing (primary decode failed outright -- covered by the existing "Primary Image Decoding Failed" message elsewhere in the UI; no need to duplicate that here).
- Once a bitmap is available: `LaunchedEffect(selectedNode, forensic.bitmap)` kicks off `withContext(Dispatchers.IO) { computeScanStatistics(bitmap.asSkiaBitmap()) }` and renders the result when it resolves. Keying on `forensic.bitmap` (not just `selectedNode`) means if the user selects SOS before the async fallback decode finishes, the stats still compute automatically once the bitmap arrives, without requiring a re-click.

**`computeScanStatistics(bitmap: Bitmap): ScanStatistics`** (new pure function, e.g. in a new `JpegScanStatistics.kt` in the parser package so it's independently testable with a synthetic `Bitmap`, no ffmpeg/real file needed):
- Full pixel scan (every pixel, not sampled -- accuracy over speed per the approved design choice; expected cost is a plain O(width×height) loop, same order of magnitude as `calculateHistogram`'s already-accepted per-image cost).
- For each pixel: `y = 0.299*r + 0.587*g + 0.114*b`, accumulate a running sum for the average and track the single maximum along with its `(x, y)` pixel coordinate and `(r, g, b)`.
- Returns `data class ScanStatistics(val averageLuminance: Double, val brightestX: Int, val brightestY: Int, val brightestR: Int, val brightestG: Int, val brightestB: Int)`.

**Display**: a new "Scan Statistics" section (label styled consistent with the existing "Warnings:" section already in `DetailedPropertiesPanel`) with two rows:
- `Average Pixel Luminance (Y): <value, 1 decimal> (range: 0..255)`
- `Brightest Pixel: RGB=[r, g, b] @ (x, y)`

Plain pixel coordinates, not JPEGsnoop's MCU-grid coordinates -- this app doesn't do its own block/MCU-level decode (no self-implemented IDCT), so an MCU coordinate system doesn't exist here to report against; pixel coordinates are the natural, already-meaningful unit and more directly usable (e.g. for cross-referencing against the hex view or the on-screen preview) than a JPEGsnoop-specific MCU index would be.

## Non-Goals (explicitly out of scope)

- JPEGsnoop's own DC-only IDCT / Huffman-code-histogram-during-decode statistics -- would require implementing a real (if simplified) JPEG entropy decoder from scratch; this app deliberately defers all real pixel decoding to Skia/ffmpeg.
- YCC/RGB "clipping" statistics -- see Background; not meaningfully reproducible from Skia's final decoded output.
- Camera compression-signature database matching (3347 built-in signatures) -- a large, separate undertaking (would need to source/license or build an equivalent signature database), already flagged out of scope in an earlier conversation.

## Testing

- **Marker enrichments**: extend `JpegWalkerTest.kt` with synthetic segment fixtures (matching its existing style) asserting the new label suffixes appear on `destination_id`, `sampling_factors`, `quantization_table`, `component_id`, `orientation`, the new `codes_length_NN` fields, and SOS's labeled `dc_table`/`ac_table`.
- **Scan statistics**: new `JpegScanStatisticsTest.kt` constructing a small synthetic Skia `Bitmap` with known pixel values (e.g. a 4x4 image with one deliberately bright pixel at a known coordinate) and asserting `computeScanStatistics` returns the exact expected average and brightest-pixel location/color -- no real JPEG file, ffmpeg, or UI involved.
- No end-to-end UI test is planned for the SOS-selection trigger itself (this codebase doesn't have Compose UI tests elsewhere either); manual verification via `./gradlew :app:run` opening a real JPEG and selecting its SOS node, per this project's established smoke-test convention.
