# Implementation Plan: Developer-Grade Hierarchical Structure Diagram

**Goal**: Deliver a developer-grade multi-column hierarchical file structure diagram (inspired by `Downloads/fig2d.jpg`), enhance the existing 4-byte grid Spec Diagram (with universal brackets, Gain Map/Motion Photo decomposition, and arrow fixes across all formats), and provide frictionless high-resolution image downloads.

**Branch**: Feature branch `feat/hierarchical-structure-diagram` (worktree: `.worktrees/hierarchical-structure-diagram`).

---

## Task 1: Spec Diagram Layout Engine & Universal Bracket/Arrow Fixes

**Goal**: Fix `SpecDiagramLayoutEngine.kt` to dynamically calculate bracket ranges from arbitrary byte offsets across all file formats, prevent right-bracket collisions with tiered X-offsets, and ensure arrow routing succeeds without dropped endpoints.

### Tests: `app/src/test/kotlin/com/multiviewer/ui/diagram/spec/SpecDiagramLayoutUniversalTest.kt`
- Test that brackets with arbitrary byte ranges (e.g. `fromOffset = 1000L, toOffset = 5000L`) locate matching blocks and assign correct `y1` and `y2`.
- Test that multiple brackets on the right side receive incremented X coordinates to prevent visual overlapping.
- Test that arrows route correctly when fromCell and toBlock are present.

### Implementation:
- Edit `app/src/main/kotlin/com/multiviewer/ui/diagram/spec/SpecDiagramLayout.kt`:
  - Replace hardcoded `val dibBlock = layoutBlocks.find { it.id == "block_dib_header" }` with dynamic block range intersection.
  - Implement staggered X-coordinates for right-side brackets: `SPEC_GRID_LEFT + SPEC_GRID_WIDTH + 20 + (rightBracketIndex * 26)`.

---

## Task 2: Spec Diagram Builders Enhancement for Gain Map, Motion Photo & All Formats

**Goal**: Enhance `JpegSpecDiagramBuilder.kt`, `Mp4SpecDiagramBuilder.kt`, and `HeicSpecDiagramBuilder.kt` to decompose secondary Gain Map images and embedded MP4 box trees (`ftyp`, `moov`, `mdat`), and connect them with arrows and brackets.

### Tests: `app/src/test/kotlin/com/multiviewer/parser/diagram/spec/EnhancedSpecDiagramTest.kt`
- Test JPEG with Gain Map: verify `block_gainmap` or Gain Map header, bracket `Gain Map Image`, and arrow from primary APP2.
- Test Samsung SEF: verify `block_mp4_ftyp`, `block_mp4_moov`, `block_mp4_mdat` are created under `sefd`, with purple bracket and arrow from `MotionPhoto_Data`.
- Test Google Motion Photo: verify MP4 boxes appended after EOI are parsed into MP4 blocks.
- Test MP4 builder: verify multi-track bracket and track hierarchy.

### Implementation:
- Edit `app/src/main/kotlin/com/multiviewer/parser/diagram/spec/JpegSpecDiagramBuilder.kt`:
  - Detect secondary JPEG (`SOI...EOI`) and generate Gain Map block, bracket, and arrow.
  - Decompose `MotionPhoto_Data` children into `block_mp4_ftyp`, `block_mp4_moov`, `block_mp4_mdat`.
  - Fix arrow targeting from `MotionPhoto_Data` to `block_mp4_ftyp`.
- Edit `app/src/main/kotlin/com/multiviewer/parser/diagram/spec/Mp4SpecDiagramBuilder.kt` and `HeicSpecDiagramBuilder.kt`.

---

## Task 3: Hierarchical Multi-Column Structure Diagram Model & Builders (`fig2d.jpg` style)

**Goal**: Create the data model and builders for the canonical multi-column expanded specification structure diagram (`HierarchicalDiagram`).

### Files:
- Create `app/src/main/kotlin/com/multiviewer/parser/diagram/hierarchical/HierarchicalDiagramModel.kt`:
  - `data class HierarchicalDiagram(title, fileSize, columns, projections, notes)`
  - `data class HierarchicalColumn(id, title, sections)`
  - `data class HierarchicalSection(id, marker, name, description, offset, length, role, status, children)`
  - `data class HierarchicalProjection(fromSectionId, toColumnId, color, label)`
- Create `app/src/main/kotlin/com/multiviewer/parser/diagram/hierarchical/HierarchicalDiagramBuilder.kt`:
  - Dispatcher for all formats: JPEG, HEIC, MP4, PNG, WebP, TIFF, BMP.
  - For JPEG: Col 1 (`Structure of Compressed File`), Col 2 (`Structure of APP1 / Gain Map / SEF`), Col 3 (`Structure of Embedded MP4`).
  - For HEIC: Col 1 (`Structure of HEIF File`), Col 2 (`Structure of meta`), Col 3 (`Structure of iprp / items`).
  - For MP4: Col 1 (`Structure of ISO Media File`), Col 2 (`Structure of moov`), Col 3 (`Structure of trak`).
  - For PNG: Col 1 (`Structure of PNG File`), Col 2 (`Structure of IHDR & Metadata Chunks`).
  - For WebP / TIFF / BMP: Corresponding canonical multi-column decomposition.

### Tests: `app/src/test/kotlin/com/multiviewer/parser/diagram/hierarchical/HierarchicalDiagramBuilderTest.kt`
- Test multi-column generation for JPEG with SEF Motion Photo.
- Test multi-column generation for MP4 and HEIC.
- Test projections connecting container sections to expanded sub-columns.

---

## Task 4: Hierarchical Diagram Layout Engine & Exporter (SVG & High-Res PNG)

**Goal**: Compute multi-column geometric layouts with angled projection trapezes and render exportable vector SVG and 2x high-resolution PNG.

### Files:
- Create `app/src/main/kotlin/com/multiviewer/ui/diagram/hierarchical/HierarchicalLayoutEngine.kt`:
  - Compute column X offsets, table headers, row heights, and trapezoidal projection polylines between parent sections and child columns.
- Create `app/src/main/kotlin/com/multiviewer/ui/diagram/hierarchical/HierarchicalDiagramExporter.kt`:
  - `renderSvg(layout: HierarchicalLayout): String`
  - `renderPng(layout: HierarchicalLayout, scale: Float = 2.0f): BufferedImage`

### Tests: `app/src/test/kotlin/com/multiviewer/ui/diagram/hierarchical/HierarchicalExportTest.kt`
- Verify SVG contains table headers, section titles, projection paths, and valid XML.
- Verify 2x PNG rendering with proper dimensions and crisp anti-aliasing.

---

## Task 5: Modernize Export & Download Experience in `StructureDiagramWindow`

**Goal**: Integrate the new "계층 구조도 (Hierarchical)" view tab into `StructureDiagramWindow.kt`, fix file save mode, and add a one-click "📥 빠른 다운로드" button saving directly to `~/Downloads/`.

### Implementation:
- Edit `app/src/main/kotlin/com/multiviewer/ui/StructureDiagramWindow.kt`:
  - Add tab button: `[계층 구조도 (Hierarchical)]` alongside `[규격도]` and `[개요/상세/세부]`.
  - Fix file saving: replace `StandardOpenOption.CREATE_NEW` with `StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE`.
  - Add "📥 빠른 다운로드" button: saves `${tab.file.nameWithoutExtension}-structure.png` directly to `~/Downloads/` and shows a brief toast message.
  - Implement canvas renderer for `HierarchicalDiagramImage` with interactive drag, zoom, and row-click selection (`tab.selected = node`).

---

## Task 6: Add `[📊 파일 구조도]` Button in `MotionPhotoInspectorWindow` Toolbar

**Goal**: Allow developers to jump directly from the Motion Photo Inspector to the File Structure Diagram.

### Implementation:
- Edit `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoInspectorWindow.kt`:
  - Add an AssistChip `[📊 파일 구조도]` in the top toolbar row.
  - Clicking sets `tab.isStructureDiagramOpen = true` (or triggers callback).

---

## Task 7: Full Test Suite & UI Verification

- Run `./gradlew :app:test` to confirm 100% green tests.
- Run `./gradlew :app:run` and verify rendering with `fig2d.jpg`, `20260718_200439_motion.jpg`, `20261012_131852_s26.heic`, and MP4 files.
- Merge feature worktree into `main`.
