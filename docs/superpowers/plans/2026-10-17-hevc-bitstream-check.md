# HEVC Bitstream Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded HEVC rule runner for non-fragmented `hvc1` and `hev1` MP4/MOV video, with exact byte evidence and explicit untested scope.

**Architecture:** A parser-side scanner reads length-prefixed NAL headers from the existing sample index without loading whole samples. A narrow rule catalog converts verified observations into normative findings; unsupported syntax and coverage gaps remain NOT_CHECKED. The Content Check codec stage invokes this runner through the workflow plan's stage interface.

**Tech Stack:** Kotlin, existing ISO-BMFF/HEVC readers, JDK file I/O, coroutines, JUnit, ffmpeg/x265 fixture generation.

**Spec:** `docs/superpowers/specs/2026-10-17-hevc-bitstream-check-design.md` (depends on `2026-10-17-content-check-workflow-design.md`)

## Global Constraints

- Scope is `MediaType.VIDEO`, first HEVC video track with `hvc1` or `hev1`, valid `hvcC`, non-fragmented `moov` sample tables, at most `MAX_VIDEO_INDEXED_SAMPLES = 1_000_000`.
- Both `hvc1` and default-settings `hev1` must be verified with real fixtures before rule code.
- Rule ID/source/edition/clause/profile must be independently checked before labeling a result normative; otherwise label structural observation.
- Zero-length NAL and unsupported VPS/SPS/PPS/slice syntax are NOT_CHECKED unless a verified rule resolves them; never guess FAIL or PASS.
- No full HEVC conformance or Android MediaCodec compatibility claim; decoder output remains a separate operation diagnostic.
- Bounded reads, checked arithmetic, cancellation between samples/NALs, `Dispatchers.IO`, file identity before publishing.
- Bilingual KO/EN evidence, exact byte ranges, bounded displayed/exported occurrences plus total count; no heavy dependency.
- Preserve existing Content Check JSON keys and no-overwrite save behavior.

## Review Focus

- `hvcC` says 4-byte lengths while a sample ends after 1–3 prefix bytes: report exact prefix offset without out-of-bounds read (Task 2 test `truncatedLengthPrefix`).
- A sample offset plus size overflows `Long`: mark sample unreadable/partial, never wrap into a valid range (Task 2 test `sampleExtentOverflow`).
- A well-formed HEVC NAL uses an unsupported extension syntax in an existing parser: mark that rule NOT_CHECKED while header checks remain valid (Task 3 test `unsupportedParameterSyntaxIsNotFailure`).
- Two video tracks where the first is HEVC and second is AVC/HEVC: identify checked track and explicitly list the second as unscanned (Task 4 test `otherVideoTrackIsNotChecked`).
- An HEIC motion photo embeds `moov`/`hvcC`: entry point rejects because tab media type is IMAGE (Task 4 test `heicEmbeddedVideoIsExcluded`).

---

### Task 1: Real-byte fixtures and verified rule catalog

**Files:** Create `docs/validation/hevc-conformance-fixtures/README.md`, `generate.sh`, and small fixtures under `app/src/test/resources/hevc-conformance/`; create `app/src/main/kotlin/com/multiviewer/parser/conformance/HevcRuleCatalog.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/HevcRuleCatalogTest.kt`.

**Interfaces:** Produce `object HevcRuleCatalog` with `fun rule(id: String): ConformanceRule?` and stable IDs for framing, `forbidden_zero_bit`, and `nuh_temporal_id_plus1`. Document H.265 edition and exact clause used for header bits; treat length framing as a structural observation unless the applicable ISO/IEC 14496-15 requirement is verified. `hvc1`/`hev1` cross-layer rules are cataloged only after exact sample-entry wording is verified.

- [ ] **Step 1: Generate and independently dump fixtures.** Pin ffmpeg/x265 versions and commands, create a default `hev1` and explicit `hvc1` file with multiple samples, record raw sample-entry bytes, `hvcC` byte 21, first NAL prefix/header offsets, and expected values in README. Create one-byte mutations for forbidden bit, zero temporal ID, malformed `hvcC`, and mismatched reference; create truncated-prefix/oversize-NAL variants. For fragment/encrypted/multi-track boundaries, use small real files where feasible and record any fixture that cannot be generated.
- [ ] **Step 2: Write failing catalog tests.** Assert stable IDs, H.265 source/edition/clause on the two verified header rules, scope for both tags, and absence of unverified `hvc1`/`hev1` normative claims.
- [ ] **Step 3: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.HevcRuleCatalogTest'`; expect RED.
- [ ] **Step 4: Implement** the catalog and fixture provenance; compare raw bytes to independent `ffprobe`/Bento4 orientation, using raw bytes as test truth.
- [ ] **Step 5: Run** focused tests; expect PASS. Commit `test: establish HEVC conformance byte fixtures and rule catalog`.

### Task 2: Bounded NAL framing and header scanner

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/HevcNalScanner.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/HevcNalScannerTest.kt`.

**Interfaces:** `data class HevcNalObservation(val sampleIndex: Int, val nalIndex: Int, val prefixOffset: Long, val headerOffset: Long?, val nalType: Int?, val forbiddenZeroBit: Int?, val temporalIdPlus1: Int?, val issue: String?)`; `fun scanHevcSample(reader: ByteReader, sample: VideoSample, lengthSize: Int, onNal: (HevcNalObservation) -> Unit): HevcSampleScan`. `HevcSampleScan` stores scanned NAL count, exact consumed bytes, and a coverage reason. Scanner checks `lengthSize in 1..4`, sample bounds and overflow before reads, reads only prefix + two header bytes, and stops on malformed extent. Zero-length NAL emits a NOT_CHECKED observation.

- [ ] **Step 1: Write failing tests.** Assert raw offsets and bit fields on both real tag fixtures; `truncatedLengthPrefix`, oversize NAL, one-byte header, `sampleExtentOverflow`, zero-length NAL, forbidden bit, and temporal ID zero have exact offsets/status and never PASS for unreadable data.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.HevcNalScannerTest'`; expect RED.
- [ ] **Step 3: Implement** bounded scanner with checked addition and no sample-sized buffer allocation; callers check coroutine cancellation between NALs.
- [ ] **Step 4: Run** scanner tests; expect PASS. Commit `feat: scan HEVC NAL headers with bounded reads`.

### Task 3: Parameter-set evidence and rule aggregation

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/HevcBitstreamReport.kt`, `HevcBitstreamAnalyzer.kt`, and `app/src/test/kotlin/com/multiviewer/parser/conformance/HevcBitstreamAnalyzerTest.kt`; reuse `HevcParameterSetExtraction.kt`, `HevcParameterSets.kt`, and `HevcSliceParser.kt` without expanding syntax speculatively.

**Interfaces:** `data class HevcBitstreamReport(val findings: List<ConformanceFinding>, val checkedSamples: Int, val indexedSamples: Int, val totalOccurrences: Map<String, Long>, val limitations: List<String>, val checkedTrack: String, val complete: Boolean)`; `suspend fun analyzeHevcBitstream(file: File, root: BoxNode, onProgress: (Int, Int?) -> Unit): HevcBitstreamReport`. The UI entry point checks `MediaType.VIDEO` before calling this parser-side function. The analyzer calls `buildVideoSampleTable`, `extractHvcCRawParameterSets`, and scanner from Task 2; it emits verified header findings and only supported VPS/SPS/PPS reference findings. Keep a fixed per-rule occurrence cap, reporting totals separately.

- [ ] **Step 1: Write failing tests.** Valid `hev1` and `hvc1` files report checked/indexed counts and exact header ranges; corrupted header has stable rule ID and observed/expected bits; missing reference is FAIL only when parser fully resolves the referenced ID; `unsupportedParameterSyntaxIsNotFailure`; failed prerequisite yields NOT_CHECKED; 101 repeated violations show bounded findings and full occurrence count.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.HevcBitstreamAnalyzerTest'`; expect RED.
- [ ] **Step 3: Implement** aggregation and parameter-set applicability with documented parser limits. Never turn an unsupported parser return into a syntax violation; retain exact sample/NAL/track evidence in details and ranges.
- [ ] **Step 4: Run** focused analyzer and existing `HevcParameterSetsTest`, `HevcSliceParserTest`; expect PASS. Commit `feat: evaluate bounded HEVC bitstream rules`.

### Task 4: Scope/coverage and workflow integration

**Files:** Modify `HevcBitstreamAnalyzer.kt`, `ContentCheckStage.kt`, `ContentCheckRunner.kt`, `ContentCheckWindow.kt`, `ContentCheckSnapshot.kt`, `ConformancePanel.kt`; create `app/src/test/kotlin/com/multiviewer/ui/HevcContentCheckIntegrationTest.kt`.

**Interfaces:** Codec stage from the workflow plan invokes `analyzeHevcBitstream` only for VIDEO plus matching `hvcC`. Extend snapshot with `val hevcBitstream: HevcBitstreamReport? = null`; append HEVC rule findings to the conformance detail only after a completed/partial codec run. `workflow.version == 1` JSON adds codec coverage and bounded evidence; existing keys remain. Other tracks, fragments, encryption, mismatched tables, cap truncation, changed files, and cancelled scans expose named NOT_CHECKED coverage and cannot yield overall PASS.

- [ ] **Step 1: Write failing tests.** `otherVideoTrackIsNotChecked`, `heicEmbeddedVideoIsExcluded`, fragment/encrypted/unreadable sample coverage, malformed `hvcC`, cap truncation, cancellation after sample 1, file changed before publish, and default `hev1` availability. Assert `Run all` executes codec after structure and before decode, while a failed structure prerequisite leaves cross-layer findings NOT_CHECKED but independent header checks still run.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.ui.HevcContentCheckIntegrationTest'`; expect RED.
- [ ] **Step 3: Implement** capability detection, IO dispatcher/cancellation, per-sample progress, stage result and conformance summary integration. Gate by media type plus config, identify checked track, and report every unscanned track or sample region. Preserve old JSON, bounded AI text, path scrubbing, and `CREATE_NEW`.
- [ ] **Step 4: Run** focused integration tests, `./gradlew :app:compileKotlin`, then `./gradlew :app:test`; expect PASS. Inspect actual Content Check for both tags, invalid file, cancellation, result switching, and export when a display is available.
- [ ] **Step 5: Commit** `feat: expose HEVC rule coverage in Content Check`.

## Branch finish

- [ ] Review exact normative citations and real-byte dumps against source standards; downgrade any unverified rule to structural observation or NOT_CHECKED.
- [ ] Run `./gradlew :app:test` and `git diff --check`; fix nonflaky failures, review whole branch, and commit fixes.
- [ ] Fast-forward local `main` only after review; do not push without explicit user authorization.
