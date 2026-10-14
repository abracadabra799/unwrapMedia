# Specification Conformance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show what published/implementation rules were actually checked for the active file, with evidence and an honest non-green result for unsupported, empty, skipped, or failed inspections.

**Architecture:** Wrap the existing byte-level `ImageIntegrityChecker` and `AudioStructureChecker` results in a versioned rule inventory. The inventory supplies check scope and source references; a normalizer supplies one result per rule and preserves byte locations. A new Content Check tab displays the result and links located findings to existing selection-driven Hex highlighting. Start with existing format checkers, explicitly label their rules as implementation checks, and give PNG rules a published PNG 3 reference after verifying the raw conditions. Do not claim complete normative conformance.

**Tech Stack:** Kotlin 2, Compose Desktop Material3, existing byte readers, kotlin.test. No new dependency.

**Spec:** `docs/superpowers/specs/2026-10-14-contextual-media-analysis-design.md`, section 1.

## Global Constraints

- User-visible text must be Korean and English.
- Empty, truncated, unsupported, failed, cancelled, or all-skipped input must not display a green pass.
- Parser warnings, decode results, and conformance results stay distinct.
- Byte offsets link through existing tree-driven selection and Hex highlighting; do not add always-on overlays.
- Heavy reads run off the UI thread and cancel when the window closes.
- Samsung internal rules are deferred until the user provides documents and fixtures.

## Review Focus

1. An unknown file whose parser emits no warning: `NOT_CHECKED`, not PASS (Task 2 test).
2. A truncated image with some passing checks: FAIL remains the overall status (Task 2 test).
3. A known format with a skipped conditional check: skipped count is visible (Task 2 test).
4. Two findings with the same rule ID: worst status and all locations remain visible (Task 2 test).
5. A stale active tab after switching files: report remains pinned to its original tab (Task 3 UI review).

---

### Task 1: Rule inventory and report model

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/conformance/ConformanceModel.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/conformance/ConformanceRuleCatalog.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/conformance/ConformanceRuleCatalogTest.kt`

**Interfaces:**
- `enum class ConformanceStatus { PASS, FAIL, WARN, NOT_CHECKED, NOT_APPLICABLE }`
- `data class ConformanceRule(val id: String, val title: String, val source: String, val reference: String?, val scope: String)`
- `data class ConformanceFinding(val rule: ConformanceRule, val status: ConformanceStatus, val details: List<String>, val ranges: List<LongRange>)`
- `data class ConformanceReport(val format: String, val profile: String, val findings: List<ConformanceFinding>, val limitations: List<String>)`
- `fun rulesFor(format: String): List<ConformanceRule>` returns stable known inventories for existing format checker IDs, excluding `parser.*` as a separate category.
- `ConformanceReport.overall` is FAIL if any FAIL; WARN if any WARN; NOT_CHECKED if none checked or any NOT_CHECKED; PASS only when at least one executed rule and all applicable rules pass.

- [ ] **Step 1: Write failing tests** for catalog uniqueness and overall status on empty, all-skipped, fail-plus-pass, and complete-pass reports.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.ConformanceRuleCatalogTest'`; confirm failures are the missing model/catalog.
- [ ] **Step 3: Implement model and catalog.** Use the IDs emitted by `parser/integrity/*`; mark non-PNG rules as `unwrapMedia structural check` and PNG rules against `W3C PNG Third Edition`. Document unsupported codec bitstream syntax in `limitations`.
- [ ] **Step 4: Re-run focused tests; confirm pass.**
- [ ] **Step 5: Commit** `feat: add conformance rule model and catalog`.

### Task 2: Normalize actual check results with no false pass

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/conformance/ConformanceAnalyzer.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/integrity/ImageIntegrityModels.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/conformance/ConformanceAnalyzerTest.kt`

**Interfaces:**
- `fun analyzeConformance(format: String, items: List<IntegrityCheckItem>, inspectionError: String? = null): ConformanceReport`
- `CheckStatus.SKIP` maps to `NOT_CHECKED`; parser warnings are collected in a separate limitations/evidence section, never as a spec rule.
- Unknown IDs remain visible as additional `unwrapMedia structural check` findings and are never silently dropped.
- `ImageStructureReport.overall` yields SKIP for empty or all-SKIP items, consistent with `AudioStructureReport.overall`.

- [ ] **Step 1: Write failing tests** using real `PngIntegrity` results from a valid fixture and a deliberately truncated copy, plus unknown/all-skipped, duplicate-ID and parser-warning-only cases. Assert expected overall, checked/unchecked counts, and byte ranges.
- [ ] **Step 2: Run focused tests and observe the expected failures.**
- [ ] **Step 3: Implement normalization and fix image empty/all-SKIP overall.** A rule not emitted by the checker becomes `NOT_CHECKED`; do not infer PASS from its absence. Preserve every observation for repeated IDs.
- [ ] **Step 4: Re-run focused tests and existing image/audio integrity tests.**
- [ ] **Step 5: Commit** `feat: normalize conformance findings with coverage`.

### Task 3: Content Check integration and evidence navigation

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ContentCheckPlan.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ContentCheckWindow.kt`
- Create: `app/src/main/kotlin/com/multiviewer/ui/ConformancePanel.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ContentCheckSnapshot.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ContentCheckPlanTest.kt`

**Interfaces:**
- Add `ContentTab.CONFORMANCE` to every parsed media type.
- `ConformancePanel(report, language, onSelectRange)` shows headline, counts, source/scope, per-rule result and limitations; `onSelectRange(offset,length)` uses the Content Check window's existing Hex navigation callback pattern.
- The active tab's `ContentCheckSnapshot` stores the report so AI can use deterministic findings later.

- [ ] **Step 1: Write failing plan tests** for the Conformance tab on image/video/audio and an unsupported/raw file.
- [ ] **Step 2: Run focused tests and confirm red.**
- [ ] **Step 3: Implement UI/state integration.** Calculate the report from the existing structure result in the same cancellable IO workflow. For video, show honest unverified profile until normative rules exist. No filesystem access in composition.
- [ ] **Step 4: Compile and run focused tests; manually inspect PNG, truncated PNG, JPEG motion photo, MP4, and unknown file on macOS/Windows when available.**
- [ ] **Step 5: Commit** `feat: show conformance coverage in content check`.

### Task 4: Whole-feature review

- [ ] Run `./gradlew :app:test` and confirm only the documented intermittent ThumbnailDiskCacheTest failure, if it occurs; rerun it alone.
- [ ] Review the branch diff against the design and verify no result claims more than its actual rule inventory.
- [ ] Record deferred Samsung internal profile work in the design/handoff and proceed to the structure diagram plan.
