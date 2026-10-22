# Motion photo builder: Google-spec and Samsung-compatible modes — design

Date: 2026-10-22. Status: approved (user chose "두 가지 모드 제공"). Follows
`2026-10-22-google-motion-photo-spec-compliance-design.md` on the same branch.

## Problem

"모션포토 생성" (`MotionPhotoBuilder.createMotionPhoto`) writes Samsung-style files: JPEG
`[JPEG][SEF blocks][MotionPhoto_Data header][MP4][SEFH][SEFT]`, HEIC `[ftyp][meta][mdat][mpvd][sefd]`, and its XMP puts
`Item:Padding="0"` on the MotionPhoto item. The new Google Motion Photo 1.0 checker therefore FAILs the app's own
output. Google §3.4 forbids any byte after the video, while Samsung Gallery locates SEF through the `SEFT` tail at
the end of the file, so one layout cannot satisfy both.

## Decision

Two creation modes, chosen in the Tools menu:

| Mode | Layout | XMP | Default save name |
|---|---|---|---|
| **Google spec (default)** | JPEG: `[primary JPEG (+ any gain-map JPEG kept from the source)][MP4]`. HEIC/AVIF: `[base boxes][mpvd(MP4)]` with `mpvd` last. No SEF trailer / `sefd`. | Camera:MotionPhoto=1, MotionPhotoVersion=1, PresentationTimestampUs; Directory Primary (+ GainMap when the source is Ultra HDR) + MotionPhoto(Length = MP4 size); Padding only on the first item and only when non-zero (JPEG) / =8 (HEIC/AVIF); no MicroVideo fields. | `<name>_MP.jpg` / `<name>_MP.heic` |
| **Samsung compatible** | Current layout (SEF trailer / `sefd` after the video). | Same XMP fixes: no Padding on non-first items; JPEG primary Padding omitted when 0; HEIC primary Padding=8. | `<name>_motion.<ext>` (unchanged) |

MicroVideo v1 (legacy) creation stays as-is; it FAILs the 1.0 checker by design (deleted format).

Source SEF data cannot be preserved in Google-spec mode (it would have to follow the video); the success status
message says so in both languages.

## Acceptance (round-trip tests)

Each created file is analyzed with `MotionPhotoIntegrityAnalyzer.analyze` and the `googleSpec` rows are asserted:
- Google-spec mode (JPEG from a plain ffmpeg JPEG; JPEG from `samsung-style.jpg` fixture with SEF; HEIC from a
  `sips`-made HEIC): **no CRITICAL and no WARNING rows** (the default `_MP` name passes `gmp.filename`), decode PASS.
- Samsung-compatible mode (same inputs): the only CRITICAL rows are `gmp.layout.video_last` and, for HEIC,
  `gmp.mpvd.last` / `gmp.mpvd.length`; WARNING only `gmp.filename`; SEF trailer still present and the existing SEF
  integrity checks pass.
- Existing `MotionPhotoBuilderTest` behavior (XMP preservation/merge) keeps passing, with assertions about
  `Item:Padding` on the video item updated to the new rule.

## UI

Tools menu: the single "모션포토 생성" (v2) item becomes two items, "모션포토 생성 (구글 스펙) / Create Motion Photo
(Google spec)" and "모션포토 생성 (삼성 호환) / Create Motion Photo (Samsung compatible)"; the MicroVideo item is
unchanged. Strings via `I18n`. No other UI changes.
