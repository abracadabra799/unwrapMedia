# HEVC configuration details
Approved HEVC detail coverage: expose omitted hvcC fixed-header fields (profile space/tier, compatibility and constraint masks, spatial segmentation, parallelism, chroma format, luma/chroma bit depth, average rate, constant rate, temporal layers/nesting). Retain existing first eight fields and summary. Every derived value points at original bytes. Invalid file ranges warn; truncated NAL entries must not silently count an array as complete. No compressed payload analysis or new dependency.

Real fixtures BEFORE code: hevc-conformance/default-hev1.mp4 hvcC payload4782: 0101600000009000000000001ef000fcfdf8f800000f04; video-frame/hdr10.mp4 payload25135: 0102200000009000000000001ef000fcfdfafa00000f04. Independently dumped in hevc-config-bytes.txt. Both are chroma_format_idc1, tier0, num_temporal_layers1, temporal_id_nested1; default is8-bit, HDR10 is10-bit. Existing HvcCBoxDecoderTest also documents the stored layout.

- [x] Verify actual default and HDR bytes.
- [x] Red tests for complete configuration values/source masks, nonzero bitfields, truncated NAL and file range.
- [x] Add bounded fixed fields, retain existing order and summary, warn incomplete arrays.
- [x] Focused/full tests and independent review; commit/local main merge.

Validation: new tests failed before implementation, then focused tests passed. Whole-branch :app:test: {'tests': 2570, 'failures': 0, 'errors': 0, 'skipped': 1}. Independent read-only review found no introduced issues.

Merged into local main on 2026-10-24 after whole-branch regression (2570 tests, 0 failures, 1 skipped). Remote push not performed.
