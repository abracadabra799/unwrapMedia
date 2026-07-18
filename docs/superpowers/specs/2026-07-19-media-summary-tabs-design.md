# Media Summary / Structure Analyser 2-Tab UI — Design

## Background

The app currently shows exactly one view per opened file: a tree (`BoxTreeView`) + field panel (`FieldPanel`/`TableView`) + hex view (`HexView`), laid out with draggable dividers (`Main.kt`). This is a "structure explorer" view — accurate and complete, but not a quick at-a-glance summary of what the file actually *is* (resolution, duration, camera model, etc.), which requires manually clicking through the tree to assemble.

The user wants every opened file to present exactly two tabs: a curated **Media Summary** (human-readable highlights) and a **Structure Analyser** (the existing tree/field/hex explorer, relabeled). The engine decides which summary layout to use — image or video — by inspecting the file's own parsed structure, not by file extension.

This is layered entirely on top of already-shipped parsing: no new byte-level decoding is introduced. Every value shown in Media Summary is read from `BoxNode`/`BoxField` values that existing decoders (`FtypBoxDecoder`, `MvhdBoxDecoder`, `TkhdBoxDecoder`, `MdhdBoxDecoder`, `HdlrBoxDecoder`, `VisualSampleEntryDecoder`, `AudioSampleEntryDecoder`, `StszBoxDecoder`, `IspeBoxDecoder`, `ColrBoxDecoder`, `ExifDecoder`'s IFD tree, `SefdBoxDecoder`, `JpegWalker`'s `SOF*` nodes) already produce.

## Goal

1. Every opened file gets a 2-tab sub-selector, nested inside its existing per-file tab: **Media Summary** and **Structure Analyser**.
2. **Structure Analyser** is the current tree+field+hex layout, unchanged in behavior, just relabeled and moved under this second tab.
3. **Media Summary** is a new curated view. The engine classifies the file as `IMAGE` or `VIDEO` by inspecting its parsed root `BoxNode` (not the file extension), and renders a different set of sections accordingly:
   - **Image** (JPEG, HEIC, or any other ISOBMFF-image-shaped file): Basic Info, Camera Info, GPS Location (if present), Samsung Metadata (if present).
   - **Video** (MP4/MOV with at least one video or audio track): Basic Info, Track List, Video Track Detail (if present), Audio Track Detail (if present).
4. Sections with nothing to show are omitted entirely (no empty "GPS Location" section on a photo with no GPS tags, no "Audio Track Detail" on a silent video, etc.).

## Non-Goals

- No new byte-level parsing. If a value isn't already exposed by an existing decoder, it isn't in Media Summary v1 (e.g., no `stts`-based exact frame-rate reconstruction, no per-track bitrate — see Design for the specific approximations used instead).
- No GPS coordinate conversion/formatting beyond what's already stored (the existing `GPSLatitude`/`GPSLongitude` fields are raw DMS rational strings, e.g. `"0/0, 0/0, 0/0"` when the camera recorded no location — Media Summary displays them as-is, not converted to decimal degrees or validated as non-zero).
- No per-track bitrate estimation (would require summing every `stsz` sample-size entry per track, which is unbounded work for long videos). Only a single whole-file average bitrate is shown, computed from `file.length()` and total duration.
- No editing, exporting, or copying from Media Summary — display only, matching the read-only nature of the rest of the app.
- No support for audio-only files (`.m4a`/bare audio ISOBMFF) as a third category — out of scope, matching this project's existing file-type scope (image/video containers only).

## Design

### 1. Category detection

A new function inspects the already-parsed root `BoxNode` (the same one `Structure Analyser` already renders) — no re-parsing:

- If any direct child of root has `type == "SOI"` (the JPEG walker's first-emitted node — always present for JPEG, never present for ISOBMFF), classify as `IMAGE` immediately.
- Otherwise (an ISOBMFF file — MP4/MOV/HEIC), look for a top-level `moov` child. If found, walk its `trak` children; for each, find the nested `mdia > hdlr` node and read its `handler_type` field. If any track's `handler_type` is `"vide"` or `"soun"`, classify as `VIDEO`.
- Otherwise (no `moov`, or a `moov` with no video/audio track — i.e. HEIC's `meta`/`iprp`-based structure), classify as `IMAGE`.

This check only looks at **direct/shallow** structure (root's own children, and `moov`'s own `trak` children) — it does not recurse into nested embedded content. This is deliberate: a Motion Photo JPEG or HEIC can contain a full nested MP4 (with its own `moov`/`trak`) inside a `sefd` field or an `mpvd` box, but the *file itself* is still fundamentally an image, so it must not be misclassified as `VIDEO` because of what's embedded inside it. Since the check starts by testing for a top-level `SOI` (JPEG) or only inspects `moov`'s *direct* `trak` children (not `moov` boxes found deeper in the tree), embedded video content several levels down never reaches this logic.

### 2. Data model (`MediaSummary.kt`, new file)

```kotlin
enum class MediaCategory { IMAGE, VIDEO }
data class SummaryField(val label: String, val value: String)
data class SummarySection(val title: String, val fields: List<SummaryField>)
data class MediaSummary(val category: MediaCategory, val sections: List<SummarySection>)
```

Deliberately not reusing `BoxField` (which carries file `offset`/`length` for the tree/hex-view highlighting use case) — summary values are read from possibly several different source fields and don't map to one byte range, so a simpler label/value pair avoids implying a click-to-jump affordance this view doesn't have.

### 3. Extraction (`MediaSummaryBuilder.kt`, new file)

`fun buildMediaSummary(root: BoxNode, file: File): MediaSummary` — a single entry point, dispatching to `buildImageSummary`/`buildVideoSummary` based on the category detection above. All tree lookups use a small recursive `findFirst(node) { predicate }` helper (search the whole tree depth-first for the first node matching a type predicate) so the same extraction code works whether the value lives directly under the root (JPEG's `APP1 > IFD0`) or several levels deeper (HEIC's `meta > iloc > item_N > IFD0`) — no separate JPEG/HEIC code paths.

**Image — Basic Info:**
- Resolution: first node found matching `type.startsWith("SOF")` (JPEG) or `type == "ispe"` (HEIC) — read `width`/`height` (JPEG) or `image_width`/`image_height` (HEIC).
- File size: `file.length()`, formatted human-readable (e.g. `"5.4 MB"`).
- Format: `"JPEG"` if an `SOI` node exists; otherwise the first `ftyp` node's `major_brand` field (e.g. `"heic"`).
- Color space: first `colr` node's `summary`, if present; else, for JPEG, derived from the same `SOF*` node's `num_components` field (`3` → `"Color (YCbCr)"`, `1` → `"Grayscale"`, else `"Unknown"`).
- Capture date: first `IFD0` or `Exif` node's `DateTimeOriginal` field if present, else `IFD0`'s `DateTime` field.

**Image — Camera Info** (omitted entirely if no `IFD0` node exists anywhere in the tree): `Make`, `Model` (from `IFD0`), `ExposureTime`, `FNumber`, `ISOSpeedRatings`, `FocalLength` (from the nested `Exif` sub-IFD) — each shown only if the corresponding field is present.

**Image — GPS Location** (omitted entirely if no `GPS` node exists): `GPSLatitudeRef`+`GPSLatitude`, `GPSLongitudeRef`+`GPSLongitude`, shown as raw values per the Non-Goals note above.

**Image — Samsung Metadata** (omitted entirely if no `sefd` node exists anywhere in the tree, whether from the HEIC box form or the JPEG trailer form): one row per direct child field of the `sefd` node, `label = field.type`, `value = field.summary ?: (first field's value)`.

**Video — Basic Info:**
- Duration: first `mvhd` node's `timescale`/`duration` fields, computed as `duration / timescale` seconds, formatted `"H:MM:SS"`.
- Resolution: the `tkhd` (`width`/`height` fields) belonging to the *video* track specifically (the `trak` whose `mdia > hdlr` has `handler_type == "vide"`), not just any track.
- File size: `file.length()`, human-readable.
- Container brand: first `ftyp` node's `major_brand` field.
- Average bitrate: `file.length() * 8 / durationSeconds`, formatted (e.g. `"12.4 Mbps"`) — whole-file only, per Non-Goals.

**Video — Track List:** one row per top-level `moov > trak`, counting by `handler_type` (`"Video tracks"` / `"Audio tracks"` / `"Other tracks"` for any other handler type), e.g. `"Video: 1"`, `"Audio: 1"`.

**Video — Video Track Detail** (omitted if no video track): for the video track's `trak`, the codec box name found at `trak > mdia > minf > stbl > stsd > (avc1|hvc1)` (the node's own `type`, e.g. `"avc1"`), plus an estimated frame rate: that track's `stsz` sample count (`sample_count` field, or `table.entryCount` if variable-size) divided by that track's own `mdhd`-derived duration in seconds.

**Video — Audio Track Detail** (omitted if no audio track): for the audio track's `trak`, the codec box name at `stsd > mp4a` (or whichever audio sample entry is present), plus its `samplerate` and `channelcount` fields, read directly.

### 4. UI (`MediaSummaryView.kt`, new file)

Renders `MediaSummary` as a `Column` of sections, each a title `Text` followed by its `SummaryField`s as label/value rows — visually matching `FieldPanel.kt`'s existing `MetadataRow` composable (reused as-is, since it's already a generic `(label, value)` row renderer with no `BoxNode`-specific coupling).

### 5. Integration (`Main.kt`, `AppState.kt`)

`TabState` gains `var mediaSummary: MediaSummary? by mutableStateOf(null)` (built once in `AppState.openFile`, right after `tab.root = parseFile(file)` succeeds — same place, same error handling) and `var summaryTabIndex: Int by mutableStateOf(0)` (0 = Media Summary, 1 = Structure Analyser; defaults to Media Summary as the more useful first impression).

In `Main.kt`, directly below the existing per-file `TabRow` (which selects *which open file*), a second `TabRow` is added (which selects *Media Summary vs Structure Analyser* for the currently-selected file) with two tabs labeled `"Media Summary"` and `"Structure Analyser"`. The body below switches on `currentTab.summaryTabIndex`: `0` renders `MediaSummaryView(currentTab.mediaSummary)`, `1` renders the existing tree+field+hex `Column`/`Row`/`DraggableDivider` block, completely unchanged.

## Testing

Unit tests (no Compose UI test infrastructure exists in this project — as established for prior UI-adjacent features, this is manual/visual verification, not automated) for `MediaSummaryBuilder.kt`, following the established `byteReaderOf`/synthetic-`BoxNode`-construction convention:
- Category detection: a JPEG-shaped root (has `SOI` child) → `IMAGE`; an ISOBMFF root with `moov > trak > mdia > hdlr(handler_type="vide")` → `VIDEO`; an ISOBMFF root with only `meta`/`iprp` children (HEIC-shaped, no `moov`) → `IMAGE`; a JPEG root that also happens to contain a `sefd` node with an embedded-MP4 `MotionPhoto_Data` field (itself containing a nested `moov`/`trak`) → still `IMAGE` (confirms the shallow-only detection rule).
- Image summary: a synthetic tree with `SOF0`, `IFD0`/`Exif`/`GPS` children, and a `sefd` node produces all four sections with correct values; a minimal tree with only `SOF0` (no Exif at all) produces only Basic Info, with Camera Info/GPS/Samsung Metadata sections entirely absent.
- Video summary: a synthetic tree with `moov`/`mvhd`/two `trak`s (one video, one audio) produces all four sections with correct duration formatting, resolution from the *video* track's `tkhd` specifically (not the audio track's), and correct per-track codec/frame-rate/sample-rate values; a video-only (no audio trak) tree omits Audio Track Detail.

Manual verification: open one of the real sample JPEGs and a real sample MP4/MOV already used for this project's testing, confirm Media Summary shows plausible values (matching what Structure Analyser's tree already shows for the same fields) and Structure Analyser still renders identically to before this change.
