# FastStone Benchmark: Media Compare & Explorer Enhancement Plan

> **Goal:** Enhance both the Folder Explorer and the Media Compare Analyzer (`ImageCompareWindow`) by benchmarking FastStone Image Viewer's core strengths, while preserving the existing visual viewers (Wiper, Side-by-Side, Heatmap) and comparison tabs (Structure, Metadata, Visual, Hex).

---

## Architecture & Scope

1. **Folder Explorer (FastStone Browser Workflow):**
   - **Multi-Selection Mode:** Checkbox selection or Shift/Ctrl multi-select of 2~4 files in the current folder.
   - **Compare Action Bar:** Floating/docked bottom bar when files are selected: `[ ⚖️ 미디어 비교분석기로 비교 (N개) ]`.
   - **View Mode Toggle:** List View ↔ Thumbnail Grid View (with async cached thumbnail generation for image/video).

2. **Media Compare Analyzer (`ImageCompareWindow`):**
   - **FastStone Zoom/Pan Presets & OSD (HUD):**
     - Quick preset buttons: `Fit to Screen`, `100% (1:1)`, `200%`, `400%`.
     - Hold-to-Zoom (mouse press temporary 100% zoom).
     - HUD / OSD Overlay: Semi-transparent tags showing resolution, file size, codec/format, and camera EXIF.
   - **Blink / Flicker Comparison View Mode:**
     - A new mode in `VisualDiffView` alongside `Split Wiper`, `Side-by-Side`, and `Diff Heatmap`.
     - Rapidly alternates between File A and B at customizable intervals (100ms~1000ms) or on Spacebar tap.
   - **Bottom Filmstrip Quick Switch:**
     - Horizontal thumbnail strip of siblings in the current folder for quick file switching without closing the comparison window.
   - **Multi-Slot Comparison (2-Up / 3-Up / 4-Up):**
     - Expand from 2 slots (A, B) to up to 4 slots (A, B, C, D) with synchronized Pan & Zoom across all panes.

---

## Implementation Tasks

### Task 1: FastStone Zoom Presets, HUD Overlay, and Blink Comparison in `ImageCompareWindow`
- Add Zoom presets (`Fit`, `100%`, `200%`, `400%`) to `SideBySideCompareView`.
- Add OSD overlay (Resolution, Format, Size) to each viewer pane.
- Add `VisualCompareMode.BLINK` and `BlinkCompareView` composable with speed slider and Spacebar toggle.
- Verify compilation and unit tests.

### Task 2: Folder Explorer Multi-Selection & Direct Compare Launch
- In `AppState`, add support for opening `ImageCompareWindow` with arbitrary file list (e.g. `compareTargetFiles: List<File>`).
- In `FolderExplorerView`, add selection state (`selectedFiles: Set<File>`), checkbox toggles, and "Compare Selected" action bar.
- Clicking "Compare" passes the selected files directly into `ImageCompareWindow`.
- In `Main.kt`, wire `appState.compareTargetFiles` to `ImageCompareWindow`.

### Task 3: Bottom Filmstrip in `ImageCompareWindow`
- Render a collapsible horizontal filmstrip of the folder's media files at the bottom of `ImageCompareWindow`.
- Clicking a filmstrip thumbnail allows replacing Slot A, Slot B (or C/D).

### Task 4: 4-Up Multi-Slot Comparison Grid
- Extend data model to support up to 4 files (`files: List<File?>`, `infos: List<CompareMediaInfo?>`).
- Add layout toggle (`1x2`, `2x1`, `2x2` grid).
- Synchronize zoom/pan across all active panes.

### Task 5: Thumbnail Grid View in `FolderExplorerView`
- Add List ↔ Grid view mode switch in `FolderExplorerView`.
- Render media cards with async thumbnail loading.

---

## Verification
- `./gradlew compileKotlin` & `./gradlew test`
- Manual GUI verification of Zoom presets, Blink mode, Multi-selection in explorer, and 4-Up grid.
