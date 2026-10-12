# Implementation Plan: MV/QP Overlay for HEVC and AV1

**Date:** 2026-10-13  
**Status:** In Progress (YOLO Mode)  
**Spec:** `docs/superpowers/specs/2026-10-13-hevc-av1-mv-qp-overlay-design.md`

---

## Overview

본 계획은 H.264에 한정되어 있던 모션 벡터 및 QP 시각화 기능을 HEVC 및 AV1으로 확장하는 세부 TDD 단계를 정의합니다.

- **Task 1**: HEVC PPS `initQpMinus26` 필드 노출 및 `HevcSliceParser.kt` 슬라이스 헤더 QP 파서 구현
- **Task 2**: `CodecViewFrameDecoder.kt`의 `codecViewSupportedFor` 및 CLI 아규먼트 확장
- **Task 3**: `CodecViewPreviewWindow.kt` UI에 코덱별(HEVC/AV1) QP 및 블록 정보 카드 렌더링
- **Task 4**: 전체 테스트 통과 및 `main` 병합

---

## Task 1: HEVC Slice Header QP Parser

### 목적
- HEVC VCL NAL(0..9, 16..21)의 슬라이스 헤더를 파싱하여 `slice_type`, `slice_pic_parameter_set_id`, `slice_qp_delta`를 추출하고, PPS의 `init_qp_minus26`와 합산하여 정확한 슬라이스 QP를 산출합니다.

### 대상 파일
- 수정: `app/src/main/kotlin/com/multiviewer/parser/HevcParameterSets.kt` (`HevcPps`에 `initQpMinus26: Int` 추가)
- 생성: `app/src/main/kotlin/com/multiviewer/parser/HevcSliceParser.kt`
- 생성: `app/src/test/kotlin/com/multiviewer/parser/HevcSliceParserTest.kt`

---

## Task 2: CodecViewFrameDecoder Extension

### 목적
- `codecViewSupportedFor`에서 `hevc` (MOTION_VECTORS, QP_HEATMAP) 및 `av1` (QP_HEATMAP)을 지원하도록 확장합니다.
- `buildCodecViewFfmpegArgs`에서 HEVC의 경우 block/qp 필터 아규먼트를 적절히 구성합니다.

### 대상 파일
- 수정: `app/src/main/kotlin/com/multiviewer/ui/CodecViewFrameDecoder.kt`
- 수정: `app/src/test/kotlin/com/multiviewer/ui/CodecViewFrameDecoderTest.kt` (신규 생성 또는 기존 테스트 확장)

---

## Task 3: CodecViewPreviewWindow UI Extension

### 목적
- `CodecViewPreviewWindow.kt`에서 현재 선택된 프레임의 비트스트림 QP 정보를 표시합니다:
  - HEVC: `Slice QP: XX (Delta: +X, Type: I/P/B)`
  - AV1: `Base Q Index: XX / 255 (Tile: XxY)`
  - H.264: `Macroblock QP Heatmap`

### 대상 파일
- 수정: `app/src/main/kotlin/com/multiviewer/ui/CodecViewPreviewWindow.kt`

---

## Task 4: Full Validation & Merge

### 목적
- `./gradlew :app:test` 전체 테스트 슈트 통과 확인.
- `main` 브랜치 병합 및 푸시.
