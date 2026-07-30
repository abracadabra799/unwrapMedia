# WebM Playback and Structural Parsing Support Design

## Goal

Add WebM to the set of supported video formats: playable via the existing player, and structurally parseable in the tree/summary/warnings views like every other supported container.

## Background

This app currently supports `mp4`/`mov`/`m4v` (`VIDEO_EXTENSIONS` in `AppState.kt`). Playback goes through `FfmpegVideoPlayer.kt`, which shells out to the bundled ffmpeg binary (a general BtbN/FFmpeg-Builds LGPL build) -- confirmed this build already demuxes Matroska/WebM and decodes VP8/VP9/Opus, so playback needs no new native dependency. GOP frame-type analysis (`probeFrameTypes`) and per-stream codec detail probing (`probeStreamDetails`) also run ffprobe directly against the source file, independent of this app's own structural parser, so both work automatically once the extension is added.

Structural parsing is the real gap: `parseFile` (`ParseFile.kt`) dispatches by magic bytes to one of several format-specific walkers (`JpegWalker`, `PngWalker`, `WebpWalker`, etc.) or, for anything ISOBMFF-shaped (MP4/MOV/M4A), the generic box walker `BoxWalker.kt` (`parseBoxes`) -- 8-byte fixed headers (4-byte big-endian size + 4-byte ASCII fourCC). WebM uses Matroska's EBML binary format instead: variable-length-integer element IDs and sizes, numeric (not 4-character) element identifiers, defined by the Matroska/EBML specification rather than ISOBMFF's. None of the existing walkers can read it; a new one is required.

## Design

### A. New walker: `EbmlWalker.kt`

`fun parseEbmlElements(reader: ByteReader, start: Long, end: Long): List<BoxNode>`, structurally parallel to `parseBoxes` -- reuses the existing `BoxNode`/`BoxField` model unchanged (offset, headerSize, size, children for master/container elements, fields/summary for leaf elements, warnings using the same conventions as `BoxWalker.kt`: declared size overrunning its parent, truncated headers, etc.). Because it returns the same `BoxNode` tree type everything else already consumes, the structure tree view, `collectWarnings`, and the CLI `dump`/`check` commands work on WebM files with no changes anywhere else.

Two new low-level readers (EBML has no fixed header size, unlike every other format this app parses):
- **Element ID**: a variable-length integer (1-4 bytes), determined by the position of the leading `1` bit in the first byte -- the marker bit(s) are kept as part of the ID's value (canonical EBML convention; IDs are conventionally written/matched including their length marker).
- **Element size**: also a VINT, but the marker bit is stripped to get the numeric value. A special "unknown size" encoding (all value bits set to 1) is treated the same way `BoxWalker.kt` already treats a declared `size == 0`: the element's content extends to the end of its parent's range.

### B. Element ID → name/type table

A table (new file or a private map inside `EbmlWalker.kt`, following `BoxRegistry`'s existing role for ISOBMFF) maps known Matroska element IDs to `(name: String, type: EbmlElementType)`, where `EbmlElementType` is one of `MASTER` (recurse into children, matching how `BoxWalker` recurses via `BoxRegistry`), `UINT`, `STRING`/`UTF8`, `FLOAT`, `DATE`, or `BINARY` (leaf types, each decoded into a `BoxField` value the same way `FtypBoxDecoder` and friends already decode ISOBMFF leaf fields). The table covers as many real Matroska elements as practical to name (not just a minimal handful) -- `EBML`, `Segment`, `SeekHead`/`Seek`, `Info` (`TimecodeScale`, `Duration`, `MuxingApp`, `WritingApp`), `Tracks`/`TrackEntry` (`TrackNumber`, `TrackType`, `CodecID`, `Video`/`PixelWidth`/`PixelHeight`, `Audio`/`SamplingFrequency`/`Channels`), `Cues`/`CuePoint`, `Cluster`/`SimpleBlock`/`BlockGroup`, and their common relatives. Any element ID not in the table falls back to the same "unlabeled, shown as a bare numeric ID" convention this app already uses elsewhere (e.g. unlabeled JPEG destination IDs) -- not a parse error, just unnamed.

### C. Wiring

- `ParseFile.kt`: add `isEbmlMagic(reader)` (checks for the EBML header ID `0x1A45DFA3` at offset 0) to the existing magic-byte dispatch chain, routing matches to `parseEbmlElements(reader, 0, reader.length)`.
- `AppState.kt`: add `"webm"` to `VIDEO_EXTENSIONS`.
- `MediaSummaryBuilder.kt`: `detectCategory` recognizes a root with an `EBML` child as `MediaCategory.VIDEO`; a new `buildWebmVideoSummary(root, fileSizeBytes)` (parallel to, not shared with, the existing ISOBMFF-specific `buildVideoSummary`) builds General/Track/Video/Audio sections by reading `Segment` -> `Info` (`Duration` x `TimecodeScale` for real seconds, matching how `buildVideoGeneral` already combines `mvhd`'s `timescale`/`duration`) and `Segment` -> `Tracks` -> `TrackEntry` (per-track `CodecID`, `PixelWidth`/`PixelHeight`, `SamplingFrequency`/`Channels`), presented in the same `SummarySection`/`SummaryField` shape the UI already renders generically.

## Non-Goals

- Full Matroska/EBML specification coverage (hundreds of defined elements, chapters, tags, attachments, detailed subtitle-track parsing) -- only the elements actually meaningful to a video inspector are named; everything else still parses structurally (as an unnamed numeric-ID node) but isn't specially summarized.
- Embedding scenarios (e.g. a motion-photo-style file with WebM embedded inside another container) -- out of scope, no current use case.
- Any change to `FfmpegVideoPlayer.kt`, `probeFrameTypes`, or `probeStreamDetails` -- all three already work generically against the source file and need no WebM-specific code.

## Testing

- `EbmlWalker.kt`: unit tests with hand-built byte arrays (matching this codebase's existing walker-test style, e.g. `JpegWalkerTest.kt`/`BoxWalkerTest.kt`) covering: VINT decoding for element IDs and sizes at each byte-length (1-4 bytes), the "unknown size extends to end of parent" case, a known named element decoding to the right type/value, an unknown element ID falling back to the unlabeled/numeric convention, and the same size-overrun/truncation warning cases `BoxWalkerTest.kt` already covers for the ISOBMFF walker.
- `ParseFile.kt`/`AppState.kt` wiring: a real-file test using an actual ffmpeg-generated `.webm` fixture (matching this session's established pattern of generating real fixtures via `ProcessBuilder("ffmpeg", ...)` rather than hand-rolling container bytes for whole-file tests), confirming `parseFile` routes it to `parseEbmlElements` and the file opens as `MediaType.VIDEO`.
- `buildWebmVideoSummary`: unit tests with a hand-built `BoxNode` tree (matching `MediaSummaryBuilderTest.kt`'s existing style for `buildVideoSummary`) asserting the extracted Duration/track fields.
- Manual verification: open a real `.webm` file, confirm the tree, summary, playback, and (if the file has enough frames) GOP frame analysis all work end-to-end.
