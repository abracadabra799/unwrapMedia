# TIFF and AVIF Format Support — Design

## Background

The app currently recognizes exactly one special-cased format at the top level (JPEG, via its `0xFFD8` magic bytes in `ParseFile.kt`); every other file falls through to the generic ISOBMFF box walker (`parseBoxes`). This happens to already work for HEIC. The user asked for broader format support (TIFF, AVIF, PNG, BMP, GIF/animated GIF) — investigation showed these six formats span very different implementation costs, so this is being split into separate spec→plan cycles. This first one covers the two cheapest: **TIFF** and **AVIF**, confirmed via code inspection to require only small, targeted additions rather than new parser modules.

- **AVIF** is built on the same HEIF-family container structure (`ftyp`/`meta`/`iprp`/`ipco`/`iloc`/`pitm`) already used for HEIC, and already flows through the existing generic box walker today. `MediaSummaryBuilder`'s Resolution/Color Space lookups (`findPrimaryItemProperty` over `ispe`/`colr`) are format-agnostic — they work off box structure, not codec identity — so they already work for AVIF with no changes. The only real gap: `av01` (AV1's visual sample entry box, structurally identical to `avc1`/`hvc1`) isn't registered in `Decoders.kt`, so it currently falls back to a generic, field-less display in Structure Analyser instead of showing its `width`/`height`.
- **TIFF** already has a full IFD-walking decoder, `decodeTiff` (`ExifDecoder.kt`), reused today to parse the miniature TIFF structure embedded in a JPEG/HEIC file's EXIF data — its tag table already includes `ImageWidth`/`ImageLength` (tags `0x0100`/`0x0101`). Standalone TIFF files are structured as *only* that IFD chain, with no other wrapper, so the missing piece is purely: (1) detecting a standalone TIFF file at the top level and routing straight into the existing decoder, and (2) making `buildImageBasicInfo` recognize a TIFF-shaped tree for the Resolution/Format fields (Camera Info/GPS/Capture Date already walk `IFD0`/`Exif`/`GPS` generically regardless of container, so they need no changes).

## Goal

Open a `.avif` file and see correct Resolution/Color Space/File Size in Media Summary, and a properly-decoded `av01` box (with width/height) in Structure Analyser. Open a standalone `.tiff`/`.tif` file and see it parsed as an image with Resolution, Format ("TIFF"), Camera Info, GPS, and Capture Date populated from its IFD0/Exif/GPS tags, exactly like a JPEG's embedded EXIF data already produces.

## Non-Goals

- No dedicated `av1C` (AV1 Codec Configuration Box) decoder — it will show as a generic, field-less node in Structure Analyser (harmless; the box itself is still visible and browsable, just not broken down into codec-specific fields). Deferred as a future follow-up if wanted.
- No Color Space support for TIFF — would require decoding tags not currently in the TIFF tag table (e.g. `PhotometricInterpretation`). Deferred.
- No support for multi-page TIFF (`NextIFDOffset` chaining beyond IFD0) — `decodeTiff` already only decodes the first IFD, matching its existing EXIF-embedded use; this plan doesn't change that.
- No PNG, BMP, or GIF/animated-GIF support — separate, larger efforts, to be scoped in their own cycles later.
- No motion-photo/XMP-related work for either format — out of scope here.

## Design

### 1. AVIF (`Decoders.kt`)

One line, registered alongside the existing `avc1`/`hvc1` entries:

```kotlin
BoxRegistry.register("av01", VisualSampleEntryDecoder)
```

`VisualSampleEntryDecoder` is already fully generic — it reads the standard ISOBMFF `VisualSampleEntry` fixed header (`data_reference_index`/`width`/`height`) and recursively parses whatever nested boxes follow (e.g. `av1C`, `colr`), regardless of codec identity. No other file changes: `detectCategory`, `findPrimaryItemProperty`, and `buildImageBasicInfo`'s `ispe`/`colr` lookups already operate purely on box *structure*, so they already produce correct Resolution/Color Space/File Size for an AVIF file exactly as they do for HEIC.

### 2. TIFF detection (`ParseFile.kt`)

TIFF files start with a 4-byte magic identifying byte order: `II*\0` (little-endian, bytes `49 49 2A 00`) or `MM\0*` (big-endian, bytes `4D 4D 00 2A`). `parseFile` gains a second special case, checked after the existing JPEG check:

```kotlin
fun parseFile(path: File): BoxNode {
    registerAllDecoders()
    ByteReader.open(path).use { reader ->
        val isJpeg = reader.length >= 2 && reader.readUInt8(0) == 0xFF && reader.readUInt8(1) == 0xD8
        val isTiff = !isJpeg && isTiffMagic(reader)
        val children = when {
            isJpeg -> parseJpegSegments(reader, 0, reader.length)
            isTiff -> decodeTiff(reader, 0, reader.length)
            else -> parseBoxes(reader, 0, reader.length)
        }
        return BoxNode(type = "root", offset = 0, headerSize = 0, size = reader.length, children = children)
    }
}
```

`decodeTiff(reader, 0, reader.length)` is called with `tiffStart = 0` because, unlike the JPEG-embedded case (where the TIFF header starts partway into an APP1 segment), a standalone TIFF file's byte-order marker sits at the very start of the file — so all of `decodeTiff`'s internal offset math (which is always relative to `tiffStart`) resolves correctly against absolute file offsets with no adjustment needed.

### 3. TIFF-aware Media Summary (`MediaSummaryBuilder.kt`)

A standalone TIFF file's parsed tree is `root.children = [IFD0]` — `IFD0` as a *direct* child of `root`, which never happens for JPEG/HEIC (their `IFD0` is always nested several levels inside `APP1` or an `iloc` item). This is used as the TIFF signal:

```kotlin
val isTiff = root.children.any { it.type == "IFD0" }
```

In `buildImageBasicInfo`, when neither a JPEG `SOF` marker nor an `ispe` box is found, fall back to reading `ImageWidth`/`ImageLength` directly off the root-level `IFD0` for Resolution; and add a `"TIFF"` branch to the Format field (parallel to the existing `"JPEG"` branch) when `isTiff` is true. Camera Info, GPS Location, and Capture Date sections are untouched — they already call `findFirst(root) { it.type == "IFD0" }` and walk its `Exif`/`GPS` children generically, so they work for a root-level `IFD0` exactly as they already do for a deeply-nested one.

## Testing

- `MediaSummaryBuilderTest` additions: a synthetic AVIF-shaped tree (`ftyp` with `major_brand = "avif"`, `meta`/`iprp`/`ipco`/`ispe`/`pitm`/`ipma`, mirroring the existing HEIC test fixtures) asserts Resolution/Format/File Size populate correctly; a synthetic TIFF-shaped tree (`root.children = [IFD0]` with `ImageWidth`/`ImageLength`/`Make`/`Model` fields and nested `GPS`/`Exif` children) asserts Resolution, Format ("TIFF"), Camera Info, and GPS Location all populate correctly.
- `ParseFileIntegrationTest`/new test: a small synthetic TIFF byte fixture (valid `II*\0`/`MM\0*` header + IFD0 with a couple of tags, following `decodeTiff`'s existing byte layout) asserts `parseFile` routes it through `decodeTiff` rather than the generic box walker.
- Manual verification: open a real `.avif` file, confirm Media Summary and Structure Analyser (including the now-decoded `av01` box) render correctly; open a real `.tiff`/`.tif` file, confirm Resolution/Format/Camera Info/GPS/Capture Date render correctly.
