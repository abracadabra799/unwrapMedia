# Design Spec: Motion Photo Deep Analysis

**Date:** 2026-10-13  
**Status:** Approved (YOLO Mode)  
**Target Version:** 1.21.0-dev  
**Scope:** 모션포토 심층 분석 (스틸-비디오 프레임 매칭, Apple Live Photo 페어 검사, 크로스 플랫폼 호환성 매트릭스)

---

## 1. Background & Goals

현재 unwrapMedia는 모션포토의 기본 추출(`MotionPhotoExtractor.kt`), SEF/XMP/mpvd 무결성 검사(`MotionPhotoIntegrityAnalyzer.kt`), C2PA 하드 바인딩 영향도 진단(`C2paCoverageMap.kt`)을 지원합니다.
그러나 갤럭시/안드로이드 멀티미디어 엔지니어와 포스틱 포렌식 분석가가 실무에서 요구하는 다음 3가지 핵심 기능이 부재합니다:

1. **Still ↔ Video Frame Match**:
   스틸 사진의 셔터 클릭 순간(`PresentationTimestampUs`)이 비디오 트랙의 어느 프레임(Sample Index / PTS / Keyframe 여부)에 정확히 매칭되는지, 셔터와 비디오 간 타임코드 오차(delta)가 얼마나 발생하는지 정밀하게 분석.
2. **Apple Live Photo Pair Inspection**:
   Apple 생태계의 Live Photo는 단일 컨테이너(HEIC mpvd)뿐만 아니라 주로 **스틸 이미지(JPEG/HEIC) + 동영상(MOV)**의 2개 파일 페어 구조를 사용합니다. 두 파일 간의 `Content Identifier` (UUID) 일치 여부, `com.apple.quicktime.live-photo.still-image-time` 유효성, `quicktime:CreationDate` ↔ EXIF `DateTimeOriginal` 동기화를 종합 진단.
3. **Cross-Platform Compatibility Matrix**:
   제조사별(Samsung, Google, Apple, Xiaomi) 모션포토 포맷 파편화로 인해 특정 기기에서 촬영한 모션포토가 타사 갤러리나 Google Photos, Apple Photos, 웹 브라우저, SNS(Instagram/X)에서 어떻게 동작하는지(완전 재생, 스틸 사진만 표시, 재생 불가/손상)를 진단하는 호환성 매트릭스 제공.

---

## 2. Core Architecture

### 2.1 Module 1: Still ↔ Video Frame Matcher (`MotionPhotoFrameMatcher.kt`)

#### 1) 타임스탬프 추출
- **Google XMP v2**: `Container:Directory` 내 `Item:Semantic="MotionPhoto"` 또는 `GCamera:MotionPhotoPresentationTimestampUs` (μs).
- **Google XMP v1 / Xiaomi**: `GCamera:MicroVideoPresentationTimestampUs` (μs).
- **Samsung SEF**: XMP 내 presentation timestamp 또는 SEF 메타데이터.
- **Apple QuickTime / Live Photo**: `com.apple.quicktime.live-photo.still-image-time` (비디오 timescale 단위 ticks 또는 μs, -1인 경우 중간/기본값).

#### 2) 정밀 PTS 매칭 알고리즘
- 비디오 샘플 테이블(`VideoSampleTable`, ISO BMFF `stts`/`ctts`/`stsz`/`stss` 기반):
  - 각 샘플의 PTS 계산:
    $$\text{ptsTicks} = \text{sample.decodeTime} + \text{sample.compositionOffset}$$
    $$\text{ptsUs} = \frac{\text{ptsTicks} \times 1,000,000}{\text{timescale}}$$
  - 목표 타임스탬프 $T_{\text{target}}$과 $|\text{ptsUs} - T_{\text{target}}|$가 최소가 되는 샘플 $S_{\text{matched}}$ 검색.
  - 시간차 계산: $\Delta_{\mu s} = \text{matchedPtsUs} - T_{\text{target}}$.
  - 키프레임 여부 판별: $S_{\text{matched}}.\text{isSync}$.

#### 3) 분석 결과 데이터 모델
```kotlin
data class FrameMatchResult(
    val presentationTimestampUs: Long,
    val matchedSampleIndex: Int,          // 0-based
    val matchedFrameNumber: Int,          // 1-based (matchedSampleIndex + 1)
    val matchedPtsUs: Long,
    val deltaUs: Long,                    // matchedPtsUs - presentationTimestampUs
    val isKeyframe: Boolean,              // IDR / I-Frame
    val videoTimescale: Long,
    val totalSamples: Int,
    val videoDurationUs: Long,
    val fps: Double,
    val relativePositionPercent: Double,  // (presentationTimestampUs / videoDurationUs) * 100
    val alignmentVerdict: FrameAlignmentVerdict,
    val warnings: List<String> = emptyList(),
)

enum class FrameAlignmentVerdict {
    EXACT_KEYFRAME,       // 오차 < 1ms 및 I-Frame에 정확히 정렬 (최적)
    ALIGNED_NON_KEYFRAME, // 오차 < 1 프레임 간격 (일반적인 정상 상태)
    MISALIGNED,           // 오차 >= 1 프레임 간격 (프레임 드롭 또는 부정확한 타임스탬프)
    OUT_OF_BOUNDS,        // 비디오 duration 범위 초과
}
```

---

### 2.2 Module 2: Apple Live Photo Pair Checker (`AppleLivePhotoPairChecker.kt`)

#### 1) 메타데이터 추출기
- **Still Image (HEIC/JPEG)**:
  - QuickTime/HEIF `meta/keys` → `com.apple.quicktime.content.identifier`
  - EXIF MakerNote Apple Tag `0x0011` (`ContentIdentifier`)
  - XMP `apple-fi:ContentIdentifier` / `http://ns.apple.com/livephoto/1.0/`
  - EXIF `DateTimeOriginal` (ISO/EXIF 날짜 문자열)
  - MakerNote `LivePhotoCoverageTime` (0x0015), `LivePhotoVideoIndex` (0x0019)
- **Video (MOV)**:
  - `moov/meta/keys` → `com.apple.quicktime.content.identifier`
  - `com.apple.quicktime.live-photo.still-image-time`
  - `com.apple.quicktime.creationdate`
  - `com.apple.quicktime.live-photo.vitality-score`

#### 2) 정합성 평가 로직
- UUID 일치 검사: 대소문자 무시, 하이픈 정규화 비교.
- 날짜/시간 정합성: EXIF `DateTimeOriginal` vs MOV `CreationDate` 간 시간차(초) 계산.
- Still Image Time 검증: 비디오 duration 내에 존재하는지 확인.
- 자동 페어 탐색: 대상 파일이 `IMG_1234.HEIC`인 경우 동일 디렉토리의 `IMG_1234.MOV` 자동 확인 지원.

#### 3) 데이터 모델
```kotlin
enum class LivePhotoPairStatus {
    MATCHED,            // 스틸과 비디오의 Content ID UUID 일치
    MISMATCHED_UUID,    // 서로 다른 Content ID
    MISSING_STILL_ID,   // 스틸 이미지에 Content ID 부재
    MISSING_VIDEO_ID,   // 동영상에 Content ID 부재
    NOT_LIVE_PHOTO,     // 둘 다 Live Photo 식별자 없음
}

data class LivePhotoPairReport(
    val status: LivePhotoPairStatus,
    val stillFile: File?,
    val videoFile: File?,
    val stillContentId: String?,
    val videoContentId: String?,
    val stillImageTimeTicks: Long?,
    val stillImageTimeUs: Long?,
    val creationDateSyncDeltaSeconds: Long?,
    val checks: List<SefCheckResult>,
)
```

---

### 2.3 Module 3: Cross-Platform Compatibility Matrix (`MotionPhotoCompatibilityMatrix.kt`)

#### 1) 대상 플랫폼 및 판정 규칙
1. **Samsung Gallery (삼성 갤러리)**
   - Fully Supported: `MotionPhoto_Data`가 포함된 정상 SEF 트레일러 또는 Google XMP v1/v2 또는 HEIC `mpvd`.
   - Partial: SEF 트레일러가 손상되었거나 XMP만 있는 구형 기기.
2. **Google Photos (구글 포토)**
   - Fully Supported: Google XMP v2 (`Container:Directory`) 또는 v1 (`GCamera:MicroVideoOffset`).
   - Unsupported / Fallback: 순수 Samsung SEF 전용(XMP 없음) 파일은 구글 포토에서 정적 사진으로만 취급됨.
3. **Apple Photos / iOS (애플 사진 앱)**
   - Fully Supported: 매칭되는 Content Identifier UUID를 가진 HEIC/JPG + MOV 페어, 또는 Live Photo 표준 HEIC `mpvd`.
   - Fallback to Still: Android 모션포토(SEF, Google XMP)는 스틸 사진으로만 표시됨.
4. **Xiaomi Gallery (샤오미 갤러리)**
   - Fully Supported: Google 레거시 XMP (`GCamera:MicroVideo="1"`, `MicroVideoOffset`) 또는 XMP v2.
5. **Web Browsers (Chrome / Safari / Edge)**
   - Fallback to Still: 모든 단일 파일 모션포토는 일반 브라우저에서 스틸 사진으로만 렌더링됨.
6. **Social Platforms (Instagram / X)**
   - Stripped to Still: 직접 업로드 시 비디오 트레일러/임베디드가 제거되거나 스틸 사진만 게시됨.

#### 2) 데이터 모델
```kotlin
enum class PlatformSupportLevel {
    FULL,            // 모션 비디오 완전 재생 및 셔터 순간 일치
    PARTIAL,         // 재생 가능하나 일부 메타데이터(셔터 시점/오디오) 미지원 또는 변환 필요
    STILL_ONLY,      // 모션 비디오 무시되고 스틸 사진만 표시됨
    UNSUPPORTED,     // 형식 오류 또는 인식 불가
}

data class PlatformCompatibilityEntry(
    val platformName: String,
    val supportLevel: PlatformSupportLevel,
    val requiredFeatures: String,
    val currentStatusText: String,
    val notes: String,
)

data class CompatibilityMatrixReport(
    val detectedProfile: String,
    val entries: List<PlatformCompatibilityEntry>,
)
```

---

## 3. UI/UX Design

- **진입 경로**: `Content Check` 창의 `모션포토 (Motion photo)` 탭 내부 확장.
- **구성**:
  1. 기존 상단: `감지된 형식`, `SEF/XMP/mpvd` 무결성 검사 테이블 유지.
  2. 신규 섹션 1: **스틸 ↔ 비디오 프레임 매칭 (Frame Match)**
     - 셔터 타임스탬프(μs), 매칭 프레임(#), PTS, 오차(ms), 키프레임 일치 뱃지.
     - 프레임 정렬 상태 인디케이터.
  3. 신규 섹션 2: **Apple Live Photo 페어 검사 (Live Photo Pair Check)**
     - 페어링 상태 뱃지 (MATCHED / MISMATCHED / SINGLE FILE).
     - Still Content ID vs Video Content ID 비교.
     - 타임스탬프 동기화 및 Still Image Time 분석.
     - 페어 파일 선택 / 디렉토리 자동 탐색 UI.
  4. 신규 섹션 3: **크로스 플랫폼 호환성 매트릭스 (Compatibility Matrix)**
     - 플랫폼별 지원 배지(초록: FULL, 노랑: STILL ONLY, 빨강: UNSUPPORTED).
     - 테이블 형태의 상세 요건 및 분석 리포트.

---

## 4. Testing Strategy (TDD)

1. **Unit Tests**:
   - `MotionPhotoFrameMatcherTest`:
     - 가상 `VideoSampleTable` (Timescale 1000, 30fps) 생성 후 정밀 PTS 매칭 테스트.
     - IDR 키프레임 정렬, 비-키프레임 정렬, 오차 계산, 경계값(0us, 마지막 프레임, 범위 초과) 검증.
     - XMP 파서 연동 타임스탬프 추출 테스트.
   - `AppleLivePhotoPairCheckerTest`:
     - 일치하는 UUID를 가진 Still 및 MOV 메타데이터 페어 검사 테스트.
     - 불일치 UUID, UUID 누락, 시간차 계산 테스트.
   - `MotionPhotoCompatibilityMatrixTest`:
     - Samsung SEF 전용 파일 평가 테스트.
     - Google XMP v2 파일 평가 테스트.
     - Apple Live Photo 페어 파일 평가 테스트.
2. **Integration Tests**:
   - 실제 샘플(기존 `s26-motion-photo.heic`, Google XMP 샘플 등) 분석 테스트.
   - `MotionPhotoIntegrityAnalyzer` 및 Content Check 리포트 통합 검증.
