# Image Integrity — Motion Photo Integration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Show the existing Motion Photo integrity analysis (Google XMP, Samsung SEF, HEIC `mpvd`, embedded-video FFmpeg decode) inside the Image Integrity window and in `check --decode` JSON, without duplicating any analysis logic.

**Architecture:** Reuse `MotionPhotoIntegrityAnalyzer.analyze(file, root): MotionPhotoIntegrityReport` (ui/MotionPhotoIntegrityAnalyzer.kt) unchanged. Extract the body of `MotionPhotoIntegrityWindow` into a shared composable used by both windows. Add a pure severity mapping (`SefIntegritySeverity` → `CheckStatus`) and a pure JSON renderer for the report.

**Tech Stack:** Kotlin/JVM, Compose Desktop Material3, kotlin.test/JUnit5.

## Global Constraints

- Work ONLY in `/Users/dong.kim/AndroidStudioProjects/multiViewer/.worktrees/image-integrity` on branch `feature/image-integrity`; confirm `git branch --show-current` before every commit. Never touch `/Users/dong.kim/AndroidStudioProjects/multiViewer` (main checkout holds someone else's uncommitted work). Never use `git stash`.
- Do not change `MotionPhotoIntegrityAnalyzer` behaviour or the existing Motion Photo Integrity Check menu/window behaviour (the standalone window must look the same after the extraction).
- Severity mapping (exact): `PASS→PASS`, `INFO→INFO`, `WARNING→WARN`, `CRITICAL→FAIL`, `SKIPPED→SKIP`.
- Motion-photo detection must be the same predicate the Analysis/Motion Photo menu already uses (Main.kt `hasMotionPhoto`), moved into one shared function — not a second copy.
- Motion-photo analysis runs only on "Start inspection" (GUI) / `--decode` (CLI), because it decodes the whole embedded video. When detected but not run, JSON says `"status": "NOT_RUN"`; when not detected, the `motionPhoto` key is absent.
- Material3 `Text` only. Commit trailer EXACTLY `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Gradle always with a timeout: `timeout 900 ./gradlew ...`.

---

### Task 10: Shared detection + shared report composable + window integration

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoReportContent.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityWindow.kt` (use the shared composable)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt` (add `hasMotionPhotoData` + `toCheckStatus` only; analyzer logic untouched)
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt` (menu gate calls `hasMotionPhotoData`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageIntegrityWindow.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/integrity/JpegIntegrity.kt` (INFO detail suffix)
- Test: `app/src/test/kotlin/com/multiviewer/ui/MotionPhotoIntegrationTest.kt`

**Interfaces produced:**
- `fun hasMotionPhotoData(root: BoxNode): Boolean` — body moved verbatim from Main.kt's `val hasMotionPhoto = currentTab?.root?.let { r -> ... }` lambda (keep its comment).
- `fun SefIntegritySeverity.toCheckStatus(): CheckStatus` — mapping from Global Constraints.
- `@Composable fun MotionPhotoReportContent(report: MotionPhotoIntegrityReport, modifier: Modifier = Modifier)` — exactly the current `else ->` branch content of `MotionPhotoIntegrityWindow` (badge row + LazyColumn of sections). `motionPhotoFormatLabel` moves with it (internal).

- [ ] **Step 1: Tests first** — `MotionPhotoIntegrationTest`:
  - mapping: each of the 5 severities maps as specified.
  - `hasMotionPhotoData`: false for a plain JPEG tree (`parseFile` of `jpegBytes()` fixture written to a temp file); true for a tree containing a node of type `"mpvd"` (construct `BoxNode(type="root", offset=0, headerSize=0, size=10, children=listOf(BoxNode("mpvd", 0, 8, 10)))`) and for one with `"EmbeddedVideoData"`.
  Run, see RED (unresolved references).
- [ ] **Step 2: Implement** `hasMotionPhotoData` and `toCheckStatus` in MotionPhotoIntegrityAnalyzer.kt; replace the Main.kt lambda with `currentTab?.root?.let { hasMotionPhotoData(it) } ?: false`. GREEN.
- [ ] **Step 3: Extract** `MotionPhotoReportContent` and make `MotionPhotoIntegrityWindow` call it (loading/error/not-detected branches stay in the window).
- [ ] **Step 4: Image Integrity window**
  - State: `var motion by remember(tab.file) { mutableStateOf<MotionPhotoIntegrityReport?>(null) }`; `val motionDetected = remember(tab.root) { tab.root?.let { hasMotionPhotoData(it) } ?: false }`.
  - Button label "디코딩 검사 시작 / Start decode check" → "검사 시작 / Start inspection".
  - On start: inside the same job, after `inspectImageDecode`, if `motionDetected`: `motion = withContext(Dispatchers.IO) { MotionPhotoIntegrityAnalyzer.analyze(tab.file, root) }` (wrap in try/catch: CancellationException rethrown; other exceptions → message "모션포토 분석 실패: …"). Reset `motion = null` at start. Status message while running: "디코딩·모션포토 검사 중…" when detected.
  - Header: third verdict `모션포토: <label>` only when `motionDetected`; label = "미검사/Not run" if `motion == null`, else `checkStatusLabel(motion.overallSeverity.toCheckStatus())` with `checkStatusColor`.
  - Tabs: add third tab "모션포토 / Motion photo" only when `motionDetected`; its content: if `motion == null` → text "'검사 시작'을 눌러 모션포토 영상 검사를 실행하세요. (영상 전체를 디코딩하므로 시간이 걸릴 수 있습니다)"; else `MotionPhotoReportContent(motion)`.
  - Cancel note: the analyzer's FFmpeg call is not interruptible (30 s internal timeout). Cancelling discards its result; do not attempt to change the analyzer.
  - Save analysis case passes `motion` along (Task 11 adds the parameter; in this task keep the call compiling as-is).
- [ ] **Step 5: JPEG trailer hint** — in `JpegIntegrity.trailingItem`, when the INFO kinds include SEF trailer or embedded motion photo video, append " — video integrity: see the Motion photo tab / `motionPhoto` JSON" to the detail. Update/add a JpegIntegrityTest assertion only if an existing test checks that detail text.
- [ ] **Step 6: Verify** `timeout 900 ./gradlew :app:compileKotlin -q` and `timeout 900 ./gradlew :app:test -q`. Commit: `feat(integrity): motion photo tab in Image Integrity window`.

---

### Task 11: `motionPhoto` in CLI JSON and analysis case

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/cli/ImageIntegrityJson.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/cli/CheckFile.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageIntegrityWindow.kt` (pass `motion` to the case builder)
- Test: `app/src/test/kotlin/com/multiviewer/cli/ImageIntegrityJsonTest.kt` (extend)

**Interfaces:**
- `fun motionPhotoJson(detected: Boolean, report: MotionPhotoIntegrityReport?): JsonValue?` — `null` when `!detected`; `{"status":"NOT_RUN"}` when detected and `report == null`; otherwise:
  ```
  { "status": "<overallSeverity.toCheckStatus().name>",
    "detectedFormats": ["SAMSUNG_SEF", ...],
    "checks": [ { "section": "...", "status": "PASS|INFO|WARN|FAIL|SKIP", "label": "...", "detail": "..." }, ... ] }
  ```
  `checks` flattens, in this order and with these section names: `googleXmpChecks` → "google_xmp", `sefSection.structuralChecks` → "sef_structure", `sefSection.semanticChecks` → "sef_semantic", `sefSection.directoryEntries` → "sef_directory" (label = entry name or "#<entryIndex>", status = row status, detail = a short "marker … offset … length …" string built from the row's existing fields — read `SefDirectoryEntryRow` first), `appleMpvdChecks` → "heic_mpvd", `decodeChecks` → "video_decode".
- `imageIntegrityJson(structure, decode, motionPhoto: JsonValue? = null)` appends `"motionPhoto"` when non-null; `buildImageIntegrityCaseJson(file, structure, decode, motion: MotionPhotoIntegrityReport? = null, motionDetected: Boolean = false)` passes it through.
- `checkFile`: for images, compute `detected = hasMotionPhotoData(root)`; when `decode && detected`, run `MotionPhotoIntegrityAnalyzer.analyze(file, root)`; render with `motionPhotoJson(detected, report)`.

- [ ] **Step 1: Tests first** (pure, no FFmpeg): build a `MotionPhotoIntegrityReport` by hand (one google check WARNING, one decode check CRITICAL, no SEF, `overallSeverity = CRITICAL`) and assert: status "FAIL", detectedFormats rendered, a check with section "google_xmp" status "WARN" and one with section "video_decode" status "FAIL". Also: `motionPhotoJson(false, null) == null`; `motionPhotoJson(true, null)` renders `"status": "NOT_RUN"`. And `checkFile` on the plain `jpegBytes()` fixture has no `"motionPhoto"` key. RED → implement → GREEN.
- [ ] **Step 2: Wire** CheckFile and the window's save-case call.
- [ ] **Step 3: Verify** full suite; then manual CLI on the real samples (paths given in the dispatch): Samsung `motion.jpg` and `real.heic` with `--decode` → `motionPhoto` present with detectedFormats and a `video_decode` PASS; `real.jpg` → no `motionPhoto`; `motion.jpg` without `--decode` → `"status": "NOT_RUN"`. Commit: `feat(cli): motion photo integrity in image check JSON`.
- [ ] **Step 4: README** — one sentence in the Image Integrity paragraph: 모션포토(삼성 SEF·구글 XMP·HEIC mpvd)가 감지되면 같은 창의 **모션포토** 탭과 CLI `--decode` JSON의 `motionPhoto`에서 내장 영상까지 검사합니다. Include in the same commit.
