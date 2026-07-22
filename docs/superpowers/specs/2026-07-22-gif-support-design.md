# GIF (Static + Animated) Format Support — Design

## Background

GIF is the last format from the user's original broader-format-support request (TIFF, AVIF, PNG, BMP, GIF, animated GIF), previously deferred twice (first alongside AGIF when TIFF/AVIF were prioritized, then again when PNG/BMP were scoped without it). With TIFF, AVIF, PNG, and BMP now shipped — plus the MediaInfo tree-view alignment restructuring Media Summary into General/Image/Video/Audio categories — GIF is the remaining item.

GIF (both the static GIF87a and animated GIF89a variants — the 89a revision that added animation is a strict superset of 87a) is a block-based stream: a fixed 6-byte header, a Logical Screen Descriptor, an optional Global Color Table, then a sequence of self-describing blocks (each starting with a 1-byte introducer) until a Trailer byte ends the file. This is structurally the same shape as PNG's chunk stream (already implemented as `PngWalker.kt`) and JPEG's marker-segment stream (`JpegWalker.kt`) — a flat, self-describing sequence of typed blocks — so the same "flat walker, decode known blocks, generic node for the rest" pattern applies directly, and animated GIF requires no additional architecture beyond what static GIF needs: an animated GIF is simply a static GIF's block stream with more than one Image Descriptor block, each preceded by its own Graphic Control Extension. The walker doesn't need to know "this file is animated" as a special case — it just decodes whatever sequence of blocks is actually present.

## Goal

Open a `.gif` file (static or animated) and see it parsed as an image: Structure Analyser shows the Logical Screen Descriptor, Global Color Table (if present), each frame's Graphic Control Extension and Image Descriptor, and the Application Extension (with Netscape loop-count decoded when present) — all as properly decoded nodes, with Comment/Plain Text Extensions and any other block shown as generic, browsable nodes. Media Summary shows General (Format "GIF", File Size) and Image (Width, Height, and — only when applicable — Frame Count and Loop Count) sections, using the same General/Image structure already established for every other image format.

## Non-Goals

- No LZW decompression or pixel rendering — the app is a structure/metadata viewer, not an image renderer. Image data sub-blocks are skipped over (their byte ranges are still visible/navigable in Structure Analyser as part of the Image Descriptor node), never decoded into pixels.
- No Color Table entry decoding — Global/Local Color Tables show only their byte size, not each individual RGB triplet. (Parallels PNG's deferred ICC/embedded-profile-byte decoding — the table exists and is navigable, just not broken into per-entry fields.)
- No Comment Extension or Plain Text Extension text decoding — both show as generic, field-less nodes. (Parallels PNG's deferred zTXt/iTXt.)
- No Color Space field for GIF in Media Summary — GIF pixel data is always palette-indexed, so there's no meaningful per-file color-space value to show, matching BMP's precedent (also deferred, also a simple/legacy format without a color-space concept worth surfacing).
- No frame-rate or total-duration calculation — real-world GIFs commonly have varying per-frame delay times, so there is no single reliable "fps" the way video files have one; only Frame Count (a plain block count) and Loop Count (a plain field from the Netscape extension) are computed, both of which are unambiguous regardless of delay variation.
- No support for interlaced GIF pixel reconstruction — the `Interlace Flag` on an Image Descriptor is shown as a raw field, nothing further (parallels PNG's deferred Adam7 interlace handling).
- No non-Netscape application extension decoding — any Application Extension whose identifier isn't `NETSCAPE2.0` shows its Application Identifier field but not any nested sub-block data (there's no other widely-used application extension convention worth special-casing).

## Design

### 1. GIF detection (`ParseFile.kt`)

GIF's magic is a 6-byte ASCII header, either `GIF87a` or `GIF89a`:

```kotlin
private fun isGifMagic(reader: ByteReader): Boolean {
    if (reader.length < 6) return false
    val bytes = reader.readBytes(0, 6)
    val text = String(bytes, Charsets.US_ASCII)
    return text == "GIF87a" || text == "GIF89a"
}
```

`parseFile` gains a fifth branch, checked alongside the existing JPEG/PNG/BMP/TIFF checks (order among them doesn't matter — all five magics are mutually exclusive on their first bytes): `isGif -> parseGifBlocks(reader, 6, reader.length)` (starting past the 6-byte header, mirroring how PNG's chunk stream starts at offset 8, past its own signature).

### 2. GIF block walker (new file `GifWalker.kt`)

A flat loop, mirroring `parsePngChunks`'s shape: read one block at a time by its introducer byte, dispatch to a specific decoder or a generic fallback, advance past it, repeat until the Trailer byte (`0x3B`) or end of file.

- **Logical Screen Descriptor** — always the first block after the header (7 bytes: width(2,LE) + height(2,LE) + packed fields(1) + background color index(1) + pixel aspect ratio(1)). Decoded fields: `width`, `height`, `global_color_table_flag`, `color_resolution`, `sort_flag`, `global_color_table_size`, `background_color_index`, `pixel_aspect_ratio`; summary `"WxH"`.
- **Global Color Table** — present immediately after the Logical Screen Descriptor when its `global_color_table_flag` is set; size in bytes is `3 * 2^(N+1)` where N is `global_color_table_size`. Decoded as a generic-shaped node (type `"GlobalColorTable"`) with a single `size` field — entries themselves aren't broken out (Non-Goal above).
- **Extension Introducer** (`0x21`) blocks — dispatch on the following Label byte:
  - **Graphic Control Extension** (Label `0xF9`): fixed 4-byte body (disposal method, user input flag, transparent color flag packed into 1 byte; delay time, 2 bytes LE; transparent color index, 1 byte) followed by a 1-byte terminator. Decoded fields: `disposal_method`, `transparent_color_flag`, `delay_time` (in hundredths of a second, matching the GIF spec's unit — displayed as-is, not converted), `transparent_color_index`.
  - **Application Extension** (Label `0xFF`): fixed 11-byte body is `block_size`(1, always 11) + `application_identifier`(8 ASCII bytes) + `application_auth_code`(3 ASCII bytes), followed by one or more size-prefixed data sub-blocks, terminated by a zero-size sub-block. Decoded field: `application_identifier` (e.g. `"NETSCAPE2.0"` when identifier+auth code are read together). When the identifier is exactly `"NETSCAPE2.0"`, the first data sub-block (3 bytes: sub-block ID `0x01` + loop count, 2 bytes LE) is additionally decoded into a `loop_count` field.
  - **Comment Extension** (Label `0xFE`) and **Plain Text Extension** (Label `0x01`) — both consist of size-prefixed sub-blocks terminated by a zero-size sub-block (Plain Text also has a fixed 12-byte header before its sub-blocks). Both fall through to the generic fallback: a node is still emitted with the correct type and byte range (so it's visible/navigable in Structure Analyser), but no fields are decoded from its content.
  - Any other/unrecognized extension label — same generic fallback treatment.
- **Image Descriptor** (`0x2C`): fixed 9-byte body (left position, top position, width, height — all 2 bytes LE — plus 1 packed-fields byte for local color table flag/interlace flag/sort flag/local color table size). Decoded fields: `left`, `top`, `width`, `height`, `local_color_table_flag`, `interlace_flag`, `local_color_table_size`. If the local color table flag is set, a nested `LocalColorTable` child node follows (same generic size-only treatment as the Global Color Table). After that comes a 1-byte LZW minimum code size, then the image's compressed pixel data as size-prefixed sub-blocks terminated by a zero-size sub-block — these are walked (to correctly compute the node's total byte size and advance the outer loop to the next block) but not decoded (Non-Goal above); summary `"WxH at (left,top)"`.
- **Trailer** (`0x3B`): a single-byte marker; the walker stops here (matches the GIF spec — nothing meaningful follows a Trailer, and real-world files reliably end at or very near it).

A shared private helper reads the size-prefixed sub-block sequence common to Application/Comment/Plain-Text Extensions and Image Descriptor pixel data: repeatedly read a 1-byte size, then that many data bytes, until a zero-size byte is read; returns the byte offset immediately after the terminator (and, for Application Extension, the raw bytes of each sub-block, so the Netscape loop-count sub-block can be inspected).

### 3. Media Summary integration (`MediaSummaryBuilder.kt`)

In `buildImageGeneral`, add `isGif` detection (`root.children.any { it.type == "LogicalScreenDescriptor" }`) alongside the existing `isJpeg`/`isTiff`/`isPng`/`isBmp` checks, contributing `Format = "GIF"` — same pattern as the other three format-literal branches.

In `buildImageDetail`, add a `isGif` branch reading `width`/`height` directly off the `LogicalScreenDescriptor` node (same pattern as the PNG/BMP/TIFF branches), and two additional conditional fields, added only when computable (matching the file's existing "add field only if non-null" idiom used throughout this function):
- `Frame Count`: the count of `ImageDescriptor` child nodes anywhere in the tree (`root.children.count { it.type == "ImageDescriptor" }`) — added whenever at least one exists (i.e., always for a valid GIF; this makes static vs. animated GIFs visually distinguishable by "Frame Count: 1" vs. "Frame Count: 12").
- `Loop Count`: read from the `ApplicationExtension` node's `loop_count` field, when present — displayed as `"Infinite"` when the value is `0` (GIF's own convention for infinite looping), otherwise the raw number as a string.

No changes to `buildImageSummary`, Camera Info, GPS Location, or Samsung Metadata construction — GIF has no EXIF-equivalent metadata mechanism in scope here, so those sections simply don't appear for GIF files, consistent with how they already don't appear for BMP.

## Testing

- `GifWalkerTest` (new): a synthetic static GIF (header + Logical Screen Descriptor + Global Color Table + one Graphic Control Extension + one Image Descriptor with minimal LZW data + Trailer) asserts each block decodes with correct fields/summary; a synthetic animated GIF (same, but with 3 Graphic-Control+Image-Descriptor pairs) asserts 3 `ImageDescriptor` nodes are produced; a synthetic Application Extension with identifier `NETSCAPE2.0` asserts `loop_count` decodes correctly; a synthetic Application Extension with a different identifier asserts no `loop_count` field is produced; an unrecognized extension label and a Comment Extension both assert generic, field-less node output.
- `ParseFileIntegrationTest` addition: a small synthetic GIF byte fixture asserts `parseFile` routes it through `parseGifBlocks` rather than the generic box walker or another format's path.
- `MediaSummaryBuilderTest` additions: a synthetic static-GIF-shaped tree (one `ImageDescriptor`) asserts Resolution/Format/Frame Count ("1") populate and no Loop Count field appears; a synthetic animated-GIF-shaped tree (three `ImageDescriptor` nodes plus a Netscape `ApplicationExtension` with `loop_count = 0`) asserts Frame Count ("3") and Loop Count ("Infinite") both populate correctly.
- Manual verification: open a real static `.gif` and a real animated `.gif`, confirm Media Summary shows correct Width/Height/Frame Count/Loop Count, and Structure Analyser shows the Logical Screen Descriptor, Color Table(s), Graphic Control Extension(s), Image Descriptor(s), and Application Extension all correctly decoded, with Comment/Plain-Text Extensions (if the sample file has any) shown as generic browsable nodes.
