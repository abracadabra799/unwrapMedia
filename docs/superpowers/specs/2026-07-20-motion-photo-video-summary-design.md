# Motion Photo Video Summary in Media Summary — Design

## Background

The Media Summary tab shows curated metadata for the currently open file — image sections (Basic Info/Camera Info/GPS/Samsung Metadata) for photos, or video sections (Basic Info/Track List/Video Track Detail/Audio Track Detail) for standalone video files. For a Motion Photo (an image file with an embedded video, already detected and extractable via `MotionPhotoExtractor.findEmbeddedVideo`), Media Summary currently only shows the image's own metadata — nothing about the embedded video itself (its resolution, duration, codec, etc.).

## Goal

When the open file is a Motion Photo, Media Summary shows two visually distinct boxed regions: one for the image, one for the embedded video, each with its own curated summary sections. Non-motion-photo files are unaffected — no boxes, no layout change.

## Non-Goals

- No change to Structure Analyser — this is Media Summary only.
- No video preview/playback in the summary.
- No change to the existing `MotionPhoto > 동영상 추출` menu action.

## Design

### 1. Layout (`MediaSummaryView.kt`)

Approved via mockup: **stacked**, top-to-bottom. When `MediaSummary.motionPhotoVideoSections` is non-null, render two bordered boxes instead of the current flat section list:
- **"📷 이미지"** — wraps `summary.sections` (the existing image sections), rendered exactly as today inside the box.
- **"🎬 동영상 (모션포토)"** — wraps `motionPhotoVideoSections`, rendered the same way.

When `motionPhotoVideoSections` is `null` (the common case — not a motion photo), rendering is byte-for-byte unchanged from today: a flat `LazyColumn` of sections, no boxes, no group headers.

### 2. Video summary content — reusing `buildVideoSummary`

The video box's sections are built by the *same* `buildVideoSummary` function already used for standalone video files (Basic Info, Track List, Video Track Detail, Audio Track Detail) — so a Motion Photo's video box looks identical in structure to what you'd see opening that video as its own file.

`buildVideoSummary(root: BoxNode, file: File)` currently calls `file.length()` for the "File Size" field and average-bitrate math. For an embedded video, the *containing photo's* file size would be wrong — the video box needs the video's own byte length. `buildVideoSummary` and its private helper `buildVideoBasicInfo` are refactored to take `fileSizeBytes: Long` instead of `file: File`, with the one existing call site (`buildMediaSummary`) passing `file.length()` as before — a signature-only change, no behavior change for standalone video files.

### 3. Locating and parsing the embedded video (`MediaSummaryBuilder.kt`)

When building an image's summary, call `findEmbeddedVideo(root)` (existing, from `MotionPhotoExtractor.kt`). If it returns a range, open a fresh `ByteReader` on the same `file` and call `parseBoxes(reader, video.start, video.end)` — independently re-parsing just that byte range as its own self-contained box tree (its own `ftyp`/`moov`/`mdat`), wrapped in a synthetic root `BoxNode`. This is then passed to `buildVideoSummary` with `fileSizeBytes = video.end - video.start`.

Re-parsing independently (rather than reusing whatever tree shape each of the three detection paths happens to already have) keeps this uniform across HEIC `mpvd`, Samsung SEFD, and Google XMP alike — the video box works the same way regardless of which detection path found it. Any failure (malformed video data, parse error) is caught and results in `motionPhotoVideoSections = null` — the image summary is never affected by a broken video box.

### 4. Data model (`MediaSummary.kt`)

```kotlin
data class MediaSummary(
    val category: MediaCategory,
    val sections: List<SummarySection>,
    val motionPhotoVideoSections: List<SummarySection>? = null,
)
```

Backward compatible — every existing call site and test that doesn't care about Motion Photos is unaffected by the new defaulted field.

## Testing

- `MediaSummaryBuilderTest` additions: a synthetic Motion Photo fixture (image box tree + `mpvd` with a real `ftyp`/`moov`/`mdat` video tree) asserts `motionPhotoVideoSections` contains the expected video sections (Duration/Resolution/Codec/etc.), sourced from the embedded video's own bytes, not the containing file's. A non-motion-photo fixture asserts `motionPhotoVideoSections` is `null` (regression guard — existing behavior for ordinary photos is untouched).
- `MediaSummaryViewTest`-equivalent verification is manual (this app's established pattern for UI-level rendering) — this app doesn't unit-test Compose UI layout, per its existing convention (`MediaSummaryView.kt` has no dedicated test file today).
- Manual verification: open a real Motion Photo sample (e.g. the Samsung JPEG/HEIC files already used to verify prior Motion Photo work, or the Google XMP samples from `MVIMG_20180211_141455.jpg`/`PXL_20230526_102639313.MP.jpg`), confirm both boxes render with correct, plausible values (video duration matches what QuickTime reports), and confirm an ordinary non-motion-photo file still renders as a flat list with no boxes.
