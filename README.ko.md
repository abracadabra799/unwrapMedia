**Language:** [English](README.md) | 한국어

# unwrapMedia

**unwrapMedia**는 Kotlin과 Compose Multiplatform for Desktop 기반의 초경량·고성능 미디어 파일 구조 분석 및 포렌식 디버깅 도구입니다. 이미지, 비디오, 오디오 파일의 내부 바이너리 컨테이너를 오프셋 단위로 파싱하여 상호 연동되는 구조 트리와 Hex 뷰어, 시각적 디버깅 도구를 제공합니다.

---

## ⚡ 전체 워크플로우 및 주요 핵심 기능

```
┌─────────────────┐     ┌──────────────────────────────┐     ┌──────────────────────────────┐
│ 미디어 파일 열기 │ ──► │  인터랙티브 컨테이너 구조 트리 │ ──► │    포렌식 및 시각적 분석     │
│ (Drag & Drop)   │     │  & 오프셋 연동 Hex 뷰어       │     │ (게인맵, QP, 파형, AI 진단)  │
└─────────────────┘     └──────────────────────────────┘     └──────────────────────────────┘
```

### 🖼️ 이미지 & HDR 게인맵 (Gain Map)
* **심층 메타데이터**: EXIF, Apple MakerNote, Samsung SEFD, HEVC 그리드 타일 오버레이.
* **HDR 게인맵**: ISO 21496-1, Ultra HDR, Apple MPF, Adobe HDRGM 지원 (부스트 헤드룸 카드, 원본 XMP XML 뷰어, 게인맵 이미지 분리 팝업 및 저장).
* **모션 포토(Motion Photo)**: 삼성 및 구글 방식 모션포토 자동 감지 및 재생/추출.

### 🎬 비디오 & 비트스트림 포렌식
* **차세대 코덱**: **APV**, **AV1**, **HEVC (H.265)**, **AVC (H.264)**, **Dolby Vision** 헤더 및 파라미터 셋 상세 파싱.
* **시각적 비디오 오버레이**: 매크로블록 **모션 벡터(Motion Vectors)** 및 **QP 히트맵(QP Heatmap)** 실시간 재생 렌더링.
* **프레임 간격/드랍 분석**: 20만 개 이상의 프레임도 120 FPS LOD 렌더링으로 부드럽게 타임스탬프 산점도 분석.

### 🎵 오디오 & Raw PCM
* **파형 & 스펙트로그램**: 피크 기반 파형(Waveform) 및 FFT 스펙트로그램(Spectrogram) 줌/팬 인터랙티브 시각화.
* **지원 포맷**: WAV, MP3, AAC/M4A, FLAC, OGG, Opus, AIFF 및 헤더 없는 원시 **PCM** (`.pcm`, `.raw`).

### 🔲 Raw 픽셀 & 비교/품질 벤치마크
* **Raw 픽셀 뷰어**: 헤더 없는 YUV420 (NV12/NV21/I420) 및 RGB 덤프 실시간 렌더링 및 멀티프레임 재생.
* **두 파일 상세 비교**: 구조, 메타데이터, Hex 레벨 바이트 단위 차이점 비교.
* **화질 측정 벤치마크**: 프레임별 VMAF, PSNR, SSIM 정밀 품질 비교.

### 🤖 구조 검사 & AI 진단 프롬프트
* **구조 결함 검사 (Check)**: 심각도(`CRITICAL`, `WARNING`, `INFO`)별 컨테이너 결함 자동 린팅.
* **AI 진단 프롬프트**: ISO/IEC 표준 스펙이 매핑된 도메인 지식 기반 디버깅 프롬프트를 생성하여 클립보드에 원클릭 복사.
* **CLI 모드**: CI/CD 파이프라인 및 터미널 자동화를 위한 `dump`, `check` 명령어 제공.

---

## 📦 지원 포맷 요약

| 분류 | 지원 포맷 |
|---|---|
| **이미지** | JPEG, PNG, GIF, WebP, AVIF, HEIC/HEIF, BMP, TIFF, 카메라 RAW (DNG, CR2, NEF, ARW) |
| **비디오** | MP4, MOV, M4V, WebM, APV, AV1, IVF (코덱: APV, AV1, HEVC, AVC, Dolby Vision, VP8/VP9) |
| **오디오** | WAV, MP3, M4A/AAC, FLAC, OGG, Opus, AIFF, 헤더 없는 Raw PCM (`.pcm`) |
| **Raw 픽셀** | `.raw`, `.rgb`, `.rgba`, `.yuv`, `.nv12`, `.nv21` |

---

## 🚀 빠른 시작 가이드

### 배포 바이너리 다운로드
[GitHub Actions Artifacts / Releases](https://github.com/abracadabra799/unwrapMedia/actions)에서 최신 패키지를 다운로드할 수 있습니다:
* **macOS**: `.dmg` (`brew install ffmpeg` 필요)
* **Windows**: `.exe` (ffmpeg/ffprobe 내장)
* **Linux**: `.deb` (ffmpeg/ffprobe 내장)

### 소스코드에서 빌드 및 실행
요구 사항: JDK 21 이상 및 Gradle:
```bash
./gradlew :app:run         # 애플리케이션 실행
./gradlew test             # 테스트 실행
./gradlew :app:package     # 현재 OS용 패키지 빌드
```

### CLI 사용법
```bash
unwrapMedia dump <file>              # 전체 구조 트리를 JSON으로 출력
unwrapMedia check <file>             # 구조적 결함 및 경고 검사
unwrapMedia check <file> --prompt    # AI 진단 프롬프트 생성
unwrapMedia check <file> -p -c       # 프롬프트 생성 후 클립보드에 자동 복사
```

---

## 📄 라이선스

MIT -- [LICENSE](LICENSE) 참조.
