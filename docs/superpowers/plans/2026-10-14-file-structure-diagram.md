# File Structure Diagram Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce an accurate, shareable diagram and table of the opened file's physical layout and semantic media relationships.

**Architecture:** Build a bounded `StructureDiagram` model from the parsed `BoxNode` tree and verified `EmbeddedVideo` range; separate model building, rendering, and UI. Render SVG and PNG from one deterministic layout plan, and copy the PNG image to the OS clipboard. Use the existing tree selection for evidence navigation.

**Tech Stack:** Kotlin, Compose Desktop Material3, Java2D/ImageIO/clipboard, existing parsers. No new heavy dependency.

**Spec:** `docs/superpowers/specs/2026-10-14-contextual-media-analysis-design.md`, section 2.

## Global Constraints

- The diagram describes the actual parsed file, not a generic format illustration.
- Every offset and length comes from parsed byte ranges; ambiguous and unparsed spans are labeled.
- Samsung internal rules are deferred. Public Samsung SEF structures already verified by the parser may be shown.
- Never include absolute local paths, GPS, serial numbers, or raw media payload in the default shared output.
- User-visible strings are Korean/English, and Windows clipboard/paste must work.
- Export and rendering happen off the UI thread and can be cancelled on close.

## Review Focus

1. Overlapping or out-of-order top-level children: show a warning rather than a false contiguous map (Task 1).
2. A large `mdat` next to tiny XMP/SEF fields: all blocks remain readable because widths are labeled, not byte-proportional (Task 2).
3. SVG/XML metacharacters in a parsed label: output remains valid XML (Task 2).
4. Clipboard image paste on Windows and macOS, including a busy clipboard: report copy failure without UI freeze (Task 3).
5. A motion photo lacking a verified embedded video range: do not draw an invented video link (Task 1).

---

### Task 1: Byte-range and relationship model

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/diagram/StructureDiagram.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/diagram/StructureDiagramBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/diagram/StructureDiagramBuilderTest.kt`

**Interfaces:**
- `buildStructureDiagram(root: BoxNode, fileSize: Long, embeddedVideo: EmbeddedVideo?): StructureDiagram`
- Model contains ordered physical `DiagramRegion(id, label, offset, length, role, status)`, typed `DiagramLink(fromId, toId, meaning, evidence)`, and `unparsedRanges`/warnings.
- Top-level ranges derive from `root.children`; embedded video is a semantic overlay/reference, not a duplicate physical allocation.
- `findEmbeddedVideo` from the existing parser is used only after a valid range check.

- [ ] Write a failing test against the real `docs/showcase-media/images/campfire-motion-photo.jpg` fixture: assert file size `1,041,843` bytes, independently located `ftyp` at byte `237,772` (MP4 box starts at `237,768`), no invented ranges, and an embedded video link only if parser-verified.
- [ ] Write synthetic overlap/gap/unknown tests, with literal offsets and lengths independent of the implementation.
- [ ] Run focused tests, verify RED, then implement the minimal builder and verify GREEN.
- [ ] Commit `feat: model actual file structure diagrams`.

### Task 2: Deterministic SVG/PNG and table export

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/StructureDiagramLayout.kt`
- Create: `app/src/main/kotlin/com/multiviewer/ui/StructureDiagramExport.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/StructureDiagramExportTest.kt`

**Interfaces:**
- `layoutStructureDiagram(diagram: StructureDiagram, depth: Int): DiagramLayout` returns text, shapes and arrows once for both renderers.
- `renderStructureSvg(layout: DiagramLayout): String` escapes all parsed text and marks unverified regions.
- `renderStructurePng(layout: DiagramLayout): BufferedImage` uses Java2D with the same positions.
- `structureDiagramMarkdownTable(diagram: StructureDiagram): String` includes role, decimal/hex offset, byte length, status.

- [ ] Write failing tests for XML escaping, exact range labels in SVG/table, nonempty PNG pixels, and sensible bounded canvas size with 1000 nested/sample nodes.
- [ ] Run focused tests, verify RED, implement exporters, verify GREEN.
- [ ] Commit `feat: export structure diagrams as svg png and table`.

### Task 3: Structure diagram window and clipboard

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/StructureDiagramWindow.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/util/ClipboardUtil.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`
- Test: `app/src/test/kotlin/com/multiviewer/util/ClipboardImageTransferableTest.kt`

**Interfaces:**
- A menu action opens the diagram for the active tab and keeps it pinned to that tab.
- Overview/detail depth and table toggle use the same model. A row/block click selects its underlying `BoxNode` through existing selection state.
- Buttons save SVG/PNG and copy PNG image or Markdown table; save dialogs reject overwrite or explicitly confirm according to existing app convention.
- `ClipboardUtil.copyImage(BufferedImage): Boolean` uses `DataFlavor.imageFlavor`.

- [ ] Write a failing clipboard transferable test and run it RED; implement it and run GREEN.
- [ ] Add the Compose UI and menu action, compile, inspect a real motion photo and an MP4; verify the copied image pastes into a slide/doc app on available OSes.
- [ ] Commit `feat: add shareable file structure diagram view`.

### Task 4: Whole-feature review

- [ ] Run `./gradlew :app:test`, checking the known intermittent ThumbnailDiskCacheTest separately if needed.
- [ ] Review generated SVG/PNG/table against independently dumped byte ranges from the real fixture.
- [ ] Review default output for path/GPS/serial leakage and report any remaining platform-specific manual checks.
