# Windows AV2 playback handoff

- Worktree: `/private/tmp/multiviewer-av2-index`
- Branch: `feat/windows-av2-stream-index`
- Base commit: `1b75461 docs: add Windows AV2 handoff notes`
- Current changes are uncommitted; no Windows runner has executed them yet.

## Implemented

- ISO-BMFF AV2 sample indexing from `stts`, `stsz`, `stsc`, and `stco/co64`, with bounded OBU parsing and safe `av2C`/sample assembly.
- Windows packaging workflow pins AVM to `cd2ba5446504e24c4ad1c7c06ef382214653a537`, builds and stages `avmdec.exe`, includes that revision's license/patent notices, and checks the packaged app image.
- AVM CLI integration uses `avmdec -o - <input.obu>` for Y4M on stdout, drains stderr, and supports cancellation/backpressure.
- AV2 tracks (`av02` with `av2C`) route to a Compose player for first-frame preview and sequential play/pause. Decoded frames are bounded in memory and large display frames are downsampled.
- Y4M frame reading validates truncation and caps per-frame allocation; odd-sized 4:2:0 planes are accounted for.
- Push/PR packaging builds and caches only the pinned AVM decoder, then checks its help output and packaged resources. The slower AVM encoder plus generated OBU end-to-end smoke test is opt-in via `workflow_dispatch` (`av2_end_to_end: true`) and has a separate cache.

## Validation and limitations

- `./gradlew test` passed on 2026-10-03.
- `git diff --check` passed at handoff preparation.
- Workflow YAML is parsed locally; AVM compilation, Windows DLL/runtime behavior, optional end-to-end decoder smoke test, app-image staging, and installer build still require Windows CI.
- Seeking is intentionally disabled: the current sample index does not classify safe random-access points (RAPs). Do not infer RAPs from timestamps; add bitstream classification/indexing before enabling seeking.
- AV2 playback is Windows-only. macOS/Linux playback remains out of scope.

## Resume

From `/private/tmp/multiviewer-av2-index` on `feat/windows-av2-stream-index`, inspect the uncommitted diff and run the Windows package workflow. Fix any Windows AVM build/runtime or packaging failures before committing.
