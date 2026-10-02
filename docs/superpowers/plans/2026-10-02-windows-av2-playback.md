# Windows AV2 Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Package an internal Windows AVM helper and use it to index, decode, preview, and sequentially play AV2 ISO-BMFF video.

**Architecture:** A pinned AVM decoder executable is staged beside bundled ffmpeg tools. AV2 sample indexing and bitstream assembly stay in Kotlin; a cancellable helper process emits Y4M frames that a dedicated Compose player renders. Existing ffmpeg playback remains unchanged.

**Tech Stack:** Kotlin/Compose Desktop, AVM CMake/Visual Studio build, GitHub Actions Windows runner, ISO-BMFF parser, Y4M.

**Spec:** [Windows AV2 playback design](../specs/2026-10-02-windows-av2-playback-design.md)

## Global Constraints

- Windows x64 only; do not change macOS or Linux packaging.
- Package `avmdec.exe` as an internal resource; users launch only `unwrapMedia.exe`.
- Support AV2 ISO-BMFF `av02`/`av2C` tracks only. Never route malformed sample data to AVM.
- Keep temporary bitstreams bounded and delete them after cancellation, failure, or completion.
- Display AV2 sequence data as a prefix until a complete sequence-header parser exists.

## Review Focus

- Missing helper reports a clear error and never silently falls back to an unrelated PATH binary.
- A declared sample/OBU range beyond EOF causes a warning, not an AVM process launch.
- Helper stderr is continuously drained and cancellation terminates the child process.
- Unsupported Y4M chroma/bit depth is rejected with a usable diagnostic.
- Installer staging includes `avmdec.exe` and its runtime dependencies.

---

### Task 1: Windows AVM helper packaging and resolution

**Files:** `.github/workflows/package.yml`, `app/src/main/kotlin/com/multiviewer/ui/AvmLocator.kt`, focused locator tests.

- [ ] Write failing tests for packaged Windows helper resolution, development fallback behavior, and missing-helper diagnostics.
- [ ] Add `AvmLocator.decoderPath(): AvmDecoderLocation` and `AvmLocator.configureEnvironment(ProcessBuilder)` following `FfmpegLocator` conventions.
- [ ] Add a pinned AVM source revision, CMake Visual Studio build, resource staging, and smoke test to the Windows-only workflow; test the staged executable with `--help` or version output.
- [ ] Run focused tests and commit `feat: bundle Windows AV2 decoder`.

### Task 2: AV2 sample index and safe bitstream assembly

**Files:** Create `parser/Av2SampleIndexer.kt`, `parser/Av2BitstreamAssembler.kt`, and tests; modify `AppState.kt` only for index state.

- [ ] Write failing fixtures for `buildAv2SampleIndex(file, root): Av2SampleIndex?`, including PTS/duration, sample offsets, valid OBU lists, truncated LEB128, and OBU-overrun rejection.
- [ ] Implement sample-table traversal using existing `stts`, `stsc`, `stco`/`co64`, and `stsz` nodes; return warnings with no unbounded reads.
- [ ] Write failing assembler tests proving `assembleAv2Bitstream(file, index, range, destination)` emits configuration OBUs before ordered sample OBUs and deletes incomplete output on failure.
- [ ] Implement bounded streaming assembly and commit `feat: index AV2 samples for decoding`.

### Task 3: AVM Y4M process and frame decoder

**Files:** Create `ui/AvmDecoderProcess.kt`, `ui/Y4mFrameReader.kt`, and tests.

- [ ] Write failing tests for AVM command construction, Y4M header parsing, supported 8/10-bit 4:2:0 conversion selection, unsupported format errors, and cancellation.
- [ ] Implement `decode(input: File, onFrame: (Av2DecodedFrame) -> Boolean): AvmDecodeResult`, with stderr draining, output-size cap, process cleanup, and non-zero exit diagnostics.
- [ ] Run focused tests and commit `feat: decode AV2 frames through AVM`.

### Task 4: AV2 preview, sequential player, and stream panel

**Files:** Create `ui/Av2VideoPlayer.kt`; modify `VideoInspectorUI.kt`, `AppState.kt`, `GopAnalysisView.kt`; add UI/state tests.

- [ ] Write failing state tests for an AV2 index populating sample rows, first-frame preview, process errors, and seek disabled without a random-access sample.
- [ ] Connect the indexer and decoder on `Dispatchers.IO`; use the AV2 player only when the active track has `av02`.
- [ ] Render sample PTS, size, OBU types, layer IDs, and random-access classification. Implement play/pause and first-frame thumbnails; restart decode from an indexed random-access sample for seek.
- [ ] Run focused tests, `./gradlew test`, and `./gradlew compileKotlin`; commit `feat: play and inspect AV2 streams on Windows`.

### Task 5: Windows package integration verification

**Files:** Windows workflow and AV2 fixtures/tests from Tasks 1–4.

- [ ] Add a Windows CI smoke test that opens a small AV2 ISO-BMFF fixture through the staged helper, receives at least one Y4M frame, and verifies helper cleanup.
- [ ] Verify the installer resource tree contains `avmdec.exe`; test missing helper and malformed sample behavior without invoking the decoder.
- [ ] Run `git diff --check`, `./gradlew test`, and `./gradlew compileKotlin`; manually verify the Windows installer launches AV2 preview/playback and retains normal H.264 playback.
- [ ] Commit `test: verify Windows AV2 playback packaging`.
