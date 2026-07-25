# Codec Details Extension: Duration, Frame Count, Motion Photo — Design

## Background

The just-shipped codec-details feature (`probeStreamDetails`/`mergeStreamCodecDetails`) enriches the main video's "Video"/"Audio" summary sections with Profile, Level, Bit Rate, Chroma Subsampling, Bit Depth, Color Primaries/Transfer/Matrix, Color Range, and Frame Rate Mode. Two gaps remain: (1) neither section shows a per-stream Duration or Frame Count (the existing "General" section only has a container-level Duration; ffprobe already returns `duration` and `nb_frames` per stream — confirmed present in the same `-show_streams` output already used for this feature: `duration=3.133200`/`nb_frames=95` for video, `duration=3.029292`/`nb_frames=142` for audio, in the real HEVC+AAC test file); (2) the Motion Photo Video summary (`buildMediaSummary`'s `motionPhotoVideoSections`) only ever gets pure box-parsed fields — the embedded video is a byte range inside the image file, not a standalone file, so `ffprobe` can't be pointed at it directly the way it can for a real video file.

## Goal

Video and Audio sections (both the main video summary and the Motion Photo Video summary) show per-stream Duration and Frame Count alongside the existing codec fields. The Motion Photo Video summary gets the same codec-detail enrichment as the main video, via a button (not automatic, since it requires extracting the embedded video to a temporary file first — a real, if usually small, I/O cost the user should opt into, unlike the main video's already-a-real-file case).

## Non-Goals

- No change to the main video's automatic (non-button) enrichment — it stays as-is.
- No caching/sharing between this temp extraction and the separate one `MotionPhotoVideoPreview` already does for playback — they serve different purposes (one short-lived probe, one long-lived player source) and unifying them would add real coordination complexity for a marginal I/O saving on typically-small motion photo clips.

## Design

### Duration + Frame Count fields (`StreamCodecDetails.kt`, modified)

Add `duration` and `nb_frames` to `probeStreamDetails`'s `-show_entries stream=...` field list. In `buildVideoFields`/`buildAudioFields`, add:
- `Duration` ← `values["duration"]?.toDoubleOrNull()`, formatted `h:mm:ss` (a small local formatter in this file — mirrors `MediaSummaryBuilder.kt`'s private `formatDuration`, not shared across the package boundary, consistent with how `formatCodecBitrate` already duplicates rather than reaches into `parser`'s private functions).
- `Frame Count` ← `values["nb_frames"]` as-is (already a plain integer string; omitted if ffprobe reports `"N/A"`, which `toIntOrNull()` naturally filters).

### Motion Photo codec enrichment (button-triggered)

**Merge function refactor** (`MediaSummaryBuilder.kt`): extract the section-list-level merge logic out of `mergeStreamCodecDetails` into a new public function:

```kotlin
fun mergeStreamCodecDetailsIntoSections(sections: List<SummarySection>, videoFields: List<SummaryField>, audioFields: List<SummaryField>): List<SummarySection> {
    return sections.map { section ->
        when (section.title) {
            "Video" -> section.copy(fields = section.fields + videoFields)
            "Audio" -> section.copy(fields = section.fields + audioFields)
            else -> section
        }
    }
}

fun mergeStreamCodecDetails(summary: MediaSummary, videoFields: List<SummaryField>, audioFields: List<SummaryField>): MediaSummary {
    if (summary.category != MediaCategory.VIDEO) return summary
    return summary.copy(sections = mergeStreamCodecDetailsIntoSections(summary.sections, videoFields, audioFields))
}
```

`mergeStreamCodecDetails`'s existing `MediaCategory.VIDEO` guard doesn't apply to the motion-photo case (the outer file's category is `IMAGE`), so `AppState.kt` calls `mergeStreamCodecDetailsIntoSections` directly on `motionPhotoVideoSections`, bypassing the whole-summary category gate — safe because the caller already knows it's specifically the motion-photo video's own section list, not the outer image summary.

**State** (`AppState.kt`'s `TabState`): two new fields, mirroring the GOP feature's `isAnalyzingFrames`/(null-vs-non-null) pattern —
```kotlin
var isAnalyzingMotionPhotoCodec: Boolean by mutableStateOf(false)
var motionPhotoCodecDetailsLoaded: Boolean by mutableStateOf(false)
```
(A separate boolean is needed here, unlike GOP's `gopFrames == null` trick, because `motionPhotoVideoSections` is already non-null before enrichment — its nullability already means something else.)

**Trigger function** (`AppState.kt`, new method): `analyzeMotionPhotoCodecDetails(tab: TabState)` — guards on `tab.embeddedVideo`/`tab.mediaSummary` being present and not already (in-progress or done); on a background `Thread`: extracts the embedded video to a `File.createTempFile(...)` via the existing `extractEmbeddedVideo(source, video, destination)` (already used by `MotionPhotoVideoPreview`, same package `com.multiviewer.parser`), runs `probeStreamDetails` on that temp file, deletes the temp file immediately after probing (this is a short-lived probe-only file, not kept around for playback), then on `EventQueue.invokeLater`: merges the result into `tab.mediaSummary.motionPhotoVideoSections` via `mergeStreamCodecDetailsIntoSections` and sets both new flags.

### UI (`ImageInspectorUI.kt`, modified; `Main.kt`, modified)

`ImageInspectorUI` gains an `appState: AppState` parameter (same change already made to `VideoInspectorUI` for the GOP feature), and its one call site in `Main.kt` is updated to pass it. Where the Motion Photo Video summary currently renders unconditionally when present:

```kotlin
item {
    val videoSections = summary?.motionPhotoVideoSections
    if (videoSections != null) {
        Spacer(Modifier.height(16.dp))
        SummaryBox("🎬 동영상 (모션포토)", videoSections)
    }
}
```

a small button ("코덱 상세정보 분석") is shown above the `SummaryBox` when `tab.embeddedVideo != null && !tab.motionPhotoCodecDetailsLoaded`, calling `appState.analyzeMotionPhotoCodecDetails(tab)`; while `tab.isAnalyzingMotionPhotoCodec`, the button is replaced with a brief "분석 중..." label (same convention as the GOP button/loading states). Once loaded, the button/label disappears and the (now-enriched) `SummaryBox` reflects the merged fields — no further UI change needed, same "sections render generically" property the main video enrichment already relies on.

## Testing

`probeStreamDetails`'s existing test (`StreamCodecDetailsTest.kt`) is extended to also assert `Duration`/`Frame Count` values against the same real synthetic video used today (exact expected values verified by hand against a real `ffprobe` run before writing the plan).

`mergeStreamCodecDetailsIntoSections` gets its own direct test (hand-built section list, assert append behavior) in `MediaSummaryBuilderTest.kt`; the existing `mergeStreamCodecDetails` tests continue to cover the `MediaSummary`-level wrapper unchanged.

No automated test for `analyzeMotionPhotoCodecDetails`'s temp-extraction-and-probe flow or the UI button (matches this project's established convention: UI/Compose layer and the exact same kind of already-tested building blocks — `extractEmbeddedVideo`, `probeStreamDetails` — don't need a redundant end-to-end test). Manual verification: open a motion-photo image, confirm the "코덱 상세정보 분석" button appears above the Motion Photo Video summary, click it, confirm "분석 중..." appears briefly then the button disappears and Profile/Bit Rate/Duration/Frame Count/etc. appear in that summary's Video/Audio sections. Also re-verify the main video summary still shows Duration/Frame Count automatically (no button) after the `StreamCodecDetails.kt` field-list change.
