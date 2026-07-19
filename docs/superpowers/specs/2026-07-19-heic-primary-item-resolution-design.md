# HEIC Primary-Item Resolution/Color-Space Fix — Design

## Background

`MediaSummaryBuilder.buildImageBasicInfo` derives the Media Summary's "Resolution" and "Color Space" fields for HEIC/HEIF files via `findFirst(root) { it.type == "ispe" }` and `findFirst(root) { it.type == "colr" }` — the *first* matching box found anywhere in the box tree, in tree order, with no regard for which HEIF item it actually describes.

This is correct for simple, single-item HEIC files (one `ispe`, one `colr`), but wrong for files with multiple HEIF items — which is exactly what Samsung's tiled/grid-encoded high-resolution photos, portrait/depth companion images, and HDR gain maps produce. Such files have several `ispe` and `colr` boxes inside `ipco` (the Item Property Container), one set per item, and the first one in tree order often belongs to a grid tile or an auxiliary image rather than the primary displayed photo.

**Confirmed via byte-level analysis of a real sample** (`20260715_223828.heic`, a Samsung Galaxy photo): the file's `ipco` contains 5 `ispe` boxes. The primary item (ID 41, a `grid` item per `pitm`) is associated — via `ipma` — with the *second* `ispe` in tree order (4000×2252, the true photo dimensions). The *first* `ispe` in tree order (512×512) belongs to the grid's individual tile sub-images (items 1–40). The shipped code picks the first one, so the Media Summary shows "512x512" instead of "4000x2252".

Color Space uses the identical "first match" pattern for `colr`, so it has the same latent bug even though it happened to read correctly on this particular sample (the primary item's `colr` association happened to also be the first `colr` in tree order).

## Goal

Resolve "Resolution" and "Color Space" through the proper HEIF item-property association chain — `pitm` (primary item ID) → `ipma` (item → property index list) → `ipco` (indexed property list) — so both fields reflect the primary item specifically, not whichever box happens to appear first in the tree.

## Non-Goals

- No change to Camera Info, GPS Location, Samsung Metadata, or Capture Date — these come from the single Exif item and are not affected by this bug.
- No change to JPEG resolution/color-space handling — JPEG uses SOF-marker fields directly, not `ispe`/`colr`, so it's unaffected by this bug entirely.
- No change to video (`moov`/`trak`-based) resolution handling — unaffected, different code path.
- No handling of `ipma` version/flag combinations beyond what the ISOBMFF spec defines (version 0 or ≥1 for item ID width; flags bit 0 for 1-byte vs 2-byte association width) — this covers every real-world encoder.

## Design

### 1. `PitmBoxDecoder` (new)

Decodes the Primary Item Box (`pitm`): a FullBox with `version` (1 byte) + `flags` (3 bytes), followed by `primary_item_ID` — 2 bytes if `version == 0`, else 4 bytes. Produces a `BoxField("primary_item_ID", ...)`. Mirrors `InfeBoxDecoder`'s structure and error-handling style (warn and return an empty-fields `BoxNode` if the box is too short).

### 2. `IpmaBoxDecoder` (new)

Decodes the Item Property Association Box (`ipma`): FullBox `version`+`flags`, then `entry_count` (4 bytes), then that many entries:
- `item_ID` — 2 bytes if `version < 1`, else 4 bytes
- `association_count` (1 byte)
- that many associations — 2 bytes each if `flags & 1`, else 1 byte each; top bit is the "essential" flag, the rest is the 1-based property index into `ipco`

Produces one child `BoxNode` per item entry, named `item_$itemId` (matching `IlocBoxDecoder`'s existing `item_$itemId` naming convention for consistency), each holding one `BoxField("property_index", "<index>", ...)` per association in that item's list, in order. Same short-box warning style as other decoders.

Both decoders get registered in `Decoders.kt` alongside the existing HEIF decoders. As a side effect, `pitm`/`ipma` become properly decoded in the Structure Analyser tree/hex view too (today they render as raw/opaque bytes since neither has a decoder).

### 3. `findPrimaryItemProperty` (new, in `MediaSummaryBuilder.kt`)

```kotlin
private fun findPrimaryItemProperty(root: BoxNode, propertyType: String): BoxNode? {
    val meta = root.children.find { it.type == "meta" } ?: return null
    val pitm = meta.children.find { it.type == "pitm" } ?: return null
    val primaryItemId = pitm.fields.find { it.name == "primary_item_ID" }?.value ?: return null
    val ipma = meta.children.find { it.type == "ipma" } ?: return null
    val itemEntry = ipma.children.find { it.type == "item_$primaryItemId" } ?: return null
    val propertyIndices = itemEntry.fields
        .filter { it.name == "property_index" }
        .mapNotNull { it.value.toIntOrNull() }
    val ipco = findFirst(meta) { it.type == "ipco" } ?: return null
    for (index in propertyIndices) {
        val property = ipco.children.getOrNull(index - 1) ?: continue
        if (property.type == propertyType) return property
    }
    return null
}
```

Returns `null` whenever any part of the chain is missing or unresolvable — the caller then falls back to today's behavior, so simple single-item HEIC files (which may lack `pitm`/`ipma` entirely, or associate cleanly through the first-found box anyway) are unaffected.

### 4. `buildImageBasicInfo` — wiring

For both the `ispe` lookup (Resolution) and the `colr` lookup (Color Space), try `findPrimaryItemProperty(root, "ispe" | "colr")` first; if it returns `null`, fall back to the existing `findFirst(root) { it.type == "ispe" | "colr" }`. No other logic in `buildImageBasicInfo` changes.

## Testing

- `PitmBoxDecoderTest` / `IpmaBoxDecoderTest` (new) — unit tests mirroring `InfeBoxDecoderTest`/`IlocBoxDecoderTest`'s style: correct decode for version 0 and version 1, short-box warnings, multi-item `ipma` decode with both 1-byte and 2-byte association widths.
- `MediaSummaryBuilderTest` additions:
  - A HEIC-shaped fixture (`root` → `meta` → `pitm` + `ipma` + `iprp`/`ipco` with multiple `ispe`/`colr` candidates) where `pitm`/`ipma` point at a *non-first* candidate — asserts the Media Summary reports the correct (associated) dimensions/color space, not the first one in tree order.
  - A HEIC-shaped fixture with `pitm`/`ipma` absent entirely — asserts the existing fallback-to-first-found behavior still works (regression guard for simple single-item HEIC files).
