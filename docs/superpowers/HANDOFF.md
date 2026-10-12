# Handoff — 2026-10-12 (Claude Code → Codex)

Read `AGENTS.md` (repo root) for build/test commands, workflow and hard rules. This file is the current state.

## Where things stand

- `main` = v1.20.0 (`0d2c6f1` + docs commits after it), pushed to `origin`. All tests pass (1484) apart from the
  known flaky `ThumbnailDiskCacheTest`. No feature branch or worktree is open.
- The user asked to execute a benchmarking roadmap **in order, one item at a time**, each through
  brainstorm → spec → plan → implementation → review → merge.

## Roadmap (from a 2026-10-12 benchmark against StreamEye, MediaInfo, ExifTool, c2patool, YUView, Baton…)

| # | Item | Status |
|---|---|---|
| 1 | SEI / HDR metadata analysis (H.264/HEVC SEI, AV1 metadata OBU, HDR10+, mdcv/clli, per-frame Detail section, Analyze window with trend graph, 4 anomaly rules, CSV) | **Shipped** in 1.20.0. Spec/plan: `docs/superpowers/{specs,plans}/2026-10-12-sei-hdr-metadata-analysis*`. GUI manual pass still owed to the user. |
| 2 | C2PA Content Credentials + motion-photo interaction — split into **2a** structure/display, **2b** hard-binding hash recomputation + motion-photo/XMP/SEF range mapping ("does this edit break the binding?"), **2c** COSE_Sign1 + X.509 chain + timestamp verification using **JDK crypto only** (user's decision, no external crypto library) | **2a: spec + plan done, ready to implement** (see below). 2b/2c: not started. |
| 3 | Motion photo deep analysis: still ↔ video frame match at `MicroVideoPresentationTimestampUs`, Apple Live Photo pair check, cross-platform compatibility matrix | not started |
| 4 | Firmware build-vs-build regression batch diff (folder A vs folder B field diff) | not started |
| 5 | MV/QP overlay for HEVC and AV1 (currently H.264 only) | not started |
| 6 | HRD/VBV buffer simulation | not started |
| 7 | MV-HEVC spatial video | not started |
| 8 | Timed metadata tracks (camm / gyro / GPS) on a timeline | not started |
| 9 | HDR scopes (nits false colour, waveform, vectorscope, gamut, gain-map headroom slider) | not started |
| 10 | ADB integration (device browse/pull, `media_codecs.xml` / `dumpsys media.codec`) | not started |
| 11 | MPEG-TS, VVC, loudness (LUFS) | not started |

## Next action: implement C2PA phase 2a

- Spec: `docs/superpowers/specs/2026-10-12-c2pa-manifest-parsing-design.md`
- Plan: `docs/superpowers/plans/2026-10-12-c2pa-manifest-parsing.md` — 8 tasks with complete code and tests.
  **Task 1 (fixtures) is already done** (fixtures committed in `app/src/test/resources/c2pa/`, provenance and
  Python dump tools in `docs/validation/c2pa-fixtures/`). Start at Task 2.
- Suggested execution: create a worktree `git worktree add .worktrees/c2pa-2a -b feature/c2pa-manifest-parsing`,
  run the baseline `./gradlew :app:test`, then do Tasks 2–8 in order. Each task is TDD: write the test, see it
  fail, implement, see it pass, run the full suite once, commit. Review each task's diff against its plan task
  before moving on; at the end review the whole branch (cross-task integration, Windows risks, false-PASS risks).
- The plan's code was written without compiling. If something does not compile, fix it minimally and record the
  deviation; **never change a test's expected value** to make it pass — the values were read from the real
  files. If a value differs, re-check with `python3 -I docs/validation/c2pa-fixtures/tools/c2pa_dump.py <file>`.
- Task 7's GUI and Task 8's manual checks need the user (no GUI automation here).
- After 2a merges: brainstorm 2b with the user (hash.data exclusion ranges are absolute file offsets, e.g.
  C.jpg `{start: 20, length: 45884}` = the APP11 segment; hash.bmff uses xpath exclusions such as `/uuid`,
  `/ftyp`, `/meta/iloc`), then 2c.

## Facts worth knowing for C2PA (verified on real files)

- No real Galaxy C2PA sample yet. The user's unedited capture (`~/Downloads/20261012_223627.jpg`) has none —
  Samsung adds C2PA only to Galaxy-AI-edited images (generative edit etc.). Ask the user for an AI-edited JPEG
  and an AI-edited motion photo before 2b; 2b's motion-photo question needs real Samsung files.
- That capture is an Ultra HDR JPEG: ISO 21496-1 APP2 + MPF + gain-map JPEG at 1,693,523, and a 217-byte SEF
  trailer (`Image_UTC_Data`, `MCC_Data`, `Color_Display_P3`, `Photo_HDR_Info`, `Camera_Capture_Mode_Info`).
- c2patool 0.27.22 is installed via Homebrew on the dev Mac; it signs with a built-in test certificate.

## Open loose ends

- GUI manual checks owed by the user: HDR/SEI window + Detail section (item 1); several earlier features listed
  in the user's notes (Content Check, Image Integrity, SEF tables).
- `internal` remote push of 1.20.0 not confirmed (SSH timed out from this machine).
- Minor findings recorded during item 1 reviews but intentionally left: frames CSV exports only HDR10+ window 0;
  `detectVideoCodec`/length-size mismatch possible only for multi-video-track files; cancel during sample-table
  build isn't honoured.
