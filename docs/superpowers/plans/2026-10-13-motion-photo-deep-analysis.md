# Motion Photo Deep Analysis Implementation Plan

**Date:** 2026-10-13  
**Status:** In Progress (YOLO Mode)  
**Spec:** `docs/superpowers/specs/2026-10-13-motion-photo-deep-analysis-design.md`

---

## Overview

본 계획은 모션포토의 3대 심층 분석 기능을 TDD 방식으로 구현하고, 기존 `Content Check` 창의 모션포토 리포트에 자연스럽게 통합하는 절차를 정의합니다.

- **Task 1**: Still ↔ Video Frame Matcher (`MotionPhotoFrameMatcher.kt`, `MotionPhotoFrameMatcherTest.kt`)
- **Task 2**: Apple Live Photo Pair Checker (`AppleLivePhotoPairChecker.kt`, `AppleLivePhotoPairCheckerTest.kt`)
- **Task 3**: Cross-Platform Compatibility Matrix (`MotionPhotoCompatibilityMatrix.kt`, `MotionPhotoCompatibilityMatrixTest.kt`)
- **Task 4**: Integration into MotionPhotoIntegrityAnalyzer & UI (`MotionPhotoReportContent.kt`)

---

## Task 1: Still ↔ Video Frame Matcher

### 목적
- 스틸 사진의 셔터 클릭 타임스탬프(`PresentationTimestampUs`)를 비디오 트랙의 샘플 테이블(`VideoSampleTable`)과 매칭하여 가장 가까운 비디오 프레임 인덱스, 실제 PTS(μs), 시간 오차(delta μs), I-Frame(키프레임) 여부, 정렬 판정(`FrameAlignmentVerdict`)을 산출합니다.

### 대상 파일
- 생성: `app/src/main/kotlin/com/multiviewer/parser/motionphoto/MotionPhotoFrameMatcher.kt`
- 생성: `app/src/test/kotlin/com/multiviewer/parser/motionphoto/MotionPhotoFrameMatcherTest.kt`

### 주요 기능
- `matchFrameToTimestamp(sampleTable: VideoSampleTable, targetTimestampUs: Long): FrameMatchResult`
- `extractPresentationTimestampUs(root: BoxNode): Long?` (Google XMP, Apple metadata, SEF XMP 모두 탐색)

---

## Task 2: Apple Live Photo Pair Checker

### 목적
- Apple Live Photo의 스틸 이미지(JPEG/HEIC)와 비디오(MOV) 간의 `Content Identifier` (UUID) 일치 여부, `com.apple.quicktime.live-photo.still-image-time` 범위, 촬영 시각(`CreationDate` vs `DateTimeOriginal`) 동기화 상태를 분석합니다.
- 단일 파일 모드 시 동일 디렉토리 내 페어 파일(`.mov`, `.heic`, `.jpg`) 자동 탐색을 지원합니다.

### 대상 파일
- 생성: `app/src/main/kotlin/com/multiviewer/parser/motionphoto/AppleLivePhotoPairChecker.kt`
- 생성: `app/src/test/kotlin/com/multiviewer/parser/motionphoto/AppleLivePhotoPairCheckerTest.kt`

### 주요 기능
- `extractAppleLivePhotoStillMetadata(root: BoxNode): AppleStillMetadata?`
- `extractAppleLivePhotoVideoMetadata(root: BoxNode): AppleVideoMetadata?`
- `inspectLivePhotoPair(stillFile: File?, stillRoot: BoxNode?, videoFile: File?, videoRoot: BoxNode?): LivePhotoPairReport`
- `findLivePhotoPairCandidate(sourceFile: File): File?`

---

## Task 3: Cross-Platform Compatibility Matrix

### 목적
- 파일의 구조(Samsung SEF, Google XMP v1/v2, Apple Live Photo, HEIC mpvd 등)를 분석하여 주요 플랫폼(Samsung Gallery, Google Photos, Apple Photos, Xiaomi Gallery, Web Browsers, Social Media)에서의 호환성 수준(`FULL`, `PARTIAL`, `STILL_ONLY`, `UNSUPPORTED`)을 평가하고 매트릭스로 리포트합니다.

### 대상 파일
- 생성: `app/src/main/kotlin/com/multiviewer/parser/motionphoto/MotionPhotoCompatibilityMatrix.kt`
- 생성: `app/src/test/kotlin/com/multiviewer/parser/motionphoto/MotionPhotoCompatibilityMatrixTest.kt`

### 주요 기능
- `evaluateMotionPhotoCompatibility(specs: MotionPhotoProfileSpecs): CompatibilityMatrixReport`
- `extractMotionPhotoProfileSpecs(file: File, root: BoxNode, detectedFormats: List<MotionPhotoFormat>): MotionPhotoProfileSpecs`

---

## Task 4: Integration into Analyzer & UI

### 목적
- `MotionPhotoIntegrityAnalyzer.kt`에서 `MotionPhotoFrameMatcher`, `AppleLivePhotoPairChecker`, `MotionPhotoCompatibilityMatrix`를 호출하여 심층 분석 결과(`MotionPhotoDeepAnalysis`)를 구성합니다.
- `MotionPhotoReportContent.kt`에 프레임 매칭 섹션, Live Photo 페어 섹션, 호환성 매트릭스 테이블을 Compose Material3 컴포넌트로 렌더링합니다.
- 전체 테스트 슈트(`gradlew :app:test`) 통과 확인 및 `main` 브랜치 병합.

### 대상 파일
- 수정: `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt`
- 수정: `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoReportContent.kt`
- 수정: `app/src/test/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzerTest.kt`
