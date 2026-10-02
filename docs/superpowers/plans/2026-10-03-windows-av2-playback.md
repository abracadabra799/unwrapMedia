# Windows AV2 Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bundle a pinned AVM decoder in Windows distributions and connect ISO-BMFF AV2 decoding to the existing Compose video player.

**Architecture:** Keep sample indexing and bitstream assembly in the parser layer, run the packaged decoder through a testable command builder/process runner, and expose decoded Y4M frames through an AV2 player that follows existing video playback state and controls. Stage the helper in normal Windows packaging; keep the encoder-backed end-to-end smoke test opt-in to avoid making every package build compile the encoder.

**Tech Stack:** Kotlin, Compose Desktop, Gradle, GitHub Actions, AVM decoder, Y4M.

**Spec:** `docs/superpowers/specs/2026-10-02-windows-av2-playback-design.md`

## Global Constraints

- Windows x64 only; AV2 in ISO-BMFF `av02`/`av2C` tracks.
- No PATH fallback for the packaged Windows decoder.
- Stream the source and decoded output; do not load an entire video into memory.
- Preserve structure inspection when decoding is unavailable.
- Keep macOS and Linux playback out of scope.

## Review Focus

- AVM CLI or pinned revision changes: build the exact command from the pinned source and test the argument vector.
- Corrupt or truncated sample data: refuse assembly/decoding and preserve parser warnings.
- Cancellation while either process pipe is blocked: terminate the process and release temporary files.
- Unsupported Y4M chroma or bit depth: show a useful playback error without emitting malformed frames.
- Decoder missing in packaged resources: surface the checked paths in an actionable error.

---

### Task 1: Pin and package the Windows AVM decoder

**Files:**
- Modify: `.github/workflows/package.yml`
- Create: `app/resources/windows/AVM-LICENSE.txt` (or the applicable upstream notice file)
- Test: Windows workflow staging and decoder smoke-test steps

**Interfaces:**
- Produces `app/resources/windows/bin/avmdec.exe` for the existing Compose resource staging convention.
- Pins an immutable AVM source revision and records it with the shipped license notice.

- [x] Identify the AVM build target, prerequisites, license notice, and decoder CLI from the pinned upstream source; record the immutable revision in the workflow.
- [x] Add a Windows-only build step that compiles `avmdec.exe` and stages it under `app/resources/windows/bin/`.
- [x] Add workflow checks that the executable exists, reports usable help/version output, and remains present in the packaged app image.
- [x] Validate the workflow YAML and the local Gradle test suite; Windows runtime checks execute in Windows CI.

### Task 2: Add AVM command construction and decoder integration

**Files:**
- Create or modify: `app/src/main/kotlin/com/multiviewer/ui/AvmDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AvmProcessRunner.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/AvmDecoderTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/AvmProcessRunnerTest.kt`

**Interfaces:**
- Consumes: `AvmLocator.decoderPathOrThrow()`, AV2 assembled input, and `streamAv2Frames`.
- Produces a testable command builder using the verified AVM CLI and a cancellable decode operation returning process diagnostics and decoded frames.

- [x] Add command-builder tests for input/output arguments using the verified upstream CLI contract.
- [x] Implement the minimal command builder and connect stdout to Y4M parsing while stderr is drained.
- [x] Add tests for nonzero exit, cancellation, malformed Y4M, and missing helper diagnostics.
- [x] Run the focused AVM and Y4M tests.

### Task 3: Connect AV2 to the Compose video player

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/Av2VideoPlayer.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` only if shared playback state requires it
- Test: focused player state/dispatch tests

**Interfaces:**
- Consumes: AV2 track/sample index, `Av2DecodedFrame.toImageBitmap()`, decoder integration, and existing player callbacks.
- Produces: AV2 first-frame preview, sequential playback, play/pause, thumbnails, and supported random-access seeking with a clear disabled-seek state where no access point exists.

- [x] Add dispatch tests proving AV2 tracks select the AV2 player and other codecs retain the existing player.
- [x] Implement frame production and Compose presentation using the existing video controls and timing conventions.
- [x] Add cancellation/disposal handling when the tab or player is replaced.
- [x] Run player and AV2 unit tests.

**Task 3 ruling:** Seeking remains disabled with an explicit “no RAP index” explanation because the current sample index does not establish safe random-access points. First-frame preview is provided; no standalone thumbnail strip is claimed.

### Task 4: End-to-end packaging verification and handoff

**Files:**
- Modify: `.github/workflows/package.yml`
- Modify: `docs/superpowers/AV2-WINDOWS-HANDOFF.md`

**Interfaces:**
- Normal Windows CI builds the staged decoder and verifies the installed resource layout. A manual workflow dispatch can additionally build the encoder and decode a generated AV2 stream.

- [x] Avoid an external fixture: generate a tiny OBU stream from synthetic Y4M with the pinned AVM encoder on opt-in manual runs.
- [x] Add an opt-in Windows decode smoke test that checks successful exit and at least one complete frame.
- [x] Run `./gradlew test` and `git diff --check`; record Windows-only validation that cannot run locally.
- [x] Update the handoff with the pinned revision, tested CLI, workflow results, and remaining limitations.

**Task 4 ruling:** The manual end-to-end workflow generates a tiny OBU stream from synthetic Y4M using the encoder built from the same pinned AVM source, avoiding external fixture provenance/licensing drift. Regular push/PR packaging omits encoder compilation and retains decoder help and package-presence checks.
