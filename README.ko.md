**Language:** [English](README.md) | 한국어

# unwrapMedia

**unwrapMedia**는 초경량·고성능 미디어 파일 바이너리 구조 분석 및 디버깅 툴입니다 (Kotlin + Compose Multiplatform for Desktop). 이미지, 비디오, 오디오 파일의 내부 컨테이너 구조(Box, Marker, IFD, Chunk, 비트스트림)를 오프셋 단위로 파싱하여 상호 연동되는 구조 트리, 헥스 뷰어, 디코딩된 메타데이터를 제공합니다.

---

## <font color="#0969da">⚡ 주요 지원 기능</font>

### <font color="#1f6feb">🖼️ 이미지 분석기 (Image Inspector)</font>
- **지원 포맷**: JPEG, PNG, BMP, GIF, WebP, AVIF, HEIC/HEIF, TIFF, 카메라 RAW (CR2, NEF, ARW, DNG).
- **포렌식 회전 보정 및 상세 해상도 표기**: EXIF/HEIF 회전각(Orientation 1~8) 자동 렌더링 및 원본/표시 해상도 상세 표기 (예: `288x512 (Raw 512x288 · 90° 회전 (6))`).
- **HEVC 그리드 타일 오버레이 & 팝업**: HEVC/HEIF 타일 경계선 오버레이 표시, 구조 트리와 실시간 양방향 싱크, 줌/팬이 가능한 독립 팝업 뷰어 제공.
- **Apple & Samsung 메타데이터**: Apple MakerNote (렌즈, 센서, 초점, HDR 게인/헤드룸, Smart Style binary plist), Samsung SEFD.
- **Apple HEIF 보조 이미지**: HDR 게인맵(Gain Map), 심도/디스패리티(Depth), 인물 효과 및 세그멘테이션 매트.
- **정밀 검증 도구**: XMP 정렬 출력, 컬러 히스토그램, JPEG DQT 히트맵, 썸네일 추출기, 애니메이션 GIF 필름스트립.
- **모션 포토(Motion Photo)**: 삼성 방식(`MotionPhoto_Data`) 및 구글 방식(스틸 + 임베디드 비디오) 완벽 지원.

### <font color="#8250df">🎬 비디오 분석기 (Video Inspector)</font>
- **컨테이너 포맷**: MP4, MOV, M4V, WebM, APV, AV1, IVF.
- **심층 코덱 및 비트스트림 파서**:
  - **APV (Advanced Professional Video)**: `apvC` 박스, 프레임 헤더(Chroma 4:2:2/4:4:4/4:4:4:4, Bit Depth 10/12/14/16-bit, 타일 그리드, Color Primaries).
  - **AV1**: `av1C` 박스, 시퀀스 헤더(Profile, Level, Tier, Bit Depth, Color Primaries, Timing), 프레임 헤더 (OBU).
  - **HEVC (H.265)**: `hvcC` 박스, VPS, SPS, PPS (Profile, Level, Tier, Main 10, 타일 그리드).
  - **AVC (H.264)**: `avcC` 박스, SPS (Profile, Level, Chroma 4:2:0/4:2:2/4:4:4, Bit Depth, VUI), PPS (CABAC/CAVLC, 8x8 Transform).
  - **Dolby Vision**: `dvcC`, `dvvC` 설정 레코드.
- **시각적 비디오 디버깅 오버레이**:
  - **모션 벡터 오버레이 (Motion Vectors)**: P/B 프레임 매크로블록 모션 추정 벡터를 재생 영상 위에 직접 오버레이 렌더링.
  - **QP 히트맵 오버레이 (QP Heatmap)**: 압축 품질 및 비트 할당을 분석할 수 있는 매크로블록 QP 컬러 히트맵 제공.
- **비디오 화질 품질 비교 (Quality Compare)**: VMAF, PSNR, SSIM 지표 기반 프레임별 정밀 품질 비교 및 차이 분석.
- **고성능 프레임 간격/타임스탬프 분석**:
  - 타임스탬프 비정상 간격 및 프레임 드랍을 감지하는 산점도 및 데이터 테이블.
  - **LOD (Level of Detail) 다운샘플링**: 20만 개 이상의 프레임도 120 FPS로 부드럽게 렌더링.
  - **비동기 청크 스트리밍 (`Flow`)**: 긴 영상 분석 시에도 UI 블로킹 없는 즉각적 반응.
- **2-Tier 인덱스 캐시**: L1 메모리 LRU + L2 컴팩트 바이너리 디스크 캐시를 통한 0ms Instant 탭 전환.
- **트랙 추출**: 무손실 스트림 복사(Stream copy) 또는 재인코딩 추출.

### <font color="#0969da">🎵 오디오 분석기 (Audio Inspector)</font>
- **지원 포맷**: M4A, MP3, WAV, FLAC, OGG, Opus, AIFF, 헤더 없는 원시 PCM (`.pcm`).
- **시각화**: 실제 디코딩된 피크 기반 파형(Waveform) 및 스펙트로그램(Spectrogram), 줌/팬 및 미니맵 지원.

### <font color="#57606a">🔲 Raw 픽셀 뷰어 (Raw Pixel Viewer)</font>
- **지원 포맷**: 헤더 없는 `.raw`, `.rgb`, `.rgba`, `.yuv`, `.nv12`, `.nv21` 덤프.
- **지원 포맷**: YUV420 (NV12/NV21/I420/YV12), RGB565, RGB888, RGBA8888, ARGB8888. 멀티 프레임 원시 영상 재생 지원.

### <font color="#bf3989">🤖 GUI 분석 도구 및 지능형 AI 진단</font>
- **단일 `분석(Analyze)` 메뉴 통합**:
  - **구조 덤프... (Dump Structure)**: 포맷별 전체 박스/마커 트리를 검색 및 줄 수/용량 표시가 포함된 JSON 뷰어로 제공.
  - **구조 결함 검사... (Check Structure)**: 비정상 박스, 오프셋 결함, 필드 값 불일치를 심각도(`CRITICAL`, `WARNING`, `INFO`)별로 직관적 표시.
  - **AI 진단 프롬프트 생성... (Generate AI Prompt)**: 포맷별(MP4, HEIC, JPEG, RAW) ISO/IEC 스펙 및 타깃 런타임이 포함된 전문 디버깅 프롬프트 자동 생성.
  - **AI 프롬프트 생성 및 클립보드 복사**: 클릭 즉시 프롬프트를 생성하여 시스템 클립보드에 복사하고 플로팅 토스트 안내 제공.
- **다국어(I18n) 지원**: `보기(View)` 메뉴를 통한 실시간 한글(기본값) / 영문 전환 및 영구 설정 저장.

---

## <font color="#0969da">💻 CLI 모드</font>

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

## <font color="#0969da">📦 지원 포맷 및 코덱 요약</font>

| 분류 | 지원 확장자 및 주요 코덱 |
|---|---|
| **이미지** | `.jpg`, `.jpeg`, `.png`, `.bmp`, `.gif`, `.webp`, `.avif`, `.heic`, `.tif`, `.tiff`, `.cr2`, `.nef`, `.arw`, `.dng` |
| **비디오 컨테이너** | `.mp4`, `.mov`, `.m4v`, `.webm`, `.apv`, `.av1`, `.ivf` |
| **비디오 코덱** | **APV**, **AV1**, **HEVC (H.265)**, **AVC (H.264)**, **Dolby Vision**, MPEG-4, VP8/VP9 |
| **오디오** | `.m4a`, `.mp3`, `.wav`, `.flac`, `.ogg`, `.opus`, `.aiff`, `.aif`, `.aifc`, `.pcm` |
| **Raw 픽셀** | `.raw`, `.rgb`, `.rgba`, `.yuv`, `.nv12`, `.nv21` |

---

## <font color="#0969da">🚀 설치 및 실행 방법</font>

### <font color="#1a7f37">배포 패키지 다운로드</font>
GitHub Actions의 [Artifacts](https://github.com/abracadabra799/unwrapMedia/actions)에서 최신 빌드를 다운로드할 수 있습니다:
- **macOS**: `.dmg` (PATH에 ffmpeg 필요: `brew install ffmpeg`)
- **Windows**: `.exe` (ffmpeg/ffprobe 내장)
- **Linux**: `.deb` (ffmpeg/ffprobe 내장)

### <font color="#57606a">소스코드에서 직접 빌드/실행</font>
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

## <font color="#1a7f37">🛡️ 안정성 및 리소스 안전장치</font>

- **좀비 프로세스 완벽 차단**: 전역 `ProcessManager`와 JVM Shutdown Hook을 통해 앱 종료 및 취소 시 모든 백그라운드 ffmpeg/ffprobe 프로세스를 강제 회수합니다.
- **안전한 자원 해제**: 철저한 `.use { ... }` 패턴으로 파일 락(File Lock) 및 메모리 누수를 방지합니다.
- **크로스 플랫폼 클립보드**: 외부 CLI 의존 없이 네이티브 JVM 클립보드 엔진 사용.
- **글로벌 예외 처리**: 예상치 못한 런타임 오류 시에도 앱이 조용히 멈추지 않고 안전하게 복구 및 클린업을 수행합니다.

---

## <font color="#57606a">📄 라이선스</font>

MIT -- [LICENSE](LICENSE) 파일을 참조하세요.
