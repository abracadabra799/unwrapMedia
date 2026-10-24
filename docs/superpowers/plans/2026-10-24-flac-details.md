# FLAC metadata details
Continue approved FLAC coverage. Expand previously summary-only SEEKTABLE to bounded source-backed seek-point children (uint64 sample/offset as unsigned strings, u16 frame_samples, explicit placeholder flag); max4096 nodes with unparsed count/warning. Expose APPLICATION ID and bounded opaque bytes without guessed payload semantics. Append common metadata header type/last flag/declared length, preserving existing fields first. Add missing STREAMINFO MD5 to MP4 dfLa and prevent short declared blocks reading into following bytes; validate file/box bounds.

Evidence BEFORE parser code: docs/validation/audio-details-fixtures/generate_flac.py generates native FLAC/MP4 via ffmpeg; explicitly authors SEEKTABLE from actual ffprobe frame offsets and sample counts (not a claim ffmpeg emitted it). Independent verified-flac-bytes.txt: three points (0,0,4608),(4608,1260,4608),(9216,2525,2784); SEEKTABLE offset42/payload46; STREAMINFO MD5 bd486fb020ea6bfa1468407e81ecc5b9. dfLa payload3843, fullbox0, STREAMINFO length34, same MD5. Placeholder/APPLICATION/truncated records use explicit synthetic fixtures.

- [x] Generate actual audio, derive valid seek points and independently dump bytes.
- [x] Red tests for actual seek points/common headers/MD5, placeholder UInt64, truncated records and short dfLa block.
- [x] Implement bounded extra metadata parser and dfLa correction.
- [x] Focused/full regression, independent review, commit/local main merge.

Ruling: updated unknown-block test to assert raw header presence and absence of invented payload fields, because exposing common metadata headers is intentional. Original payload summary retained.

Validation: focused tests and final whole-branch :app:test passed: {'tests': 2588, 'failures': 0, 'errors': 0, 'skipped': 1}. Independent read-only reviews completed; reported defects have regression tests and were resolved. UI compiled with Material3 badges; no manual GUI screenshot verification was performed.

Merged into local main on 2026-10-24. Remote push not performed.
