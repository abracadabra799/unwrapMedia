# Video Media Summary Codec Details — Design

## Background

`VideoInspectorUI`'s "🎬 비디오 분석 요약" panel (built by `buildMediaSummary` → `buildVideoSummary` in `MediaSummaryBuilder.kt`) currently shows General/Track List/Video/Audio sections derived purely from parsed ISOBMFF box fields: duration, file size, container format, overall bit rate, track counts, codec fourcc (translated via `CODEC_DISPLAY_NAMES`), width/height, frame rate (computed from `stts`/`stsz`), audio sample rate and channel count. This is noticeably thinner than what the MediaInfo tool shows for the same file — no codec profile/level, no per-stream bit rate, no color space/chroma/bit-depth information, no frame-rate-mode (constant vs variable) detection.

The missing fields (codec profile name, bit depth, chroma subsampling, color primaries/transfer/matrix, per-stream bit rate) require parsing inside the actual H.264/HEVC SPS bitstream or codec-specific metadata that this app's box parser does not currently decode — the same category of problem the recent frame-type (GOP) feature solved by delegating to the already-bundled `ffprobe` rather than writing a bitstream parser. Verified directly against a real test file (`~/Downloads/20260718_200431_motion.mp4`, HEVC + AAC): a single `ffprobe -show_entries stream=...` call reliably returns `profile=Main`, `level=120`, `pix_fmt=yuvj420p`, `color_primaries=bt470bg`, `color_transfer=bt709`, `color_space=smpte170m`, `color_range=pc`, `bit_rate=10109250` for the video stream, and `profile=LC`, `bit_rate=256426`, `channel_layout=stereo` for the audio stream — everything needed, in one fast call whose cost doesn't scale with video length (unlike `-show_frames`, used for GOP analysis, which does).

## Goal

Opening a video file automatically (no button) enriches the existing "Video" and "Audio" summary sections with codec profile, level, bit rate, chroma subsampling, bit depth, color primaries/transfer/matrix, color range, and frame-rate mode (constant/variable) — matching MediaInfo's level of detail, merged into the same sections the box parser already produces rather than shown as separate panels.

## Non-Goals

- No change to the image summary (`buildImageSummary`) — video only, per user decision.
- No change to `buildMediaSummary`'s existing box-parsing logic — it stays pure and ffmpeg-free; the new data is merged in afterward.
- No hand-rolled H.264/HEVC SPS bitstream parser — `ffprobe` supplies everything.
- No UI/button for this — it's automatic background enrichment, since the ffprobe call is cheap and length-independent (unlike GOP analysis, which is button-gated because it scales with frame count).

## Design

### Data extraction (`StreamCodecDetails.kt`, new, `com.multiviewer.ui` package — alongside `FrameTypeAnalyzer.kt`, `FfmpegVideoPlayer.kt`)

```kotlin
data class StreamCodecDetails(
    val videoFields: List<SummaryField>,
    val audioFields: List<SummaryField>,
)

fun probeStreamDetails(file: File): StreamCodecDetails?
```

Runs `ffprobe -v error -show_entries "stream=index,codec_type,profile,level,pix_fmt,color_space,color_transfer,color_primaries,color_range,bit_rate,r_frame_rate,avg_frame_rate,channel_layout" -of default=noprint_wrappers=1 <file>`, matching the established `redirectError(Redirect.DISCARD)` / try-catch-null pattern from `probeVideo`/`probeFrameTypes`. Output is flat `key=value` lines grouped per stream, each group starting with an `index=` line (verified directly — no multi-line fields are requested, so unlike a bare `-show_streams`, there's no `side_data`/`displaymatrix` block to worry about). Parsed by accumulating key=value pairs into a map per stream, finalizing whenever a new `index=` line starts (or at end of input), and building a `SummaryField` list depending on that stream's `codec_type`:

**Video fields** (only when `codec_type=video`):
- `Profile` ← `profile` (as-is, e.g. `"Main"`)
- `Level` ← `level` (as-is; HEVC/H.264 level-number formulas differ, so the raw ffprobe value is shown rather than risking a wrong conversion)
- `Bit Rate` ← `bit_rate`, formatted with the same Mbps/Kbps/bps scaling already used elsewhere (reimplemented locally in this file — `ui` package doesn't reach into `parser`'s private `formatBitrate`)
- `Chroma Subsampling` ← derived from `pix_fmt` (`"yuv420p"`/`"yuvj420p"` → `"4:2:0"`, `"422"` → `"4:2:2"`, `"444"` → `"4:4:4"`, else the raw `pix_fmt` string as a fallback)
- `Bit Depth` ← derived from `pix_fmt` (contains `"10"` → `"10 bit"`, `"12"` → `"12 bit"`, `"16"` → `"16 bit"`, else `"8 bit"`)
- `Color Primaries` / `Transfer Characteristics` / `Matrix Coefficients` ← `color_primaries` / `color_transfer` / `color_space` as-is (ffprobe already returns human-readable names like `"bt709"`)
- `Color Range` ← `color_range` as-is (`"pc"` = full range, `"tv"` = limited — shown verbatim, no further translation)
- `Frame Rate Mode` ← `"Constant"` if `r_frame_rate == avg_frame_rate`, else `"Variable"` (verified meaningful on the real test file: `r_frame_rate=120/1` vs `avg_frame_rate=237500/7833` ≈ 30.3 — correctly flagged as Variable for this real phone-recorded clip)

**Audio fields** (only when `codec_type=audio`):
- `Profile` ← `profile` (e.g. `"LC"`)
- `Bit Rate` ← `bit_rate`, same formatting as video
- `Channel Layout` ← `channel_layout` (e.g. `"stereo"`)

Fields whose source value is missing/unparseable are simply omitted (matches the existing box-parser sections' `if (x != null) fields.add(...)` convention throughout `MediaSummaryBuilder.kt`).

### Merge (`MediaSummaryBuilder.kt`, new function alongside the existing `build*` functions)

```kotlin
fun mergeStreamCodecDetails(summary: MediaSummary, videoFields: List<SummaryField>, audioFields: List<SummaryField>): MediaSummary {
    val mergedSections = summary.sections.map { section ->
        when (section.title) {
            "Video" -> section.copy(fields = section.fields + videoFields)
            "Audio" -> section.copy(fields = section.fields + audioFields)
            else -> section
        }
    }
    return summary.copy(sections = mergedSections)
}
```

Matches on the existing section titles `"Video"`/`"Audio"` (set by `buildVideoDetail`/`buildAudioDetail`) and appends the new fields onto them — no new sections, no change to section ordering, `SummarySection`/`MediaSummary` being simple data classes makes this a pure structural `.copy()`. If a video has no video or audio track (so `buildVideoDetail`/`buildAudioDetail` returned `null` and no such section exists), the corresponding fields are silently dropped — there's nothing to append them to, which is correct (nothing to enrich).

### Wiring (`AppState.kt`'s `openFile()`, modified)

Inside the existing background `Thread` (already running `parseFile`, `buildMediaSummary`, etc. — see the earlier UI-responsiveness fix from this session), when `type == MediaType.VIDEO`: call `probeStreamDetails(file)` right after `mediaSummary` is computed, and if both succeed, replace `mediaSummary` with `mergeStreamCodecDetails(mediaSummary, details.videoFields, details.audioFields)` before the `EventQueue.invokeLater` block that assigns `tab.mediaSummary`. This is one additional fast, length-independent subprocess call on the same background thread the rest of the analysis already runs on — the UI never blocks, and no new button/loading-state is needed since the whole `mediaSummary` (enriched or not) becomes visible at the same moment it always has (when `tab.isLoading` flips to `false`).

## Testing

`probeStreamDetails` is tested the same way `probeVideo`/`probeFrameTypes` already are: generate a real synthetic video via `ffmpeg -f lavfi -i "testsrc=..."`, run `probeStreamDetails` against it, assert the video fields list is non-empty and contains expected keys (e.g. `"Chroma Subsampling"`, `"Frame Rate Mode"`) with sane values (no mocking, matches this codebase's established convention for ffmpeg-backed code).

`mergeStreamCodecDetails` is tested with a hand-built `MediaSummary` (a `"Video"` section and an `"Audio"` section with a couple of pre-existing fields each) and asserts the new fields are appended after the existing ones, section titles/order are unchanged, and a summary with no `"Video"`/`"Audio"` section is returned unchanged.

No automated test for the `AppState.openFile()` wiring beyond what already exists — the existing `AppStateTest` async-behavior tests (`waitForLoad`) continue to cover that `openFile()` doesn't block and settles correctly; a manual check (open a real HEVC+AAC video, confirm Profile/Level/Bit Rate/Chroma/Bit Depth/Color fields appear in the Video section and Profile/Bit Rate/Channel Layout appear in Audio) closes the loop.
