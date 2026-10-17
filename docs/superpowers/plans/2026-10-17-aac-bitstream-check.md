# AAC Bitstream Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded AAC (`mp4a`) rule runner for non-fragmented MP4/MOV/M4A audio, with exact byte evidence and explicit untested scope.

**Architecture:** A parser-side scanner reads AAC `esds` / `AudioSpecificConfig` and sample prefixes from the audio sample index using bounded reads (max 512 bytes per audio sample). A narrow rule catalog converts verified observations into findings; unsupported syntax and coverage gaps remain NOT_CHECKED. The Content Check codec stage invokes this runner for `MediaType.AUDIO` (`mp4a`) and supported audio tracks through the workflow stage interface.

**Tech Stack:** Kotlin, existing ISO-BMFF readers (`IsoSampleTable.kt`, `AudioSampleEntryDecoder.kt`), JDK file I/O, coroutines, JUnit, Python fixture generator.

**Spec:** `docs/superpowers/specs/2026-10-17-aac-bitstream-check-design.md` (depends on `2026-10-17-content-check-workflow-design.md`)

## Global Constraints

- Scope is MP4/MOV/M4A audio track with `mp4a`, valid `esds` box, non-fragmented `moov` sample tables, at most `MAX_AUDIO_INDEXED_SAMPLES = 1_000_000`.
- Active in `CheckStage.CODEC` when `MediaType.AUDIO` with `mp4a` is present.
- Rule ID/source/edition/clause must be independently checked before labeling a result normative; otherwise label structural observation.
- Unsupported syntax is NOT_CHECKED unless a verified rule resolves it; never guess FAIL or PASS.
- Bounded reads (at most 512 bytes per sample), checked arithmetic, cancellation between samples, `Dispatchers.IO`, file identity before publishing.
- Bilingual KO/EN evidence, exact byte ranges, bounded displayed/exported occurrences plus total count; no heavy dependency.
- Preserve existing Content Check JSON keys and no-overwrite save behavior.

## Review Focus

- `esds` box structure: version 0, `ES_Descriptor` (0x03), `DecoderConfigDescriptor` (0x04) with `streamType == 5` (AudioStream) (Task 1).
- `AudioSpecificConfig`: AOT 1..4/5/29, sampling frequency index 0..12 or 15 (indices 13, 14 forbidden), channel configuration 1..7 (Task 2).
- ADTS guard: sample bytes in MP4 `mp4a` must not start with 12-bit `0xFFF` syncword (Task 2).
- ICS reserved bit: in `ics_info`, `ics_reserved_bit` must be 0 (Task 2).
- Multiple audio tracks: identify checked track and list others as unscanned (Task 4).

---

### Task 1: Real-byte fixtures, AacRuleCatalog, and AudioSampleTable

**Files:** Create `docs/validation/aac-conformance-fixtures/README.md`, `generate.py`, and fixtures under `app/src/test/resources/aac-conformance/`; create `app/src/main/kotlin/com/multiviewer/parser/conformance/AacRuleCatalog.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/AacRuleCatalogTest.kt`; add `buildAudioSampleTable` in `IsoSampleTable.kt`.

**Interfaces:** Produce `object AacRuleCatalog` with `fun rule(id: String): ConformanceRule?` and stable IDs: `aac.config.esds`, `aac.config.asc`, `aac.config.match`, `aac.frame.adts_guard`, `aac.frame.element`, `aac.frame.ics_reserved`, `aac.sample.framing`. `data class AudioSample(val index: Int, val offset: Long, val size: Long, val decodeTime: Long)`; `data class AudioSampleTable(val format: String, val timescale: Long, val samples: List<AudioSample>, val warnings: List<String>)`; `fun buildAudioSampleTable(reader: ByteReader, root: BoxNode, maxSamples: Int = 1_000_000): AudioSampleTable?`.

- [ ] **Step 1: Generate fixtures.** Create Python generator creating valid `default-mp4a.m4a` with verified AAC LC raw access unit and valid `esds` box, record byte offsets in README. Create mutations for `bad-adts-leaked.m4a`, `bad-asc-sampling.m4a`, `bad-ics-reserved.m4a`, `malformed-esds.m4a`, `truncated-sample.m4a`, and `many-adts-failures.m4a`.
- [ ] **Step 2: Write failing catalog and table tests.** Assert stable IDs, MPEG-4 Audio source/edition, scope, structural observation labeling, and `buildAudioSampleTable` resolution.
- [ ] **Step 3: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.AacRuleCatalogTest'`; expect RED.
- [ ] **Step 4: Implement** `AacRuleCatalog` and `buildAudioSampleTable` in `IsoSampleTable.kt`.
- [ ] **Step 5: Run** focused tests; expect PASS. Commit `test: establish AAC conformance byte fixtures and rule catalog`.

### Task 2: Bounded AAC sample and header scanner

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/AacSampleScanner.kt` and `app/src/test/kotlin/com/multiviewer/parser/conformance/AacSampleScannerTest.kt`.

**Interfaces:** `data class AacConfigObservation(val esdsOffset: Long, val aot: Int, val samplingFrequencyIndex: Int, val sampleRate: Int, val channelConfig: Int, val issue: String?)`; `fun parseAacConfig(reader: ByteReader, esds: BoxNode): AacConfigObservation`; `data class AacSampleObservation(val sampleIndex: Int, val offset: Long, val size: Long, val hasAdtsSync: Boolean, val elementId: Int?, val elementInstanceTag: Int?, val icsReservedBit: Int?, val issue: String?)`; `fun scanAacSample(reader: ByteReader, sample: AudioSample, channelConfig: Int): AacSampleObservation`. Scanner reads only up to 512 bytes prefix per sample, checks bounds, checks ADTS sync guard, verifies raw data block element header and ics_reserved_bit.

- [ ] **Step 1: Write failing scanner tests.** Assert raw offsets and bit fields on real `mp4a` fixtures; `badAdtsLeaked`, `badAscSampling`, `badIcsReserved`, `truncatedSample`, and `sampleExtentOverflow` have exact offsets/status and never PASS for unreadable data.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.AacSampleScannerTest'`; expect RED.
- [ ] **Step 3: Implement** bounded scanner with checked addition and bounded buffer prefix; callers check coroutine cancellation between samples.
- [ ] **Step 4: Run** scanner tests; expect PASS. Commit `feat: scan AAC esds config and sample headers with bounded reads`.

### Task 3: Parameter-set evidence and rule aggregation

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/AacBitstreamReport.kt`, `AacBitstreamAnalyzer.kt`, and `app/src/test/kotlin/com/multiviewer/parser/conformance/AacBitstreamAnalyzerTest.kt`.

**Interfaces:** `data class AacBitstreamReport(val findings: List<ConformanceFinding>, val checkedSamples: Int, val indexedSamples: Int, val totalOccurrences: Map<String, Long>, val limitations: List<String>, val checkedTrack: String, val complete: Boolean)`; `suspend fun analyzeAacBitstream(file: File, root: BoxNode, onProgress: (Int, Int?) -> Unit): AacBitstreamReport`. The analyzer calls `buildAudioSampleTable`, checks `esds` / `AudioSpecificConfig`, and scanner from Task 2; it emits verified header findings and supported observations. Keep a fixed per-rule occurrence cap (100), reporting totals separately.

- [ ] **Step 1: Write failing tests.** Valid `mp4a` reports checked/indexed counts and exact header ranges; corrupted ADTS syncword/reserved bits have stable rule ID and observed bits; `malformedEsds` yields NOT_CHECKED; 101 repeated violations show bounded findings and full occurrence count.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.AacBitstreamAnalyzerTest'`; expect RED.
- [ ] **Step 3: Implement** aggregation and config applicability with documented parser limits. Retain exact sample/track evidence in details and ranges.
- [ ] **Step 4: Run** focused analyzer tests; expect PASS. Commit `feat: evaluate bounded AAC bitstream rules`.

### Task 4: Scope/coverage and workflow integration

**Files:** Modify `ContentCheckStage.kt`, `ContentCheckWindow.kt`, `ContentCheckSnapshot.kt`; create `AacContentFindings.kt` and `app/src/test/kotlin/com/multiviewer/ui/AacContentCheckIntegrationTest.kt`.

**Interfaces:** Codec stage invokes `analyzeAacBitstream` when audio entry is `mp4a` with valid `esds`. Extend snapshot with `val aacBitstream: AacBitstreamReport? = null`; append AAC findings to conformance detail. Other tracks, fragments, encryption, mismatched tables, cap truncation, changed files, and cancelled scans expose named NOT_CHECKED coverage and cannot yield overall PASS.

- [ ] **Step 1: Write failing tests.** `otherAudioTrackIsNotChecked`, fragment/encrypted/unreadable sample coverage, malformed `esds`, cap truncation, cancellation after sample 1, file changed before publish, and default `mp4a` availability in `MediaType.AUDIO`.
- [ ] **Step 2: Run** `./gradlew :app:test --tests 'com.multiviewer.ui.AacContentCheckIntegrationTest'`; expect RED.
- [ ] **Step 3: Implement** capability detection in `ContentCheckStage.kt`, dispatcher/cancellation, progress, and UI presentation in `ContentCheckWindow.kt`.
- [ ] **Step 4: Run** focused integration tests, `./gradlew :app:compileKotlin`, then `./gradlew :app:test`; expect PASS.
- [ ] **Step 5: Commit** `feat: expose AAC rule coverage in Content Check`.

## Branch finish

- [ ] Review exact normative citations and real-byte dumps against source standards; downgrade any unverified rule to structural observation or NOT_CHECKED.
- [ ] Run `./gradlew :app:test` and `git diff --check`; fix nonflaky failures, review whole branch, and commit fixes.
- [ ] Fast-forward local `main` only after review; do not push without explicit user authorization.
