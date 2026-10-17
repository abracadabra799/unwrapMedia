# FLAC Bitstream Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded FLAC (`fLaC`) rule runner for non-fragmented MP4/MOV audio, with exact byte evidence and explicit untested scope.

**Architecture:** A parser-side scanner reads FLAC `dfLa` (`FLACSpecificBox`) containing `STREAMINFO` and sample prefixes from the audio sample index using bounded reads (max 512 bytes per sample for header + CRC check). A narrow rule catalog converts verified observations into findings; unsupported syntax and coverage gaps remain NOT_CHECKED. The Content Check codec stage invokes this runner for `MediaType.AUDIO` (`fLaC`) and supported audio tracks through the workflow stage interface.

**Tech Stack:** Kotlin, existing ISO-BMFF readers (`IsoSampleTable.kt`, `AudioSampleEntryDecoder.kt`), FLAC CRC helpers (`FlacStructure.kt`), JDK file I/O, coroutines, JUnit, Python fixture generator.

**Spec:** `docs/superpowers/specs/2026-10-17-flac-bitstream-check-design.md` (depends on `2026-10-17-content-check-workflow-design.md`)

## Global Constraints

- Scope is MP4/MOV audio track with `fLaC`, valid `dfLa` box, non-fragmented `moov` sample tables, at most `MAX_AUDIO_INDEXED_SAMPLES = 1_000_000`.
- Active in `CheckStage.CODEC` when `MediaType.AUDIO` with `fLaC` is present.
- Rule ID/source/edition/clause must be independently checked before labeling a result normative; otherwise label structural observation.
- Unsupported syntax is NOT_CHECKED unless a verified rule resolves it; never guess FAIL or PASS.
- Bounded reads (at most 512 bytes for header prefix), checked arithmetic, cancellation between samples, `Dispatchers.IO`, file identity before publishing.
- Bilingual KO/EN evidence, exact byte ranges, bounded displayed/exported occurrences plus total count; no heavy dependency.
- Preserve existing Content Check JSON keys and no-overwrite save behavior.

## Review Focus

- `dfLa` box structure: FullBox version 0, flags 0, first metadata block is `STREAMINFO` (34 bytes), parameter bounds (Task 1).
- `fLaC` AudioSampleEntry match: `channelcount` == channels + 1, `samplesize` == bits_per_sample + 1, `samplerate` == sample_rate (Task 2).
- Frame header: 14-bit syncword `0x3FFE`, reserved bits 0, valid codes, valid UTF-8, CRC-8 match (Task 2).
- Frame CRC-16: stored 16-bit CRC matches computed CRC-16 (Task 2).
- Multiple audio tracks: identify checked track and list others as unscanned (Task 4).

---

### Task 1: Real-byte fixtures, DflaBoxDecoder, FlacRuleCatalog, and AudioSampleTable

**Files:** Create `docs/validation/flac-conformance-fixtures/README.md`, `generate.py`, and fixtures under `app/src/test/resources/flac-conformance/`; create `app/src/main/kotlin/com/multiviewer/parser/DflaBoxDecoder.kt`, `app/src/main/kotlin/com/multiviewer/parser/conformance/FlacRuleCatalog.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/FlacRuleCatalogTest.kt`; add `"fLaC"` to `AUDIO_SAMPLE_ENTRY_CODECS` in `IsoSampleTable.kt` and register in `Decoders.kt`.

**Interfaces:** Produce `object FlacRuleCatalog` with `fun rule(id: String): ConformanceRule?` and stable IDs: `flac.config.dfla`, `flac.config.match`, `flac.frame.header`, `flac.frame.crc`, `flac.sample.framing`. `object DflaBoxDecoder : BoxDecoder`.

- [ ] **Step 1: Generate fixtures.** Create Python generator creating valid `default-flac.mp4` with verified FLAC samples and valid `dfLa` box, record byte offsets in README. Create mutations for `bad-dfla-version.mp4`, `bad-streaminfo-size.mp4`, `bad-config-match.mp4`, `bad-frame-sync.mp4`, `bad-frame-crc8.mp4`, `bad-frame-crc16.mp4`, `truncated-sample.mp4`, and `many-sync-failures.mp4`.
- [ ] **Step 2: Write failing catalog and table tests.** Assert stable IDs, FLAC-in-ISOBMFF and FLAC format sources, structural observation labeling, and `buildAudioSampleTable` resolution for `fLaC`.
- [ ] **Step 3: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.FlacRuleCatalogTest'`; expect RED.
- [ ] **Step 4: Implement** `DflaBoxDecoder`, `FlacRuleCatalog`, `AUDIO_SAMPLE_ENTRY_CODECS += "fLaC"` in `IsoSampleTable.kt`, and `Decoders.kt` registration.
- [ ] **Step 5: Run** focused tests; expect PASS. Commit `test: establish FLAC conformance byte fixtures and rule catalog`.

### Task 2: Bounded FLAC sample and header scanner

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/FlacSampleScanner.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/FlacSampleScannerTest.kt`.

**Interfaces:** `data class FlacConfigObservation(val dflaOffset: Long, val version: Int, val minBlockSize: Int? = null, val maxBlockSize: Int? = null, val sampleRate: Long? = null, val channels: Int? = null, val bitsPerSample: Int? = null, val issue: String? = null)`; `fun parseFlacConfig(reader: ByteReader, dfLa: BoxNode): FlacConfigObservation`; `data class FlacSampleObservation(val sampleIndex: Int, val offset: Long, val size: Long, val syncValid: Boolean, val headerCrcValid: Boolean, val frameCrcValid: Boolean, val blockSize: Int? = null, val sampleRate: Long? = null, val channels: Int? = null, val issue: String? = null)`; `fun scanFlacSample(reader: ByteReader, sample: AudioSample, config: FlacConfigObservation): FlacSampleObservation`. Scanner reads bounded header prefix (up to 512 bytes), verifies 14-bit syncword `0x3FFE`, reserved 0 bits, code ranges, UTF-8 frame number, header CRC-8, and verifies trailing 16-bit frame CRC.

- [ ] **Step 1: Write failing scanner tests.** Assert raw offsets and bit fields on real `fLaC` fixtures; `badDflaVersion`, `badFrameSync`, `badFrameCrc8`, `badFrameCrc16`, `truncatedSample`, and sample extent bounds have exact offsets/status and never PASS for unreadable data.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.FlacSampleScannerTest'`; expect RED.
- [ ] **Step 3: Implement** bounded scanner with checked addition and bounded buffer prefix; callers check coroutine cancellation between samples.
- [ ] **Step 4: Run** scanner tests; expect PASS. Commit `feat: scan FLAC dfLa config and sample headers with bounded reads`.

### Task 3: Parameter-set evidence and rule aggregation

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/FlacBitstreamReport.kt`, `FlacBitstreamAnalyzer.kt`, and `app/src/test/kotlin/com/multiviewer/parser/conformance/FlacBitstreamAnalyzerTest.kt`.

**Interfaces:** `data class FlacBitstreamReport(val findings: List<ConformanceFinding>, val checkedSamples: Int, val indexedSamples: Int, val totalOccurrences: Map<String, Long>, val limitations: List<String>, val checkedTrack: String, val complete: Boolean)`; `suspend fun analyzeFlacBitstream(file: File, root: BoxNode, onProgress: (Int, Int?) -> Unit): FlacBitstreamReport`. The analyzer calls `buildAudioSampleTable`, checks `dfLa` and AudioSampleEntry match, and runs the scanner from Task 2; it emits verified header findings and supported observations. Keep a fixed per-rule occurrence cap (100), reporting totals separately.

- [ ] **Step 1: Write failing tests.** Valid `fLaC` reports checked/indexed counts and exact header ranges; corrupted sync/CRC bits have stable rule ID and observed bits; `malformedDfla` yields NOT_CHECKED; 101 repeated violations show bounded findings and full occurrence count.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.FlacBitstreamAnalyzerTest'`; expect RED.
- [ ] **Step 3: Implement** aggregation and config applicability with documented parser limits. Retain exact sample/track evidence in details and ranges.
- [ ] **Step 4: Run** focused analyzer tests; expect PASS. Commit `feat: evaluate bounded FLAC bitstream rules`.

### Task 4: Scope/coverage and workflow integration

**Files:** Modify `ContentCheckStage.kt`, `ContentCheckWindow.kt`, `ContentCheckSnapshot.kt`; create `FlacContentFindings.kt` and `app/src/test/kotlin/com/multiviewer/ui/FlacContentCheckIntegrationTest.kt`.

**Interfaces:** Codec stage invokes `analyzeFlacBitstream` when audio entry is `fLaC` with valid `dfLa`. Extend snapshot with `val flacBitstream: FlacBitstreamReport? = null`; append FLAC findings to conformance detail. Other tracks, fragments, encryption, mismatched tables, cap truncation, changed files, and cancelled scans expose named NOT_CHECKED coverage and cannot yield overall PASS.

- [ ] **Step 1: Write failing tests.** `otherAudioTrackIsNotChecked`, fragment/encrypted/unreadable sample coverage, malformed `dfLa`, cap truncation, cancellation after sample 1, file changed before publish, and default `fLaC` availability in `MediaType.AUDIO`.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.ui.FlacContentCheckIntegrationTest'`; expect RED.
- [ ] **Step 3: Implement** capability detection in `ContentCheckStage.kt`, dispatcher/cancellation, progress, and UI presentation in `ContentCheckWindow.kt`.
- [ ] **Step 4: Run** focused integration tests, `./gradlew :app:compileKotlin`, then `./gradlew :app:test`; expect PASS.
- [ ] **Step 5: Commit** `feat: expose FLAC rule coverage in Content Check`.

## Branch finish

- [ ] Review exact normative citations and real-byte dumps against source standards; downgrade any unverified rule to structural observation or NOT_CHECKED.
- [ ] Run `./gradlew :app:test` and `git diff --check`; fix nonflaky failures, review whole branch, and commit fixes.
- [ ] Fast-forward local `main` only after review; do not push without explicit user authorization.
