# Windows AV2 playback handoff

- Worktree: `/private/tmp/multiviewer-av2-index`
- Branch: `feat/windows-av2-stream-index`
- Latest commit: `7e2c8bd feat: convert AV2 frames to Compose bitmaps`
- Validation: `./gradlew test` passed; `git diff --check` passed; worktree was clean.

## Implemented

- AV2 ISO-BMFF sample indexing from `stts`, `stsz`, `stsc`, `stco/co64`.
- Bounded OBU parsing and warnings for malformed samples.
- Safe `av2C` configuration + sample bitstream assembly.
- Windows-only packaged `avmdec.exe` locator.
- AVM process lifecycle runner with stderr draining, cancellation, and stdout callback.
- Y4M header parsing, frame reading, YUV420 streaming, and 8-bit YUV420 → RGBA conversion.
- Compose `ImageBitmap` conversion for 8-bit AV2 frames.

## Next work

1. Build/package a pinned Windows AVM decoder (`avmdec.exe`) in the Windows workflow.
2. Confirm the actual AVM CLI arguments and connect them to `runAvmProcess`.
3. Connect `Av2DecodedFrame.toImageBitmap()` to an AV2 Compose player state.
4. Add Windows fixture smoke testing and installer verification.

The user wants Windows first; macOS/Linux are deferred.
