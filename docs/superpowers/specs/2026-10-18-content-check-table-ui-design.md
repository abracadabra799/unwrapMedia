# Content Check Table UI & Terminology Redesign Spec

## Goal

Redesign the Content Check window (`ContentCheckWindow.kt`) to replace the cluttered card-based checklists and ambiguous top buttons with a unified, professional **top-bottom table dashboard**:
1. **Terminology alignment with media engineering standards**:
   - Replace "규격 검사 (Conformance checks)" with **"포맷 및 비트스트림 검증"** (*Format & Bitstream Validation*).
   - Replace "동작 진단 (Operation checks)" with **"디코딩 검증"** (*Decode Verification*).
   - Retain "확장 검사 (Additional checks)" for conditional motion photo and gain map inspections.
2. **First-class Container Conformance row & remove ambiguous `[규격 결과]` button**:
   - Promote **컨테이너 규격** (*Container Conformance*) to a distinct first-class row in the overview table alongside **파일 구조 및 무결성** (*File Structure & Integrity*) and **코덱 비트스트림** (*Codec Bitstream*).
   - Remove the confusing floating `[규격 결과]` button from the detail header. Selecting the container conformance row directly opens its conformance findings table.
3. **Top-Bottom Split Table Dashboard Layout**:
   - **Top pane (~220dp, full 1040dp width)**: Comprehensive **Check Overview Table** (표 구조):
     - Columns: `구분 (Category)` | `검사 항목 (Check Item)` | `대상 / 포맷 (Target / Format)` | `상태 (Status)` | `결과 요약 (Verdict / Summary)` | `실행 (Action)`
     - Clicking a row highlights it and loads its detail findings in the bottom pane.
     - Visual badges for status (`완료`, `검사 중`, `대기`, `미실행`, `미지원`, `해당 없음`) and verdict (`통과`, `경고 N건`, `오류 N건`, `일부 미검증`).
   - **Bottom pane (remaining height, full width)**: **Detail Findings Table** matching the selected row:
     - Container Conformance: tabular view of evaluated normative and structural rules with rule ID, source/clause, scope, details, and Hex jump buttons.
     - File Structure & Integrity: interactive box/chunk tree and integrity findings.
     - Codec Bitstream: `ContentFindingTable` with status filter chips (All / Issues / Unverified) and Hex jump buttons.
     - Video Decode: clean sub-tab toggle between `프레임 디코딩 로그 표` and `비디오 패킷 목록 표`.
     - Audio Decode / Motion Photo / Gain Map: consistent tabular findings views.

---

## Architectural & UX Changes

### 1. Stage Inventory & Categorization

In `ContentCheckStage.kt`, `CheckStage` enum and labels:
- `CheckStage.CONTAINER_CONFORMANCE` (or dedicated mapping for container rules):
  - Category: `포맷 및 비트스트림 검증` (Format & Bitstream Validation)
  - Label: `컨테이너 규격` / `Container Conformance`
  - Description: `컨테이너 포맷 규격(ISO-BMFF, EXIF 등) 필수 박스 및 구조 제약 조건을 검증합니다.` / `Validates container format specification requirements (ISO-BMFF, EXIF, etc.).`
- `CheckStage.STRUCTURE`:
  - Category: `포맷 및 비트스트림 검증`
  - Label: `파일 구조 및 무결성` / `File Structure & Integrity`
  - Description: `박스·청크 배치, 오프셋 무결성 및 파서 경고를 검사합니다.` / `Checks box and chunk layout, offset integrity, and parser warnings.`
- `CheckStage.CODEC`:
  - Category: `포맷 및 비트스트림 검증`
  - Label: `코덱 비트스트림` / `Codec Bitstream`
  - Description: `코덱 구문, 값 범위, NAL 헤더 및 파라미터 간 참조를 검사합니다.` / `Checks codec syntax, value ranges, NAL headers, and parameter references.`
- `CheckStage.VISUAL_DECODE`:
  - Category: `디코딩 검증` (Decode Verification)
  - Label: `영상·이미지 디코딩` / `Video or image decode`
  - Description: `압축 데이터를 실제로 디코딩하고 오류를 확인합니다.` / `Decodes compressed data and checks for errors.`
- `CheckStage.AUDIO_DECODE`:
  - Category: `디코딩 검증`
  - Label: `오디오 디코딩` / `Audio decode`
  - Description: `오디오 트랙을 디코딩하고 오류를 확인합니다.` / `Decodes audio tracks and checks for errors.`
- `CheckStage.MOTION_PHOTO`:
  - Category: `확장 검사` (Additional Checks)
  - Label: `모션포토` / `Motion photo`
  - Description: `정지 이미지와 포함된 영상의 연결·시점을 확인합니다.` / `Checks the link and timing between the still image and embedded video.`
- `CheckStage.GAINMAP`:
  - Category: `확장 검사`
  - Label: `게인맵` / `Gain map`
  - Description: `게인맵 데이터와 메타데이터의 구조·값을 확인합니다.` / `Checks gain-map data and metadata structure and values.`

### 2. Overview Table Layout (Top Pane)

The top overview table replaces the stacked card column:
- Rendered with `AppColors.Panel` header and bordered rows.
- Selected row styled with `AppColors.SurfaceSelected` / `AppColors.NeonBlue` accent left border.
- Each row contains:
  1. `Category`: Category cell with subtle grouping line or badge.
  2. `Item`: Stage name (e.g. `컨테이너 규격`, `코덱 비트스트림`).
  3. `Target`: Detected format or track summary (e.g. `ISO-BMFF (mp42)`, `HEVC (hev1) #1`, `1080p 60fps`, `AAC 48kHz`).
  4. `Status`: Color-coded status badge (`완료` NeonGreen, `검사 중` NeonYellow with miniature progress, `미실행` TextSecondary, `미지원`/`해당 없음` TextMuted).
  5. `Verdict`: Summary verdict (`규격 통과`, `정상`, `오류 N건`, `일부 미검증`).
  6. `Action`: Action button `[검사]` / `[재검사]`, disabled while running or if unavailable.
- Clicking any cell in the row sets `selectedStage` and switches the bottom pane.

### 3. Detail Findings Pane (Bottom Pane)

- **Header Bar**:
  - Left: `[Selected Stage Name]` + item count + format summary.
  - Middle: For `VISUAL_DECODE`, sub-tabs `[ 프레임 디코딩 결과 | 비디오 패킷 목록 ]`.
  - Right: Finding filters `[ 전체 (All) | 문제만 (Issues) | 미검증 (Unverified) ]`.
- **Table View**:
  - `ContentFindingTable` component used across all findings tabs for uniform forensic experience.
  - Container Conformance converted from free-form text cards into `ContentFindingTable` rows with stable IDs, standard sources, observations, and Hex offsets.

---

## Technical Constraints & Invariants

- **Bilingual KO/EN**: All labels, tooltips, and table headers must use `I18n` or `label(korean, english)` helper.
- **Material3 Only**: Use `androidx.compose.material3.*` (`Text`, `Button`, `FilterChip`).
- **No False PASS**: Unread, unsupported, or incomplete stages must never report PASS.
- **Data & Snapshot Compatibility**: `ContentCheckSnapshot`, `contentCheckWorkflowJson`, and AI diagnosis prompt remain 100% backward compatible.
- **Tests**: Comprehensive unit and integration tests for table data mapping, selection state, and presentation.
