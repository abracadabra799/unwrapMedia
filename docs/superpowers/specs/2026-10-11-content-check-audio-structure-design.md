# Content Check Phase 3: audio structure validation

## Goal

Add byte-level structure checks for standalone audio files to the unified
Content Check window. The checks should identify malformed container/codec
structures and report actionable findings with file offsets where available.
This complements phase 2 audio decoding: a file can be structurally suspicious
even when a decoder recovers it, or decode unsuccessfully without a precise
byte-level finding.

## Scope

Supported formats:

- RIFF/WAVE (PCM and other recognized WAVE codecs)
- AIFF and AIFF-C
- FLAC, including metadata boundaries and frame CRC checks
- MPEG audio / MP3, including frame continuity and Xing/VBRI headers
- ADTS AAC
- Ogg audio (Vorbis and Opus), including page CRC and sequence continuity
- ISO BMFF audio files (M4A), including audio sample tables and chunk ranges
- ASF / WMA

The check applies to standalone files classified as `MediaType.AUDIO`. Audio
tracks inside video containers remain covered by phase 2 audio decoding and the
existing video packet checks; phase 3 does not attempt to validate embedded
audio sample tables independently.

## User experience and result model

Extend the existing structure-check result path with an audio-specific report,
using the shared integrity item model (`PASS`, `WARN`, `FAIL`, `SKIP`). The
Content Check window's Structure tab will show a separate “Audio structure”
section alongside parser warnings, so parser diagnostics remain visible and
distinct from the new checks. Each finding includes a concise title, detail,
and byte offset/length when meaningful.

- `FAIL`: objectively invalid or out-of-file structure, such as a declared
  chunk/frame/table range extending past the file, invalid required header,
  sequence discontinuity, or a CRC mismatch.
- `WARN`: suspicious or unverifiable structure that is not sufficient to prove
  corruption, such as unsupported variants, ambiguous resynchronization, or
  optional metadata inconsistencies.
- `SKIP`: a format-specific check that cannot apply to this file/codec or needs
  unavailable data. A skip must not make the overall result look like a clean
  validation of that property.
- `PASS`: a completed check with no finding.

The overall audio structure status is the worst applicable result (`FAIL`,
then `WARN`, then `PASS`); skipped checks do not worsen it. The report is
included in the Content Check snapshot and AI diagnosis facts with the same
status distinctions as the existing structure results. It does not change the
analysis-case schema or add new CLI behavior in this phase.

## Format checks

### RIFF/WAVE

Validate the RIFF/WAVE signature, declared RIFF extent, required `fmt ` and
`data` chunks, chunk headers/payload extents, odd-byte padding, and duplicate or
undersized required chunks. Check `block_align` and `byte_rate` consistency
where the codec fields permit an unambiguous calculation. A partial final
sample/block is reported as `WARN` unless the format's declared alignment makes
it definitively invalid.

### AIFF / AIFF-C

Validate FORM extent and `AIFF`/`AIFC` type, chunk extents and even-byte
padding, required `COMM` and `SSND` chunks, minimum payload sizes, and the
declared SSND offset range. Compare COMM's declared sample-frame count with
available SSND bytes only for uncompressed encodings where the relationship is
well-defined. Compressed or otherwise unsupported relationships are `SKIP` or
`WARN`, not failures.

### FLAC

Validate metadata block boundaries, required unique 34-byte STREAMINFO placement
and size, and reserved block types. Parse FLAC frames to verify frame boundaries
and header/frame CRCs without decoding samples. CRC mismatch or impossible
frame extent is `FAIL`; unsupported/resynchronization ambiguity is `WARN`.
Processing is streaming/bounded and must not load the full audio payload into
memory.

### MPEG audio / MP3

Scan past a valid ID3 prefix and optional ID3v1/APE tail, then validate
consecutive MPEG frame headers and computed frame extents. Report invalid data
between frames, incompatible version/layer/sample-rate changes where not
allowed, and a truncated final frame. Inspect Xing/Info and VBRI metadata only
when present at the codec-defined frame offsets; validate their declared frame
count/byte count against observed data as a warning when exact equivalence is
not guaranteed by padding or trailing tags. Free-format or unsupported MPEG
variants must be reported as unverified, not rejected solely for being
unsupported.

### ADTS AAC

Validate each ADTS sync/header, legal sampling-frequency index, header length,
and frame length against the remaining file. Require a coherent frame walk;
report junk gaps, invalid headers, and truncated final frames. CRC presence is
reported, but AAC payload CRC is not recomputed in this phase unless the
existing parser provides a verified implementation.

### Ogg Vorbis / Opus

Validate each Ogg page capture pattern, segment table and page extent, stored
page CRC, and per-serial page sequence continuity. Validate beginning/end
stream flags and recognized Vorbis/Opus identification headers when present.
Multiplexed logical streams are tracked independently. Unknown logical-stream
codecs retain generic page checks and mark codec-specific checks `SKIP`.

### ISO BMFF audio (M4A)

Walk parsed boxes and inspect audio tracks' `stbl` data. Validate `stsz`
sample-size table counts/ranges and `stco`/`co64` chunk offsets against file
bounds. When enough table information exists, verify chunk/sample mapping
coverage without assuming an unsupported sample-to-chunk layout. Missing or
unsupported tables are `WARN`/`SKIP`; offsets beyond EOF and malformed table
extents are `FAIL`. This check is for standalone audio containers, not video
files.

### ASF / WMA

Validate ASF top-level and child object extents, header object child count,
required File Properties / Stream Properties / Data objects, and declared file
size when present. Packet-count and data-object size relationships that cannot
be verified without interpreting packet payloads remain `WARN` or `SKIP`.

## Architecture

Add an `AudioStructureChecker` under parser integrity, separate from the
existing audio decode inspector. It receives the file and parsed root/tree (or
opens a bounded `ByteReader` where raw sequential scanning is required),
dispatches by detected container/codec, and returns a report of integrity
items. Format-specific logic should be factored into focused helpers rather
than expanding the UI or generic parser walkers. Reuse existing parser data
where it is reliable, but validate raw byte boundaries independently where the
existing walker truncates or recovers malformed input.

Wire the result into `contentStructureReport` for `MediaType.AUDIO` and the
existing Structure tab, preserving image behavior and parser-warning behavior
for video, raw-pixel, and unknown files. Do not run an external decoder from
this structural checker; phase 2 remains responsible for decode diagnostics.

## Safety and resource limits

- All offset/length arithmetic must be overflow-safe and checked against file
  length before reads.
- Avoid unbounded allocations from declared counts or lengths; cap tables and
  report when a cap prevents complete verification.
- Stream CRC/frame scans in bounded buffers.
- Respect coroutine cancellation if inspection is performed off the UI thread.
- Avoid copying sensitive full paths into findings; findings use offsets and
  generic format details.

## Testing

For every format, add focused unit tests for a valid minimal/example file and
mutations for truncation or invalid size/bounds. Add format-specific tests for
CRC mismatch (FLAC and Ogg), MP3 discontinuity/VBR metadata, ADTS invalid frame
length, M4A chunk offsets outside the file, and ASF object bounds. Include
overflow and large-declared-count cases to prove bounded behavior. Add dispatch
and Content Check integration tests to confirm audio files receive the new
report, unsupported formats do not appear falsely clean, and existing image
and video structure behavior remains unchanged.

## Out of scope

- Audio decoding and stream metadata comparison (phase 2)
- Repairing or rewriting damaged files
- Full semantic validation of every codec payload
- Audio tracks embedded in video containers
- New command-line flags or analysis-case schema changes
