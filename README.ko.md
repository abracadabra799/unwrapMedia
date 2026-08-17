**Language:** [English](README.md) | 한국어

# unwrapMedia

**unwrapMedia**는 초경량·고성능 미디어 파일 바이너리 구조 분석 및 디버깅 툴입니다 (Kotlin + Compose Multiplatform for Desktop). 이미지, 비디오, 오디오 파일의 내부 컨테이너 구조(Box, Marker, IFD, Chunk, 비트스트림)를 오프셋 단위로 파싱하여 상호 연동되는 구조 트리, 헥스 뷰어, 디코딩된 메타데이터를 제공합니다.

---

## ⚡ 주요 지원 기능

### 🖼️ 이미지 분석기 (Image Inspector)
- **지원 포맷**: JPEG, PNG, BMP, GIF, WebP, AVIF, HEIC/HEIF, TIFF, 카메라 RAW (CR2, NEF, ARW, DNG).
- **HEVC 그리드 타일 오버레이 & 팝업**: HEVC/HEIF 타일 경계선 오버레이 표시, 구조 트리와 실시간 양방향 싱크, 줌/팬이 가능한 독립 팝업 뷰어 제공.
- **Apple & Samsung 메타데이터**: Apple MakerNote (렌즈, 센서, 초점, HDR 게인/헤드룸, Smart Style binary plist), Samsung SEFD.
- **Apple HEIF 보조 이미지**: HDR 게인맵(Gain Map), 심도/디스패리티(Depth), 인물 효과 및 세그멘테이션 매트.
- **정밀 검증 도구**: XMP 정렬 출력, 컬러 히스토그램, JPEG DQT 히트맵, 썸네일 추출기, 애니메이션 GIF 필름스트립.
- **모션 포토(Motion Photo)**: 삼성 방식(`MotionPhoto_Data`) 및 구글 방식(스틸 + 임베디드 비디오) 완벽 지원.

### 🎬 비디오 분석기 (Video Inspector)
- **지원 포맷**: MP4, MOV, M4V, WebM.
- **재생 및 GOP 그래프**: GOP 프레임 타입 그래프(I/P/B)와 완벽히 동기화된 내장 비디오 플레이어.
- **Apple 메타데이터 & Dolby Vision**: `com.apple.quicktime.*`, Live Photo, 타임드 메타데이터(`mebx`/`mdta`), Dolby Vision (`dvcC`/`dvvC`).
- **고성능 프레임 간격/타임스탬프 분석**:
  - 타임스탬프 비정상 간격을 감지하는 산점도 및 데이터 테이블.
  - **LOD (Level of Detail) 다운샘플링**: 20만 개 이상의 프레임도 120 FPS로 부드럽게 렌더링.
  - **비동기 청크 스트리밍 (`Flow`)**: 긴 영상 분석 시에도 UI 블로킹 없는 즉각적 반응.
- **2-Tier 인덱스 캐시**: L1 메모리 LRU + L2 컴팩트 바이너리 디스크 캐시를 통한 0ms Instant 탭 전환.
- **트랙 추출**: 무손실 스트림 복사(Stream copy) 또는 재인코딩 추출.

### 🎵 오디오 분석기 (Audio Inspector)
- **지원 포맷**: M4A, MP3, WAV, FLAC, OGG, Opus, AIFF, 헤더 없는 원시 PCM (`.pcm`).
- **시각화**: 실제 디코딩된 피크 기반 파형(Waveform) 및 스펙트로그램(Spectrogram), 줌/팬 및 미니맵 지원.

### 🔲 Raw 픽셀 뷰어 (Raw Pixel Viewer)
- **지원 포맷**: 헤더 없는 `.raw`, `.rgb`, `.rgba`, `.yuv` 덤프.
- **지원 포맷**: YUV420 (NV12/NV21/I420/YV12), RGB565, RGB888, RGBA8888, ARGB8888. 멀티 프레임 원시 영상 재생 지원.

### 🤖 지능형 AI 진단 프롬프트 어시스턴트
- **원클릭 AI 디버깅 프롬프트**: 파일 정보, 바이너리 파서가 감지한 구조적 결함(JSON), ISO/IEC 스펙 컨텍스트를 포함한 전문 프롬프트 자동 생성.
- **OS 클립보드 원클릭 복사**: ChatGPT, Claude, Gemini 등에 바로 붙여넣어 원인 분석 및 FFmpeg 복구 방안을 즉시 확인.

---

## 💻 CLI 모드

터미널 스크립트, CI/CD 파이프라인 검사, AI 디버깅 프롬프트 생성에 활용할 수 있습니다:

```bash
# 1. 전체 구조 트리를 JSON으로 출력
unwrapMedia dump <file>

# 2. 구조적 결함 및 경고만 검사 (CI 린터용)
unwrapMedia check <file>

# 3. 도메인 지식이 포함된 AI 진단 프롬프트 생성
unwrapMedia check <file> --prompt

# 4. AI 프롬프트 생성 후 OS 클립보드에 자동 복사
unwrapMedia check <file> -p --clipboard
```

*종료 코드(Exit Code)*: 정상 파싱 시 `0`, 파일 누락 또는 파싱 실패 시 `1`.

---

## 📦 지원 포맷 요약

| 분류 | 확장자 |
|---|---|
| **이미지** | `.jpg`, `.jpeg`, `.png`, `.bmp`, `.gif`, `.webp`, `.avif`, `.heic`, `.tif`, `.tiff`, `.cr2`, `.nef`, `.arw`, `.dng` |
| **비디오** | `.mp4`, `.mov`, `.m4v`, `.webm` |
| **오디오** | `.m4a`, `.mp3`, `.wav`, `.flac`, `.ogg`, `.opus`, `.aiff`, `.aif`, `.aifc`, `.pcm` |
| **Raw 픽셀** | `.raw`, `.rgb`, `.rgba`, `.yuv`, `.nv12`, `.nv21` |

---

## 🚀 설치 및 실행 방법

### 배포 패키지 다운로드
GitHub Actions의 [Artifacts](https://github.com/abracadabra799/unwrapMedia/actions)에서 최신 빌드를 다운로드할 수 있습니다:
- **macOS**: `.dmg` (PATH에 ffmpeg 필요: `brew install ffmpeg`)
- **Windows**: `.exe` (ffmpeg/ffprobe 내장)
- **Linux**: `.deb` (ffmpeg/ffprobe 내장)

### 소스코드에서 직접 빌드/실행
요구 사항: JDK 21 이상 및 Gradle.

```bash
# 앱 실행
./gradlew :app:run

# 전체 테스트 실행
./gradlew test

# 현재 OS용 배포 패키지 빌드
./gradlew :app:packageDistributionForCurrentOS
```

---

## 🛡️ 안정성 및 리소스 안전장치

- **좀비 프로세스 완벽 차단**: 전역 `ProcessManager`와 JVM Shutdown Hook을 통해 앱 종료 및 취소 시 모든 백그라운드 ffmpeg/ffprobe 프로세스를 강제 회수합니다.
- **안전한 자원 해제**: 철저한 `.use { ... }` 패턴으로 파일 락(File Lock) 및 메모리 누수를 방지합니다.
- **글로벌 예외 처리**: 예상치 못한 런타임 오류 시에도 앱이 조용히 멈추지 않고 안전하게 복구 및 클린업을 수행합니다.

---

## 라이선스

MIT -- [LICENSE](LICENSE) 파일을 참조하세요.
