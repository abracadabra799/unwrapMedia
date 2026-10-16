# AI 대화 — 프레임/HDR 분석 자동 실행 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** AI가 `report gop|interval|hdr`를 요청하면 아직 실행되지 않은 프레임 분석·HDR 스캔을 앱이 자동 실행하고, 결과를 같은 질문 안(`frame <n>` 포함)과 탭(화면·다음 질문)에서 재사용한다.

**Spec:** `docs/superpowers/specs/2026-10-17-ai-autorun-analyses-design.md`

## Global Constraints

- VIDEO 탭만 (규칙 6). 파일 identity 확인은 기존 브리지 검사 유지; 보관 HDR 결과의 identity는 스캔 직전 캡처 (규칙 7).
- 외부 프로세스: 기존 `probeFrameTypes`(내부 120 s 타임아웃, `readProcessOutputWithTimeout`) 재사용; 인터럽트로 중단 (규칙 5).
- UI 스레드에서 파일 I/O 금지; 탭 상태 변경은 UI 스레드에서 (`EventQueue.invokeLater` 또는 기존 `tab.postIfOpen`).
- 자동 실행 타임아웃 120 s (`AUTO_RUN_TIMEOUT_SECONDS`), 기존 kind 20 s 유지.
- 기존 동작 불변: 결과가 이미 있으면 지금과 같은 `Ready`/`OnRequest` 출력. 새 의존성 금지. 문구 KO/EN.
- 작업 위치: worktree `.worktrees/ai-autorun`, 브랜치 `feat/ai-autorun-analyses`. 커밋 전 브랜치 확인. `.git`을 리다이렉트로 덮지 않기.
- 커밋 trailer: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

---

### Task 1: 자동 실행 (레지스트리·브리지·컨트롤러·패널)

**Files:** `ui/ai/AiReportRegistry.kt`, `ui/ai/AiToolBridge.kt`, `ui/ai/AiChatController.kt`, `ui/ai/AiChatPanel.kt`, `ui/AppState.kt`; tests `ui/ai/AiReportRegistryTest.kt`, `ui/ai/AiToolBridgeTest.kt`.

**Interfaces:**
```kotlin
// AiReportRegistry.kt
const val AUTO_RUN_TIMEOUT_SECONDS = 120L
class OnRequest(val run: () -> String, val timeoutSeconds: Long = REPORT_TIMEOUT_SECONDS, val autoRunLabel: String? = null) : AiReportSource

/** Shared by one send: what an auto-run produced, so later requests in the same send reuse it. */
class AiAutoRunState(initialFrames: List<FrameInfo>?) {
    val frames = java.util.concurrent.atomic.AtomicReference(initialFrames)
    val hdr = java.util.concurrent.atomic.AtomicReference<HdrScanResult?>(null)
}

/** Injected work for auto-runs (defaults = the real analyses; tests replace them). */
class AiAutoRunHooks(
    val probeFrames: (File) -> List<FrameInfo>? = ::probeFrameTypes,
    val scanHdr: (File, BoxNode, () -> Boolean) -> HdrScanResult? = { f, r, c -> scanHdrMetadata(f, r, isCancelled = c) },
    val captureIdentity: (File) -> ContentFileIdentity? = { runCatching { ContentFileIdentity.capture(it) }.getOrNull() },
    /** Called off the UI thread with a finished result; the controller posts it to the tab on the UI thread. */
    val publishFrames: (List<FrameInfo>) -> Unit = {},
    val publishHdr: (StoredAnalysis<HdrScanResult>) -> Unit = {},
    /** Running indicator: non-null label while an auto-run executes, null after (always cleared in finally). */
    val running: (String?) -> Unit = {},
)

fun aiReportSources(inputs: AiReportInputs, label: …, …, autoRun: AiAutoRunState = AiAutoRunState(inputs.frames), hooks: AiAutoRunHooks = AiAutoRunHooks()): Map<AiReportKind, AiReportSource>
```

**규칙:**
- `framesOrRun()`: `autoRun.frames.get()?.takeIf { it.size >= 2 } ?: run { hooks.running(label("프레임 분석", "frame analysis")); try { hooks.probeFrames(file)?.also { autoRun.frames.set(it); hooks.publishFrames(it) } } finally { hooks.running(null) } }`. 동시에 두 kind가 불러도 프로브는 한 번만(작은 `synchronized(autoRun)`).
- INTERVAL / GOP (VIDEO): 프레임 ≥2 있으면 지금과 같은 OnRequest(기본 타임아웃); 없으면 `OnRequest(run = { val f = framesOrRun(); if (f == null || f.size < 2) "frame analysis found fewer than 2 frames" else render(f) }, timeoutSeconds = AUTO_RUN_TIMEOUT_SECONDS, autoRunLabel = label("분석 자동 실행", "runs the analysis"))`.
- HDR (VIDEO, root != null): 보관 결과가 현재면 지금과 같은 `Ready`; 아니면 `OnRequest(timeout 120, autoRunLabel)`: `autoRun.hdr.get()` 있으면 그걸 렌더; 없으면 `val id = hooks.captureIdentity(file); hooks.running(label("HDR 분석", "HDR analysis")); try { hooks.scanHdr(file, root) { Thread.currentThread().isInterrupted } } finally { hooks.running(null) }` → null이면 `"HDR scan: no supported video sample table (MP4/MOV family only)"`, 아니면 `autoRun.hdr.set(r)`; `id?.let { hooks.publishHdr(StoredAnalysis(r, it)) }`; `renderHdr(r, file)`.
- `renderReportSection`: OnRequest에 `autoRunLabel`이 있으면 "요청 가능 (<autoRunLabel>)" / "on request (<autoRunLabel>)".

**브리지:**
- `runReportWithTimeout(source.run, ctx.file, source.timeoutSeconds)`.
- `AiToolContext.frames`를 `() -> List<FrameInfo>?`로 바꾸고 `frame <n>`이 이를 호출. 컨트롤러는 `{ autoRun.frames.get() }`를 넘긴다(같은 전송의 자동 실행 결과 반영). 기존 테스트의 `frames = …`는 `{ … }`로.

**컨트롤러:**
- 전송마다 `val autoRun = AiAutoRunState(frames)`; `hooks = AiAutoRunHooks(publishFrames = { f -> EventQueue.invokeLater { if (!tab.isClosed && tab.gopFrames == null && !tab.isAnalyzingFrames) tab.gopFrames = f } }, publishHdr = { s -> EventQueue.invokeLater { if (!tab.isClosed) tab.hdrScan = s } }, running = { l -> EventQueue.invokeLater { tab.aiAutoRunning = l } })` — `tab.isClosed`/`postIfOpen`의 실제 API를 확인해 맞춘다.
- `aiReportSources(..., autoRun, hooks)`; `AiToolContext(frames = { autoRun.frames.get() })`.

**패널/AppState:**
- `TabState`: `var aiAutoRunning: String? by mutableStateOf(null)` (주석: AI auto-run in progress).
- 패널 진행 문구 우선순위: `tab.aiFrameDecoding` → 기존, `tab.aiAutoRunning != null` → `"📊 ${it} 실행 중…"` / `"📊 Running ${it}…"`, `tab.aiImageChecking` → 기존, 그 외 기존.

- [ ] **Step 1: 테스트(먼저, RED 기록)** — 레지스트리: (a) VIDEO, frames=null → GOP·INTERVAL OnRequest(timeout 120), 두 run()을 차례로 호출해도 주입 `probeFrames` 호출 1회, `publishFrames` 1회, 출력에 GOP/interval 내용; (b) probe가 null/1프레임 → "fewer than 2 frames", publish 없음; (c) HDR 보관 없음 → OnRequest(timeout 120), run()이 주입 scan 결과 렌더, `publishHdr` 1회(identity 포함), 두 번째 run()은 scan 재호출 없음; scan null → 미지원 문구, publish 없음; (d) `running`이 label→null 순서로 호출(예외 시에도 null); (e) frames가 이미 있으면 기존과 같은 OnRequest(timeout 20)이고 probe 미호출; (f) section 문구에 autoRunLabel. 브리지: `report gop`(주입 hooks) 뒤 같은 ctx로 `frame 1` → OK; OnRequest의 짧은 timeoutSeconds(1 s)로 sleep run → "timed out". 실파일(ffmpeg 있을 때): `video-frame/default.mp4`를 `parseFile`로 열고 기본 hooks로 GOP run() → `frames ` 줄과 `GOP length` 포함.
- [ ] **Step 2: 구현 → `./gradlew :app:test --tests 'com.multiviewer.ui.ai.*'` + `:app:compileKotlin` PASS (GREEN 기록).**
- [ ] **Step 3: 커밋** — `feat: run frame and HDR analyses automatically when the AI asks`

### Task 2: 전체 검증

- [ ] `./gradlew :app:test` PASS.
- [ ] GUI(사용자): 비디오 열고(프레임 분석 안 한 상태) AI에 "GOP 구조 알려줘" → 진행 문구 "📊 프레임 분석 실행 중…" → 답 후 필름스트립/GOP 화면에 결과가 채워짐; "HDR 메타데이터 이상 있어?" → HDR 자동 실행 → HDR 창을 열면 결과 재사용.
