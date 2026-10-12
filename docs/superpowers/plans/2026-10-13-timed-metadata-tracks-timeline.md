# Implementation Plan: Timed Metadata Tracks on Timeline

**Date:** 2026-10-13  
**Status:** In Progress (YOLO Mode)  
**Spec:** `docs/superpowers/specs/2026-10-13-timed-metadata-tracks-timeline-design.md`

---

## Overview

본 계획은 비디오 파일 내 Android `camm`, GoPro `gpmd`, Apple `mebx` 등의 timed metadata 트랙을 추출하여 타임라인 기반 3축 센서(Gyro, Accel, GPS) 시계열 파형 및 정밀 수치 분석을 지원하는 개발 단계를 정의합니다.

- **Task 1**: `TimedMetadataTrackReader.kt` 구현 및 단위 테스트 (`TimedMetadataTrackReaderTest.kt`)
- **Task 2**: `SensorTimelineGraph.kt` Canvas 기반 3축 센서 파형 컴포넌트 구현
- **Task 3**: `TimedMetadataTimelineWindow.kt` 분석 창 구현 및 `Main.kt` Analyze 메뉴 연동
- **Task 4**: 전체 빌드 검증 및 `main` 머지 & 푸시

---

## Task 1: Timed Metadata Track Parser Engine

### 목적
- `camm`, `gpmd`, `mebx` 트랙의 샘플 테이블(`stts`, `stsz`, `stco`/`co64`)을 읽어 각 샘플의 바이너리를 디코딩하고 타임스탬프가 매핑된 3축 `SensorSamplePoint` 목록을 산출합니다.

### 대상 파일
- 생성: `app/src/main/kotlin/com/multiviewer/parser/metadata/TimedMetadataTrackReader.kt`
- 생성: `app/src/test/kotlin/com/multiviewer/parser/metadata/TimedMetadataTrackReaderTest.kt`

---

## Task 2: Sensor Timeline Graph Component

### 목적
- 비디오 재생 시간(X축)에 동기화된 Gyro/Accel 3축 파형(X, Y, Z)을 렌더링하고, 호버 툴팁 및 특정 시간대 선택 기능을 제공합니다.

### 대상 파일
- 생성: `app/src/main/kotlin/com/multiviewer/ui/SensorTimelineGraph.kt`

---

## Task 3: Timed Metadata Timeline Window & UI Integration

### 목적
- 사용자가 Analyze 메뉴에서 "Timed Metadata 센서 타임라인"을 열어 센서 종류(Gyro/Accel/GPS)를 전환하며 파형 차트, 통계 요약, 샘플 데이터 테이블 및 CSV 내보내기를 인터랙티브하게 사용할 수 있도록 구성합니다.

### 대상 파일
- 생성: `app/src/main/kotlin/com/multiviewer/ui/TimedMetadataTimelineWindow.kt`
- 수정: `app/src/main/kotlin/com/multiviewer/ui/I18n.kt`
- 수정: `app/src/main/kotlin/com/multiviewer/Main.kt`

---

## Task 4: Full Validation & Merge

### 목적
- 전체 빌드 및 테스트 검증 (`./gradlew :app:test`)
- `feature/timed-metadata-tracks`를 `main`으로 fast-forward 머지 및 푸시
