# AV2 ISO-BMFF Structure and Metadata Analysis

## Goal

Allow unwrapMedia to open AV2 media carried in ISO-BMFF containers and inspect its container and codec metadata without decoding pixels. The first release covers MP4 and MOV files using the AV2 draft ISO-BMFF binding. Playback, thumbnails, frame extraction, and raw/Matroska/IVF AV2 files are outside this release.

## Scope

The supported media extension list gains `.av2` so a user can select a file whose name uses that extension. AV2 detection is structural rather than extension-only: an ISO-BMFF file is recognized when its compatible brands include `av02` or a video sample entry is `av02`.

The parser registers `av02` as a visual sample entry and introduces an `av2C` box decoder. The decoder reads the reserved byte, configuration-OBU count, and each length-delimited OBU. It creates child nodes for each OBU, with exact byte ranges, type, layer identifiers when present, declared size, and malformed-length warnings.

The AV2 configuration OBU parser extracts the Sequence Header OBU when present. The first release reports fields that can be parsed without a decoder: profile, level, tier where signalled, coded dimensions, chroma format, bit depth, monochrome state, colour description, film-grain capability, and output-order mode. Metadata, film-grain, and layer-configuration OBUs are represented structurally; their payloads are not semantically decoded in this release.

The summary layer labels the video codec as AV2, exposes the AV2 sequence metadata in the detail panel, and adds a visible warning that the ISO-BMFF binding is based on the 22 September 2026 Working Group Draft. Existing generic MP4 metadata remains available even when an AV2 field cannot be parsed.

## Data Flow

`AppState.openFile` continues to classify `.mp4` and `.mov` as video. `.av2` also routes to the video path. `parseFile` keeps its existing ISO-BMFF box walker. During box decoding, `av02` produces a visual sample-entry node and `av2C` produces typed OBU children. The summary builder resolves the codec from `av02`. The video inspector reads the parsed `av2C` node and displays extracted sequence fields and their hex ranges.

No path invokes ffmpeg for AV2. Existing player and frame-analysis controls remain unavailable when ffprobe cannot decode AV2. This prevents a misleading partial player state.

## Errors and Limits

`av2C` length fields are validated against the parent box and file range. A malformed count, truncated LEB128, oversized OBU, or invalid OBU header becomes a warning on the relevant node; the rest of the MP4 tree remains usable. Unknown OBU types retain their raw bytes and numeric type rather than causing a parse failure.

The AV2 ISO-BMFF binding is a working draft. The UI warning identifies the binding date and links its parsing behavior to the draft so later spec changes can be isolated to the AV2 parser.

## Tests

Unit tests will cover `av02` registration, `av2C` bounds validation, LEB128 framing, OBU header layer fields, a Sequence Header fixture, and summary codec labelling. App-state tests will verify `.av2` reaches the video analysis path without requiring ffmpeg. A synthetic MP4 fixture will exercise the complete `ftyp`/`av02`/`av2C` path and verify that malformed AV2 configuration data produces warnings rather than an exception.

## References

- AV2 v1.0.0 specification, 28 May 2026.
- AV2 Codec ISO Media File Format Binding, Working Group Draft, 22 September 2026: `av02` brand and sample entry; `av2C` configuration box; length-delimited configuration OBUs.
