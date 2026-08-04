# EXIF/TIFF/DNG Detail Enrichment Design

## Goal

Make the Detailed Properties panel show metadata as thoroughly as ExifTool does (and, where practical, more usefully) for TIFF-family files (JPEG EXIF, TIFF, DNG and other camera RAW): far more named tags, human-readable values instead of raw numbers, and the ability to click any individual field and jump the hex viewer straight to its exact bytes.

## Background

`ExifDecoder.kt` already walks the full IFD chain correctly (IFD0, IFD1, Exif/GPS/Interop sub-IFDs, TIFF/EP SubIFDs used by camera RAW, Samsung MakerNote) and has a safe fallback for anything it doesn't recognize -- an unmapped tag still renders as `Tag 0x1234` with its raw value rather than being dropped. This means expanding coverage is additive and low-risk: it's a matter of growing lookup tables and adding a value-interpretation layer, not restructuring the parser.

Current tag coverage is thin against ExifTool's public-spec baseline: `TAG_NAMES_IFD0` has 11 entries, `TAG_NAMES_EXIF` has 28, `TAG_NAMES_GPS` has 6 (ExifTool's equivalent public-spec groups have 100+, 100+, and 30+ respectively), and there is no DNG-private tag table at all (`0xC6xx` range: `DNGVersion`, `ColorMatrix1/2`, `CalibrationIlluminant1/2`, `BlackLevel`, `WhiteLevel`, `AsShotNeutral`, `NoiseProfile`, `CFAPattern`, etc.) -- confirmed absent in `ExifDecoder.kt`. Values are also shown as raw TIFF-typed data only (`formatTiffValue`): a rational is `"10/1"`, an enum is a bare integer like `2`, never translated to a label. Scope is explicitly bounded to what's in the public EXIF 2.32 / TIFF 6.0 / Adobe DNG 1.6 specs -- camera-vendor MakerNote formats beyond the existing Samsung one (Canon, Nikon, Sony, etc.) are reverse-engineered proprietary formats and are out of scope here (confirmed with the user).

Separately, `BoxField` (`BoxNode.kt`) already carries `offset: Long` and `length: Long` per field, and `HexView` already accepts a `highlightRange: LongRange?` and is driven by `LaunchedEffect(currentTab.selected) { hexListState.scrollToItem(...) }` in `Main.kt` -- today this only fires for whole-node selection in the tree. The byte-position data needed for field-level jump-to-hex already exists; only the click wiring is missing.

## Design

### A. Expanded standard tag tables

`TAG_NAMES_IFD0`, `TAG_NAMES_EXIF`, and `TAG_NAMES_GPS` in `ExifDecoder.kt` grow to cover the EXIF 2.32 / TIFF 6.0 public tag sets (e.g. IFD0 gains `Compression`, `PhotometricInterpretation`, `SamplesPerPixel`, `PlanarConfiguration`, `WhitePoint`, `PrimaryChromaticities`, `Copyright`, `Artist`, `HostComputer`, `CFARepeatPatternDim`, `CFAPattern`, ...; Exif gains `LensMake`, `LensModel`, `LensSpecification`, `SubjectDistance`, `LightSource`, `Gain­Control`, `Contrast`, `Saturation`, `Sharpness`, `BodySerialNumber`, `RecommendedExposureIndex`, `SensitivityType`, `CustomRendered`, `FileSource`, `SceneType`, ...; GPS gains `GPSTimeStamp`, `GPSDateStamp`, `GPSSpeed`, `GPSImgDirection`, `GPSDestLatitude/Longitude`, `GPSHPositioningError`, `GPSProcessingMethod`, ...). No dispatch/structural changes -- these are the same flat `Map<Int, String>` the code already reads from in the `else ->` branch of `decodeIfd`.

### B. New DNG tag table

A new `TAG_NAMES_DNG` map (Adobe DNG 1.6 spec, tag range `0xC612`-`0xC7B5`) is merged into the map `decodeIfd` uses for IFD0 -- DNG's private tags are stored directly in IFD0 alongside standard TIFF tags (not a separate pointer-based sub-IFD), so no new traversal logic is needed, only a bigger combined lookup for that call site.

### C. Human-readable value interpretation

A new lookup, `TAG_VALUE_LABELS: Map<Pair<String, Int>, Map<Int, String>>` keyed by `(group, tag)` (e.g. `("IFD0" to 0x0112)` for Orientation, `("Exif" to 0x8822)` for ExposureProgram), holds `{rawValue -> label}` for enum/flag-style tags (Orientation, ExposureProgram, MeteringMode, Flash, WhiteBalance, ColorSpace, ResolutionUnit, YCbCrPositioning, LightSource, SceneCaptureType, ExposureMode, GPSLatitudeRef/GPSLongitudeRef, ...). In `decodeIfd`'s tag-formatting step: if a single-value integer field's `(group, tag)` has an entry for its raw value, the label is shown instead of the bare number; otherwise the existing raw formatting is unchanged.

Separately, measurement-style rational tags get dedicated formatters instead of raw `"num/den"`: `ExposureTime` -> `"1/125s"` (or `"2s"` when num >= den), `FNumber`/`ApertureValue`/`MaxApertureValue` -> `"f/2.8"`, `FocalLength`/`FocalLengthIn35mmFilm` -> `"24.0mm"`. These are keyed the same way as the enum table (`(group, tag) -> formatter`), so both mechanisms share one small dispatch point in `decodeIfd` rather than special-casing individual tags inline.

Tags with no entry in either table keep exactly today's behavior (raw formatted value) -- nothing regresses, coverage only grows.

### D. Field-level hex highlighting

- `TabState` gets a new `var selectedField: BoxField? by mutableStateOf(null)`, reset to `null` whenever `selected` (the tree node) changes -- same reset pattern already used for `selectedFrame`.
- Each field row in `DetailedPropertiesPanel` (currently `PropertyRow(field.name, field.value)`) becomes clickable, setting `tab.selectedField = field` on click, with a selected-row background (`AppColors.Selection`, same convention as `BoxTreeView`'s selected-node row) so the active field is visible.
- `Main.kt`'s existing hex-scroll `LaunchedEffect` and `HexView`'s `highlightRange` both prefer `selectedField` when present, falling back to the whole selected node's range otherwise: `tab.selectedField?.let { it.offset until (it.offset + it.length) } ?: tab.selected?.let { it.offset until (it.offset + it.size) }`.
- This is format-agnostic -- every `BoxField` (JPEG markers, PNG chunks, MP4 boxes, EXIF tags, etc.) already carries `offset`/`length`, so the click-to-locate behavior works everywhere `PropertyRow` is used, not only for EXIF data. Fields without meaningful offset/length data (defaulted to 0/0 by whichever parser produced them) simply produce a degenerate highlight rather than crashing -- acceptable, not a new failure mode.

## Error Handling

- Unmapped tags/values: unchanged, existing raw-value fallback (already crash-proof and already tested, see `unrecognized tag falls back to a hex label` in `ExifDecoderTest.kt`).
- Field click with `offset`/`length` of 0/0 (a field whose producer never set real position data): highlight range becomes `0 until 0`, `HexView` shows no visible highlight -- no crash, no misleading highlight.

## Testing

- `ExifDecoderTest.kt` gains cases for: several newly-added IFD0/Exif/GPS tag names resolving correctly, at least one DNG tag (e.g. `DNGVersion` at `0xC612`) resolving from the new table, an enum tag's label lookup (e.g. `Orientation` value `6` -> `"Rotate 90 CW"`), and a rational-formatter case (e.g. `FNumber` `28/10` -> `"f/2.8"`). Unmapped tags/values continue to hit the existing fallback tests unchanged.
- Field-level hex highlighting (Compose UI wiring) is not covered by automated tests, consistent with this project's existing convention for click/selection UI -- covered by code review and the controller's manual run.

## Non-Goals

- Camera-vendor MakerNote formats beyond the existing Samsung Type2 decoder (Canon, Nikon, Sony, Fujifilm, Apple, etc.) -- reverse-engineered proprietary formats, explicitly out of scope for this round.
- IPTC and ICC profile parsing -- not part of the TIFF/IFD tag space this design covers.
- Any change to `GridDisplay`, `EmbeddedTableView`, or `XmpFieldDisplay` -- field-level hex highlighting is scoped to the plain `PropertyRow`-rendered fields.
