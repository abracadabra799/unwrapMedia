# AV1 Bitstream Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded AV1 rule runner for non-fragmented `av01` MP4/MOV video, with exact byte evidence and explicit untested scope.

**Architecture:** A parser-side scanner reads length-delimited OBU headers and sizes using leb128 from the existing sample index without loading whole samples. A narrow rule catalog converts verified observations into findings; unsupported syntax and coverage gaps remain NOT_CHECKED. The Content Check codec stage invokes this runner alongside HEVC and AVC through the workflow stage interface.

**Tech Stack:** Kotlin, existing ISO-BMFF/AV1 readers, JDK file I/O, coroutines, JUnit, ffmpeg/libsvtav1 fixture generation.

**Spec:** `docs/superpowers/specs/2026-10-17-av1-bitstream-check-design.md` (depends on `2026-10-17-content-check-workflow-design.md`)

## Global Constraints

- Scope is `MediaType.VIDEO`, first video track with `av01`, valid `av1C`, non-fragmented `moov` sample tables, at most `MAX_VIDEO_INDEXED_SAMPLES = 1_000_000`.
- Rule ID/source/edition/clause must be independently checked before labeling a result normative; otherwise label structural observation.
- Zero-length OBU and unsupported Sequence Header/Frame Header syntax are NOT_CHECKED unless a verified rule resolves them; never guess FAIL or PASS.
- No full AV1 conformance or Android MediaCodec compatibility claim; decoder output remains a separate operation diagnostic.
- Bounded reads, checked arithmetic, cancellation between samples/OBUs, `Dispatchers.IO`, file identity before publishing.
- Bilingual KO/EN evidence, exact byte ranges, bounded displayed/exported occurrences plus total count; no heavy dependency.
- Preserve existing Content Check JSON keys and no-overwrite save behavior.

## Review Focus

- OBU size exceeds remaining sample bytes: report exact prefix offset without out-of-bounds read (Task 2 test `truncatedObu`).
- A sample offset plus size overflows `Long`: mark sample unreadable/partial, never wrap into a valid range (Task 2 test `sampleExtentOverflow`).
- OBU forbidden bit (bit 7) is 1: report FAIL with exact 1-byte header range and bits (Task 2 test `forbiddenBit`).
- Two video tracks where the first is AV1 and second is AVC/HEVC/AV1: identify checked track and explicitly list the second as unscanned (Task 4 test `otherVideoTrackIsNotChecked`).
- An HEIC/AVIF motion photo embeds `moov`/`av1C`: entry point rejects because tab media type is IMAGE (Task 4 test `imageEmbeddedVideoIsExcluded`).

---

### Task 1: Real-byte fixtures and verified rule catalog

**Files:** Create `docs/validation/av1-conformance-fixtures/README.md`, `generate.sh`, `mutate.py`, and small fixtures under `app/src/test/resources/av1-conformance/`; create `app/src/main/kotlin/com/multiviewer/parser/conformance/Av1RuleCatalog.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/Av1RuleCatalogTest.kt`.

**Interfaces:** Produce `object Av1RuleCatalog` with `fun rule(id: String): ConformanceRule?` and stable IDs for framing (`av1.obu.framing`), `forbidden_bit` (`av1.obu.forbidden_bit`), and `reserved_bit` (`av1.obu.reserved_bit`). Document AV1 spec version (v1.0.0 with errata) and exact clause used for header bits; treat framing as a structural observation.

- [x] **Step 1: Generate and independently dump fixtures.** Pin ffmpeg/libsvtav1 version and commands, create a standard `av01` MP4 file with multiple samples, record raw sample-entry bytes, `av1C`, first OBU offsets, and expected values in README. Create one-byte mutations for forbidden bit, reserved bit, truncated OBU, and malformed `av1C`. Record any fixture that cannot be generated.
- [x] **Step 2: Write failing catalog tests.** Assert stable IDs, AV1 source/edition on header rules, scope, and structural observation labeling.
- [x] **Step 3: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.Av1RuleCatalogTest'`; expect RED.
- [x] **Step 4: Implement** the catalog and fixture provenance; compare raw bytes to independent inspection, using raw bytes as test truth.
- [x] **Step 5: Run** focused tests; expect PASS. Commit `test: establish AV1 conformance byte fixtures and rule catalog`.

### Task 2: Bounded OBU framing and header scanner

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/Av1ObuScanner.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/Av1ObuScannerTest.kt`.

**Interfaces:** `data class Av1ObuObservation(val sampleIndex: Int, val obuIndex: Int, val headerOffset: Long, val payloadOffset: Long, val obuType: Int, val forbiddenBit: Int, val reservedBit: Int, val extensionFlag: Boolean, val temporalId: Int?, val spatialId: Int?, val payloadSize: Long, val issue: String?)`; `fun scanAv1Sample(reader: ByteReader, sample: VideoSample, onObu: (Av1ObuObservation) -> Unit): Av1SampleScan`. `Av1SampleScan` stores scanned OBU count, exact consumed bytes, and a coverage reason. Scanner checks bounds and overflow before reads, reads OBU header + leb128 size, and stops on malformed extent.

- [x] **Step 1: Write failing tests.** Assert raw offsets and bit fields on real `av01` fixtures; `truncatedObu`, `sampleExtentOverflow`, forbidden bit, and reserved bit mutations have exact offsets/status and never PASS for unreadable data.
- [x] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.Av1ObuScannerTest'`; expect RED.
- [x] **Step 3: Implement** bounded scanner with checked addition and no sample-sized buffer allocation; callers check coroutine cancellation between OBUs.
- [x] **Step 4: Run** scanner tests; expect PASS. Commit `feat: scan AV1 OBU headers with bounded reads`.

### Task 3: Parameter-set evidence and rule aggregation

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/Av1BitstreamReport.kt`, `Av1BitstreamAnalyzer.kt`, and `app/src/test/kotlin/com/multiviewer/parser/conformance/Av1BitstreamAnalyzerTest.kt`; reuse `Av1SequenceHeader.kt` and `Av1ParameterSetExtraction.kt`.

**Interfaces:** `data class Av1BitstreamReport(val findings: List<ConformanceFinding>, val checkedSamples: Int, val indexedSamples: Int, val totalOccurrences: Map<String, Long>, val limitations: List<String>, val checkedTrack: String, val complete: Boolean)`; `suspend fun analyzeAv1Bitstream(file: File, root: BoxNode, onProgress: (Int, Int?) -> Unit): Av1BitstreamReport`. The analyzer calls `buildVideoSampleTable`, checks `av1C` Sequence Header, and scanner from Task 2; it emits verified header findings and supported Sequence Header observations. Keep a fixed per-rule occurrence cap (100), reporting totals separately.

- [x] **Step 1: Write failing tests.** Valid `av01` reports checked/indexed counts and exact header ranges; corrupted header has stable rule ID and observed bits; `unsupportedParameterSyntaxIsNotFailure`; failed prerequisite yields NOT_CHECKED; 101 repeated violations show bounded findings and full occurrence count.
- [x] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.Av1BitstreamAnalyzerTest'`; expect RED.
- [x] **Step 3: Implement** aggregation and parameter-set applicability with documented parser limits. Retain exact sample/OBU/track evidence in details and ranges.
- [x] **Step 4: Run** focused analyzer tests; expect PASS. Commit `feat: evaluate bounded AV1 bitstream rules`.

### Task 4: Scope/coverage and workflow integration

**Files:** Modify `ContentCheckStage.kt`, `ContentCheckWindow.kt`, `ContentCheckSnapshot.kt`; create `Av1ContentFindings.kt` and `app/src/test/kotlin/com/multiviewer/ui/Av1ContentCheckIntegrationTest.kt`.

**Interfaces:** Codec stage invokes `analyzeAv1Bitstream` when first video entry is `av01` with valid `av1C`. Extend snapshot with `val av1Bitstream: Av1BitstreamReport? = null`; append AV1 findings to conformance detail. Other tracks, fragments, encryption, mismatched tables, cap truncation, changed files, and cancelled scans expose named NOT_CHECKED coverage and cannot yield overall PASS.

- [ ] **Step 1: Write failing tests.** `otherVideoTrackIsNotChecked`, `imageEmbeddedVideoIsExcluded`, fragment/encrypted/unreadable sample coverage, malformed `av1C`, cap truncation, cancellation after sample 1, file changed before publish, and default `av01` availability.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.ui.Av1ContentCheckIntegrationTest'`; expect RED.
- [ ] **Step 3: Implement** capability detection in `ContentCheckStage.kt`, dispatcher/cancellation, progress, and UI presentation in `ContentCheckWindow.kt`.
- [ ] **Step 4: Run** focused integration tests, `./gradlew :app:compileKotlin`, then `./gradlew :app:test`; expect PASS.
- [ ] **Step 5: Commit** `feat: expose AV1 rule coverage in Content Check`.

## Branch finish

- [ ] Review exact normative citations and real-byte dumps against source standards; downgrade any unverified rule to structural observation or NOT_CHECKED.
- [ ] Run `./gradlew :app:test` and `git diff --check`; fix nonflaky failures, review whole branch, and commit fixes.
- [ ] Fast-forward local `main` only after review; do not push without explicit user authorization.
