# Implementation Plan: MV-HEVC Spatial Video Analysis

**Date:** 2026-10-13  
**Status:** In Progress (YOLO Mode)  
**Spec:** `docs/superpowers/specs/2026-10-13-mv-hevc-spatial-video-design.md`

---

## Overview

본 계획은 Apple Spatial Video (Apple Vision Pro, iPhone 15 Pro/16 Pro) 및 ISO-BMFF MV-HEVC 공간 비디오의 박스 구조, Apple 공간 메타데이터, 그리고 멀티레이어 HEVC 비트스트림 NAL을 정밀 분석하는 구현 단계들을 정의합니다.

- **Task 1**: ISO-BMFF Spatial Video 박스 디코더 (`lhvC`, `eyes`, `hero`, `vwdi`, `st3d`) 구현 및 단위 테스트
- **Task 2**: `SpatialVideoAnalyzer.kt` NAL 레이어 분석 엔진 및 이상 검출 규칙 구현 및 단위 테스트
- **Task 3**: `SpatialVideoAnalysisWindow.kt` 분석 창 구현, `Main.kt` Analyze 메뉴 연동 및 `I18n.kt` 번역
- **Task 4**: 전체 빌드, 테스트 검증 및 `main` 머지 & 푸시

---

## Task 1: ISO-BMFF Spatial Video Box Decoders

### 목적
- `lhvC`, `eyes`, `hero`, `vwdi`, `st3d` 박스를 파싱하여 박스 트리 및 필드 정보로 노출합니다.
- `BoxRegistry`에 등록하여 컨테이너 파싱 시 자동 인식되도록 합니다.

### 대상 파일
- 생성: `app/src/main/kotlin/com/multiviewer/parser/spatial/SpatialBoxDecoders.kt`
- 수정: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- 생성: `app/src/test/kotlin/com/multiviewer/parser/spatial/SpatialBoxesTest.kt`

---

## Task 2: Spatial Video NAL Layer Analyzer Engine

### 목적
- 비디오 샘플을 순회하며 NAL 단위로 `nuh_layer_id` (Base Layer 0 vs Dependent Layer 1)를 집계하고, 프레임 수 대칭성 및 레이어별 용량/비트레이트 비를 산출합니다.
- Apple QuickTime spatial 메타데이터(`eye-bidi-baseline`, `horizontal-field-of-view`, `disparity-adjustment`)를 결합하여 종합 `SpatialVideoReport`를 생성합니다.

### 대상 파일
- 생성: `app/src/main/kotlin/com/multiviewer/parser/spatial/SpatialVideoAnalyzer.kt`
- 생성: `app/src/test/kotlin/com/multiviewer/parser/spatial/SpatialVideoAnalyzerTest.kt`

---

## Task 3: Spatial Video Analysis Window & UI Integration

### 목적
- 사용자가 Analyze 메뉴에서 "Spatial Video (MV-HEVC) 공간 비디오 분석"을 열어 양안 대조 통계, 3D 스테레오 무결성, 레이어별 비트 분배 차트, 프레임 목록 및 CSV 내보내기를 인터랙티브하게 확인하도록 합니다.

### 대상 파일
- 생성: `app/src/main/kotlin/com/multiviewer/ui/SpatialVideoAnalysisWindow.kt`
- 수정: `app/src/main/kotlin/com/multiviewer/ui/I18n.kt`
- 수정: `app/src/main/kotlin/com/multiviewer/Main.kt`

---

## Task 4: Full Validation & Merge

### 목적
- 전체 단위 테스트 및 빌드 검증 (`./gradlew :app:test`)
- `feature/mv-hevc-spatial-video`를 `main`으로 fast-forward 머지 및 푸시
