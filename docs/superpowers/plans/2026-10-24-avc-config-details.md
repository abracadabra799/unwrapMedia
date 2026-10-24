# AVC configuration extension details
Continue approved AVC coverage. Display existing High Profile avcC extension (chroma_format, bit_depth_luma/chroma_minus8 and derived bit depths, num_sps_ext and parsed count). Preserve first seven fields and summary. Only inspect extension after complete SPS/PPS lists, for known extended profiles; absent extension remains compatible with older records. Truncated extension/extension NAL warns. File bounds precede reads.

Actual fixture signed-ctts.mp4 generated with libx264 before this parser change; independent avc-config-bytes.txt has avcC offset4784 payload4792, profile100, ending fdf8f800 = chroma1, 8-bit luma/chroma, zero SPS extension entries. Cross-check FFmpeg official source https://raw.githubusercontent.com/FFmpeg/FFmpeg/master/libavformat/avc.c ff_isom_write_avcc (accessed 2026-10-24).

- [x] Dump actual config before code.
- [x] Red tests for actual extension values/offset, nonzero depths, truncated extension/NAL and invalid file bounds.
- [x] Implement appended extension fields and bounded array reads.
- [x] Focused/full tests, independent review, commit/local main merge.

Validation: new tests failed before implementation, then focused tests passed. Whole-branch :app:test: {'tests': 2570, 'failures': 0, 'errors': 0, 'skipped': 1}. Independent read-only review found no introduced issues.

Merged into local main on 2026-10-24 after whole-branch regression (2570 tests, 0 failures, 1 skipped). Remote push not performed.
