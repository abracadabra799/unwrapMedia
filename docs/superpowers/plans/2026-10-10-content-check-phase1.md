# Content Check Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** One Analysis → 컨텐츠 검사 window that runs and shows every applicable check (structure, image decode, motion photo, video decode/packets), replacing four menus.

**Architecture:** Move the bodies of the existing Image/Video integrity windows into panel composables, add a pure planner that decides tabs and heavy steps per media type, and build one window shell that orchestrates the runs. Analysis code (`ImageIntegrityChecker`, `inspectImageDecode`, `MotionPhotoIntegrityAnalyzer`, `inspectVideoIntegrity`) is not changed.

**Spec:** `docs/superpowers/specs/2026-10-10-content-check-design.md`

## Global Constraints

- Work ONLY in `/Users/dong.kim/AndroidStudioProjects/multiViewer/.worktrees/content-check` on branch `feature/content-check`; confirm `git branch --show-current` before every commit. Never touch `/Users/dong.kim/AndroidStudioProjects/multiViewer` (main checkout). Never use `git stash`.
- Menu label exactly `컨텐츠 검사` (KO) / `Content Check` (EN), no ellipsis; shortcut ⌘⇧C (`AppKeyShortcut(Key.C, meta = true, shift = true)`).
- Removed menus: `menuCheckStructure`, `menuVideoIntegrity`, `menuImageIntegrity`, `menuMotionPhotoIntegrityCheck`. SEF Integrity Check stays.
- Do not change analysis behaviour or any analyzer/checker code. Behaviour of moved panels must be identical (texts, hex highlight, packet table, diagnostics expansion).
- Material3 `Text` only. Gradle always with a timeout (`timeout 900 ./gradlew ...`). Commit messages: blank line before trailer; trailer EXACTLY `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 1: Pure planner + non-image structure items

**Files:** Create `app/src/main/kotlin/com/multiviewer/ui/ContentCheckPlan.kt`; Test `app/src/test/kotlin/com/multiviewer/ui/ContentCheckPlanTest.kt`.

**Interfaces produced:**
```kotlin
enum class ContentTab { STRUCTURE, IMAGE_DECODE, MOTION_PHOTO, VIDEO_DECODE, VIDEO_PACKETS }
enum class HeavyStep { IMAGE_DECODE, MOTION_PHOTO, VIDEO_INTEGRITY }
fun contentTabs(type: MediaType, motionDetected: Boolean): List<ContentTab>
fun heavySteps(type: MediaType, motionDetected: Boolean): List<HeavyStep>
/** Structure report for any media type: images → ImageIntegrityChecker.check; others → parser warnings only. */
fun contentStructureReport(file: File, root: BoxNode, type: MediaType): ImageStructureReport
```
Rules: IMAGE → [STRUCTURE, IMAGE_DECODE] + MOTION_PHOTO if detected; steps [IMAGE_DECODE] + MOTION_PHOTO if detected. VIDEO → [STRUCTURE, VIDEO_DECODE, VIDEO_PACKETS]; steps [VIDEO_INTEGRITY]. AUDIO, RAW_PIXEL, UNKNOWN → [STRUCTURE]; steps []. For non-images `contentStructureReport` returns `ImageStructureReport(format = type.name, items = ImageIntegrityChecker.parserWarningItems(root))` (make `parserWarningItems` accessible if it is not already — keep its id/semantics).

- [ ] Tests first (RED): all five types × motion flag for both functions; `contentStructureReport` on a synthetic non-image root with one warning node → one WARN `parser.warning` item with that node's offset/size; with no warnings → single PASS `parser.warnings`; on an image (fixture `jpegBytes()` from `com.multiviewer.parser.integrity` test fixtures written to a temp .jpg and parsed) → same items as `ImageIntegrityChecker.check`.
- [ ] Implement (GREEN). Commit `feat(content-check): media-type planner for tabs and steps`.

### Task 2: Extract panels (no behaviour change)

**Files:** Create `ui/ImageCheckPanels.kt`, `ui/VideoCheckPanels.kt`; Modify `ui/ImageIntegrityWindow.kt`, `ui/VideoIntegrityWindow.kt` to call them (both windows still exist and compile after this task).

- `ImageCheckPanels.kt`: move `checkStatusColor`, `decodeStatusColor`, `checkStatusLabel`, `decodeStatusLabel`, `headerDecodeLabel`, `StructurePanel` → rename `ImageStructurePanel`, `DecodePanel` → `ImageDecodePanel` (make them `internal`). Same parameters.
- `VideoCheckPanels.kt`: from `VideoIntegrityWindow` extract
  - `internal fun videoStatusLabel(value: IntegrityStatus, ko: Boolean): String`
  - `@Composable internal fun VideoDecodePanel(report: VideoIntegrityReport, ko: Boolean, onShowPackets: () -> Unit, modifier: Modifier)` — the `selectedTab == 0` branch (status line, notes, truncation note, diagnostics list with expansion; "패킷 목록에서 직접 확인" calls `onShowPackets`).
  - `@Composable internal fun VideoPacketPanel(report: VideoIntegrityReport, tab: TabState, ko: Boolean, modifier: Modifier)` — the `else` branch (status, header row, packet logs, selectable packet rows with the same hex-highlight bounds check). Selected-packet and expanded-diagnostic state live inside the panels (`remember(report)`).
- [ ] `timeout 900 ./gradlew :app:compileKotlin -q` and full `:app:test`. Commit `refactor(ui): extract image and video check panels`.

### Task 3: Content Check window, menu, cleanup

**Files:** Create `ui/ContentCheckWindow.kt`; Modify `Main.kt`, `ui/I18n.kt`, `cli/ImageIntegrityJson.kt` (delete `buildImageIntegrityCaseJson` + its tests), `ui/AnalysisWindows.kt` (delete `StructureCheckWindow` only if nothing else uses it), README.md; Delete `ui/ImageIntegrityWindow.kt`, `ui/VideoIntegrityWindow.kt`, `ui/MotionPhotoIntegrityWindow.kt`.

Window `ContentCheckWindow(tab: TabState, language: AppLanguage, onCloseRequest: () -> Unit)`:
- Title `컨텐츠 검사 — <name>` / `Content Check — <name>`; size ~1080×780.
- State keyed on `tab.file`: `structure`, `structureError`, `imageDecode`, `motion`, `motionError`, `video: VideoIntegrityReport?`, `job`, `running`, `runId`, `message`, `selectedTab`.
- `LaunchedEffect(tab.file, tab.root)`: `structure = withContext(IO) { contentStructureReport(tab.file, root, tab.type) }`.
- `val motionDetected = remember(tab.root) { tab.type == MediaType.IMAGE && tab.root?.let(::hasMotionPhotoData) == true }`; `tabs = contentTabs(tab.type, motionDetected)`; `steps = heavySteps(...)`.
- Header row: media type label; verdicts for each tab present (structure overall; image decode via `headerDecodeLabel`; motion via `verdictStatus()` / motionError → Failed; video decode/packet via `videoStatusLabel`).
- 검사 시작 (disabled when `steps` empty — show note "이 파일 형식은 1단계에서 구조 검사만 지원합니다" / "Only structure checks are available for this type yet"): run steps in order inside one job, reusing exactly the existing logic from ImageIntegrityWindow (stale-run guard `runId`/`current()`, `awaitDetached` for motion, motionError handling, message lifecycle) and VideoIntegrityWindow (progress callback messages "패킷 읽는 중: N" / "디코딩 중: N 프레임", `LinearProgressIndicator` while running). Cancel cancels the job immediately (same UX as image window).
- 분석 케이스 저장: FileDialog, default name `<name>-case.json`; write with `CREATE_NEW` (never overwrite) the result of `buildAnalysisCaseJson(file, collectWarnings(root), buildMediaSummary(root, file), integrityReport = video, imageIntegrity = <imageIntegrityJson(structure, imageDecode, motionPhotoJson(motionDetected, motion, file, motionError)) for images, else null>)` on IO.
- JSON 복사: `ClipboardUtil.copyToClipboard(buildCheckJson(file, warnings, video, imageJsonOrNull))`, transient "복사됨 / Copied" message.
- Tabs: `TabRow` over `tabs` with labels 구조/Structure, 디코딩/Decode, 모션포토/Motion photo, 영상 디코딩/Video decode, 패킷 매핑/Packet mapping; content: `ImageStructurePanel` (for all types — onSelect highlights hex), `ImageDecodePanel`, motion tab (same placeholder/error/`MotionPhotoReportContent` as the image window), `VideoDecodePanel` (placeholder text before a run; `onShowPackets` switches to the packets tab), `VideoPacketPanel`.

Main.kt: add `var contentCheckTab by remember { mutableStateOf<TabState?>(null) }`; Analysis menu: first item `I18n.menuContentCheck(language)`, enabled `hasActiveFile`, ⌘⇧C, `onClick = { contentCheckTab = currentTab }`; remove the four menu items, their state vars and window blocks; render `contentCheckTab?.let { ContentCheckWindow(it, language) { contentCheckTab = null } }`.
I18n: add `fun menuContentCheck(lang) = if (KO) "컨텐츠 검사" else "Content Check"`; delete the four removed menu functions if unused.
README: replace the "동영상은 **분석 → 영상 무결성 검사…**" and "이미지는 **분석 → 이미지 무결성 검사…**" window descriptions with one paragraph describing **분석 → 컨텐츠 검사** (tabs per type, 검사 시작 runs decode/motion/video, Hex highlight, case save, JSON copy), keeping the existing technical details (limits, Skia rationale, motionPhoto JSON statuses, video log/packet limits).

- [ ] Tests: none new beyond Task 1 (UI); remove tests of deleted functions only. Run `timeout 900 ./gradlew :app:compileKotlin -q` and full `:app:test`.
- [ ] Launch smoke: `./gradlew :app:run` is GUI — do not attempt to drive it; report that GUI verification is left to the controller.
- [ ] Commit `feat(content-check): unified Content Check window replaces four menus`.

---

### Task 4: AI diagnosis linked to Content Check

**Files:** Create `ui/ContentCheckSnapshot.kt` (data class + `contentCheckPromptSection(snapshot, file): String`); Modify `ui/AppState.kt` (TabState `contentCheck`, AppState `contentCheckTab` moved from Main.kt local state so other UI can open the window), `ui/ContentCheckWindow.kt` (publish snapshot; "AI 진단 / AI diagnosis" button), `cli/AiDiagnosticPromptBuilder.kt` (optional `contentCheck: ContentCheckSnapshot?` param → new section), `ui/AnalysisWindows.kt` (`AiPromptPreviewWindow` passes `tab.contentCheck`), `Main.kt` (remove AI 진단 실행 menu item; use `appState.contentCheckTab`), `ui/ImageInspectorUI.kt` (chips per spec addendum), `ui/I18n.kt` (remove `menuGenerateAiPrompt` if unused); Test `ui/ContentCheckSnapshotTest.kt`.

`data class ContentCheckSnapshot(val structure: ImageStructureReport?, val imageDecode: ImageDecodeReport?, val motionDetected: Boolean, val motion: MotionPhotoIntegrityReport?, val motionError: String?, val video: VideoIntegrityReport?)`

Section text (Korean, like the rest of the prompt), exact header `### [컨텐츠 검사 결과 (앱이 직접 검증한 사실)]`, followed by an instruction line telling the model these are verified facts from byte-level checks and real decoding, then the sub-sections in the spec addendum order. Use `scrubPaths(text, file)` from cli/ImageIntegrityJson.kt on every detail/log line. Limits: 30 items per list, 20 log lines per decoder, with "… N개 생략" lines.

- [ ] Tests first (RED): structure with PASS/INFO/WARN/FAIL items → only WARN/FAIL listed with offsets; 35 FAIL items → 30 listed + "5개 생략"; snapshot with no decode/video → "미실행" lines; a log line containing `<java.io.tmpdir>/x.mp4` and the file's absolute path → scrubbed; `buildPrompt(..., contentCheck = snapshot)` contains the header, and `buildPrompt` without it does not.
- [ ] Implement; wire window/button/menu/chips. Full suite + compile. Commit `feat(content-check): feed verified results into AI diagnosis; drop standalone AI menu`.
