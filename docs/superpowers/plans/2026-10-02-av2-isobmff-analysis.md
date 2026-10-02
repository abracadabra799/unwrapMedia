# AV2 ISO-BMFF Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let unwrapMedia recognize AV2 carried by ISO-BMFF, inspect `av02`/`av2C` structure, and show safely parsed sequence metadata without attempting pixel decoding.

**Architecture:** Register the AV2 sample entry and configuration box with the existing ISO-BMFF parser. Keep AV2 OBU framing and sequence-header parsing in new AV2-specific types rather than adapting AV1 code. Extract the parsed stream-wide sequence header in the video inspector, retain its byte range for the hex view, and render it in the existing detailed-properties panel. Unsupported OBU semantics and draft-rule violations become structural warnings, preserving the box tree and generic MP4 metadata.

**Tech Stack:** Kotlin, Compose Desktop, existing ISO-BMFF `BoxRegistry`/`BoxNode` parser, Kotlin test, Gradle.

**Spec:** [AV2 ISO-BMFF structure and metadata analysis](../specs/2026-10-02-av2-isobmff-analysis-design.md)

## Global Constraints

- Support only AV2 in an ISO-BMFF video track (`.mp4`, `.mov`, `.m4v`, and `.av2` when it is ISO-BMFF). Do not claim support for raw AV2 elementary streams, IVF, WebM, or Matroska.
- Do not add AVM, ffmpeg, or any other decoder dependency. Playback, thumbnails, frame extraction, and per-frame AV2 parsing remain unavailable in this phase.
- Treat the AV2 ISO-BMFF binding as the 22 September 2026 Working Group Draft. Expose that draft status in the inspector and localize format-specific rules to AV2 files.
- AV2 configuration OBU lengths use unsigned LEB128. Every cursor movement must be bounded by the enclosing `av2C` payload and must make progress; malformed input must produce warnings instead of exceptions or unbounded allocation.
- Keep `Av1*` APIs and behavior unchanged. AV2 OBU headers and sequence-header syntax must not be assumed to be AV1-compatible.
- Preserve exact file offsets for configuration OBUs and the selected sequence-header payload, so clicking the AV2 detail row highlights the actual bytes in the hex view.

## Review Focus

- Verify `av02` is registered as a `VisualSampleEntry` and `av2C` is registered exactly once with `registerAllDecoders()`.
- Verify configuration OBU count, each LEB128 length, OBU header, and payload interval are checked against the parent box before reads.
- Verify invalid/truncated data remains visible in the structure tree with a clear warning, while valid subsequent parser work is not lost.
- Verify the summary says `AV2`, the detail panel labels the draft accurately, and no UI suggests AV2 playback or thumbnail support.
- Verify only AV2 source, registration, summary, UI state, and corresponding tests are changed; do not mix the separate thumbnail/HEIC working-tree edits into AV2 commits.

---

## Task 1: Register AV2 and route selectable ISO-BMFF files as video

**Files:** `Decoders.kt`, `MediaSummaryBuilder.kt`, `AppState.kt`, and their focused tests.

- [ ] Write failing registration, summary, and extension-gate tests for `av02`, `av2C`, `AV2`, and `.av2`.
- [ ] Register `av02` as a visual sample entry and `av2C` as a dedicated decoder; add `"av02" to "AV2"` to the ISO-BMFF codec map; add `av2` to `VIDEO_EXTENSIONS`.
- [ ] Keep category detection based on the existing `vide` handler and run the focused test set.
- [ ] Commit AV2 registration/routing only: `feat: recognize AV2 ISO-BMFF tracks`.

## Task 2: Add bounded AV2 OBU primitives

**Files:** Create `Av2Obu.kt` and `Av2ObuTest.kt`.

- [ ] Write failing tests for AV2 OBU headers, extension temporal/spatial layer IDs, valid/multi-byte LEB128, unterminated/overflow lengths, and payload-bound enforcement.
- [ ] Implement immutable header and length result types with recoverable parse errors and a type-name map that retains unknown numeric values.
- [ ] Run focused tests and commit: `feat: parse bounded AV2 configuration OBUs`.

## Task 3: Decode `av2C` into inspectable structural children

**Files:** Create `Av2CBoxDecoder.kt` and `Av2CBoxDecoderTest.kt`; modify `Decoders.kt`.

- [ ] Write failing tests for the reserved/count fields, multi-OBU records, extension-layer values, exact ranges, and all required malformed input warnings.
- [ ] Parse the fixed prefix and bounded length-delimited records into `BoxNode` children. Preserve unknown valid OBUs; stop safely on unrecoverable framing errors.
- [ ] Run focused tests and commit: `feat: inspect AV2 codec configuration boxes`.

## Task 4: Extract and parse AV2 sequence metadata

**Files:** Create `Av2ParameterSetExtraction.kt`, `Av2SequenceHeader.kt`, and focused tests.

- [ ] Define and test stream metadata: profile, level/tier, dimensions, bit depth, monochrome, chroma, colour fields, film-grain capability, and output-order mode.
- [ ] Extract a sequence-header OBU plus exact payload range from bounded `av2C` records. Test absent and malformed cases.
- [ ] Parse only the approved-draft fields; return a recoverable failure on truncation or unsupported syntax and never search sample payloads.
- [ ] Run focused tests and commit: `feat: parse AV2 sequence metadata`.

## Task 5: Show AV2 metadata and draft status in the inspector

**Files:** `AppState.kt`, `VideoInspectorUI.kt`, `ImageInspectorUI.kt`, and focused UI/state tests.

- [ ] Add test-first `TabState` AV2 stream metadata/range behavior.
- [ ] Read `av2C` on `Dispatchers.IO`, parse it once per root, and retain the payload range for hex selection. Do not add AV2 frame analysis.
- [ ] Render `AV2 Sequence Header` values and the visible text `ISO-BMFF binding: Working Group Draft (22 Sep 2026); AV2 decoding is unavailable.`
- [ ] Run focused tests, full tests, and compilation; commit: `feat: show AV2 analysis metadata`.

## Task 6: Integration coverage and final review

**Files:** Create `Av2IsoBmffIntegrationTest.kt`; change AV2 source only when needed.

- [ ] Use a synthetic `ftyp`/`moov`/`stsd`/`av02`/`av2C` fixture to verify registration, structural ranges, summary, and extracted metadata together.
- [ ] Add malformed-configuration coverage showing generic MP4 analysis and warnings survive.
- [ ] Run `git diff --check`, full tests, compilation, manual desktop verification, and a fresh code review. Keep the original checkout's HEIC/thumbnail work out of AV2 commits.
- [ ] Commit integration corrections: `test: cover AV2 ISO-BMFF analysis path`.
