# AI 대화 — 그래프 데이터(시계열)를 리포트로 제공 (설계)

날짜: 2026-10-17 · 상태: 승인됨 (사용자, 대화 중) · 하위 프로젝트 4/4 ("AI가 화면을 더 보게 하기")

선행: 하위 프로젝트 3 (`docs/superpowers/specs/2026-10-16-ai-analysis-reports-design.md`, main `e8623c8c`) — `report <kind>`
레지스트리(`ui/ai/AiReportRegistry.kt`), 렌더러(`ui/ai/AiAnalysisReports.kt`), `StoredAnalysis`.

## 결정 (사용자)

네 가지 모두: (1) 기존 리포트에 추세 섹션, (2) 새 `gop`, (3) 새 `audio`, (4) 새 `quality`.
그래프는 이미지로 캡처하지 않고, 그래프 뒤의 시계열을 텍스트로 줄여 보낸다.

## 공통: 시계열 요약 `renderSeries`

```kotlin
data class SeriesPoint(val x: Double, val y: Double)   // x = 초(또는 프레임 번호), y = 값
fun renderSeries(title: String, unit: String, xLabel: String, points: List<SeriesPoint>, buckets: Int = 60, spikes: Int = 10): String
```
- 점이 없으면 `<title>: no data`.
- x 범위를 최대 `buckets`개 같은 폭 구간으로 나눠 구간마다 `x0–x1: min/mean/max` 한 줄(빈 구간은 생략). 점이 buckets 이하이면 점마다 한 줄.
- 끝에 `largest values:` 상위 `spikes`개 `x=…: y` 와 `overall min/mean/max`.
- 숫자는 `Locale.US`, 소수 3자리(값 크기에 맞게 `%.3f`/`%.1f`). 출력 상한 ≈ 70줄.

## (1) 기존 리포트 추세 섹션 (렌더러에 `trend:` 블록 추가)

| kind | 시계열 |
|---|---|
| `hdr` | 프레임 PTS별 HDR10+ `targetedSystemDisplayMaximumLuminance`, 프레임별 SEI MaxCLL(있는 것만, 각각 별도 시리즈) |
| `hrd` | DTS별 `occupancyPercent` |
| `timed` | 비GPS 센서 종류별 크기 `sqrt(x²+y²+z²)`(시간축). **GPS·GENERIC은 시리즈 없음** |
| `avsync` | `syncPoints`의 `timeSeconds` → `deltaMs` |
| `interval` | 프레임 PTS → interval ms |

`avsync`는 지금 컨트롤러가 직접 문자열을 만든다 → 렌더러 `renderAvSyncReport(r)`로 옮기되 첫 줄(기존 요약)은 그대로 두고 추세만 추가.

## (2) 새 `gop` — 요청 시 계산 (VIDEO, `tab.gopFrames` 2개 이상)

- 프레임 수, 타입별 개수(I/P/B/기타)
- GOP 길이(I~다음 I 직전까지 프레임 수): min/mean/max, 마지막 GOP는 열린 GOP 표시
- 타입별 프레임 크기 bytes: min/mean/max
- 평균 비트레이트(전체 bytes·8 / (마지막 PTS−첫 PTS)), 구간별 비트레이트 추세 `renderSeries("bitrate", "kbps", "s", …)` — 구간 내 bytes·8/구간 길이
- 프레임 분석 전이면 `Unavailable("프레임 분석(GOP) 실행 필요")`.

## (3) 새 `audio` — 오디오 탭이 계산한 파형 보관

- `FfmpegAudioPlayer`가 파형(`WaveformPeaks`, 채널별 min/max 버킷)을 계산하면 콜백으로 넘기고, `AudioInspectorUI`가
  `tab.audioWaveform = StoredAnalysis(AudioWaveformData(peaks, durationSeconds), identity)`로 보관(identity는 파형 계산과 같은
  IO 단계에서 capture).
- 리포트(AUDIO 탭, 보관 결과 있을 때 `Ready`; 없으면 "오디오 탭에서 파형이 그려지면 요청 가능"):
  - 채널 수, 길이, 버킷 해상도
  - 채널별 피크 레벨 추세(dBFS, `20·log10(max(|min|,|max|))`, 하한 −120)
  - 클리핑: `|peak| ≥ 0.999`인 구간의 개수와 처음 10개 시각
  - 무음: 피크 < −60 dBFS가 0.5 s 이상 이어진 구간 개수와 처음 10개 `start–end`
  - 한계 명시: "peak-only (min/max per bucket); RMS/loudness not available".

## (4) 새 `quality` — Quality Compare 마지막 결과

- `QualityCompareWindow`가 결과를 낼 때 `appState.lastQualityComparison = StoredComparison(files: Map<role, File>,
  identities: Map<role, ContentFileIdentity?>, results: Map<pairLabel, Map<metric, MetricRunResult>>)` 저장(identity는
  비교 시작 직전 IO에서 capture). role = `raw`/`encodedA`/`encodedB`.
- 적용: 현재 탭의 파일이 비교 파일 중 하나일 때만. 모든 identity가 현재 파일과 맞을 때 `Ready`, 아니면
  `Unavailable("비교한 파일이 바뀌어 결과가 무효 — 다시 비교")`.
- 리포트: 쌍마다(라벨) 역할→파일 **이름**(경로 없음), 지표마다 통계(min/mean/median/max), 가장 나쁜 프레임 10개(PSNR/SSIM/VMAF
  모두 낮을수록 나쁨), 프레임 번호축 추세 `renderSeries`.

## 오류·상한·개인정보

- 모든 리포트는 기존 브리지의 `bounded()`(8000자)와 identity 검사를 그대로 거친다.
- 시계열은 숫자만. 파일 이름은 `scrubPaths` 후 이름만. GPS·GENERIC 센서 값은 어떤 형태로도 출력하지 않는다(기존 규칙).

## 테스트

- `renderSeries`: 빈 입력, 점 ≤ buckets, 큰 입력(10만 점) → 줄 수 상한, 스파이크 순서, 숫자 형식.
- 추세 섹션: 각 렌더러에 `trend:`가 생기고 값이 맞다; `timed`에 GPS/GENERIC 값이 없다.
- `gop`: 알려진 프레임 목록 → 타입 개수·GOP 길이·비트레이트.
- `audio`: 합성 피크(클리핑 구간 1개, 무음 1 s 구간 1개) → 검출; dBFS 계산.
- `quality`: 탭 파일이 비교에 포함/미포함, identity 무효 → 상태; 최악 프레임 정렬.
- 레지스트리: 새 kind 3개의 적용 조건과 상태.
