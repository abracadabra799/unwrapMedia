# Audio Structure Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add byte-level structural integrity checks for standalone audio files and display actionable findings in Content Check.

**Architecture:** Add a parser-integrity checker that detects supported audio formats and emits a typed `AudioStructureReport` composed of shared `IntegrityCheckItem`s. Keep format logic in focused helpers and present format findings separately from existing parser warnings; retain image and other media behavior.

**Tech Stack:** Kotlin, existing `ByteReader`, `BoxNode`, parser-integrity model, JUnit/Kotlin test, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-11-content-check-audio-structure-design.md`

## Global Constraints

- Apply checks only to standalone files classified as `MediaType.AUDIO`; embedded audio in video remains out of scope.
- Use `PASS`, `WARN`, `FAIL`, and `SKIP`; do not present unsupported or unverified data as clean.
- Check all offset/length arithmetic for overflow and file bounds before reads.
- Bound count-derived allocation and report when limits prevent full verification.
- Stream CRC/frame scans in bounded buffers; do not load full audio payloads into memory.
- Do not invoke an external decoder from the structural checker.
- Do not change analysis-case schema or add CLI behavior.

## Review Focus

- Malicious declared lengths/counts and integer overflow: tests assert bounded failure without huge allocation or out-of-range reads (Tasks 1, 7).
- Valid but uncommon codec variants (WAVE extensible, AIFC compressed, MP3 free-format): tests assert `WARN`/`SKIP`, not false corruption (Tasks 2, 3, 5).
- Recoverable junk/resynchronization gaps in frame streams: tests assert the gap is reported, not silently treated as a clean continuous stream (Tasks 4, 5, 6).
- Multiplexed Ogg serials and legal sequence wrap: tests assert per-serial handling and no false discontinuity (Task 6).
- M4A fragmented/unsupported sample tables and non-audio tracks: tests assert no false clean mapping result and no embedded-video scope expansion (Task 7).

---

### Task 1: Shared audio structure report and dispatch

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/AudioStructureChecker.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ContentCheckPlan.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/integrity/AudioStructureCheckerTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ContentCheckPlanTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, `BoxNode`, `IntegrityCheckItem`, `CheckStatus`.
- Produces: `AudioStructureReport(format: String, items: List<IntegrityCheckItem>, parserWarnings: List<IntegrityCheckItem>)` with computed `overall: CheckStatus`; `AudioStructureChecker.check(file: File, root: BoxNode): AudioStructureReport`; `contentAudioStructureReport(file: File, root: BoxNode): AudioStructureReport`; `detectAudioFormat(reader: ByteReader): String`.

- [ ] **Step 1: Write failing checker/dispatch tests**

Test unknown audio bytes return a `SKIP` format item, and WAV dispatch returns format `WAVE` with an explicit `SKIP` item until its validator is added in Task 2. Assert parser warnings remain in a separate report field. Keep `contentStructureReport`'s existing behavior for compatibility; test the new `contentAudioStructureReport` API directly. Task 8 wires the new API to the Structure tab.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.integrity.AudioStructureCheckerTest' --tests 'com.multiviewer.ui.ContentCheckPlanTest'`
Expected: FAIL because `AudioStructureChecker` and audio dispatch are not implemented.

- [ ] **Step 3: Implement shared report and format dispatch**

Implement `AudioStructureChecker.check(file, root)` using one `ByteReader.open(file)` lifetime; dispatch by magic signature in precedence order WAV, AIFF, FLAC, MP3, ADTS, Ogg, ISO BMFF, ASF. Return an explicit `SKIP` item for unrecognized audio bytes and store `ImageIntegrityChecker.parserWarningItems(root)` separately as `parserWarnings`. Keep `contentStructureReport` behavior unchanged; add a separate `contentAudioStructureReport(file, root)` entry point for AUDIO. Until Tasks 2–7 add each validator, recognized formats return an explicit per-format `SKIP`, never `PASS`.

- [ ] **Step 4: Run focused tests**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.integrity.AudioStructureCheckerTest' --tests 'com.multiviewer.ui.ContentCheckPlanTest'`
Expected: PASS.

### Task 2: RIFF/WAVE and AIFF/AIFF-C validators

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/WaveStructure.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/AiffStructure.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/integrity/AudioStructureChecker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/integrity/WaveStructureTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/integrity/AiffStructureTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, `IntegrityCheckItem`, `CheckStatus`.
- Produces: `checkWaveStructure(reader: ByteReader): List<IntegrityCheckItem>` and `checkAiffStructure(reader: ByteReader): List<IntegrityCheckItem>`.

- [ ] **Step 1: Write failing valid/truncation/malformed-length tests**

Cover valid minimal RIFF/WAVE and AIFF, truncated required chunks, out-of-file declared extents, missing/duplicate required chunks, odd-byte padding, WAVE alignment/byte-rate consistency, and uncompressed AIFF COMM/SSND sample-frame consistency. Assert offsets on structural failures and `SKIP`/`WARN` for unsupported compressed sample accounting.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests '*WaveStructureTest' --tests '*AiffStructureTest'`
Expected: FAIL because the validators do not exist.

- [ ] **Step 3: Implement bounded RIFF/WAVE and AIFF checks**

Use checked `Long` arithmetic, validate declared parent extents and chunk padding before reads, and only calculate sample alignment for codecs whose format fields define it unambiguously.

- [ ] **Step 4: Run focused tests**

Run: `./gradlew :app:test --tests '*WaveStructureTest' --tests '*AiffStructureTest'`
Expected: PASS.

### Task 3: FLAC metadata and frame CRC validation

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/FlacStructure.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/integrity/AudioStructureChecker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/integrity/FlacStructureTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, `IntegrityCheckItem`, `CheckStatus`.
- Produces: `checkFlacStructure(reader: ByteReader): List<IntegrityCheckItem>`.

- [ ] **Step 1: Write failing metadata/frame tests**

Test valid STREAMINFO ordering/size and frame CRCs; mutate metadata size, duplicate/misplaced STREAMINFO, reserved block type, frame header CRC, frame CRC, and final frame truncation. Include a sparse/large input case to verify bounded reads.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests '*FlacStructureTest'`
Expected: FAIL because the validator does not exist.

- [ ] **Step 3: Implement streaming FLAC checks**

Walk metadata blocks by checked extents, then parse frame sync/header and frame boundaries; calculate FLAC CRC-8/CRC-16 incrementally in bounded blocks. Return `WARN` when frame boundaries cannot be unambiguously recovered.

- [ ] **Step 4: Run focused tests**

Run: `./gradlew :app:test --tests '*FlacStructureTest'`
Expected: PASS.

### Task 4: MP3 frame continuity and VBR headers

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/Mp3Structure.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/integrity/AudioStructureChecker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/integrity/Mp3StructureTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, shared integrity item model.
- Produces: `checkMp3Structure(reader: ByteReader): List<IntegrityCheckItem>`.

- [ ] **Step 1: Write failing MP3 tests**

Cover consecutive MPEG frame sizes, invalid/inter-frame junk, truncated last frame, ID3v2/ID3v1/APE boundaries, version/layer/rate changes, Xing/Info/VBRI counts and lengths, and free-format `SKIP`/`WARN` behavior.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests '*Mp3StructureTest'`
Expected: FAIL because the validator does not exist.

- [ ] **Step 3: Implement frame-walk validation**

Parse MPEG headers and compute frame lengths from version/layer/bitrate/sample rate/padding. Stop at recognized trailing tags; report gaps and header-incompatible continuity. Validate Xing/VBRI only at their version/channel-defined offsets and treat count differences as `WARN` when padding/trailing metadata prevents exact comparison.

- [ ] **Step 4: Run focused tests**

Run: `./gradlew :app:test --tests '*Mp3StructureTest'`
Expected: PASS.

### Task 5: ADTS AAC frame validation

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/AdtsStructure.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/integrity/AudioStructureChecker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/integrity/AdtsStructureTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, shared integrity item model.
- Produces: `checkAdtsStructure(reader: ByteReader): List<IntegrityCheckItem>`.

- [ ] **Step 1: Write failing ADTS tests**

Cover valid consecutive frames, invalid sync/header, reserved sample-rate index, frame length shorter than header, frame beyond EOF, junk gap, and optional CRC presence. Assert payload CRC is not claimed as checked.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests '*AdtsStructureTest'`
Expected: FAIL because the validator does not exist.

- [ ] **Step 3: Implement ADTS frame walk**

Walk every frame with checked length arithmetic; mark gaps and truncated tails as failures, and make the CRC limitation explicit in report detail.

- [ ] **Step 4: Run focused tests**

Run: `./gradlew :app:test --tests '*AdtsStructureTest'`
Expected: PASS.

### Task 6: Ogg page CRC and stream continuity

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/OggStructure.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/integrity/AudioStructureChecker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/integrity/OggStructureTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, shared integrity item model.
- Produces: `checkOggStructure(reader: ByteReader): List<IntegrityCheckItem>`.

- [ ] **Step 1: Write failing Ogg tests**

Cover page bounds/segment tables, valid and mismatched CRC, per-serial sequence continuity, BOS/EOS flags, multiplexed streams, sequence wraparound, truncated page, and unknown codec `SKIP`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests '*OggStructureTest'`
Expected: FAIL because the validator does not exist.

- [ ] **Step 3: Implement Ogg page walker**

Read one page at a time, calculate the Ogg CRC with the checksum field zeroed, and track sequence/BOS/EOS independently for every serial number. Bound per-stream tracking to data observed in the file.

- [ ] **Step 4: Run focused tests**

Run: `./gradlew :app:test --tests '*OggStructureTest'`
Expected: PASS.

### Task 7: M4A sample tables and ASF/WMA object bounds

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/M4aStructure.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/integrity/AsfStructure.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/integrity/AudioStructureChecker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/integrity/M4aStructureTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/integrity/AsfStructureTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, `BoxNode`, `IntegrityCheckItem`, `CheckStatus`.
- Produces: `checkM4aStructure(reader: ByteReader, root: BoxNode): List<IntegrityCheckItem>` and `checkAsfStructure(reader: ByteReader): List<IntegrityCheckItem>`.

- [ ] **Step 1: Write failing M4A/ASF tests**

Cover M4A `stsz` entry-size extent/count, `stco` and `co64` offsets in and out of file, unsupported/missing sample mapping, non-audio tracks, ASF top-level/child object bounds, header child count, required object presence, and declared file-size mismatch. Include overflowing/large declared table counts and assert capped behavior.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests '*M4aStructureTest' --tests '*AsfStructureTest'`
Expected: FAIL because the validators do not exist.

- [ ] **Step 3: Implement M4A and ASF checks**

Validate M4A audio-track sample tables against file bounds without allocating from untrusted counts; verify mapping only when supported tables fully describe it. Walk ASF objects by checked 64-bit extents and compare declared counts/sizes only where the format gives a reliable invariant.

- [ ] **Step 4: Run focused tests**

Run: `./gradlew :app:test --tests '*M4aStructureTest' --tests '*AsfStructureTest'`
Expected: PASS.

### Task 8: UI, AI facts, and regression verification

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ContentCheckPlan.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ContentCheckWindow.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ContentCheckSnapshot.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageCheckPanels.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ContentCheckPlanTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ContentCheckSnapshotTest.kt`

**Interfaces:**
- Consumes: Task 1 `contentAudioStructureReport(file, root): AudioStructureReport` and existing `ImageStructureReport` display path.
- Produces: Structure tab and AI diagnosis output that visibly distinguish audio structure findings, parser warnings, and audio decode results.

- [ ] **Step 1: Write failing UI/snapshot regression tests**

Assert audio structure report for AUDIO keeps checker findings and parser warnings in separate sections, VIDEO structure remains parser-warning-only, IMAGE output is unchanged, AI prompt surfaces audio structure findings and skips without claiming clean validation, and existing decode facts are unchanged.

- [ ] **Step 2: Run focused tests to verify failure**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ContentCheckPlanTest' --tests 'com.multiviewer.ui.ContentCheckSnapshotTest'`
Expected: FAIL only for the missing audio-structure behavior.

- [ ] **Step 3: Wire presentation and snapshot**

Add `audioStructure: AudioStructureReport?` to `ContentCheckSnapshot`; populate it on window open; render separate “Audio structure” and “Parser warnings” sections in the Structure tab; and include audio structure facts in the AI prompt without conflating them with phase-2 `audio` decode results.

- [ ] **Step 4: Run focused and full tests**

Run focused: `./gradlew :app:test --tests 'com.multiviewer.ui.ContentCheckPlanTest' --tests 'com.multiviewer.ui.ContentCheckSnapshotTest'`
Expected: PASS.

Run full suite: `./gradlew test`
Expected: all tests pass; investigate any failure rather than treating intermittent unrelated failures as validation.

- [ ] **Step 5: Review branch diff**

Run: `git diff --check && git status --short && git diff --stat`
Expected: only phase-3 audio structure code/tests/docs are changed; no phase-1/phase-2 regressions or unrelated modifications.
