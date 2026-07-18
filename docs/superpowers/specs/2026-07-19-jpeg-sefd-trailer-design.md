# JPEG SEFD Trailer Parsing — Design

## Background

The already-shipped Samsung SEFD/Motion Photo feature (`docs/superpowers/specs/2026-07-17-sefd-motion-photo-design.md`) decodes Samsung's `sefd` field-directory format when it appears as a top-level ISOBMFF box inside HEIC files. That design's Non-Goals section explicitly deferred "the older JPEG/PNG trailer form of this format (raw bytes appended after a JPEG, found by scanning from EOF rather than being a proper ISOBMFF box)" because JPEG support didn't exist yet at the time.

JPEG support has since shipped (`docs/superpowers/plans/2026-07-18-jpeg-support.md`, `2026-07-18-jpeg-detail-decoding.md`). Testing the JPEG feature against real Samsung phone photos showed that after the JPEG marker-segment data ends, there is frequently several KB to several MB of trailing data that the walker cannot parse (it correctly bails out with `"Expected marker prefix 0xFF, found 0x..."`, per the no-throw convention, but shows nothing useful).

This design closes that gap: recognize and decode this trailing data as a Samsung SEFD trailer when present.

## Research

This design is grounded in empirical analysis of three real sample files (`~/Downloads/20260718_200431.jpg`, `20260718_200426.jpg`, `20260718_200439.jpg`) — every byte offset and structural claim below was independently verified by directly parsing each file's actual trailing bytes with a Python script, not assumed from documentation alone. This was cross-checked against public community research (the [Hacker Factor blog's "Reversing Samsung Metadata"](https://www.hackerfactor.com/blog/index.php?/archives/1039-Reversing-Samsung-Metadata.html) and exiftool's `Samsung.pm` module), which independently confirms the same structure and describes it as an undocumented, proprietary format appended at the end of JPEG/PNG files by Samsung's "Sound & Shot"/"Shot & More"/Motion Photo camera features (Galaxy S4 and later).

**Key finding: the JPEG-trailer form of this format is byte-for-byte identical to the already-implemented HEIC `sefd` box's internal structure** — same `SEFH`...`SEFT` directory framing, same per-field block layout (`reserved`+`marker`+`name_size`+`name`+`data`), same offset-resolution formula (`absoluteBlockStart = sefhPosition - entry.offset`). The only difference is *where* this structure lives: inside a `sefd` ISOBMFF box (HEIC) versus appended directly at the end of the file with no box wrapper (JPEG). Verified across all three sample files, including one (`20260718_200431.jpg`) with a 7-entry directory containing `MotionPhoto_AutoPlay` (554,333 bytes) and `MotionPhoto_Data` (4,059,205 bytes), both confirmed to start with an embedded `ftyp` MP4 signature at their data's relative offset 4.

**Structural detail specific to the JPEG variant:** in the already-shipped HEIC design, `MotionPhoto_Data`'s value is a 12-byte pointer (`format_tag`/`video_offset`/`video_length`) into a sibling `mpvd` box elsewhere in the file — because HEIC's box structure allows a separate top-level box for the video. JPEG has no equivalent "separate top-level box" concept, so in the JPEG-trailer variant, `MotionPhoto_Data` instead contains the *entire* embedded MP4 video inline as its field data (confirmed: 4,059,205 bytes starting with `ftyp`, not a 12-byte pointer). The already-shipped `SefdBoxDecoder.decodeField`'s value-decoding priority order — check for an embedded `ftyp` MP4 signature *before* checking `name == "MotionPhoto_Data"` — already handles this correctly with no code changes: the generic embedded-MP4 sniff fires first and wins for the JPEG variant's inline video, while the name-specific 12-byte-pointer branch (guarded by `dataLength == 12`) simply never matches it. This was verified by reading `SefdBoxDecoder.kt`'s actual source, not assumed.

**Also confirmed:** the byte offset where the JPEG marker-segment walker (`parseJpegSegments`) naturally stops parsing (the first non-`0xFF` byte after the last real marker segment) exactly equals the start of the SEFD trailer's lowest-addressed field block, in all three samples. This isn't load-bearing for the design (see below — the trailer's own internal offsets are self-describing, independent of where the JPEG walker happened to stop), but it confirms the trailer is appended with no gap or padding after the JPEG data.

## Goal

When JPEG marker-segment parsing (`parseJpegSegments`) reaches a point where the next byte is not a valid marker prefix (`0xFF`), and the file's last 4 bytes are `"SEFT"`, decode the trailing region as a Samsung SEFD trailer using the exact same field-directory decoding already shipped for HEIC, and show it in the tree as a `sefd` node — instead of (or in addition to, if trailing bytes remain unaccounted for) the existing generic `"?"` warning node.

## Non-Goals

- No changes to `SefdBoxDecoder.kt` itself — this design is pure reuse.
- No support for the PNG variant of this same trailer form (community research confirms Samsung also appends this trailer to PNG files) — out of scope, no PNG support exists in this project at all.
- No handling of a SEFD trailer that is truncated, corrupted, or offset-inconsistent beyond what `SefdBoxDecoder.decode`'s existing bounds checks and warnings already do — any such case falls back to whatever `SefdBoxDecoder.decode` itself reports (which may include its own warnings), not a new JPEG-specific error path.
- No encoder-signature-style verification that a `"SEFT"`-terminated trailing region is *actually* a well-formed Samsung trailer beyond what `SefdBoxDecoder.decode`'s own `SEFH`/`sef_size`/bounds validation already provides.

## Design

### Reuse, not duplication

`SefdBoxDecoder` (`app/src/main/kotlin/com/multiviewer/parser/SefdBoxDecoder.kt`) already implements the full `BoxDecoder` interface: `decode(reader, type, offset, headerSize, size, warnings): BoxNode`. Its internal logic computes `payloadStart = offset + headerSize` and `payloadEnd = offset + size`, then locates `SEFT`/`SEFH` and decodes the field directory entirely within `[payloadStart, payloadEnd)` — it has no dependency on actually being inside an ISOBMFF box. Calling it directly with `headerSize = 0` and `size = <trailing region length>` makes `payloadStart`/`payloadEnd` collapse to exactly the raw trailing byte range, so the object can be invoked unchanged:

```kotlin
SefdBoxDecoder.decode(reader, "sefd", start, 0, end - start, emptyList())
```

This is the same "extract and reuse core logic across two entry shapes" pattern already used in this project for `decodeExif`/`decodeTiff` (Exif: HEIF's box-wrapped form vs. JPEG's bare-TIFF-header form).

### `JpegWalker.kt` change

In `parseJpegSegments`'s main loop, at the point where `markerPrefix != 0xFF` is currently detected (today: unconditionally emit a `"?"` warning node and stop), first check whether the file's last 4 bytes are `"SEFT"`. If so, call `SefdBoxDecoder.decode` on the range from the current position to the end of the file and use its result instead of the generic warning node. If the last 4 bytes are not `"SEFT"` (an ordinary malformed/truncated JPEG, or one with no Samsung trailer), fall back to the existing `"?"` warning behavior, completely unchanged.

```kotlin
private fun tryDecodeSefdTrailer(reader: ByteReader, start: Long, end: Long): BoxNode? {
    if (end - start < 4 || reader.readFourCC(end - 4) != "SEFT") return null
    return SefdBoxDecoder.decode(reader, "sefd", start, 0, end - start, emptyList())
}
```

This function is checked once, at the exact point the walker would otherwise give up — it cannot fire more than once per file, and it never changes behavior for any JPEG that doesn't end in a Samsung trailer.

### Tree presentation

The resulting node's `type` is `"sefd"` — the same label already used for the HEIC path — so a user who has seen this feature on a HEIC file recognizes the same structure immediately on a JPEG. All child field nodes (`Image_UTC_Data`, `MCC_Data`, `Color_Display_P3`, `Photo_HDR_Info`, `Camera_Capture_Mode_Info`, `MotionPhoto_AutoPlay`, `MotionPhoto_Data`, etc.) render exactly as they already do for HEIC, including `MotionPhoto_AutoPlay`/`MotionPhoto_Data` recursing into a full nested MP4 box tree via the existing embedded-`ftyp` sniff.

## Testing

Unit tests (following this project's established `byteReaderOf`/exact-byte-fixture convention, every fixture independently verified via a standalone Python script before being written into the implementation plan) covering:
- A synthetic JPEG (SOI + a trivial marker + EOI) followed directly by a synthetic SEFD trailer (`SEFH`/directory/`SEFT`, built the same way the existing `SefdBoxDecoderTest` fixtures are) — confirms `parseJpegSegments` returns a `sefd` node with the expected field children instead of a `"?"` warning node.
- A JPEG with ordinary trailing garbage that does *not* end in `"SEFT"` — confirms the existing `"?"` warning behavior is completely unchanged (this is effectively the pre-existing `a byte that is not 0xFF where a marker is expected produces a warning and stops` test, which must keep passing unmodified).
- A JPEG file shorter than 4 bytes past the failure point (edge case for the `end - start < 4` guard) — confirms no exception, falls back to the warning node.

Manual verification: open one of the real sample JPEGs already used for this project's testing and confirm the `sefd` node appears with the same field breakdown already verified manually for the HEIC path (offsets/names matching what was independently confirmed via the Python analysis in this design's Research section), including `MotionPhoto_Data` recursing into a nested MP4 tree for the sample that contains it.
