# EXIF / XMP / Samsung MakerNote Parsing — Design

## Background

The HEIC sample file (`~/Downloads/20260715_223828.heic`) stores EXIF metadata and two XMP blocks as HEIF "metadata items" inside the `meta` box: `iinf` declares each item's type, `iloc` locates its bytes (in `mdat` or the small `idat` box), and the referenced bytes are a standard TIFF/EXIF blob or raw XMP RDF/XML text. This was empirically verified against the real file: item 49 (`Exif`) at absolute file offset 719723/946 bytes decodes as a valid little-endian TIFF structure (`Make=samsung`, `Model=Galaxy Z TriFold`, a `DateTimeOriginal`, a GPS IFD, and a Samsung MakerNote blob under Exif tag `0x927c`); items 50 and 51 (`mime`, `content_type=application/rdf+xml`) are two real XMP RDF/XML blocks.

`iloc` was explicitly deferred as out of scope in the earlier box-detail-parsing feature (its nibble-packed, version-dependent field widths). It's back in scope now because it's the only way to locate the Exif/XMP bytes at all.

## Goal

1. Parse `iloc`, resolving every item's byte location (file-absolute or `idat`-relative).
2. Cross-reference `iloc` with `iinf` (which already carries each item's type) so the box tree shows Exif and XMP content inline, not just raw offsets.
3. Fully decode EXIF: TIFF header, IFD0, Exif SubIFD, GPS IFD, and Samsung's proprietary MakerNote sub-structure, each tag shown by name where known.
4. Decode XMP items (`mime` + `content_type=application/rdf+xml`) as their actual RDF/XML text.

## Non-Goals

- Samsung MakerNote coverage is the subset of tags documented by the public Exiv2/ExifTool community references (a stable, long-maintained reference, unlike the ad hoc reverse-engineering this project did for Samsung's SEFD trailer) — undocumented tag IDs are shown as `Tag 0xXXXX` with their raw decoded value, not guessed at.
- No exhaustive EXIF tag dictionary covering the entire standard (hundreds of tags across every IFD) — only the tags observed in real files plus other very commonly seen baseline/Exif/GPS tags are named; anything else falls back to `Tag 0xXXXX`.
- `iloc` construction_method 2 ("item offset", i.e. this item's bytes are defined relative to another item) is not implemented — not observed in the real file and rare in practice. An item using it gets a warning and its raw (unresolved) offset/length fields, same graceful-degradation pattern used elsewhere in this codebase.
- No write/edit support — this tool is read-only, unchanged.

## Design

### 1. `InfeBoxDecoder` fix (existing file, `app/src/main/kotlin/com/multiviewer/parser/InfeBoxDecoder.kt`)

Today, `item_name` is read as "everything from its offset to the end of the payload, trailing NULs trimmed" — this was an explicit simplification when `infe` was first added, silently wrong for `mime`-typed items, which have an additional `content_type` (and optionally `content_encoding`) field after `item_name`. Fix: read `item_name` only up to its **first** NUL byte (not the whole remainder), then, only when `item_type == "mime"`, read a second null-terminated string immediately after as `content_type`, exposed as a new `BoxField`. Non-`mime` items are unaffected (their `item_name` already has no embedded NUL in practice, so stopping at the first NUL vs. trimming trailing NULs produces the same result).

### 2. `IlocBoxDecoder` (new, `app/src/main/kotlin/com/multiviewer/parser/IlocBoxDecoder.kt`)

FullBox, with the classic ISOBMFF Item Location Box nibble-packed header:
- `version` (1 byte)
- `offset_size`/`length_size` (one byte, high/low nibble)
- `base_offset_size`/`index_size` (one byte, high/low nibble — `index_size` only meaningful for version 1/2)
- `item_count` (2 bytes for version < 2, 4 bytes for version 2)
- per item: `item_ID` (2 or 4 bytes), `construction_method` (2 bytes, low 4 bits — only present for version 1/2, else implicitly 0), `data_reference_index` (2 bytes), `base_offset` (`base_offset_size` bytes), `extent_count` (2 bytes), per extent: `extent_index` (`index_size` bytes, skipped/unused — not needed for construction_method 0/1) + `extent_offset` (`offset_size` bytes) + `extent_length` (`length_size` bytes)

Verified against the real file: version 1, `offset_size=4`, `length_size=4`, `base_offset_size=0`, `index_size=0`, 51 items, every item with exactly one extent — parsing consumed the box's payload down to the exact last byte.

Produces one child `BoxNode` per item (`type = "item_$itemId"`), with a field for `construction_method`, and one grandchild `BoxNode` per extent:
- `construction_method == 0` (file offset): extent's absolute file offset = `base_offset + extent_offset` (both already file-absolute) — resolved directly, shown as `offset`/`length` fields.
- `construction_method == 1` (idat offset): shown as `idat_relative_offset`/`length` fields — `IlocBoxDecoder` alone doesn't know the sibling `idat` box's absolute file position (a `BoxDecoder` only sees its own box, not siblings — the same constraint noted when `iloc` was first deferred), so absolute resolution happens one layer up, in `MetaBoxDecoder` (see below).
- `construction_method == 2` (item offset): a warning is added (`"construction_method=2 (item offset) is not supported"`), and the raw `base_offset`/`extent_offset` numbers are shown unresolved.

### 3. `MetaBoxDecoder` enhancement (existing file)

`MetaBoxDecoder` already has what none of its children individually have: the fully-parsed `children` list, meaning `iinf`, `iloc`, and `idat` (if present) are all already decoded `BoxNode`s by the time `MetaBoxDecoder` builds its own result. After building `children` via the existing `parseBoxes(...)` call, add a post-processing pass:

1. Find the `iinf` and `iloc` children by `type`. If either is missing, skip this pass entirely (leave `children` as-is — this keeps `MetaBoxDecoder` behaving exactly as before for files without item metadata).
2. Build a map from `item_ID` (parsed back to `Long` from each `infe` child's `item_ID` field) to `(item_type, content_type)` (also read from that `infe` child's fields — `content_type` may be absent).
3. Find the `idat` child (if present) to get its absolute payload offset, for resolving `construction_method == 1` items.
4. For each item child of `iloc`: resolve any `construction_method == 1` extent's absolute offset as `idatPayloadOffset + idat_relative_offset` (only possible if `idat` was found — otherwise leave as-is with an added warning). Then, using the looked-up `item_type`/`content_type`:
   - `item_type == "Exif"`: read the resolved extent's bytes and decode as EXIF (see below), replacing that item's plain offset/length view with the decoded tag tree as additional children.
   - `item_type == "mime" && content_type == "application/rdf+xml"`: read the resolved extent's bytes, decode as UTF-8, trim trailing whitespace/NUL padding, and add it as a single `BoxField("xmp", <text>, ...)` on that item node.
   - Anything else: leave the item node exactly as `IlocBoxDecoder` produced it (structural offset/length only, no content decode).
5. Rebuild the `iloc` `BoxNode` with its item-children list replaced by the enriched versions (`BoxNode`s are immutable data classes — this is a `copy()`/reconstruction, not a mutation), and rebuild `MetaBoxDecoder`'s own `children` list with that enriched `iloc` node substituted in place of the original.

This keeps every individual decoder (`IlocBoxDecoder`, `InfeBoxDecoder`) simple, self-contained, and testable in isolation with synthetic bytes, while confining the cross-box correlation logic to the one place that already legitimately owns "the whole `meta` picture."

### 4. EXIF decoding (new, `app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt`)

A plain function (not a `BoxDecoder` — it operates on a raw byte range handed to it by `MetaBoxDecoder`, not on a registered box type), `decodeExif(bytes: ByteArray, baseOffset: Long): List<BoxNode>`, producing a small tree:

**Exif item data block:** per the HEIF/Exif spec, the item's bytes are `[4-byte big-endian offset to the TIFF header][padding, typically "Exif" + two space characters][TIFF header...]`. Verified against the real file: offset value `6`, followed by 6 bytes of `"Exif" + two space characters`, then the TIFF header.

**TIFF header:** 2-byte byte-order mark (`"II"` little-endian or `"MM"` big-endian — verified `"II"` in the real file), 2-byte magic (42), 4-byte offset to IFD0 (all relative to the TIFF header's own start).

**Generic IFD walker** (used for IFD0, the Exif SubIFD, and the GPS IFD — all three share the identical entry format): `entry_count` (2 bytes) + that many 12-byte entries (`tag`(2) + `type`(2) + `count`(4) + `value_or_offset`(4), the last field either holding the value directly when it fits in 4 bytes or an offset to it) + `next_IFD_offset` (4 bytes, always 0 in the real file — multi-IFD chains aren't specially handled beyond following it if present, matching the generic TIFF structure). Per-type value decoding: `ASCII` → string (NUL-trimmed), `SHORT`/`LONG`/`SBYTE`/`SSHORT`/`SLONG` → integer(s), `RATIONAL`/`SRATIONAL` → `numerator/denominator` pairs (displayed as the fraction and, where the denominator isn't zero, the computed decimal), `UNDEFINED`/`BYTE` → raw bytes (shown as hex for anything not otherwise special-cased).

**Sub-IFD pointer tags**, each followed recursively through the same generic IFD walker, appearing as a nested child node:
- `0x8769` → Exif SubIFD
- `0x8825` → GPS IFD
- `0xA005` → Interop IFD (if present; not in the real file, but a standard pointer tag worth following since the walker is generic)

**Samsung MakerNote** (Exif SubIFD tag `0x927c`): verified structurally different from a standard TIFF IFD — same 12-byte entry format, but no `next_IFD_offset` trailer (`entry_count*12 + 2 == total tag length` exactly, confirmed against the real file's 50-byte blob with 4 entries). Decoded with the same generic entry-parsing logic minus the trailing offset read, shown as a nested `MakerNote` child.

**Tag name tables**, `Map<Int, String>` per namespace (baseline/IFD0, Exif SubIFD, GPS IFD, Samsung MakerNote), covering the tags actually observed in the real file plus other very common standard tags (e.g. `Orientation`, `XResolution`, `Flash`, `WhiteBalance`) and the Samsung MakerNote tags documented at exiv2.org (`Version`, `DeviceType`, `FirmwareName`, `LensType`, `SensorAreas`, `ISO`, `ExposureTime`, `FNumber`, `FocalLengthIn35mmFormat`, etc.). A tag not in the relevant table displays as `Tag 0xXXXX` — this is a deliberately non-exhaustive, extend-as-needed table, not a claim of full EXIF/MakerNote spec coverage.

Each decoded tag becomes one `BoxField` (name = the table lookup or `Tag 0xXXXX` fallback, value = the type-decoded display string, offset/length = the entry's or its out-of-line value's absolute file position), and each IFD becomes one `BoxNode` (`IFD0`, `Exif`, `GPS`, `MakerNote`) with those fields, nested exactly where its pointer tag was found.

## Testing

- `InfeBoxDecoderTest`: extend with a `mime` item whose bytes include a `content_type` after `item_name`, asserting both fields decode correctly and `item_name` no longer swallows the content_type string when it isn't itself null-containing garbage (i.e. confirm the "stop at first NUL" fix).
- `IlocBoxDecoderTest`: synthetic version-1 box with `offset_size=4, length_size=4, base_offset_size=0, index_size=0`, one item per construction_method (0, 1, 2), asserting resolved/unresolved offsets and the construction_method=2 warning.
- `ExifDecoderTest`: synthetic TIFF bytes (byte-order mark, IFD0 with an ASCII tag and a pointer to a GPS IFD, a Samsung MakerNote blob), asserting tag names, decoded values, and correct recursive nesting.
- `MetaBoxDecoderTest`: extend (or add) a case with `iinf`+`iloc`+an `Exif` item's raw bytes inline, asserting the enriched `iloc` child shows decoded EXIF fields rather than plain offset/length.
- Manual verification: open the real sample file, confirm `iloc`'s `item_49` (Exif) node shows `IFD0`/`Exif`/`GPS`/`MakerNote` sub-trees with named tags matching the values gathered during analysis (`Make=samsung`, `Model=Galaxy Z TriFold`, MakerNote `Version=0101`, `FocalLengthIn35mmFormat=13`), and `item_50`/`item_51` (XMP) show the actual RDF/XML text.
