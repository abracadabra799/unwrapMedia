# AI 대화 — 분석 창 결과 리포트 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI가 `@@unwrapmedia report <kind>`로 8개 분석 창(HDR/SEI, HRD, 프레임 간격, 공간 비디오, 타임드 메타데이터, C2PA, SEF, 게인맵)의 결과를 받게 하고, 컨텍스트에 그 파일에서 쓸 수 있는 리포트 목록을 보여 준다.

**Architecture:** (1) 결과 타입별 순수 렌더러(개인정보 제거·상한 포함), (2) 무거운 창(HDR, HRD, 타임드)이 결과를 `TabState`에 파일 identity와 함께 보관, (3) 브리지에 리포트 레지스트리 — 보관된 결과는 identity 확인 후 반환, 가벼운 분석(C2PA, SEF, 게인맵, 공간, 간격)은 요청 시 타임아웃을 두고 실행, 컨텍스트에 적용 가능한 kind와 상태 목록.

**Tech Stack:** Kotlin, Compose Desktop, JUnit5 + kotlin.test.

**Spec:** `docs/superpowers/specs/2026-10-16-ai-analysis-reports-design.md` (먼저 읽을 것 — 개인정보 규칙의 기준)

## Global Constraints

- 개인정보: `timed` GPS 좌표 금지; `c2pa` claim title·instanceId·인증서 subject/issuer·assertion 내용·action 시각 금지; `sef` 의미 검사 세부·항목 값 금지; `gainmap` 원본 XMP 금지. 모든 자유 텍스트(경고·메시지·세부)는 `com.multiviewer.cli.scrubPaths(text, file)`를 거친다.
- 무효 결과 금지: 보관된 결과의 identity가 현재 파일과 다르면 반환하지 않는다 (규칙 7).
- 요청 시 실행: IO 스레드, 타임아웃 20 s, 인터럽트(취소)로 중단, 예외는 경로 없는 사유로.
- 응답은 브리지의 `bounded()`(`MAX_TOOL_RESULT_CHARS` = 8000)를 거치고, 각 렌더러는 목록을 30개로 자른 뒤 "… N개 생략"을 붙인다.
- 기존 `avsync`/`bitstream` 동작 불변. 새 의존성 금지. 사용자 문구 KO/EN. Material3.
- 작업 위치: worktree `.worktrees/ai-reports`, 브랜치 `feat/ai-analysis-reports`. 커밋 전 `git -C <worktree> branch --show-current` 확인. `>` 리다이렉트로 `.git`을 덮지 않도록 주의(임시 파일은 scratchpad).
- 커밋 trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

## File Structure

| 파일 | 역할 |
|---|---|
| `app/src/main/kotlin/com/multiviewer/ui/ai/AiAnalysisReports.kt` (신규) | 렌더러 8개 (순수) |
| `app/src/test/kotlin/com/multiviewer/ui/ai/AiAnalysisReportsTest.kt` (신규) | 렌더러 테스트 |
| `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` (수정) | `StoredAnalysis`, `TabState.hdrScan/hrdReport/timedMetadata` |
| `app/src/main/kotlin/com/multiviewer/ui/{HdrMetadataWindow,HrdBufferSimulationWindow,TimedMetadataTimelineWindow}.kt` (수정) | 결과 보관 |
| `app/src/main/kotlin/com/multiviewer/ui/ai/AiReportRegistry.kt` (신규) | kind 목록, 적용/상태 판정, 요청 시 실행, 컨텍스트 섹션 |
| `app/src/main/kotlin/com/multiviewer/ui/ai/AiToolBridge.kt`, `AiChatController.kt` (수정) | 레지스트리 연결 |
| `app/src/test/kotlin/com/multiviewer/ui/ai/AiReportRegistryTest.kt` (신규) | 레지스트리·브리지 테스트 |

---

### Task 1: 렌더러 (순수 함수)

**Files:** Create `ui/ai/AiAnalysisReports.kt`, `test/.../ui/ai/AiAnalysisReportsTest.kt`

**Interfaces (Produces, package `com.multiviewer.ui.ai`):**
```kotlin
internal const val MAX_REPORT_ITEMS = 30
fun renderHdrReport(r: com.multiviewer.parser.HdrScanResult, file: File): String
fun renderHrdReport(r: com.multiviewer.parser.hrd.HrdSimulationReport): String
fun renderIntervalReport(frames: List<com.multiviewer.ui.FrameInfo>): String
fun renderTimedReport(r: com.multiviewer.parser.metadata.TimedMetadataReport): String
fun renderSpatialReport(r: com.multiviewer.parser.spatial.SpatialVideoReport, file: File): String
fun renderC2paReport(store: com.multiviewer.parser.c2pa.C2paManifestStore, binding: com.multiviewer.parser.c2pa.C2paHardBindingReport?, bindingError: String?, file: File): String
fun renderSefReport(r: com.multiviewer.parser.SefIntegrityReport, file: File): String
fun renderGainmapReport(info: com.multiviewer.parser.GainmapInfo, checks: List<com.multiviewer.parser.integrity.IntegrityCheckItem>, file: File): String
```
(정확한 패키지는 실제 타입 위치를 따른다 — `grep -rn "data class HdrScanResult\|class C2paManifestStore\|data class SefIntegrityReport\|data class GainmapInfo\|data class TimedMetadataReport\|data class SpatialVideoReport\|data class HrdSimulationReport" app/src/main/kotlin`.)

**출력 형식 (한 줄씩, 영어 키 + 값; 숫자는 `String.format(Locale.US, …)`; 목록은 `MAX_REPORT_ITEMS`로 자르고 `… N more`):**

- `renderHdrReport`: 
  - `codec: <codec>` · `frames scanned: <records.size>`
  - `container mdcv: <present/absent + 주요 값(primaries, white point, min/max luminance)>` · `container clli: <MaxCLL/MaxFALL 또는 absent>`
  - `frames with HDR10+: N · mastering SEI: N · content light level SEI: N`
  - 첫 HDR10+ 프레임의 targeted max luminance 등 핵심 값 1줄(있으면, `Hdr10PlusMetadata`의 실제 필드 이름 사용)
  - `anomalies (default thresholds):` + `detectHdrAnomalies(r.records, r.container)` 각 `- [SEVERITY] RULE frames a–b: message`(message는 scrub)
  - `warnings:` + scrub한 `r.warnings`
- `renderHrdReport`: `cpb <bytes> B · bitrate <bps> bps · initial delay <s> s · frames <n>` / `occupancy min/avg/max %` / `underflows <n>: frames …(최대 30)` / `warnings at frames …(최대 30)`.
- `renderIntervalReport(frames)`: `computeFrameIntervals(frames)`로 — `frames <n> · intervals <n>` / `interval ms: min/mean/max/stddev` / `largest gaps:` 상위 10개 `#index type PTS s: interval ms (Δ ms)` / 프레임이 2개 미만이면 `fewer than 2 frames`.
- `renderTimedReport`: `track <format> id <id> · samples <total> · duration <s> s · rate <Hz> Hz` / 센서 종류별: GPS → `GPS: <count> points (coordinates withheld)`; 그 외 → `<TYPE>: <count> samples · x min/mean/max · y … · z …`. **GPS의 x/y/z 숫자는 어떤 형태로도 출력하지 않는다.** label은 출력하지 않는다(자유 텍스트).
- `renderSpatialReport`: `spatial video: yes/no` / metadata(hero eye, baseline mm, hFOV, disparity adj, stereo mode, lhvC 요약 scrub) / `frames · layer0/1 bytes · keyframes · parity match` / `anomalies:` 각 `- [SEVERITY] code @frame: message`(scrub).
- `renderC2paReport`: `location: <store.location의 종류>` · `manifests: n (active = last)` / 각 manifest(최대 5): `label`, `claim generator`, `generator info`, `format`, `claim version`, `assertions: label1, label2, …(라벨만)`, `actions: <action> by <softwareAgent> (<digitalSourceType>)`(시각 없음; actions assertion을 어떻게 읽는지는 `C2paAssertionContent`를 보고 결정), `ingredients: n`, `signature: alg <alg> · chain <n> certs · certificate subject/issuer present (withheld)` 또는 `signature error: …`, manifest warnings(scrub) / `hard binding: <MATCH|MISMATCH|UNSUPPORTED> · alg · hashed <n> B · excluded <n> B`(+ error) 또는 `hard binding: not verified (<bindingError>)` / store warnings(scrub). **claim title, instanceId, certificateSubject/Issuer 값, assertion 내용은 출력하지 않는다.**
- `renderSefReport`: `overall: <severity>` · `declared entries: <n> (<severity>)` / `structural checks:` 각 `- [SEV] label: detail`(scrub) / `semantic checks:` 각 `- [SEV] label`(세부 없음) / `directory:` 각 `#i marker <hex> <name> @<declaredOffset> +<declaredLength> in-bounds <y/n> marker-match <y/n/-> [<status>]`.
- `renderGainmapReport`: `format: <formatType>` · `summary: <summaryDescription scrub>` / `gain map image: WxH <format> @offset +length`(있는 값만) / `aux type: …` / ISO 21496-1 메타데이터 핵심 값(있으면; `IsoGainmapMetadata`의 실제 필드: 버전, base/alternate headroom, 채널별 min/max/gamma/offset 등) / `XMP: <n> chars (not sent)` / `checks:` 각 `- [STATUS] title: detail`(scrub).

- [ ] **Step 1: 실패하는 테스트 작성** — `AiAnalysisReportsTest.kt`에 렌더러마다 최소 1개, 실제 데이터 클래스를 생성자로 만들어(필수 필드는 실제 정의를 보고 채움):
  - HDR: HDR10+ 레코드 2개 + 기본 임계값을 넘는 급변 → 출력에 `frames with HDR10+: 2`, `ABRUPT_CHANGE`가 있다.
  - HRD: underflow 프레임 40개 → `underflows 40`, 30개만 나열 + `… 10 more`.
  - interval: PTS 0, 0.033, 0.066, 0.3 → `largest gaps`의 첫 줄이 #3, `interval ms` max ≈ 234.
  - timed: GPS 표본 (37.5665, 126.978, 30.0) + GYRO 표본 → 출력에 `GPS: 1 points (coordinates withheld)`; `37.5665`, `126.978`, `37.57`, `126.98` 문자열이 **없다**; GYRO의 min/mean/max는 있다.
  - spatial: anomaly 1개 → `[WARN]` 등 severity·code·message.
  - C2PA: claim title "Jane's holiday", certificateSubject "CN=Jane Doe", instanceId "xmp:iid:123" → 출력에 셋 다 **없다**; assertion 라벨과 `MATCH`는 있다.
  - SEF: 의미 검사 detail "MCC 450 (Korea)" → 출력에 `450`/`Korea` **없음**, label은 있음; 구조 검사 detail은 있음.
  - gainmap: rawXmp 1,000자 → `XMP: 1000 chars (not sent)`, XMP 내용 문자열 없음.
  - 경로: 메시지에 테스트 파일의 절대 경로를 넣으면 출력에 그 경로가 없다(`scrubPaths`).

- [ ] **Step 2: 실패 확인** — `./gradlew :app:test --tests 'com.multiviewer.ui.ai.AiAnalysisReportsTest'` → 컴파일 실패.
- [ ] **Step 3: 구현** — 위 형식대로 `AiAnalysisReports.kt` 작성. 파일 머리 주석에 "creates AI-facing text; privacy rules: see spec".
- [ ] **Step 4: 통과 확인** — 같은 명령 PASS.
- [ ] **Step 5: 커밋** — `feat: render analysis results as bounded, privacy-safe AI reports`

---

### Task 2: 무거운 창 결과 보관

**Files:** Modify `ui/AppState.kt`, `ui/HdrMetadataWindow.kt`, `ui/HrdBufferSimulationWindow.kt`, `ui/TimedMetadataTimelineWindow.kt`; Test `test/.../ui/StoredAnalysisTest.kt` (신규)

**Interfaces (Produces, package `com.multiviewer.ui`):**
```kotlin
/** A window's finished result plus the bytes it describes; stale once the file changes. */
data class StoredAnalysis<T>(val value: T, val identity: ContentFileIdentity?) {
    /** Blocking stat: call off the UI thread. */
    fun currentFor(file: File): T? = value.takeIf { identity?.matches(file) == true }
}
// TabState:
var hdrScan: StoredAnalysis<com.multiviewer.parser.HdrScanResult>? by mutableStateOf(null)
var hrdReport: StoredAnalysis<com.multiviewer.parser.hrd.HrdSimulationReport>? by mutableStateOf(null)
var timedMetadata: StoredAnalysis<com.multiviewer.parser.metadata.TimedMetadataReport>? by mutableStateOf(null)
```
(`ContentFileIdentity.capture(file)` / `matches(file)`는 기존 — `ui/ContentCheck*.kt`.)

- [ ] **Step 1: 테스트** — `StoredAnalysisTest`: 임시 파일로 capture → `currentFor` 값 반환; 파일 내용을 바꾸면(길이 변경) null; identity null이면 null.
- [ ] **Step 2: 실패 확인 → 구현** — `StoredAnalysis`는 `AppState.kt`의 `TabState` 근처(또는 `ContentFileIdentity` 옆)에 두고, TabState에 위 3개 필드를 `avSyncReport` 근처에 추가(주석: "for the AI report bridge").
- [ ] **Step 3: 창 연결** (각 창에서 결과를 얻는 지점, IO 스레드 안에서 identity를 캡처):
  - `HdrMetadataWindow.kt`: `withContext(Dispatchers.IO) { … scanHdrMetadata(…) … }` 블록이 성공 결과를 돌려줄 때 같은 IO 블록 안에서 `ContentFileIdentity.capture(tab.file)`을 얻고, `result = scanned` 다음 줄에서 `if (scanned != null) tab.hdrScan = StoredAnalysis(scanned, identity)`.
  - `TimedMetadataTimelineWindow.kt`: `report = rep` 지점에서 `tab.timedMetadata = StoredAnalysis(rep, ContentFileIdentity.capture(tab.file))` (IO 블록 안이므로 그대로).
  - `HrdBufferSimulationWindow.kt`: `val report: HrdSimulationReport = remember(frames, bitrateBps, cpbSizeBytes, initialDelaySec) { simulateHrdBuffer(…) }` 뒤에 `LaunchedEffect(report) { tab.hrdReport = StoredAnalysis(report, withContext(Dispatchers.IO) { ContentFileIdentity.capture(tab.file) }) }`. frames가 비어 있으면(로드 전/오류) 보관하지 않는다.
  - 창이 닫혀도 보관된 결과는 유지(A/V sync와 같음).
- [ ] **Step 4:** `./gradlew :app:test --tests 'com.multiviewer.ui.StoredAnalysisTest'` + `:app:compileKotlin` PASS.
- [ ] **Step 5: 커밋** — `feat: keep HDR, HRD and sensor-timeline results on the tab for the AI`

---

### Task 3: 리포트 레지스트리 + 브리지 + 컨텍스트

**Files:** Create `ui/ai/AiReportRegistry.kt`, `test/.../ui/ai/AiReportRegistryTest.kt`; Modify `ui/ai/AiToolBridge.kt`, `ui/ai/AiChatController.kt`

**Interfaces (Produces, package `com.multiviewer.ui.ai`):**
```kotlin
enum class AiReportKind(val key: String) { AVSYNC("avsync"), BITSTREAM("bitstream"), HDR("hdr"), HRD("hrd"), INTERVAL("interval"),
    TIMED("timed"), SPATIAL("spatial"), C2PA("c2pa"), SEF("sef"), GAINMAP("gainmap") }

/** What the AI can get for one kind on this file, captured at send time. */
sealed interface AiReportSource {
    data class Ready(val text: String) : AiReportSource                 // stored result, already rendered
    data class OnRequest(val run: () -> String) : AiReportSource        // light analysis, blocking, run by the bridge
    data class Unavailable(val reason: String) : AiReportSource         // not run / stale / needs frame analysis
}

/** Send-time snapshot inputs (read on the UI thread). */
data class AiReportInputs(
    val file: File, val root: BoxNode?, val type: MediaType,
    val avSync: String?, val bitstream: String?,                         // existing rendered strings
    val hdr: StoredAnalysis<HdrScanResult>?, val hrd: StoredAnalysis<HrdSimulationReport>?, val timed: StoredAnalysis<TimedMetadataReport>?,
    val frames: List<FrameInfo>?, val gainmap: GainmapInfo?, val gainmapBase: Pair<Int, Int>?, // coded base size as GainmapCheckPage computes it
)

/** Blocking (stat, cheap tree scans): call off the UI thread. Only kinds applicable to this file. */
fun aiReportSources(inputs: AiReportInputs, label: (String, String) -> String): Map<AiReportKind, AiReportSource>
fun renderReportSection(sources: Map<AiReportKind, AiReportSource>, label: (String, String) -> String): String
const val REPORT_TIMEOUT_SECONDS = 20L
```

**판정 (`aiReportSources`):**
- AVSYNC/BITSTREAM: VIDEO일 때 — 문자열 있으면 `Ready`, 없으면 `Unavailable(<I18n.menuAvSyncAnalysis 등> 실행 필요)`. (기존 controller의 `reports` 맵 문자열을 그대로 쓴다.)
- HDR/HRD: VIDEO일 때 — `stored?.currentFor(file)` 있으면 `Ready(render…)`, stored는 있는데 무효면 `Unavailable("파일이 바뀌어 결과가 무효 — 다시 실행")`, 없으면 `Unavailable("<I18n.menuHdrSeiAnalysis / menuHrdBufferSimulation>에서 실행하면 요청 가능")`.
- TIMED: VIDEO && 트리에 `camm|gpmd|mebx` 노드가 있을 때만 — HDR과 같은 규칙(`I18n.menuTimedMetadataTimeline`).
- INTERVAL: VIDEO — `frames` 2개 이상이면 `OnRequest { renderIntervalReport(frames) }`, 아니면 `Unavailable("프레임 분석(GOP) 실행 필요")`.
- SPATIAL: VIDEO && 트리에 `lhvC` — `OnRequest { analyzeSpatialVideo(file, root)?.let { renderSpatialReport(it, file) } ?: "no HEVC video track" }`.
- C2PA: `root != null && hasC2paManifestStore(root)` — `OnRequest { val store = readC2paManifestStore(file, root) ?: return "C2PA store could not be read"; val (binding, err) = runCatching { verifyC2paHardBinding(file, root, store) }…; renderC2paReport(store, binding, err?.let { scrub }, file) }`.
- SEF: 트리에 `sefd` — `OnRequest { ByteReader.open(file).use { SefIntegrityAnalyzer.analyze(it, sefd.offset, sefd.headerSize, sefd.size, file.length()) }.let { renderSefReport(it, file) } }` (SefIntegrityWindow와 같은 인자 사용 — 그 창 코드를 확인).
- GAINMAP: `gainmap?.hasGainmap == true && root != null` — `OnRequest { renderGainmapReport(gainmap, GainmapValidator.validate(file, root, gainmap, base?.first, base?.second), file) }`.

**`renderReportSection`:** 비어 있으면 빈 문자열. 아니면
```
### [분석 리포트 — 필요하면 `@@unwrapmedia report <kind>`로 요청]
- <key>: 요청 가능 | 실행됨 | <Unavailable reason>
```
(`Ready` → "실행됨"/"available", `OnRequest` → "요청 가능"/"on request", label로 KO/EN.)

**브리지 변경 (`AiToolBridge.kt`):**
- `TOOL_REPORT_KINDS = AiReportKind.entries.map { it.key }.toSet()` (help 문구도 자동 반영).
- `AiToolContext`의 `reports: Map<String, String>`을 `reports: Map<AiReportKind, AiReportSource>`로 바꾼다.
- `is AiToolRequest.Report ->`:
  - kind 없음 → `fail("The <kind> report does not apply to this file.")`
  - `Ready` → OK bounded(text)
  - `Unavailable` → fail(reason)
  - `OnRequest` → `runReportWithTimeout(source.run, REPORT_TIMEOUT_SECONDS)`: 단일 데몬 스레드 executor에 submit, `future.get(timeout)`; `TimeoutException` → `future.cancel(true)` + fail("timed out after 20 s"); `ExecutionException` → fail(원인 클래스 이름 + scrub한 메시지); `InterruptedException` → cancel(true) 후 재던짐; finally `shutdownNow()`. 성공 → OK bounded.

**컨트롤러 변경 (`AiChatController.send`):**
- 전송 시점(UI 스레드)에 `AiReportInputs`에 필요한 값을 읽는다: 기존 `reports` 맵을 만드는 코드의 avsync/bitstream 문자열, `tab.hdrScan/hrdReport/timedMetadata`, `tab.gopFrames`, `tab.gainmapInfo`, 게인맵 base 크기(`GainmapCheckPage.kt`의 `codedSize(bitmap.width, bitmap.height, orientationCode)` 계산과 같게: `tab.imageForensic?.bitmap`, `tab.imageForensic?.orientationCode`).
- 코루틴의 IO 단계(컨텍스트 캡처와 같은 `withContext(ioDispatcher)` 블록)에서 `aiReportSources(...)`를 계산하고, `renderAiContext(...)` 결과 뒤에 `renderReportSection(...)`을 붙인다.
- `AiToolContext(..., reports = sources)`.
- 도구 요청 실행 블록 `withContext(ioDispatcher) { requests…map { executeToolRequest(...) } }` 를 `withContext(ioDispatcher) { kotlinx.coroutines.runInterruptible { … } }`로 감싸 취소가 실행 중인 분석을 인터럽트하게 한다.

- [ ] **Step 1: 테스트 (`AiReportRegistryTest`)**
  - 적용 판정: IMAGE 탭(루트에 sefd 없음, gainmap 없음) → 결과 맵에 SEF/GAINMAP/HDR 없음; VIDEO 탭 → HDR/HRD/INTERVAL/AVSYNC/BITSTREAM 있고 TIMED/SPATIAL은 해당 노드 없으면 없음; 루트에 `sefd` 노드 → SEF 있음(`OnRequest`).
  - HDR 보관 결과: identity 일치 → `Ready`; 파일을 바꾼 뒤 → `Unavailable`(무효 문구); 없음 → 메뉴 이름이 든 `Unavailable`.
  - INTERVAL: 프레임 1개 → Unavailable, 3개 → OnRequest이고 run() 결과에 `intervals 2`.
  - 브리지: `Ready` → ok; `Unavailable` → not ok + reason; `OnRequest { Thread.sleep(…) }` + 짧은 테스트 타임아웃(테스트용 파라미터 `timeoutSeconds`를 `runReportWithTimeout`에 두어 1 s) → "timed out"; `OnRequest { error("boom /Users/x/secret.mp4") }` → not ok, 메시지에 `/Users/x` 없음(파일 경로 scrub — 테스트에서 그 파일을 ctx.file로); 적용 안 되는 kind → "does not apply".
  - `parseToolRequests("@@unwrapmedia report c2pa")` → Report("c2pa"); `report nope` → 거부.
  - `renderReportSection`: Ready/OnRequest/Unavailable 문구가 각각 나온다.
  - 기존 controller 테스트(`AiChatControllerTest`)가 avsync/bitstream으로 `reports`를 다루면 새 타입에 맞게 고친다.
- [ ] **Step 2: 실패 확인 → 구현 → `./gradlew :app:test --tests 'com.multiviewer.ui.ai.*'` + `:app:compileKotlin` PASS.**
- [ ] **Step 3: 실파일 확인 (자동 테스트로)** — `app/src/test/resources/c2pa/C.jpg`(C2PA 있음)를 실제로 파싱(`parseFile`)해 `aiReportSources`로 C2PA `OnRequest`를 얻고 run() → 출력에 `hard binding:`과 `assertions:`가 있고 `certificate subject` 값(실제 subject 문자열 — 테스트에서 store를 직접 읽어 얻은 값)이 없다. `no_manifest.jpg` → C2PA 없음.
- [ ] **Step 4: 커밋** — `feat: let the AI request analysis-window reports`

---

### Task 4: 전체 검증

- [ ] `./gradlew :app:test` PASS (`ThumbnailDiskCacheTest`·`SilentFrameCancellationTest`는 단독 재실행 통과 시 허용).
- [ ] 실사용(옵트인): 실제 Claude CLI에 C2PA fixture로 질문 → AI가 `report c2pa`를 요청하고 답에 하드바인딩 결과를 인용하는지(수동 로그).
- [ ] GUI(사용자): HDR/HRD/센서 타임라인 창을 연 뒤 AI 질문 → 컨텍스트 목록에 "실행됨", 미실행 시 메뉴 이름 안내.
