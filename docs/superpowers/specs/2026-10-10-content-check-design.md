# Content Check (Phase 1: unified window and menu) Design

## Goal

Replace four separate menus with one **Analysis → 컨텐츠 검사 / Content Check** (⌘⇧C) that runs every applicable check for the open file — image, motion photo, video (and, in later phases, audio) — and shows all results in one window.

Phases (each its own spec → plan → review → merge):
1. **This spec:** unified window + menu cleanup, reusing existing analysis code unchanged.
2. Audio decode verification (all audio streams, in audio files and inside videos; duration/sample-rate/channel cross-check) → adds an "오디오" tab.
3. Audio per-format byte-level structure checks (WAV, AIFF, FLAC, MP3, ADTS AAC, Ogg/Opus, M4A, WMA/ASF) → extends the "구조" tab for audio.

## Menu changes

- Add: Analysis → `컨텐츠 검사` / `Content Check` (no ellipsis), shortcut ⌘⇧C, enabled when a file is loaded (`hasActiveFile`).
- Remove: `구조 정합성 검사` (⌘⇧C), `영상 무결성 검사…` (⌘⇧B), `이미지 무결성 검사…`, and Motion Photo menu → `모션포토 정합성 검사`. ⌘⇧B becomes unassigned.
- Keep unchanged: structure dump, AI diagnosis, A/V sync, frame intervals, **SEF Integrity Check** (kept by user decision).

## Window: "컨텐츠 검사 — <file name>"

- Header: media type, then one verdict per section that applies (구조, 디코딩, 모션포토, 영상 디코딩, 패킷).
- Buttons: 검사 시작 / Start inspection, 취소 / Cancel, 분석 케이스 저장 / Save analysis case, JSON 복사 / Copy JSON (copies the same JSON `unwrapMedia check` prints for the current results; replaces the old structure window's "Copy Report").
- Tabs, built from the media type (pure function, unit-tested):
  - Always: **구조** — images: the image structure report (format checks + parser warnings); all other types: parser warnings rendered as WARN items in the same table (`parser.warning`, offset/length from the node), or a single PASS row when there are none. Clicking a row with an offset highlights it in the Hex view.
  - Image: **디코딩** (FFmpeg/Skia panel).
  - Motion photo detected (`hasMotionPhotoData`): **모션포토** (`MotionPhotoReportContent`).
  - Video: **영상 디코딩** (diagnostics list) and **패킷 매핑** (packet table), moved from the Video Integrity window unchanged in behaviour.
  - Audio / raw: only **구조** in phase 1.
- Execution: structure runs automatically when the window opens (and when `tab.root` becomes available). **검사 시작** runs the heavy steps for the type in order (pure function, unit-tested):
  - Image: image decode → motion-photo analysis (if detected).
  - Video: video integrity (packet scan + decode, with its existing progress messages).
  - Other types: no heavy steps in phase 1 (button disabled with a note).
  Cancel stops the whole run; existing stale-run guards and detached motion analysis are kept.

## Analysis case

One format: the existing `buildAnalysisCaseJson` (schema 1) with `videoIntegrity` and `imageIntegrity` (incl. `motionPhoto`). The window builds the image JSON from its in-memory results and passes it in; `buildImageIntegrityCaseJson` is deleted. Never overwrites an existing file.

## Code structure

- `ui/ContentCheckWindow.kt` — window shell, state, run orchestration.
- `ui/ContentCheckPlan.kt` — pure: `contentTabs(type, motionDetected)`, `heavySteps(type, motionDetected)`, `parserWarningItems(root)` reuse (from `ImageIntegrityChecker`).
- `ui/ImageCheckPanels.kt` — `ImageStructurePanel`, `ImageDecodePanel`, status label/color helpers (moved from `ImageIntegrityWindow.kt`).
- `ui/VideoCheckPanels.kt` — `VideoDecodePanel`, `VideoPacketPanel` (moved from `VideoIntegrityWindow.kt`).
- Deleted: `ImageIntegrityWindow.kt`, `VideoIntegrityWindow.kt`, `MotionPhotoIntegrityWindow.kt`, `StructureCheckWindow` (in `AnalysisWindows.kt`), their state vars and menu items in `Main.kt`, now-unused I18n strings.
- README: replace the separate image/video window descriptions with one "컨텐츠 검사" description; keep CLI docs.

## Testing

- Unit: `contentTabs` / `heavySteps` for IMAGE (with/without motion), VIDEO, AUDIO, UNKNOWN; non-image structure items from parser warnings.
- Full suite, compile, and a launched-app check that the menu exists, the old menus are gone, and the window opens for an image, a motion photo and a video.
