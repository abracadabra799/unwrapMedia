**Language:** [English](README.md) | 한국어

# unwrapMedia

**unwrapMedia**는 이미지·영상·오디오 파일의 내부 구조(박스, 마커, IFD, 청크 등)를 구조 트리, 헥스 뷰, 디코딩된 메타데이터로 보여주는 파일 분석 도구입니다. (Kotlin + Compose Multiplatform Desktop)

---

## 다운로드 및 설치

매 push마다 Windows/Linux/macOS용으로 자동 빌드됩니다 -- [Actions](https://github.com/abracadabra799/unwrapMedia/actions) 페이지에서 가장 최근 **"Package unwrapMedia"** 실행의 Artifacts에서 받으세요.

| 플랫폼 | 파일 | 비고 |
|---|---|---|
| Windows | `.exe` | ffmpeg/ffprobe 내장, 별도 설치 불필요 |
| Linux | `.deb` | ffmpeg/ffprobe 내장, 별도 설치 불필요 |
| macOS | `.dmg` | 영상/오디오 재생 및 HEIC 미리보기를 쓰려면 `brew install ffmpeg`로 ffmpeg를 PATH에 설치해야 함 |

---

## 지원 포맷

| 종류 | 확장자 |
|---|---|
| 이미지 | `.jpg` `.jpeg` `.png` `.bmp` `.gif` `.webp` `.avif` `.heic` `.tif` `.tiff` `.cr2` `.nef` `.arw` `.dng` |
| 영상 | `.mp4` `.mov` `.m4v` `.webm` |
| 오디오 | `.m4a` `.mp3` `.wav` `.flac` `.ogg` `.opus` `.aiff` `.aif` `.aifc` `.pcm` |
| Raw 픽셀 | `.raw` `.rgb` `.rgba` `.yuv` |

해상도 제한: 이미지 8K, 영상 4K 초과 시 무시 가능한 경고 표시. 약 2억 6800만 픽셀 초과 시 디코드 전 헤더 단계에서 거부.

---

## 지원 기능

**이미지 분석기**
- EXIF/TIFF, GPS, Samsung MakerNote, XMP(정렬 출력), 컬러 히스토그램, JPEG DQT 히트맵
- 임베디드 썸네일 추출(JPEG/HEVC 모두), HEIC/HEVC 미리보기 디코드(ffmpeg)
- 모션 포토 지원(삼성/구글 방식: 사진 + 임베디드 영상)
- 애니메이션 GIF는 프레임 필름스트립으로 재생

**영상 분석기**
- 내장 플레이어(재생/일시정지/탐색/클릭 탐색), GOP 프레임 타입 그래프(I/P/B)
- **프레임 간격 분석**: 프레임 번호별 간격 산점도 + 데이터 테이블(프레임 번호/타임스탬프/간격/간격 차이) -- 프레임 드랍/불규칙 간격 확인용
- 모션 포토는 미리보기 재생 영상이 아닌 임베디드 동영상 자체에 대해 별도로 프레임 간격 분석 가능
- 스트림별 코덱 정보(프로파일/레벨/크로마/비트 심도/프레임레이트/비트레이트/길이)
- 트랙 추출(영상/오디오 스트림을 별도 파일로, 스트림 카피 우선·실패 시 재인코딩)

**오디오 분석기**
- 파형(실제 디코딩된 값) + 스펙트로그램 재생, 마우스 휠 줌/트랙패드 팬/스크롤바/미니맵, 클릭·드래그 탐색
- 포맷별 전용 구조 파서
- 포맷/샘플레이트/채널/비트 심도/길이

**Raw 픽셀 뷰어** -- 
헤더 없는 원시 픽셀 덤프
YUV420(NV12/NV21/I420/YV12), RGB565/BGR565, RGB888/BGR888, RGBA8888/ARGB8888. 
멀티 프레임(동영상) 지원(영상처럼 재생)

**바이너리 탐색기** -- 
구조 트리 + 
우측 패널(선택 전엔 한눈에 보는 Overview, 트리 노드 선택 시 상세 속성으로 전환, 필드 클릭 시 헥스 뷰가 해당 바이트로 이동) + 
드래그 선택 가능한 헥스/raw 바이트 뷰어. 모든 패널 크기 조절 가능, 다크/라이트 테마 전환(설정 유지).

**CLI 모드** -- GUI 없이 스크립트/CI에서 사용:
```bash
unwrapMedia dump <file>   # 전체 구조 트리를 JSON으로 출력
unwrapMedia check <file>  # 경고만 JSON으로 출력, 스펙에 부합하는지 check 
```
정상 파싱 시 종료 코드 `0`(경고 유무 무관), 파싱 자체가 불가능하면 `1`.

---

## 기술 스택

Kotlin, Compose Multiplatform Desktop (JVM 21+), Gradle, ffmpeg/ffprobe(외부 프로세스 호출, 정적 링크 아님).

```bash
./gradlew :app:run                            # 실행
./gradlew test                                # 테스트
./gradlew :app:packageDistributionForCurrentOS # 배포 패키지 빌드
```

---

## 추가 코멘트

- 사내 공유 전 확인 필요: ffmpeg/ffprobe를 서브프로세스로 호출하며(LGPL, 소스 내장 아님), H.264/HEVC/AAC 등 코덱을 디코드/재생합니다. LGPL은 ffmpeg 자체 코드에 대한 것이고 코덱 특허 라이선스와는 별개이므로, 개인 사용 범위를 넘어 배포할 경우 법무팀 검토가 필요합니다.
- 라이선스: MIT -- [LICENSE](LICENSE) 참고.
