# Image Formats Overview Detail Sections — Design

## Goal

Extend the same JPEGsnoop-depth Overview detail treatment already shipped for JPEG (`docs/superpowers/specs/2026-08-06-jpeg-overview-detail-design.md`) to the remaining image formats: PNG, BMP, GIF, WebP, and TIFF/camera-RAW (CR2/NEF/ARW/DNG), plus HEIC/AVIF-specific item properties. Each format gets a new `SummarySection` in the Overview tab surfacing information that's either already parsed but hidden until a tree node is selected, or newly parsed because it's commonly useful and currently missing entirely.

This is the second of the planned Overview-depth sub-projects (JPEG → **this** → video → audio).

## Non-Goals

- Video and audio formats — separate future sub-projects.
- Signature Analysis (re-encoding/editing detection) — deferred indefinitely per earlier agreement.
- Any change to the Detailed Properties (tree) tab or UI/Compose files — every new section is a plain `SummarySection`, and the Overview tab already renders arbitrary section lists generically (confirmed by the JPEG sub-project requiring zero UI changes).
- Full HEIC/AVIF alpha-item graph resolution — `auxC`'s presence is reported as a simple yes/no (see HEIC/AVIF Detail below), not a full item-reference walk.

## Architecture

Same pattern as JPEG: one `build<Format>Detail(root): SummarySection?` function per format in `MediaSummaryBuilder.kt`, each called unconditionally from `buildImageSummary` (mirroring `buildJpegDetail`'s call site), each internally detecting whether its format applies and returning `null` otherwise. Every field within a section is independently optional — a missing prerequisite omits only that field.

```kotlin
private fun buildImageSummary(root: BoxNode, file: File): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    sections.add(buildImageGeneral(root, file))
    buildImageDetail(root)?.let { sections.add(it) }
    buildJpegDetail(root)?.let { sections.add(it) }
    buildPngDetail(root)?.let { sections.add(it) }
    buildBmpDetail(root)?.let { sections.add(it) }
    buildGifDetail(root)?.let { sections.add(it) }
    buildWebpDetail(root)?.let { sections.add(it) }
    buildTiffDetail(root)?.let { sections.add(it) }
    buildHeicDetail(root)?.let { sections.add(it) }
    ...
}
```

Detection per format reuses the exact same signals `buildImageGeneral`/`buildImageDetail` already use to identify each format (`root.children.any { it.type == "IHDR" }` for PNG, etc.) — no new detection logic invented.

Two formats need new parsing before their Overview section has anything to show:
1. **GIF Comment Extension** — currently skipped without extracting its text.
2. **HEIC/AVIF `irot`/`imir`/`pixi`/`auxC` boxes** — currently unregistered in `BoxRegistry`, so they parse as empty leaf nodes.

WebP's VP8X flags byte is already captured as a raw field (`BoxField("flags", "0x..", ...)`) — decoding it into labels is Overview-layer work only, no parser change needed.

---

## PNG Detail

Detected the same way `buildImageGeneral` already does: `root.children.any { it.type == "IHDR" }`.

Fields, each independently optional:
- **Bit Depth**: `IHDR`'s existing `bit_depth` field, as `"${it.value}-bit"`.
- **Compression Method**: `IHDR`'s existing `compression_method` field. PNG only defines value `0` ("Deflate/Inflate"); anything else is unrecognized — show `"Deflate/Inflate"` for `0`, otherwise the raw number.
- **Interlace**: `IHDR`'s existing `interlace_method` field, labeled `0 → "None"`, `1 → "Adam7"`, else raw number.
- **Pixel Density**: from the `pHYs` chunk (if present), fields `pixels_per_unit_x`/`pixels_per_unit_y`/`unit_specifier`. When `unit_specifier == "meter"`, convert to DPI: `dpi = round(pixels_per_unit * 0.0254)`. Format as `"<x-dpi> x <y-dpi> DPI"` when both axes convert to the same value show just `"<dpi> DPI"`; when `unit_specifier` is `"unknown"` (no defined physical unit), show the raw `"<x> x <y> px/unit"` instead of a bogus DPI conversion.
- **Text Metadata**: one `SummaryField` per `tEXt` chunk found among `root.children`, label = the chunk's `keyword` field value, value = its `text` field value. (PNG's `Filter Method` field is intentionally excluded — every real-world PNG uses method 0, adaptive filtering per scanline, so it carries no useful signal and would just be clutter.)

## BMP Detail

Detected the same way `buildImageDetail` already does: `root.children.any { it.type == "BITMAPFILEHEADER" }`.

Fields:
- **Bit Count**: `BITMAPINFOHEADER`'s existing `bit_count` field, as `"${it.value}-bit"`.
- **Compression**: `BITMAPINFOHEADER`'s existing `compression` field, labeled via:
  ```kotlin
  private val BMP_COMPRESSION_NAMES = mapOf(
      0 to "None (BI_RGB)",
      1 to "RLE 8-bit (BI_RLE8)",
      2 to "RLE 4-bit (BI_RLE4)",
      3 to "Bit Fields (BI_BITFIELDS)",
      4 to "JPEG (BI_JPEG)",
      5 to "PNG (BI_PNG)",
  )
  ```
  falling back to the raw number for anything else.

(BMP's `BITMAPINFOHEADER` decoder only fires when `header_size == 40`; a non-standard/extended DIB header — `DIBHEADER` node, no `bit_count`/`compression` fields — means this section is simply omitted, matching the existing `buildImageDetail` behavior for that same case.)

## GIF Detail

Detected the same way `buildImageDetail` already does: `root.children.any { it.type == "LogicalScreenDescriptor" }`.

Fields:
- **Color Resolution**: `LogicalScreenDescriptor`'s existing `color_resolution` field, as `"${it.toInt() + 1}-bit"` (the field stores the value minus one per GIF89a's packed-byte convention).
- **Global Color Table**: only if `global_color_table_flag == "1"` — show `"Yes (${1 shl (global_color_table_size.toInt() + 1)} colors)"` using the existing `global_color_table_size` field (also stored minus one; `1 shl (size+1)` mirrors `parseGifBlocks`' own existing `1L shl (globalColorTableSize + 1)` table-size formula exactly).
- **Disposal Method**: from the *first* `GraphicControlExtension` found among `root.children`, its existing `disposal_method` field, labeled:
  ```kotlin
  private val GIF_DISPOSAL_METHOD_NAMES = mapOf(
      0 to "Unspecified",
      1 to "Do Not Dispose",
      2 to "Restore to Background",
      3 to "Restore to Previous",
  )
  ```
- **Frame Delay**: from the same first `GraphicControlExtension`, its existing `delay_time` field (in hundredths of a second per spec) converted to `"${it.toInt() * 10} ms"`.
- **Comment**: from the first `CommentExtension` found (requires the new parsing below) — its `comment` field value.

### New parsing: GIF Comment Extension text

`decodeGenericSubBlockExtension` (used for `CommentExtension`) currently discards the sub-block bytes via `skipSubBlocks`. Add a dedicated decoder that mirrors `decodeGraphicControlExtension`'s use of `readSubBlocks` (which does materialize the bytes) instead:

```kotlin
private fun decodeCommentExtension(reader: ByteReader, offset: Long, end: Long): Pair<BoxNode, Long>? {
    val (blocks, nextPos) = readSubBlocks(reader, offset + 2, end) ?: return null
    val text = blocks.joinToString("") { String(it, Charsets.ISO_8859_1) }
    val fields = if (text.isNotEmpty()) listOf(BoxField("comment", text, offset + 2, nextPos - (offset + 2))) else emptyList()
    return BoxNode(type = "CommentExtension", offset = offset, headerSize = 2, size = nextPos - offset, fields = fields) to nextPos
}
```

Wire it into `decodeExtension`'s `when`, replacing the current `COMMENT_LABEL -> decodeGenericSubBlockExtension(reader, "CommentExtension", offset, end)` branch with `COMMENT_LABEL -> decodeCommentExtension(reader, offset, end)`. (`ISO_8859_1`, not UTF-8, to match `PngWalker.kt`'s existing `tEXt` decoding convention for text-in-binary-container fields — GIF comment text is technically 7-bit ASCII per spec, and ISO_8859_1 is a strict superset that never throws on stray high-bit bytes some encoders emit.)

## WebP Detail

Detected the same way `buildImageDetail` already does: `root.children.any { it.type == "RIFF" }`.

Fields:
- **Codec**: based on which of `VP8X`/`VP8 `/`VP8L` is present among `root.children` — `"Extended (VP8X)"`, `"Lossy (VP8)"`, or `"Lossless (VP8L)"` respectively. If more than one is present (a `VP8X` container always wraps an inner `VP8 `/`VP8L` bitstream chunk, both landing as direct root children in this app's flat chunk walk), `VP8X` takes priority since it's the outer/defining chunk.
- When `VP8X` is present, three more fields decoded from its existing `flags` field (format `"0x<hex>"` — parse the hex back to an `Int`, or simpler: re-read the same byte position; simplest is to add the raw flags byte as an additional `Int`-typed value alongside the existing hex-string field — see implementation note below):
  - **Has Alpha**: `(flags and 0x10) != 0` → `"Yes"`/`"No"`.
  - **Has Animation**: `(flags and 0x02) != 0` → `"Yes"`/`"No"`.
  - **Has ICC Profile**: `(flags and 0x20) != 0` → `"Yes"`/`"No"`.

  (Bit positions per the WebP container spec / libwebp's `mux_types.h`: `ICCP_FLAG = 0x20`, `ALPHA_FLAG = 0x10`, `EXIF_FLAG = 0x08`, `XMP_FLAG = 0x04`, `ANIM_FLAG = 0x02`. Exif/XMP flags are skipped here — the app already surfaces actual EXIF/XMP presence via decoded `EXIF` chunk children elsewhere, so a redundant flag-only field would be less informative, not more.)

  Implementation note: `WebpWalker.kt`'s existing `VP8X` field is `BoxField("flags", "0x${flags.toString(16)}", ...)` — a formatted string, not the raw int. Rather than re-parsing that hex string back to an int in `MediaSummaryBuilder.kt` (fragile/roundabout), parse it in `buildWebpDetail` via `.removePrefix("0x").toIntOrNull(16)`.

## TIFF/RAW Detail

Detected the same way as every other `build<Format>Detail` function — self-contained, single `root: BoxNode` parameter, doing its own `val ifd0 = findFirst(root) { it.type == "IFD0" } ?: return null` lookup (the same lookup `buildImageSummary` already performs separately for Camera Info/GPS Location — a second, independent tree walk, consistent with this file's existing style of each `build*` function locating its own inputs rather than threading extra parameters through call sites).

All fields already have human-readable values baked in by the existing `exif-detail-enrichment` work (`TAG_VALUE_LABELS`/`interpretedDisplay` in `ExifDecoder.kt`) — this section is pure reuse, no new parsing:
- **Orientation**: `ifd0.fields.find { it.name == "Orientation" }` (already a label like `"Horizontal (normal)"`).
- **Compression**: `ifd0.fields.find { it.name == "Compression" }` (already a label like `"Uncompressed"`/`"JPEG"`).
- **Photometric Interpretation**: `ifd0.fields.find { it.name == "PhotometricInterpretation" }` (already a label like `"RGB"`/`"YCbCr"`).
- **Bits Per Sample**: `ifd0.fields.find { it.name == "BitsPerSample" }`, raw value as-is.
- **Samples Per Pixel**: `ifd0.fields.find { it.name == "SamplesPerPixel" }`, raw value as-is.
- **Resolution**: only if both `XResolution` and `YResolution` fields are present — `"<x> x <y> <unit>"` where `<unit>` comes from the `ResolutionUnit` field (already labeled `"None"`/`"inches"`/`"cm"`) if present, else omitted from the string. `XResolution`/`YResolution` values are shown as their existing raw rational strings (e.g. `"300/1"`) — no new rational-to-decimal conversion, consistent with how this app already leaves GPS coordinate rationals unconverted elsewhere.

This section applies identically to plain TIFF and every camera-RAW format (CR2/NEF/ARW/DNG all route through the same generic TIFF/IFD0 walker per `IMAGE_EXTENSIONS`' existing comment) — no per-RAW-format special-casing needed.

## HEIC/AVIF Detail

Detected via the same `meta`-box presence this app already uses for HEIC/AVIF (`findPrimaryItemProperty` requires a `meta` child, and this section only produces fields when at least one of the four properties below is found — if none are found, the whole section is omitted, matching every other optional-field section's `null`-if-empty convention here).

Fields, each found via `findPrimaryItemProperty(root, "<type>")` (the same primary-item-aware lookup already used for `colr`/`ispe` in `buildImageDetail`):
- **Rotation**: from `irot`'s `angle` field (new field, see below) — `0 → "0°"`, `1 → "90°"`, `2 → "180°"`, `3 → "270°"`.
- **Mirror**: from `imir`'s `axis` field (new field, see below) — `0 → "Horizontal Flip (좌우반전)"`, `1 → "Vertical Flip (상하반전)"`. (HEIF's `axis=0` is "mirrored about a vertical axis," which in image-editing terms is a left-right/horizontal flip; `axis=1` is "mirrored about a horizontal axis," a top-bottom/vertical flip — translated to editing terminology per user decision, not left as raw spec wording.)
- **Bit Depth**: from `pixi`'s `bits_per_channel` field (new field, see below) — e.g. `"8, 8, 8"` or `"10, 10, 10"`.
- **Has Alpha Channel**: `"Yes"` if any `auxC` box exists anywhere in the tree (via `findFirst(root) { it.type == "auxC" }`) whose `aux_type` field contains `"alpha"` (case-insensitive substring match against the standard URN `urn:mpeg:mpegB:cicp:systems:auxiliary:alpha`); omitted entirely (not `"No"`) if no such box exists — this is a presence check, not a full item-reference resolution, so absence isn't authoritative enough to assert `"No"`.

### New parsing: `irot`, `imir`, `pixi`, `auxC` box decoders

Four new `BoxDecoder` objects, following the exact structure of the existing `IspeBoxDecoder`/`ColrBoxDecoder` (same package, same `BoxDecoder` interface, same bounds-check-then-`warnings`-else-`fields` shape), registered in `Decoders.kt` alongside the other `BoxRegistry.register(...)` calls.

**`IrotBoxDecoder`** (1-byte payload, low 2 bits = angle):
```kotlin
object IrotBoxDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        if (offset + size - payloadStart < 1) {
            w.add("Box too short for irot angle byte")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val angle = reader.readUInt8(payloadStart) and 0x03
        return BoxNode(type, offset, headerSize, size, fields = listOf(BoxField("angle", angle.toString(), payloadStart, 1)), warnings = w, summary = "${angle * 90}°")
    }
}
```

**`ImirBoxDecoder`** (1-byte payload, low bit = axis):
```kotlin
object ImirBoxDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        if (offset + size - payloadStart < 1) {
            w.add("Box too short for imir axis byte")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val axis = reader.readUInt8(payloadStart) and 0x01
        return BoxNode(type, offset, headerSize, size, fields = listOf(BoxField("axis", axis.toString(), payloadStart, 1)), warnings = w)
    }
}
```

**`PixiBoxDecoder`** (a FullBox — 4-byte version/flags prefix, same as the existing `IspeBoxDecoder`'s own `payloadStart + 4` convention — then `num_channels` byte, then that many 1-byte bits-per-channel values):
```kotlin
object PixiBoxDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize + 4 // skip version(1)+flags(3)
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 1) {
            w.add("Box too short for pixi num_channels")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val numChannels = reader.readUInt8(payloadStart)
        if (payloadEnd - (payloadStart + 1) < numChannels) {
            w.add("pixi declares $numChannels channel(s) but not enough bytes remain")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val bits = (0 until numChannels).map { reader.readUInt8(payloadStart + 1 + it) }
        return BoxNode(
            type, offset, headerSize, size,
            fields = listOf(BoxField("bits_per_channel", bits.joinToString(", "), payloadStart + 1, numChannels.toLong())),
            warnings = w, summary = bits.joinToString(", ") { "${it}-bit" },
        )
    }
}
```

**`AuxCBoxDecoder`** (also a FullBox — same 4-byte version/flags skip — then a null-terminated `aux_type` string):
```kotlin
object AuxCBoxDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize + 4 // skip version(1)+flags(3)
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 1) {
            w.add("Box too short for auxC aux_type")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val bytes = reader.readBytes(payloadStart, (payloadEnd - payloadStart).toInt())
        val nullIndex = bytes.indexOf(0)
        val auxType = String(bytes, 0, if (nullIndex >= 0) nullIndex else bytes.size, Charsets.US_ASCII)
        return BoxNode(type, offset, headerSize, size, fields = listOf(BoxField("aux_type", auxType, payloadStart, (if (nullIndex >= 0) nullIndex else bytes.size).toLong())), warnings = w, summary = auxType)
    }
}
```

All four registered in `Decoders.kt`:
```kotlin
BoxRegistry.register("irot", IrotBoxDecoder)
BoxRegistry.register("imir", ImirBoxDecoder)
BoxRegistry.register("pixi", PixiBoxDecoder)
BoxRegistry.register("auxC", AuxCBoxDecoder)
```

`irot`/`imir`/`pixi` land inside `ipco` (the same item-property container `ispe`/`colr` already live in) and become reachable via `findPrimaryItemProperty` with zero changes to that helper. `auxC` is a top-level item-info box (attached to its own auxiliary item, not necessarily the primary item), so it's found via a plain recursive `findFirst`, not `findPrimaryItemProperty`.

---

## Testing

Same conventions as the JPEG sub-project: hand-crafted byte-array tests in the relevant `*WalkerTest.kt` file for each new/changed parser (`GifWalkerTest` for the Comment Extension fix; new `IrotBoxDecoderTest`/`ImirBoxDecoderTest`/`PixiBoxDecoderTest`/`AuxCBoxDecoderTest`, or folded into an existing decoder test file if one already covers sibling box decoders — check at plan-writing time), and synthetic-`BoxNode`-tree tests in `MediaSummaryBuilderTest.kt` for each new `build<Format>Detail` function, covering: the happy path with all fields present, each field's independent-omission case, and a non-matching-format regression check (same pattern as JPEG Detail's "a non-JPEG image (PNG) has no JPEG Detail section" test) for each new section.

Given the JPEG sub-project's Task 3 uncovered a real bug only visible against a real file (two concatenated JPEG streams), each format's manual-verification task should include testing against at least one real-world file of that format where practical (PNG/GIF/BMP/WebP are easy to source or generate via `ffmpeg`; HEIC/AVIF real samples may already exist among the user's test files from earlier sessions).
