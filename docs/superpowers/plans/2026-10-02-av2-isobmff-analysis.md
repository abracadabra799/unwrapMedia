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

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt`

- [ ] Write failing tests that assert `registerAllDecoders()` resolves `av02` through `VisualSampleEntryDecoder` and recognizes `av2C`, that an `stsd` child of type `av02` produces video-summary format `AV2`, and that `.av2` passes the supported-video extension gate.
- [ ] Add `av02` as a visual sample entry and `av2C` as a dedicated decoder registration (the decoder implementation arrives in Task 3).
- [ ] Add `"av02" to "AV2"` to the ISO-BMFF codec display-name map; leave category detection based on the existing `vide` handler, rather than incorrectly classifying a bare `ftyp` brand as a playable track.
- [ ] Add `av2` to `VIDEO_EXTENSIONS`, so extension selection reaches the existing ISO-BMFF parser. Keep the actual supported-container constraint in user-facing behavior and test that non-ISO-BMFF inputs fail through ordinary parsing rather than a decoder path.
- [ ] Run `./gradlew test --tests com.multiviewer.parser.DecodersRegistrationTest --tests com.multiviewer.parser.MediaSummaryBuilderTest --tests com.multiviewer.ui.AppStateTest` and confirm the new assertions pass.
- [ ] Commit only the Task 1 AV2 registration/routing files with message `feat: recognize AV2 ISO-BMFF tracks`.

## Task 2: Add bounded AV2 OBU primitives

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/Av2Obu.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/Av2ObuTest.kt`

- [ ] Write failing tests for AV2 OBU header decoding, including the forbidden/reserved bits, OBU type, extension flag, and temporal/spatial layer identifiers when an extension byte is present.
- [ ] Write failing tests for unsigned LEB128 framing: one-byte and multi-byte lengths, an unterminated length, an overflow/non-canonical length if prohibited by the binding, and an end-bound that stops a read at the `av2C` payload boundary.
- [ ] Implement immutable AV2 OBU header and length result types that carry parsed values plus the next absolute offset. Return a recoverable parse error/result instead of throwing for truncated or invalid inputs.
- [ ] Add a small AV2 OBU type-name mapping used by the structural tree; unknown numeric types must still be represented as `unknown(<n>)`.
- [ ] Run `./gradlew test --tests com.multiviewer.parser.Av2ObuTest`.
- [ ] Commit only the Task 2 AV2 OBU primitive files with message `feat: parse bounded AV2 configuration OBUs`.

## Task 3: Decode `av2C` into inspectable structural children

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/Av2CBoxDecoder.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/Av2CBoxDecoderTest.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`

- [ ] Write failing decoder tests using synthetic `av2C` payloads for: reserved byte and `config_obus_count_minus1` fields; multiple valid length-delimited configuration OBUs; extension-layer fields; and exact child offsets/sizes.
- [ ] Write failing malformed-fixture tests for a short fixed prefix, truncated LEB128, declared OBU payload extending beyond `av2C`, invalid OBU header, and a configuration count whose entries are absent. Assert warnings are attached to `av2C` or the affected child and no exception escapes.
- [ ] Implement `Av2CBoxDecoder` with the binding's initial reserved byte and count field. Emit `BoxField`s for their values and a child `BoxNode` for each declared OBU, including type, declared length, header flags, and layer identifiers when present.
- [ ] Stop safely on unrecoverable framing errors. For a valid unknown OBU, consume its bounded payload and continue to the next declared entry.
- [ ] Register the decoder in `registerAllDecoders()` and ensure duplicate registration remains idempotent through the existing registration guard.
- [ ] Run `./gradlew test --tests com.multiviewer.parser.Av2CBoxDecoderTest --tests com.multiviewer.parser.DecodersRegistrationTest`.
- [ ] Commit only the Task 3 AV2 box-decoder files with message `feat: inspect AV2 codec configuration boxes`.

## Task 4: Extract and parse AV2 sequence metadata

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/Av2ParameterSetExtraction.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/Av2SequenceHeader.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/Av2ParameterSetExtractionTest.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/Av2SequenceHeaderTest.kt`

- [ ] Define the metadata model before parsing: profile, level, tier when signalled, coded width/height, bit depth, monochrome, chroma subsampling, colour primaries/transfer/matrix, film-grain capability, and output-order mode. Make absent/signalled values explicit rather than inventing defaults.
- [ ] Write failing extraction tests for an `av2C` node that finds the required Sequence Header configuration OBU and returns its payload bytes and exact payload offset; add tests for no sequence header and malformed preceding OBU framing.
- [ ] Write a fixture-based failing parser test from a valid AV2 Sequence Header OBU payload. Assert every exposed metadata field. Add guarded failure tests for empty/truncated payloads and unsupported syntax branches, asserting `null`/recoverable result rather than a thrown exception.
- [ ] Implement extraction by walking the `av2C` configuration records with the same bounded AV2 OBU primitive used in Task 3. Do not search sample payloads or use ffmpeg.
- [ ] Implement the AV2 sequence-header bit parser against the approved AV2 draft, reading only the fields in the data model. Keep reserved bits and unimplemented branches explicit in result warnings or a null parse result so a future draft revision has one correction point.
- [ ] Run `./gradlew test --tests com.multiviewer.parser.Av2ParameterSetExtractionTest --tests com.multiviewer.parser.Av2SequenceHeaderTest`.
- [ ] Commit only the Task 4 AV2 metadata files with message `feat: parse AV2 sequence metadata`.

## Task 5: Show AV2 metadata and draft status in the inspector

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`
- Create or modify: focused UI/state tests in `app/src/test/kotlin/com/multiviewer/ui/`

- [ ] Write failing state/helper tests covering an AV2 sequence header and its byte range being stored for a video tab, then cleared or replaced when the tab/root changes.
- [ ] Add nullable AV2 sequence-header state and exact payload range to `TabState`, following the existing AV1 stream-wide-state convention but without any AV2 frame-header pass.
- [ ] Add a `LaunchedEffect(tab.root)` to find `av2C`, extract and parse its sequence header on `Dispatchers.IO`, and update the AV2 tab state only when parsing succeeds. Leave player/thumbnail/frame-analysis behavior untouched.
- [ ] Render an `AV2 Sequence Header` section in detailed properties with every parsed field. Make its profile/level row select the stored byte range for the hex view.
- [ ] Render a concise visible text warning near the AV2 section: `ISO-BMFF binding: Working Group Draft (22 Sep 2026); AV2 decoding is unavailable.` Use the existing warning/detail visual language and do not add a misleading action button.
- [ ] Run focused UI/state tests, then `./gradlew test` and `./gradlew compileKotlin`.
- [ ] Commit only the Task 5 AV2 inspector files with message `feat: show AV2 analysis metadata`.

## Task 6: End-to-end synthetic ISO-BMFF regression coverage and final review

**Files:**
- Create or modify: `app/src/test/kotlin/com/multiviewer/parser/Av2IsoBmffIntegrationTest.kt`
- Modify only if needed: AV2 files from Tasks 1–5

- [ ] Build a minimal synthetic ISO-BMFF fixture containing `ftyp` with `av02`, a `moov` video track, `stsd`/`av02`, and nested `av2C` with a valid sequence-header configuration OBU. Assert parser registration, tree fields/OBU child ranges, summary format `AV2`, and extracted metadata all agree.
- [ ] Add malformed `av2C` integration fixture coverage. Assert generic MP4 tree and summary remain available and the issue appears in collected warnings.
- [ ] Run `git diff --check`, `./gradlew test`, and `./gradlew compileKotlin` from a clean task state. Manually open the valid synthetic fixture in the desktop app, confirm the Structure tree, AV2 summary, hex highlighting, draft warning, and absence of false playback claims.
- [ ] Review the final AV2-only diff against the Global Constraints. Keep the already-uncommitted HEIC/thumbnail changes out of AV2 commits.
- [ ] Commit the integration test and any necessary AV2 corrections with message `test: cover AV2 ISO-BMFF analysis path`.
