# Motion Photo Preview Video Extraction — Design

## Background

The `MotionPhoto` menu already has `동영상 추출`, which extracts the full-length embedded video from a Motion Photo (via `MotionPhotoExtractor.findEmbeddedVideo`, preferring the SEFD `MotionPhoto_Data` field for Samsung JPEGs, `mpvd` for HEIC, or Google's XMP-based schemes). Samsung's SEFD trailer separately carries a second, much shorter clip in a field named `MotionPhoto_AutoPlay` — a short looping preview intended for gallery auto-play, confirmed via byte-level analysis of real sample files during the `MotionPhoto_Data` bug fix earlier this session (`MotionPhoto_AutoPlay` was the field the old, buggy code accidentally extracted instead of the real video). The user wants this preview clip extractable too, as its own menu action.

## Goal

Add a second `MotionPhoto` menu item, `미리보기 동영상 추출`, that extracts the `MotionPhoto_AutoPlay` clip specifically and saves it to a file the user chooses. Enabled only when that field actually exists in the currently selected tab's file.

## Non-Goals

- No change to the existing `동영상 추출` item or its `MotionPhoto_Data`/`mpvd`/XMP detection logic.
- No support for a `MotionPhoto_AutoPlay`-equivalent in HEIC (`mpvd`-only) or Google XMP-only files — this field is Samsung-SEFD-specific; the menu item is simply disabled (not shown as an error) for any file that doesn't have it.
- No shared "which clip did you mean" UI — this is a second, independent, always-visible menu item, exactly like `동영상 추출`'s own pattern.

## Design

### 1. Detection (`MotionPhotoExtractor.kt`)

```kotlin
fun findMotionPhotoPreview(root: BoxNode): EmbeddedVideo? {
    val previewNode = findFirst(root) { it.type == "sefd" }
        ?.children
        ?.find { it.type == "MotionPhoto_AutoPlay" && it.children.firstOrNull()?.type == "ftyp" }
        ?: return null
    val majorBrand = previewNode.children.find { it.type == "ftyp" }
        ?.fields?.find { it.name == "major_brand" }?.value
    val extension = if (majorBrand?.trim() == "qt") "mov" else "mp4"
    return EmbeddedVideo(previewNode.offset + previewNode.headerSize, previewNode.offset + previewNode.size, extension)
}
```

Mirrors `findEmbeddedVideo`'s existing SEFD-field validation and extension logic exactly, but selects specifically by name (`MotionPhoto_AutoPlay`) rather than the "prefer `MotionPhoto_Data`, fall back to first ftyp-sniffed field" logic used for the main video. Returns `null` whenever the field doesn't exist (HEIC-only, XMP-only, or plain non-motion-photo files) — this directly is the enable/disable signal, no separate flag needed.

### 2. State (`AppState.kt`)

`TabState` gains `motionPhotoPreview: EmbeddedVideo? by mutableStateOf(null)`, computed in `openFile` right alongside the existing `embeddedVideo` computation, with the same try/catch-to-null pattern (a detection failure must not collapse the tab).

### 3. UI (`Main.kt`)

A second `Item` under the existing `MotionPhoto` menu:

```kotlin
Item(
    "미리보기 동영상 추출",
    enabled = currentTab?.motionPhotoPreview != null,
    onClick = { currentTab?.let { extractMotionPhotoPreview(appState, it) } },
)
```

`extractMotionPhotoPreview` mirrors `extractMotionPhotoVideo` exactly (same save-dialog flow, same `extractEmbeddedVideo` byte-copy call, same `appState.statusMessage` success/failure reporting), reading `tab.motionPhotoPreview` instead of `tab.embeddedVideo`, and defaulting the save filename to `<name>_preview.<ext>` (distinct from the main video's `_motion` suffix, so extracting both doesn't overwrite one with the other).

## Testing

- `MotionPhotoExtractorTest` additions: a SEFD fixture with both `MotionPhoto_AutoPlay` and `MotionPhoto_Data` fields present (mirroring the real-world field order) asserts `findMotionPhotoPreview` returns the `AutoPlay` field's range specifically, distinct from what `findEmbeddedVideo` returns for the same tree; a fixture with no `MotionPhoto_AutoPlay` field (including one with only `MotionPhoto_Data`, and one with only `mpvd`) asserts `null`.
- Manual verification: open a real Samsung Motion Photo JPEG (the same samples used to verify the `MotionPhoto_Data` fix), confirm `미리보기 동영상 추출` is enabled and produces a short, playable clip; confirm it's disabled for a HEIC-only or Google-XMP-only motion photo and for an ordinary non-motion-photo file.
