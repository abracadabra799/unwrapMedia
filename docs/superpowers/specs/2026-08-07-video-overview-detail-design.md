# Video Overview Detail — Design

## Goal

Extend the same Overview-depth treatment already shipped for JPEG and the other image formats to video (MP4/MOV/M4V and WebM) — the third Overview-depth sub-project (JPEG → images → **this** → audio). Unlike the prior two sub-projects, this one needs **zero new parsing**: every field below is already parsed by existing box/element decoders (`MvhdBoxDecoder`, `MdhdBoxDecoder`, `HdlrBoxDecoder`, `AvcCBoxDecoder`, `HvcCBoxDecoder`, `ElstBoxDecoder`, the `stss`/`ctts` table decoders, and `EbmlWalker`'s `DateUTC`/`MuxingApp`/`WritingApp`/`StereoMode` elements) but never surfaced in the Overview tab.

Also folds in a related, explicitly requested addition: per-track (video/audio) duration with millisecond precision in the General section, most visible in the Motion Photo Overview's embedded-video summary card (which already reuses this same `buildVideoSummary` code path).

## Non-Goals

- Audio-only formats (M4A/MP3/WAV/etc.) — the next, separate sub-project.
- Signature Analysis (re-encoding/editing detection) — deferred indefinitely per earlier agreement.
- Any new parsing. If a real-file verification pass (Task N, this plan's equivalent of the JPEG sub-project's Task 3) surfaces a genuine gap that needs new parsing, that becomes a follow-up, not silently folded into this plan.
- Per-block keyframe/B-frame detection for WebM — this app does not currently parse individual `SimpleBlock` keyframe flags into a table the way MP4's `stss`/`ctts` are already parsed, so WebM does not get an equivalent "Keyframe Interval"/"B-Frames" facts. Only `StereoMode` (already parsed) is added for WebM's video track.
- Any change to the Detailed Properties (tree) tab or any UI/Compose file — the Overview tab already renders arbitrary `SummarySection` lists generically.

## Architecture

Two shapes of change, both to existing functions in `MediaSummaryBuilder.kt` — no new section for most of this (unlike JPEG/PNG/etc., which each got a brand-new section):

1. **Fields added to existing sections** — Creation Time/Modification Time/Video Track Duration/Audio Track Duration into `buildVideoGeneral`'s "General"; Creation Date/Muxing App/Writing App into `buildWebmGeneral`'s "General"; Handler Name/Language into `buildVideoDetail`'s "Video" and `buildAudioDetail`'s "Audio"; Stereo Mode into `buildWebmVideoDetail`'s "Video". Each of these fields is independently optional and several have an explicit "don't show the uninformative default" rule (see per-field sections below) to avoid cluttering every single file with a near-always-present-but-meaningless row.
2. **One new section, MP4-family only** — `buildVideoStructureDetail`, producing a new **"Video Detail"** section (distinct from the existing "Video" section — "Video" already covers stream/codec identity, both this app's own fields and ffprobe's `mergeStreamCodecDetails` enrichment; "Video Detail" covers container/bitstream *structure* facts that don't fit that framing: NAL length size, parameter set counts, edit list, keyframe interval, B-frame usage). No WebM equivalent — see Non-Goals.

```kotlin
private fun buildVideoSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    val moov = root.children.find { it.type == "moov" }
    val traks = moov?.children?.filter { it.type == "trak" } ?: emptyList()
    val videoTrak = traks.find { trakHandlerType(it) == "vide" }
    val audioTrak = traks.find { trakHandlerType(it) == "soun" }

    sections.add(buildVideoGeneral(root, fileSizeBytes, moov, videoTrak, audioTrak))
    sections.add(buildTrackList(traks))
    buildVideoDetail(videoTrak)?.let { sections.add(it) }
    buildVideoStructureDetail(videoTrak, movieTimescale(moov))?.let { sections.add(it) }
    buildAudioDetail(audioTrak)?.let { sections.add(it) }

    return sections
}
```

Because `buildMotionPhotoVideoSummary` already calls this same `buildVideoSummary` on the embedded video's own parsed tree, every field below automatically appears in the Motion Photo Overview's "🎬 동영상(모션포토) 분석 요약" card too — no special-casing needed, matching this codebase's existing architecture.

---

## General section (MP4-family)

Modify `buildVideoGeneral(root, fileSizeBytes, moov, videoTrak, audioTrak)` (signature gains `videoTrak`/`audioTrak`, both already computed one call site up in `buildVideoSummary`):

- **Creation Time** / **Modification Time**: `mvhd`'s existing `creation_time`/`modification_time` fields (already formatted as real dates by `formatMp4Time`, or the literal string `"0 (not set)"` when the muxer wrote zero). Only add the field when the value does **not** start with `"0 "` — most muxers that don't bother writing a real timestamp leave this zeroed, and showing "not set" on every single file would be noise; only surface it when there's an actual date to report.
- **Video Track Duration** / **Audio Track Duration**: computed from each track's own `mdhd` (`findFirst(videoTrak) { it.type == "mdhd" }`, same for `audioTrak`) — `timescale`/`duration` fields, same formula `buildVideoDetail`'s Frame Rate calculation already uses (`duration.toDouble() / timescale`), formatted via the existing `formatDuration` (already millisecond-precision: `"H:MM:SS.mmm"`). Independently optional — omitted if that track doesn't exist or its `mdhd` is missing/degenerate (`timescale <= 0`). This is the explicitly requested per-track duration, most visible in the Motion Photo embedded-video Overview card where users want to see the video and audio streams' own durations at a glance, not just the movie-level `Duration` already shown.

## General section (WebM)

Modify `buildWebmGeneral(fileSizeBytes, info)`:

- **Creation Date**: `Info`'s `DateUTC` child (`webmFieldValue(info, "DateUTC")` — already a parsed date string per `EbmlElementType.DATE`). Omitted if absent (many encoders don't write it).
- **Muxing App** / **Writing App**: `Info`'s `MuxingApp`/`WritingApp` children (`webmFieldValue(info, "MuxingApp")`/`"WritingApp"`, already UTF8-decoded strings). Independently optional.

## Video / Audio sections (MP4-family)

Modify `buildVideoDetail(videoTrak)` and `buildAudioDetail(audioTrak)` — same two additions to each, applied to that function's own track parameter:

- **Handler Name**: `hdlr`'s existing `name` field (`findFirst(trak) { it.type == "hdlr" }`), only added when non-blank (many muxers leave it empty, and an empty-string field reads as a bug rather than "no name given").
- **Language**: `mdhd`'s existing `language` field (`findFirst(trak) { it.type == "mdhd" }`), only added when it's not `"und"` (ISO-639-2's "undefined" — the overwhelming common default when no language was actually set, so showing it on every file would be noise; only surface a real language code).

## Video section (WebM)

Modify `buildWebmVideoDetail(videoTrack)`:

- **Stereo Mode**: `Video`'s `StereoMode` child (`webmFieldValue(video, "StereoMode")`), only added when the value is not `"0"` (mono/no-3D is the overwhelming default; only surface when the file is actually stereoscopic). Labeled via:
  ```kotlin
  private val WEBM_STEREO_MODE_NAMES = mapOf(
      1 to "Side by Side (Left Eye First)",
      2 to "Top-Bottom (Right Eye First)",
      3 to "Top-Bottom (Left Eye First)",
      11 to "Side by Side (Right Eye First)",
  )
  ```
  falling back to the raw number for any other value (the Matroska spec defines several more exotic modes — checkerboard, row/column-interleaved, anaglyph — not worth enumerating for an at-a-glance Overview field).

## New "Video Detail" section (MP4-family only)

```kotlin
private fun movieTimescale(moov: BoxNode?): Long? =
    moov?.children?.find { it.type == "mvhd" }?.fields?.find { it.name == "timescale" }?.value?.toLongOrNull()

private fun buildVideoStructureDetail(videoTrak: BoxNode?, movieTimescale: Long?): SummarySection? {
    if (videoTrak == null) return null
    val fields = mutableListOf<SummaryField>()

    val avcC = findFirst(videoTrak) { it.type == "avcC" }
    val hvcC = findFirst(videoTrak) { it.type == "hvcC" }
    when {
        avcC != null -> {
            avcC.fields.find { it.name == "length_size" }?.let { fields.add(SummaryField("NAL Length Size", "${it.value} bytes")) }
            val numSps = avcC.fields.find { it.name == "num_sps" }?.value
            val numPps = avcC.fields.find { it.name == "num_pps" }?.value
            if (numSps != null && numPps != null) fields.add(SummaryField("Parameter Sets", "$numSps SPS, $numPps PPS"))
        }
        hvcC != null -> {
            hvcC.fields.find { it.name == "length_size" }?.let { fields.add(SummaryField("NAL Length Size", "${it.value} bytes")) }
            val numVps = hvcC.fields.find { it.name == "num_vps" }?.value
            val numSps = hvcC.fields.find { it.name == "num_sps" }?.value
            val numPps = hvcC.fields.find { it.name == "num_pps" }?.value
            if (numVps != null && numSps != null && numPps != null) {
                fields.add(SummaryField("Parameter Sets", "$numVps VPS, $numSps SPS, $numPps PPS"))
            }
        }
    }

    val elst = findFirst(videoTrak) { it.type == "elst" }
    if (elst != null && elst.fields.isNotEmpty()) {
        val editCount = elst.fields.count { it.name == "segment_duration" }
        val firstMediaTime = elst.fields.find { it.name == "media_time" }?.value
        val firstSegmentDuration = elst.fields.find { it.name == "segment_duration" }?.value?.toDoubleOrNull()
        val label = if (firstMediaTime == "-1" && firstSegmentDuration != null && movieTimescale != null && movieTimescale > 0) {
            val offsetSeconds = firstSegmentDuration / movieTimescale
            "${pluralize(editCount.toLong(), "edit", "edits")} (empty edit, ${formatDuration(offsetSeconds)} offset)"
        } else {
            pluralize(editCount.toLong(), "edit", "edits")
        }
        fields.add(SummaryField("Edit List", label))
    }

    val stss = findFirst(videoTrak) { it.type == "stss" }
    val stsz = findFirst(videoTrak) { it.type == "stsz" }
    val totalSamples = stsz?.fields?.find { it.name == "sample_count" }?.value?.toLongOrNull() ?: stsz?.table?.entryCount
    if (totalSamples != null && totalSamples > 0) {
        if (stss == null) {
            fields.add(SummaryField("Keyframe Interval", "All frames (no separate sync sample table)"))
        } else {
            val keyframeCount = stss.table?.entryCount ?: 0
            if (keyframeCount > 0) {
                val avgInterval = totalSamples.toDouble() / keyframeCount
                fields.add(SummaryField("Keyframe Interval", "$keyframeCount of $totalSamples frames (every ~${"%.0f".format(avgInterval)} frames)"))
            }
        }
    }

    val ctts = findFirst(videoTrak) { it.type == "ctts" }
    fields.add(SummaryField("B-Frames", if (ctts != null && (ctts.table?.entryCount ?: 0) > 0) "Yes" else "No"))

    return if (fields.isNotEmpty()) SummarySection("Video Detail", fields) else null
}
```

Notes on the code above:
- `avcC`/`hvcC` are mutually exclusive per file (H.264 vs. HEVC) — `when` picks whichever is present, matching how `CODEC_DISPLAY_NAMES` lookups elsewhere in this file already assume one codec box per track.
- `elst`'s fields are a flat repeated triple (`segment_duration`, `media_time`, `media_rate` per edit, per `ElstBoxDecoder`) — `elst.fields.count { it.name == "segment_duration" }` counts edits without needing a new grouping structure, and `elst.fields.find { it.name == "media_time" }`/`"segment_duration"` grab the *first* edit's values (an empty edit, when present, is always the first edit per the ISOBMFF convention this detects).
- `Keyframe Interval`'s "no separate sync sample table" branch reflects ISO/IEC 14496-12's own rule: absence of `stss` means every sample is a random access point (a fully justified assertion, not a guess) — distinct from the "all-keyframes but zero total samples" degenerate case, which is guarded by the `totalSamples > 0` check.
- `B-Frames` is **always** added (not conditionally omitted) once this section renders at all, since `ctts` presence/absence is a complete, already-fully-parsed signal — unlike, say, the HEIC/AVIF sub-project's `auxC`-based alpha-channel check (a partial substring heuristic), this is an authoritative "yes" or "no", not a "yes or nothing".

## Testing

Same conventions as the JPEG and image-formats sub-projects: synthetic-`BoxNode`-tree tests in `MediaSummaryBuilderTest.kt` for every modified/new function, covering the happy path, each field's independent-omission rule (the `"0 (not set)"`/`"und"`/`"0"` skip cases above are exactly the kind of edge case that needs its own test, not just a happy-path one), and — since this plan touches five existing functions rather than adding format-gated new ones — a check that a **non-video** media type (e.g. a JPEG fixture) is completely unaffected, run once at the end rather than per-function (unlike the image-formats plan's per-section non-matching-format tests, since none of these changes are behind a new format-detection gate; they're purely additive to already-format-gated functions).

Given the JPEG sub-project's manual-verification task caught a real structural surprise only visible in a real file (two concatenated JPEG streams), the equivalent manual-verification task here should test against at least one real MP4/MOV file (H.264 and, if available, HEVC), one real WebM file, and the Motion Photo Overview card specifically (to confirm Video Track Duration/Audio Track Duration render correctly there, per the explicit request that motivated adding them). Specifically watch for files with more than one video track (multi-angle recordings, some screen-capture tools) — `buildVideoStructureDetail`'s `findFirst` calls search the whole already-selected `videoTrak` subtree, which is safe by construction (unlike the JPEG bug, there's no "two video traks concatenated into one subtree" scenario possible here since `videoTrak` is already narrowed to a single `trak` node before any `findFirst` runs), but real-world multi-track files are still worth eyeballing to confirm the *right* video track's structure is what's shown.
