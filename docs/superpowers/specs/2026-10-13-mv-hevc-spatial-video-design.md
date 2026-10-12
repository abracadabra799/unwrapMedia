# Design Spec: MV-HEVC Spatial Video Analysis

**Date:** 2026-10-13  
**Status:** Approved (YOLO Mode)  
**Target Version:** 1.21.0-dev  
**Scope:** Roadmap Item 7 — Apple Spatial Video 및 ISO-BMFF MV-HEVC(Multi-View HEVC) 공간 비디오 구조 분석

---

## 1. Background & Goals

Apple Vision Pro 출시와 iPhone 15 Pro / iPhone 16 시리즈의 공간 비디오(Spatial Video) 촬영 기능 도입으로, **MV-HEVC (Multi-View High Efficiency Video Coding, ISO/IEC 23008-2 Annex G)**가 개인용 3D/VR 미디어의 표준 규격으로 자리잡았습니다.
Apple Spatial Video는 2D HEVC 디코더와의 하위 호환성을 완벽히 유지하면서 Base View (Layer 0, 좌안)와 Secondary View (Layer 1, 우안)를 단일 트랙/비트스트림에 패키징하고, 특화된 QuickTime 박스(`lhvC`, `eyes`, `hero`, `vwdi`, `st3d`) 및 메타데이터(`eye-bidi-baseline`, `horizontal-field-of-view`)를 통해 양안 시차 및 카메라 광학 특성을 기술합니다.

본 기능의 목적은 다음과 같습니다:
1. **MV-HEVC ISO-BMFF 박스 디코더 구현**: `lhvC`, `eyes`, `hero`, `vwdi`, `st3d`를 파싱하여 박스 트리 및 속성 창에 명확히 표출.
2. **Apple Spatial QuickTime 메타데이터 추출**: 카메라 베이스라인(Interpupillary Baseline), 수평 화각(Horizontal FOV), 디스패리티 조정값 추출.
3. **비트스트림 멀티레이어 NAL 분석 (`nuh_layer_id`)**: Base view(Layer 0)와 Secondary view(Layer 1)의 프레임 수 대칭성(Parity), 비트 분배율, 키프레임 정렬 검증.
4. **전용 분석 창 (`SpatialVideoAnalysisWindow.kt`)**: 3D 스테레오 무결성, 좌안/우안 사양 대조, 이상 항목 진단 및 CSV 내보내기 지원.

---

## 2. Technical Specifications

### 2.1 ISO-BMFF Box Definitions

| Box Type | Container | Purpose | Description |
|---|---|---|---|
| `lhvC` | `trak > mdia > minf > stbl > stsd` | L-HEVC Configuration Box | 레이어 확장 VPS, SPS, PPS 비트스트림 구성 정보 |
| `eyes` | `trak` 또는 `stsd` | Spatial Video Eye Definition | Hero eye (1=Left, 2=Right), Stereo configuration |
| `hero` | `trak` | Hero Eye Indicator | 2D 디스플레이 시 기본 재생 대상이 되는 눈 지정 |
| `vwdi` | `trak` 또는 `stbl` | View Description Box | View ID 및 시점 속성 (Left/Right) |
| `st3d` | `trak` | Stereoscopic 3D Box | 0=Mono, 1=Top-Bottom, 2=Left-Right, 3=Side-by-Side |

### 2.2 Apple QuickTime Spatial Metadata Keys

QuickTime `meta > ilst` 또는 `udta` 내 Apple 메타데이터:
- `com.apple.quicktime.spatial.eye-bidi-baseline`: 양안 카메라 간격 (보통 micrometers 단위 또는 mm).
- `com.apple.quicktime.spatial.horizontal-field-of-view`: 카메라 렌즈 수평 화각 (millidegrees 또는 degrees).
- `com.apple.quicktime.spatial.disparity-adjustment`: 깊이감 보정 오프셋.

### 2.3 HEVC Bitstream NAL Layer Analysis

HEVC NAL 유닛 헤더 2바이트:
$$\text{forbidden\_zero\_bit (1b)} \parallel \text{nal\_unit\_type (6b)} \parallel \text{nuh\_layer\_id (6b)} \parallel \text{nuh\_temporal\_id\_plus1 (3b)}$$
- `nuh_layer_id == 0`: Base View (기본 2D 및 좌안 시점)
- `nuh_layer_id == 1`: Dependent View (우안 시점)
- 분석 지표:
  - Base vs Dependent 프레임 개수 일치 여부 (1:1 Parity)
  - Base vs Dependent 비트레이트 비율 (보통 Dependent view는 모션/시차 예측을 활용하므로 Base 대비 30~50% 수준)
  - 키프레임(IDR/CRA) 동기화 상태

### 2.4 Anomaly Detection Rules

1. `MISSING_LAYER_1` (FAIL): 컨테이너에 Spatial 메타데이터(`eyes` 등)가 존재하지만 비트스트림에 Layer 1 NAL이 없음.
2. `LAYER_PARITY_MISMATCH` (FAIL): Layer 0 프레임 수와 Layer 1 프레임 수가 불일치하여 3D 싱크가 어긋남.
3. `ASYMMETRIC_KEYFRAME` (WARN): Layer 0가 IDR일 때 Layer 1이 IDR이 아니거나 그 반대.
4. `MISSING_HERO_EYE` (INFO/WARN): 2D 디스플레이용 기본 뷰(`hero`)가 지정되지 않음.

---

## 3. UI Component Architecture

- **`SpatialVideoAnalysisWindow.kt`**:
  - 상단: 3D 상태 배지 (`✓ MV-HEVC 3D Spatial Video`), Hero Eye 배지, Baseline & FOV 메트릭.
  - 레이어 비트 분배 바: Base (Layer 0) vs Secondary (Layer 1) 용량 비교.
  - 양안 대조 카드: 좌안/우안 해상도, 프레임 수, I/P/B 통계.
  - 이상 항목 테이블 및 프레임별 레이어 검사 목록.
  - CSV 내보내기 (UTF-8 BOM).
