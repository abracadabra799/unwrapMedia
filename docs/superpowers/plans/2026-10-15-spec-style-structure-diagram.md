# Spec-Style Structure Diagram Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a spec-grade byte layout diagram mode in `StructureDiagramWindow` inspired by the canonical `BMPfileFormat.svg.webp` reference, supporting dark header banners, a 4-byte ruler grid, dynamic pointer arrows, 2D row/padding layout, and export capabilities.

**Architecture:**
- Separate data modeling (`com.multiviewer.parser.diagram.spec`), layout/rendering (`com.multiviewer.ui.diagram.spec`), and UI presentation (`StructureDiagramWindow.kt`).
- Provide dedicated high-fidelity builder for BMP (`BmpSpecDiagramBuilder`) and an automatic field-packing fallback builder for all other formats (`UniversalSpecDiagramBuilder`).
- Maintain zero new external dependencies (pure Kotlin, Compose Desktop Material3, Java2D, SVG XML).

**Tech Stack:** Kotlin, Compose Desktop Material3, Java2D / BufferedImage, SVG XML builder, JUnit 5.

**Spec Reference:** `docs/superpowers/specs/2026-10-15-spec-style-structure-diagram-design.md`.

---

## Review Focus

1. **Byte-accuracy**: Every cell width, offset, and row padding MUST match the actual parsed file byte ranges. Never guess or hallucinate offsets.
2. **Deterministic Layout**: The same `SpecDiagram` model must produce identical layouts in both SVG and PNG renderers.
3. **Robustness against corrupted/truncated inputs**: If a BMP file is truncated or has invalid offsets, the diagram must show warning states rather than crashing with an `IndexOutOfBoundsException`.
4. **Platform & Clipboard safety**: Java2D image generation and clipboard copy must run off the UI thread and handle busy clipboard exceptions gracefully.

---

### Task 1: Spec Diagram Data Model & BMP Spec Diagram Builder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/diagram/spec/SpecDiagramModel.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/diagram/spec/BmpSpecDiagramBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/diagram/spec/BmpSpecDiagramBuilderTest.kt`

**Details:**
1. In `SpecDiagramModel.kt`, define:
   - `SpecDiagram(title, fileSize, blocks, arrows, brackets, callouts, columnBytes)`
   - `SpecBlock(id, title, subtitle, offset, length, role, rows)`
   - `SpecRow(cells, rowOffset)`
   - `SpecCell(id, name, value, byteLength, colSpan, role, status, isPadding)`
   - `SpecArrow(fromCellId, toBlockId, color, label)`
   - `SpecBracket(fromOffset, toOffset, label, color)`
   - `SpecCallout(targetCellOrBlockId, text)`
2. In `BmpSpecDiagramBuilder.kt`, define `buildBmpSpecDiagram(root: BoxNode, fileSize: Long): SpecDiagram`:
   - Parse `BITMAPFILEHEADER`:
     - Row 0: `bfType` (2B, colSpan 2) + `bfSize` (first 2B of 4B, or spanned).
     - `bfSize` bracket spanning file size `0..fileSize`.
     - `bfReserved1` (2B) + `bfReserved2` (2B).
     - `bfOffBits` (4B) + `SpecArrow` pointing to `PixelArray` block.
   - Parse DIB Header (`BITMAPINFOHEADER` 40B, `BITMAPV4HEADER` 108B, `BITMAPV5HEADER` 124B):
     - Align all fields to 4-byte rows according to Microsoft Win32 DIB specification.
     - Bracket spanning `biSize` header range.
   - Parse Color Table (if present).
   - Parse `PixelArray[x, y]`:
     - Calculate stride: `rowBytes = ((width * bitCount + 31) / 32) * 4`.
     - Calculate padding: `paddingBytes = rowBytes - (width * bitCount + 7) / 8`.
     - Output bottom-up rows (`Row 0`, `Row 1` ... `Row h-1`) with `Pixel[...]` and `Padding` cell.
     - If `paddingBytes > 0`, attach callout: *"Pad row size to a multiple of 4 Bytes"*.
3. Tests in `BmpSpecDiagramBuilderTest.kt`:
   - Test standard 24-bit BMP with padding (`width=3, height=2` -> 12 bytes/row, 3 bytes padding).
   - Test unpadded BMP (`width=4, height=2` -> 12 bytes/row, 0 bytes padding).
   - Test BMP with Color Table (8-bit paletted).
   - Verify `bfOffBits` arrow correctly targets `PixelArray`.

- [ ] Create `SpecDiagramModel.kt` with all data classes.
- [ ] Create `BmpSpecDiagramBuilder.kt` implementation.
- [ ] Write `BmpSpecDiagramBuilderTest.kt` verifying cell counts, colSpans, arrows, and padding.
- [ ] Run `./gradlew :app:test --tests "com.multiviewer.parser.diagram.spec.BmpSpecDiagramBuilderTest"` and verify GREEN.

---

### Task 2: Spec Diagram Layout Engine & SVG/PNG Export

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/diagram/spec/SpecDiagramLayout.kt`
- Create: `app/src/main/kotlin/com/multiviewer/ui/diagram/spec/SpecDiagramExport.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/diagram/spec/SpecDiagramExportTest.kt`

**Details:**
1. In `SpecDiagramLayout.kt`:
   - `layoutSpecDiagram(spec: SpecDiagram): SpecLayout`
   - Calculate coordinates:
     - Top title + Ruler row (`Byte offset: 0   1   2   3`).
     - Dark section header banner for each `SpecBlock`.
     - Grid cells with pastel background according to `role`, solid borders, and text labels (name + value).
     - Right/left brackets for file size and header sizes.
     - Bezier/polyline arrows with arrowheads connecting `fromCell` to `toBlock`.
     - Callout note boxes with dotted pointer lines.
2. In `SpecDiagramExport.kt`:
   - `renderSpecSvg(layout: SpecLayout): String`:
     - Produces clean, standards-compliant SVG with `<rect>`, `<path>`, `<marker id="arrow">`, `<text>`.
   - `renderSpecPng(layout: SpecLayout): BufferedImage`:
     - Draws identical shapes, antialiased text, and fills using Java2D.
3. Tests in `SpecDiagramExportTest.kt`:
   - Assert SVG string is well-formed XML and contains expected labels (`BITMAPFILEHEADER`, `PixelArray`, ruler offsets).
   - Assert PNG `BufferedImage` has non-zero width/height and correct background.

- [ ] Create `SpecDiagramLayout.kt`.
- [ ] Create `SpecDiagramExport.kt`.
- [ ] Write `SpecDiagramExportTest.kt`.
- [ ] Run `./gradlew :app:test --tests "com.multiviewer.ui.diagram.spec.SpecDiagramExportTest"` and verify GREEN.

---

### Task 3: Universal Fallback Builder for All Media Formats

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/diagram/spec/UniversalSpecDiagramBuilder.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/diagram/spec/SpecDiagramDispatcher.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/diagram/spec/UniversalSpecDiagramBuilderTest.kt`

**Details:**
1. In `UniversalSpecDiagramBuilder.kt`:
   - `buildUniversalSpecDiagram(root: BoxNode, fileSize: Long, title: String): SpecDiagram`
   - Iterate top-level `BoxNode` children:
     - Each becomes a `SpecBlock` with dark banner.
     - Pack `child.fields` into 4-byte aligned rows (1B, 2B, 4B, 8B -> 2 rows, etc.).
     - If child has data payload without detailed fields (e.g. `mdat`, `IDAT`, `SOS`), render a data payload cell pattern.
2. In `SpecDiagramDispatcher.kt`:
   - `buildSpecDiagram(root: BoxNode, fileSize: Long, fileName: String): SpecDiagram`:
     - If `fileName.endsWith(".bmp", ignoreCase = true)` or `root.children.any { it.type == "BITMAPFILEHEADER" }` -> invoke `buildBmpSpecDiagram`.
     - Otherwise -> invoke `buildUniversalSpecDiagram`.
3. Tests in `UniversalSpecDiagramBuilderTest.kt`:
   - Test synthetic MP4 tree (`ftyp` + `moov` with fields + `mdat`).
   - Verify non-BMP files produce well-formed `SpecDiagram` without errors.

- [ ] Create `UniversalSpecDiagramBuilder.kt` and `SpecDiagramDispatcher.kt`.
- [ ] Write `UniversalSpecDiagramBuilderTest.kt`.
- [ ] Run `./gradlew :app:test --tests "com.multiviewer.parser.diagram.spec.*"` and verify GREEN.

---

### Task 4: UI Integration in StructureDiagramWindow

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/StructureDiagramWindow.kt`

**Details:**
1. Add state variable `diagramMode`:
   - Enum or tab option: `OVERVIEW (0)`, `DETAIL (1)`, `DEEP (2)`, `SPEC`, `TABLE`.
2. Add toolbar button `[규격도 | Spec Diagram]` styled consistently with existing mode buttons.
3. When `diagramMode == SPEC`:
   - Compute `SpecDiagram` and `SpecLayout` asynchronously via `buildSpecDiagram`.
   - Render `SpecImage` using `renderSpecPng` with existing interactive Pan & Zoom HUD.
   - Connect click gesture on cells to select the corresponding node/field in `tab.selected`.
4. Update `이미지 복사`, `SVG 저장`, `PNG 저장` buttons to export the active mode's layout (`SpecDiagram` when in Spec mode).
5. Ensure Korean/English bilingual localization for all new strings (`label("규격도", "Spec Diagram")` etc.).

- [ ] Update `StructureDiagramWindow.kt` to integrate the Spec Diagram mode.
- [ ] Run `./gradlew :app:compileKotlin` and full UI tests `./gradlew :app:test --tests "com.multiviewer.ui.*"`.
- [ ] Manual test: Run `./gradlew :app:run`, open a BMP file, open File Structure Diagram, click `[규격도]`, and inspect visual fidelity against `BMPfileFormat.svg.webp`.
