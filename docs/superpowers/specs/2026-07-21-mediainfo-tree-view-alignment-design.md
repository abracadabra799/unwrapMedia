# MediaInfo Tree View Alignment — Design

## Background

The user asked that the Media Summary tab's section/field naming and grouping match the MediaInfo tool's "Tree view" display mode, across all formats the app already supports (JPEG/HEIC/TIFF/PNG/BMP/AVIF as images; ISOBMFF/MP4-family as video, including Motion Photo's embedded video). MediaInfo's Tree view groups a file's metadata into named categories — **General**, then one category per elementary stream (**Video**, **Audio**, and for still images, **Image**) — each showing a small set of well-known field names (Format, Width, Height, Frame rate, Sampling rate, Channel(s), etc.).

The app currently has one flat "Basic Info" section per file (mixing container-level and stream-level fields together) plus, for video, separate "Video Track Detail"/"Audio Track Detail" sections. This design restructures those into MediaInfo's category shape and renames fields to MediaInfo's terms, reusing 100% of the data the app already extracts — no new byte-level parsing is introduced.

Clarified scope (from user Q&A):
- Match both category structure AND major field names/labels to MediaInfo — not just category names.
- Resolution splits into separate Width/Height rows (MediaInfo's own format), replacing the current combined "1920x1080" string.
- App-unique sections that have no MediaInfo equivalent — Camera Info, GPS Location, Samsung Metadata, Track List, and the Motion Photo stacked-box layout — are kept exactly as-is, positioned alongside the newly-renamed MediaInfo-style sections.
- Codec values (currently raw ISOBMFF box-type strings like `avc1`/`hvc1`/`mp4a`) are translated to MediaInfo's human-readable names (AVC/HEVC/AAC) via a small, bounded lookup table — the same pattern already used for `PNG_COLOR_TYPE_NAMES`. Unmapped codecs fall back to their raw box-type string.

## Goal

Opening any supported image file shows **General** (Format, File Size) and **Image** (Width, Height, Color Space, Capture Date) sections instead of one "Basic Info" section. Opening any supported video file (including a Motion Photo's embedded-video box) shows **General** (Format, File Size, Duration, Overall Bit Rate), **Video** (Format, Width, Height, Frame Rate), and **Audio** (Format, Sampling Rate, Channel(s)) instead of "Basic Info" + "Video Track Detail" + "Audio Track Detail". Video codec and audio codec values display as MediaInfo-style names (AVC, HEVC, AV1, AAC) rather than raw box-type strings. All other existing sections (Camera Info, GPS Location, Samsung Metadata, Track List) are unaffected.

## Non-Goals

- No new byte-level parsing — every field shown today continues to be sourced from the exact same existing extraction logic; only section grouping, field labels, and codec display values change.
- No MediaInfo-style container format name translation (e.g. turning `ftyp` major_brand `"isom"` into a human string like "MPEG-4") — the container-level Format field keeps showing whatever raw value it already computes today (major_brand code, or "JPEG"/"TIFF"/"PNG"/"BMP" for those formats). Only the *codec* names (Video/Audio Format field) get the human-name lookup table, since that's a small, well-defined, already-precedented mapping (like `PNG_COLOR_TYPE_NAMES`); container brand naming would need a much larger, open-ended dictionary and isn't part of this request.
- No exhaustive codec dictionary — only the four codecs the app currently recognizes (`avc1`, `hvc1`, `av01`, `mp4a`) get mapped; any other codec box type falls back to its raw string, unchanged from today's behavior.
- No changes to Camera Info, GPS Location, Samsung Metadata, or Track List — kept exactly as they are today, per explicit user decision.
- No changes to `MediaSummaryView.kt`'s rendering logic (the Motion Photo stacked-box "📷 이미지"/"🎬 동영상 (모션포토)" titles, `SummaryBox`/`SectionContent` composables) — this is purely a `MediaSummaryBuilder.kt` data restructuring; the existing generic section/field renderer needs no changes since it already iterates whatever `SummarySection`/`SummaryField` list it's given.

## Design

All changes are contained in `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`. No other file changes.

### 1. Image summary: `buildImageBasicInfo` → `buildImageGeneral` + `buildImageDetail`

The existing `buildImageBasicInfo` function (builds one `SummarySection("Basic Info", ...)` containing Resolution/File Size/Format/Color Space/Capture Date) is replaced by two functions:

- `buildImageGeneral(root, file): SummarySection` — returns `SummarySection("General", fields)` with exactly two fields: `Format` (unchanged value/logic — JPEG/TIFF/PNG/BMP literal, or `ftyp` major_brand fallback) and `File Size` (unchanged).
- `buildImageDetail(root): SummarySection` — returns `SummarySection("Image", fields)` with: `Width` and `Height` (split from the existing combined Resolution computation — same source data, e.g. `sofOrIspe`/TIFF `IFD0`/PNG `IHDR`/BMP `BITMAPINFOHEADER`, just emitted as two `SummaryField`s instead of one formatted string), `Color Space` (unchanged logic, moved into this section), and `Capture Date` (unchanged logic, moved into this section).

`buildImageSummary` (the caller) changes from `sections.add(buildImageBasicInfo(root, file))` to `sections.add(buildImageGeneral(root, file)); sections.add(buildImageDetail(root))`. Everything after that in `buildImageSummary` (Camera Info, GPS Location, Samsung Metadata construction) is untouched.

### 2. Video summary: split Resolution out of `buildVideoBasicInfo`, rename `Video/Audio Track Detail`

- `buildVideoBasicInfo` (renamed `buildVideoGeneral`, still returns `SummarySection("General", ...)`) drops its `Resolution` field entirely (that data was drawn from `videoTrak`'s `tkhd` width/height) — it keeps `Duration`, `File Size`, and renames its two remaining fields: `Container Brand` → `Format`, `Average Bitrate` → `Overall Bit Rate`. No other logic changes.
- `buildVideoTrackDetail` (renamed `buildVideoDetail`, returns `SummarySection("Video", ...)` instead of `"Video Track Detail"`) gains the `Width`/`Height` fields moved from the old Basic Info (same `tkhd` width/height source, read as `Double` and displayed via `.toInt()`, matching the existing Basic Info formatting exactly), and renames its existing `Codec` field to `Format` — whose *value* now passes through the new codec-name lookup (`CODEC_DISPLAY_NAMES[codecType] ?: codecType`) instead of the raw `stsd` child type string. `Frame Rate` is unchanged.
- `buildAudioTrackDetail` (renamed `buildAudioDetail`, returns `SummarySection("Audio", ...)`) renames `Codec` → `Format` (same codec-name lookup applied), `Sample Rate` → `Sampling Rate` (value/logic unchanged), `Channels` → `Channel(s)` (value/logic unchanged).
- `buildVideoSummary` (the caller) updates its calls to the renamed functions; section order is unchanged (`General`, `Track List`, `Video`, `Audio`), since `Track List`'s own construction (`buildTrackList`) is untouched.

### 3. Codec display name mapping

A new top-level map, alongside the existing `PNG_COLOR_TYPE_NAMES`-style pattern:

```kotlin
private val CODEC_DISPLAY_NAMES = mapOf(
    "avc1" to "AVC",
    "hvc1" to "HEVC",
    "av01" to "AV1",
    "mp4a" to "AAC",
)
```

Used at both call sites (`buildVideoDetail`'s video codec, `buildAudioDetail`'s audio codec) as `CODEC_DISPLAY_NAMES[rawType] ?: rawType` — any codec box type not in the map (e.g. `s263`, `samr`, or any future/unrecognized entry) falls back to displaying its raw box-type string exactly as today, so no format that currently shows a codec loses that information.

### 4. Motion Photo embedded-video summary

`buildMotionPhotoVideoSummary` already calls the shared `buildVideoSummary` function on the embedded video's independently-reparsed box tree — since `buildVideoSummary` itself is what's being restructured (not a separate code path), the Motion Photo video box's sections automatically become `General`/`Track List`/`Video`/`Audio` with zero additional code. This is a direct consequence of the existing architecture (`buildMotionPhotoVideoSummary` reuses `buildVideoSummary` rather than reimplementing it) and needs no separate design.

## Testing

- `MediaSummaryBuilderTest` updates: every existing assertion that reads a section by title `"Basic Info"` for an image tree updates to read `"General"` and/or `"Image"` per the field's new home; every assertion reading `"Video Track Detail"`/`"Audio Track Detail"` updates to `"Video"`/`"Audio"`; every assertion reading a `"Resolution"` field updates to separate `"Width"`/`"Height"` assertions; every assertion reading `"Codec"` updates to `"Format"` with the mapped display value (e.g. `"AVC"` instead of `"avc1"`); every assertion reading `"Sample Rate"`/`"Channels"`/`"Container Brand"`/`"Average Bitrate"` updates to `"Sampling Rate"`/`"Channel(s)"`/`"Format"`/`"Overall Bit Rate"`.
- New test: an unrecognized codec box type (e.g. a `stsd` child type not in `CODEC_DISPLAY_NAMES`) still displays its raw string under the `Format` field, proving the fallback path.
- New test: the existing Motion Photo video-summary test (which currently asserts on the embedded video's `"Basic Info"` section) updates to assert on `"General"`/`"Video"` as appropriate, proving the restructuring flows through automatically.
- Manual verification: open a JPEG/HEIC/PNG/BMP/TIFF/AVIF file and confirm General + Image sections render with Width/Height as separate rows; open an MP4/MOV video and confirm General + Track List + Video + Audio render with Format showing the mapped codec name (e.g. "AVC"/"AAC"); open a Motion Photo JPEG and confirm its embedded-video box also shows General/Video/Audio.
