# Content Check Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Content Check's automatic and bundled runs with a compact, sequential, per-check workflow that publishes trustworthy results as each check finishes.

**Architecture:** A pure stage catalog and a cancellable run controller own applicability, order, status, and file identity. `ContentCheckWindow` renders their state, delegates to existing analyzers, and keeps reports independent. The existing `ConformanceReport` remains a rule inventory, with normative coverage separated from structural observations; the codec stage stays unsupported until the companion HEVC runner is installed.

**Tech Stack:** Kotlin, Compose Desktop Material3, coroutines, JUnit, existing parsers and ffmpeg/ffprobe wrappers.

**Spec:** `docs/superpowers/specs/2026-10-17-content-check-workflow-design.md`

## Global Constraints

- No Content Check job starts on popup open; ordinary tab parsing may continue.
- UI copy is Korean and English; use Material3 `Text` only.
- Default window is 1080×780 with a 260–300 dp checklist; checklist and detail scroll independently.
- File reads and saves stay off the UI thread; every job is cancellable and pinned to its opening tab.
- Gate video sample reads on `MediaType.VIDEO` and a matching codec configuration box; HEIC motion photos are excluded.
- No new heavy dependencies; preserve existing JSON fields, `CREATE_NEW` saves, and path scrubbing.
- Empty, truncated, unsupported, failed, or cancelled coverage never yields an overall PASS.
- Motion/Gain Map analyzers and the separate Gain Map Analyzer are reused, not duplicated.

## Review Focus

- Tree arrives after popup opens: rows become runnable without auto-running (Task 2 test `treeArrivalDoesNotStartChecks`).
- A later stage fails after an earlier PASS: the earlier report remains visible and the next independent stage runs (Task 3 test `failurePreservesPriorResultAndContinues`).
- File changes between ordinary parse and check: compare the captured identity with `tab.parsedIdentity` and invalidate on mismatch; document that equal size and timestamp replacement is not detected by the existing identity scheme (Task 3 test `parsedIdentityMismatchInvalidatesAll`).
- An image contains embedded `moov`/`hvcC`: codec row remains unavailable because media type is IMAGE (Task 1 test `heicMotionPhotoDoesNotOfferVideoCodec`).
- Export/AI occurs during a queued or cancelled stage: completed evidence is retained, pending or cancelled coverage is explicit, and no clean verdict is invented (Task 5 test `partialRunExportAndPromptDoNotClaimPass`).

---

### Task 1: Stage catalog, applicability, and bilingual copy

**Files:** Modify `app/src/main/kotlin/com/multiviewer/ui/ContentCheckPlan.kt`; create `app/src/main/kotlin/com/multiviewer/ui/ContentCheckStage.kt`; modify `app/src/test/kotlin/com/multiviewer/ui/ContentCheckPlanTest.kt`.

**Interfaces:** Produce `enum class CheckStage { STRUCTURE, CODEC, VISUAL_DECODE, AUDIO_DECODE, MOTION_PHOTO, GAINMAP }`, `enum class CheckAvailability { AVAILABLE, UNSUPPORTED, NOT_APPLICABLE, LOADING }`, `data class CheckStageSpec(val stage: CheckStage, val availability: CheckAvailability, val reasonKo: String?, val reasonEn: String?)`, and `fun contentCheckStages(type: MediaType, root: BoxNode?, motionDetected: Boolean, gainmapDetected: Boolean, hasAudioTrack: Boolean, codecRunnerAvailable: Boolean): List<CheckStageSpec>`. Add `fun checkStageDescription(stage: CheckStage, ko: Boolean): String` with the exact copy in the spec. Keep old `ContentTab` entry points until Task 4 migrates callers.

- [ ] **Step 1: Write failing tests.** Assert six descriptions exactly match the spec, `contentCheckStages(VIDEO, root, false, false, true, false)` orders structure/codec/visual/audio, marks codec UNSUPPORTED, and `heicMotionPhotoDoesNotOfferVideoCodec` marks CODEC NOT_APPLICABLE for IMAGE even if the tree contains `hvcC`. Assert absent audio/motion/gain map rows are NOT_APPLICABLE or hidden as specified.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.ui.ContentCheckPlanTest'`; expect RED.
- [ ] **Step 3: Implement** the catalog and short KO/EN descriptions; codec availability is an injected capability, never guessed from a box alone.
- [ ] **Step 4: Run** the focused test; expect PASS.
- [ ] **Step 5: Commit** `feat: model content check stages and applicability`.

### Task 2: Explicit execution state and no work on open

**Files:** Create `app/src/main/kotlin/com/multiviewer/ui/ContentCheckRunState.kt`; create `app/src/test/kotlin/com/multiviewer/ui/ContentCheckRunStateTest.kt`; modify `ContentCheckWindow.kt`.

**Interfaces:** Produce `enum class CheckRunStatus { NOT_RUN, QUEUED, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED }`, `data class CheckProgress(val completed: Long, val total: Long?, val unit: String)`, `data class CheckStageState(val status: CheckRunStatus, val progress: CheckProgress? = null, val reason: String? = null)`, and `fun queueApplicableStages(specs: List<CheckStageSpec>): Map<CheckStage, CheckStageState>`. Run state is distinct from `ConformanceStatus` and report verdict.

- [ ] **Step 1: Write failing tests.** `treeArrivalDoesNotStartChecks` verifies catalog refresh leaves all AVAILABLE rows NOT_RUN; `queueApplicableStages` queues only available rows in spec order; a COMPLETED state may carry a FAIL report verdict; progress with unknown total has `total == null`. In Task 4 desktop QA, also observe that simply opening the window starts no check.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.ui.ContentCheckRunStateTest'`; expect RED.
- [ ] **Step 3: Implement** state and remove `ContentCheckWindow`'s structure/conformance `LaunchedEffect(tab, tab.root, type)` work and eager gain-map validation. On open, only derive applicability; `root == null` displays Loading and disables run actions without a failure report.
- [ ] **Step 4: Run** focused state and `ContentCheckSnapshotTest`; expect PASS.
- [ ] **Step 5: Commit** `feat: start content checks only on explicit action`.

### Task 3: Sequential, cancellable runner with immediate results

**Files:** Create `app/src/main/kotlin/com/multiviewer/ui/ContentCheckRunner.kt` and `app/src/test/kotlin/com/multiviewer/ui/ContentCheckRunnerTest.kt`; modify `ContentCheckWindow.kt` and `ContentCheckSnapshot.kt`.

**Interfaces:** `class ContentCheckRunner(private val execute: suspend (CheckStage, (CheckProgress) -> Unit) -> Unit)` exposes `suspend fun run(stages: List<CheckStage>, onState: (CheckStage, CheckStageState) -> Unit)`; injected execution lets tests control failure/cancellation. Window adapts existing `contentStructureReport`, `contentAudioStructureReport`, `inspectImageDecode`, `inspectVideoIntegrity`, `inspectAudioIntegrity`, `MotionPhotoIntegrityAnalyzer`, and existing gain-map validation. Add `val stages: Map<CheckStage, CheckStageState> = emptyMap()` to `ContentCheckSnapshot`. A stage publishes its report before its state becomes COMPLETED. Capture `ContentFileIdentity` before each run, compare with `tab.parsedIdentity` and before publishing; invalidate all on mismatch. The controller never starts the next stage after cancellation, but continues independent stages after an ordinary stage error.

- [ ] **Step 1: Write failing tests.** Assert exact all-run order; `failurePreservesPriorResultAndContinues`; cancellation marks active CANCELLED and leaves later QUEUED without executing them; rerun changes only target stage and dependent conformance; `parsedIdentityMismatchInvalidatesAll`; unknown total progress stays indeterminate. Use fake executor, no ffmpeg in unit tests.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.ui.ContentCheckRunnerTest'`; expect RED.
- [ ] **Step 3: Implement** controller and window adapter with `runId`/job guards, bounded progress updates, per-stage errors, and file checks on `Dispatchers.IO`. Structure recomputes the conformance inventory; other diagnostics cannot change normative rules. Reuse existing video packet enumeration inside `inspectVideoIntegrity`.
- [ ] **Step 4: Run** runner, snapshot, and `VideoIntegrityTest`; expect PASS.
- [ ] **Step 5: Commit** `feat: run content checks sequentially with isolated results`.

### Task 4: Compact checklist and evidence pane

**Files:** Modify `ContentCheckWindow.kt`, `ContentCheckPresentation.kt`, `Main.kt`; modify `ContentCheckPresentationTest.kt`.

**Interfaces:** Render `CheckStageSpec` and `CheckStageState` from Tasks 1–3. `ContentTab` may remain as an internal detail target, but CONFORMANCE and VIDEO_PACKETS have no executable top-level row. `ContentCheckWindow` still accepts `initialTab` temporarily for source compatibility; remove the Motion Photo menu shortcut in `Main.kt` and select the conditional row from inside Content Check.

- [ ] **Step 1: Write failing presentation tests.** Check bilingual status/action labels, unknown-total progress copy, completed WARN/FAIL distinct from execution failure, and a manually selected detail row preserved when later stages start.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.ui.ContentCheckPresentationTest'`; expect RED.
- [ ] **Step 3: Implement** 1080×780 two-pane UI, row descriptions/actions, Run all/Cancel, per-row verdict/count/progress, finding filters, and packet subsection under video. Remove redundant status/tab navigation and duplicate Motion Photo menu entry; keep Hex navigation and separate Gain Map Analyzer.
- [ ] **Step 4: Run** focused UI-model tests and `./gradlew :app:compileKotlin`; expect PASS.
- [ ] **Step 5: Commit** `feat: show content checks as a compact checklist`.

### Task 5: Coverage, export, AI snapshot, and reuse

**Files:** Modify `ContentCheckSnapshot.kt`, `ContentCheckWindow.kt`, `ConformancePanel.kt`; create `app/src/test/kotlin/com/multiviewer/ui/ContentCheckWorkflowIntegrationTest.kt`.

**Interfaces:** Add JSON `workflow: { version: 1, stages: ... }` without removing old keys. Preserve `ContentCheckSnapshot`'s existing fields and capped/path-scrubbed prompt. Stage-aware summary counts normative rule PASS/FAIL/WARN/NOT_CHECKED/NOT_APPLICABLE separately from structural observations and decode diagnostics. A missing codec runner is NOT_CHECKED, not PASS.

- [ ] **Step 1: Write failing tests.** `partialRunExportAndPromptDoNotClaimPass` checks queued/cancelled coverage in JSON and prompt; no-result filter shows a useful empty state; stale identity disables evidence; motion/gain-map rows reuse a completed report rather than reexecuting; legacy JSON keys remain; `CREATE_NEW` still rejects overwrite.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.ui.ContentCheckWorkflowIntegrationTest'`; expect RED.
- [ ] **Step 3: Implement** additive export/AI metadata and summary semantics. Keep clipboard/save off the UI thread and existing case-save behavior.
- [ ] **Step 4: Run** focused tests, `./gradlew :app:test`, and desktop QA matrix in the spec (image, motion/gain map, video with/without audio, audio, unsupported/truncated, long cancelled run); expect PASS and no auto-run on open. Record any display limitation explicitly.
- [ ] **Step 5: Commit** `feat: report content check coverage and preserve evidence`.

## Branch finish

- [ ] Review whole diff against both specs and AGENTS.md; verify no false PASS and no duplicate scan.
- [ ] Run `./gradlew :app:test` and `git diff --check`; fix any nonflaky failure and commit.
- [ ] Integrate only after the HEVC plan is implemented and reviewed; fast-forward local `main`, with no push unless the user explicitly authorizes it.
