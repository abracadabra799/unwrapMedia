# Design Spec: Timed Metadata Tracks on Timeline

**Date:** 2026-10-13  
**Status:** Approved (YOLO Mode)  
**Target Version:** 1.21.0-dev  
**Scope:** Roadmap Item 8 — 비디오 동기화 센서(camm / gpmd / mebx 자이로, 가속도, GPS) 타임라인 분석기

---

## 1. Background & Goals

현대 스마트폰(Galaxy, Pixel 등) 및 액션캠(GoPro, DJI), 드론에서 촬영되는 동영상은 전자식 손떨림 보정(EIS, Super Steady), 3D 카메라 궤적 복원, 위치 기록을 위해 비디오 프레임과 마이크로초 단위로 동기화된 센서 트랙을 기록합니다.
주요 표준:
1. **Android `camm` (Camera Motion Metadata)**: ISO-BMFF 컨테이너 내 전용 트랙에 Gyroscope (각속도), Accelerometer (가속도), GPS 등을 스트리밍 기록.
2. **GoPro `gpmd` (GoPro Metadata Format)**: KLV(Key-Length-Value) 구조로 `GYRO`, `ACCL`, `GPS5` 등 고주파수(200Hz~1kHz) IMU 데이터 기록.
3. **Apple QuickTime `mebx` / `mdta`**: `keyd` 및 `ilst`를 통해 비디오 방향(`video-orientation`), 카메라 자세 메타데이터 기록.

본 기능의 목적은 비디오 컨테이너에 내장된 Timed Metadata 트랙을 감지하고, 비디오 재생 시간대와 1:1 동기화된 3축 센서 그래프(`SensorTimelineGraph`) 및 정밀 수치 분석 창(`TimedMetadataTimelineWindow`)을 제공하는 것입니다.

---

## 2. Core Architecture

### 2.1 Track Parsers (`TimedMetadataTrackReader.kt`)

#### 1) Android `camm` Parser
- `hdlr` handler_type = `"camm"` 또는 sample entry = `"camm"`.
- 각 샘플 페이로드:
  - 2바이트 `reserved`, 2바이트 `type`
  - `type == 0`: Gyro (Angular Velocity) — 3 x float32 (rad/s: x, y, z)
  - `type == 1`: Accelerometer — 3 x float32 (m/s²: x, y, z)
  - `type == 2`: Magnetometer — 3 x float32 (μT: x, y, z)
  - `type == 5`: GPS — double64 time, int32 fix, double64 lat, lon, alt

#### 2) GoPro `gpmd` Parser
- `hdlr` handler_type = `"gpmd"` 또는 sample entry = `"gpmd"`.
- KLV fourcc 순회:
  - `ACCL`: 16-bit signed integer (scale factor 적용) -> m/s²
  - `GYRO`: 16-bit signed integer (scale factor 적용) -> rad/s
  - `GPS5`: lat, lon, alt, 2D speed, 3D speed

#### 3) Apple `mebx` / `mdta` Parser
- 기존 `AppleTimedMetadataDecoder.kt`의 샘플 테이블 및 keyd 디코딩을 확장 활용.

### 2.2 Data Model
```kotlin
enum class SensorType {
    GYRO,
    ACCEL,
    GPS,
    ORIENTATION,
    GENERIC,
}

data class SensorSamplePoint(
    val sampleIndex: Int,
    val timestampSeconds: Double,
    val type: SensorType,
    val x: Double,
    val y: Double,
    val z: Double,
    val label: String? = null,
)

data class TimedMetadataReport(
    val trackId: Long,
    val trackFormat: String, // "camm", "gpmd", "mebx"
    val totalSamples: Int,
    val durationSeconds: Double,
    val sampleRateHz: Double,
    val samples: List<SensorSamplePoint>,
)
```

---

## 3. UI & Visualization

- **`SensorTimelineGraph.kt`**:
  - Canvas 기반 3축 시계열 파형 차트 (X축 = Blue, Y축 = Green, Z축 = Red).
  - 수평 0선 및 진폭 그리드.
  - 마우스 클릭/호버 시 시간 및 X/Y/Z 센서 수치 툴팁 표시.
- **`TimedMetadataTimelineWindow.kt`**:
  - 상단: 트랙 정보 (포맷, 샘플 수, 평균 샘플링 주파수 Hz, 지속시간).
  - 센서 종류 필터 칩 (Gyroscope / Accelerometer / GPS).
  - 센서 파형 차트.
  - 하단: 이상치(Peak Spikes) 및 타임라인 샘플 데이터 테이블.
  - CSV 내보내기 (UTF-8 BOM 지원).
