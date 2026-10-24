# SEF structural details
Continuation of approved all-identifiable SEF parsing. Preserve existing text/JSON, timestamp, MCC, MP4 and pointer fields; add missing SEFH header/version/count, trailer size/magic, per-directory reserved/marker/offset/size and block reserved/name_size/name/data_length. Keep existing field order for consumers. Binary data gets a bounded raw preview with explicit unparsed status, never guessed semantic meaning.

Real S26 fixtures independently verified in sef-bytes.txt: HEIC SEFH=8509091, version=107, count=11, size=144; JPG SEFH=9638360, count=10, size=132. Block name_size includes trailing NUL for some names.

- [x] Record raw fixture layout before code.
- [x] Failing tests for header and directory/source ranges, block header, raw binary preview and out-of-file declaration.
- [x] Add fields and enforce directory end before trailer, blocks before SEFH, bounded directory expansion (4096), byte previews (256).
- [ ] Focused/full tests, independent review, commit, merge local main.

Review: independent review found no introduced blocking defects; added direct directory/trailer and block/SEFH overlap regression tests. Existing whole-name/payload allocations remain a known limitation; the 256-byte binary preview limits display size, not total parsing memory.

Validation: new initial tests failed on missing fields and out-of-range EOF; focused tests and full :app:test then passed. Reviewer-requested additional boundary test passed in a subsequent focused run (4 SEF detail tests).
