# AI 대화 — 비디오 선택 프레임 첨부 + 프레임 이동 정확도 수정 (설계)

날짜: 2026-10-16 · 상태: 승인됨 (사용자, 대화 중) · 하위 프로젝트 2/4 ("AI가 화면을 더 보게 하기")

선행: 하위 프로젝트 1(`docs/superpowers/specs/2026-10-16-ai-claude-image-input-design.md`, main `837943b5`) — 이미지 첨부
경로(Claude stream-json / Codex `-i` / agy `view_file`)와 첫 이미지 전송 자동 확인.

## 결정 (사용자)

1. 프레임 출처: 원본 해상도 재디코딩 (플레이어의 1280px 재생용 비트맵이 아님).
2. 기준 프레임: `tab.selectedFrame` — 필름스트립·GOP 클릭, 프레임 팝업(정지 시)에서 정해지는 프레임. 컨텍스트의 "선택한
   프레임"(#번호·타입·PTS)과 항상 같은 프레임. LIVE PLAYER는 지금처럼 독립.
3. 첨부 범위(1단계): 프레임 전체를 1024px 이하로 축소. 영역 지정은 다음 단계.
4. 프레임 팝업의 프레임 이동 버그도 같이 고친다.

## 실측 (2026-10-16, ffmpeg/ffprobe 로컬, macOS)

Fixture 4종: `default.mp4`(x264 `-bf 2`, start 0), `offset.ts`(x264 `-bf 2`, `-output_ts_offset 10` → start_time 11.4),
`hevc.mp4`(x265 bframes=3), `vfr.mkv`(x264, 프레임 41 이후 +0.2 s 간격). 기준값 = `-vf select=eq(n\,N)` 디코딩의 RGB md5.
프레임 PTS = 앱과 같은 ffprobe `frame=pts_time`(절대 스트림 타임스탬프).

| 방법 | start 0 파일 | offset.ts |
|---|---|---|
| `-ss PTS -i`(현재 `FrameFullSizeDecoder`, 플레이어) | 일치 | 불일치 / 플레이어 파이프는 **0 프레임**(PTS 12.9 > 길이 4 s) |
| `-i … -ss PTS` | 일치 | 불일치 |
| `-ss (PTS−start) -i` | 일치 | 4/11 |
| `-copyts -i … -ss PTS` | 일치 | 일치(느림: 처음부터 디코딩) |
| **`-copyts -ss max(0,PTS−start−3) -i … -vf select=gte(t\,PTS−0.0005)`** | **11/11 ×3 파일** | **11/11** |
| 같은 방법, 플레이어식 연속 출력(첫 프레임 + 남은 프레임 수) | 5/5 ×3 | 5/5 |

결론: 절대 PTS로 정확히 찾으려면 `-copyts` + 앞당긴 빠른 탐색 + `select`가 필요하다. start 0 파일은 지금 방식도 맞다.

## 범위

- 포함: (A) AI 전송 시 선택 프레임 원본 디코딩·첨부, (B) 플레이어(팝업 포함)의 절대 PTS ↔ 상대 위치 경계 수정.
- 제외: 영역 지정/줌 영역 첨부, AV2(별도 디코더 `Av2FrameBitmap` — 컨텍스트에 "아직 미지원"), HDR 톤매핑(그대로 변환, 컨텍스트에
  명시), 비교 창(`CompareVisualDiffView`/`ImageCompareWindow`의 `FrameFullSizeDecoder` 사용 — 다음에 이 결과로 판단).

## 설계

### A1. 정확한 단일 프레임 디코더 (신규, `ui/ExactFrameDecoder.kt`)

```kotlin
/** Absolute-PTS seek that lands on exactly that frame (see spec table). */
internal fun exactFrameArgs(ffmpeg: String, file: String, ptsSeconds: Double, startTime: Double): List<String>
// [ffmpeg, -v, info, -copyts, -ss, max(0, pts-start-3), -i, file, -map, 0:v:0,
//  -vf, "select=gte(t\,pts-0.0005),showinfo", -frames:v, 1, -f, image2pipe, -vcodec, png, -]

internal fun parseShowinfoPts(stderr: String): Double?   // first "pts_time:<x>"
fun decodeExactFrame(file: File, ptsSeconds: Double, startTime: Double, frameDuration: Double, timeoutMs: Long = 15_000): ExactFrameResult
sealed interface ExactFrameResult { data class Frame(val image: BufferedImage, val ptsSeconds: Double); data class Failed(val reason: String) }
```
- `showinfo`의 출력 PTS가 목표와 `frameDuration/2`(최소 1 ms) 넘게 다르면 `Failed("decoded PTS … ≠ selected …")` — 다른 프레임을
  보내지 않는다(규칙 7).
- 프로세스: `ProcessManager` 등록, `readProcessOutputWithTimeout` 계열로 stdout/stderr를 동시에 읽고 타임아웃 시 종료(규칙 5),
  인터럽트(취소) 시 종료. PNG는 `ImageIO.read`.
- `startTime`: ffprobe `format=start_time`(없거나 N/A면 0.0). 함수 `probeFormatStartTime(file): Double`.

### A2. AI 전송 연결 (`AiChatController.send`)

- 조건: `tab.type == MediaType.VIDEO`, 비디오 코덱 설정 박스가 트리에 있음(규칙 6 — 기존 헬퍼 재사용), `tab.selectedFrame != null`,
  AV2가 아님, 선택한 연동이 `canAttachImages`.
- 이미지 미리보기(`tab.aiImageSource`)가 없는 비디오 탭에서만 동작한다(이미지 탭 경로는 그대로).
- 전송 시점(UI 스레드)에 `selectedFrame`을 읽고, 코루틴의 IO 단계에서 `decodeExactFrame` → 성공 시
  `AiVisualContext(source = "video frame #N (type, PTS s)", w, h, visible = 전체, pointer = null, marked = null)`와
  `cropForAi(image, 전체)`를 하위 프로젝트 1의 확인·첨부 경로에 그대로 넘긴다(첫 전송 자동 확인 포함).
- 컨텍스트 문구(KO/EN):
  - 성공: 기존 "첨부 이미지: … PNG" 줄 + "프레임 #N 전체(원본 W×H)를 축소".
  - 프레임 미선택: "프레임 이미지 없음 — 필름스트립/GOP에서 프레임을 선택하면 첨부됩니다."
  - AV2: "AV2 프레임 이미지는 아직 첨부하지 않습니다."
  - 디코딩 실패/PTS 불일치: "선택한 프레임 이미지를 정확히 얻지 못해 첨부하지 않음 (사유)".
  - HDR(PQ/HLG 전달 특성): "HDR 프레임을 톤매핑 없이 8비트로 변환한 이미지 — 밝기·색이 실제와 다를 수 있음".
- 디코딩은 전송 1회당 1번, 취소(`stop`/탭 닫기) 시 ffmpeg 종료.

### B. 플레이어 절대 PTS 경계 수정 (`FfmpegVideoPlayer.kt`, 팝업)

계약: 플레이어의 **공개 값은 절대 스트림 PTS**(`initialSeekSeconds`, `seekRequestSeconds`, `knownFrameTimestamps`,
`onFrameTimestamps`, `onFramePresented.ptsSeconds`), **내부 위치는 지금처럼 파일 시작 기준 상대 초**(`startFromSeconds`,
`playedSeconds`, 진행 바, 경과 표시, 오디오). 변환은 경계에서만 한다.

- `VideoInfo`에 `startTime: Double = 0.0` 추가(`probeVideo`가 `format=start_time`도 읽음).
- 들어오는 절대값 → 상대: `seekRequestSeconds`, `initialSeekSeconds`, 프레임 타임스탬프 목록(내부 보관은 상대).
- 나가는 상대값 → 절대: `onFrameTimestamps`, `onFramePresented`의 `ptsSeconds`.
- ffmpeg 시작 인자: `startTime`이 0(±1 ms)이면 **지금과 같은 인자**(성능·동작 회귀 없음). 0이 아니면
  `-copyts -ss max(0, rel−3)` + `-vf`의 맨 앞에 `select=gte(t\,rel+startTime−0.0005)`. 오디오(`VideoAudioTrack`)는 상대 `-ss rel`.
- 범위 제한(`coerceIn(0, duration)`)은 상대 기준이므로 그대로 맞게 된다.
- 순수 함수로 분리해 단위 테스트: `playerSeekArgs(relSeconds, startTime): PlayerSeek(inputArgs, selectFilter?)`,
  `toRelative/toAbsolute`.

## 오류 처리

| 상황 | 결과 |
|---|---|
| 디코딩 타임아웃/실패/취소 | 이미지 없음 + 사유를 컨텍스트에, 질문은 좌표 없이 보냄 |
| 디코딩된 PTS ≠ 선택 PTS | 첨부 안 함(거짓 프레임 금지) + 사유 |
| start_time 읽기 실패 | 0.0으로 간주 — start 0 파일 동작 = 지금과 동일; TS 등은 A1의 PTS 검증이 막는다 |

## 테스트

- 실제 fixture(`app/src/test/resources/video-frame/`, 위 4종을 작게 — 640×360 → 160×90, 2 s, 생성 스크립트와 출처
  `docs/validation/video-frame-fixtures/`): 프레임 목록의 여러 N에 대해 `decodeExactFrame(PTS_N)` = `select=eq(n,N)` 기준 픽셀
  (fixture 생성 때 기준 md5를 함께 저장). ffmpeg 없는 환경에서는 `assumeTrue`로 건너뜀(기존 ffmpeg 테스트 관례 따름).
- `exactFrameArgs` / `playerSeekArgs` / `parseShowinfoPts` 순수 함수 테스트(start 0 → 기존 인자 그대로).
- 플레이어: offset.ts에서 PTS N으로 시작한 파이프의 첫 프레임 = 기준, 남은 프레임 수 = 전체−N (start 0 파일은 인자 불변 단언).
- 컨트롤러: 비디오 탭 + selectedFrame → 프레임 이미지 첨부(가짜 디코더 주입), 미선택/AV2/실패/PTS 불일치 → 첨부 없음 + 문구.
- GUI(사용자): offset.ts 팝업에서 필름스트립 프레임 선택 → 같은 프레임 표시, ←/→ 이동; AI 질문에 프레임 이미지 첨부.

## 남은 위험

- 3 s 앞당김은 경험값: GOP가 매우 긴 TS에서도 `-ss`는 그 앞 키프레임에서 디코딩을 시작하므로 정확성은 유지되고, 비용만 는다.
- 편집 목록(edit list)으로 시작이 음수/잘린 MP4는 fixture에 없다 — 기본 설정 iPhone/Galaxy MOV를 실기 확인 항목으로 남긴다.
