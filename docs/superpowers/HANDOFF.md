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
| 2 | C2PA Content Credentials + motion-photo interaction — split into **2a** structure/display, **2b** hard-binding hash recomputation + motion-photo/XMP/SEF range mapping ("does this edit break the binding?"), **2c** COSE_Sign1 + X.509 chain + timestamp verification using **JDK crypto only** (user's decision, no external crypto library) | **Shipped (2a, 2b, 2c merged into main)**. Spec/plan: `docs/superpowers/{specs,plans}/2026-10-12-c2pa-signature-verification*`. GUI manual pass owed to user. |
| 3 | Motion photo deep analysis: still ↔ video frame match at `MicroVideoPresentationTimestampUs`, Apple Live Photo pair check, cross-platform compatibility matrix | **Next action: ready to brainstorm** |

## Next action: brainstorm Item 3 (Motion photo deep analysis)

- Roadmap Item 2 (C2PA Content Credentials 2a, 2b, 2c) is completely **Shipped and merged into `main`**.
  - 2a: Manifest stores, claim, assertions, ingredients, thumbnails, and JUMBF tree nodes.
  - 2b: Hard-binding hash recomputation (`c2pa.hash.data`, `c2pa.hash.bmff` v2/v3) and structure coverage mapping (motion-photo video append, SEF trailer `/sefd` exclusion impact).
  - 2c: Cryptographic verification of COSE_Sign1 signatures (ES256, PS256, RS256), X.509 certificate chains, and assertion URI hash integrity using **JDK crypto only**.
- Next topic: **Item 3 — Motion photo deep analysis**:
  - Still ↔ video frame match at `MicroVideoPresentationTimestampUs` (or `MotionPhoto_PresentationTimestampUs`).
  - Apple Live Photo pair check (UUID metadata / `quicktime:CreationDate` matching between JPEG/HEIC and MOV).
  - Cross-platform compatibility matrix (Samsung vs Google vs Apple Live Photo vs Xiaomi).

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
