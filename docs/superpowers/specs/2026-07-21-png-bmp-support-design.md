# PNG and BMP Format Support — Design

## Background

Following TIFF and AVIF support, the user asked to continue with the remaining formats from the original broader list (PNG, BMP, GIF, animated GIF). PNG and BMP are the next cheapest: both are well-understood, non-animated, and — like TIFF — can be modeled as a small, self-contained top-level parsing path rather than requiring changes to the generic ISOBMFF box walker. GIF and animated GIF remain deferred, as previously agreed.

- **PNG** is a chunk stream: 8-byte signature, then a sequence of `length`(4) + `type`(4) + `data` + `crc`(4) records. This is structurally analogous to the JPEG marker-segment stream already handled by `JpegWalker.kt` — a flat, self-describing sequence of typed segments — so the same "flat walker, selectively decode well-known segments, generic node for the rest" pattern applies directly.
- **BMP** is not chunked at all: a fixed 14-byte file header immediately followed by a DIB header (commonly the 40-byte `BITMAPINFOHEADER`), then raw pixel data. This is the simplest format in the original list — two fixed-layout structs, no nesting, no container.
- Critically, PNG has an official metadata mechanism the app can reuse for free: the `eXIf` ancillary chunk (registered in the PNG spec since 2017) holds a raw TIFF/EXIF structure with no `"Exif\0\0"` prefix (unlike JPEG's APP1). Since `decodeTiff(reader, tiffStart, itemEnd)` already exists and is reused as-is for standalone TIFF files, pointing it at the `eXIf` chunk's data start gives PNG Camera Info/GPS/Capture Date support with no new decoding logic — `MediaSummaryBuilder`'s existing `IFD0`-walking code already handles it generically.

## Goal

Open a `.png` file and see correct Resolution, Format ("PNG"), and Color Space (from `IHDR`'s color type) in Media Summary, with Camera Info/GPS/Capture Date populated whenever an `eXIf` chunk is present; browse the chunk stream in Structure Analyser with `IHDR`/`pHYs`/`tEXt`/`eXIf` fully decoded and all other chunks visible as generic, field-less nodes. Open a `.bmp` file and see correct Resolution and Format ("BMP") in Media Summary; browse `BITMAPFILEHEADER` and `BITMAPINFOHEADER` (when present) as decoded nodes in Structure Analyser.

## Non-Goals

- No `zTXt`/`iTXt` decoding (compressed/international text chunks) — shown as generic nodes.
- No PNG interlace (Adam7) scan-detail decoding — `interlace_method` is shown as a raw field on `IHDR`, nothing further.
- No animated PNG (APNG) support — out of scope, same category as GIF/AGIF.
- No BMP Color Space support — deferred, same reasoning as TIFF's deferred Color Space.
- No BMP header variants beyond `BITMAPINFOHEADER` (40 bytes) — `BITMAPCOREHEADER` (12 bytes, legacy OS/2) and `BITMAPV4HEADER`/`BITMAPV5HEADER` (108/124 bytes) show as a generic `DIBHEADER` node with just a `header_size` field.
- No pixel-data node for either format — raw pixel bytes aren't modeled as a box, same treatment JPEG gives its entropy-coded scan data today.
- No PNG chunk CRC verification — CRCs are skipped over (used only to compute chunk boundaries), not validated or displayed.

## Design

### 1. PNG detection (`ParseFile.kt`)

PNG's signature is the fixed 8-byte sequence `89 50 4E 47 0D 0A 1A 0A`. Checked after the existing JPEG check, before the existing TIFF check:

```kotlin
private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
)

private fun isPngMagic(reader: ByteReader): Boolean {
    if (reader.length < 8) return false
    return reader.readBytes(0, 8).contentEquals(PNG_SIGNATURE)
}
```

`parseFile` gains a branch: `isPng -> parsePngChunks(reader, 8, reader.length)` (starting past the 8-byte signature).

### 2. PNG chunk walker (new file `PngWalker.kt`)

```kotlin
fun parsePngChunks(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    var pos = start
    while (pos < end) {
        if (pos + 8 > end) {
            result.add(BoxNode("?", pos, 0, end - pos, warnings = listOf("Trailing ${end - pos} byte(s): too short for a chunk header")))
            break
        }
        val length = reader.readUInt32(pos)
        val type = reader.readFourCC(pos + 4)
        val dataStart = pos + 8
        val chunkTotalSize = 8L + length + 4L // length + type + data + crc
        if (pos + chunkTotalSize > end) {
            result.add(BoxNode(type, pos, 8, end - pos, warnings = listOf("Chunk declares length $length but only ${end - pos - 8} byte(s) remain")))
            break
        }
        result.add(decodePngChunk(reader, type, pos, dataStart, length, chunkTotalSize))
        pos += chunkTotalSize
        if (type == "IEND") break
    }
    return result
}

private fun decodePngChunk(reader: ByteReader, type: String, offset: Long, dataStart: Long, length: Long, totalSize: Long): BoxNode =
    when (type) {
        "IHDR" -> decodeIhdr(reader, offset, dataStart, totalSize)
        "pHYs" -> decodePhys(reader, offset, dataStart, totalSize)
        "tEXt" -> decodeText(reader, offset, dataStart, length, totalSize)
        "eXIf" -> decodeExifChunk(reader, offset, dataStart, dataStart + length, totalSize)
        else -> BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize)
    }
```

(`readUInt32`/`readFourCC` already exist on `ByteReader` — used throughout the existing ISOBMFF box walker.)

**`IHDR`** (13-byte body: width(4) + height(4) + bit_depth(1) + color_type(1) + compression_method(1) + filter_method(1) + interlace_method(1)):

```kotlin
private val COLOR_TYPE_NAMES = mapOf(
    0 to "Grayscale",
    2 to "Truecolor",
    3 to "Indexed",
    4 to "Grayscale+Alpha",
    6 to "Truecolor+Alpha",
)

private fun decodeIhdr(reader: ByteReader, offset: Long, dataStart: Long, totalSize: Long): BoxNode {
    val width = reader.readUInt32(dataStart)
    val height = reader.readUInt32(dataStart + 4)
    val bitDepth = reader.readUInt8(dataStart + 8)
    val colorType = reader.readUInt8(dataStart + 9)
    val compressionMethod = reader.readUInt8(dataStart + 10)
    val filterMethod = reader.readUInt8(dataStart + 11)
    val interlaceMethod = reader.readUInt8(dataStart + 12)
    val colorTypeName = COLOR_TYPE_NAMES[colorType] ?: "Unknown"
    return BoxNode(
        type = "IHDR", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(
            BoxField("width", width.toString(), dataStart, 4),
            BoxField("height", height.toString(), dataStart + 4, 4),
            BoxField("bit_depth", bitDepth.toString(), dataStart + 8, 1),
            BoxField("color_type", "$colorType ($colorTypeName)", dataStart + 9, 1),
            BoxField("compression_method", compressionMethod.toString(), dataStart + 10, 1),
            BoxField("filter_method", filterMethod.toString(), dataStart + 11, 1),
            BoxField("interlace_method", interlaceMethod.toString(), dataStart + 12, 1),
        ),
        summary = "${width}x${height}, $colorTypeName, ${bitDepth}-bit",
    )
}
```

**`pHYs`** (9-byte body: pixels_per_unit_x(4) + pixels_per_unit_y(4) + unit_specifier(1)):

```kotlin
private fun decodePhys(reader: ByteReader, offset: Long, dataStart: Long, totalSize: Long): BoxNode {
    val ppuX = reader.readUInt32(dataStart)
    val ppuY = reader.readUInt32(dataStart + 4)
    val unitSpecifier = reader.readUInt8(dataStart + 8)
    val unitLabel = if (unitSpecifier == 1) "meter" else "unknown"
    return BoxNode(
        type = "pHYs", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(
            BoxField("pixels_per_unit_x", ppuX.toString(), dataStart, 4),
            BoxField("pixels_per_unit_y", ppuY.toString(), dataStart + 4, 4),
            BoxField("unit_specifier", "$unitSpecifier ($unitLabel)", dataStart + 8, 1),
        ),
        summary = "${ppuX}x${ppuY} px/$unitLabel",
    )
}
```

**`tEXt`** (Latin-1 `keyword\0text`, keyword ≤ 79 bytes):

```kotlin
private fun decodeText(reader: ByteReader, offset: Long, dataStart: Long, length: Long, totalSize: Long): BoxNode {
    val bytes = reader.readBytes(dataStart, length.toInt())
    val nullIndex = bytes.indexOf(0)
    if (nullIndex < 0) {
        return BoxNode(type = "tEXt", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("Missing keyword/text separator"))
    }
    val keyword = String(bytes, 0, nullIndex, Charsets.ISO_8859_1)
    val text = String(bytes, nullIndex + 1, bytes.size - nullIndex - 1, Charsets.ISO_8859_1)
    return BoxNode(
        type = "tEXt", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(
            BoxField("keyword", keyword, dataStart, (nullIndex).toLong()),
            BoxField("text", text, dataStart + nullIndex + 1, (bytes.size - nullIndex - 1).toLong()),
        ),
        summary = "$keyword: $text",
    )
}
```

**`eXIf`** (raw TIFF bytes, no prefix — reuses the existing decoder exactly as JPEG's APP1 does):

```kotlin
private fun decodeExifChunk(reader: ByteReader, offset: Long, dataStart: Long, dataEnd: Long, totalSize: Long): BoxNode {
    val children = decodeTiff(reader, dataStart, dataEnd)
    return BoxNode(type = "eXIf", offset = offset, headerSize = 8, size = totalSize, children = children, summary = "Exif metadata")
}
```

### 3. BMP detection (`ParseFile.kt`)

BMP's signature is the 2 ASCII bytes `"BM"` at file offset 0:

```kotlin
private fun isBmpMagic(reader: ByteReader): Boolean {
    if (reader.length < 2) return false
    val bytes = reader.readBytes(0, 2)
    return bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()
}
```

`parseFile` gains a branch: `isBmp -> parseBmpHeaders(reader, 0, reader.length)`.

### 4. BMP header walker (new file `BmpWalker.kt`)

BMP's multi-byte fields are **little-endian** — unlike PNG/JPEG/ISOBMFF, which are big-endian and are what `ByteReader.readUInt16`/`readUInt32` assume. Rather than touch the shared big-endian reader, `BmpWalker.kt` reads its own bytes and assembles them little-endian locally (self-contained, no risk to existing big-endian call sites):

```kotlin
private fun readUInt16LE(reader: ByteReader, offset: Long): Int {
    val b = reader.readBytes(offset, 2)
    return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
}

private fun readUInt32LE(reader: ByteReader, offset: Long): Long {
    val b = reader.readBytes(offset, 4)
    return (b[0].toLong() and 0xFF) or
        ((b[1].toLong() and 0xFF) shl 8) or
        ((b[2].toLong() and 0xFF) shl 16) or
        ((b[3].toLong() and 0xFF) shl 24)
}

private fun readInt32LE(reader: ByteReader, offset: Long): Int = readUInt32LE(reader, offset).toInt()

fun parseBmpHeaders(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    if (end - start < 14) {
        return listOf(BoxNode("?", start, 0, end - start, warnings = listOf("File too short for a BITMAPFILEHEADER")))
    }
    val result = mutableListOf<BoxNode>()
    result.add(decodeBitmapFileHeader(reader, start))

    val dibStart = start + 14
    if (end - dibStart < 4) return result
    val headerSize = readUInt32LE(reader, dibStart)
    result.add(
        if (headerSize == 40L) {
            decodeBitmapInfoHeader(reader, dibStart, end)
        } else {
            BoxNode(
                type = "DIBHEADER", offset = dibStart, headerSize = 0, size = minOf(headerSize, end - dibStart),
                fields = listOf(BoxField("header_size", headerSize.toString(), dibStart, 4)),
            )
        },
    )
    return result
}

private fun decodeBitmapFileHeader(reader: ByteReader, offset: Long): BoxNode {
    val fileSize = readUInt32LE(reader, offset + 2)
    val pixelDataOffset = readUInt32LE(reader, offset + 10)
    return BoxNode(
        type = "BITMAPFILEHEADER", offset = offset, headerSize = 0, size = 14,
        fields = listOf(
            BoxField("signature", "BM", offset, 2),
            BoxField("file_size", fileSize.toString(), offset + 2, 4),
            BoxField("pixel_data_offset", pixelDataOffset.toString(), offset + 10, 4),
        ),
    )
}

private fun decodeBitmapInfoHeader(reader: ByteReader, offset: Long, end: Long): BoxNode {
    if (end - offset < 40) {
        return BoxNode(type = "BITMAPINFOHEADER", offset = offset, headerSize = 0, size = end - offset, warnings = listOf("Truncated BITMAPINFOHEADER"))
    }
    val width = readInt32LE(reader, offset + 4)
    val height = readInt32LE(reader, offset + 8)
    val bitCount = readUInt16LE(reader, offset + 14)
    val compression = readUInt32LE(reader, offset + 16)
    return BoxNode(
        type = "BITMAPINFOHEADER", offset = offset, headerSize = 0, size = 40,
        fields = listOf(
            BoxField("width", width.toString(), offset + 4, 4),
            BoxField("height", height.toString(), offset + 8, 4),
            BoxField("bit_count", bitCount.toString(), offset + 14, 2),
            BoxField("compression", compression.toString(), offset + 16, 4),
        ),
        summary = "${width}x${height}, ${bitCount}-bit",
    )
}
```

`height` is read as a signed 32-bit value (`readInt32LE`) because BMP uses a negative height to indicate a top-down bitmap; a naive unsigned read would misinterpret it as a huge positive number. `MediaSummaryBuilder` takes `abs(height)` for Resolution display. No changes to `ByteReader` itself — the existing big-endian `readUInt16`/`readUInt32` remain untouched and are not used by BMP.

### 5. Media Summary integration (`MediaSummaryBuilder.kt`)

In `buildImageBasicInfo`, alongside the existing `isJpeg`/`isTiff` checks:

```kotlin
val isPng = root.children.any { it.type == "IHDR" }
val isBmp = root.children.any { it.type == "BITMAPFILEHEADER" }
```

Resolution: when `isPng`, read `width`/`height` off the `IHDR` node directly (mirrors the existing TIFF fallback branch). When `isBmp`, read `width`/`height` off `BITMAPINFOHEADER` (when present — a non-40-byte DIB header yields no Resolution, same as any other field gap in this codebase), taking `abs(height)`.

Format: add `"PNG"` and `"BMP"` branches parallel to the existing `"JPEG"`/`"TIFF"` branches.

Color Space: when `isPng`, read `IHDR`'s `color_type` field and map it through the same `COLOR_TYPE_NAMES` label used in the decoder (e.g. `"Truecolor+Alpha"`). No Color Space field for BMP.

Camera Info / GPS / Capture Date: no code changes — these already call `findFirst(root) { it.type == "IFD0" }` and walk `Exif`/`GPS` children generically, so a PNG's `eXIf` chunk (which produces exactly the same `IFD0` tree shape as TIFF/JPEG) populates them automatically. BMP never has an `IFD0` node, so these sections simply don't appear, consistent with how they already behave for any file lacking EXIF today.

## Testing

- `PngWalkerTest` (new): a synthetic PNG (signature + `IHDR` + `pHYs` + `tEXt` + `eXIf` wrapping a small TIFF/EXIF fixture + `IEND`) asserts each chunk decodes with correct fields/summary, and that an unrecognized chunk type shows as a generic node.
- `BmpWalkerTest` (new): a synthetic BMP (`BITMAPFILEHEADER` + `BITMAPINFOHEADER`) asserts both headers decode correctly, including a negative-height (top-down) case; a second case with a non-40-byte DIB header size asserts the generic `DIBHEADER` fallback.
- `MediaSummaryBuilderTest` additions: a synthetic PNG-shaped tree asserts Resolution/Format/Color Space populate, and that an embedded `eXIf` chunk's `Make`/`Model`/`GPS` tags populate Camera Info/GPS Location exactly like the existing TIFF test does; a synthetic BMP-shaped tree asserts Resolution/Format populate and no Color Space/Camera Info/GPS sections appear.
- `ParseFileIntegrationTest` additions: a small real-byte PNG fixture and a small real-byte BMP fixture each assert `parseFile` routes through the new walker rather than the generic box walker or another format's path.
- Manual verification: open a real `.png` file (with and without embedded EXIF) and a real `.bmp` file, confirm Media Summary and Structure Analyser render as designed.
