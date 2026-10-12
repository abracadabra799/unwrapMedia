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
| 2 | C2PA Content Credentials + motion-photo interaction — split into **2a** structure/display, **2b** hard-binding hash recomputation + motion-photo/XMP/SEF range mapping ("does this edit break the binding?"), **2c** COSE_Sign1 + X.509 chain + timestamp verification using **JDK crypto only** (user's decision, no external crypto library) | **2a: Shipped (merged into main)**. Spec/plan: `docs/superpowers/{specs,plans}/2026-10-12-c2pa-manifest-parsing*`. GUI manual pass still owed to user. **2b: ready to brainstorm**. 2c: not started. |

## Next action: brainstorm C2PA phase 2b

- Phase 2a (C2PA structure parsing & credentials window) is complete and fast-forward merged into `main`.
  All automated tests pass. Real Galaxy S26 JPEG (`s26-camera.jpg`) and HEIC motion photo (`s26-motion-photo.heic`) fixtures are integrated.
- Phase 2b topic: Hard-binding hash recomputation + motion-photo/XMP/SEF range mapping ("does this edit break the binding?").
  - `hash.data` exclusion ranges are absolute file offsets (e.g. C.jpg `{start: 20, length: 45884}` = APP11 segment).
  - `hash.bmff` uses xpath exclusions such as `/uuid`, `/ftyp`, `/meta/iloc`.
  - Motion photo interaction: appending a video track or SEF trailer vs C2PA hash integrity.
- Next step: Brainstorm phase 2b with the user to align on requirements and architecture, followed by design spec and implementation plan.

## Facts worth knowing for C2PA (verified on real files)

- Update from user-supplied S26 samples (2026-10-12): `s26-motion-photo.heic` and `s26-camera.jpg` both contain C2PA. See `docs/validation/c2pa-fixtures/README.md` for raw offsets and provenance. The prior AI-edit-only assumption below is superseded.
- Earlier observation: The user's unedited capture (`~/Downloads/20261012_223627.jpg`) has none —
  That absence does not establish an AI-edit-only rule. Real Samsung camera samples are now available for 2b; AI-edited variants remain useful additional coverage.
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
