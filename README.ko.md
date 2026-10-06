**언어:** [English](README.md) | 한국어

# unwrapMedia

**unwrapMedia**는 데스크톱 미디어 뷰어이자 포렌식 분석 도구입니다. 이미지·동영상·오디오를 재생하고, 컨테이너 구조와 메타데이터, 바이트 데이터를 함께 살펴볼 수 있어 파일이 어떻게 구성되었는지 분석할 수 있습니다.

<p align="center">
  <img src="docs/screenshots/showcase/01-image-analysis.jpg" width="920" alt="unwrapMedia 이미지 분석기에서 EXIF 내장 썸네일과 원본 해상도 이미지를 나란히 보여주는 은색 고양이 사진" />
</p>

## 미디어 열기와 분석

### 이미지 뷰잉 및 분석

이미지를 보면서 컨테이너 구조, EXIF·카메라 메타데이터, 크기와 색상 정보를 확인할 수 있습니다. JPEG에 EXIF 내장 썸네일이 있으면 원본 이미지와 나란히 표시합니다. 게인맵·HDR 도구로 최신 이미지 포맷과 보조 이미지도 살펴볼 수 있습니다.

저장소의 쇼케이스 JPEG에는 작은 EXIF 썸네일을 실제 파일 내부에 넣었습니다. README에만 별도로 붙인 그림이 아닙니다.

### 동영상 재생 및 프레임 분석

동영상을 재생하면서 컨테이너와 코덱 정보를 확인할 수 있습니다. 프레임 분석은 프레임 종류와 크기를 시각화하고, 필름스트립에서 원하는 프레임을 빠르게 찾아 이동할 수 있게 합니다.

<p align="center">
  <img src="docs/screenshots/showcase/02-video-filmstrip.jpg" width="920" alt="주황색 아기 고양이 동영상과 프레임 종류 그래프, 프레임 썸네일 필름스트립을 보여주는 unwrapMedia 화면" />
</p>

샘플 영상은 [kitten-pounce.mp4](docs/showcase-media/video/kitten-pounce.mp4)입니다. 아래 움직이는 미리보기에서 필름스트립이 표현하는 동작을 볼 수 있습니다.

<p align="center">
  <img src="docs/showcase-media/video/kitten-pounce-preview.gif" width="480" alt="노란 깃털 장난감을 쫓는 주황색 아기 고양이" />
</p>

### 모션포토 재생 및 분석

삼성·구글 방식의 모션포토를 감지해 스틸 이미지와 내장 동영상을 확인하고, 재생·추출·분석할 수 있습니다. 샘플은 실제 JPEG 안에 영상 세그먼트를 포함한 캠프파이어 모션포토입니다.

<p align="center">
  <img src="docs/screenshots/showcase/03-motion-photo-fire.jpg" width="920" alt="모션포토 정지 이미지와 EXIF 썸네일, 파싱된 JPEG·삼성 모션포토 구조 및 내장 동영상 플레이어를 보여주는 unwrapMedia 화면" />
</p>

[campfire-motion-photo.jpg](docs/showcase-media/images/campfire-motion-photo.jpg) 또는 [분리된 영상](docs/showcase-media/video/campfire-motion.mp4)을 열어보세요.

아래 애니메이션은 모션포토에 내장된 영상의 미리보기입니다.

<p align="center">
  <img src="docs/showcase-media/video/campfire-motion-preview.gif" width="480" alt="푸른 저녁에 활활 타오르는 모닥불 모션포토 영상 미리보기" />
</p>

### 미디어 비교 분석

두 파일의 시각적 픽셀 차이, 메타데이터, 컨테이너 구조, 바이트 단위 차이를 비교할 수 있습니다. 동영상 화질 비교 도구는 VMAF, PSNR, SSIM 등의 지표를 제공합니다.

[고양이 비교 샘플](docs/showcase-media/images/) 두 장은 거의 같은 장면이며, 두 번째 이미지에는 청록색 목걸이와 방울이 있습니다. 두 파일을 연 다음 **Tools → Compare Files**를 선택해 비교해 보세요.

### 오디오 재생 및 분석

오디오를 들으며 메타데이터와 파형을 분석할 수 있습니다. 플레이어에서 탐색·확대/축소를 지원하고, 지원되는 오디오 레이아웃에서는 채널 제어도 가능합니다.

<p align="center">
  <img src="docs/screenshots/showcase/04-audio-waveform.jpg" width="920" alt="WAV 구조와 메타데이터, 헥스 데이터, 초록색 파형을 보여주는 unwrapMedia 오디오 분석 화면" />
</p>

생성한 [gentle-tones.wav](docs/showcase-media/audio/gentle-tones.wav)를 재생해 보세요.

## 타이밍 및 파일 진단

### A/V 싱크 및 프레임 타이밍 분석

A/V 싱크 분석기는 오디오·비디오 프레젠테이션 타임라인을 비교해 오프셋과 재생 시간 차이를 조사합니다. 프레임 간격 분석은 타임스탬프 간격을 시각화해 불규칙한 타이밍이나 프레임 드랍 가능성을 살펴봅니다. 진단 결과는 분석 근거이며, 캡처 단계의 모든 프레임 손실을 확정하는 것은 아닙니다.

[avsync-frame-gap.mp4](docs/showcase-media/video/avsync-frame-gap.mp4)는 두 기능을 시험하기 위한 합성 샘플입니다. 의도적인 오디오 지연과 비디오 타임스탬프 간격이 포함되어 있습니다. 자세한 내용은 샘플 미디어 설명을 확인하세요.

### AI 보조 진단

구조 검사는 파서가 감지한 경고를 보여주며, AI 프롬프트 생성 기능은 파일별 기술 정보를 정리해 외부 AI 도우미에 전달할 수 있게 합니다. unwrapMedia가 프롬프트를 만들며 AI 서비스를 내장하거나 요구하지 않습니다.

### 구조 트리, 헥스 뷰어 및 CLI

파싱된 박스·마커를 바이트 오프셋과 연결된 헥스 뷰어에서 탐색할 수 있습니다. CLI의 `dump`, `check` 모드는 스크립트 및 CI 검사에 활용할 수 있습니다.

## 샘플 미디어

작고 합성된 쇼케이스 파일은 [`docs/showcase-media/`](docs/showcase-media/README.md)에 있습니다. 모두 이 저장소를 위해 생성했으며 A/V 타이밍 샘플에는 테스트용 이상 구간이 의도적으로 들어 있습니다. 형식, 크기, 사용 방법은 샘플 목록을 참고하세요.

## 지원 포맷

| 분류 | 예시 |
|---|---|
| 이미지 | JPEG, PNG, GIF, WebP, AVIF, HEIC/HEIF, BMP, TIFF, 카메라 RAW |
| 비디오 | MP4, MOV, M4V, WebM, IVF; AVC/H.264, HEVC/H.265, AV1, APV, VP8/VP9, Dolby Vision |
| 오디오 | WAV, MP3, M4A/AAC, FLAC, OGG/Opus, AIFF, Raw PCM |
| Raw 픽셀 | YUV (`NV12`, `NV21`, `I420`), RGB/RGBA 덤프 |

## 시작하기

[GitHub Actions](https://github.com/abracadabra799/unwrapMedia/actions)에서 패키지를 받거나 JDK 21 이상으로 빌드하세요.

```bash
./gradlew :app:run
./gradlew test
./gradlew :app:package
```

CLI 예시:

```bash
unwrapMedia dump <file>              # 파싱된 구조를 JSON으로 출력
unwrapMedia check <file>             # 구조 경고 검사
unwrapMedia check <file> --prompt    # AI 진단 프롬프트 생성
```

## 라이선스

MIT — [LICENSE](LICENSE)를 참고하세요.
