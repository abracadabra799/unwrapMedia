# AIFF/AIFF-C Playback and Structural Parsing Support Design

## Goal

Add AIFF and AIFF-C to the set of supported audio formats: playable via the existing `FfmpegAudioPlayer` (ffmpeg already decodes AIFF natively -- confirmed by generating a real fixture with plain `ffmpeg -i ... out.aiff`, no new playback code needed), and structurally parseable in the tree/summary/warnings views like every other supported audio container (WAV, MP3, FLAC, OGG/Opus). Third and last of the three planned audio-format additions (FLAC, then OGG/Opus, now AIFF), each with its own spec/plan per the user's explicit choice.

## Background

`AUDIO_EXTENSIONS` (`AppState.kt`) currently lists `m4a`/`mp3`/`wav`/`flac`/`ogg`/`opus`. AIFF's container ("Audio Interchange File Format") is an IFF chunk format: `"FORM"` + a 4-byte big-endian size + a 4-byte form type (`"AIFF"` for classic AIFF, `"AIFC"` for AIFF-C, the compression-capable extension), followed by a sequence of chunks (`chunkID`(4) + `chunkSize`(4, big-endian) + payload, padded to an even byte boundary). This is structurally almost identical to WAV's RIFF container (`WavWalker.kt` is the closest precedent) -- the only structural difference is byte order: WAV is little-endian throughout, AIFF is big-endian throughout. Since this app's `ByteReader` is big-endian by default, AIFF's chunk walker needs no custom endian-swapping helpers at all (unlike `WavWalker.kt`, which defines its own `readUInt16LE`/`readUInt32LE`).

The one genuinely new challenge is the `COMM` (Common) chunk's `sampleRate` field: unlike every sample rate this app has decoded so far (WAV's 32-bit integer, FLAC's bit-packed integer, Ogg/Vorbis's 32-bit integer), AIFF stores it as a 10-byte **80-bit IEEE 754 extended-precision float** (the x87 FPU's native format) -- a completely different bit layout from the 32/64-bit floats most languages support natively, requiring a hand-rolled decoder.

AIFF-C (form type `"AIFC"`) is common in practice -- many real "AIFF" files produced by Apple's own tools are actually AIFF-C internally -- and only adds two extra fields to the end of the `COMM` chunk (`compressionType` FourCC + a Pascal-string `compressionName`), so both are supported by the same walker with no meaningful extra cost.

## Design

### A. New walker: `AiffWalker.kt`

`fun parseAiffChunks(reader: ByteReader, start: Long, end: Long): List<BoxNode>`.

Emits a `"FORM"` marker node (offset `start`, headerSize 8, size 12 -- the same role WAV's `"RIFF"` node plays) with fields `file_size` (declared size + 8) and `form_type` (`"AIFF"` or `"AIFF-C"`, read from the 4 bytes after `"FORM"`+size). Then loops over chunks starting at `start + 12`, using the exact same chunk-loop shape as `WavWalker.kt`'s `parseWavChunks` (chunk ID + big-endian size + payload, odd sizes padded to even, a chunk declared to extend past the file's end gets a warning and stops the loop) -- but reading sizes via `ByteReader`'s native big-endian `readUInt32`/`readUInt16` directly, with no LE helpers needed.

Per chunk type:

- **`COMM`**: `num_channels` (u16), `num_sample_frames` (u32), `sample_size` (u16, bits per sample), `sample_rate` (10-byte 80-bit extended-precision float, decoded by a new `readExtendedFloat80(reader, offset): Double` -- sign bit + 15-bit biased exponent (bias 16383) + a 64-bit mantissa that (unlike normal IEEE754) has an *explicit* leading integer bit, so `value = sign * (mantissa as unsigned 64-bit) * 2^(exponent - 16383 - 63)`. Verified against a real ffmpeg-generated fixture: the byte sequence `40 0E AC 44 00 00 00 00 00 00` decodes to exactly `44100.0` by this formula, confirmed by hand -- exponent field `0x400E - 0x3FFF = 15`, mantissa `0xAC44000000000000 = 44100 * 2^48`, so `value = (44100 * 2^48 / 2^63) * 2^15 = 44100 * 2^0 = 44100`). If the form type is `"AIFF-C"` and the chunk is long enough, two more fields are decoded: `compression_type` (4-byte FourCC, mapped to a human-readable name via a small lookup table -- `"NONE"`→"PCM (uncompressed)", `"sowt"`→"PCM (little-endian)", `"fl32"`→"32-bit float", `"fl64"`→"64-bit float", `"ima4"`→"IMA 4:1 ADPCM", `"MAC3"`→"MACE 3:1", `"MAC6"`→"MACE 6:1", `"ulaw"`/`"ULAW"`→"μ-law", `"alaw"`/`"ALAW"`→"A-law", `"Qclp"`→"Qualcomm PureVoice", `"QDMC"`/`"QDM2"`→"QDesign Music", unrecognized → `"Unknown (fourCC)"`) and `compression_name` (a Pascal string: 1 length byte + that many ASCII bytes).
- **`SSND`** (Sound Data): `offset`/`block_size` fields (the chunk's own 8-byte header, almost always zero in practice) plus a byte-count summary for the actual sample data that follows -- the raw PCM bytes themselves are never decoded, matching this app's established "don't decode compressed/raw sample payloads" convention (WAV's `data`, FLAC's `FrameData`, Ogg's `OggPages`).
- Any other chunk (`MARK`, `INST`, `COMT`, `NAME`, `AUTH`, `"(c) "`, `ANNO`, etc.): byte-count-only summary, no field decoding -- these are uncommon and not useful to expose field-by-field for this app's purposes.

### B. Wiring

- `ParseFile.kt`: add `isAiffMagic(reader)` (checks `reader.readFourCC(0) == "FORM"` and `reader.readFourCC(8)` is `"AIFF"` or `"AIFC"` -- the second check is necessary since `"FORM"` alone is shared by other IFF-family formats not used here) to the magic-byte dispatch chain, routing to `parseAiffChunks(reader, 0, reader.length)`.
- `AppState.kt`: add `"aiff"`, `"aif"`, and `"aifc"` to `AUDIO_EXTENSIONS` (all three route through the same magic-byte detection and parser).
- `MediaSummaryBuilder.kt`: `detectCategory` gets a `root.children.any { it.type == "COMM" }` check -- mirroring WAV's own `"fmt "` check exactly, since `"COMM"` is AIFF-specific and cannot collide with any other format this app parses. `buildStandaloneAudioSummary` gets a new branch calling `buildAiffSummary(root, fileSizeBytes)`: reads the `COMM` node's fields for General (`Format` = "AIFF" or "AIFF-C", `Duration` = `num_sample_frames / sample_rate`, `File Size`, `Overall Bit Rate`) and Audio (`Format` = the compression type's display name, or "PCM" for classic AIFF; `Sampling Rate`; `Channel(s)`; `Bit Depth` from `sample_size`), reusing the same field-label vocabulary as `buildWavSummary` for consistency.

## Non-Goals

- Decoding the actual PCM sample bytes inside `SSND` -- represented as one byte-count summary, same as every other format's raw sample data.
- Field-by-field decoding of `MARK`/`INST`/`COMT`/text chunks -- byte-count summary only.
- A complete mapping of every AIFF-C `compressionType` FourCC ever used in the wild -- only the common ones get a friendly name; anything else falls back to `"Unknown (fourCC)"`, matching this app's established fallback-naming convention (FLAC's unknown block types, WebM's unknown element IDs).

## Testing

- `AiffWalker.kt`: unit tests with hand-built byte arrays (matching `FlacWalkerTest.kt`'s/`OggWalkerTest.kt`'s style) covering: the `"FORM"` marker node for both `"AIFF"` and `"AIFC"` form types, a `COMM` chunk's field decoding for classic AIFF (including the 80-bit extended-precision `sample_rate` using the hand-verified `44100.0` byte sequence above), a `COMM` chunk's extra `compression_type`/`compression_name` fields when the form type is AIFF-C, an `SSND` chunk's offset/block_size fields plus byte-count summary, an unrecognized chunk type falling back to a byte-count-only summary, and a chunk declared to extend past the end of the file producing a warning and stopping the scan (matching `WavWalker.kt`'s existing behavior for the equivalent case).
- `ParseFile.kt`/`AppState.kt` wiring: a real-file test using an actual ffmpeg-generated `.aiff` fixture, confirming `parseFile` routes it to `parseAiffChunks` and the file opens as `MediaType.AUDIO`.
- `buildAiffSummary`: unit test with a hand-built `BoxNode` tree (matching `MediaSummaryBuilderTest.kt`'s style for `buildWavSummary`), asserting Duration/Sampling Rate/Channel(s)/Bit Depth fields, including a separate case for the AIFF-C compression-type-as-Format display.
- Manual verification: open a real `.aiff` file (and, if easily producible, a real AIFF-C file), confirm the tree, summary, and playback (via `FfmpegAudioPlayer`, including its waveform/spectrogram) all work end-to-end.
