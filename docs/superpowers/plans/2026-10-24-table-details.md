# Fixed-width sample table details
Approved metadata coverage follow-up. FixedWidthTableDecoder currently omits version/flags/count and interprets ctts v1 offsets as unsigned. Expose headers on all registered fixed-width tables; use existing SIGNED_LAST_32 encoding only for ctts v1. Preserve v0 unsigned values and lazy row reads. Unknown versions/flags produce warnings without fabricating a table; bound box/file ranges before reads.

Real fixture BEFORE parser code: `ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=size=64x64:rate=5 -t 1 -c:v libx264 -bf 2 -pix_fmt yuv420p -movflags +negative_cts_offsets app/src/test/resources/detail-parsing/signed-ctts.mp4`. Default muxer fixture remains unchanged. Independent ctts-bytes.txt: offset4917, FullBox v1, entry_count4, rows (1,0),(1,4096),(2,-2048),(1,0). v0 must preserve same last bits as4294965248.

- [x] Generate actual fixture and independent bytes before implementation.
- [x] Red tests for signed actual row, v0 unsigned compatibility, count/source header fields, unknown versions, file bounds.
- [x] Implement bounded header decode and version-dependent row encoding.
- [x] Focused/full regression, independent review, commit and local merge.

Validation: new tests failed before implementation, then focused tests passed. Whole-branch :app:test: {'tests': 2570, 'failures': 0, 'errors': 0, 'skipped': 1}. Independent read-only review found no introduced issues.

Merged into local main on 2026-10-24 after whole-branch regression (2570 tests, 0 failures, 1 skipped). Remote push not performed.
