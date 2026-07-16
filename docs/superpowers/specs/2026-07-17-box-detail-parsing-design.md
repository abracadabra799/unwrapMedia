# Box Detail Parsing — Design

## Background

Selecting a box in the tree currently shows box-specific fields in the right-hand detail panel (`FieldPanel`) only for boxes that have a registered `BoxDecoder` (`ftyp`, `mvhd`, `tkhd`, `mdhd`, `hdlr`, `ispe`, `meta`, and the sample-table boxes `stts`/`stsc`/`stco`/`co64`/`stss`/`ctts`/`stsz`). Every other box — including common, frequently-inspected ones like the `stsd` sample entries (`avc1`/`hvc1`/`mp4a`), their codec-configuration children (`avcC`/`hvcC`), `elst`, `dref` and its entries, `colr`, `pasp`, and the HEIC item-info boxes (`iinf`/`infe`) — falls back to `LeafBoxDecoder`, which produces no fields at all. Selecting one of these today shows a completely blank detail panel. Separately, even boxes that DO have fields never show basic positional metadata (offset, size, header size) or their own warnings — that information only exists inside `BoxNode` and is never rendered anywhere.

## Goal

1. Always show a minimal set of common metadata for the selected box, regardless of whether it has box-specific fields.
2. Add box-specific field parsing for a prioritized set of commonly-inspected boxes that currently show nothing.

## Non-Goals

- **`iloc` (Item Location Box)** is deferred. Its offset/length/index field widths are packed as 4-bit nibbles that vary by box version, making it structurally more complex than every other box in this batch; it is left for a future iteration.
- **SPS/PPS (or VPS) bitstream/RBSP decoding** is deferred, consistent with the original project design's stated non-goal around bitstream analysis. `avcC`/`hvcC` decoding in this batch stops at the box's own structural fields (profile, level, NAL-length size, NAL/parameter-set counts) — it does not parse the actual parameter-set bytes to recover resolution, chroma format, etc.
- Other `ipco` children beyond `colr`/`pasp` (e.g. `auxC`, `av1C`) are out of scope for this batch.
- No UI changes beyond `FieldPanel`'s always-shown metadata section — the tree, hex view, and table view are unaffected.

## Design

### 1. Always-shown metadata (`FieldPanel.kt`)

Every call to `FieldPanel(node)` renders a fixed metadata block first, using only data already present on every `BoxNode` (`type`, `offset`, `headerSize`, `size`, `children`, `warnings`) — no parser changes needed for this part:

- `Type`: the fourcc
- `Offset`: decimal, followed by the hex form in parentheses (e.g. `1024 (0x400)`), so it cross-references directly with the hex view's offset column
- `Size`: total box size in bytes (header + payload)
- `Header size`: 8 or 16
- `Payload size`: `size - headerSize`
- `Children`: count, shown only if the node has children (containers)
- `Warnings`: each warning on its own line, shown only if the node has warnings (currently these only surface as a `⚠` prefix in the tree; this is the first place their text becomes visible)

Box-specific fields (the existing `node.fields` list), if any, render below this block, unchanged from today. If a node has neither fields nor a table, the metadata block is still shown (today's early-return-on-empty-fields behavior is removed — a box with no decoder-specific fields still isn't a blank panel anymore).

### 2. New box decoders

All new decoders follow the existing per-box-file convention (one object/class per box type in `app/src/main/kotlin/com/multiviewer/parser/`, implementing `BoxDecoder`, registered in `Decoders.kt`), reusing `BoxField`/`TableData` and the existing bounds-checking/warning idioms (`"Box too short for ..."`, `"Declared N but only space for M"`).

**`avc1` / `hvc1` (VisualSampleEntry, `VisualSampleEntryDecoder`)**: fixed 78-byte header (`reserved[6]`, `data_reference_index`, `pre_defined`, `reserved`, `pre_defined[3]`, `width`, `height`, `horizresolution`, `vertresolution`, `reserved`, `frame_count`, `compressorname[32]`, `depth`, `pre_defined`). Surfaces `data_reference_index`, `width`, `height` as fields; parses everything after byte 78 as child boxes (so `avcC`/`hvcC`/`colr`/`pasp`/etc. appear as children, exactly like any container). Summary: `"${width}x${height}"`.

**`mp4a` (AudioSampleEntry, `AudioSampleEntryDecoder`)**: fixed 28-byte header (`reserved[6]`, `data_reference_index`, `reserved[8]`, `channelcount`, `samplesize`, `pre_defined`, `reserved`, `samplerate` as 16.16 fixed-point). Surfaces `data_reference_index`, `channelcount`, `samplesize`, `samplerate` (converted to Hz) as fields; parses everything after byte 28 as child boxes (e.g. `esds`). Summary: `"${channelcount}ch, ${samplerate}Hz"`.

**`avcC` (AVCDecoderConfigurationRecord, `AvcCBoxDecoder`)**: not a FullBox — plain structure. Reads `configuration_version`, `avc_profile_indication`, `profile_compatibility`, `avc_level_indication` (1 byte each), then `length_size` (`lengthSizeMinusOne` lower 2 bits of the next byte, plus one), then walks the declared SPS list (count = lower 5 bits of the following byte) and PPS list purely to count entries and validate bounds — it does not retain or decode the parameter-set bytes themselves. Surfaces all five scalar fields plus `num_sps`/`num_pps` as fields. Adds a warning (matching the existing "declared vs. available" idiom) if a declared parameter-set length would run past the box's end, and stops walking at that point.

**`hvcC` (HEVCDecoderConfigurationRecord, `HvcCBoxDecoder`)**: not a FullBox. Reads the fixed 23-byte header (`configuration_version`, `general_profile_idc` from the profile-space/tier/idc byte, `general_level_idc`, `length_size` from the constant-frame-rate byte's lower 2 bits, `num_arrays`), surfacing those as fields. Walks the `num_arrays` NAL arrays purely to tally counts by NAL unit type (VPS=32, SPS=33, PPS=34 all bucketed as `num_vps`/`num_sps`/`num_pps`, everything else summed into `num_other_nalus`) — like `avcC`, it does not retain or decode NAL contents, only counts and validates bounds.

**`elst` (Edit List Box, `ElstBoxDecoder`)**: FullBox. Reads `version` and `entry_count`, then decodes each entry as `segment_duration`/`media_time`/`media_rate` fields (repeated field names per entry, following `FtypBoxDecoder`'s repeated-`compatible_brand` convention) rather than the paginated `TableData` used for sample tables — edit lists are always small in practice, so pagination isn't needed, and using plain fields lets `media_time` and `media_rate` display as properly **signed** values (edit lists commonly use `media_time = -1` for an initial empty edit), which the existing unsigned-only `TableData`/`TableRowReader` path can't represent. Field widths depend on `version` (4-byte `segment_duration`/`media_time` for version 0, 8-byte for version 1); `media_rate_integer`/`media_rate_fraction` are always 2 bytes each, signed. Entries beyond what fits in the box's declared size are truncated with a warning, same as the table decoders.

**`dref` (Data Reference Box)**: no new decoder class — registered exactly like `stsd` via `ContainerBoxDecoder(childOffsetInPayload = 8, summarize = true)`, since its layout (FullBox header + `entry_count` + N child boxes) is identical in shape.

**`url ` / `urn ` (DataEntryUrlBox / DataEntryUrnBox, `UrlBoxDecoder` / `UrnBoxDecoder`)**: both FullBox. `url `'s single `location` field is a null-terminated UTF-8 string filling the rest of the payload, present only when `flags & 1 == 0` (bit 0 set means "self-contained", no location data) — the field list is empty (self-contained) or contains `location`. `urn ` always has two consecutive null-terminated strings, `name` then `location`. Note the registration keys have a trailing space (`"url "`, `"urn "`), matching the exact 4-byte fourcc the box walker reads.

**`colr` (Colour Information Box, `ColrBoxDecoder`)**: not a FullBox. Reads the 4-byte `colour_type` fourcc. If it's `nclx`, additionally reads `colour_primaries`/`transfer_characteristics`/`matrix_coefficients` (2 bytes each) and `full_range_flag` (top bit of the following byte), surfacing all five as fields. For any other `colour_type` (`rICC`/`prof`, embedded ICC profiles), surfaces only `colour_type` and summarizes the remaining bytes as `"ICC profile (N bytes)"` without attempting to parse the profile.

**`pasp` (Pixel Aspect Ratio Box, `PaspBoxDecoder`)**: not a FullBox. Reads `hSpacing`/`vSpacing` (4 bytes each) as fields. Summary: `"${hSpacing}:${vSpacing}"`.

**`iinf` (Item Information Box, `IinfBoxDecoder`)**: FullBox with a version-dependent `entry_count` width (2 bytes if `version == 0`, else 4 bytes) — this varies the child-boxes' starting offset by version, so it needs its own decoder rather than `ContainerBoxDecoder`'s fixed `childOffsetInPayload`. Parses everything after `entry_count` as child boxes (`infe` entries). Summary: `"N item(s)"`.

**`infe` (Item Info Entry, `InfeBoxDecoder`)**: FullBox. Supports `version` 2 and 3 (the versions HEIF requires for the `item_type` field, and the only versions modern encoders/decoders produce); `item_ID` is 2 bytes for version 2, 4 bytes for version 3, followed by `item_protection_index` (2 bytes), `item_type` (4-byte fourcc), and `item_name` (null-terminated UTF-8 string filling the rest of the payload — optional trailing fields like `content_type`/`content_encoding` for MIME items are not parsed). Surfaces all four as fields. Summary: `"$item_type: $item_name"`, mirroring `HdlrBoxDecoder`'s convention. Versions below 2 add a warning ("unsupported infe version") and return with no fields, the same bail-out pattern other decoders use for undersized boxes.

## Testing / Verification

Each new decoder gets unit tests in the same style as the existing decoder tests (`byteReaderOf(...)` synthetic byte arrays, asserting on `node.fields`, `node.summary`, and `node.warnings`), covering: the normal-case field values, the version-dependent width branch where applicable (`elst`, `infe`), and a truncated/undersized-input case that produces a warning instead of throwing. `FieldPanel`'s always-shown metadata section has no automated test (this project's UI layer has no test suite by design) and is verified by compiling and running: select boxes with and without decoder-specific fields and confirm the metadata block appears either way, then open a real file containing the newly-decoded box types (a `.mov`/`.mp4` with H.264 or H.265 video for `avc1`/`hvc1`/`avcC`/`hvcC`, and a `.heic` for `iinf`/`infe`) and confirm their fields render correctly.
