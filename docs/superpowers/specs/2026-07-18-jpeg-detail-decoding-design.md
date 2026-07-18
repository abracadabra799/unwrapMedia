# JPEG Detail Decoding (DQT/DHT/SOS/COM/APP0) — Design

## Background

The JPEG file support feature (already shipped) deliberately treats `DQT`, `DHT`, `SOS`, `COM`, and `APPn` (other than `APP1`) as structural-only nodes — marker name, offset, and size, with no field decode — matching that feature's stated non-goals. The user wants the detail panel to show information closer to what [JPEGsnoop](https://www.impulseadventure.com/photo/jpeg-snoop.html) shows when a marker is selected: per-marker technical fields, the quantization/Huffman table contents, and a JPEG quality estimate. JPEGsnoop's own decoder source (`JfifDecode.cpp` in the open-source [jerem/JPEGsnoop](https://github.com/jerem/JPEGsnoop) repository) and public search results describing its output format (e.g. `*** Marker: SOF0 (Baseline DCT) (xFFC0) *** OFFSET: 0x000000E3`, `*** Marker: DHT (Define Huffman Table) (xFFC4) *** OFFSET: 0x00009B51 Huffman table length = 31 ---- Destination ID = 0 Class = 0`) were consulted to ground this design in what JPEGsnoop actually shows, not just what it's known for.

## Goal

Add detailed field decoding, matching the depth (not the exact textual format) of JPEGsnoop, for:
1. **DQT** (Define Quantization Table) — per-table `precision`, `destination_id`, an estimated JPEG quality percentage, and the 64 coefficients shown as an 8×8 grid in natural (de-zigzagged) raster order.
2. **DHT** (Define Huffman Table) — per-table `class` (DC/AC), `destination_id`, per-code-length symbol counts, and total code count.
3. **SOS** (Start of Scan) header — number of scan components, per-component DC/AC Huffman table selectors, spectral selection range, successive approximation bits. (The existing entropy-coded scan-data size computation is unchanged — this only adds header field decoding.)
4. **COM** (Comment) — the comment text.
5. **APP0** (JFIF) — version, density units, X/Y density, thumbnail dimensions.

## Non-Goals

- **Encoder/software signature detection.** JPEGsnoop's best-known feature — matching a file's DQT tables against a database of known encoder signatures (Photoshop versions, camera models, etc.) to guess what software last saved the file — requires its own signature database and is explicitly deferred to a future feature, per the user's explicit choice this round.
- **APP2 (ICC profile), APP13 (Photoshop/IPTC), APP14 (Adobe)** decoding remain out of scope, unchanged from the original JPEG feature's non-goals.
- **Actual entropy (Huffman) decoding, DCT coefficient extraction, or pixel reconstruction.** This tool stays at the structural/header level, consistent with the project's scope for every other format it supports (ISOBMFF box parsing, not video/audio decoding).
- **Rendering thumbnail bitmaps** (JFIF's embedded thumbnail, if present) as an actual image — only its declared pixel dimensions are shown as fields, matching how this project has never rendered embedded media, only described it structurally.
- **Compression ratio / file-size-based statistics** that JPEGsnoop also shows — out of scope; quality estimation from DQT tables is the only "derived" metric being added.

## Design

### 1. New `GridData` model (`BoxNode.kt`)

`TableData` (the existing table model) is a *view into a contiguous range of file bytes*, read sequentially via `readTableRow` — appropriate for large lists like `stsz`'s sample-size table, but unable to represent DQT's zigzag-to-raster reordering, since the value at raster position N is not the Nth sequential byte in the file. A new `GridData` model carries already-decoded values directly, computed once at parse time:

```kotlin
data class GridData(
    val columns: Int,
    val rows: Int,
    val values: List<String>,
)
```

Added to `BoxNode` as `val grid: GridData? = null`.

### 2. `FieldPanel.kt` renders `node.grid`

After the existing fields list, if `node.grid != null`, render an 8-column-by-N-row grid of `Text` cells (fixed-width, right-aligned, matching the visual convention of a monospace numeric table). Unlike the existing `table`-vs-`fields` exclusivity in `Main.kt` (a node either shows `TableView` or `FieldPanel`, never both — appropriate since `table` entries can be huge and paginated), `grid` is additive within `FieldPanel`: a `QuantizationTable` node shows both its `precision`/`destination_id`/`quality_estimate` fields *and* the 8×8 grid, since the grid is small (64 values) and always fully decoded already, not read on demand from the file.

### 3. DQT decoding (`JpegWalker.kt`)

A single `DQT` marker segment can pack multiple quantization tables back-to-back (the JPEG spec allows this). `decodeSegment` routes `marker == 0xDB` to a new `decodeDqt`, which loops over the payload:

- Read 1 byte: high nibble = `precision` (0 = 8-bit, 1 = 16-bit), low nibble = `destination_id`.
- Read 64 values in **zigzag order** (1 byte each if `precision == 0`, 2 bytes each if `precision == 1`).
- **De-zigzag into raster (natural viewing) order** using the standard JPEG zigzag index table (a public, spec-defined 64-entry permutation — the same one used by every JPEG codec, safe to hardcode like this project's existing public-spec tag-name tables).
- Emit one child `BoxNode` (`type = "QuantizationTable"`) with fields `precision`, `destination_id`, `quality_estimate` (see below), and `grid = GridData(8, 8, deZigzagged.map { it.toString() })`.
- Advance the read position by `1 + 64 * (precision == 0 ? 1 : 2)` bytes and repeat until the segment payload is exhausted; bounds-checked per-table (if the remaining payload can't fit a full table, bail with a warning on that iteration, matching the project's established no-throw convention) rather than reading past the segment.

**Quality estimate.** JPEGsnoop and several other public tools (e.g. libjpeg's own `jpeg_quality_scaling`, used in reverse) estimate a 1–100 JPEG quality value by comparing a file's quantization table against the JPEG standard's baseline table (ITU-T.81 Annex K.1 — public spec material, not proprietary):

Baseline luminance table (raster order):
```
16 11 10 16 24 40 51 61
12 12 14 19 26 58 60 55
14 13 16 24 40 57 69 56
14 17 22 29 51 87 80 62
18 22 37 56 68 109 103 77
24 35 55 64 81 104 113 92
49 64 78 87 103 121 120 101
72 92 95 98 112 100 103 99
```
Baseline chrominance table (raster order):
```
17 18 24 47 99 99 99 99
18 21 26 66 99 99 99 99
24 26 56 99 99 99 99 99
47 66 99 99 99 99 99 99
99 99 99 99 99 99 99 99
99 99 99 99 99 99 99 99
99 99 99 99 99 99 99 99
99 99 99 99 99 99 99 99
```

Compute `ratio = average(table[i].toDouble() / baseline[i])` over the 64 de-zigzagged values, comparing against the luminance baseline when `destination_id == 0` and the chrominance baseline when `destination_id >= 1` (the conventional encoder assignment — DC luminance tables are almost universally assigned destination 0 and chrominance destination 1+, but this is a convention, not a spec guarantee, so it's documented here as a heuristic, same as JPEGsnoop's own approach). `ratio` approximates `scaleFactor / 100` from the forward IJG formula (`scaleFactor = quality < 50 ? 5000/quality : 200 - 2*quality`), so invert it:

```
scaleFactor = ratio * 100.0
quality = if (scaleFactor < 100.0) 100.0 - scaleFactor / 2.0 else 5000.0 / scaleFactor
```
clamped to `[1, 100]` and rounded to the nearest integer, displayed as `"~N%"` (the tilde signals "estimated," matching how this project already flags derived/heuristic values distinctly from directly-read ones). This inversion was verified against the forward IJG formula with a Python script across quality values 1–100: it recovers the exact original quality for the 25–95 range and comes within a few percentage points at the extremes (1–10, 99–100), where integer clamping of the forward-scaled table values inherently loses precision — the same fundamental limitation every DQT-based quality estimator (including JPEGsnoop's) has.

### 4. DHT decoding (`JpegWalker.kt`)

A single `DHT` marker segment can also pack multiple Huffman tables. `decodeSegment` routes `marker == 0xC4` to a new `decodeDht`, which loops over the payload:

- Read 1 byte: high nibble = `class` (0 = DC, 1 = AC), low nibble = `destination_id`.
- Read 16 bytes: `BITS[1..16]`, the count of Huffman codes for each code length 1–16.
- Read `sum(BITS)` bytes: the symbol values (`HUFFVAL`) — not decoded individually (this tool doesn't reconstruct the Huffman tree), just counted for `total_codes`.
- Emit one child `BoxNode` (`type = "HuffmanTable"`) with fields `class` (shown as the string `"DC"` or `"AC"`), `destination_id`, `bit_counts` (the 16 `BITS` values joined with `", "`, matching the existing comma-joined-rational convention already used for GPS coordinate lists), and `total_codes`.
- Advance by `1 + 16 + sum(BITS)` bytes and repeat; same per-table bounds-checking and warn-and-stop convention as DQT.

### 5. SOS header decoding (`JpegWalker.kt`)

`decodeSegment` routes `marker == 0xDA` to a new `decodeSos`, which decodes only the **fixed-length header** (the entropy-coded scan data that follows has no field structure to decode and is already correctly sized by the existing scan-skip loop in `parseJpegSegments`, which is unchanged):

- Read 1 byte: `num_components` (number of components in this scan).
- For each component: read 2 bytes — `component_selector`, and a packed byte where the high nibble is `dc_table` and the low nibble is `ac_table`. Exposed as repeated `component_selector`/`dc_table`/`ac_table` field triples (matching `FtypBoxDecoder`'s repeated-field-name convention, already reused by this project's SOF component decoding).
- Read 3 bytes: `spectral_selection_start`, `spectral_selection_end`, and a packed byte split into `successive_approx_high`/`successive_approx_low`.
- Bounds-checked the same way as `decodeSof`: if the declared segment length can't fit the computed header size, add a warning and return whatever fields were successfully read.

### 6. COM decoding (`JpegWalker.kt`)

`decodeSegment` routes `marker == 0xFE` to a new `decodeCom`, which decodes the entire segment payload as UTF-8 text (matching the existing XMP-field convention already used for `APP1`), exposed as a single field `comment`.

### 7. APP0/JFIF decoding (`JpegWalker.kt`)

`decodeSegment` routes `marker == 0xE0` to a new `decodeApp0`, following the same prefix-sniffing pattern already established by `decodeApp1`:

- If the payload starts with the 5-byte ASCII prefix `"JFIF\0"`, decode: `version` (2 bytes, formatted as `"major.minor"`, e.g. `"1.1"`), `units` (1 byte, mapped `0 → "none"`, `1 → "pixels/inch"`, `2 → "pixels/cm"`, else the raw numeric value), `x_density`/`y_density` (2 bytes each), `x_thumbnail`/`y_thumbnail` (1 byte each — the declared thumbnail pixel dimensions; the thumbnail's raw RGB bytes that may follow are not decoded or rendered, per the Non-Goals).
- Anything else (e.g. `"JFXX\0"` variants, or non-JFIF `APP0` usage) falls back to a plain structural node, matching `decodeApp1`'s existing fallback behavior.

## Testing

Unit tests (following this project's established `byteReaderOf` / exact-byte-fixture convention — every fixture independently verified via a standalone Python script before being transcribed into the implementation plan) covering:
- DQT: a single 8-bit table's zigzag bytes correctly de-zigzagged into the expected raster-order grid; a quality estimate computed against a table scaled to a known quality value lands within a reasonable tolerance of that value; multiple tables packed into one `DQT` segment are each emitted as separate `QuantizationTable` children; truncated/malformed DQT payload produces a warning, not a crash.
- DHT: `bit_counts` and `total_codes` computed correctly from a hand-built table; multiple tables packed into one `DHT` segment; truncated payload warns.
- SOS: header fields (component selectors, DC/AC table indices, spectral selection, successive approximation) decoded correctly from a synthetic 2-component scan header; confirms the existing scan-data-skip sizing behavior (from the already-shipped JPEG feature) is unaffected.
- COM: UTF-8 text decoded correctly into the `comment` field.
- APP0: JFIF fields decoded correctly from a synthetic APP0/JFIF segment; a non-JFIF APP0 payload falls back to a plain structural node.
- `GridData`/`FieldPanel`: a `BoxNode` with both `fields` and a `grid` renders both sections (this may be a manual/visual check rather than a Compose UI test, consistent with how this project has treated UI-layer verification so far — no existing Compose UI test infrastructure exists in this codebase to extend).

Manual verification: open one of the real sample JPEGs already used for this project's testing (e.g. `~/Downloads/20260718_200431.jpg`) and confirm the `DQT` nodes show plausible quality estimates (a phone camera JPEG is typically in the 80–95% range), the grid renders as an 8×8 table, `DHT` shows sensible bit-count distributions, `SOS` shows the expected 3-component (Y/Cb/Cr) header, and `APP0` (if present in the sample) shows JFIF version/density fields.
