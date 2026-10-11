# Content Check Result Presentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Present image, motion-photo, video, and audio inspection results as concise, structured findings with clear evidence, explicitly tentative interpretation, and useful next checks.

**Architecture:** Preserve current parser/checker report types and normalize their output through a UI-only `ContentFinding` model. Shared summary and table composables provide consistent scanning and selection, while packet and audio-track tables retain their domain-specific columns. The AI prompt reuses the same fact-versus-hypothesis distinction without changing exported JSON.

**Tech Stack:** Kotlin, Compose Multiplatform, existing JUnit/Kotlin tests, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-11-content-check-result-presentation-design.md`

## Global Constraints

- Keep parser/checker report types and exported JSON schemas unchanged.
- Use stable check IDs and existing diagnostic mappings (including video log explanations) where available.
- Preserve unrecognized findings using their original title/detail and mark interpretation as unavailable; do not infer a root cause without a specific rule.
- Any root-cause interpretation is labeled `Possible cause / hypothesis` and is kept separate from evidence.
- Follow the current path-scrubbing behavior in AI diagnostic prompts. Do not add raw filesystem paths to the new presentation model or AI prompt.
- Preserve existing inspection triggers and status semantics; this work changes presentation, not checker verdicts.
- Preserve selection/highlighting for structure and packet byte ranges.
- Keep large reports scrollable and cap/virtualize rendered findings as current panels do.
- Do not alter CLI output or analysis-case JSON schema.

## Review Focus

- Unknown finding IDs: show original evidence and explicitly unavailable interpretation, never invent a cause. Task 1 tests the fallback mapping.
- Missing byte offsets or lengths: show location unavailable and disable Hex selection. Task 1 tests nullable ranges and Task 3 tests selection eligibility.
- Pending, failed-to-run, skipped, and passed checks: summary counts and default filtering must not conflate them. Task 1 tests status mapping and counts.
- Duplicate decoder messages and multiple findings at the same offset: preserve distinct source findings and deterministic ordering without suggesting they are unique root causes. Task 1 tests stable tie ordering.
- Very long logs/details and large finding lists: keep rendering bounded/scrollable and prevent one expanded row from overflowing the panel. Task 3 tests capped summaries and uses lazy table rows.

---

### Task 1: Shared finding model and summary/filter rules

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/ContentFinding.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ContentFindingTest.kt`

**Interfaces:**
- Produces: `ContentFinding(id: String, stage: FindingStage, status: CheckStatus, title: String, location: String?, offset: Long?, length: Long?, summary: String, verifiedFacts: List<String>, hypothesis: String?, nextChecks: List<String>)`.
- Produces: `ContentFindingSummary(total: Int, failures: Int, warnings: Int, passed: Int, unverified: Int)`, `contentFindingSummary(findings: List<ContentFinding>): ContentFindingSummary`, `orderedContentFindings(findings: List<ContentFinding>, showAll: Boolean): List<ContentFinding>`, `contentFindingLocationLabel(finding: ContentFinding, ko: Boolean): String`, and `mapIntegrityStatus(IntegrityStatus): CheckStatus` / `mapImageDecodeStatus(ImageDecodeStatus): CheckStatus`.

- [ ] **Step 1: Write failing tests** named `mapsInspectionStatusesWithoutTreatingNotRunAsClean`, `countsEachFindingByStatus`, `hidesPassAndSkipUntilShowAll`, `ordersBySeverityThenOffsetAndKeepsTiesStable`, `keepsUnknownExplanationEmpty`, and `marksMissingLocationUnavailable` (through `contentFindingLocationLabel`).
- [ ] **Step 2: Run tests to verify failure.**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ContentFindingTest'`

Expected: FAIL because the presentation model and helpers do not exist.
- [ ] **Step 3: Implement the immutable model and pure helpers.** Map `IntegrityStatus` and `ImageDecodeStatus` to `CheckStatus` without changing their source reports. Keep unknown interpretations null and preserve source detail as verified evidence.
- [ ] **Step 4: Run focused tests.**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ContentFindingTest'`

Expected: PASS.
- [ ] **Step 5: Commit** the model and tests as `feat: add shared content finding presentation model`.

### Task 2: Map every inspection report to findings

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/ContentFindingMappers.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ContentFindingMappersTest.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoIntegrity.kt` only if a public read-only conversion helper is required; do not change JSON serialization.

**Interfaces:**
- Consumes: Task 1 `ContentFinding` and existing `ImageStructureReport`, `AudioStructureReport`, `ImageDecodeReport`, `MotionPhotoIntegrityReport`, `VideoIntegrityReport`, and `AudioIntegrityReport`.
- Produces: `structureFindings(report: ImageStructureReport, stage: FindingStage): List<ContentFinding>`, `audioStructureFindings(report: AudioStructureReport): List<ContentFinding>`, `imageDecodeFindings(report: ImageDecodeReport): List<ContentFinding>`, `motionPhotoFindings(report: MotionPhotoIntegrityReport): List<ContentFinding>`, `videoFindings(report: VideoIntegrityReport): VideoFindingPresentation`, and `audioFindings(report: AudioIntegrityReport): AudioFindingPresentation`; mappings do not mutate inputs. `VideoFindingPresentation` contains decoder findings and packet rows; `AudioFindingPresentation` contains probe findings and per-track measurement rows.

- [ ] **Step 1: Write failing mapper tests** named `mapsStructureItemsAndParserWarningsToSeparateStages`, `mapsImageDecodeResolutionMismatch`, `labelsMotionPhotoSeverityAndEvidence`, `marksVideoLogExplanationAsHypothesisWithoutByteLocation`, `preservesPacketLocationAndRange`, and `mapsAudioMeasurementsAndMismatchesAsVerifiedFacts`.
- [ ] **Step 2: Run tests to verify failure.**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ContentFindingMappersTest'`

Expected: FAIL because report adapters do not exist.
- [ ] **Step 3: Implement adapters using stable IDs and existing diagnostic helpers.** Only use existing evidence for verified facts; keep video log explanation fields explicitly hypothetical; leave unknown causes unset.
- [ ] **Step 4: Run focused mapper and model tests.**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ContentFindingMappersTest' --tests 'com.multiviewer.ui.ContentFindingTest'`

Expected: PASS.
- [ ] **Step 5: Commit** the adapters and tests as `feat: map content reports to structured findings`.

### Task 3: Shared summary and findings table components

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/ContentFindingTable.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ContentFindingTableTest.kt`

**Interfaces:**
- Consumes: Task 1 `ContentFinding`, `ContentFindingSummary`, and ordered/filter helpers.
- Produces: `isSelectableContentRange(finding: ContentFinding, fileLength: Long): Boolean` plus reusable Compose summary and lazy findings table with status, check, location, and finding columns; an all-results toggle; expandable evidence/hypothesis/next-check sections; and an `onSelectRange` callback only for valid byte ranges.

- [ ] **Step 1: Write failing pure-state tests** named `actionableModeShowsFailuresWarningsAndFailedSteps`, `showAllIncludesPassAndSkip`, `summaryUsesFindingCounts`, and `rangeSelectionRequiresPositiveInFileRange`. Keep visual behavior in the composable and test the decision logic through pure helpers.
- [ ] **Step 2: Run tests to verify failure.**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ContentFindingTableTest'`

Expected: FAIL because the table state helpers do not exist.
- [ ] **Step 3: Implement `isSelectableContentRange`** to require non-negative offset, positive length, overflow-safe end arithmetic, and end no greater than file length; implement the summary and lazy table using existing `AppColors`, `checkStatusColor`, and localized labels; show unavailable location explicitly; cap expanded text and keep long results selectable/scrollable.
- [ ] **Step 4: Run focused table and mapper tests.**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ContentFindingTableTest' --tests 'com.multiviewer.ui.ContentFindingMappersTest'`

Expected: PASS.
- [ ] **Step 5: Commit** the shared components and tests as `feat: add structured content findings table`.

### Task 4: Integrate all Content Check tabs and AI facts

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageCheckPanels.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AudioCheckPanel.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoCheckPanels.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoReportContent.kt` or its Content Check call site in `ContentCheckWindow.kt`, whichever is needed to reuse the common finding presentation without changing other SEF screens.
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ContentCheckWindow.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ContentCheckSnapshot.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ContentCheckSnapshotTest.kt`
- Test: existing UI presentation/mapping tests as needed.

**Interfaces:**
- Consumes: Tasks 1–3 finding model, mappers, summary, and table components.
- Produces: all Content Check tabs use common summary/findings presentation; packet table retains PTS/DTS/offset/size/keyframe columns; audio tracks retain declared-versus-decoded comparisons; AI prompt distinguishes verified facts from hypotheses and next checks.

- [ ] **Step 1: Write failing integration/prompt tests** named `everyMediaTypePublishesFindingFactsToPrompt`, `hypothesesAreNotListedAsVerifiedFacts`, `contentFindingPromptContinuesToScrubPaths`, `structureAndPacketRangesRemainSelectable`, and `pendingSkippedAndFailedStatesRemainDistinct`.
- [ ] **Step 2: Run focused tests to verify failure.**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ContentCheckSnapshotTest' --tests 'com.multiviewer.ui.ContentFindingMappersTest'`

Expected: FAIL for missing shared presentation integration or fact/hypothesis separation.
- [ ] **Step 3: Replace duplicated status/list rows in Content Check panels with shared components.** Keep domain-specific packet and audio-track columns, retain existing Hex range callbacks, and provide a filter to reveal PASS/SKIP rows.
- [ ] **Step 4: Update AI prompt formatting** to reuse normalized facts while marking all interpretations as hypotheses; preserve path scrubbing and the current prompt section boundaries.
- [ ] **Step 5: Run focused and full verification.**

Run focused: `./gradlew :app:test --tests 'com.multiviewer.ui.ContentCheckSnapshotTest' --tests 'com.multiviewer.ui.ContentFindingTest' --tests 'com.multiviewer.ui.ContentFindingMappersTest' --tests 'com.multiviewer.ui.ContentFindingTableTest'`

Expected: PASS.

Run full suite: `./gradlew test`

Expected: all tests pass; investigate any failure and report unrelated reproducible failures explicitly.
- [ ] **Step 6: Review the complete diff** with `git diff --check`, `git status --short`, and `git diff --stat`; verify no parser/checker verdicts or exported JSON schemas changed.
- [ ] **Step 7: Commit** the integration and tests as `feat: unify content check result presentation`.
