# unwrapMedia Showcase Media

These compact, original demonstration assets were created for this repository's README and app walkthrough. No third-party photos, footage, or music are included. The project source is MIT-licensed; these generated media files are supplied as demo/test fixtures.

| File | Approx. size | What to try |
|---|---:|---|
| `images/silver-tabby.jpg` | 441 KB | JPEG image viewer, EXIF thumbnail, structure and hex inspection |
| `images/silver-tabby-with-collar.jpg` | 433 KB | Open alongside the first cat image for visual/metadata comparison; the teal collar and bell are the intentional difference |
| `images/campfire-motion-photo.jpg` | 1.0 MB | Google-style JPEG Motion Photo with an embedded campfire clip |
| `images/campfire.jpg` | 231 KB | Still image used by the Motion Photo fixture |
| `images/campfire-contact-sheet.jpg` | 684 KB | Generated source board for the campfire animation fixture |
| `video/kitten-pounce.mp4` | 723 KB | Short AVC video for playback, frame analysis, and filmstrip navigation |
| `video/kitten-pounce-preview.gif` | 878 KB | Animated README preview of the kitten clip |
| `video/kitten-action-storyboard.png` | 2.2 MB | Generated source board for the kitten action clip |
| `video/campfire-motion.mp4` | 785 KB | Standalone video segment embedded in the Motion Photo fixture |
| `video/campfire-motion-preview.gif` | 258 KB | Animated README preview of the campfire video |
| `video/avsync-frame-gap.mp4` | 427 KB | Synthetic A/V sync and frame timestamp diagnostic fixture |
| `audio/gentle-tones.wav` | 465 KB | Generated tone sequence for audio playback and waveform inspection |

The four ordinary JPEG images in `images/` have a real EXIF IFD1 JPEG thumbnail embedded in the file (up to 160×120 pixels). This lets the app showcase its embedded-thumbnail path. The Motion Photo's existing XMP/video payload is retained.

`avsync-frame-gap.mp4` is deliberately non-natural test media: its audio is offset by about 280 ms and its video presentation timestamps contain a 125 ms interval after frame 48. It is useful for exercising analysis views, not for judging real-world capture quality. The GIF and short videos are derived from generated storyboard artwork; they are illustrative demo media, not camera recordings.

To try the comparison pair, open both cat JPEGs and choose **Tools → Compare Files**. For timing checks, open `avsync-frame-gap.mp4` and use the **Analyze** menu's A/V sync and frame-interval tools.

---

# unwrapMedia 쇼케이스 샘플 미디어

이 저장소의 README와 앱 시연을 위해 만든 소형 오리지널 샘플입니다. 외부 사진·영상·음원은 포함하지 않습니다. 프로젝트 소스 코드는 MIT 라이선스이며, 생성 미디어 파일은 데모·테스트 픽스처로 제공합니다.

| 파일 | 대략 크기 | 시험 기능 |
|---|---:|---|
| `images/silver-tabby.jpg` | 441 KB | JPEG 이미지 뷰어, EXIF 썸네일, 구조·헥스 분석 |
| `images/silver-tabby-with-collar.jpg` | 433 KB | 첫 번째 고양이 이미지와 시각·메타데이터 비교; 청록색 목걸이와 방울이 의도적인 차이 |
| `images/campfire-motion-photo.jpg` | 1.0 MB | 캠프파이어 영상이 내장된 구글 방식 JPEG 모션포토 |
| `images/campfire.jpg` | 231 KB | 모션포토 픽스처의 정지 이미지 |
| `images/campfire-contact-sheet.jpg` | 684 KB | 캠프파이어 애니메이션용 생성 원본 보드 |
| `video/kitten-pounce.mp4` | 723 KB | 재생, 프레임 분석, 필름스트립 탐색용 AVC 동영상 |
| `video/kitten-pounce-preview.gif` | 878 KB | README에 표시하는 고양이 동영상 미리보기 |
| `video/kitten-action-storyboard.png` | 2.2 MB | 고양이 동작 동영상용 생성 원본 보드 |
| `video/campfire-motion.mp4` | 785 KB | 모션포토 픽스처에 내장된 독립 영상 |
| `video/campfire-motion-preview.gif` | 258 KB | README에 표시하는 캠프파이어 영상 미리보기 |
| `video/avsync-frame-gap.mp4` | 427 KB | A/V 싱크 및 프레임 타임스탬프 진단용 합성 픽스처 |
| `audio/gentle-tones.wav` | 465 KB | 오디오 재생 및 파형 분석용 생성 음원 |

`images/`의 일반 JPEG 네 장에는 실제 EXIF IFD1 JPEG 썸네일(최대 160×120)이 내장되어 앱의 내장 썸네일 경로를 보여줍니다. 모션포토의 기존 XMP와 영상 페이로드도 유지했습니다.

`avsync-frame-gap.mp4`는 자연 녹화 영상이 아닌 의도적인 테스트 미디어입니다. 오디오가 약 280ms 늦고, 비디오 48번 프레임 이후 타임스탬프 간격이 125ms가 되도록 구성했습니다. 분석 화면 테스트용이며 실제 캡처 품질 판단용은 아닙니다. GIF와 단편 영상은 생성한 스토리보드 이미지를 기반으로 만든 데모 콘텐츠입니다.

비교 샘플을 사용하려면 고양이 JPEG 두 장을 열고 **Tools → Compare Files**를 선택하세요. 타이밍 분석은 `avsync-frame-gap.mp4`를 연 뒤 **Analyze** 메뉴의 A/V 싱크 및 프레임 간격 분석을 사용하면 됩니다.
