# Explicit uninterpreted-value indicators
User requested visible 미해석 / 파싱 불가 for values that could not be interpreted. Add ValueInterpretation(PARSED default, UNINTERPRETED, UNPARSABLE) to BoxField with source/value unchanged. Parser authors set state explicitly; never infer from arbitrary user strings or hex-looking values. Shared node-level presentation aggregates explicit field states; an opaque payload-only leaf is UNINTERPRETED, a warning-bearing leaf with no decoded properties is UNPARSABLE, valid marker/empty container or parsed numeric fields remain PARSED.

Detailed Properties shows bilingual badges beside flagged values and a selected-node notice. Keep raw values and byte highlighting. Export nondefault states in CLI JSON only, retaining default field JSON compatibility. Annotate unknown SEF binary, unknown descriptor payloads, HEIF auxiliary subtype/preview/unparsed ranges, FLAC raw application and unknown blocks, AAC invalid sample rate, TIFF out-of-bounds/raw/unknown tag fallback. Typed parsed values and reserved numeric fields must not be marked just because they look raw. Future parser units must flag new opaque/failed fields explicitly.

- [x] Red behavior tests through actual SEF binary/descriptor/fallback node JSON; verify values and ranges unchanged and ordinary fields remain unflagged.
- [x] Add state model/aggregation and JSON metadata; annotate verified fallback branches.
- [x] Add Material3 value badges and node notice in Detailed Properties.
- [x] Focused/full regression, review, commit/local main merge; no push.

Review correction: failed payloads with retained header fields initially lacked a state. Added explicit node interpretation (default PARSED) and marked structural-failure branches in AV1/APV/HEIF/FLAC/dfLa; header fields retain PARSED. Resource-limited seek tables stay UNINTERPRETED, not malformed. Added regression for short native STREAMINFO/dfLa, unterminated aux_type and missing APV frame info. Unknown TIFF tag versus known decoded width also tested. JSON test originally assumed compact whitespace while using pretty render; corrected to renderCompact.

Final edge correction: zero-byte required FLAC blocks previously bypassed minimum-size validation before common headers were appended. The zero-length STREAMINFO regression failed first; STREAMINFO/VORBIS_COMMENT/PICTURE now reach existing minimum validators, while empty padding/seektable remain legal.

Validation: focused tests and final whole-branch :app:test passed: {'tests': 2588, 'failures': 0, 'errors': 0, 'skipped': 1}. Independent read-only reviews completed; reported defects have regression tests and were resolved. UI compiled with Material3 badges; no manual GUI screenshot verification was performed.

Merged into local main on 2026-10-24. Remote push not performed.
