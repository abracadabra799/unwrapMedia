# JPEG File Support — Design

## Background

The tool currently only opens ISOBMFF-family files (MP4/MOV/HEIC/3GP) — `parseFile` unconditionally treats byte 0 as the start of a `[size][fourcc]` box header. JPEG uses a completely different structure (a sequence of marker segments, no box nesting), so opening a JPEG today silently produces garbage: the first bytes (`0xFFD8...`) get misread as a box size, and nothing useful is shown.

## Goal

Recognize JPEG files and show their marker-segment structure in the same tree UI already used for ISOBMFF files, with dedicated decoding for the segments users actually care about: image dimensions (`SOF`) and embedded metadata (`APP1` Exif/XMP).

## Non-Goals

- No decoding of `DQT` (quantization tables), `DHT` (Huffman tables), `SOS` (scan header) fields, or any other segment beyond structural marker/offset/size — these are shown as plain nodes, matching how many ISOBMFF boxes without a specific decoder already fall back to structural-only display.
- No JPEG XL, no motion-JPEG, no JPEG 2000 — only classic ISO/IEC 10918-1 JPEG (the format every digital camera/phone still produces alongside HEIC).
- No PNG support (a separate format with its own chunk structure) — out of scope for this feature, may be considered separately later.
- No decoding of `APP2` (commonly ICC profiles), `APP13` (Photoshop/IPTC), `APP14` (Adobe) or any `APPn` other than `APP1`'s Exif/XMP content.

## Design

### 1. File-type dispatch (`ParseFile.kt`)

`parseFile` peeks the first 2 bytes of the opened file: `0xFF 0xD8` (the SOI marker) means JPEG, routed to a new `parseJpegSegments(reader, start, end): List<BoxNode>`; anything else keeps going through the existing `parseBoxes` (ISOBMFF) path, unchanged. Both paths return the same `List<BoxNode>` shape, wrapped in the same synthetic `"root"` `BoxNode` as today — no changes needed anywhere in the UI layer (tree view, field panel, hex view, table view all already operate on `BoxNode` generically).

### 2. JPEG marker segment walker (new `JpegWalker.kt`)

Walks from SOI to EOI, producing one `BoxNode` per segment (`type` = the marker's name, e.g. `"SOF0"`, `"APP1"`, `"DQT"`, or a `0xFFXX` hex fallback for any marker not in the name table):

- **No-payload markers** (`SOI` 0xD8, `EOI` 0xD9, `TEM` 0x01, `RST0`-`RST7` 0xD0-0xD7): exactly 2 bytes, no length field, no children/fields.
- **Length-bearing markers** (everything else, including `SOF*`, `DHT`, `DQT`, `DRI`, `SOS`, `COM`, `APPn`, `DAC`): a 2-byte big-endian length field immediately follows the marker; the length value **includes** those 2 length bytes (matching the JPEG spec), so total segment size = `2 (marker) + length`.
- **`SOS` (Start of Scan)** is special: after its own length-bearing header, the entropy-coded scan data follows with **no length prefix** — the walker scans byte-by-byte for the next real marker (`0xFF` followed by a byte that is neither `0x00` — a stuffing byte — nor a restart marker `0xD0`-`0xD7`, both of which are legitimately allowed inside compressed scan data) and treats everything up to that point as part of the `SOS` node's size. Progressive JPEGs have multiple `SOS` segments (each followed by more scan data); this falls out naturally since the main walker loop continues from wherever the scan-data scan stopped.
- Malformed/truncated input (a byte that isn't `0xFF` where a marker is expected, a length field running past EOF) bails out with a warning on the last-parsed node, matching every existing bail-out convention in this codebase — never throws.

### 3. `SOF` (Start of Frame) decoding

Any of the SOF marker variants (`0xC0`-`0xC3`, `0xC5`-`0xC7`, `0xC9`-`0xCB`, `0xCD`-`0xCF` — excluding `0xC4`/`DHT`, `0xC8`/reserved, and `0xCC`/`DAC`, which share the numeric range but aren't frame headers) share one payload layout: `precision`(1 byte) + `height`(2 bytes BE) + `width`(2 bytes BE) + `num_components`(1 byte), followed by `num_components` × 3-byte component records (`component_id`, a byte packing horizontal/vertical sampling factors as two nibbles, `quantization_table_selector`). Decoded into fields (`precision`, `height`, `width`, `num_components`, then repeated `component_id`/`sampling_factors`/`quantization_table` fields per component, following the same repeated-field-name convention `FtypBoxDecoder` already uses for repeated `compatible_brand` entries). Summary: `"${width}x${height}, N component(s)"`.

### 4. `APP1` (Exif / XMP) decoding

`APP1`'s payload is sniffed by its leading bytes:
- **Exif**: payload starts with the 6-byte ASCII sequence `"Exif"` followed by two `0x00` bytes, then a TIFF header directly (no length-prefix wrapper, unlike HEIF's `ExifDataBlock`). This reuses the *existing* `ExifDecoder` — a small refactor extracts the TIFF-walking core (currently inlined in `decodeExif`, which also handles HEIF's extra 4-byte offset field) into a new function `decodeTiff(reader, tiffStart, itemEnd): List<BoxNode>`, called directly by both the HEIF path (`decodeExif`, unchanged behavior, now a thin wrapper) and this new JPEG path (`tiffStart` = right after the 6-byte prefix). No duplication of the IFD/GPS/MakerNote/tag-table logic.
- **XMP**: payload starts with the 29-byte ASCII sequence `"http://ns.adobe.com/xap/1.0/"` followed by a `0x00` byte, then the RDF/XML text directly filling the rest of the segment — decoded as UTF-8 text and exposed as a single `xmp` field, the same pattern already used for HEIC's `mime`/`application/rdf+xml` items.
- Anything else in `APP1` (rare in practice) falls back to a plain structural node, same as any other unhandled marker.

## Testing

Unit tests (synthetic byte arrays, following this project's established `byteReaderOf` convention) for: the no-payload marker set, a length-bearing generic marker, `SOS` scan-data skipping (including a byte-stuffed `0xFF00` and a restart marker inside the "scan data" that must NOT be mistaken for the next real marker), truncated/malformed input warnings, `SOF0` dimension/component decoding, `APP1` Exif detection (delegating to `decodeTiff`) and XMP text extraction, and `decodeExif`'s existing behavior staying unchanged after the refactor (regression coverage). Manual verification: open a real JPEG photo (ideally one with embedded Exif/XMP, such as a photo exported alongside the Motion Photo HEIC already used for testing this tool) and confirm the marker tree, `SOF0` dimensions, and Exif/XMP content all render correctly.
