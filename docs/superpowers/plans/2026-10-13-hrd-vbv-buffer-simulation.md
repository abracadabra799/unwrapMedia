# Implementation Plan: HRD / VBV Buffer Simulation

**Date:** 2026-10-13  
**Status:** In Progress (YOLO Mode)  
**Spec:** `docs/superpowers/specs/2026-10-13-hrd-vbv-buffer-simulation-design.md`

---

## Overview

본 계획은 비디오 비트스트림의 가상 디코더 버퍼(HRD / VBV) 모델을 시뮬레이션하고, 프레임별 버퍼 점유율 곡선 및 언더플로우/오버플로우 위험을 시각화하는 전체 절차를 정의합니다.

- **Task 1**: `HrdBufferSimulator.kt` 시뮬레이터 엔진 구현 및 단위 테스트 (`HrdBufferSimulatorTest.kt`)
- **Task 2**: `HrdBufferGraph.kt` Canvas 기반 점유율 곡선 차트 구현
- **Task 3**: `HrdBufferSimulationWindow.kt` 분석 창 구현 및 `Main.kt` Analyze 메뉴 연동
- **Task 4**: 전체 빌드, 테스트 및 머지

---

## Task 1: HRD Buffer Simulator Engine

### 목적
- 비디오 프레임 크기 및 DTS 타임스탬프를 기반으로 가상 디코더 버퍼 채움/제거 과정을 모델링하여 프레임별 버퍼 점유율(`occupancyPercent`) 및 언더플로우(`isUnderflow`)를 산출합니다.

### 대상 파일
- 생성: `app/src/main/kotlin/com/multiviewer/parser/hrd/HrdBufferSimulator.kt`
- 생성: `app/src/test/kotlin/com/multiviewer/parser/hrd/HrdBufferSimulatorTest.kt`

---

## Task 2: HRD Buffer Trend Graph

### 목적
- `HdrTrendGraph.kt` 패턴을 따라 Canvas 기반으로 0%~100% 버퍼 점유율 곡선, 15% 위험 기준선, 언더플로우 경고 마커, 프레임 호버 툴팁을 렌더링합니다.

### 대상 파일
- 생성: `app/src/main/kotlin/com/multiviewer/ui/HrdBufferGraph.kt`

---

## Task 3: HRD Buffer Simulation Window & Menu Integration

### 목적
- 사용자가 Analyze 메뉴에서 `HRD/VBV 버퍼 시뮬레이션`을 열어 실시간으로 비트레이트 및 버퍼 크기를 변경하며 언더플로우 발생 여부를 인터랙티브하게 검증할 수 있는 전용 창을 구성합니다.

### 대상 파일
- 생성: `app/src/main/kotlin/com/multiviewer/ui/HrdBufferSimulationWindow.kt`
- 수정: `app/src/main/kotlin/com/multiviewer/Main.kt`
- 수정: `app/src/main/kotlin/com/multiviewer/ui/I18n.kt`

---

## Task 4: Full Validation & Merge

### 목적
- `./gradlew :app:test` 전체 테스트 슈트 통과 확인.
- `main` 브랜치 병합 및 푸시.
