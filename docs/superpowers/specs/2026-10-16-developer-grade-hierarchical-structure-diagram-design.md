# Developer-Grade Hierarchical Structure Diagram Design

**Date**: 2026-10-16  
**Status**: In Review  
**Target Feature**: File Structure Diagram (`Analyze → 파일 구조도`) across all formats, inspired by canonical multi-column specification diagrams (e.g. `Downloads/fig2d.jpg` — JEITA CP-3451 / ISO specification architecture).

---

## 1. Background & User Needs

unwrapMedia's current "파일 구조도 (File Structure Diagram)" window (`StructureDiagramWindow.kt`) provides two visualizations:
1. **Linear Block Diagram** (`개요 / 상세 / 세부`): A not-to-scale horizontal ribbon mapping parsed byte ranges.
2. **Spec Diagram (`규격도`)**: A 4-byte (32-bit) aligned grid inspired by BMP canonical specs.

### Limitations in Existing Implementation:
- **Missing Developer-Grade Hierarchical Breakdown (`fig2d.jpg` style)**: Industry-standard specification documents (JEITA Exif, ISO/IEC ISOBMFF, ITU-T JPEG) represent file architectures as a multi-column expanded layout:
  - **Column 1**: Main Container / Compressed File Structure (`Structure of Compressed file`).
  - **Projection Lines (Zoom Trapeze)**: Angled connector lines projecting from the container segment (e.g. `APP1`, `moov`, `meta`, `SEF`) to its expanded internal structure.
  - **Column 2 & 3**: Expanded Internal Substructure (`Structure of APP1`, `Structure of moov`, `Structure of SEF Trailer`, `Structure of Motion Photo MP4`).
- **Spec Diagram Limitations**:
  - `SpecDiagramLayoutEngine.kt` hardcoded the right-hand bracket lookup to BMP's `block_dib_header`, causing all brackets for JPEG, HEIC, MP4, and other formats to be omitted.
  - Arrow targeting in `JpegSpecDiagramBuilder.kt` pointed to a non-existent `block_motion_photo`, leaving arrows unrendered.
  - Secondary JPEG (Gain Map / Ultra HDR) was not separated or bracketed.
  - MP4 boxes inside Samsung SEF (`MotionPhoto_Data`) or at the end of Google Motion Photos were collapsed into flat summary rows rather than decomposed into box hierarchies (`ftyp`, `moov`, `mdat`).
- **Export & Download Friction**:
  - `save("png")` and `save("svg")` used `StandardOpenOption.CREATE_NEW`, which throws a `FileAlreadyExistsException` if the user confirms overwriting a file in the native file picker.
  - There is no single-click "Save to Downloads" button for fast forensic reporting.
  - Diagrams were exported at 1x scale, lacking Retina / high-DPI crispness.

---

## 2. Architecture & Key Features

### 2.1 Multi-Column Hierarchical Structure Diagram (`계층 구조도` / `Hierarchical Diagram`)
Inspired by `Downloads/fig2d.jpg`, this new primary view provides an engineer-grade structural projection:

```
+-----------------------------------+         +-----------------------------------+
|   Structure of Compressed File   |         |         Structure of APP1         |
+---------+-------------------------+         +-----------------------------------+
| SOI     | Start of Image (0xFFD8) |         | APP1 Marker (0xFFE1)              |
+---------+-------------------------+         +-----------------------------------+
| APP1    | Exif Attribute Info     |----\    | APP1 Length (49,143 B)            |
|         | (49,143 B)              |     \   +-----------------------------------+
+---------+-------------------------+      \  | Exif Identifier Code ("Exif\0\0") |
| APP1    | XMP Metadata (1,271 B)  |       \ +-----------------------------------+
+---------+-------------------------+        >| TIFF Header (8 B, Little-Endian)  |
| APP2    | ICC Profile (650 B)     |       / +-----------------------------------+
+---------+-------------------------+      /  | 0th IFD (13 entries)              |
| APP2    | ISO 21496-1 Gain Map    |     /   +-----------------------------------+
+---------+-------------------------+----/    | 0th IFD Values / Exif SubIFD      |
| DQT     | Quantization Table      |         +-----------------------------------+
+---------+-------------------------+         | 1st IFD (Thumbnail Attributes)    |
| SOF0    | Frame Header (4000x2252)|         +-----------------------------------+
+---------+-------------------------+         | 1st IFD Image Data (48,099 B)     |
| SOS     | Scan Data (1.72 MB)     |         +-----------------------------------+
+---------+-------------------------+
| EOI     | End of Image (0xFFD9)   |
+---------+-------------------------+
| [SEF]   | Samsung Trailer         |----+    +-----------------------------------+
|         | (3.96 MB)               |     \   |        Structure of SEF           |
+---------+-------------------------+      \  +-----------------------------------+
                                            \ | Image_UTC_Data (35 B)             |
                                             \| MCC_Data (19 B)                   |
                                              +-----------------------------------+
                                              | MotionPhoto_Data (3.96 MB)   |----\
                                              +-----------------------------------+ \
                                              | SEF Trailer Magic (SEFT)          |  \
                                              +-----------------------------------+   \
                                                                                       +-----------------------------+
                                                                                       | Structure of Embedded MP4   |
                                                                                       +-----------------------------+
                                                                                       | ftyp (isom, 28 B)           |
                                                                                       +-----------------------------+
                                                                                       | moov (1,572 B)              |
                                                                                       |  - mvhd (3.13s)             |
                                                                                       |  - trak (1752x984 hvc1)     |
                                                                                       +-----------------------------+
                                                                                       | mdat (3,959,296 B video)    |
                                                                                       +-----------------------------+
```

#### Multi-Format Hierarchical Generators:
- **JPEG**:
  - Col 1: `SOI`, `APP1 (Exif)`, `APP1 (XMP)`, `APP2 (ICC/GainMap)`, `DQT`, `SOF0`, `DHT`, `SOS`, `EOI`, `[Gain Map JPEG]`, `[SEF Trailer / Google MP4]`.
  - Col 2: Expanded `APP1 (Exif)` / `APP1 (XMP)` / `Gain Map Secondary Image` / `SEF Trailer`.
  - Col 3: Expanded `Motion Photo MP4` (`ftyp`, `moov`, `mdat`).
- **HEIC / AVIF**:
  - Col 1: `ftyp`, `meta`, `mdat`, `[SEF / MP4 trailer]`.
  - Col 2: Expanded `meta` (`hdlr`, `pitm`, `iloc`, `iinf`, `iprp`).
  - Col 3: Expanded `iprp` (`ipco`, `ispe`, `hvcC`/`av1C`, `auxC`) and item extents.
- **MP4 / MOV**:
  - Col 1: Top-level boxes (`ftyp`, `moov`, `mdat`, `free`).
  - Col 2: Expanded `moov` (`mvhd`, `trak [video]`, `trak [audio]`, `udta`).
  - Col 3: Expanded `trak` (`tkhd`, `mdia` → `mdhd`, `hdlr`, `minf` → `stbl` → `stsd`, `stts`, `stsc`, `stsz`, `stco`).
- **PNG**:
  - Col 1: Signature, `IHDR`, critical & ancillary chunks (`PLTE`, `cHRM`, `gAMA`, `sRGB`, `iCCP`, `eXIf`, `IDAT`, `IEND`).
  - Col 2: Expanded `IHDR` (Width, Height, Bit Depth, Color Type, Compression, Filter, Interlace) and Metadata.
- **WebP**:
  - Col 1: `RIFF`, `WEBP`, `VP8X`, `EXIF`, `ICCP`, `ANIM`, `ANMF`, `VP8`/`VP8L`.
  - Col 2: Expanded `VP8X` flags and Animation / Frame chunk decomposition.
- **TIFF / BMP**:
  - Col 1: File Header, Primary IFD / DIB Header, Pixel Data.
  - Col 2: Expanded IFD Entries (Tags, Types, Counts, Offsets, Inline Values).

### 2.2 Spec Diagram (`규격도`) Fixes & Enhancements
- **Dynamic Offset Bracket Engine (`SpecDiagramLayoutEngine.kt`)**:
  - Remove hardcoded `block_dib_header` lookup.
  - Look up blocks intersecting `[fromOffset..toOffset]`.
  - Calculate `y1` from `startBlock.headerY` and `y2` from `endBlock.bottomY`.
  - Stagger multiple right brackets (`SPEC_GRID_LEFT + SPEC_GRID_WIDTH + 20 + bracketIndex * 26`) to avoid overlapping text and lines.
- **Gain Map & Motion Photo Blocks**:
  - Secondary JPEG recognized and bracketed as `Gain Map (Ultra HDR Secondary Image)`.
  - Primary APP2 ISO version block connected via arrow to the Gain Map Secondary Image block.
  - `MotionPhoto_Data` decomposed into `block_mp4_ftyp`, `block_mp4_moov`, and `block_mp4_mdat`.
  - Arrow from `MotionPhoto_Data` (or Primary XMP) to `block_mp4_ftyp`.
- **Support for All Formats**:
  - Add MP4 and HEIC multi-bracket and cross-referencing arrows (e.g. `iloc` extent pointers to `mdat`).

### 2.3 Download & Export Modernization
- **High-Resolution Export**:
  - Provide 1x and 2x (Retina) PNG rendering for crisp documentation insertion.
  - Render full vector SVG with clean projection trapezes and styling.
- **Safe Native File Saving**:
  - Replace `StandardOpenOption.CREATE_NEW` with `StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE`.
- **"📥 Downloads 폴더로 빠른 저장" (Quick Save)**:
  - Add a dedicated Quick Download button that directly outputs `${tab.file.nameWithoutExtension}-structure.png` (or `.svg`) to `~/Downloads/`.
  - Show a non-intrusive status toast confirming the exact path.
- **Support All Visualizations**:
  - "계층 구조도", "규격도", "개요/상세/세부", "표(Markdown)" all support instant Copy and Download.

---

## 3. UI Changes & Interactions

1. **Toolbar Buttons in `StructureDiagramWindow`**:
   - `[계층 구조도 (Hierarchical)]` (Default for rich multi-segmented files)
   - `[규격도 (Spec Diagram)]`
   - `[개요 (Overview)]` / `[상세 (Detail)]` / `[세부 (Deep)]`
   - `[표 (Table)]`
   - Divider
   - `[📋 이미지 복사]`
   - `[📥 빠른 다운로드 (Downloads)]`
   - `[💾 PNG 저장]` / `[💾 SVG 저장]`
2. **Interactive Selection**:
   - Clicking any box or entry in Column 1, 2, or 3 highlights the item and updates `tab.selected = node`, immediately focusing the node in the main window's container tree and hex viewer.
3. **Motion Photo Inspector Integration**:
   - Add a `[📊 파일 구조도]` AssistChip button to `MotionPhotoInspectorWindow` header toolbar for seamless one-click inspection.

---

## 4. Testing Strategy (TDD)
- **Unit Tests**:
  - `HierarchicalDiagramBuilderTest`: Verify multi-column generation, projection ranges, and box decomposition for JPEG, HEIC, MP4, PNG, WebP, BMP, TIFF.
  - `SpecDiagramLayoutEngineTest`: Verify dynamic offset matching, tiered right brackets, and arrow endpoint routing.
  - `DiagramExportTest`: Verify PNG and SVG rendering at 1x and 2x resolution, ensuring valid XML and graphics metrics.
- **Regression Tests**:
  - Ensure all existing tests in `MultiFormatSpecDiagramTest`, `UniversalSpecDiagramBuilderTest`, and `StructureDiagramBuilderTest` continue to pass.
