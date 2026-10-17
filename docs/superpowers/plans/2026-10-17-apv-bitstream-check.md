# APV Bitstream Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded APV rule runner for non-fragmented `apv1` MP4/MOV video, with exact byte evidence and explicit untested scope.

**Architecture:** A parser-side scanner reads APV access unit prefixes, PBU headers, and frame headers from the existing sample index using bounded reads (max 4096 bytes per sample) without loading whole multi-megabyte intra samples. A narrow rule catalog converts verified observations into findings; unsupported syntax and coverage gaps remain NOT_CHECKED. The Content Check codec stage invokes this runner alongside HEVC, AVC, AV1, and VP9 through the workflow stage interface.

**Tech Stack:** Kotlin, existing ISO-BMFF readers (`IsoSampleTable.kt`, `ApvCBoxDecoder.kt`, `ApvPbu.kt`, `ApvFrameHeader.kt`), JDK file I/O, coroutines, JUnit, Python fixture generator.

**Spec:** `docs/superpowers/specs/2026-10-17-apv-bitstream-check-design.md` (depends on `2026-10-17-content-check-workflow-design.md`)

## Global Constraints

- Scope is `MediaType.VIDEO`, first video track with `apv1`, valid `apvC`, non-fragmented `moov` sample tables, at most `MAX_VIDEO_INDEXED_SAMPLES = 1_000_000`.
- Rule ID/source/edition/clause must be independently checked before labeling a result normative; otherwise label structural observation.
- Unsupported syntax is NOT_CHECKED unless a verified rule resolves it; never guess FAIL or PASS.
- No full APV conformance or Android MediaCodec compatibility claim; decoder output remains a separate operation diagnostic.
- Bounded reads (at most 4096 bytes per sample), checked arithmetic, cancellation between samples, `Dispatchers.IO`, file identity before publishing.
- Bilingual KO/EN evidence, exact byte ranges, bounded displayed/exported occurrences plus total count; no heavy dependency.
- Preserve existing Content Check JSON keys and no-overwrite save behavior.

## Review Focus

- Access unit signature at byte 4: must be `'aPv1'` (`0x61507631`). Altered signature reports FAIL with exact byte range (Task 2 test `badSignature`).
- PBU header reserved byte: byte 3 of `pbu_header` must be 0 (Task 2 test `badPbuReserved`).
- Frame info reserved bits and band: `band_idc <= 3` and `reserved_zero_5bits == 0` (Task 2 test `badBandReserved`).
- Sample offset plus size overflows `Long`: mark sample unreadable/partial, never wrap into a valid range (Task 2 test `sampleExtentOverflow`).
- Two video tracks where the first is APV and second is other: identify checked track and explicitly list the second as unscanned (Task 4 test `otherVideoTrackIsNotChecked`).
- An HEIC/AVIF motion photo embeds `moov`/`apv1`: entry point rejects because tab media type is IMAGE (Task 4 test `imageEmbeddedVideoIsExcluded`).

---

### Task 1: Real-byte fixtures, ApvRuleCatalog, and VideoCodec.APV

**Files:** Create `docs/validation/apv-conformance-fixtures/README.md`, `generate.py`, and fixtures under `app/src/test/resources/apv-conformance/`; create `app/src/main/kotlin/com/multiviewer/parser/conformance/ApvRuleCatalog.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/ApvRuleCatalogTest.kt`; update `VideoCodec` enum and `SAMPLE_ENTRY_CODECS` in `IsoSampleTable.kt`.

**Interfaces:** Produce `object ApvRuleCatalog` with `fun rule(id: String): ConformanceRule?` and stable IDs for signature (`apv.au.signature`), framing (`apv.pbu.framing`), PBU reserved byte (`apv.pbu.reserved_zero`), profile/band (`apv.frame.profile`), frame reserved byte (`apv.frame.reserved_zero`), and apvC (`apv.config.apvc`). Register `APV` in `VideoCodec` and `"apv1"` in `SAMPLE_ENTRY_CODECS`.

- [x] **Step 1: Generate fixtures.** Create Python generator creating valid `default-apv1.mp4` with verified `qp_D.apv` access unit prefix and valid `apvC` box, record byte offsets in README. Create mutations for `bad-signature.mp4`, `bad-pbu-reserved.mp4`, `bad-band-reserved.mp4`, `malformed-apvc.mp4`, `truncated-sample.mp4`, and `many-signature-failures.mp4`.
- [x] **Step 2: Write failing catalog and enum tests.** Assert stable IDs, APV source/edition, scope, structural observation labeling, and `VideoCodec.APV` sample entry resolution.
- [x] **Step 3: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.ApvRuleCatalogTest'`; expect RED.
- [x] **Step 4: Implement** `ApvRuleCatalog`, add `APV` to `VideoCodec`, and add `"apv1"` to `SAMPLE_ENTRY_CODECS`.
- [x] **Step 5: Run** focused tests; expect PASS. Commit `test: establish APV conformance byte fixtures and rule catalog`.

### Task 2: Bounded APV access unit and frame scanner

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/ApvAccessUnitScanner.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/ApvAccessUnitScannerTest.kt`.

**Interfaces:** `data class ApvSampleObservation(val sampleIndex: Int, val offset: Long, val size: Long, val signature: Int, val primaryFrameOffset: Long?, val profileIdc: Int?, val levelIdc: Int?, val bandIdc: Int?, val reservedZero5bits: Int?, val pbuReservedZero: Int?, val frameReservedZero: Int?, val issue: String?)`; `fun scanApvSample(reader: ByteReader, sample: VideoSample): ApvSampleObservation`. Scanner reads only up to 4096 bytes prefix per sample, checks bounds, checks `'aPv1'` signature at offset 4, verifies PBU headers and primary frame header fields.

- [x] **Step 1: Write failing scanner tests.** Assert raw offsets and bit fields on real `apv1` fixtures; `badSignature`, `badPbuReserved`, `badBandReserved`, `truncatedSample`, and `sampleExtentOverflow` have exact offsets/status and never PASS for unreadable data.
- [x] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.ApvAccessUnitScannerTest'`; expect RED.
- [x] **Step 3: Implement** bounded scanner with checked addition and bounded buffer prefix; callers check coroutine cancellation between samples.
- [x] **Step 4: Run** scanner tests; expect PASS. Commit `feat: scan APV access unit headers with bounded reads`.

### Task 3: Parameter-set evidence and rule aggregation

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/ApvBitstreamReport.kt`, `ApvBitstreamAnalyzer.kt`, and `app/src/test/kotlin/com/multiviewer/parser/conformance/ApvBitstreamAnalyzerTest.kt`.

**Interfaces:** `data class ApvBitstreamReport(val findings: List<ConformanceFinding>, val checkedSamples: Int, val indexedSamples: Int, val totalOccurrences: Map<String, Long>, val limitations: List<String>, val checkedTrack: String, val complete: Boolean)`; `suspend fun analyzeApvBitstream(file: File, root: BoxNode, onProgress: (Int, Int?) -> Unit): ApvBitstreamReport`. The analyzer calls `buildVideoSampleTable`, checks `apvC` configuration box, and scanner from Task 2; it emits verified header findings and supported `apvC` observations. Keep a fixed per-rule occurrence cap (100), reporting totals separately.

- [x] **Step 1: Write failing tests.** Valid `apv1` reports checked/indexed counts and exact header ranges; corrupted signature/reserved bytes have stable rule ID and observed bits; `malformedApvc` yields NOT_CHECKED; 101 repeated violations show bounded findings and full occurrence count.
- [x] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.ApvBitstreamAnalyzerTest'`; expect RED.
- [x] **Step 3: Implement** aggregation and apvC applicability with documented parser limits. Retain exact sample/track evidence in details and ranges.
- [x] **Step 4: Run** focused analyzer tests; expect PASS. Commit `feat: evaluate bounded APV bitstream rules`.

### Task 4: Scope/coverage and workflow integration

**Files:** Modify `ContentCheckStage.kt`, `ContentCheckWindow.kt`, `ContentCheckSnapshot.kt`; create `ApvContentFindings.kt` and `app/src/test/kotlin/com/multiviewer/ui/ApvContentCheckIntegrationTest.kt`.

**Interfaces:** Codec stage invokes `analyzeApvBitstream` when first video entry is `apv1` with valid `apvC`. Extend snapshot with `val apvBitstream: ApvBitstreamReport? = null`; append APV findings to conformance detail. Other tracks, fragments, encryption, mismatched tables, cap truncation, changed files, and cancelled scans expose named NOT_CHECKED coverage and cannot yield overall PASS.

- [x] **Step 1: Write failing tests.** `otherVideoTrackIsNotChecked`, `imageEmbeddedVideoIsExcluded`, fragment/encrypted/unreadable sample coverage, malformed `apvC`, cap truncation, cancellation after sample 1, file changed before publish, and default `apv1` availability.
- [x] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.ui.ApvContentCheckIntegrationTest'`; expect RED.
- [x] **Step 3: Implement** capability detection in `ContentCheckStage.kt`, dispatcher/cancellation, progress, and UI presentation in `ContentCheckWindow.kt`.
- [x] **Step 4: Run** focused integration tests, `./gradlew :app:compileKotlin`, then `./gradlew :app:test`; expect PASS.
- [x] **Step 5: Commit** `feat: expose APV rule coverage in Content Check`.

## Branch finish

- [ ] Review exact normative citations and real-byte dumps against source standards; downgrade any unverified rule to structural observation or NOT_CHECKED.
- [ ] Run `./gradlew :app:test` and `git diff --check`; fix nonflaky failures, review whole branch, and commit fixes.
- [ ] Fast-forward local `main` only after review; do not push without explicit user authorization.
