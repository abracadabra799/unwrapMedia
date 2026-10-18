# Content Check Table UI & Terminology Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganize the Content Check window from vertical cards to a clean **top-bottom table dashboard**, adopting media-engineering terminology ("포맷 및 비트스트림 검증", "디코딩 검증"), promoting Container Conformance to a first-class table row, and removing the ambiguous `[규격 결과]` button.

**Architecture:**
- Top pane: `ContentCheckOverviewTable` component showing all applicable stages in a structured tabular grid (`구분 | 검사 항목 | 대상/포맷 | 상태 | 결과 요약 | 실행`).
- Bottom pane: Selected stage's detailed findings table, with Container Conformance, Codec Bitstream, Packet mapping, and Motion/Gainmap findings rendered uniformly.
- Workflow & Snapshot: All underlying execution logic, cancellation, file identity validation, and JSON export remain 100% backward compatible.

**Tech Stack:** Kotlin, Compose Desktop (Material3), JUnit tests.

**Spec:** `docs/superpowers/specs/2026-10-18-content-check-table-ui-design.md`

---

## Tasks

### Task 1: Presentation & Model Helpers (Terminology & Stage Mapping)

**Files:**
- Modify `app/src/main/kotlin/com/multiviewer/ui/ContentCheckStage.kt`
- Modify `app/src/main/kotlin/com/multiviewer/ui/ContentCheckPresentation.kt`
- Modify `app/src/test/kotlin/com/multiviewer/ui/ContentCheckPresentationTest.kt`

- [ ] **Step 1: Write failing presentation tests.** Assert updated group labels ("포맷 및 비트스트림 검증", "디코딩 검증"), first-class Container Conformance stage label/description, and stage target descriptions (e.g. `ISO-BMFF (mp42)`, `HEVC (hvc1)`, `1080p`).
- [ ] **Step 2: Run** focused tests; expect RED.
- [ ] **Step 3: Update `ContentCheckStage.kt` & `ContentCheckPresentation.kt`** with:
  - `CheckStage.CONTAINER_CONFORMANCE` added to `CheckStage` enum (or separate stage definition).
  - Updated category headers and KO/EN descriptions.
  - Stage target format resolution helper `checkStageTargetLabel(stage, type, root, summary)`.
- [ ] **Step 4: Run** focused tests; expect PASS.
- [ ] **Step 5: Commit** `refactor: update Content Check stage terminology and target presentation`.

---

### Task 2: Top Overview Table Component (`ContentCheckOverviewTable.kt`)

**Files:**
- Create `app/src/main/kotlin/com/multiviewer/ui/ContentCheckOverviewTable.kt`
- Create `app/src/test/kotlin/com/multiviewer/ui/ContentCheckOverviewTableTest.kt`

- [ ] **Step 1: Write failing unit tests for `ContentCheckOverviewTable`** (row generation, category grouping, selection, action enablement).
- [ ] **Step 2: Run** focused tests; expect RED.
- [ ] **Step 3: Implement `ContentCheckOverviewTable`** with Material3:
  - Clean table header: `구분 | 검사 항목 | 대상 / 규격 | 상태 | 결과 요약 | 실행`.
  - Group visual separation between "포맷 및 비트스트림 검증", "디코딩 검증", "확장 검사".
  - Color-coded badges for status and verdicts.
  - Selection border & background for the active row.
  - Inline progress indicator during running state.
- [ ] **Step 4: Run** focused tests; expect PASS.
- [ ] **Step 5: Commit** `feat: create Content Check stage overview table component`.

---

### Task 3: Findings Table Adaptation & Remove `[규격 결과]` Button

**Files:**
- Modify `app/src/main/kotlin/com/multiviewer/ui/ConformancePanel.kt` (or create tabular adapter `conformanceFindings(report)`)
- Modify `app/src/main/kotlin/com/multiviewer/ui/ContentCheckWindow.kt`

- [ ] **Step 1: Write failing tests** asserting Conformance findings can be mapped to tabular `ContentFinding` items with rule ID, source/clause, scope, and Hex jump ranges.
- [ ] **Step 2: Implement `conformanceFindings`** mapping `ConformanceReport` rules to `ContentFinding` so they render in `ContentFindingTable`.
- [ ] **Step 3: Remove the floating `[규격 결과]` button** from the detail pane header. Selecting `CONTAINER_CONFORMANCE` directly opens the conformance table.
- [ ] **Step 4: Add clean sub-tab toggle** in `VISUAL_DECODE` (`[ 프레임 디코딩 결과 | 비디오 패킷 목록 ]`).
- [ ] **Step 5: Run** focused tests; expect PASS.
- [ ] **Step 6: Commit** `feat: unify container conformance and video packets into tabular findings`.

---

### Task 4: Top-Bottom Split Layout & Workflow Integration

**Files:**
- Modify `app/src/main/kotlin/com/multiviewer/ui/ContentCheckWindow.kt`
- Create / update `app/src/test/kotlin/com/multiviewer/ui/ContentCheckTableUiIntegrationTest.kt`

- [ ] **Step 1: Write integration tests** verifying top-bottom layout transitions, row clicks changing detail views, individual rerun actions, cancel actions, and export completeness.
- [ ] **Step 2: Integrate `ContentCheckOverviewTable` in `ContentCheckWindow.kt`**:
  - Top pane (~220dp, full width) displaying the overview table.
  - Bottom pane (remaining height) displaying the selected stage's findings table.
- [ ] **Step 3: Verify** snapshot publishing, AI diagnosis context, and file change invalidation.
- [ ] **Step 4: Run** `./gradlew :app:compileKotlin` and focused tests; expect PASS.
- [ ] **Step 5: Commit** `feat: integrate top-bottom table dashboard layout into Content Check`.

---

## Branch Finish

- [ ] Run full test suite `./gradlew :app:test` and `git diff --check`.
- [ ] Update `docs/superpowers/HANDOFF.md`.
- [ ] Fast-forward merge `feat/content-check-table-ui` into `main`.
- [ ] Remove worktree and delete feature branch.
