# Design Spec: HRD / VBV Buffer Simulation

**Date:** 2026-10-13  
**Status:** Approved (YOLO Mode)  
**Target Version:** 1.21.0-dev  
**Scope:** Roadmap Item 6 — 가상 디코더 버퍼 모델(HRD / VBV) 시뮬레이션 및 점유율 곡선 시각화

---

## 1. Background & Goals

비디오 스트리밍 및 방송, 모바일 재생 환경에서 압축 비트스트림은 네트워크나 저장 매체를 거쳐 디코더의 Coded Picture Buffer (CPB / VBV)에 일정한 전송률로 유입된 뒤, 매 프레임 표시 시각(DTS)에 디코더로 제거됩니다.
만약 비트레이트 제어가 불안정하여 순간적으로 너무 큰 프레임이 몰리면 버퍼가 비어 화면이 멈추는 **언더플로우 (Underflow)**가 발생하고, 반대로 버퍼 용량을 초과하면 패킷 유실을 유발하는 **오버플로우 (Overflow)**가 발생합니다.

Elecard StreamEye 4 및 전문 방송 검증 도구(Baton)의 핵심 기능인 **HRD/VBV Buffer Analysis**를 벤치마킹하여, unwrapMedia 내에서 비디오의 버퍼 안정성을 직관적으로 진단하고 그래프로 시각화하는 기능을 구현합니다.

---

## 2. Core Architecture

### 2.1 Simulation Engine (`HrdBufferSimulator.kt`)

#### 1) 입력 파라미터
- `VideoSampleTable` 또는 프레임 목록: 각 프레임의 크기(bytes), DTS(decodeTime in timescale ticks), 키프레임 여부.
- `bitrateBps: Long`: 비트스트림 전송률 (VUI HRD `bit_rate_value` 또는 컨테이너 평균/최대 비트레이트, 사용자 조정 가능).
- `cpbSizeBytes: Long`: 디코더 버퍼 용량 (VUI HRD `cpb_size_value` 또는 비트레이트 기반 기본값, 예: 1.5초 분량).
- `initialDelayMs: Long`: 초기 버퍼 채움 지연 시간 (Initial CPB Removal Delay, 디폴트 500ms).

#### 2) 시뮬레이션 알고리즘
1. 초기 상태: $t = 0$부터 $t = t_{\text{initial}}$까지 버퍼 채움.
   $$\text{bufferLevel}_0 = \min(\text{cpbSizeBytes}, \text{bitrateBytesPerSec} \times t_{\text{initial}})$$
2. 각 프레임 $i$ ($i = 0 \dots N-1$):
   - 이전 프레임과의 시간차: $\Delta t = \text{dts}_i - \text{dts}_{i-1}$.
   - 유입 바이트: $\text{incoming} = \text{bitrateBytesPerSec} \times \Delta t$.
   - 디코딩 직전 버퍼: $\text{levelBefore} = \min(\text{cpbSizeBytes}, \text{bufferLevel}_{i-1} + \text{incoming})$.
   - 디코딩 후 버퍼: $\text{bufferLevel}_i = \text{levelBefore} - \text{frameSize}_i$.
   - 언더플로우 판정: $\text{bufferLevel}_i < 0$.
   - 위험 경고(Warning): $\text{bufferLevel}_i < \text{cpbSizeBytes} \times 0.15$ (15% 이하).

#### 3) 데이터 모델
```kotlin
data class HrdBufferPoint(
    val frameIndex: Int,
    val dtsSeconds: Double,
    val frameSizeBytes: Long,
    val bufferLevelBytes: Long,
    val occupancyPercent: Double, // 0.0% ~ 100.0% (음수인 경우 0% 미만으로 언더플로우 표시)
    val isUnderflow: Boolean,
    val isWarning: Boolean,
    val isKeyframe: Boolean,
)

data class HrdSimulationReport(
    val cpbSizeBytes: Long,
    val bitrateBps: Long,
    val totalFrames: Int,
    val minOccupancyPercent: Double,
    val maxOccupancyPercent: Double,
    val avgOccupancyPercent: Double,
    val underflowFrames: List<Int>,
    val warningFrames: List<Int>,
    val points: List<HrdBufferPoint>,
)
```

---

### 2.2 UI & Visualization (`HrdBufferGraph.kt` & `HrdBufferSimulationWindow.kt`)

1. **상단 컨트롤 및 요약 바**:
   - 총 프레임 수, 비트레이트(kbps), 버퍼 크기(KB / ms).
   - 언더플로우 발생 건수 배지 (`✓ 0회 (안전)` vs `✗ N회 발생 (버퍼 고갈)`).
   - 비트레이트 및 버퍼 크기 실시간 파라미터 조절 슬라이더/인풋 (다양한 네트워크 환경 가상 테스트).
2. **버퍼 점유율 트렌드 그래프 (`HrdBufferGraph`)**:
   - Y축: 버퍼 점유율 0% ~ 100% (위험 기준선 15% 점선 표시).
   - X축: 프레임 번호 / 재생 타임라인.
   - 키프레임(I-Frame) 마커(노란색) 및 언더플로우 포인트(빨간색 경고 마커).
   - 마우스 호버 시 툴팁 (프레임 번호, 점유율 %, 프레임 크기).
   - 클릭 시 해당 프레임 선택 및 타임라인 동기화.

---

## 3. Testing Strategy (TDD)

1. **단위 테스트 (`HrdBufferSimulatorTest.kt`)**:
   - 정상 CBR 스트림에서 안정적인 버퍼 점유율 유지 검증.
   - 급격한 비트레이트 스파이크(대형 I-Frame 연달아 발생) 시 언더플로우 발생 감지 검증.
   - 초기 버퍼 채움 지연(`initialDelayMs`) 변화에 따른 버퍼 레벨 검증.
2. **통합 테스트**:
   - 실제 비디오 파일(`hevc_hev1.mp4`, `av1_hdr.mp4`)의 샘플 테이블 기반 시뮬레이션 동작 검증.
