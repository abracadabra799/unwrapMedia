# HEIF / HEIC / AVIF image conformance — design (phase 1 of 3)

Date: 2026-10-24. Status: approved in brainstorming. Item 2 of the standards-conformance roadmap.

## Goal

Content Check's **"컨테이너 포맷 규격"** row reports rule-by-rule conformance for item-based HEIF images (HEIC,
HEIF, AVIF) — today it shows only the existing structural observations (ftyp/meta/primary/grid/iloc), which are not
normative.

- **Phase 1 (this spec):** item structure — `ftyp` brands, `meta` boxes, items, item locations, properties and their
  associations, item references, derived items (grid / iovl / tmap), and the AVIF item rules.
- **Phase 2:** codec consistency — AVIF `av1C` ↔ Sequence Header OBU, `ispe` ↔ frame size, `pixi` ↔ bit depth,
  alpha auxiliary images (mono_chrome, color_range, bit depth); HEIC `hvcC` ↔ SPS.
- **Phase 3:** image sequences (`msf1`, `avis`; track-based) combined with the ISO-BMFF checker.

## Decisions

1. Severity as before: shall/required → FAIL, should/no keyword or unconfirmed strength → WARN, satisfied → PASS,
   not evaluable → NOT_CHECKED (never PASS), not applicable → NOT_APPLICABLE.
2. Sources:
   - HEIF/MIAF rules: `"ISO/IEC 23008-12 (공개 자료 기반, 원문 미대조)"` — the standard is paid; rules whose normative
     strength we cannot confirm are WARN, and rules we cannot ground at all are left out (e.g. we do **not** judge
     whether `ispe` may be marked essential, which real Samsung files do).
   - AVIF rules: `"AVIF v1.2.0 (AOMedia)"` with the exact section (free, verified 2026-10-24 against
     https://aomediacodec.github.io/av1-avif/).
3. Box-structure and `ftyp`-position rules come from the existing ISO-BMFF walker and rules (reused, not duplicated).
4. Display: Container Conformance row + grouped detail table (scope = group label), CLI `check`/`--case`
   `containerConformance`, AI prompt — the same plumbing as ISO-BMFF. The Structure row keeps today's checks.
5. Applies to files whose content is `ContainerFormat.HEIF` or `AVIF` (sniffer). Image sequences (`moov` present)
   still get phase-1 item rules for their `meta`; their tracks are phase 3.

## Rules

Groups (scope labels): `"ftyp"`, `"meta 구조 / meta structure"`, `"항목 / Items"`, `"항목 위치 / Item locations"`,
`"속성 / Properties"`, `"참조 / References"`, `"파생 항목 / Derived items"`, `"AVIF"`.

| ID | Rule | Level | Source |
|---|---|---|---|
| `heif.box.*` | Reuse ISO-BMFF `bmff.box.size`, `bmff.box.size0`, `bmff.box.trailing`, `bmff.ftyp.position` (ftyp first) | as ISO-BMFF | ISO-BMFF |
| `heif.ftyp.mif1` | `mif1` (or `msf1` when a `moov` image sequence exists) in compatible brands | WARN | HEIF |
| `heif.ftyp.codec_brand` | HEVC primary → `heic`/`heix`/`heim`/`heis` brand listed; AV1 primary → `avif` | WARN | HEIF |
| `avif.ftyp.miaf` | AVIF files list `miaf` | FAIL | AVIF §7 |
| `avif.ftyp.avif` | AVIF files list `avif` or `avis` | FAIL | AVIF §7 |
| `heif.meta.present` | Exactly one top-level `meta` (FullBox) | FAIL | HEIF |
| `heif.meta.required` | `meta` holds exactly one each of `hdlr` (handler `pict`), `pitm`, `iinf`, `iloc`; at most one `iprp`, `iref`, `idat`, `dinf` | FAIL | HEIF |
| `heif.items.ids` | `infe` versions 2 or 3; item_IDs unique and non-zero; `iinf` entry_count = number of `infe` | FAIL | HEIF |
| `heif.items.primary` | `pitm` names a declared item; the primary item is not hidden; it is an image item (coded or derived) | FAIL | HEIF |
| `heif.iloc.items` | Every coded image item and every non-derived metadata item has exactly one `iloc` entry; every `iloc` entry names a declared item | FAIL | HEIF |
| `heif.iloc.method` | construction_method 0 (file) or 1 (`idat`, requires `idat`); 2 (item) requires an `iref` `iloc` reference; `iloc` version 0 only uses method 0 | FAIL | HEIF |
| `heif.iloc.extents` | Every extent lies inside the file (method 0) or inside `idat` (method 1); extent_count ≥ 1 for items with data | FAIL | HEIF |
| `heif.ipma.refs` | `ipma` entries name declared items, strictly increasing item_IDs, each item at most once; every property_index ≤ `ipco` count (0 = none allowed) | FAIL | HEIF |
| `heif.props.ispe` | Every image item (coded and derived) has exactly one `ispe` | FAIL | HEIF |
| `heif.props.codec_config` | Coded image items have their decoder configuration: `hvc1`→`hvcC`, `av01`→`av1C` (AVIF §2.1), `j2k1`→`j2kH`, `vvc1`→`vvcC`, … unknown item types → NOT_APPLICABLE | FAIL | HEIF / AVIF §2.1 |
| `heif.props.essential` | Transformative properties (`irot`, `imir`, `clap`) are marked essential | WARN | HEIF |
| `heif.props.unique` | At most one of each of `ispe`, `pixi`, `irot`, `imir`, `clap`, `colr` (per colour_type) per item | WARN | HEIF |
| `heif.iref.targets` | Every `iref` from/to item_ID is declared; `thmb`/`auxl`/`cdsc` sources point at existing master items; an `auxl` source has an `auxC` property | FAIL | HEIF |
| `heif.derived.grid` | grid descriptor readable (version 0, flags → 16/32-bit sizes), `dimg` count = rows × columns, all tiles same `ispe`, rows·tile_height ≥ output height and columns·tile_width ≥ output width, grid `ispe` = output size | FAIL | HEIF |
| `heif.derived.inputs` | `iovl` / `tmap` / other derived items have `dimg` inputs that exist; `tmap` has exactly two inputs | FAIL | HEIF |
| `avif.primary` | Primary item is `av01` or a derived image whose inputs are `av01` | FAIL | AVIF §6.2 |
| `avif.props.av1c_essential` | `av1C` should be marked essential | WARN | AVIF §2.2.1 |
| `avif.props.pixi` | AV1 image items have `pixi` | FAIL | AVIF §9.1.1 (table) |
| `avif.props.pasp` | `pasp`, if present, is 1:1 | FAIL | AVIF §9.1.2 |
| `avif.props.a1op` | `a1op`, if associated, is essential | FAIL | AVIF §2.3.2.1.2 |
| `avif.props.a1lx` | `a1lx`, if associated, is not essential | FAIL | AVIF §2.3.2.3.2 |
| `avif.alpha.auxc` | An auxiliary item linked by `auxl` whose `auxC` names alpha uses `urn:mpeg:mpegB:cicp:systems:auxiliary:alpha` (or the HEVC alpha URN) | FAIL | AVIF §4.1 |

Codec-bitstream comparisons (sequence header, SPS, ispe vs frame size, pixi vs bit depth) are phase 2: phase 1 does
not open coded item data except the grid descriptor and the `tmap` metadata header count.

### Observed real files (2026-10-24, not committed except where noted)

- ffmpeg 8 `libsvtav1` AVIF (`avif mif1 miaf MA1B`; `ispe`, `pixi`, essential `av1C`, `colr`): expected clean.
- macOS `sips` HEIC (`heic`; compatible `mif1 MiPr miaf MiHB heic`; `dinf` in meta; `colr`/`irot`/`hvcC` essential):
  expected clean.
- Samsung Galaxy HEIC (major `heic`, compatible `mif1 heic tmap`, no `miaf`; 51 items: 40 hidden HEVC tiles,
  grid primary 41 (not hidden), thumbnail 42, hidden alpha/gain-map grid 47 via `auxl` with `auxC`, `tmap` 48 with
  inputs 41 + 47, Exif/XMP `cdsc` items 49–51; `iloc` v1 with `idat`; every image item has an essential `ispe`;
  trailing `free`/`mpvd`/`sefd` boxes): expected no FAIL. Unknown top-level `mpvd`/`sefd` are judged by the Google
  Motion Photo checker, not here.

## Architecture

- **New** `app/src/main/kotlin/com/multiviewer/parser/conformance/heif/`:
  - `HeifItemModel.kt` — reads `meta` (via the ISO-BMFF `BmffTree`) into items (id, type, version, hidden),
    `iloc` entries/extents, `ipco` properties (type, offset, size), `ipma` associations (index, essential), `iref`
    references, `pitm`; bounded reads, problems recorded instead of thrown.
  - `HeifRules.kt` — rule catalog (id, KO/EN title, reference, scope, source kind HEIF/AVIF).
  - `HeifConformanceChecker.kt` — `fun check(reader: ByteReader): ConformanceReport` (format "HEIF" or "AVIF").
- Reuses `IsoBmffBoxTree.walk` and the ISO-BMFF box-structure rule functions (expose them as internal functions
  rather than copying).
- Wiring: `isoBmffConformanceFor` (rename to `containerConformanceFor`) dispatches HEIF/AVIF content to the HEIF
  checker; everything downstream (window, CLI, case export, prompt) already handles any `ConformanceReport`.

## Testing

- Synthetic HEIF builder in tests (ftyp + meta with hdlr/pitm/iinf/iloc/iprp/iref/idat + mdat) to inject one
  violation per rule, in HEIC and AVIF flavours.
- Real fixtures (`docs/validation/heif-conformance-fixtures/generate.sh`): ffmpeg AVIF, `sips` HEIC (macOS only —
  the script skips it elsewhere and the committed file is used), plus corrupted variants (missing `ispe`, `pitm` to a
  hidden item, `ipma` index past `ipco`, `iloc` extent past EOF, grid with a missing tile, AVIF without `miaf`, AVIF
  `av1C` missing, `pasp` 2:1).
- Manual: several Samsung HEICs from `~/Downloads` (copies; motion photos included).
