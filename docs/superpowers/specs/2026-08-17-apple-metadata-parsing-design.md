# Apple-Specific Photo and Video Metadata Parsing Design

## Goal

Extend unwrapMedia's native binary parser so Apple photos and videos expose Apple MakerNote data, QuickTime metadata, auxiliary-image relationships, and timed metadata through the existing structure tree, Overview, Detailed Properties, CLI dump, warnings, and hex-location workflow.

The implementation must preserve every structurally valid unknown Apple tag or key instead of silently dropping it. ExifTool is a research and verification oracle, not a runtime dependency.

## Evidence and Scope

The design is based on the current parser architecture, ExifTool's 2026 `Apple.pm` and `QuickTime.pm` tag tables, Apple QuickTime/Core Media conventions, and two local Apple originals:

- `/Users/dong.kim/Downloads/20260728/20260402_185008_IMG_0002.HEIC`
- `/Users/dong.kim/Downloads/20260728/IMG_0001.MOV`

The HEIC contains Apple MakerNote data, HDR gain-map data, disparity/depth metadata, portrait-effects and semantic mattes, Smart Style assets, and XMP calibration data. The MOV contains a primary HEVC track, several auxiliary HEVC tracks, PCM audio, multiple `mebx` metadata tracks, and movie-level Live Photo and Smart Style keys. These files are local verification inputs only and must not be copied into or committed to the repository.

In scope:

- JPEG, TIFF, DNG/ProRAW, and HEIC Apple MakerNote parsing.
- QuickTime MOV/MP4 `meta`/`keys`/`ilst`/`data` parsing.
- Apple auxiliary item/track declarations and relationships.
- Bounded parsing of Apple timed-metadata declarations and small sample payloads.
- Dolby Vision configuration and video color/HDR metadata when present.
- Overview and Detailed Properties integration.
- Synthetic fixtures and local real-file verification.

Out of scope:

- Rendering depth maps or semantic mattes as new visual overlays.
- Decoding every auxiliary image into pixels when the existing preview path cannot already do so.
- Shipping or invoking ExifTool at runtime.
- Guessing meanings for undocumented tags or opaque payloads.
- Committing user originals or metadata containing personal identifiers.

## Architectural Decision

Use native structural parsing and return the existing `BoxNode`/`BoxField` model throughout. This preserves byte offsets, hex navigation, warning collection, CLI output, and the current UI interaction model. Do not add an Apple-only UI tab. Apple data appears in the existing Overview and Detailed Properties tabs.

A subprocess-only design was rejected because ffprobe and ExifTool output cannot reliably retain the exact source byte range or the original box/IFD hierarchy. A runtime hybrid was rejected because behavior would vary by installed tools. ExifTool and ffprobe remain development-time comparison tools.

## Parser Components

### AppleMakerNoteDecoder

`ExifDecoder` already peeks at IFD0 `Make` before decoding MakerNote tags. Extend that dispatch so `Make` values containing `Apple` select a dedicated Apple decoder rather than the Samsung Type2 table or the generic raw fallback.

The decoder supports standard TIFF scalar and array types plus Apple-specific compound values. Initial named coverage follows ExifTool's current Apple table and includes:

- MakerNote version.
- AE stability, target, and average values.
- AF stability, performance, measured depth, confidence, and focus position.
- Acceleration vector.
- HDR image type, headroom, and gain.
- Burst UUID and content identifier.
- Image capture type, including ProRAW, Portrait, Photo, Manual Focus, and Scene labels where documented.
- Processing, scene, quality, and signal-to-noise fields.
- Color temperature and camera type.
- Semantic/Photographic Style structures.
- Runtime binary plist and its CMTime fields.

Known enum and bit-field labels are shown only where the source table documents them. Unconfirmed ExifTool entries remain visible but carry an `Unconfirmed` warning or description. Unknown entries are emitted as `Apple Tag 0xXXXX`, retaining TIFF type, count, raw value preview, absolute offset, and byte length.

Binary plists are decoded by a small bounded reader supporting the types needed by observed Apple payloads: null/bool, signed integer, real, date, data, ASCII/UTF-16 string, UID, array, set, and dictionary. It enforces maximum recursion depth, object count, string/data preview size, and offset-table boundaries. Opaque or unsupported plist objects remain raw rather than failing the MakerNote.

### QuickTimeMetadataDecoder

Decode QuickTime metadata structurally:

```text
meta
├── hdlr
├── keys
│   └── key entries (namespace + key text)
└── ilst
    └── numeric key index
        └── data (type/locale + value)
```

The `keys` decoder records the one-based key index, namespace (normally `mdta`), original key, offset, and length. The `ilst` decoder resolves numeric child types through that table without discarding unresolved indices. Each `data` atom decodes its type flag and locale. Supported types include binary/undefined, UTF-8, UTF-16, Shift-JIS, signed and unsigned integers, float, double, point/size/rectangle/matrix tuples, JPEG, PNG, BMP, and nested atom data. Large binary values show type, length, and a bounded preview.

Every `com.apple.quicktime.*` key is emitted even when the app has no friendly label for it. Known keys receive display labels and appropriate formatting, including:

- make, model, software, and creation date.
- camera lens model, aperture, focal length, and 35 mm equivalent.
- content identifier and full-frame-rate playback intent.
- Live Photo vitality, scoring version, relighting, still-image time, and transforms.
- Smart Style rendering version, tone, color, intensity, bypass, and cast.
- orientation and other declared camera or capture values.

Original key text and raw type/value remain available alongside the interpreted display value.

### AppleAuxiliaryMetadataDecoder

Build an explanatory view over existing HEIF/ISO-BMFF item and track structures without duplicating pixel data. It correlates:

- `auxC` auxiliary type declarations.
- `iref` relationships such as `auxl`, `cdsc`, and `dimg`.
- `ipma` property associations.
- item type, dimensions, pixel information, codec configuration, and location.
- auxiliary QuickTime track handlers and track references.

Recognized identifiers include:

- `urn:com:apple:photo:2020:aux:hdrgainmap`.
- Apple depth/disparity auxiliary identifiers.
- portrait-effects matte.
- sky, person, skin, hair, teeth, and glasses semantic mattes.
- Smart Style linear thumbnail and style delta map.
- standard HEVC auxiliary ID identifiers.

XMP metadata is parsed as bounded XML and summarized for Apple depth, pixel-data, portrait, semantic-segmentation, and HDR-gain-map namespaces. Important scalar values and matrix/vector arrays become fields with source offsets where the XML tokenizer can provide them. Very large base64 rendering parameters are represented by encoding, decoded/encoded length, and a short preview; the full payload stays accessible in the original XMP/raw view.

The derived `Auxiliary Images` node lists each auxiliary item's semantic role, item/track ID, resolution, bit depth, codec, referenced primary item, and relevant metadata. Broken or dangling relationships generate warnings on the relationship node.

### AppleTimedMetadataDecoder

Recognize `mebx` metadata tracks and parse their declarations and bounded samples. The decoder covers sample-description declarations such as `keyd`, `mett`, `mdta`, `tagc`, and `logs`, including observed keys for:

- video orientation.
- Live Photo information and still-image time.
- Live Photo still-image transform and reference dimensions.
- Smart Style information.
- Apple Log and video-map declarations.
- content identifier and camera metadata where carried per sample.

Sample timing is obtained from the existing sample-table structures. The decoder reads only declared sample ranges and applies caps to individual sample bytes, total sampled bytes, sample count, recursion depth, and displayed preview length. Known compact payloads are decoded; unknown payloads retain key, decode timestamp/duration, size, offset, and raw preview.

### Dolby Vision and Frame-Rate Metadata

Dolby Vision is identified from codec configuration rather than inferred from Apple branding. Decode both `dvcC` and `dvvC` when encountered, exposing configuration version, profile, level, RPU presence, EL presence, BL presence, and compatibility ID. Combine this with `hvcC`, `colr`, mastering-display/content-light metadata when present, without labeling Apple Log, HDR10, or HLG as Dolby Vision.

Frame-rate presentation distinguishes:

- track timescale and duration.
- sample-derived average frame rate.
- declared/nominal rate where available.
- variable-frame-rate status based on sample deltas or existing probe data.

Values that come from ffprobe-powered codec analysis remain clearly separate from values parsed from the file's binary tree.

## Data Flow

Photo path:

```text
JPEG/TIFF/DNG/HEIC
  → standard EXIF/TIFF
  → IFD0 Make dispatch
  → Apple MakerNote
  → HEIF item/property/reference correlation
  → Apple XMP summaries
  → MediaSummaryBuilder
```

Video path:

```text
MOV/MP4
  → ISO-BMFF box tree
  → meta/keys/ilst static metadata
  → track classification and auxiliary references
  → bounded mebx declaration/sample parsing
  → Dolby Vision, color, and frame-rate detail
  → MediaSummaryBuilder
```

Parsing produces one authoritative tree. Overview sections are projections of that tree; they do not invoke a second Apple metadata parser.

## UI Integration

Do not add an Apple tab. Retain `Overview / Detailed Properties` and the existing auto-switch to Detailed Properties after tree selection.

When data exists, Overview conditionally adds:

- **Apple Device:** make, model, software, creation date.
- **Camera & Capture:** lens model, focal length, 35 mm equivalent, aperture, camera type, capture type, focus position.
- **Computational Photography:** HDR mode/headroom/gain, Smart Style, semantic style, processing flags.
- **Depth & Portrait:** depth/disparity quality, simulated aperture, calibration presence, portrait and semantic matte roles.
- **Live Photo:** content identifier, still-image time, vitality, transform, full-frame-rate intent.
- **Video Metadata:** nominal and average rates, VFR state, Apple Log, Dolby Vision configuration, color metadata, and auxiliary/timed track counts.

Empty fields and sections are omitted. Matrices, full plist structures, large binary values, and long rendering parameters remain in Detailed Properties only.

The structure tree keeps the original storage hierarchy and may add derived grouping nodes where relationships span distant boxes:

```text
Exif / MakerNote (Apple)
meta / keys / ilst / Apple QuickTime Metadata
meta / Auxiliary Images / <semantic role>
trak [mebx] / Timed Metadata
```

Detailed Properties reuses current typography, spacing, warning colors, property rows, and click-to-hex behavior. A field shows a friendly name, original tag ID or key, interpreted value, raw value/type when useful, byte range, and warnings. No Apple-specific visual theme is introduced.

## Failure Handling and Resource Limits

- Validate every IFD, box, item, sample, plist, and XML offset against both its parent boundary and file length.
- Check multiplication/addition overflow for TIFF counts, box lengths, extent arithmetic, and sample offsets.
- Track visited IFD/plist/reference objects and cap recursion to prevent cycles.
- Treat a malformed tag or sample as a node-local warning and continue with siblings.
- Never infer a semantic name from an undocumented numeric value.
- Bound binary previews and timed-sample analysis; do not scan media payloads without declared ranges.
- Parse privacy-sensitive location, face, and semantic metadata locally because this is a forensic viewer, but never upload or automatically export it.
- Preserve exact raw keys and values whenever interpretation fails.

## Testing Strategy

### Unit Tests

- Apple MakerNote endian, TIFF scalar/array types, known names, enum labels, rational formatting, compound plist fields, and unknown fallback.
- Binary-plist object kinds, CMTime extraction, invalid trailer, cyclic reference, excessive depth/count, and out-of-range offset.
- QuickTime `keys` indexing, `ilst` resolution, all supported `data` types, locale fields, unknown index/key, and truncated values.
- `auxC` identifier classification and relationship resolution.
- `mebx` declaration parsing, known compact samples, timestamps, caps, and raw fallback.
- Dolby Vision configuration bits and malformed records.

### Synthetic Structure Tests

Build minimal byte arrays inside tests rather than storing personal media:

- TIFF with `Make=Apple` and Apple MakerNote.
- QuickTime `meta` with several `mdta` keys and typed values.
- HEIF item graph containing depth, HDR, and semantic-matte relationships.
- `mebx` track with a key declaration and timed sample.
- constant- and variable-duration sample timing.
- Dolby Vision configuration record.

### Local Real-File Verification

For the local HEIC, verify Apple/iPhone identity, MakerNote fields, HDR gain map, depth/disparity calibration, portrait/semantic mattes, Smart Style data, and absence of parser crashes or out-of-bounds reads.

For the local MOV, verify all movie-level `com.apple.quicktime.*` keys, Live Photo identifier/still-image metadata, auxiliary tracks, `mebx` tracks, declared versus average frame rate, and absence of parser crashes or out-of-bounds reads.

Public Apple samples may be downloaded for local verification if their license and provenance are clear. They are committed only when redistribution is explicitly allowed and their size is reasonable; otherwise they remain local test inputs.

### Regression Verification

- Run targeted new test classes during TDD.
- Run the full `./gradlew test` suite before completion.
- Re-run Samsung MakerNote, JPEG, HEIC, TIFF/DNG, generic MOV/MP4, MediaSummaryBuilder, CLI dump/check, and corruption tests.
- Confirm malformed files produce warnings rather than process-level failures.

## Acceptance Criteria

- Known Apple MakerNote tags display a stable name, type, interpreted value, and byte location.
- Every structurally valid unknown Apple MakerNote tag remains visible with a raw fallback.
- Every structurally valid QuickTime metadata key is visible by its original key; known keys also receive friendly formatting.
- Auxiliary images/tracks show their semantic role and relationship to the primary media.
- Known timed metadata is decoded within explicit resource limits; unknown samples remain inspectable.
- Dolby Vision, Apple Log, HDR10/HLG, and SDR are not conflated.
- Apple metadata appears naturally in existing Overview and Detailed Properties views.
- Fields retain correct byte ranges wherever the container supplies one.
- Both local Apple originals parse without crashes or out-of-bounds warnings caused by the new code.
- New and existing automated tests pass.

## References

- ExifTool Apple MakerNote implementation: <https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool/Apple.pm>
- ExifTool QuickTime implementation: <https://github.com/exiftool/exiftool/blob/master/lib/Image/ExifTool/QuickTime.pm>
- Apple QuickTime File Format documentation references embedded in ExifTool's QuickTime implementation.
- Existing unwrapMedia EXIF/MakerNote design: `docs/superpowers/specs/2026-07-18-exif-xmp-makernote-design.md`
- Existing unwrapMedia EXIF detail design: `docs/superpowers/specs/2026-08-04-exif-detail-enrichment-design.md`
- Existing unwrapMedia video Overview design: `docs/superpowers/specs/2026-08-07-video-overview-detail-design.md`
