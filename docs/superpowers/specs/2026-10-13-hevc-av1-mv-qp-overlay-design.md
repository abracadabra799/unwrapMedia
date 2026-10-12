# Design Spec: MV/QP Overlay for HEVC and AV1

**Date:** 2026-10-13  
**Status:** Approved (YOLO Mode)  
**Target Version:** 1.21.0-dev  
**Scope:** Roadmap Item 5 — H.264 전용이었던 CodecView (Motion Vectors & QP Heatmap) 기능을 HEVC 및 AV1으로 확장

---

## 1. Background & Goals

현재 unwrapMedia는 H.264 동영상에 대해서만 `모션 벡터 보기 (Motion Vectors)` 및 `QP 히트맵 (QP Heatmap)` 메뉴를 제공하고 있습니다 (`CodecViewFrameDecoder.kt: codecViewSupportedFor(...) = codecName == "h264"`).
현대 모바일 및 스트리밍 환경에서 가장 널리 사용되는 HEVC(H.265)와 차세대 오픈 코덱 AV1에서도 프레임별 모션 벡터와 양자화 파라미터(QP), 블록 구조를 시각화하여 비디오 압축 효율과 인코더 품질을 진단할 수 있어야 합니다.

### 본 기능의 목표
1. **HEVC 지원**:
   - **모션 벡터**: FFmpeg 8.1.2의 `-flags2 +export_mvs -vf codecview=mv=pf+bf+bb`를 통한 P/B 프레임 모션 벡터 시각화.
   - **QP & 블록 구조**: HEVC PPS(`init_qp_minus26`) 및 VCL Slice Header(`slice_qp_delta`) 비트스트림 파싱을 통한 슬라이스 QP (`26 + init_qp_minus26 + slice_qp_delta`) 추출 및 `codecview=block=1` 파티셔닝 오버레이 지원.
2. **AV1 지원**:
   - **QP & 타일 구조**: AV1 OBU Frame Header의 `base_q_idx` (0..255) 및 타일 그리드(`tileCols x tileRows`) 파싱.
   - 프레임 이미지 위에 타일 파티셔닝 및 Base Q Index 정보 오버레이 제공.
3. **UI 및 메뉴 활성화**:
   - `codecViewSupportedFor`:
     - `MOTION_VECTORS`: `h264`, `hevc`
     - `QP_HEATMAP`: `h264`, `hevc`, `av1`
   - `CodecViewPreviewWindow`: 코덱별(H.264, HEVC, AV1) 특화 QP/블록 정보 뱃지 및 상세 수치 표시.

---

## 2. Architecture & Modules

### 2.1 HEVC Slice Header Parser (`HevcSliceParser.kt`)
- **목적**: HEVC 비디오 트랙의 샘플 데이터(NAL)에서 슬라이스 헤더를 파싱하여 정확한 Slice QP를 추출.
- **주요 알고리즘**:
  1. NAL 유닛 헤더 읽기: `nal_unit_type` (bit 1~6).
  2. VCL NAL(0..9: Non-IRAP, 16..21: IRAP) 필터링.
  3. 에뮬레이션 방지 바이트(`0x000003` -> `0x0000`) 제거 후 BitReader로 슬라이스 헤더 파싱:
     - `first_slice_segment_in_pic_flag` (1 bit)
     - if NAL type in IRAP: `no_output_of_prior_pics_flag` (1 bit)
     - `slice_pic_parameter_set_id` (ue)
     - if !first_slice: `dependent_slice_segment_flag` (1 bit)
     - if !dependent: `slice_type` (ue: 0=B, 1=P, 2=I)
     - if !dependent: `slice_qp_delta` (se)
  4. PPS의 `init_qp_minus26`와 결합하여 `sliceQp = 26 + init_qp_minus26 + slice_qp_delta` 계산 (범위: 0..51).

### 2.2 AV1 Frame QP Parser (`Av1FrameHeader.kt` 연동)
- **목적**: 기존 `Av1FrameHeader.kt`의 `baseQIdx` (0..255) 및 타일 정보(`tileCols`, `tileRows`)를 활용하여 프레임 수준의 양자화 인덱스 및 타일 그리드 정보 제공.
- **환산 QP**:
  - AV1 `base_q_idx`는 0..255 범위의 AC 양자화 인덱스.
  - H.264/HEVC 유사 스케일(0..63) 환산값: `(baseQIdx * 63) / 255`.

### 2.3 CodecViewFrameDecoder 확장 (`CodecViewFrameDecoder.kt`)
- `codecViewSupportedFor(mode, codecName)`:
  ```kotlin
  fun codecViewSupportedFor(mode: CodecViewMode, codecName: String?): Boolean = when (mode) {
      CodecViewMode.MOTION_VECTORS -> codecName in setOf("h264", "hevc")
      CodecViewMode.QP_HEATMAP -> codecName in setOf("h264", "hevc", "av1")
  }
  ```
- FFmpeg 필터 아규먼트 빌더:
  - HEVC QP 모드 시: `codecview=qp=1:block=1`
  - AV1 모드 시: 일반 프레임 캡처 후 Kotlin 레이어에서 타일/QP 오버레이 합성.

### 2.4 CodecViewPreviewWindow UI 확장 (`CodecViewPreviewWindow.kt`)
- 상단 인포 바:
  - 프레임 인덱스, 타임스탬프, 코덱명 태그 (H.264 / HEVC / AV1).
  - HEVC의 경우: `Slice QP: XX (Delta: +X)`, 슬라이스 타입 (I/P/B).
  - AV1의 경우: `Base Q Index: XX / 255`, `Tiles: XxY`.
  - H.264의 경우: 기존 Macroblock QP 히트맵 표시.

---

## 3. Testing Strategy (TDD)

1. **HEVC Slice Parser Unit Tests**:
   - Synthetic HEVC Slice NAL (I-Slice, P-Slice, B-Slice) with known `slice_qp_delta`.
   - SPS/PPS와 결합된 최종 Slice QP 계산 검증.
2. **CodecViewFrameDecoder Arguments Unit Tests**:
   - `buildCodecViewFfmpegArgs` for H.264, HEVC, AV1.
   - `codecViewSupportedFor` 반환값 검증.
3. **Integration Tests**:
   - 기존 테스트 리소스 `hevc_hev1.mp4`, `av1_hdr.mp4`를 대상으로 프레임 디코딩 및 QP 정보 추출 검증.
