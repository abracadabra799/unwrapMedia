# Media Comparison Analyzer — Drag-and-Drop File Selection — Design

**Date:** 2026-09-11
**Status:** Approved (pending user spec review)

## Goal

Reduce the friction of picking the two files to compare in the Media Comparison
Analyzer (메뉴명: "미디어 비교 분석기", `ImageCompareWindow.kt`, `Cmd+D`). The window
already supports picking both files in one trip through the OS file dialog
(multi-select → auto-assign to A/B, see `resolveComparePick`) and re-picking from
already-open tabs or sibling files in the last-browsed folder (`FileDropdownOrLabel`).
The remaining friction the user identified is the OS file dialog itself — opening it
at all, navigating to a folder, and selecting inside it, even when it's a one-trip
multi-select. Drag-and-drop removes that step entirely for the common case.

This is **additive**: the existing "📂 파일 찾기" dialog button and the open-tab /
sibling-file dropdown are unchanged and remain the primary path for anyone who
prefers them.

## Non-goals

- Dropping a folder to batch-load or batch-compare its contents. No folder-to-folder
  comparison feature exists today, and this change doesn't add one — a dropped
  folder is ignored (see Error handling below).
- Any change to the existing file dialog, multi-select behavior, or the open-tab /
  sibling-file dropdown.

## Behavior

The whole `ImageCompareWindow` becomes a drop target, mirroring the existing
main-window drag-and-drop (`Main.kt`'s `attachRecursively` + `DropTargetAdapter`
pattern, which walks down to the Skia layer since that's the only component that
actually receives OS drag events under Compose Desktop).

Dropped items are filtered to files with a supported media extension (reusing
`ALL_SUPPORTED_MEDIA_EXTENSIONS`, the same list `FileDropdownOrLabel` uses for
sibling-file listing) before anything else happens. Non-media files and folders are
dropped from consideration before the count-based rules below apply.

| Dropped (after filtering) | Result |
|---|---|
| Exactly 2 valid media files | Same auto-assign as multi-select dialog: sort by filename (`resolveComparePick`'s existing rule), first → A, second → B. |
| Exactly 1 valid media file | Side is chosen by drop x-position: left half of the window → A, right half → B (see below). Overwrites whatever was already in that slot. |
| More than 2 valid media files | Refused, same as the dialog path: show the existing "최대 2개까지만 선택할 수 있습니다" warning (`tooManyPickedCount`), nothing is assigned. |
| 0 valid media files (all filtered out — non-media files and/or folders) | Ignored. No warning; nothing changes. |

While a drag is in progress over the window, whichever half (A-side panel vs B-side
panel) the cursor currently sits over is highlighted, so the user can see where a
single-file drop will land before releasing. The highlight clears on `dragExit` and
on drop.

## Architecture

### Drop target wiring

In `ImageCompareWindow`'s `Window` content, add a `LaunchedEffect(Unit)` that
attaches a `DropTargetAdapter` recursively, following the exact pattern already
proven in `Main.kt` (accept only `DataFlavor.javaFileListFlavor`, reject everything
else). The main-window and compare-window versions share enough logic (the recursive
attach-to-every-child walk, the accept/reject-on-flavor checks) that this is worth
extracting into a small shared helper — e.g. `fun attachFileDropTarget(window:
java.awt.Window, onFilesDropped: (files: List<File>, xFraction: Float) -> Unit)` —
rather than duplicating the walk and its explanatory comment a second time.

### Resolving a drop into a `ComparePick`

The drop handler computes `xFraction = dropXInWindow / window.width` (converting the
AWT drop location into window-relative coordinates via
`SwingUtilities.convertPoint`, since the recursive attach means the event's reported
component varies) and calls a new pure function alongside the existing
`resolveComparePick`:

```kotlin
/**
 * Same output shape as [resolveComparePick], but a single dropped file's side is
 * decided by where it landed rather than which button was clicked: left half of the
 * window -> A, right half -> B.
 */
fun resolveDroppedFiles(picked: List<File>, xFraction: Float): ComparePick {
    if (picked.size > MAX_COMPARE_SELECTION) return ComparePick(null, null, refusedCount = picked.size)
    val sorted = picked.sortedBy { it.name.lowercase(Locale.US) }
    return when {
        sorted.isEmpty() -> ComparePick(null, null)
        sorted.size == 1 -> if (xFraction < 0.5f) ComparePick(sorted[0], null) else ComparePick(null, sorted[0])
        else -> ComparePick(sorted[0], sorted[1])
    }
}
```

The result feeds into the exact same `onPick: (ComparePick) -> Unit` callback the
dialog path already calls (the one that updates `fileA`/`fileB`/`folderA`/`folderB`
and `tooManyPickedCount`), so no state-update logic is duplicated between the two
entry points.

### Extension filtering

Before calling `resolveDroppedFiles`, the dropped `List<File>` from
`DataFlavor.javaFileListFlavor` is filtered to
`f.isFile && f.extension.lowercase(Locale.US) in ALL_SUPPORTED_MEDIA_EXTENSIONS`.
Folders and non-media files are silently excluded (falling through to the "0 valid
files" / ignored row above when that empties the list).

### Visual highlight

`dragEnter`/`dragOver` compute the same `xFraction` and push it into a small Compose
state (`remember { mutableStateOf<Boolean?>(null) }` — `true`/`false`/`null` for
A-side/B-side/none) via `EventQueue.invokeLater`, matching the
Executor-plus-`EventQueue.invokeLater` threading pattern already used throughout this
file and `QualityCompareWindow.kt`. `dragExit` and `drop` reset it to `null`. The two
side panels (`Column`s in `MediaSelectionBar`) read this state to draw a highlight
border when active.

## Error handling

- **Wrong drag flavor** (not a file list): rejected in `dragEnter`/`dragOver`,
  exactly like `Main.kt`.
- **More than 2 valid files**: refused with the existing too-many warning; no
  partial assignment.
- **Everything filtered out** (folder(s) and/or non-media files only): dropped
  silently — this is the explicit non-goal above, not a bug state, so no warning is
  shown.
- **Mixed valid + invalid**: invalid entries are filtered out first, then the count
  rule applies to what remains (e.g. one media file + one text file → treated as a
  single-file drop, not a 2-file drop and not a rejection).

## Testing

- Unit tests for `resolveDroppedFiles` alongside the existing `resolveComparePick`
  tests in `CompareFileSelectionTest.kt`: left-half single drop → A, right-half
  single drop → B, boundary at `xFraction == 0.5f`, two-file drop → sorted A/B
  (shared behavior with `resolveComparePick`), three-file drop → refused.
- Manual verification: run the app, open the Media Comparison Analyzer, drag one
  file onto each half and two files at once from Finder, since real OS drag
  gestures aren't practically covered by unit tests (same caveat noted for
  `Main.kt`'s existing drag-and-drop).
