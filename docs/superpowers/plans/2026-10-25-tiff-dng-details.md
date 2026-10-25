# TIFF / DNG Detailed Properties and Boundary Handling Implementation Plan

**Spec:** ../specs/2026-10-25-tiff-dng-details-design.md

## Task 1: Real tool fixture generation and byte dump verification
- [x] Create `docs/validation/tiff-dng-fixtures/generate.py` using Python Pillow 11.3.0 to produce LE and BE TIFF/DNG metadata fixtures containing:
  - DNGVersion (50706) = 1.4.0.0, DNGBackwardVersion (50707) = 1.1.0.0
  - CFARepeatPatternDim (33421) = (2, 2)
  - CFAPattern (33422) = (0, 1, 1, 2)
  - CFAPlaneColor (50710) = (0, 1, 2)
  - CFALayout (50711) = 1 (Rectangular)
  - CalibrationIlluminant1 (50778) = 17 (Standard light A), CalibrationIlluminant2 (50779) = 21 (D65)
  - ColorimetricReference (50879) = 0 (sRGB)
  - ProfileEmbedPolicy (50941) = 0 (allowCopying)
  - OpcodeList1 (51008) = 4-byte raw payload
- [x] Create `docs/validation/tiff-dng-fixtures/dump.py` and record independent struct unpacked bytes to `docs/validation/tiff-dng-fixtures/verified-bytes.txt`.
- [x] Save output files to `app/src/test/resources/tiff-dng/{le,be}.tif` alongside a default fixture.

## Task 2: Write failing unit and boundary tests (RED)
- [x] Create `app/src/test/kotlin/com/multiviewer/parser/TiffDngDetailsTest.kt`.
- [x] Assert real fixtures: DNGVersion ("1.4.0.0"), DNGBackwardVersion ("1.1.0.0"), CFARepeatPatternDim ("2 x 2"), CFAPlaneColor ("0, 1, 2 (Red, Green, Blue)"), CFAPattern ("0, 1, 1, 2 (Red, Green, Green, Blue)"), CFALayout ("Rectangular"), CalibrationIlluminant1 ("Standard light A"), CalibrationIlluminant2 ("D65"), ColorimetricReference ("sRGB"), ProfileEmbedPolicy ("allowCopying"), OpcodeList1 ("Tag 0xc740" -> "OpcodeList1", UNINTERPRETED).
- [x] Assert single-byte EXIF enum tags: FileSource (0xA300) -> "Digital Camera", SceneType (0xA301) -> "Directly photographed".
- [x] Boundary tests:
  - Empty tag (`count == 0L`) -> "빈 값 / Empty value" (`UNPARSABLE`).
  - Capped array: SHORT/LONG/BYTE arrays with > 256 values report `… (256/$count 표시 / shown; 나머지 미해석 / remainder uninterpreted)` and `UNINTERPRETED`.
  - Unknown fieldType (e.g. 13 or 99) -> reports "알 수 없는 자료형 / Unknown type" (`UNINTERPRETED`) without indexing garbage.
  - BigTIFF magic (0x002B) -> root node warning "BigTIFF format is currently unsupported" without crashing.
  - SubIFD recursion cap (64 SubIFDs max).
- [x] Update existing `ExifDecoderTest.kt:270` test expectation from `"01 01 04 00"` to `"1.1.4.0"` (or dedicated DNG version test).
- [x] Run test suite and observe RED (7 of 7 tests failed).

## Task 3: Implementation in ExifDecoder.kt (GREEN)
- [x] In `ExifDecoder.kt`:
  - Add OpcodeList tags to `TAG_NAMES_TIFF`: 0xC740 ("OpcodeList1"), 0xC741 ("OpcodeList2"), 0xC74E ("OpcodeList3").
  - Add enum translations to `TAG_VALUE_LABELS_TIFF`: CFALayout (0xC617), CalibrationIlluminant1 (0xC65A), CalibrationIlluminant2 (0xC65B), ColorimetricReference (0xC6BF), ProfileEmbedPolicy (0xC6FD).
  - Update `interpretedDisplay`: add fieldType 1 and 7 handling for 1-byte enum tags.
  - Add specialized decoding for DNGVersion / DNGBackwardVersion (0xC612, 0xC613), CFARepeatPatternDim (0x828D), CFAPattern (0x828E), CFAPlaneColor (0xC616).
  - Update `formatTiffValue`:
    - Handle Type 1 (BYTE) as unsigned integers (0..255).
    - Cap numeric arrays (1, 3, 4, 5, 8, 9, 10) at 256 elements with bilingual omission notice.
    - Cap Type 2 (ASCII) at 4096 bytes.
  - Update `decodeIfd`:
    - Handle `count == 0L` with "빈 값 / Empty value" (`UNPARSABLE`).
    - If `fieldType !in TIFF_TYPE_SIZES`, emit unknown type field without assuming size 1.
    - If `count > 256` in standard arrays, set `ValueInterpretation.UNINTERPRETED`.
    - In `TAG_SUB_IFDS`, cap subIFD count at 64.
    - In `readUIntArray`, cap reads at 1024 elements.
  - In `decodeTiff`:
    - Check magic number at offset 2..3; if 43 (0x002B), return node with BigTIFF unsupported warning.
- [x] Run focused tests and verify GREEN.

## Task 4: Regression and integration
- [x] Run `./gradlew :app:test` (all 2,922 tests passed, 0 failures, 1 skipped).
- [x] Independent code review and check for regressions.
- [x] Update handoff/plan documents.
- [x] Merge feature branch to local main (no push without explicit user request).

## Execution Evidence
- RED: `TiffDngDetailsTest` 7 tests executed, 7 failed. Baseline failures for unparsed DNG/CFA tags, uninterpreted enum tags, missing empty-tag handling, uncapped arrays, and BigTIFF fall-through.
- GREEN: Focused `TiffDngDetailsTest`, `ExifDecoderTest`, `TiffNumericDetailsTest`, and `TiffIntegrityTest` passed cleanly.
- Full suite: `./gradlew :app:test` executed 2,922 tests with 0 failures, 0 errors, 1 skipped (43s). `git diff --check` clean.
