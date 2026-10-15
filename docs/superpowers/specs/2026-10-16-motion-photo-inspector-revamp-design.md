# Motion Photo Inspector Revamp Design

**Date:** 2026-10-16  
**Status:** Approved  

## 1. Problem & Objectives

Currently, clicking **모션포토 → 모션포토 검사 (Motion Photo Check)** opens the generic `ContentCheckWindow` with the `ContentTab.MOTION_PHOTO` tab. This presents several UX and technical limitations for multimedia framework and motion-photo engineers:
1. **Cluttered UI**: Generic content-check tabs ("구조", "규격 적합성", "디코딩", "게인맵") and large buttons ("검사 시작", "분석 케이스 저장", "JSON 복사", "AI 진단") confuse the user. Long explanatory paragraphs add noise.
2. **Missing Specific Checks**:
   - Main still image specification checks (JPEG/HEIC marker/box integrity, Exif dimensions vs actual bitstream dimensions, truncation) are not unified into the motion-photo assessment.
   - Non-motion SEF entries across the file are not thoroughly validated for overlaps, gaps, out-of-bounds offsets, or marker corruption.
   - Embedded video container checks lack detail on `moov` fast-start position, and audio/video duration discrepancy (A/V duration delta) is not calculated to catch truncated audio/video tracks.
3. **Flat Presentation**: Findings are rendered in a flat generic table rather than structured by the 5 forensic inspection domains engineers care about.

## 2. Proposed Architecture & UI

### 2.1 Dedicated Window: `MotionPhotoInspectorWindow`
- Opens immediately from `모션포토 → 모션포토 검사` (`motionPhotoInspectorWindowOpen = true`).
- Pinned to the active tab (`tab: TabState`).
- Runs inspection automatically in `Dispatchers.IO` upon opening (with a slim indeterminate progress indicator while running).

### 2.2 Compact Toolbar & Header
- **Title**: `모션포토 검사 (Motion Photo Inspector) — <file.name>`
- **Top Row**:
  - File format badge: e.g. `Samsung SEF (JPEG)` / `Google XMP V2 (JPEG)` / `Samsung HEIC (mpvd)`
  - Overall status badge: `PASS` (Neon Green), `WARN` (Neon Yellow/Orange), `FAIL` (Neon Red)
  - Compact Action Buttons (height 30dp `AssistChip`):
    - `[ ⟳ 재검사 / Re-check ]`
    - `[ 📋 JSON 복사 / Copy JSON ]`
    - `[ 🎬 동영상 재생 / Play Video ]` (triggers video playback / popup)
- **Zero Fluff**: No lengthy introductory text. Only concrete facts, values, and status badges.

### 2.3 Five Core Inspection Categories (Accordion Cards)

1. **메인 정지 이미지 규격 (Still Image Specification)**:
   - Container/Marker validation: JPEG (`SOI`, `APP1(Exif)`, `APP1(XMP)`, `SOF`, `SOS`, `EOI`) or HEIC (`ftyp`, `meta`, `iinf`, `iloc`, `idat`, `pitm`).
   - Resolution cross-check: Exif metadata dimensions vs decoded frame dimensions (`SOF0`/`ispe`).
   - Image stream integrity & truncation check.

2. **모션포토 포맷 & Primary XMP 유효성 (Format & Metadata)**:
   - Detected schema: Google V2 Container:Directory / Google V1 MicroVideo / Samsung SEF / HEIC mpvd.
   - XMP syntax & schema compliance.
   - Byte-level offset & length validation: Declared `Item:Length` or `MicroVideoOffset` vs actual `ftyp` start offset (`root.size - declaredLength`).
   - MIME type match (`video/mp4` vs actual `ftyp` brand).
   - `Item:Padding` and `PresentationTimestampUs` bounds check.

3. **SEF 무결성 및 전체 엔트리 검증 (SEF & MotionPhoto_Data)**:
   - SEF trailer (`SEFT`) & sub-header (`SEFH`) magic bytes and structure.
   - Declared entry count vs parsed directory entries.
   - `MotionPhoto_Data` entry presence, offset, length, and payload MP4 boundary check.
   - Sub-keys: `MotionPhoto_AutoPlay`, `MotionPhoto_Version` validity.
   - **All non-motion SEF entries in file**: Marker `0x53454654`, in-bounds boundaries, overlap detection, orphan gap detection.

4. **임베디드 동영상 컨테이너 & 코덱 (Embedded MP4 & Codec)**:
   - MP4 boxes: `ftyp`, `moov`, `mvhd`, `trak` (video/audio), `mdat` hierarchy.
   - `moov` position (fast-start vs trailer).
   - Video track: Codec (H.264/HEVC), resolution, FPS, duration.
   - Audio track: Codec (AAC), sample rate, channels, duration (or note "No Audio").
   - Full software decoding pass with ffmpeg error log capture.

5. **A/V 길이 편차 & 셔터 타이밍 정합성 (A/V Sync & Timing)**:
   - **A/V Duration Delta**: Video duration vs Audio duration delta calculation (e.g. >100ms delta surfaces WARN).
   - **Shutter Presentation Timestamp**: Check that `PresentationTimestampUs` is within video bounds `[0, videoDuration]`.
   - **Frame Match**: Pinpoint exact video frame number and PTS corresponding to the still shutter capture.
   - Timestamp monotonicity and frame drop / jitter detection.

### 2.4 Accordion Cards Interaction
- 5 cards with status badge (`PASS` / `WARN` / `FAIL`), pass ratio (`5/5 passed`), and toggle chevron.
- Cards with any `WARN` or `FAIL` are **expanded by default** so defects are immediately visible. All-pass cards are collapsed for minimal visual clutter.
- Items with byte offsets support clicking to highlight the byte range in the main Hex viewer (`tab.parameterSetHighlightRange`).

## 3. Verification Plan
- Unit tests covering:
  - 5-category evaluation engine (`MotionPhotoInspectorEngine`).
  - Audio/Video duration delta calculation.
  - All-SEF-entries validation (overlaps, gaps, boundaries).
  - Main image specification & Exif dimension cross-check.
- Desktop verification: Run `./gradlew :app:run` and inspect motion photo files (JPEG SEF, JPEG Google XMP, HEIC mpvd).
