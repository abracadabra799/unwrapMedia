# HEIC Tile Grid ↔ Hex Data Mapping — Design

## Background

The user asked whether tile-level information for grid-tiled HEIC images is visible in the app (conflating it with EXIF's IFD structure, which is unrelated — IFD only covers embedded EXIF metadata, not HEIF's image-tile derivation).

A code survey found the pieces are mostly already there but not connected:
- `IrefBoxDecoder` already parses `dimg` reference entries ("grid item ID → tile item IDs", in row-major order per the HEIF spec) — visible today in the Media Structure tree.
- `InfeBoxDecoder` already shows each item's `item_type` (e.g. `grid`, `hvc1`), so a `grid`-type item is already identifiable.
- `IlocBoxDecoder` already computes each item's real, absolute file offset/length (`absoluteOffset`/`extentLength`) as *field values* — but the `extent` `BoxNode`'s own `offset`/`size` (which is what drives the existing tree-click-to-highlight-hex mechanism, `currentTab.selected?.let { it.offset until (it.offset + it.size) }` in `Main.kt`) point at the small iloc *table entry* describing that location, not the actual pixel data bytes elsewhere in the file. Clicking an extent node today highlights a few metadata bytes, not the tile's real image data.
- `HeifHevcThumbnail.kt`'s `extractHevcThumbnailAnnexB` already extracts an arbitrary item's HEVC bitstream (property lookup via `ipma`/`ipco`, length-prefixed-to-Annex-B NAL conversion) and `FfmpegImageSnapshotDecoder.decodeEmbeddedHevcThumbnailAsync` already decodes such a stream into a bitmap via a temp `.h265` file — both written for the `thmb` (thumbnail) reference specifically, but their internals are keyed by a generic `itemId` already.
- Missing: nothing decodes the `grid` item's own raw payload (row/column count, output canvas size) — the one piece needed to know each tile's position.

Real tiled HEIC files already exist in this environment for validation: `~/Downloads/20260715_223828.heic`, `~/Downloads/20260715_223835.heic`, `~/Downloads/20260728/20260402_185008_IMG_0002.HEIC` (all confirmed via byte search to contain both a `grid`-type item and a `dimg` reference).

## Goal

When the open file is a grid-tiled HEIC/HEIF (has an `infe` item with `item_type == "grid"` referenced via `iref`'s `dimg`):
- The primary image view (`PixelInspectorPreview`, in `ImageInspectorUI.kt`'s primary-image call site only) draws tile boundary lines automatically — no toggle, since this only ever appears for files that actually have the structure. A distinct color (`AppColors.NeonPurple`, matching the existing "MOTION PHOTO VIDEO" label color) keeps it visually distinct from the unrelated pixel-grid overlay (`AppColors.NeonPurple` is otherwise unused for overlays — only used for that one label today).
- Clicking a tile highlights that tile's *actual pixel data* byte range in the Hex viewer (fixing the offset/length gap above, without changing `IlocBoxDecoder`'s own node structure — the fix lives in a separate click-handling path that reads the already-computed `offset`/`length` field values).
- The same click also decodes that one tile (on demand, not all tiles upfront) via the existing HEVC extraction/decode pipeline and shows it in a small popup.

## Non-Goals

- No change for non-tiled images (the overwhelming majority) — the new code paths are no-ops when no `grid` item is found.
- AVIF's `av01`-coded tiles are out of scope for this spec (tracked as a separate follow-up spec once this one ships).
- No pre-decoding of every tile up front — only the clicked tile, matching this plan's own performance concern for grids with dozens of tiles.
- No changes to `IlocBoxDecoder`'s existing `extent` node `offset`/`size` fields (used by the existing tree-click-to-highlight mechanism) — the correct absolute byte range for tile clicks is computed separately, reusing the field values `IlocBoxDecoder` already produces.

## Design

### New: grid item payload decoder

A pure function decoding the `ImageGrid` struct (ISO/IEC 23008-12 §6.6.3) from an item's raw bytes (obtained via the same `extractItemBytes`-style iloc resolution `HeifHevcThumbnail.kt` already does):

```kotlin
data class GridLayout(val rows: Int, val columns: Int, val outputWidth: Int, val outputHeight: Int)

fun decodeGridItemPayload(bytes: ByteArray): GridLayout? {
    if (bytes.size < 4) return null
    val flags = bytes[1].toInt() and 0xFF
    val rows = (bytes[2].toInt() and 0xFF) + 1
    val columns = (bytes[3].toInt() and 0xFF) + 1
    val large = (flags and 1) == 1
    val fieldSize = if (large) 4 else 2
    if (bytes.size < 4 + fieldSize * 2) return null
    fun readUInt(offset: Int): Int = if (large) {
        ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
    } else {
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }
    val outputWidth = readUInt(4)
    val outputHeight = readUInt(4 + fieldSize)
    return GridLayout(rows, columns, outputWidth, outputHeight)
}
```

(`version` at byte 0 is read implicitly by not needing it — this app's other decoders, e.g. `IspeBoxDecoder`, likewise skip validating version bytes they don't need to branch on.)

### Generalizing `HeifHevcThumbnail.kt`

Add a new public entry point that both the existing thumbnail path and the new tile path call:

```kotlin
fun extractHevcItemAnnexB(file: File, root: BoxNode, itemId: Long): ByteArray?
```

`extractHevcThumbnailAnnexB` keeps its own signature (used by existing callers) but its body reduces to: resolve `thumbId` as it does today, then delegate to `extractHevcItemAnnexB(file, root, thumbId)`. The existing private helpers (`findItemProperty`, `extractItemBytes`, `readHvcCInfo`, `convertLengthPrefixedToAnnexB`) are unchanged — this is a pure extract-a-function refactor, not a rewrite.

### New: tile grid lookup

```kotlin
data class TileGridInfo(val layout: GridLayout, val tileItemIds: List<Long>, val tileWidth: Int, val tileHeight: Int)

fun findHeicTileGrid(file: File, root: BoxNode): TileGridInfo?
```

Steps: find the `meta`/`iinf` item with `item_type == "grid"` → its `item_ID` is the `from_item_ID` to look up in `iref`'s `dimg` children → that entry's `to_item_ID[*]` values, in field order, are `tileItemIds` (row-major, per spec). Decode the grid item's own bytes (via the same `extractItemBytes`-style resolution, generalized similarly to the HEVC extraction above) with `decodeGridItemPayload` for `layout`. Read `tileWidth`/`tileHeight` from the *first* tile item's `ispe` property (HEIF requires uniform tile size except naturally-cropped right/bottom edge tiles, so the first tile's size is representative). Returns `null` at any missing piece (no `grid` item, no matching `dimg` entry, undecodable payload, or missing `ispe`) — mirrors `extractHevcThumbnailAnnexB`'s own all-or-nothing null-return style.

### UI: tile overlay + click

`PixelInspectorPreview` gains an optional parameter:

```kotlin
tileGrid: TileGridInfo? = null,
onTileClick: ((itemId: Long) -> Unit)? = null,
```

Both default to `null`/no-op, so every existing call site (thumbnail box, GIF filmstrip, Raw Pixel viewer) is unaffected. Only `ImageInspectorUI.kt`'s primary-image box passes real values, computed once via `findHeicTileGrid(tab.file, tab.root)` when the tab's `root` is available (same background-thread-then-`EventQueue.invokeLater` pattern `AppState.openFile` already uses for other derived data, so this parse doesn't block the UI thread).

When `tileGrid != null`, a `Canvas` overlay (structurally the same shape as `PixelGridOverlay.kt`, drawn in the same fit-scale + caller's `graphicsLayer` coordinate space so it tracks zoom/pan identically) draws one rectangle per tile at `(col * tileWidth, row * tileHeight)` sized `tileWidth × tileHeight`, clamped to `outputWidth`/`outputHeight` for edge tiles, in `AppColors.NeonPurple`. A tap gesture resolves the tapped point to a `(row, column)` pair (`floor(x / tileWidth)`, `floor(y / tileHeight)`, clamped to the grid bounds) and looks up `tileItemIds[row * columns + column]`, invoking `onTileClick(itemId)`.

### Click handling: hex highlight + tile preview popup

`ImageInspectorUI.kt`'s `onTileClick` callback:
1. Resolves the tile item's real byte range the same way `IlocBoxDecoder`'s field values already express it (`offset`/`length` `BoxField`s on that item's `extent` child — read directly, not through the tree-click path) and sets a new piece of tab state, e.g. `tab.tileHighlightRange: LongRange?`, which `Main.kt`'s existing `highlightRange` computation for `HexView` folds in alongside `activeField`/`currentTab.selected` (same precedence pattern already there — tile click takes priority while a popup is open, matching how `activeField` already takes priority over tree selection).
2. Calls a new `FfmpegImageSnapshotDecoder.decodeHeicTileAsync(file, root, itemId, onResult)` (same shape as `decodeEmbeddedHevcThumbnailAsync`, reusing `extractHevcItemAnnexB` instead of `extractHevcThumbnailAnnexB`) and shows the result in a `Popup` anchored near the click point, with a close button/click-outside-to-dismiss.

## Testing

- `decodeGridItemPayload`: unit tests with hand-built byte arrays for both the 16-bit and 32-bit field-size cases, plus a too-short-input null case.
- `extractHevcItemAnnexB`/`extractHevcThumbnailAnnexB`: existing thumbnail tests must keep passing unchanged (regression proof the refactor is behavior-preserving); one new test calls `extractHevcItemAnnexB` directly against a real tile item ID from one of the confirmed real sample files.
- `findHeicTileGrid`: tested against the same real sample HEIC files (`~/Downloads/20260715_223828.heic` etc., copied into a test-fixtures location or referenced by absolute path per this project's existing convention of shelling out to real `ffmpeg`/using real files in tests rather than only synthetic fixtures where practical) — asserting the parsed `rows`/`columns`/tile count match what a manual/independent check of the file shows.
- Overlay rendering and click-to-popup wiring: manual verification against the real sample files, matching this project's established pattern for `graphicsLayer`/gesture code.
