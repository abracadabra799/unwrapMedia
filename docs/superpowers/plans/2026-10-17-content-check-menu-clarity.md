# Content Check Menu Clarity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Content Check window show which stages ran, name its actions clearly, and let engineers filter and inspect findings without mistaking uninspected work for a pass.

**Architecture:** Keep inspection orchestration and report models intact. Add small UI presentation helpers for finding filters, stage grouping/run labels, and action availability; wire them into the existing Material3 window and table. Preserve current export callbacks and Hex range selection.

**Tech Stack:** Kotlin/JVM 21, Compose Desktop Material3, kotlin.test, Gradle `:app:test`.

**Spec:** `docs/superpowers/specs/2026-10-17-content-check-menu-clarity-design.md`

## Global Constraints

- Keep checker verdicts, execution timing, byte ranges, AI context, and exported JSON unchanged.
- No global `PASS` when any stage is uninspected; distinguish `미검증 / Not checked`, `미검사 / Not run`, and `해당 없음 / Not applicable`.
- User-visible text is KO/EN; Material3 components only; no new dependencies.
- Keep all file reads, JSON generation, and save writes off the UI thread; preserve save's `CREATE_NEW` no-overwrite behavior.
- Existing `TabState` pinning, cancellation, and tree-driven Hex highlight action remain intact.

## Review Focus

1. Empty finding input: `All` must say no check results exist, never imply a pass (Task 1 test).
2. Only SKIP results: `Issues` is empty while `Unverified` still shows them (Task 1 test).
3. INFO and PASS results: neither appears in `Issues`, and INFO sorts before PASS in `All` (Task 1 test).
4. Parsed tree pending or structure error: header must show checking or failure, not a pass (Task 2 test/inspection).
5. Changed file or active run: menu actions obey the old export/run enable rules (Task 3 test).

---

### Task 1: Three finding filters and check IDs

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ContentFinding.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ContentFindingTable.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ContentFindingTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ContentFindingTableTest.kt`

**Interfaces:** Produce `enum class ContentFindingFilter { ALL, ISSUES, UNVERIFIED }`, `fun filteredContentFindings(findings: List<ContentFinding>, filter: ContentFindingFilter): List<ContentFinding>`, and `internal fun contentFindingEmptyState(findings: List<ContentFinding>, filter: ContentFindingFilter): ContentFindingEmptyState?`. Preserve `orderedContentFindings(findings, showAll)` as a wrapper for existing callers/tests.

- [ ] **Step 1: Write failing filter tests.** In `ContentFindingTest`, use literal findings with FAIL, WARN, INFO, SKIP, PASS and assert `ALL` retains severity/offset order, `ISSUES` returns only FAIL/WARN, and `UNVERIFIED` returns only SKIP. In `ContentFindingTableTest`, assert empty input is `NO_CHECKS` for every filter; SKIP-only input is `FILTERED` under `ISSUES` but visible under `UNVERIFIED`; INFO+PASS input is absent from `ISSUES`.

```kotlin
// ContentFindingTest.kt; use the file's existing finding(...) fixture.
@Test fun filtersKeepIssuesAndUnverifiedSeparate() {
    val input = listOf(
        finding("pass", CheckStatus.PASS, 4), finding("skip", CheckStatus.SKIP, 3),
        finding("info", CheckStatus.INFO, 2), finding("warn", CheckStatus.WARN, 8),
        finding("fail", CheckStatus.FAIL, 9),
    )
    assertEquals(listOf("fail", "warn", "info", "skip", "pass"),
        filteredContentFindings(input, ContentFindingFilter.ALL).map { it.id })
    assertEquals(listOf("fail", "warn"),
        filteredContentFindings(input, ContentFindingFilter.ISSUES).map { it.id })
    assertEquals(listOf("skip"),
        filteredContentFindings(input, ContentFindingFilter.UNVERIFIED).map { it.id })
}
```

```kotlin
// ContentFindingTableTest.kt; use the file's existing finding(...) fixture.
@Test fun filterEmptyStatesDoNotClaimAFalsePass() {
    for (filter in ContentFindingFilter.entries) {
        assertEquals(ContentFindingEmptyState.NO_CHECKS, contentFindingEmptyState(emptyList(), filter))
    }
    val skip = listOf(finding("skip", CheckStatus.SKIP))
    assertEquals(ContentFindingEmptyState.FILTERED, contentFindingEmptyState(skip, ContentFindingFilter.ISSUES))
    assertEquals(null, contentFindingEmptyState(skip, ContentFindingFilter.UNVERIFIED))
    assertEquals(ContentFindingEmptyState.FILTERED, contentFindingEmptyState(
        listOf(finding("info", CheckStatus.INFO), finding("pass", CheckStatus.PASS)), ContentFindingFilter.ISSUES))
}
```
- [ ] **Step 2: Run RED.** `./gradlew :app:test --tests 'com.multiviewer.ui.ContentFindingTest' --tests 'com.multiviewer.ui.ContentFindingTableTest'`; expect missing filter symbols/overload.
- [ ] **Step 3: Implement filter model.** Reuse `statusOrder` and stable index/offset ordering from `orderedContentFindings`; make the Boolean overload delegate to `ALL` or `ISSUES`. Make the empty-state helper distinguish no input from rows hidden by the selected filter.

```kotlin
enum class ContentFindingFilter { ALL, ISSUES, UNVERIFIED }

fun filteredContentFindings(findings: List<ContentFinding>, filter: ContentFindingFilter): List<ContentFinding> =
    findings.withIndex()
        .filter { (_, finding) -> when (filter) {
            ContentFindingFilter.ALL -> true
            ContentFindingFilter.ISSUES -> finding.status == CheckStatus.FAIL || finding.status == CheckStatus.WARN
            ContentFindingFilter.UNVERIFIED -> finding.status == CheckStatus.SKIP
        } }
        .sortedWith(compareBy<IndexedValue<ContentFinding>>(
            { statusOrder(it.value.status) }, { it.value.offset ?: Long.MAX_VALUE }, { it.index }))
        .map { it.value }

// ContentFindingTable.kt; retain the Boolean overload for existing callers/tests.
internal fun contentFindingEmptyState(
    findings: List<ContentFinding>, filter: ContentFindingFilter,
): ContentFindingEmptyState? = when {
    findings.isEmpty() -> ContentFindingEmptyState.NO_CHECKS
    filteredContentFindings(findings, filter).isEmpty() -> ContentFindingEmptyState.FILTERED
    else -> null
}
```
- [ ] **Step 4: Wire the table.** Replace `showAll` checkbox with three Material3 `FilterChip`s in a row; `remember { mutableStateOf(ContentFindingFilter.ALL) }` is the default. Keep summary counts over full input, and show filter-specific empty copy from the spec. In expanded details show `finding.id` as `검사 ID / Check ID` in monospace before verified facts. Keep the existing Hex selection action unchanged.
- [ ] **Step 5: Run GREEN and review.** Re-run both focused test classes; inspect KO/EN empty copy and `git diff --check`. Commit `feat: add Content Check finding filters`.

### Task 2: Grouped stage status and precise run label

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/ContentCheckPresentation.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ContentCheckWindow.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ContentCheckPresentationTest.kt`

**Interfaces:** Produce `enum class ContentCheckStageGroup { ON_OPEN, ON_REQUEST }`, `fun contentCheckStageGroups(tabs: List<ContentTab>): Map<ContentCheckStageGroup, List<ContentTab>>`, `fun contentCheckRunLabel(type: MediaType, motionDetected: Boolean, ko: Boolean): String?`, and `fun contentCheckStructureStatusLabel(status: CheckStatus?, error: String?, ko: Boolean): String`. Group Structure/Conformance/Gainmap as ON_OPEN; every other `ContentTab` as ON_REQUEST. Return null run label for types without heavy steps.

- [ ] **Step 1: Write failing presentation tests.** Use `contentTabs` for IMAGE (motion/gain map combinations), VIDEO, AUDIO, UNKNOWN and assert exact group lists. Assert the four applicable run labels plus the unsupported null case in both languages from the spec. Include a case with no ON_REQUEST tabs. Assert `contentCheckStructureStatusLabel(null, null, true) == "검사 중"`, `contentCheckStructureStatusLabel(null, "read failed", true) == "실패"`, and `contentCheckStructureStatusLabel(CheckStatus.PASS, null, true) == "정상"`.

```kotlin
@Test fun stageGroupsFollowTheActualChecks() {
    val image = contentCheckStageGroups(contentTabs(MediaType.IMAGE, motionDetected = true, gainmapDetected = true))
    assertEquals(listOf(ContentTab.STRUCTURE, ContentTab.CONFORMANCE, ContentTab.GAINMAP),
        image[ContentCheckStageGroup.ON_OPEN])
    assertEquals(listOf(ContentTab.IMAGE_DECODE, ContentTab.MOTION_PHOTO),
        image[ContentCheckStageGroup.ON_REQUEST])
    val unknown = contentCheckStageGroups(contentTabs(MediaType.UNKNOWN, motionDetected = false))
    assertEquals(null, unknown[ContentCheckStageGroup.ON_REQUEST])
}

@Test fun runLabelsAndPendingStructureStatusAreSpecific() {
    assertEquals("이미지 디코딩 검사 실행", contentCheckRunLabel(MediaType.IMAGE, false, true))
    assertEquals("Run image decode and motion-photo checks", contentCheckRunLabel(MediaType.IMAGE, true, false))
    assertEquals("영상·패킷·오디오 검사 실행", contentCheckRunLabel(MediaType.VIDEO, false, true))
    assertEquals("Run audio decode check", contentCheckRunLabel(MediaType.AUDIO, false, false))
    assertEquals(null, contentCheckRunLabel(MediaType.UNKNOWN, false, true))
    assertEquals("검사 중", contentCheckStructureStatusLabel(null, null, true))
    assertEquals("실패", contentCheckStructureStatusLabel(null, "read failed", true))
    assertEquals("정상", contentCheckStructureStatusLabel(CheckStatus.PASS, null, true))
}
```
- [ ] **Step 2: Run RED.** `./gradlew :app:test --tests 'com.multiviewer.ui.ContentCheckPresentationTest'`; expect missing presentation symbols.
- [ ] **Step 3: Implement helpers and header.** In `ContentCheckWindow`, replace the ungrouped status `FlowRow` with two labeled `FlowRow` groups from `contentCheckStageGroups(tabs)`; omit an empty group. Move the existing `when (ContentTab)` status rendering into the grouped loop. Preserve exact verdict colors; show `검사 중 / Checking` while structure/gain-map results are pending, and `실패 / Failed` on captured structure errors. Do not add an aggregate verdict.

```kotlin
enum class ContentCheckStageGroup { ON_OPEN, ON_REQUEST }

fun contentCheckStageGroups(tabs: List<ContentTab>): Map<ContentCheckStageGroup, List<ContentTab>> = buildMap {
    val onOpen = tabs.filter { it == ContentTab.STRUCTURE || it == ContentTab.CONFORMANCE || it == ContentTab.GAINMAP }
    val onRequest = tabs.filterNot { it in onOpen }
    if (onOpen.isNotEmpty()) put(ContentCheckStageGroup.ON_OPEN, onOpen)
    if (onRequest.isNotEmpty()) put(ContentCheckStageGroup.ON_REQUEST, onRequest)
}

fun contentCheckStructureStatusLabel(status: CheckStatus?, error: String?, ko: Boolean): String = when {
    error != null -> if (ko) "실패" else "Failed"
    status == null -> if (ko) "검사 중" else "Checking"
    else -> checkStatusLabel(status, ko)
}

fun contentCheckRunLabel(type: MediaType, motionDetected: Boolean, ko: Boolean): String? = when (type) {
    MediaType.IMAGE -> if (motionDetected) {
        if (ko) "이미지 디코딩·모션포토 검사 실행" else "Run image decode and motion-photo checks"
    } else {
        if (ko) "이미지 디코딩 검사 실행" else "Run image decode check"
    }
    MediaType.VIDEO -> if (ko) "영상·패킷·오디오 검사 실행" else "Run video, packet, and audio checks"
    MediaType.AUDIO -> if (ko) "오디오 디코딩 검사 실행" else "Run audio decode check"
    MediaType.RAW_PIXEL, MediaType.UNKNOWN -> null
}
```
- [ ] **Step 4: Apply run label.** The primary button text uses `contentCheckRunLabel(type, motionDetected, ko)`; keep its current click handler and heavy-step order. Hide it when label is null, leaving the structure-only explanation.
- [ ] **Step 5: Run GREEN and review.** Run `ContentCheckPresentationTest` plus `ContentCheckPlanTest`; inspect each media type against the spec. Commit `feat: clarify Content Check stage status and run action`.

### Task 3: Export menu and action-state preservation

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ContentCheckPresentation.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ContentCheckWindow.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ContentCheckPresentationTest.kt`

**Interfaces:** Produce `data class ContentCheckActionState(val canRun: Boolean, val canExport: Boolean, val canDiagnose: Boolean, val canCancel: Boolean)` and `fun contentCheckActionState(type: MediaType, hasSteps: Boolean, structureReady: Boolean, rootLoaded: Boolean, fileChanged: Boolean, running: Boolean, saving: Boolean): ContentCheckActionState` with the window's existing enable rules.

- [ ] **Step 1: Write failing action-state tests.** Assert: image without structure cannot run/export; video with no root can run but cannot export; a changed file cannot run/export; running or saving disables run/export; cancel exists only while running; diagnosis needs root. These assertions pin the old behavior before moving buttons.

```kotlin
@Test fun actionsPreserveTheExistingSafetyGates() {
    val imageLoading = contentCheckActionState(MediaType.IMAGE, true, false, true, false, false, false)
    assertFalse(imageLoading.canRun)
    assertFalse(imageLoading.canExport)
    val videoLoading = contentCheckActionState(MediaType.VIDEO, true, false, false, false, false, false)
    assertTrue(videoLoading.canRun)
    assertFalse(videoLoading.canExport)
    val changed = contentCheckActionState(MediaType.VIDEO, true, false, true, true, false, false)
    assertFalse(changed.canRun)
    assertFalse(changed.canExport)
    val running = contentCheckActionState(MediaType.VIDEO, true, false, true, false, true, false)
    assertTrue(running.canCancel)
    assertFalse(running.canExport)
}
```
- [ ] **Step 2: Run RED.** `./gradlew :app:test --tests 'com.multiviewer.ui.ContentCheckPresentationTest'`; expect missing action-state symbols.
- [ ] **Step 3: Implement state helper and menu.** Use the helper for button/menu enable state. Show `취소 / Cancel` only when running. Replace the two `OutlinedButton` exports with one `OutlinedButton` opening a Material3 `DropdownMenu` with `DropdownMenuItem`s `JSON 복사 / Copy JSON` and `분석 케이스 저장 / Save analysis case`. Move the current onClick bodies intact into those items; dismiss the menu before starting either action. Keep `AI 진단 / AI diagnosis` separate.

```kotlin
data class ContentCheckActionState(
    val canRun: Boolean, val canExport: Boolean, val canDiagnose: Boolean, val canCancel: Boolean,
)

fun contentCheckActionState(
    type: MediaType, hasSteps: Boolean, structureReady: Boolean, rootLoaded: Boolean,
    fileChanged: Boolean, running: Boolean, saving: Boolean,
): ContentCheckActionState = ContentCheckActionState(
    canRun = !fileChanged && hasSteps && !running && !saving && (type != MediaType.IMAGE || structureReady),
    canExport = !fileChanged && rootLoaded && !running && !saving && (type != MediaType.IMAGE || structureReady),
    canDiagnose = rootLoaded,
    canCancel = running,
)
```
- [ ] **Step 4: Run GREEN and whole-branch verification.** Run `ContentCheckPresentationTest`, then `./gradlew :app:test`; run `git diff --check`, review the full branch against the spec, and verify save still uses `CREATE_NEW` and clipboard/save work stays off the UI thread. Commit `feat: group Content Check export actions`.

## Finish

- [ ] Review all commits and the whole branch, including a manual UI pass if this session can access a desktop display. State explicitly if visual verification is unavailable.
- [ ] Fast-forward merge the feature branch into local `main` after tests pass. Push only with the user's explicit go-ahead, per `AGENTS.md`.
