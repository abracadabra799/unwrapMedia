# VP9 Bitstream Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded VP9 rule runner for non-fragmented `vp09` MP4/MOV video, with exact byte evidence and explicit untested scope.

**Architecture:** A parser-side scanner reads VP9 frame headers and superframe index from the existing sample index without loading whole samples. `VpcCBoxDecoder` decodes the `vpcC` configuration box. A narrow rule catalog converts verified observations into findings; unsupported syntax and coverage gaps remain NOT_CHECKED. The Content Check codec stage invokes this runner alongside HEVC, AVC, and AV1 through the workflow stage interface.

**Tech Stack:** Kotlin, existing ISO-BMFF readers, JDK file I/O, coroutines, JUnit, ffmpeg/libvpx-vp9 fixture generation.

**Spec:** `docs/superpowers/specs/2026-10-17-vp9-bitstream-check-design.md` (depends on `2026-10-17-content-check-workflow-design.md`)

## Global Constraints

- Scope is `MediaType.VIDEO`, first video track with `vp09`, valid `vpcC`, non-fragmented `moov` sample tables, at most `MAX_VIDEO_INDEXED_SAMPLES = 1_000_000`.
- Rule ID/source/edition/clause must be independently checked before labeling a result normative; otherwise label structural observation.
- Unsupported syntax is NOT_CHECKED unless a verified rule resolves it; never guess FAIL or PASS.
- No full VP9 conformance or Android MediaCodec compatibility claim; decoder output remains a separate operation diagnostic.
- Bounded reads, checked arithmetic, cancellation between samples/frames, `Dispatchers.IO`, file identity before publishing.
- Bilingual KO/EN evidence, exact byte ranges, bounded displayed/exported occurrences plus total count; no heavy dependency.
- Preserve existing Content Check JSON keys and no-overwrite save behavior.

## Review Focus

- Superframe index at end of sample vs single frame: detect marker `0b110xxxxx` and parse frame sizes; incomplete index reports FAIL with exact offset (Task 2 test `truncatedSuperframe`).
- A sample offset plus size overflows `Long`: mark sample unreadable/partial, never wrap into a valid range (Task 2 test `sampleExtentOverflow`).
- Frame marker (bits 7..6) is not 2: report FAIL with exact 1-byte range and bits (Task 2 test `badMarker`).
- Keyframe sync code is not `0x498342`: report FAIL with exact 3-byte range and bytes (Task 2 test `badSyncCode`).
- Two video tracks where the first is VP9 and second is other: identify checked track and explicitly list the second as unscanned (Task 4 test `otherVideoTrackIsNotChecked`).
- An HEIC/AVIF motion photo embeds `moov`/`vp09`: entry point rejects because tab media type is IMAGE (Task 4 test `imageEmbeddedVideoIsExcluded`).

---

### Task 1: Real-byte fixtures, VpcCBoxDecoder, and verified rule catalog

**Files:** Create `docs/validation/vp9-conformance-fixtures/README.md`, `generate.sh`, `mutate.py`, and small fixtures under `app/src/test/resources/vp9-conformance/`; create `app/src/main/kotlin/com/multiviewer/parser/VpcCBoxDecoder.kt`, `app/src/main/kotlin/com/multiviewer/parser/conformance/Vp9RuleCatalog.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/Vp9RuleCatalogTest.kt`; update `VideoCodec` enum and `BoxParser.kt`.

**Interfaces:** Produce `object Vp9RuleCatalog` with `fun rule(id: String): ConformanceRule?` and stable IDs for framing (`vp9.frame.framing`), marker (`vp9.frame.marker`), sync code (`vp9.frame.sync_code`), profile 3 reserved bit (`vp9.frame.profile_reserved_bit`), and vpcC (`vp9.config.vpcc`). Register `vpcC` in `BoxParser.kt`.

- [x] **Step 1: Generate and independently dump fixtures.** Pin ffmpeg/libvpx-vp9 version and commands, create a standard `vp09` MP4 file with multiple samples, record raw sample-entry bytes, `vpcC`, first packet offsets, and expected values in README. Create one-byte mutations for bad marker, bad sync code, truncated frame, malformed vpcC, and 101 malformed markers.
- [x] **Step 2: Write failing catalog and decoder tests.** Assert stable IDs, VP9 source/edition, scope, structural observation labeling, and `VpcCBoxDecoder` field parsing.
- [x] **Step 3: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.Vp9RuleCatalogTest'`; expect RED.
- [x] **Step 4: Implement** `VpcCBoxDecoder`, `Vp9RuleCatalog`, add `VP9` to `VideoCodec`, and add `vp09` to `SAMPLE_ENTRY_CODECS`.
- [x] **Step 5: Run** focused tests; expect PASS. Commit `test: establish VP9 conformance byte fixtures and rule catalog`.

### Task 2: Bounded VP9 frame and superframe scanner

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/Vp9FrameScanner.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/Vp9FrameScannerTest.kt`.

**Interfaces:** `data class Vp9FrameObservation(val sampleIndex: Int, val frameIndex: Int, val offset: Long, val size: Long, val frameMarker: Int, val profile: Int, val showExistingFrame: Boolean, val frameType: Int?, val showFrame: Boolean?, val errorResilientMode: Boolean?, val syncCode: Int?, val bitDepth: Int?, val colorSpace: Int?, val width: Int?, val height: Int?, val profileReservedBit: Int?, val issue: String?)`; `fun scanVp9Sample(reader: ByteReader, sample: VideoSample, onFrame: (Vp9FrameObservation) -> Unit): Vp9SampleScan`. `Vp9SampleScan` stores checked frame count, exact consumed bytes, and a coverage reason. Scanner checks superframe marker at end of sample or treats as single frame, reads uncompressed header, checks bounds before reads, and stops on malformed extent.

- [x] **Step 1: Write failing tests.** Assert raw offsets and bit fields on real `vp09` fixtures; `truncatedFrame`, `sampleExtentOverflow`, `badMarker`, `badSyncCode`, and `profileReservedBit` mutations have exact offsets/status and never PASS for unreadable data.
- [x] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.Vp9FrameScannerTest'`; expect RED.
- [x] **Step 3: Implement** bounded scanner with checked addition and no sample-sized buffer allocation; callers check coroutine cancellation between frames.
- [x] **Step 4: Run** scanner tests; expect PASS. Commit `feat: scan VP9 frame headers with bounded reads`.

### Task 3: Parameter-set evidence and rule aggregation

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/Vp9BitstreamReport.kt`, `Vp9BitstreamAnalyzer.kt`, and `app/src/test/kotlin/com/multiviewer/parser/conformance/Vp9BitstreamAnalyzerTest.kt`.

**Interfaces:** `data class Vp9BitstreamReport(val findings: List<ConformanceFinding>, val checkedSamples: Int, val indexedSamples: Int, val totalOccurrences: Map<String, Long>, val limitations: List<String>, val checkedTrack: String, val complete: Boolean)`; `suspend fun analyzeVp9Bitstream(file: File, root: BoxNode, onProgress: (Int, Int?) -> Unit): Vp9BitstreamReport`. The analyzer calls `buildVideoSampleTable`, checks `vpcC` configuration box, and scanner from Task 2; it emits verified header findings and supported `vpcC` observations. Keep a fixed per-rule occurrence cap (100), reporting totals separately.

- [ ] **Step 1: Write failing tests.** Valid `vp09` reports checked/indexed counts and exact header ranges; corrupted header has stable rule ID and observed bits; `unsupportedParameterSyntaxIsNotFailure`; failed prerequisite yields NOT_CHECKED; 101 repeated violations show bounded findings and full occurrence count.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.Vp9BitstreamAnalyzerTest'`; expect RED.
- [ ] **Step 3: Implement** aggregation and vpcC applicability with documented parser limits. Retain exact sample/frame/track evidence in details and ranges.
- [ ] **Step 4: Run** focused analyzer tests; expect PASS. Commit `feat: evaluate bounded VP9 bitstream rules`.

### Task 4: Scope/coverage and workflow integration

**Files:** Modify `ContentCheckStage.kt`, `ContentCheckWindow.kt`, `ContentCheckSnapshot.kt`; create `Vp9ContentFindings.kt` and `app/src/test/kotlin/com/multiviewer/ui/Vp9ContentCheckIntegrationTest.kt`.

**Interfaces:** Codec stage invokes `analyzeVp9Bitstream` when first video entry is `vp09` with valid `vpcC`. Extend snapshot with `val vp9Bitstream: Vp9BitstreamReport? = null`; append VP9 findings to conformance detail. Other tracks, fragments, encryption, mismatched tables, cap truncation, changed files, and cancelled scans expose named NOT_CHECKED coverage and cannot yield overall PASS.

- [ ] **Step 1: Write failing tests.** `otherVideoTrackIsNotChecked`, `imageEmbeddedVideoIsExcluded`, fragment/encrypted/unreadable sample coverage, malformed `vpcC`, cap truncation, cancellation after sample 1, file changed before publish, and default `vp09` availability.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.ui.Vp9ContentCheckIntegrationTest'`; expect RED.
- [ ] **Step 3: Implement** capability detection in `ContentCheckStage.kt`, dispatcher/cancellation, progress, and UI presentation in `ContentCheckWindow.kt`.
- [ ] **Step 4: Run** focused integration tests, `./gradlew :app:compileKotlin`, then `./gradlew :app:test`; expect PASS.
- [ ] **Step 5: Commit** `feat: expose VP9 rule coverage in Content Check`.

## Branch finish

- [ ] Review exact normative citations and real-byte dumps against source standards; downgrade any unverified rule to structural observation or NOT_CHECKED.
- [ ] Run `./gradlew :app:test` and `git diff --check`; fix nonflaky failures, review whole branch, and commit fixes.
- [ ] Fast-forward local `main` only after review; do not push without explicit user authorization.
