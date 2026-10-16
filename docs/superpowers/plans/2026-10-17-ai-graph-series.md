# AI 대화 — 그래프 시계열 리포트 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 그래프 뒤의 시계열을 AI 리포트로 제공 — 기존 hdr/hrd/timed/avsync/interval에 추세 섹션, 새 `gop`·`audio`·`quality` 리포트.

**Architecture:** 공통 `renderSeries`(최대 60구간 min/mean/max + 상위 스파이크)를 렌더러에 더하고, 새 kind 3개를 하위 프로젝트 3의 레지스트리(`AiReportRegistry.kt`)에 추가한다. `audio`는 오디오 플레이어가 계산한 파형을, `quality`는 Quality Compare의 마지막 결과를 identity와 함께 보관한다.

**Tech Stack:** Kotlin, Compose Desktop, JUnit5 + kotlin.test.

**Spec:** `docs/superpowers/specs/2026-10-17-ai-graph-series-design.md` (형식·규칙의 기준 — 먼저 읽을 것)

## Global Constraints

- 시계열은 숫자만; 파일 이름은 `scrubPaths` 후 이름만(경로 금지); `timed`의 GPS·GENERIC 값은 어떤 형태로도 출력 금지(하위 프로젝트 3 규칙 유지).
- 무효 결과 금지: 보관 결과는 identity가 현재 파일과 맞을 때만 `Ready` (규칙 7).
- UI 스레드에서 파일 stat 금지 — identity capture는 IO 단계에서.
- 숫자 형식 `Locale.US`. 응답은 기존 브리지 `bounded()`(8000자)를 거친다; `renderSeries` 출력 ≈ 70줄 이하.
- 기존 리포트의 기존 줄은 바꾸지 않는다(추세는 끝에 `trend:` 블록으로 추가). `avsync` 첫 요약 줄은 지금 텍스트와 같아야 한다.
- 새 의존성 금지. 사용자 문구 KO/EN.
- 작업 위치: worktree `.worktrees/ai-graphs`, 브랜치 `feat/ai-graph-series`. 커밋 전 `git -C <worktree> branch --show-current` 확인. `>` 리다이렉트로 `.git`을 덮지 않기(임시 파일은 scratchpad).
- 커밋 trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## File Structure

| 파일 | 역할 |
|---|---|
| `ui/ai/AiSeries.kt` (신규) | `SeriesPoint`, `renderSeries` |
| `ui/ai/AiAnalysisReports.kt` (수정) | 추세 블록, `renderAvSyncReport`, `renderGopReport`, `renderAudioReport`, `renderQualityReport` |
| `ui/ai/AiReportRegistry.kt` (수정) | kind `GOP`, `AUDIO`, `QUALITY`; avsync를 렌더러로 |
| `ui/ai/AiChatController.kt` (수정) | 입력 전달 |
| `ui/AppState.kt` (수정) | `AudioWaveformData`, `TabState.audioWaveform`, `StoredComparison`, `AppState.lastQualityComparison` |
| `ui/FfmpegAudioPlayer.kt`, `ui/AudioInspectorUI.kt`, `ui/QualityCompareWindow.kt` (수정) | 결과 보관 |
| 테스트: `ui/ai/AiSeriesTest.kt`(신규), `AiAnalysisReportsTest.kt`, `AiReportRegistryTest.kt` (수정) | |

---

### Task 1: `renderSeries` + 기존 리포트 추세 섹션

**Files:** Create `ui/ai/AiSeries.kt`, `test/.../ui/ai/AiSeriesTest.kt`; Modify `ui/ai/AiAnalysisReports.kt`, `ui/ai/AiReportRegistry.kt`, `ui/ai/AiChatController.kt`, `test/.../ui/ai/AiAnalysisReportsTest.kt`

**Interfaces (Produces):**
```kotlin
data class SeriesPoint(val x: Double, val y: Double)
fun renderSeries(title: String, unit: String, xLabel: String, points: List<SeriesPoint>, buckets: Int = 60, spikes: Int = 10): String
fun renderAvSyncReport(r: com.multiviewer.ui.AvSyncReport): String   // first line == today's controller text
```

**`renderSeries` 형식:**
```
<title> (<unit>) over <xLabel>, <n> points:
  <x0>–<x1>: min <a> · mean <b> · max <c>        (≤ buckets lines; empty buckets skipped; n ≤ buckets → one line per point "<x>: <y>")
  largest: <x>=<y>, <x>=<y>, …                   (top `spikes` by y, descending)
  overall: min <a> · mean <b> · max <c>
```
빈 입력 → `<title>: no data`. 숫자: |v| ≥ 100 → `%.1f`, 그 외 `%.3f` (Locale.US). NaN/Infinite 점은 버린다.

**추세 블록(각 렌더러 끝에 `trend:` 다음 줄부터):**
- `renderHdrReport`: `renderSeries("HDR10+ targeted max luminance", "nits", "s", records에서 hdr10Plus 있는 프레임 → (ptsSeconds, targetedSystemDisplayMaximumLuminance))`, `renderSeries("SEI MaxCLL", "nits", "s", contentLightLevel 있는 프레임 → (pts, maxCll))` — 해당 데이터 없는 시리즈는 생략.
- `renderHrdReport`: `renderSeries("buffer occupancy", "%", "s", points → (dtsSeconds, occupancyPercent))`.
- `renderTimedReport`: GPS·GENERIC 제외 센서 종류별 `renderSeries("<TYPE> magnitude", "", "s", (timestampSeconds, sqrt(x²+y²+z²)))`.
- `renderIntervalReport`: `renderSeries("frame interval", "ms", "s", (ptsSeconds, intervalMs))`.
- 새 `renderAvSyncReport(r)`: 첫 줄은 현재 `AiChatController`가 `put("avsync", …)`로 만드는 문자열과 **완전히 같게**(그 코드를 옮겨 오고 컨트롤러는 이 함수를 호출), 그 뒤 `trend:` + `renderSeries("A/V skew", "ms", "s", syncPoints → (timeSeconds, deltaMs))`. diagnoses 등 자유 텍스트가 있으면 지금처럼(경로 scrub은 기존과 동일하게).

- [ ] **Step 1: 테스트** — `AiSeriesTest`: 빈 입력; 5점(≤ buckets) → 5줄+largest+overall; 100,000점 → 전체 줄 ≤ 70; 스파이크 정렬; `1234.5`→`1234.5`, `0.12345`→`0.123`; NaN 무시. `AiAnalysisReportsTest`: 각 렌더러 출력에 `trend:`와 해당 시리즈 제목이 있다; timed에 GPS(37.5665 등)·GENERIC 값이 trend에도 없다; `renderAvSyncReport` 첫 줄이 기존 형식과 같다(기존 컨트롤러 문자열 형식을 테스트에 고정).
- [ ] **Step 2: 실패 확인 → 구현** (컨트롤러의 avsync 문자열 생성을 `renderAvSyncReport(r)` 호출로 교체; `AiReportInputs.avSync`는 그대로 문자열).
- [ ] **Step 3:** `./gradlew :app:test --tests 'com.multiviewer.ui.ai.*'` + `:app:compileKotlin` PASS.
- [ ] **Step 4: 커밋** — `feat: add series trends to AI analysis reports`

---

### Task 2: `gop` + `audio` 리포트

**Files:** Modify `ui/AppState.kt`, `ui/FfmpegAudioPlayer.kt`, `ui/AudioInspectorUI.kt`, `ui/ai/AiAnalysisReports.kt`, `ui/ai/AiReportRegistry.kt`, `ui/ai/AiChatController.kt`; tests.

**Interfaces (Produces):**
```kotlin
// AppState.kt (package com.multiviewer.ui)
data class AudioWaveformData(val peaks: WaveformPeaks, val durationSeconds: Double)
var audioWaveform: StoredAnalysis<AudioWaveformData>? by mutableStateOf(null)   // TabState
// FfmpegAudioPlayer: new parameter
onWaveformComputed: (WaveformPeaks, durationSeconds: Double, identity: ContentFileIdentity?) -> Unit = { _, _, _ -> }
// renderers
fun renderGopReport(frames: List<com.multiviewer.ui.FrameInfo>): String
fun renderAudioReport(data: com.multiviewer.ui.AudioWaveformData): String
// registry: AiReportKind.GOP("gop"), AiReportKind.AUDIO("audio"); AiReportInputs gains `audio: StoredAnalysis<AudioWaveformData>?`
```

**보관:** `FfmpegAudioPlayer`의 파형 계산(`waveformPeaks = runInterruptible(Dispatchers.IO) { computeWaveformPeaks(…) }`) 블록 안에서 같은 IO 단계에 `ContentFileIdentity.capture(file)`을 얻고, 결과가 null이 아니면 `onWaveformComputed(peaks, info.duration, identity)`. `AudioInspectorUI`가 `onWaveformComputed = { p, d, id -> tab.audioWaveform = id?.let { StoredAnalysis(AudioWaveformData(p, d), it) } }`를 넘긴다(identity null이면 보관 안 함). 다른 `FfmpegAudioPlayer` 호출부는 기본값.

**`renderGopReport(frames)`** (PTS 순):
```
frames <n> · I <a> · P <b> · B <c> · other <d>
GOP length (frames): min/mean/max over <k> GOPs (last GOP open: <m> frames)   (I가 없으면 "no I-frames")
frame size by type: I min/mean/max B · P … · B …
average bitrate <kbps> kbps over <s> s
trend:
<renderSeries("bitrate", "kbps", "s", 60구간: 구간 내 sizeBytes·8/구간 길이/1000 — 구간 중심 x)>
```
(구간 비트레이트를 `SeriesPoint`로 만든 뒤 `renderSeries(buckets = 60)`에 넘긴다 — 이미 60개 이하라 점마다 한 줄.)

**`renderAudioReport(data)`:**
```
channels <c> · duration <s> s · buckets <b> (<ms> ms each) · peak-only (min/max per bucket); RMS/loudness not available
channel <i>: peak <dBFS> dBFS · clipped buckets <n> (first at <t> s, …≤10) · silent spans ≥0.5 s below −60 dBFS: <n> (<start>–<end> s, …≤10)
trend:
<renderSeries("channel <i> peak", "dBFS", "s", 버킷 → (t, dBFS))> (채널마다)
```
dBFS = `20·log10(max(|min|,|max|))`, 0이면 −120 하한. 클리핑 = `max(|min|,|max|) ≥ 0.999`.

**레지스트리:**
- GOP: VIDEO — frames ≥ 2 → `OnRequest { renderGopReport(frames) }`, 아니면 `Unavailable("프레임 분석(GOP) 실행 필요" / EN)`.
- AUDIO: `type == MediaType.AUDIO` — `audio?.currentFor(file)` → `Ready(renderAudioReport)`(렌더 실패는 기존 `stored()`처럼 Unavailable), 보관은 있는데 무효 → 무효 문구, 없음 → `Unavailable("오디오 파형이 그려지면 요청 가능" / "available once the waveform has been drawn")`.
- 컨트롤러: `AiReportInputs(audio = tab.audioWaveform)`.

- [ ] **Step 1: 테스트** — `renderGopReport`: I P B B P I P B (PTS 0..0.233 s 간격 1/30, 크기 지정) → 타입 개수 2/3/3, GOP 길이 min/max, 평균 비트레이트 계산값. `renderAudioReport`: 2채널 합성 피크(채널 0에 1.0 클리핑 버킷 하나, 채널 1에 0 값 1 s 연속) → 클리핑 1·무음 1 검출, dBFS(0.5 → −6.021). 레지스트리: VIDEO+frames → GOP OnRequest; AUDIO 탭 + 보관 → Ready, 파일 변경 → 무효, 없음 → Unavailable; IMAGE 탭엔 둘 다 없음.
- [ ] **Step 2: 실패 확인 → 구현.**
- [ ] **Step 3:** `./gradlew :app:test --tests 'com.multiviewer.ui.ai.*'` + `:app:compileKotlin` PASS.
- [ ] **Step 4: 커밋** — `feat: add GOP and audio waveform reports for the AI`

---

### Task 3: `quality` 리포트

**Files:** Modify `ui/AppState.kt`, `ui/QualityCompareWindow.kt`, `ui/ai/AiAnalysisReports.kt`, `ui/ai/AiReportRegistry.kt`, `ui/ai/AiChatController.kt`; tests.

**Interfaces (Produces):**
```kotlin
// AppState.kt
data class StoredComparison(
    val files: Map<String, File>,                         // role: "raw", "encodedA", "encodedB"
    val identities: Map<String, ContentFileIdentity?>,
    val results: Map<String, Map<String, MetricRunResult>>, // pair label -> metric -> result (as the window holds them)
    val pairs: List<ComparisonPair>,
) {
    /** Blocking stat. */
    fun currentFor(file: File): Boolean = files.values.any { it.absolutePath == file.absolutePath } &&
        files.all { (role, f) -> identities[role]?.matches(f) == true }
}
var lastQualityComparison: StoredComparison? by mutableStateOf(null)   // AppState
fun renderQualityReport(c: StoredComparison): String
// registry: AiReportKind.QUALITY("quality"); AiReportInputs gains `quality: StoredComparison?`
```

**보관:** `QualityCompareWindow`가 비교를 시작할 때(IO 실행 직전, 기존 executor 작업 안) 역할별 파일의 identity를 capture하고, 결과(`results`)가 완성되어 UI에 반영되는 지점에서 `appState?.lastQualityComparison = StoredComparison(files, identities, results, pairs)`. 취소·실패 시 보관하지 않는다. (창 코드의 실제 변수 이름 — `rawFile`, `encodedAFile`, `encodedBFile`, `results`, `determineComparisonPairs` — 을 확인해 맞춘다.)

**`renderQualityReport(c)`:** 쌍마다
```
pair <label>: reference <role> "<name>" · comparison <role> "<name>"
  <METRIC>: min/mean/median/max · worst frames: #i=v, … (10, ascending)
  trend:
  <renderSeries("<METRIC>", "", "frame", perFrame → (frameIndex, value))>
```
파일은 `scrubPaths(f.name, f)`(이름만).

**레지스트리:** QUALITY는 `quality != null && quality.files.values.any { it.absolutePath == file.absolutePath }`일 때만 — `quality.currentFor(file)` true → `Ready(renderQualityReport)`, false → `Unavailable("비교한 파일이 바뀌어 결과가 무효 — 다시 비교" / EN)`.

- [ ] **Step 1: 테스트** — 렌더러: 두 지표·worst 정렬·이름만(경로 없음); 레지스트리: 탭 파일 포함 + identity 일치 → Ready; 포함 안 됨 → 키 없음; 비교 파일 하나가 바뀜 → Unavailable.
- [ ] **Step 2: 실패 확인 → 구현.**
- [ ] **Step 3:** `./gradlew :app:test --tests 'com.multiviewer.ui.ai.*'` + `:app:compileKotlin` PASS.
- [ ] **Step 4: 커밋** — `feat: add the last Quality Compare result as an AI report`

---

### Task 4: 전체 검증

- [ ] `./gradlew :app:test` PASS (`ThumbnailDiskCacheTest`·`SilentFrameCancellationTest`는 단독 재실행 통과 시 허용).
- [ ] GUI(사용자): 오디오 파일 열기 → AI 질문 → 목록에 `audio: 실행됨`; 프레임 분석 후 `gop`; Quality Compare 실행 후 그 파일 탭에서 `quality`.
