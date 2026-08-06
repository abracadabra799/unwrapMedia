# JPEG Overview Detail Section — Design

## Goal

Add a JPEGsnoop-depth "JPEG Detail" section to the Overview tab for JPEG files: encoding type, precision, quality estimate, Huffman table standard/custom classification, Adobe color transform, restart interval, and comment — all visible the moment a JPEG is opened, with no tree navigation required.

This is the first of several planned sub-projects extending Overview depth format-by-format (JPEG → other images → video → audio). Only JPEG is in scope here.

## Non-Goals

- Signature Analysis (re-encoding / editing artifact detection) — out of scope, deferred indefinitely per earlier session agreement.
- Any non-JPEG format — future sub-projects.
- Changes to the Detailed Properties tab (tree-node view) — this section is Overview-only. The underlying per-marker data already exists there; this work surfaces a curated subset earlier.

## Architecture

Two layers change:

1. **`JpegWalker.kt`** gains two new marker decoders (`DRI`, `APP14`) that currently fall through to a bare structural `BoxNode` with no fields. Everything else this section needs (SOF precision/marker type, DQT `quality_estimate`, DHT bit-count/code data, COM `comment`) is already parsed — no changes needed to those decoders.
2. **`MediaSummaryBuilder.kt`** gains `buildJpegDetail(root: BoxNode): SummarySection?`, called from `buildImageSummary` only when the file is JPEG-shaped (`root.children.any { it.type == "SOI" }`), appending a new "JPEG Detail" section after the existing "Image" section. Returns `null` (section omitted) only if the file has no SOF at all (malformed/truncated JPEG) — every other field is independently optional within the section.

No existing section, field, or non-JPEG code path changes.

## New Parsing: DRI

Add `marker == 0xDD -> decodeDri(...)` to `decodeSegment`'s `when`. DRI's payload is fixed: 2 bytes, big-endian `restart_interval` (MCU count between restart markers; 0 is valid — historically used by DRI-then-immediately-set-to-0 to explicitly signal "no restarts").

```kotlin
private fun decodeDri(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize
    if (payloadEnd - payloadStart < 2) {
        return BoxNode(name, offset, 4, totalSize, warnings = listOf("Segment too short to contain a restart interval"))
    }
    val restartInterval = reader.readUInt16(payloadStart)
    return BoxNode(
        type = name, offset = offset, headerSize = 4, size = totalSize,
        fields = listOf(BoxField("restart_interval", restartInterval.toString(), payloadStart, 2)),
        summary = "restart_interval=$restartInterval",
    )
}
```

## New Parsing: APP14 (Adobe)

Add `marker == 0xEE -> decodeApp14(...)`. Adobe's APP14 payload (when present) is a fixed 12-byte structure: `"Adobe"` (5 ASCII bytes) + `DCTEncodeVersion` (2 bytes) + `APP14Flags0` (2 bytes) + `APP14Flags1` (2 bytes) + `ColorTransform` (1 byte). Follow `decodeApp0`'s existing prefix-check-then-fallback pattern exactly:

```kotlin
private val ADOBE_PREFIX = byteArrayOf(0x41, 0x64, 0x6F, 0x62, 0x65) // "Adobe"

private fun decodeApp14(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize
    val bodySize = 7 // version(2) + flags0(2) + flags1(2) + transform(1)
    val hasAdobePrefix = payloadEnd - payloadStart >= ADOBE_PREFIX.size &&
        reader.readBytes(payloadStart, ADOBE_PREFIX.size).contentEquals(ADOBE_PREFIX)
    if (!hasAdobePrefix || payloadEnd - payloadStart < ADOBE_PREFIX.size + bodySize) {
        return BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
    }
    val transformPos = payloadStart + ADOBE_PREFIX.size + 6 // skip version(2)+flags0(2)+flags1(2)
    val colorTransform = reader.readUInt8(transformPos)
    return BoxNode(
        type = name, offset = offset, headerSize = 4, size = totalSize,
        fields = listOf(BoxField("color_transform", colorTransform.toString(), transformPos, 1)),
        summary = "Adobe, color_transform=$colorTransform",
    )
}
```

Malformed/non-Adobe APP14 (wrong prefix or too short) falls back to the existing bare structural node — same as `decodeApp0`'s non-JFIF fallback.

## Overview Section: `buildJpegDetail`

Called only when `root.children.any { it.type == "SOI" }`. Builds each field independently; a missing prerequisite (no SOF, no DQT, etc.) just omits that one field rather than failing the whole section. Returns `null` only if the resulting field list is empty (in practice: no SOF found at all).

### Encoding (from SOF marker number)

The SOF node's `type` string is already `"SOF<N>"` (e.g. `"SOF0"`, `"SOF2"`) via `MARKER_NAMES`. Extract `N` and map to the standard 13 JPEG encoding names (matches `JpegWalker.kt`'s existing `SOF_MARKERS` set exactly — same 13 values):

```kotlin
private val SOF_ENCODING_NAMES = mapOf(
    0 to "Baseline DCT (Huffman)",
    1 to "Extended Sequential DCT (Huffman)",
    2 to "Progressive DCT (Huffman)",
    3 to "Lossless (Sequential, Huffman)",
    5 to "Differential Sequential DCT (Huffman)",
    6 to "Differential Progressive DCT (Huffman)",
    7 to "Differential Lossless (Huffman)",
    9 to "Extended Sequential DCT (Arithmetic)",
    10 to "Progressive DCT (Arithmetic)",
    11 to "Lossless (Sequential, Arithmetic)",
    13 to "Differential Sequential DCT (Arithmetic)",
    14 to "Differential Progressive DCT (Arithmetic)",
    15 to "Differential Lossless (Arithmetic)",
)
```

Field: `sof.type.removePrefix("SOF").toIntOrNull()?.let { SOF_ENCODING_NAMES[it] }` → `SummaryField("Encoding", it)`. If the number isn't in the map (shouldn't happen given `SOF_MARKERS` is fixed), omit the field.

### Precision

Reuse `sof.fields.find { it.name == "precision" }?.value` directly → `SummaryField("Precision", "$it-bit")`.

### Quality Estimate

Gather all `QuantizationTable` children across every top-level `DQT` node: `root.children.filter { it.type == "DQT" }.flatMap { it.children }`. Prefer the one whose `destination_id` field value starts with `"0"` (Luminance, by the same 0=Luminance/1=Chrominance convention `dqtDestinationLabel` already uses elsewhere in `JpegWalker.kt`); if none, fall back to the first available table. Take its `quality_estimate` field value verbatim (already formatted as `"~87%"`) → `SummaryField("Quality Estimate", it)`.

### Huffman Tables (Standard vs Custom/Optimized)

Gather all `HuffmanTable` children the same way: `root.children.filter { it.type == "DHT" }.flatMap { it.children }`. For each table, reconstruct its actual bit-count array and concatenated code bytes from its existing fields — no new byte access needed:

- `bit_counts` field value is a comma-joined string of 16 ints (e.g. `"0, 1, 5, 1, ..."`) → split and parse to `IntArray(16)`.
- `codes_length_01` through `codes_length_16` fields (only present when that length's count is nonzero) each hold a comma-joined uppercase hex string (e.g. `"00, 01, 02"`) for that bit length's symbols, in ascending length order. Concatenating all present `codes_length_NN` fields in order (01→16) reproduces the exact original `HUFFVAL` byte sequence, since `decodeDht` builds them in that same order.

Compare against the 4 standard ITU-T T.81 Annex K tables, keyed by `(class, destination_id)` using the same 0=Luminance/1=Chrominance convention:

```kotlin
private val STANDARD_DC_LUMINANCE_BITS = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
private val STANDARD_DC_LUMINANCE_VALUES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

private val STANDARD_DC_CHROMINANCE_BITS = intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
private val STANDARD_DC_CHROMINANCE_VALUES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

private val STANDARD_AC_LUMINANCE_BITS = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D)
private val STANDARD_AC_LUMINANCE_VALUES = intArrayOf(
    0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
    0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08, 0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0,
    0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
    0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
    0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
    0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
    0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7,
    0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5,
    0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2,
    0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
    0xF9, 0xFA,
)

private val STANDARD_AC_CHROMINANCE_BITS = intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
private val STANDARD_AC_CHROMINANCE_VALUES = intArrayOf(
    0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
    0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xA1, 0xB1, 0xC1, 0x09, 0x23, 0x33, 0x52, 0xF0,
    0x15, 0x62, 0x72, 0xD1, 0x0A, 0x16, 0x24, 0x34, 0xE1, 0x25, 0xF1, 0x17, 0x18, 0x19, 0x1A, 0x26,
    0x27, 0x28, 0x29, 0x2A, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
    0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
    0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
    0x88, 0x89, 0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5,
    0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3,
    0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA,
    0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
    0xF9, 0xFA,
)
```

For each `HuffmanTable` node: read its `class` field ("DC"/"AC") and `destination_id` field (numeric string). Look up the standard `(bits, values)` pair for `(class, destinationId)`:
- `destination_id == "0"` → Luminance table for that class
- `destination_id == "1"` → Chrominance table for that class
- any other `destination_id` → no standard defined; treat as a mismatch directly (a compliant baseline encoder only uses 0/1)

A table "matches" only if its reconstructed bit-count array AND its reconstructed value-byte sequence are both exactly equal to the standard arrays. Build a label per table for mismatches: `"$class$destinationId"` (e.g. `"AC0"`, `"DC1"`).

Overall field value:
- If every table in the file matches → `"Standard"`
- If any table doesn't match → `"Custom/Optimized (differs: <comma-joined mismatch labels>)"`, e.g. `"Custom/Optimized (differs: AC0, AC1)"`
- If there are no Huffman tables at all (arithmetic-coded JPEG, or DHT missing) → omit the field entirely

### Adobe Color Transform

Find `root.children.find { it.type == "APP14" }`. If present and has a `color_transform` field:

```kotlin
private fun adobeColorTransformLabel(transform: Int, numComponents: Int?): String = when (transform) {
    1 -> "YCbCr"
    2 -> "YCCK"
    0 -> if (numComponents == 4) "CMYK" else "RGB"
    else -> "Unknown ($transform)"
}
```

`numComponents` comes from the same SOF node already located for Encoding/Precision (`sof.fields.find { it.name == "num_components" }?.value?.toIntOrNull()`). Field: `SummaryField("Adobe Color Transform", adobeColorTransformLabel(transform, numComponents))`. Omit entirely if there's no APP14 node or it has no `color_transform` field (non-Adobe or malformed APP14).

### Restart Interval

Find `root.children.find { it.type == "DRI" }`. If present with a `restart_interval` field → `SummaryField("Restart Interval", "${it.value} MCUs")`. Omit if no DRI segment.

### Comment

Find `root.children.find { it.type == "COM" }`. If present with a `comment` field → `SummaryField("Comment", it.value)`. Omit if no COM segment. (Multiple COM segments are legal but rare; take the first, matching how other optional single-instance fields in this codebase are handled — e.g. XMP.)

## Data Flow Summary

```
buildImageSummary(root, file)
  ├─ buildImageGeneral(root, file)      [unchanged]
  ├─ buildImageDetail(root)             [unchanged]
  ├─ buildJpegDetail(root)              [NEW — only when SOI present]
  ├─ Camera Info / GPS Location         [unchanged]
  └─ Samsung Metadata                   [unchanged]
```

`buildJpegDetail` reads only from already-parsed `BoxNode`/`BoxField` data (own tree walk of `root`, same as every other `buildXxxDetail` function in this file) — no new `ByteReader`/file I/O, consistent with the rest of `MediaSummaryBuilder.kt`.

## Testing

Two test files, following this codebase's existing patterns exactly:

1. **`JpegWalkerTest.kt`** (existing file) — new cases for `decodeDri` (valid restart interval, too-short payload) and `decodeApp14` (valid Adobe payload extracts `color_transform`, non-Adobe/malformed payload falls back to a bare node) — same hand-crafted byte-array style as this file's existing DQT/DHT/COM tests.
2. **`MediaSummaryBuilderTest.kt`** (existing file) — new cases for `buildJpegDetail` built from synthetic `BoxNode` trees (same style as this file's existing image/video fixture tests):
   - All-standard Huffman tables → `"Standard"`
   - One non-matching table → `"Custom/Optimized (differs: <label>)"`, verifying the exact mismatch label
   - No DHT at all → field omitted
   - Encoding name resolves correctly for a representative SOF number (e.g. SOF2 → `"Progressive DCT (Huffman)"`)
   - Quality Estimate prefers the Luminance (`destination_id=0`) table when both are present
   - Adobe Color Transform: transform=0+4 components → `"CMYK"`, transform=0+3 components → `"RGB"`, transform=1 → `"YCbCr"`
   - Restart Interval and Comment both present vs both absent
   - A minimal JPEG with no DQT/DHT/DRI/APP14/COM still produces a non-null section containing just Encoding+Precision

No UI/Compose tests — the Overview tab already renders arbitrary `SummarySection` lists generically (`CoreMetadataDisplay`), so a new section requires no UI code changes, consistent with how Camera Info / GPS Location / Samsung Metadata were added previously.
