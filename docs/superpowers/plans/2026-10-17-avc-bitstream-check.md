# H.264 / AVC Bitstream Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded H.264 / AVC rule runner for non-fragmented `avc1` MP4/MOV video, with exact byte evidence and explicit untested scope.

**Architecture:** A parser-side scanner reads length-prefixed NAL headers from the existing sample index without loading whole samples. A narrow rule catalog converts verified observations into findings; unsupported syntax and coverage gaps remain NOT_CHECKED. The Content Check codec stage invokes this runner alongside HEVC through the workflow stage interface.

**Tech Stack:** Kotlin, existing ISO-BMFF/AVC readers, JDK file I/O, coroutines, JUnit, ffmpeg/x264 fixture generation.

**Spec:** `docs/superpowers/specs/2026-10-17-avc-bitstream-check-design.md` (depends on `2026-10-17-content-check-workflow-design.md`)

## Global Constraints

- Scope is `MediaType.VIDEO`, first video track with `avc1`, valid `avcC`, non-fragmented `moov` sample tables, at most `MAX_VIDEO_INDEXED_SAMPLES = 1_000_000`.
- Rule ID/source/edition/clause must be independently checked before labeling a result normative; otherwise label structural observation.
- Zero-length NAL and unsupported SPS/PPS/slice syntax are NOT_CHECKED unless a verified rule resolves them; never guess FAIL or PASS.
- No full AVC conformance or Android MediaCodec compatibility claim; decoder output remains a separate operation diagnostic.
- Bounded reads, checked arithmetic, cancellation between samples/NALs, `Dispatchers.IO`, file identity before publishing.
- Bilingual KO/EN evidence, exact byte ranges, bounded displayed/exported occurrences plus total count; no heavy dependency.
- Preserve existing Content Check JSON keys and no-overwrite save behavior.

## Review Focus

- `avcC` says 4-byte lengths while a sample ends after 1–3 prefix bytes: report exact prefix offset without out-of-bounds read (Task 2 test `truncatedLengthPrefix`).
- A sample offset plus size overflows `Long`: mark sample unreadable/partial, never wrap into a valid range (Task 2 test `sampleExtentOverflow`).
- IDR slice with `nal_ref_idc == 0`: report FAIL with exact 1-byte header range and bits (Task 2 test `idrZeroRefIdc`).
- Two video tracks where the first is AVC and second is AVC/HEVC: identify checked track and explicitly list the second as unscanned (Task 4 test `otherVideoTrackIsNotChecked`).
- An HEIC motion photo embeds `moov`/`avcC`: entry point rejects because tab media type is IMAGE (Task 4 test `heicEmbeddedVideoIsExcluded`).

---

### Task 1: Real-byte fixtures and verified rule catalog

**Files:** Create `docs/validation/avc-conformance-fixtures/README.md`, `generate.sh`, `mutate.py`, and small fixtures under `app/src/test/resources/avc-conformance/`; create `app/src/main/kotlin/com/multiviewer/parser/conformance/AvcRuleCatalog.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/AvcRuleCatalogTest.kt`.

**Interfaces:** Produce `object AvcRuleCatalog` with `fun rule(id: String): ConformanceRule?` and stable IDs for framing (`avc.nal.framing`), `forbidden_zero_bit` (`avc.nal.forbidden_zero_bit`), and IDR reference check (`avc.nal.idr_ref_idc`). Document H.264 edition (08/21) and exact clause used for header bits; treat length framing as a structural observation unless the applicable ISO/IEC 14496-15 requirement is verified.

- [x] **Step 1: Generate and independently dump fixtures.** Pin ffmpeg version and commands, create a standard `avc1` MP4 file with multiple samples, record raw sample-entry bytes, `avcC`, first NAL prefix/header offsets, and expected values in README. Create one-byte mutations for forbidden bit, IDR with zero ref, truncated prefix, oversize NAL, and malformed `avcC`. Record any fixture that cannot be generated.
- [x] **Step 2: Write failing catalog tests.** Assert stable IDs, H.264 source/edition/clause on header rules, scope, and structural observation labeling.
- [x] **Step 3: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.AvcRuleCatalogTest'`; expect RED.
- [x] **Step 4: Implement** the catalog and fixture provenance; compare raw bytes to independent inspection, using raw bytes as test truth.
- [x] **Step 5: Run** focused tests; expect PASS. Commit `test: establish AVC conformance byte fixtures and rule catalog`.

### Task 2: Bounded NAL framing and header scanner

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/AvcNalScanner.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/AvcNalScannerTest.kt`.

**Interfaces:** `data class AvcNalObservation(val sampleIndex: Int, val nalIndex: Int, val prefixOffset: Long, val headerOffset: Long?, val nalType: Int?, val forbiddenZeroBit: Int?, val refIdc: Int?, val issue: String?)`; `fun scanAvcSample(reader: ByteReader, sample: VideoSample, lengthSize: Int, onNal: (AvcNalObservation) -> Unit): AvcSampleScan`. `AvcSampleScan` stores scanned NAL count, exact consumed bytes, and a coverage reason. Scanner checks `lengthSize in 1..4`, sample bounds and overflow before reads, reads prefix + 1 header byte, and stops on malformed extent. Zero-length NAL emits a NOT_CHECKED observation.

- [x] **Step 1: Write failing tests.** Assert raw offsets and bit fields on real `avc1` fixtures; `truncatedLengthPrefix`, oversize NAL, `sampleExtentOverflow`, zero-length NAL, forbidden bit, and IDR with ref 0 have exact offsets/status and never PASS for unreadable data.
- [x] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.AvcNalScannerTest'`; expect RED.
- [x] **Step 3: Implement** bounded scanner with checked addition and no sample-sized buffer allocation; callers check coroutine cancellation between NALs.
- [x] **Step 4: Run** scanner tests; expect PASS. Commit `feat: scan AVC NAL headers with bounded reads`.

### Task 3: Parameter-set evidence and rule aggregation

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/AvcBitstreamReport.kt`, `AvcBitstreamAnalyzer.kt`, and `app/src/test/kotlin/com/multiviewer/parser/conformance/AvcBitstreamAnalyzerTest.kt`; reuse `H264ParameterSetExtraction.kt` and `H264ParameterSets.kt`.

**Interfaces:** `data class AvcBitstreamReport(val findings: List<ConformanceFinding>, val checkedSamples: Int, val indexedSamples: Int, val totalOccurrences: Map<String, Long>, val limitations: List<String>, val checkedTrack: String, val complete: Boolean)`; `suspend fun analyzeAvcBitstream(file: File, root: BoxNode, onProgress: (Int, Int?) -> Unit): AvcBitstreamReport`. The analyzer calls `buildVideoSampleTable`, `extractAvcCRawParameterSets`, and scanner from Task 2; it emits verified header findings and only supported SPS/PPS reference findings. Keep a fixed per-rule occurrence cap (100), reporting totals separately.

- [x] **Step 1: Write failing tests.** Valid `avc1` reports checked/indexed counts and exact header ranges; corrupted header has stable rule ID and observed bits; `unsupportedParameterSyntaxIsNotFailure`; failed prerequisite yields NOT_CHECKED; 101 repeated violations show bounded findings and full occurrence count.
- [x] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.AvcBitstreamAnalyzerTest'`; expect RED.
- [x] **Step 3: Implement** aggregation and parameter-set applicability with documented parser limits. Retain exact sample/NAL/track evidence in details and ranges.
- [x] **Step 4: Run** focused analyzer tests; expect PASS. Commit `feat: evaluate bounded AVC bitstream rules`.

### Task 4: Scope/coverage and workflow integration

**Files:** Modify `ContentCheckStage.kt`, `ContentCheckWindow.kt`, `ContentCheckSnapshot.kt`; create `AvcContentFindings.kt` and `app/src/test/kotlin/com/multiviewer/ui/AvcContentCheckIntegrationTest.kt`.

**Interfaces:** Codec stage invokes `analyzeAvcBitstream` when first video entry is `avc1` with valid `avcC` (and `analyzeHevcBitstream` when `hvc1`/`hev1`). Extend snapshot with `val avcBitstream: AvcBitstreamReport? = null`; append AVC findings to conformance detail. Other tracks, fragments, encryption, mismatched tables, cap truncation, changed files, and cancelled scans expose named NOT_CHECKED coverage and cannot yield overall PASS.

- [x] **Step 1: Write failing tests.** `otherVideoTrackIsNotChecked`, `heicEmbeddedVideoIsExcluded`, fragment/encrypted/unreadable sample coverage, malformed `avcC`, cap truncation, cancellation after sample 1, file changed before publish, and default `avc1` availability.
- [x] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.ui.AvcContentCheckIntegrationTest'`; expect RED.
- [x] **Step 3: Implement** capability detection in `ContentCheckStage.kt`, dispatcher/cancellation, progress, and UI presentation in `ContentCheckWindow.kt`.
- [x] **Step 4: Run** focused integration tests, `./gradlew :app:compileKotlin`, then `./gradlew :app:test`; expect PASS.
- [x] **Step 5: Commit** `feat: expose AVC rule coverage in Content Check`.

## Branch finish

- [x] Review exact normative citations and real-byte dumps against source standards; downgrade any unverified rule to structural observation or NOT_CHECKED.
- [x] Run `./gradlew :app:test` and `git diff --check`; fix nonflaky failures, review whole branch, and commit fixes.
- [ ] Fast-forward local `main` only after review; do not push without explicit user authorization.
