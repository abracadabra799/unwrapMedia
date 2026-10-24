# JPEG Adobe property details
Continue approved JPG coverage: existing APP14 exposes only color_transform despite reading Adobe signature. Add version, flags0 and flags1 with original byte ranges, preserving color_transform as first field. Recognized truncated Adobe headers warn. Unknown APP14 remains opaque. Values remain raw numerics; no guessed flag semantics.

Real fixture generated before code via Pillow11.3.0 in docs/validation/jpeg-details-fixtures/generate.py. Independent verified-bytes.txt: APP14 offset2 size16; Adobe signature, version100, flags0=0, flags1=0, transform0. Baseline JPEG fixtures remain in place.

- [x] Generate fixture and independently dump actual payload.
- [x] Registry/walker tests: real version/source positions, nonzero flags, recognized truncated Adobe header, unknown APP14 behavior.
- [x] Implement missing fields in decodeApp14, retaining summary and transform.
- [x] Focused/full tests, review, commit/local main merge.

Validation: new tests failed before implementation, then focused tests passed. Whole-branch :app:test: {'tests': 2570, 'failures': 0, 'errors': 0, 'skipped': 1}. Independent read-only review found no introduced issues.

Merged into local main on 2026-10-24 after whole-branch regression (2570 tests, 0 failures, 1 skipped). Remote push not performed.
