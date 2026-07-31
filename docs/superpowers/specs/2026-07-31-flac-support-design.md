# FLAC Playback and Structural Parsing Support Design

## Goal

Add FLAC to the set of supported audio formats: playable via the existing `FfmpegAudioPlayer`, and structurally parseable in the tree/summary/warnings views like every other supported audio container (WAV, MP3). First of three planned audio-format additions (FLAC, then OGG, then AIFF), each with its own spec/plan.

## Background

`AUDIO_EXTENSIONS` (`AppState.kt`) currently lists `m4a`/`mp3`/`wav`. Playback goes through `FfmpegAudioPlayer.kt`, which already works generically against any format ffmpeg/ffprobe understands (probes sample rate/channels/duration, pipes raw PCM) -- ffmpeg already decodes FLAC natively, confirmed via `ffmpeg -decoders`, so **playback needs zero new code once `flac` is a recognized extension**.

Structural parsing is the real work: `parseFile` (`ParseFile.kt`) dispatches by magic bytes to format-specific walkers (`WavWalker.kt`, `Mp3Walker.kt`, `EbmlWalker.kt` for WebM, etc.) or the generic ISOBMFF `parseBoxes`. FLAC's own container format is unrelated to any of these -- a `"fLaC"` magic followed by a sequence of METADATA_BLOCKs (each a 4-byte header: 1-bit last-block flag + 7-bit block type + 24-bit big-endian length, then that many bytes of block-specific data), followed by the compressed audio frames. A new walker is required, following the same pattern established for WebM's `EbmlWalker.kt`: reuse the existing `BoxNode`/`BoxField` model unchanged, so the tree view, `collectWarnings`, and the CLI `dump`/`check` commands work automatically with no changes anywhere else.

## Design

### A. New walker: `FlacWalker.kt`

`fun parseFlacBlocks(reader: ByteReader, start: Long, end: Long): List<BoxNode>`. Emits a synthetic `"fLaC"` marker node for the 4-byte magic (offset `start`, headerSize 4, size 4 -- the same role WAV's `"RIFF"` node and WebM's `"EBML"` node play: a reliable anchor `detectCategory`/`buildStandaloneAudioSummary` can key off), then loops over METADATA_BLOCKs starting at `start + 4` until the last-block flag is seen, emitting one `BoxNode` per block, decoded according to its type:

- **STREAMINFO** (type 0, mandatory, always 34 bytes): `min_blocksize`/`max_blocksize` (16 bits each), `min_framesize`/`max_framesize` (24 bits each), then an 8-byte (64-bit) packed field split via bit-shift/mask into `sample_rate` (top 20 bits), `channels` (next 3 bits, value+1), `bits_per_sample` (next 5 bits, value+1), `total_samples` (remaining 36 bits) -- read as one big-endian `Long` (reusing the same `readUnsignedBigEndian`-style helper `EbmlWalker.kt` already has for multi-byte big-endian reads), then `shr`+`and`-masked per field. Each shift is always followed by a mask, so arithmetic-vs-logical shift makes no difference to the result (any sign-extended high bits from `shr` are always outside the mask window). Finally a 16-byte MD5 signature field (hex-encoded string).
- **VORBIS_COMMENT** (type 4): tags like title/artist/album -- **little-endian** internally (a real FLAC-spec quirk, unlike every other big-endian part of the format, inherited from the Vorbis comment spec it borrows). `vendor_length`(u32 LE) + `vendor_string`(UTF-8) + `comment_count`(u32 LE), then per comment: `length`(u32 LE) + `"KEY=VALUE"`(UTF-8), split on the first `=` into a `BoxField` named by `KEY`.
- **PICTURE** (type 6): `picture_type`/`mime_length`+`mime`/`description_length`+`description`/`width`/`height`/`color_depth`/`colors_used`/`picture_data_length` fields (all big-endian u32 except the variable-length strings) -- the picture's own raw bytes are not extracted as a displayable thumbnail (non-goal, see below).
- **SEEKTABLE, PADDING, APPLICATION, CUESHEET**: summary-only (e.g. `"$N bytes"`, or for SEEKTABLE a computed seek-point count from `payloadSize / 18`) -- no per-entry decoding, matching this codebase's existing convention of not enumerating large repeated structures as individual fields (e.g. `stss`'s "N entries" summary).
- Any other block type (7-126 reserved, 127 invalid): falls back to the same "unlabeled, byte-count-only" convention as an unrecognized element elsewhere in this codebase.

After the last metadata block, the remaining bytes to `end` become one final node, `"FrameData"`, with a byte-count summary only -- individual FLAC audio frames are not decoded, the same "we don't decode compressed sample data" convention already applied to JPEG's SOS scan data and WebM's `SimpleBlock`.

### B. Wiring

- `ParseFile.kt`: add `isFlacMagic(reader)` (checks `reader.readFourCC(0) == "fLaC"`) to the magic-byte dispatch chain, routing to `parseFlacBlocks(reader, 0, reader.length)`.
- `AppState.kt`: add `"flac"` to `AUDIO_EXTENSIONS`.
- `MediaSummaryBuilder.kt`: `buildStandaloneAudioSummary` gets a third branch (alongside its existing WAV/MP3 dispatch) keyed on `root.children.any { it.type == "fLaC" }`, calling a new `buildFlacSummary(root, fileSizeBytes)` that reads the STREAMINFO node's fields for General (`Duration` from `total_samples / sample_rate`, `Format` = `"FLAC"`, `File Size`) and Audio (`Sampling Rate`, `Channel(s)`, `Bit Depth`) sections, mirroring `buildWavSummary`'s exact field-label vocabulary for consistency.

## Non-Goals

- Decoding individual FLAC audio frames (frame header, subframe types, etc.) -- represented as one opaque `"FrameData"` byte-count node.
- Extracting `PICTURE` block image bytes as a displayable embedded-thumbnail (like JPEG/HEIC thumbnails elsewhere in this app) -- only its metadata fields (type/mime/dimensions) are shown. A natural future enhancement, not required now.
- `SEEKTABLE` per-seek-point detail (sample number/offset/frame samples for each entry) -- only a computed entry count.
- OGG and AIFF support -- separate specs/plans, not part of this one.

## Testing

- `FlacWalker.kt`: unit tests with hand-built byte arrays (matching `EbmlWalkerTest.kt`'s style) covering: the `"fLaC"` marker node, STREAMINFO's bit-packed field extraction (a known sample_rate/channels/bits_per_sample/total_samples combination decoded correctly), VORBIS_COMMENT's little-endian vendor+comment parsing, an unknown/reserved block type falling back to the unlabeled convention, the last-block flag correctly stopping the metadata loop and everything after becoming `"FrameData"`, and a truncated/too-short block header producing a warning (matching `BoxWalkerTest.kt`/`EbmlWalkerTest.kt`'s existing warning-case coverage).
- `ParseFile.kt`/`AppState.kt` wiring: a real-file test using an actual ffmpeg-generated `.flac` fixture, confirming `parseFile` routes it to `parseFlacBlocks` and the file opens as `MediaType.AUDIO`.
- `buildFlacSummary`: unit test with a hand-built `BoxNode` tree (matching `MediaSummaryBuilderTest.kt`'s style for `buildWavSummary`), asserting Duration/Sampling Rate/Channel(s)/Bit Depth fields.
- Manual verification: open a real `.flac` file, confirm the tree, summary, and playback (via `FfmpegAudioPlayer`, including its waveform/spectrogram) all work end-to-end.
