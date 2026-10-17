# Opus Bitstream Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded Opus (`Opus`) rule runner for non-fragmented MP4/MOV audio, with exact byte evidence and explicit untested scope.

**Architecture:** A parser-side scanner reads Opus `dOps` (`OpusSpecificBox`) and sample prefixes from the audio sample index using bounded reads (max 512 bytes per audio sample). A narrow rule catalog converts verified observations into findings; unsupported syntax and coverage gaps remain NOT_CHECKED. The Content Check codec stage invokes this runner for `MediaType.AUDIO` (`Opus`) and supported audio tracks through the workflow stage interface.

**Tech Stack:** Kotlin, existing ISO-BMFF readers (`IsoSampleTable.kt`, `AudioSampleEntryDecoder.kt`), JDK file I/O, coroutines, JUnit, Python fixture generator.

**Spec:** `docs/superpowers/specs/2026-10-17-opus-bitstream-check-design.md` (depends on `2026-10-17-content-check-workflow-design.md`)

## Global Constraints

- Scope is MP4/MOV audio track with `Opus`, valid `dOps` box, non-fragmented `moov` sample tables, at most `MAX_AUDIO_INDEXED_SAMPLES = 1_000_000`.
- Active in `CheckStage.CODEC` when `MediaType.AUDIO` with `Opus` is present.
- Rule ID/source/edition/clause must be independently checked before labeling a result normative; otherwise label structural observation.
- Unsupported syntax is NOT_CHECKED unless a verified rule resolves it; never guess FAIL or PASS.
- Bounded reads (at most 512 bytes per sample), checked arithmetic, cancellation between samples, `Dispatchers.IO`, file identity before publishing.
- Bilingual KO/EN evidence, exact byte ranges, bounded displayed/exported occurrences plus total count; no heavy dependency.
- Preserve existing Content Check JSON keys and no-overwrite save behavior.

## Review Focus

- `dOps` box structure: version 0, `OutputChannelCount` in 1..255, `ChannelMappingFamily` valid (0, 1, or 255), family 0 channel count in {1, 2}, family 1 stream/coupled consistency (Task 1).
- `Opus` AudioSampleEntry match: `channelcount` == `dOps.OutputChannelCount`, `samplerate` == 48000 Hz (Task 2).
- TOC byte: config 0..31, mono track stereo flag `s == 0` (Task 2).
- Code 3 packet framing: frame count `M != 0`, total duration `M * duration <= 120ms`, padding fit (Task 2).
- Multiple audio tracks: identify checked track and list others as unscanned (Task 4).

---

### Task 1: Real-byte fixtures, DopsBoxDecoder, OpusRuleCatalog, and AudioSampleTable

**Files:** Create `docs/validation/opus-conformance-fixtures/README.md`, `generate.py`, and fixtures under `app/src/test/resources/opus-conformance/`; create `app/src/main/kotlin/com/multiviewer/parser/DopsBoxDecoder.kt`, `app/src/main/kotlin/com/multiviewer/parser/conformance/OpusRuleCatalog.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/OpusRuleCatalogTest.kt`; add `"Opus"` to `AUDIO_SAMPLE_ENTRY_CODECS` in `IsoSampleTable.kt` and register in `Decoders.kt`.

**Interfaces:** Produce `object OpusRuleCatalog` with `fun rule(id: String): ConformanceRule?` and stable IDs: `opus.config.dops`, `opus.config.match`, `opus.packet.toc`, `opus.packet.framing`, `opus.sample.framing`. `object DopsBoxDecoder : BoxDecoder`.

- [x] **Step 1: Generate fixtures.** Create Python generator creating valid `default-opus.mp4` with verified Opus samples and valid `dOps` box, record byte offsets in README. Create mutations for `bad-dops-version.mp4`, `bad-channel-count.mp4`, `bad-config-match.mp4`, `bad-mono-stereo-flag.mp4`, `bad-code3-zero-frames.mp4`, `truncated-sample.mp4`, and `many-toc-failures.mp4`.
- [x] **Step 2: Write failing catalog and table tests.** Assert stable IDs, RFC 6716 and RFC 7845 sources, structural observation labeling, and `buildAudioSampleTable` resolution for `Opus`.
- [x] **Step 3: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.OpusRuleCatalogTest'`; expect RED.
- [x] **Step 4: Implement** `DopsBoxDecoder`, `OpusRuleCatalog`, `AUDIO_SAMPLE_ENTRY_CODECS += "Opus"` in `IsoSampleTable.kt`, and `Decoders.kt` registration.
- [x] **Step 5: Run** focused tests; expect PASS. Commit `test: establish Opus conformance byte fixtures and rule catalog`.

### Task 2: Bounded Opus sample and header scanner

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/OpusSampleScanner.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/OpusSampleScannerTest.kt`.

**Interfaces:** `data class OpusConfigObservation(val dopsOffset: Long, val version: Int, val channelCount: Int, val preSkip: Int, val sampleRate: Long, val gain: Int, val mappingFamily: Int, val streamCount: Int? = null, val coupledCount: Int? = null, val issue: String?)`; `fun parseOpusConfig(reader: ByteReader, dOps: BoxNode): OpusConfigObservation`; `data class OpusSampleObservation(val sampleIndex: Int, val offset: Long, val size: Long, val tocConfig: Int?, val isStereo: Boolean?, val frameCountCode: Int?, val frameCount: Int?, val issue: String?)`; `fun scanOpusSample(reader: ByteReader, sample: AudioSample, channelCount: Int): OpusSampleObservation`. Scanner reads bounded prefix (up to 512 bytes), checks bounds, parses TOC byte, validates stereo flag against channel count, and validates Code 3 packet frame count `M != 0` and duration limits.

- [x] **Step 1: Write failing scanner tests.** Assert raw offsets and bit fields on real `Opus` fixtures; `badDopsVersion`, `badMonoStereoFlag`, `badCode3ZeroFrames`, `truncatedSample`, and sample extent bounds have exact offsets/status and never PASS for unreadable data.
- [x] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.OpusSampleScannerTest'`; expect RED.
- [x] **Step 3: Implement** bounded scanner with checked addition and bounded buffer prefix; callers check coroutine cancellation between samples.
- [x] **Step 4: Run** scanner tests; expect PASS. Commit `feat: scan Opus dOps config and sample headers with bounded reads`.

### Task 3: Parameter-set evidence and rule aggregation

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/OpusBitstreamReport.kt`, `OpusBitstreamAnalyzer.kt`, and `app/src/test/kotlin/com/multiviewer/parser/conformance/OpusBitstreamAnalyzerTest.kt`.

**Interfaces:** `data class OpusBitstreamReport(val findings: List<ConformanceFinding>, val checkedSamples: Int, val indexedSamples: Int, val totalOccurrences: Map<String, Long>, val limitations: List<String>, val checkedTrack: String, val complete: Boolean)`; `suspend fun analyzeOpusBitstream(file: File, root: BoxNode, onProgress: (Int, Int?) -> Unit): OpusBitstreamReport`. The analyzer calls `buildAudioSampleTable`, checks `dOps` and AudioSampleEntry match, and runs the scanner from Task 2; it emits verified header findings and supported observations. Keep a fixed per-rule occurrence cap (100), reporting totals separately.

- [x] **Step 1: Write failing tests.** Valid `Opus` reports checked/indexed counts and exact header ranges; corrupted TOC/framing bits have stable rule ID and observed bits; `malformedDops` yields NOT_CHECKED; 101 repeated violations show bounded findings and full occurrence count.
- [x] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.OpusBitstreamAnalyzerTest'`; expect RED.
- [x] **Step 3: Implement** aggregation and config applicability with documented parser limits. Retain exact sample/track evidence in details and ranges.
- [x] **Step 4: Run** focused analyzer tests; expect PASS. Commit `feat: evaluate bounded Opus bitstream rules`.

### Task 4: Scope/coverage and workflow integration

**Files:** Modify `ContentCheckStage.kt`, `ContentCheckWindow.kt`, `ContentCheckSnapshot.kt`; create `OpusContentFindings.kt` and `app/src/test/kotlin/com/multiviewer/ui/OpusContentCheckIntegrationTest.kt`.

**Interfaces:** Codec stage invokes `analyzeOpusBitstream` when audio entry is `Opus` with valid `dOps`. Extend snapshot with `val opusBitstream: OpusBitstreamReport? = null`; append Opus findings to conformance detail. Other tracks, fragments, encryption, mismatched tables, cap truncation, changed files, and cancelled scans expose named NOT_CHECKED coverage and cannot yield overall PASS.

- [x] **Step 1: Write failing tests.** `otherAudioTrackIsNotChecked`, fragment/encrypted/unreadable sample coverage, malformed `dOps`, cap truncation, cancellation after sample 1, file changed before publish, and default `Opus` availability in `MediaType.AUDIO`.
- [x] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.ui.OpusContentCheckIntegrationTest'`; expect RED.
- [x] **Step 3: Implement** capability detection in `ContentCheckStage.kt`, dispatcher/cancellation, progress, and UI presentation in `ContentCheckWindow.kt`.
- [x] **Step 4: Run** focused integration tests, `./gradlew :app:compileKotlin`, then `./gradlew :app:test`; expect PASS.
- [x] **Step 5: Commit** `feat: expose Opus rule coverage in Content Check`.

## Branch finish

- [x] Review exact normative citations and real-byte dumps against source standards; downgrade any unverified rule to structural observation or NOT_CHECKED.
- [x] Run `./gradlew :app:test` and `git diff --check`; fix nonflaky failures, review whole branch, and commit fixes.
- [x] Fast-forward local `main` only after review; do not push without explicit user authorization.
