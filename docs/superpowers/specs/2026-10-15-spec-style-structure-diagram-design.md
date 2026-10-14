# Spec-Style Structure Diagram (BMP & Multi-Format) — Design

**Date:** 2026-10-15  
**Status:** In Review (Brainstorming approved, pending design review)

---

## 1. Goal

Enhance unwrapMedia's File Structure Diagram window (`StructureDiagramWindow`) to provide a **spec-grade byte layout diagram** inspired by canonical format architecture diagrams (such as the standard `BMPfileFormat.svg.webp`). 

Rather than displaying boxes solely as simple, single-column rows, this view renders the **actual opened file's parsed structure** with:
1. **Dark section header banners** (e.g., `BITMAPFILEHEADER`, `BITMAPINFOHEADER` / `DIB Header`, `PixelArray[x,y]`, `Color Table`).
2. **Byte-offset aligned grid** (4-column 32-bit ruler: Byte 0, 1, 2, 3) with fields subdivided according to their actual byte lengths (1B, 2B, 4B, 36B, etc.).
3. **Offset & size reference arrows and brackets** (e.g., pointer from `bfOffBits` directly to the `PixelArray` start, bracket from `bfSize` enclosing the whole file, bracket for `biSize`).
4. **Data payload & row padding visualization** (e.g., BMP 2D row layout `Pixel[0,0] ... Pixel[w-1,0]` with explicit 4-byte multiple `Padding` representation).
5. **Distinct pastel color coding** by semantic role (Green for file header, Blue for DIB/metadata, Purple for palette/color table, Yellow for image pixel array, Grey/Hatched for gaps and padding).
6. **Side callout notes** explaining format alignment rules (e.g., "Pad row size to a multiple of 4 Bytes").

---

## 2. Non-Goals

- **Static illustration replacement**: This will *not* show a static textbook picture. Every cell, offset, arrow, and padding size is computed from the **live parsed bytes** of the opened file.
- **Decompression of compressed pixel data**: For compressed BMPs (e.g., BI_RLE8, BI_BITFIELDS, BI_JPEG), pixel values are not decompressed into raw RGB in this diagram; the compressed payload block is visualized with its actual size and compression label.
- **Heavy third-party rendering engines**: Renders natively using Compose Desktop Canvas and Java2D (`BufferedImage` / SVG XML builder), fully preserving the existing zero-dependency architecture.

---

## 3. Reference Architecture & Visual Layout

Based on `/Users/dong.kim/Downloads/BMPfileFormat.svg.webp`:

```
+-------------------------------------------------------------+
|              Bitmap File Header: BITMAPFILEHEADER           |  <-- Dark Header Banner
+---------------+---------------------+-----------------------+
| Signature (2B)|         File Size (4B)                      |  <-- [File Size Bracket ───┐]
+---------------+---------------------+-----------------------+                            │
|  Reserved1(2B)|     Reserved2 (2B)  |                       |                            │
+---------------+---------------------+-----------------------+                            │
|               File Offset to PixelArray (4B) ───────────────┼──────────────┐             │
+-------------------------------------------------------------+              │             │
|                 DIB Header: BITMAPINFOHEADER                |              │             │
+-------------------------------------------------------------+              │             │
| DIB Header Size (4B) |        Image Width (4B)              |              │             │
+----------------------+--------------------------------------+              │             │
| Image Height (4B)    | Planes (2B) | Bits per Pixel (2B)    |              │             │
+----------------------+-------------+------------------------+              │             │
| Compression (4B)     | Image Size (4B)                      |              │             │
+----------------------+--------------------------------------+              │             │
| ...                                                         |              │             │
+-------------------------------------------------------------+              │             │
|               Color Table / Palette (if present)            |              │             │
+-------------------------------------------------------------+              │             │
|                 GAP / Padding (if present)                  |              │             │
+-------------------------------------------------------------+              │             │
|                 Image Data: PixelArray[x, y]                | <────────────┘             │
+-------------------------------------------------------------+                            │
| Row h-1: Pixel[0, h-1] ... Pixel[w-1, h-1] | 4B-Padding     |                            │
| ...                                                         |                            │
| Row 1:   Pixel[0, 1]   ... Pixel[w-1, 1]   | 4B-Padding     |                            │
| Row 0:   Pixel[0, 0]   ... Pixel[w-1, 0]   | 4B-Padding ────┼──> "Pad row size to 4B"    │
+-------------------------------------------------------------+                            │
|               ICC Color Profile (if present)                |                            │
+-------------------------------------------------------------+ <──────────────────────────┘
  Byte offset: 0           1           2           3
```

---

## 4. Detailed Design

### 4.1. Core Spec Diagram Data Model (`com.multiviewer.parser.diagram.spec`)

```kotlin
data class SpecDiagram(
    val title: String,
    val fileSize: Long,
    val blocks: List<SpecBlock>,
    val arrows: List<SpecArrow>,
    val brackets: List<SpecBracket>,
    val callouts: List<SpecCallout>,
    val columnBytes: Int = 4, // Default 4-byte (32-bit) alignment grid
)

data class SpecBlock(
    val id: String,
    val title: String,
    val subtitle: String?,
    val offset: Long,
    val length: Long,
    val role: RegionRole,
    val rows: List<SpecRow>,
)

data class SpecRow(
    val cells: List<SpecCell>,
    val rowOffset: Long,
)

data class SpecCell(
    val id: String,
    val name: String,
    val value: String?,
    val byteLength: Long,
    val colSpan: Int, // Number of grid columns spanned (e.g. 1 to 4)
    val role: RegionRole = RegionRole.METADATA,
    val status: RegionStatus = RegionStatus.PARSED,
    val isPadding: Boolean = false,
)

data class SpecArrow(
    val fromCellId: String,
    val toBlockId: String,
    val color: Long,
    val label: String? = null,
)

data class SpecBracket(
    val fromOffset: Long,
    val toOffset: Long,
    val label: String,
    val color: Long,
)

data class SpecCallout(
    val targetCellOrBlockId: String,
    val text: String,
)
```

### 4.2. BMP Spec Diagram Builder (`BmpSpecDiagramBuilder.kt`)

Using the parsed BMP metadata (`parseBmpHeaders` in `BmpWalker.kt`), build a faithful spec diagram for the active file:
1. **BITMAPFILEHEADER block** (14 bytes):
   - Row 0: `bfType` (2B, e.g. "BM") + `bfSize` (first 2B of 4B, or spanned).
   - `bfSize` (4B) with bracket spanning the entire file (`0..fileSize`).
   - `bfReserved1` (2B), `bfReserved2` (2B).
   - `bfOffBits` (4B) with dynamic `SpecArrow` pointing to the Image Data block (`PixelArray`).
2. **DIB Header block**:
   - `BITMAPINFOHEADER` (40B), `BITMAPV4HEADER` (108B), or `BITMAPV5HEADER` (124B).
   - Each field placed into 4-byte rows according to Microsoft Win32 DIB specification.
   - Compression enum translated (e.g., `BI_RGB (0)`).
   - Bracket spanning the DIB header range (`biSize`).
3. **Color Table block** (if `bfOffBits > 14 + biSize` or `biBitCount <= 8`):
   - Entries labeled (`Color[0]`, `Color[1]` ... `Color[n]`).
4. **Gap block** (if gap between color table and `bfOffBits`):
   - Shown as `GAP (optional)` with hatched/dashed border.
5. **PixelArray[x, y] block**:
   - Computes actual row stride: `rowBytes = ((width * bitCount + 31) / 32) * 4`.
   - Explicitly displays row padding: `paddingBytes = rowBytes - (width * bitCount + 7) / 8`.
   - If `paddingBytes > 0`: rows show `Pixel[0, y] ... Pixel[w-1, y]` and explicit `Padding (N bytes)` cell with callout: *"Pad row size to a multiple of 4 Bytes"*.
   - If image has many rows: shows top 2 rows (`Row h-1`, `Row h-2`), ellipsis divider (`⋮`), and bottom 2 rows (`Row 1`, `Row 0`).
6. **Trailing ICC Profile / Data** (if `fileSize > bfOffBits + imageSize`).

### 4.3. Universal BoxNode Spec Fallback

For non-BMP formats (e.g., MP4/MOV, JPEG, PNG, WebP):
- Top-level `BoxNode` children become `SpecBlock`s with dark header banners.
- Each `BoxNode.fields` items are packed into 4-byte / aligned `SpecRow`s based on their `length`.
- Box header size (`headerSize`) and payload ranges are visually segmented.
- Major payload blocks (like `mdat` in MP4, Scan data in JPEG, `IDAT` in PNG) are rendered with high-contrast data patterns.

### 4.4. UI Integration (`StructureDiagramWindow.kt`)

- Add a **`[규격도 | Spec Diagram]`** button to the top toolbar alongside `[개요 | Overview]`, `[상세 | Detail]`, `[세부 | Deep]`, and `[표 | Table]`.
- Interactive hover and click:
  - Clicking any cell selects the corresponding node in the main structure tree and Hex viewer.
- Zoom & Pan HUD supported seamlessly.
- **Export**:
  - `renderSpecSvg()`: Vector SVG export with exact XML markers for arrows.
  - `renderSpecPng()`: High-resolution PNG rendering via Java2D, copyable to the OS clipboard.

---

## 5. Test & Validation Plan

1. **Unit tests (`BmpSpecDiagramBuilderTest.kt`)**:
   - Verify against real BMP fixtures in test resources (`app/src/test/resources/bmp/`):
     - `real.bmp` (Standard 24-bit RGB with padding).
     - Paletted 8-bit / 4-bit BMP (with Color Table).
     - Modern BMP with `BITMAPV5HEADER` and ICC profile.
   - Assert exact cell counts, offsets, row padding calculations, and `bfOffBits` target arrow.
2. **Universal Fallback tests**:
   - Verify MP4 `ftyp + moov + mdat` generates valid 4-byte aligned blocks without exceptions.
3. **Export tests**:
   - Assert valid XML structure from `renderSpecSvg()` (no unescaped characters, correct viewBox).
   - Assert non-empty `BufferedImage` with correct dimensions from `renderSpecPng()`.
