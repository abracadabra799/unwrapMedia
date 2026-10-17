# VVC (H.266) Bitstream Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded VVC (`vvc1` / `vvi1`) rule runner for non-fragmented MP4/MOV video, with exact byte evidence and explicit untested scope.

**Architecture:** A parser-side scanner reads VVC `vvcC` (`VvcConfigurationBox`) containing `VvcDecoderConfigurationRecord` and iterates through video samples from the sample table using bounded reads to inspect length-prefixed NAL unit headers (`forbidden_zero_bit`, `nuh_reserved_zero_bit`, `nuh_temporal_id_plus1`). A narrow rule catalog converts verified observations into findings; unsupported syntax and coverage gaps remain NOT_CHECKED. The Content Check codec stage invokes this runner for `MediaType.VIDEO` with `vvc1`/`vvi1`.

**Tech Stack:** Kotlin, existing ISO-BMFF readers (`IsoSampleTable.kt`), JDK file I/O, coroutines, JUnit, Python fixture generator.

**Spec:** `docs/superpowers/specs/2026-10-18-vvc-bitstream-check-design.md` (depends on `2026-10-17-content-check-workflow-design.md`)

## Global Constraints

- Scope is MP4/MOV video track with `vvc1` or `vvi1`, valid `vvcC` box, non-fragmented `moov` sample tables, at most `MAX_VIDEO_INDEXED_SAMPLES = 1_000_000`.
- Active in `CheckStage.CODEC` when `MediaType.VIDEO` with `vvc1`/`vvi1` is present.
- Rule ID/source/edition/clause must be independently checked before labeling a result normative; otherwise label structural observation.
- Unsupported syntax is NOT_CHECKED unless a verified rule resolves it; never guess FAIL or PASS.
- Bounded reads (inspecting NAL unit headers without loading full sample), checked arithmetic, cancellation between samples, `Dispatchers.IO`, file identity before publishing.
- Bilingual KO/EN evidence, exact byte ranges, bounded displayed/exported occurrences plus total count; no heavy dependency.
- Preserve existing Content Check JSON keys and no-overwrite save behavior.

## Review Focus

- `vvcC` box structure: FullBox version 0, flags 0, valid `LengthSizeMinusOne` in 0..3 (Task 1).
- `VideoSampleTable` integration: `VideoCodec.VVC` correctly extracts `nalLengthSize` from `vvcC` (Task 1).
- NAL unit header: 2 bytes, `forbidden_zero_bit == 0`, `nuh_reserved_zero_bit == 0`, `nuh_temporal_id_plus1 != 0` (Task 2).
- Bounded findings: fixed 100 per-rule cap and `totalOccurrences` tracking (Task 3).
- Multiple video tracks: identify checked track and list others as unscanned (Task 4).

---

### Task 1: Real-byte fixtures, VvcCBoxDecoder, VvcRuleCatalog, and VideoSampleTable

**Files:** Create `docs/validation/vvc-conformance-fixtures/README.md`, `generate.py`, and fixtures under `app/src/test/resources/vvc-conformance/`; create `app/src/main/kotlin/com/multiviewer/parser/VvcCBoxDecoder.kt`, `app/src/main/kotlin/com/multiviewer/parser/conformance/VvcRuleCatalog.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/VvcRuleCatalogTest.kt`; add `VVC` to `VideoCodec` in `HdrMetadataModel.kt`, `"vvc1"` and `"vvi1"` to `SAMPLE_ENTRY_CODECS` and `buildVideoSampleTable` in `IsoSampleTable.kt`, and register `"vvcC"` in `Decoders.kt`.

**Interfaces:** Produce `object VvcRuleCatalog` with `fun rule(id: String): ConformanceRule?` and stable IDs: `vvc.config.vvcc`, `vvc.nal.framing`, `vvc.nal.forbidden_zero_bit`, `vvc.nal.reserved_zero_bit`, `vvc.nal.temporal_id_plus1`, `vvc.sample.framing`. `object VvcCBoxDecoder : BoxDecoder`.

- [ ] **Step 1: Generate fixtures.** Create Python generator creating valid `default-vvc.mp4` with verified VVC NAL units and valid `vvcC` box, record byte offsets in README. Create mutations for `bad-vvcc-box.mp4`, `bad-forbidden-bit.mp4`, `bad-reserved-bit.mp4`, `bad-temporal-id.mp4`, `truncated-sample.mp4`, and `many-nal-failures.mp4`.
- [ ] **Step 2: Write failing catalog and table tests.** Assert stable IDs, VVC-in-ISOBMFF and ITU-T H.266 sources, structural observation labeling, and `buildVideoSampleTable` resolution for `vvc1` and `vvi1`.
- [ ] **Step 3: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.VvcRuleCatalogTest'`; expect RED.
- [ ] **Step 4: Implement** `VvcCBoxDecoder`, `VvcRuleCatalog`, `VideoCodec.VVC`, `SAMPLE_ENTRY_CODECS` update in `IsoSampleTable.kt`, and `Decoders.kt` registration.
- [ ] **Step 5: Run** focused tests; expect PASS. Commit `test: establish VVC conformance byte fixtures and rule catalog`.

### Task 2: Bounded VVC sample and NAL header scanner

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/VvcSampleScanner.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/VvcSampleScannerTest.kt`.

**Interfaces:** `data class VvcConfigObservation(val vvccOffset: Long, val version: Int, val lengthSizeMinusOne: Int? = null, val ptlPresent: Boolean = false, val maxPictureWidth: Int? = null, val maxPictureHeight: Int? = null, val numOfArrays: Int? = null, val issue: String? = null)`; `fun parseVvcConfig(reader: ByteReader, vvcC: BoxNode): VvcConfigObservation`; `data class VvcNalObservation(val offset: Long, val size: Long, val forbiddenZeroBit: Boolean, val reservedZeroBit: Boolean, val layerId: Int, val nalUnitType: Int, val temporalIdPlus1: Int)`; `data class VvcSampleObservation(val sampleIndex: Int, val offset: Long, val size: Long, val nals: List<VvcNalObservation>, val issue: String? = null)`; `fun scanVvcSample(reader: ByteReader, sample: VideoSample, nalLengthSize: Int): VvcSampleObservation`. Scanner reads length-prefixed NAL units, verifies 2-byte header `forbidden_zero_bit == 0`, `nuh_reserved_zero_bit == 0`, and `nuh_temporal_id_plus1 != 0`.

- [ ] **Step 1: Write failing scanner tests.** Assert raw offsets and bit fields on real `vvc1` fixtures; `badVvccBox`, `badForbiddenBit`, `badReservedBit`, `badTemporalId`, `truncatedSample`, and sample extent bounds have exact offsets/status and never PASS for unreadable data.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.VvcSampleScannerTest'`; expect RED.
- [ ] **Step 3: Implement** bounded scanner with checked addition and bounded buffer reads; callers check coroutine cancellation between samples.
- [ ] **Step 4: Run** scanner tests; expect PASS. Commit `feat: scan VVC vvcC config and sample NAL headers with bounded reads`.

### Task 3: Parameter-set evidence and rule aggregation

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/VvcBitstreamReport.kt`, `VvcBitstreamAnalyzer.kt`, and `app/src/test/kotlin/com/multiviewer/parser/conformance/VvcBitstreamAnalyzerTest.kt`.

**Interfaces:** `data class VvcBitstreamReport(val findings: List<ConformanceFinding>, val checkedSamples: Int, val indexedSamples: Int, val totalOccurrences: Map<String, Long>, val limitations: List<String>, val checkedTrack: String, val complete: Boolean)`; `suspend fun analyzeVvcBitstream(file: File, root: BoxNode, onProgress: (Int, Int?) -> Unit): VvcBitstreamReport`. The analyzer calls `buildVideoSampleTable`, validates `vvcC`, and runs the scanner from Task 2; it emits verified header findings and supported observations. Keep a fixed per-rule occurrence cap (100), reporting totals separately.

- [ ] **Step 1: Write failing tests.** Valid `vvc1` reports checked/indexed counts and exact header ranges; corrupted forbidden/reserved/temporal bits have stable rule ID and observed bits; `malformedVvcc` yields NOT_CHECKED; 101 repeated violations show bounded findings and full occurrence count.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.VvcBitstreamAnalyzerTest'`; expect RED.
- [ ] **Step 3: Implement** aggregation and config applicability with documented parser limits. Retain exact sample/track evidence in details and ranges.
- [ ] **Step 4: Run** focused analyzer tests; expect PASS. Commit `feat: evaluate bounded VVC bitstream rules`.

### Task 4: Scope/coverage and workflow integration

**Files:** Modify `ContentCheckStage.kt`, `ContentCheckWindow.kt`, `ContentCheckSnapshot.kt`; create `VvcContentFindings.kt` and `app/src/test/kotlin/com/multiviewer/ui/VvcContentCheckIntegrationTest.kt`.

**Interfaces:** Codec stage invokes `analyzeVvcBitstream` when video entry is `vvc1` or `vvi1` with valid `vvcC`. Extend snapshot with `val vvcBitstream: VvcBitstreamReport? = null`; append VVC findings to conformance detail. Other tracks, fragments, encryption, mismatched tables, cap truncation, changed files, and cancelled scans expose named NOT_CHECKED coverage and cannot yield overall PASS.

- [ ] **Step 1: Write failing tests.** `otherVideoTrackIsNotChecked`, fragment/encrypted/unreadable sample coverage, malformed `vvcC`, cap truncation, cancellation after sample 1, file changed before publish, and default `vvc1` availability in `MediaType.VIDEO`.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.ui.VvcContentCheckIntegrationTest'`; expect RED.
- [ ] **Step 3: Implement** capability detection in `ContentCheckStage.kt`, dispatcher/cancellation, progress, and UI presentation in `ContentCheckWindow.kt`.
- [ ] **Step 4: Run** focused integration tests, `./gradlew :app:compileKotlin`, then `./gradlew :app:test`; expect PASS.
- [ ] **Step 5: Commit** `feat: expose VVC rule coverage in Content Check`.

## Branch finish

- [ ] Review exact normative citations and real-byte dumps against source standards; downgrade any unverified rule to structural observation or NOT_CHECKED.
- [ ] Run `./gradlew :app:test` and `git diff --check`; fix nonflaky failures, review whole branch, and commit fixes.
- [ ] Fast-forward local `main` only after review; do not push without explicit user authorization.
