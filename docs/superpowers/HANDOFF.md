# Handoff — 2026-10-13 (Claude Code → Codex)

## 2026-10-17 update — Content Check Workflow & HEVC Bitstream Check merged

- `feat/content-check-conformance` merged into `main`:
  1. Content Check workflow redesign: sequential isolated stage execution (Structure → Codec → Visual Decode → Audio Decode → Motion Photo / Gain Map).
  2. Bounded HEVC bitstream analyzer for non-fragmented `hvc1` and `hev1` MP4/MOV: NAL framing, `forbidden_zero_bit`, `nuh_temporal_id_plus1`, and parameter references.
  3. Structural observations vs. normative rule separation (no false PASS, strict NOT_CHECKED isolation).
  4. Specs/plans: `docs/superpowers/{specs,plans}/2026-10-17-content-check-workflow*`, `2026-10-17-hevc-bitstream-check*`.

## 2026-10-15 update — contextual media analysis merged (v1.23.0)

- `feat/contextual-analysis` merged into `main`:
  1. Spec conformance coverage analysis (avoids false PASS, tracks NOT_CHECKED coverage).
  2. File structure diagram (interactive visual container/box/payload layout with SVG/PNG/Markdown export).
  3. Contextual AI panel (Ask AI sidebar with local CLI adapters for Claude, Codex, Gemini, masked redaction, and bounded tools).
- Hand-off details and plan history: `docs/superpowers/HANDOFF-contextual-media-analysis.md`.
- **Deferred at the user's request:** Samsung internal Motion Photo, SEF, and Gallery rules (waiting for user-provided documentation/fixtures).

Read `AGENTS.md` (repo root) for build/test commands, workflow and hard rules. This file is the current state.

## Where things stand

- `main` = v1.20.0 + SEI/HDR (Item 1) + C2PA 2a/2b/2c (Item 2) + Motion photo deep analysis (Item 3) merged.
- All tests pass (1485+) apart from the known flaky `ThumbnailDiskCacheTest`. No feature branch or worktree is open.
- The user asked to execute a benchmarking roadmap **in order, one item at a time**, each through
  brainstorm → spec → plan → implementation → review → merge.

## Roadmap (from a 2026-10-12 benchmark against StreamEye, MediaInfo, ExifTool, c2patool, YUView, Baton…)

| # | Item | Status |
|---|---|---|
| 1 | SEI / HDR metadata analysis (H.264/HEVC SEI, AV1 metadata OBU, HDR10+, mdcv/clli, per-frame Detail section, Analyze window with trend graph, 4 anomaly rules, CSV) | **Shipped** in 1.20.0. Spec/plan: `docs/superpowers/{specs,plans}/2026-10-12-sei-hdr-metadata-analysis*`. GUI manual pass still owed to the user. |
| 2 | C2PA Content Credentials + motion-photo interaction — split into **2a** structure/display, **2b** hard-binding hash recomputation + motion-photo/XMP/SEF range mapping ("does this edit break the binding?"), **2c** COSE_Sign1 + X.509 chain + timestamp verification using **JDK crypto only** (user's decision, no external crypto library) | **Shipped (2a, 2b, 2c merged into main)**. Spec/plan: `docs/superpowers/{specs,plans}/2026-10-12-c2pa-signature-verification*`. GUI manual pass owed to user. |
| 3 | Motion photo deep analysis: still ↔ video frame match at `MicroVideoPresentationTimestampUs`, Apple Live Photo pair check, cross-platform compatibility matrix | **Shipped (merged into main)**. Spec/plan: `docs/superpowers/{specs,plans}/2026-10-13-motion-photo-deep-analysis*`. |
| 4 | Firmware build-vs-build regression batch diff (folder A vs folder B field diff) | deferred by user request |
| 5 | MV/QP overlay for HEVC and AV1 (currently H.264 only) | **Shipped (merged into main)**. Spec/plan: `docs/superpowers/{specs,plans}/2026-10-13-hevc-av1-mv-qp-overlay*`. |
| 6 | HRD/VBV buffer simulation | **Shipped (merged into main)**. Spec/plan: `docs/superpowers/{specs,plans}/2026-10-13-hrd-vbv-buffer-simulation*`. |
| 7 | MV-HEVC spatial video | **Shipped (merged into main)**. Spec/plan: `docs/superpowers/{specs,plans}/2026-10-13-mv-hevc-spatial-video*`. |
| 8 | Timed metadata tracks (camm / gyro / GPS) on a timeline | **Shipped (merged into main)**. Spec/plan: `docs/superpowers/{specs,plans}/2026-10-13-timed-metadata-tracks-timeline*`. |
| 9 | HDR scopes (nits false colour, waveform, vectorscope, gamut, gain-map headroom slider) | **Next action: ready to brainstorm / implement** |
| 10 | ADB integration (device browse/pull, `media_codecs.xml` / `dumpsys media.codec`) | not started |
| 11 | MPEG-TS, VVC, loudness (LUFS) | not started |

## What was completed in Item 3 (Motion photo deep analysis)

- **Task 1: Still ↔ Video Frame Matcher** (`MotionPhotoFrameMatcher.kt`):
  - Presentation timestamp extraction from Google/Samsung XMP (`MotionPhotoPresentationTimestampUs`, `MicroVideoPresentationTimestampUs`) and Apple QuickTime metadata (`still-image-time`).
  - High-precision PTS calculation over ISO-BMFF `VideoSampleTable` (`stts` decodeTime + `ctts` compositionOffset).
  - Matches closest video sample index, computes delta μs, assesses keyframe (I-Frame / Non-keyframe) alignment, and assigns `FrameAlignmentVerdict` (`EXACT_KEYFRAME`, `ALIGNED_NON_KEYFRAME`, `MISALIGNED`, `OUT_OF_BOUNDS`).
  - Relative position percent calculation and timeline gauge representation.
- **Task 2: Apple Live Photo Pair Checker** (`AppleLivePhotoPairChecker.kt`):
  - Extracts Content Identifier UUID and `DateTimeOriginal` from still images (HEIC meta keys, EXIF MakerNote tag 0x0011, XMP `apple-fi:ContentIdentifier`).
  - Extracts Content Identifier UUID, `com.apple.quicktime.creationdate`, `com.apple.quicktime.live-photo.still-image-time`, and vitality score from MOV files.
  - Case-insensitive UUID matching and verification report with `LivePhotoPairStatus` (`MATCHED`, `MISMATCHED_UUID`, etc.).
  - Automatic candidate pair file discovery within the same directory (`findLivePhotoPairCandidate`).
  - Date/time synchronization delta calculation between EXIF and QuickTime.
- **Task 3: Cross-Platform Compatibility Matrix** (`MotionPhotoCompatibilityMatrix.kt`):
  - Evaluates media support levels (`FULL`, `PARTIAL`, `STILL_ONLY`, `UNSUPPORTED`) across 6 key environments:
    1. Samsung Gallery (Samsung OneUI)
    2. Google Photos
    3. Apple Photos (iOS/macOS)
    4. Xiaomi Gallery (HyperOS)
    5. Standard Web Browsers (Chrome, Safari, Edge)
    6. Social Media Platforms (Instagram, X, Facebook)
  - Identifies composite profiles (e.g., Samsung SEF + Google XMP v2 hybrid, legacy SEF, Apple Live Photo pair, HEIC mpvd).
- **Task 4: Content Check Window Integration** (`MotionPhotoReportContent.kt` & `MotionPhotoIntegrityAnalyzer.kt`):
  - Embedded in the Content Check window's Motion Photo tab.
  - Material3 Compose UI cards and tables for Frame Match, Live Photo Pair Inspection, and Compatibility Matrix.

## Facts worth knowing for C2PA & Motion Photo

- Update from user-supplied S26 samples (2026-10-12): `s26-motion-photo.heic` and `s26-camera.jpg` both contain C2PA.
- Motion photo formats handled: Samsung SEF trailer, Google XMP Container:Directory v2, Google legacy MicroVideo v1, Apple Live Photo Pair (HEIC/JPG + MOV), Apple/Samsung HEIC `mpvd`.
- All frame matching is verified at microsecond precision directly against the container's sample tables.

## Open loose ends

- GUI manual checks owed by the user: HDR/SEI window + Detail section (item 1); C2PA verification window (item 2); Motion photo deep analysis in Content Check (item 3).
- `internal` remote push of 1.20.0 not confirmed (SSH timed out from this machine).
