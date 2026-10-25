# TIFF numeric implementation plan

Spec: ../specs/2026-10-25-tiff-numeric-details-design.md

## Task 1: verified typed values
- Real Pillow fixtures plus struct dump before parser edits (complete).
- Write tests for both endian fixtures, exact values/spans/states and unknown-tag state. Add synthetic boundary tests for inline FLOAT, signed zero, NaN/infinity, truncated DOUBLE, 257 values and UInt32 count overflow without allocation. Observe RED.
- Add a bounded shared numeric field path to regular IFD and MakerNote fields for types 6/11/12. Use Float.fromBits and Double.fromBits with explicit endian byte assembly; cap256, preserve Long count and full physical source span. Unknown semantics and truncation retain explicit states.
- Run focused tests, then full suite. Review whole branch independently. Fix substantive findings with RED/GREEN. Integrate locally to main; no push.

Review focus: endian correctness, inline versus referenced data, Long/Int narrowing, negative zero/nonfinite values, unknown tag/type states, accurate offsets and truncation limits. Broader existing TIFF allocation/conformance gaps are outside this narrow unit.

## Execution evidence
- RED: TiffNumericDetailsTest 5 tests, 3 failures: old hex fallback for SBYTE/IEEE values and missing capped-array marker. Default/unknown-type and malformed-range baselines passed.
- Real fixture generation initially exposed Pillow's scalar BYTE normalization: DNGVersion tuple was truncated. Generator now passes BYTE bytes explicitly; independent dump confirms 01 04 00 00. No parser assumption based on normalized tags.
- GREEN: focused TiffNumericDetailsTest and existing ExifDecoderTest passed. Full `./gradlew :app:test`: 2870 tests, 0 failures/errors, 1 skipped (43s). `git diff --check` clean.
- Independent final review (`review_tiff_numeric`): no findings; fixture dump reproduced. No rulings or deferred minors. Broader TIFF limits/tag semantics remain planned separately.
