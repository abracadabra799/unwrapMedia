# JPEG / JFIF / Exif / MPF conformance — design (phase 1)

Date: 2026-10-24. Status: implemented on `feat/jpeg-conformance` (2026-10-25; 2807 tests green; CLI check on the seven Samsung copies matches the table below); review decisions of 2026-10-25 applied. Item 3 of the standards-conformance
roadmap (after ISO-BMFF and HEIF/AVIF). Plan: `docs/superpowers/plans/2026-10-24-jpeg-exif-conformance.md`.

## Goal

Content Check's **"컨테이너 포맷 규격"** row reports rule-by-rule conformance for JPEG files (today it shows only the six
structural observations `jpeg.soi/frame/dqt/dht/eoi/trailing`, which stay in the Structure row). Every WARN/FAIL rule
carries a `SpecCitation` checked against the original PDF (physical page, quoted sentence below).

- **Phase 1 (this spec):** ITU-T T.81 interchange-format marker syntax, JFIF APP0 (T.871), Exif APP1 (CIPA DC-008-2026,
  Exif 3.1), MPF APP2 (CIPA DC-007-2026), and data after EOI.
- **Later:** XMP (APP1 `http://ns.adobe.com/xap/1.0/`), ICC (APP2), hierarchical mode, Exif inside MPF secondary images,
  uncompressed Exif thumbnails, MPF Extended files (MP Attribute IFD, panorama/disparity numbering, large-thumbnail
  pixel classes), entropy decoding.

## Decisions (binding, from the user)

1. Severity: a "shall"/"must"/Mandatory (M) or "not allowed" (N/J) violation → **FAIL**, even though most edited real
   photos break some of them; Recommended (R) / "should" → **WARN**. Never INFO. Not evaluable (truncated, unparsed,
   hierarchical) → `NOT_CHECKED` with the reason, never PASS; not applicable (no JFIF/Exif/MPF segment) →
   `NOT_APPLICABLE`.
2. Bytes after EOI: T.81 defines nothing there. PASS with explanatory details when every byte is explained by a known
   structure (MPF secondary image that resolved to SOI..EOI, ISO-BMFF video = Google Motion Photo, Samsung SEF trailer
   ending in `SEFT` that the existing `SefIntegrityAnalyzer` does not mark CRITICAL); otherwise WARN listing the
   unexplained ranges.
3. Rules derived from a definition (no "shall" sentence) carry `DEFINITIONAL_SUFFIX` in the citation section, like the
   codec catalogs. Their violations are still FAIL (the field definition is the format) except where listed as WARN.
4. Bilingual KO/EN titles and details, Material3 only (no UI code is added: the existing conformance table renders the
   report), no new dependencies, all file I/O off the UI thread (inside `containerConformanceFor`, already called from
   the structure stage's IO block).

## Sources (downloaded 2026-10-24 into the session scratchpad `cite/jpeg/`, read with `pdftotext -layout`)

`#page=N` = 1-based physical PDF page. These rows go into `docs/validation/spec-citations/README.md` (plan Task 8).

| Document | Edition | URL used in citations | sha256 |
|---|---|---|---|
| ITU-T (CCITT) Rec. T.81 = ISO/IEC 10918-1 | 09/1992, 186 pp ("Microsoft Word - T081E.DOC") | https://www.w3.org/Graphics/JPEG/itu-t81.pdf | 631031d4ba56b06abee3e312a0f235b9422da9c7267d1c8f7604418795768bf0 |
| ITU-T Rec. T.871 = ISO/IEC 10918-5 (JFIF) | 05/2011, 18 pp | https://www.itu.int/rec/dologin_pub.asp?lang=e&id=T-REC-T.871-201105-I!!PDF-E&type=items | ca3d749e7e04016f7695ba9d319eeeab3ad80c99265a883c14f03b9daf3e1c9a |
| CIPA DC-008-Translation-2026 (Exif 3.1) = JEITA CP-3451H | published January 2026, 267 pp | https://www.cipa.jp/std/documents/download_e.html?CIPA_DC-008-2026-E | 9cc36399a46ab7aa4a65473bb8a4945d3b044da739b8f16a01b0be11886dcff9 |
| CIPA DC-007-Translation-2026 (Multi-Picture Format) | August 2026, 65 pp | https://www.cipa.jp/std/documents/download_e.html?CIPA_DC-007-2026_E | fa03dcd8ac56ce173c5966cc5926c80ca3ff0e934e1f13dc3295627815f1b42e |

Notes:
- **T.81 is not a free download from itu.int** (the `dologin_pub` link returns the TIES login / shop page; the item is
  in the ITU shop). The W3C copy is the ITU/CCITT original PDF; its physical pages are used.
- **CIPA serves PDFs only by POST** (`dll.cgi`, `dlltarget=<id>`) after accepting its terms; no GET URL exists. The
  citation URL is the download page plus `#page=N` (the fragment documents the page of the downloaded PDF; the browser
  ignores it on the HTML page). Exif cites the current DC-008-2026 (Exif 3.1, user decision 2026-10-25); every exif.*
  page and quoted sentence was re-read in that PDF. The compressed-image M/R/N/J sets of Table 17-21 are identical in
  2023 (sha256 563e0ccb…), 2024 (0ae2ad60…) and 2026; Exif 3.1 adds seven Optional tags (LearningOptOutIn 0x9287,
  DevelopmentType 0xA40D, DevelopmentTypeDescription 0xA40E, Distortion/ChromaticAberration/Shading Correction and
  NoiseReduction 0xA40F-0xA412), added to the Type/Count table (160 tags).
- DC-007: the user named no edition; the current 2026 translation is used (it adds MP Type `050000` Gain map image,
  relevant for Ultra HDR / Samsung gain maps). Older editions downloaded for comparison: 2009 (sha256 169bd991…),
  2021 (91fd2e0d…), 2025 (69049863…).
- DC-007's physical page = printed page + 4; T.81 printed 31 = physical 35; DC-008-2026 pages are physical pages of the
  2026 PDF.
  All pages below are physical.

## Rules (39: 35 FAIL-level, 4 WARN-level)

Rule ids use the spec name as prefix (`t81.`, `jfif.`, `exif.`, `mpf.`) so they never collide with the Structure
row's `jpeg.*` observation ids. Scopes: `JPEG 마커 구문 / JPEG marker syntax (T.81)`, `JFIF APP0 (T.871)`,
`Exif APP1 (DC-008)`, `MPF APP2 (DC-007)`. "def." = section carries `DEFINITIONAL_SUFFIX`.

### T.81 (13 rules) — primary image SOI..EOI

| ID | Checks | Level | Citation (p.) | Quoted sentence |
|---|---|---|---|---|
| `t81.soi` | first marker is SOI | FAIL | B.2.1 (38) | "The top level of Figure B.2 specifies that the non-hierarchical interchange format shall begin with an SOI marker, shall contain one frame, and shall end with an EOI marker." |
| `t81.marker` | a 0xFF + byte ≠ 00/FF wherever a marker is required (fill bytes allowed) | FAIL | B.1.1.3 (35) | "All markers shall be assigned two-byte codes: a X'FF' byte followed by a second byte which is not equal to 0 or X'FF'." |
| `t81.marker.reserved` | TEM, JPG, JPGn, RES markers | WARN | B.1.1.3 Table B.1, def. (36) | Table B.1 "Reserved markers … X'FF02' through X'FFBF' RES Reserved" |
| `t81.segment.length` | length ≥ 2 and inside the file | FAIL | B.1.1.4, def. (37) | "This length parameter encodes the number of bytes in the marker segment, including the length parameter and excluding the two-byte marker." |
| `t81.frame.structure` | exactly one SOFn, ≥ 1 SOS after it, DNL only right after the first scan, Y=0 needs that DNL; hierarchical → NOT_CHECKED | FAIL | B.2.1 (39) | "The second level of Figure B.2 specifies that a frame shall begin with a frame header and shall contain one or more scans." … "If a DNL segment (see B.2.5) is present, it shall immediately follow the first scan." |
| `t81.frame.header` | Lf = 8+3Nf; P (baseline 8; ext./progr. 8,12; lossless 2-16); X ≥ 1; Nf (progr. ≤ 4); unique Ci; H,V 1-4; Tq 0-3 (lossless 0) | FAIL | B.2.2 Table B.2 (40) | "The value of Nf shall be equal to the number of sets of frame component specification parameters (Ci, Hi, Vi, and Tqi) present in the frame header." / "The value of Ci shall be different from the values of C1 through Ci − 1." |
| `t81.scan.header` | Ls = 6+2Ns; Ns 1-4; Cs ∈ frame, unique, frame order; ΣH×V ≤ 10; Td/Ta ranges; Ss/Se/Ah/Al per process; progressive AC scans not interleaved | FAIL | B.2.3 Table B.3 (41) | "Each Csj shall match one of the Ci values specified in the frame header, and the ordering in the scan header shall follow the ordering in the frame header." |
| `t81.dqt` | Pq 0/1, Tq 0-3, Pq = 0 when P = 8, no zero element, tables fit Lq | FAIL | B.2.4.1 Table B.4 (44) | "Pq shall be zero for 8 bit sample precision P (see B.2.2)." |
| `t81.dht` | Tc 0/1 (lossless 0), Th 0-3 (baseline 0-1), tables fit Lh | FAIL | B.2.4.2 Table B.5, def. (45) | "The size and allowed values of each parameter are given in Table B.5." |
| `t81.tables` | every Tq of a DCT frame and every needed DC/AC Huffman table (progressive: DC only for Ss=0,Ah=0; AC for Se>0) is defined before the SOS that uses it; arithmetic coding allows default conditioning | FAIL | B.2.1 (39) | "…this Specification hereby specifies that the interchange format shall contain all table-specification data necessary for decoding the compressed image." |
| `t81.restart` | DRI Lr=4; no RST when Ri=0; RSTm modulo-8 order; RST count = ⌈MCUs/Ri⌉−1 (DCT; lossless/unknown lines → note); no RST outside scan data | FAIL | B.2.1 (39) | "If restart is not enabled, there shall be only one entropy-coded segment (the one labeled "last"), and no restart markers shall be present. If restart is enabled, … a restart marker shall follow each entropy-coded segment except the last one." / "…each entropy-coded segment except the last one shall contain Ri MCUs." |
| `t81.eoi` | EOI reached (truncation / syntax error → FAIL; segment cap → NOT_CHECKED) | FAIL | B.2.1 (38) | same as `t81.soi` |
| `t81.trailing` | bytes after EOI explained (decision 2) | WARN | B.2.1, def. (38) | same as `t81.soi` ("shall end with an EOI marker") |

### JFIF (6 rules) — only when an APP0 starts with `JFIF\0`

| ID | Checks | Level | Citation (p.) | Quoted sentence |
|---|---|---|---|---|
| `jfif.position` | JFIF APP0 is the segment right after SOI | FAIL | 6.3 (9) | "The JFIF APP0 marker segment shall immediately follow the SOI marker." |
| `jfif.fields` | Lp = 16+3k; units 0-2; Hdensity, Vdensity ≠ 0 | FAIL | 10.1 (12) | Lp "Shall be equal to 16 + 3 * k, with k = HthumbnailA * VthumbnailA." / Hdensity "Must be non-zero." (T.871 p.4: "must" expresses requirements) |
| `jfif.version` | version = 0x0102; **1.00 / 1.01 → WARN** ("matches the earlier JFIF 1.0x edition", user decision 2026-10-25); any other value → FAIL | FAIL (1.00/1.01 WARN) | 10.1 (12) | "Files encoded according to this Recommendation \| International Standard shall use the value X'0102' (corresponding to version 1.02)." |
| `jfif.frame` | Nf 1 or 3, P = 8, component ids 1(,2,3) (§10.1 p.13) | FAIL | 6.1 (8) | "The encoded image in the JPEG File Interchange Format shall have 1 or 3 colour channels and 8 bits per colour channel." |
| `jfif.extension` | JFXX segments immediately follow the JFIF APP0; extension_code 0x10/0x11/0x13 (§10.2 p.13) | FAIL | 6.4 (9) | "If used, these segment(s) must immediately follow the JFIF APP0 marker segment." |
| `jfif.app0` | no second `JFIF` APP0; application APP0s start with a zero-terminated identifier | FAIL | 6.5 (9) | "Application-specific APP0 marker segments shall contain application data bytes such that the initial application data bytes contain a zero-terminated string value that identifies the application. For application-specific APP0 marker segments, this string shall not be the zero-terminated string values "JFIF" … or "JFXX"…" |

### Exif (11 rules, DC-008-2026 physical pages) — only when an APP1 starts with `Exif\0`; first such APP1 of the primary image

| ID | Checks | Level | Citation (p.) | Quoted sentence |
|---|---|---|---|---|
| `exif.app1.position` | Exif APP1 is the segment right after SOI | FAIL | 4.7.2.1 (116) | "APP1 shall be recorded immediately after the SOI marker indicating the start of the file (see Figure 7)." |
| `exif.tiff.header` | Exif ID `Exif`+00 00 (§4.7.2.3 p.116), II/MM, 42, 0th IFD offset inside the APP1 | FAIL | 4.5.2 Table 1 (31) | Byte Order: "Either "II" (4949.H) (little endian) or "MM" (4D4D.H) (big endian) depending on the CPU of the machine doing the recording shall be written." / 42: "002A.H (fixed)" |
| `exif.ifd.structure` | every IFD (0th, 1st, Exif, GPS, Interop) and every value inside the APP1; no loops/shared IFDs; no IFD after the 1st (§4.5.4 p.32 "a maximum of two IFD") | FAIL | 4.6.2, def. (38) | "The IFD used in this standard consists of a 2-byte count (number of fields), 12-byte field entry arrays, and 4-byte offset to the next IFD, in conformance with TIFF Rev. 6.0[17]." |
| `exif.ifd.order` | strictly ascending tags in every IFD | FAIL | 4.6.2 (39) | "Note that field entry shall be recorded in sequence starting from the smallest tag number." |
| `exif.tag.type` | Type ∈ {1,2,3,4,5,7,9,10,129}; 160 defined tags have their Type/Count (table copied from §4.6.3-4.6.8 of the 2026 PDF, each with its page) | FAIL | 4.6.2 + tag definitions, def. (38) | "The following types are used in Exif: 1 = BYTE … 129 = UTF-8"; per tag e.g. ImageUniqueID "Type = ASCII, Count = 33" (p.90) |
| `exif.tag.ascii` | ASCII/UTF-8 count includes the NULL; UTF-8 has no BOM | FAIL | 4.6.2 (38) | "The ASCII count shall include NULL." / UTF-8: "BOM (Byte Order Mark) shall not be used." (p.39) |
| `exif.tags.mandatory` | 0th: ExifIFDPointer, YCbCrPositioning; Exif: ExifVersion, ComponentsConfiguration, ColorSpace, PixelX/YDimension; GPS IFD with tags → GPSVersionID (§4.6.7.1.1 p.94 "This tag is mandatory when at least one tag related to the GPS is present."); compressed thumbnail → Compression, JPEGInterchangeFormat(Length) | FAIL | 4.6.9 Table 17-21 (104) | "M : Mandatory (shall be recorded)" |
| `exif.tags.not_allowed` | 0th IFD of a compressed primary: ImageWidth, ImageLength, BitsPerSample, Compression, PhotometricInterpretation, StripOffsets, SamplesPerPixel, RowsPerStrip, StripByteCounts, PlanarConfiguration, JPEGInterchangeFormat(Length), YCbCrSubSampling; 1st IFD of a compressed thumbnail: the Table 21 N/J set | FAIL | 4.6.9 Table 17-21 (105) | "N : It is not allowed to record" / "J : It is not allowed to record (included in JPEG marker)" |
| `exif.tags.recommended` | 0th: ImageDescription, Make, Model, Orientation, DateTime; Exif: ExposureTime, Flash, ExposureMode, WhiteBalance, SceneCaptureType, CompositeImage — one row listing every missing tag (user decision 2026-10-25) | WARN | 4.6.9 Table 17-21 (105) | "R : Recommended" |
| `exif.dimensions` | 1 ≤ PixelXDimension ≤ SOF X; PixelYDimension = SOF Y (or DNL) | FAIL | 4.6.6.3.1 (59) | "When a compressed file is recorded, the valid width of the meaningful image shall be recorded in this tag, whether or not there is padding data or a restart marker." (+ §4.8.1 p.129 "The SOF marker always has the same value as the effective image height tag.") |
| `exif.thumbnail` | compressed thumbnail inside the APP1, exactly SOI..EOI, SOF0, no APPn/COM/RST; uncompressed → NOT_CHECKED | FAIL | 4.5.8 (37) | "The images are recorded at the designated location as a JPEG stream (from SOI to EOI) conforming to the JPEG Baseline DCT format[7]. APPn marker, COM marker, nor restart marker shall not be recorded in the JPEG stream (see Figure 9)." |

Exif is checked only in the primary image: DC-007 §7 (p.40) says individual images other than large thumbnails
"should" follow Exif support levels, and phase 1 does not open secondary images.

### MPF (9 rules) — only when an APP2 starts with `MPF\0`; first individual image

| ID | Checks | Level | Citation (p.) | Quoted sentence |
|---|---|---|---|---|
| `mpf.count` | one MP Extensions APP2 | FAIL | 5.2.1, def. (15) | "There can be only one APP2 marker segment for MP Extensions in each Individual Image." |
| `mpf.header` | MP Endian `II*\0`/`MM\0*`, Offset to First IFD inside the APP2 | FAIL | 5.2.1 (14) | "Each APP2 marker segment of an Individual Image, including the First Individual Image, shall contain the following information in this order: APP2 marker, APP2 Field Length, MP Format Identifier, MP Endian, and Offset to First IFD." |
| `mpf.endian` | MP Endian = Exif TIFF byte order | WARN | 5.2.2.1 (15) | "The byte ordering of the MP Extensions should be the same as the TIFF header in the APP1 marker segment." |
| `mpf.index` | MPFVersion, NumberOfImages, MPEntry present with Table 3 Type/Count (ImageUIDList, TotalFrames when present); Baseline file: Offset to Next IFD = 0 | FAIL | 6.1 (34) | "The Baseline MP Primary Image shall contain MP Extensions. The MP Extensions includes at least the mandatory set of MP Index IFD tags shown in Table 6…" / "In the Baseline MP Primary Image, the Offset to Next IFD field in the MP Index IFD shall be NULL (00000000.H)." |
| `mpf.index.order` | tag ID order | FAIL | 5.2.3 (15) | "The MP Index IFD tags shall be specified in the order of their tag IDs." |
| `mpf.version` | MPFVersion = ASCII `0100` | FAIL | 5.2.3.1 (16) | "An MP File that complies with this specification shall specify a 4 byte ASCII value of "0100."" |
| `mpf.entries` | MPEntry Count = 16 × NumberOfImages; first entry Data Offset = 0 (§5.2.3.3.3 p.20 "shall be NULL(00000000.H)") | FAIL | 5.2.3.3 (17) | "The number of MP Entry tags shall coincide with the number of images contained in the MP File." |
| `mpf.images` | entry 1 size = primary SOI..EOI; entries ≥ 2: offset (from the MP Endian field) → SOI, offset+size inside the file and ending in EOI | FAIL | 5.2.3.3.2-3, def. (20) | "This field specifies the size of the image data between the SOI and EOI markers of an Individual Image." / "This field specifies the data offset to the beginning (i.e. SOI marker) of an Individual Image." |
| `mpf.type` | Image Data Format 0; Type in Table 4 (or xy000z); Baseline file (entry 1 = 030000) must not use 020001-3 / 000000; Extended file must not use 030000/040000/050000/xy000z | FAIL | 5.2.3.3.1 Table 4 (19) | Table 4: "×：Shall not be used" (+ p.20: "MP Format only supports the MP Type Codes listed in Table 4. All other codes are restricted.") |

## Expected outcome on real files (measured 2026-10-24 with the prototype checker on copies; nothing committed)

**Every real Samsung Galaxy camera JPEG FAILs** under the strict rules. Seven copies from `~/Downloads`
(`20261012_223627`, `20260718_200431`, `20260718_200439_motion`, S26 `20261012_131827`, `er/…225552`,
`attachments/…230611`, `mp_2`) all show:

| Rule | Status | Cause |
|---|---|---|
| `exif.tags.mandatory` | FAIL | Exif IFD has no ComponentsConfiguration; GPS IFD has GPS tags but no GPSVersionID |
| `exif.tags.not_allowed` | FAIL | ImageWidth/ImageLength in the 0th IFD and in the 1st (thumbnail) IFD |
| `exif.tag.type` | FAIL | ImageUniqueID Count 12 (spec: 33-char UUID); S26 and 223627 also GPSAltitudeRef as SHORT (spec BYTE) |
| `exif.thumbnail` | FAIL | the Exif thumbnail contains an APP2 and restart markers (DRI, 143-191 RSTs) |
| `mpf.type` | FAIL | gain-map MP Entry has Type 0x000000 (Undefined), "×" in a Baseline MP file; DC-007-2026 defines 0x050000 for gain maps |
| `exif.tags.recommended` | WARN | ImageDescription, CompositeImage missing |
| `mpf.images` | FAIL on 4 of 7 | **S26 (unedited device file): MP Entry #1 size 3,999,489 ≠ primary 4,013,983 — exactly the 14,494-byte APP11 (C2PA JUMBF) inserted after MPF was written.** `mp_2`/`_motion`/`1.jpg`: off by 654-728 bytes after the XMP APP1 changed size |
| `exif.app1.position` | FAIL on `1.jpg` | XMP APP1 placed before the Exif APP1 |
| `t81.*`, `jfif.*`, `mpf.header/index/version/entries/endian` | PASS / N/A | restart count matches (Ri 250, 35,250 MCUs → 140 RST), SEF + MPF explain all trailing data |
| `t81.trailing` | PASS, WARN on `1.jpg` | `1.jpg` has 217 unexplained bytes (a SEF-like fragment without `SEFT`) between the gain map and an appended MP4 |

Other files: ffmpeg 8.1.2 default output → PASS (no WARN). libjpeg-turbo 3.2.0 `cjpeg` default/progressive/restart
→ overall WARN, only `jfif.version` (writes JFIF 1.01). macOS `sips` (ImageIO) re-encode → WARN `jfif.version`,
FAIL `exif.app1.position` (JFIF APP0 first, Exif second — a file with both cannot satisfy T.871 §6.3 and DC-008 §4.7.2.1
at once), `exif.tags.mandatory` (no YCbCrPositioning/ExifVersion/ComponentsConfiguration), WARN
`exif.tags.recommended`. Web JPEG `mangu.jpg` (JFIF 1.01, progressive) → overall WARN. Photoshop export (`fig2d.jpg`) → FAIL `exif.tags.mandatory`, `exif.thumbnail` (APP2/13/14
and RSTs in the thumbnail). **iPhone / Pixel camera JPEGs: none available locally — not measured**; expect at least
`mpf.type` FAIL on Ultra HDR files whose gain map is typed 0x000000.

### Implementation check (2026-10-25, committed code via `./gradlew :app:run --args="check <copy>"`)

All seven Samsung copies report `containerConformance.format == "JPEG"` with no `inspectionError`, and the FAIL/WARN sets
equal the table above (no JFIF rows: Samsung writes no JFIF APP0). Difference: `mpf.images` FAILs on 3 of the 7 named
copies (S26 -14494, `20260718_200439_motion` +728, `mp_2` +726); the "4 of 7" count in the table included `1.jpg`,
which is not one of the seven. GUI click-through (Content Check, 컨테이너 포맷 규격 row) was not run.

## Architecture

New package `app/src/main/kotlin/com/multiviewer/parser/conformance/jpeg/`:

- `JpegRules.kt` — catalog (`citedRule`, verified citations, `WARN_RULES`, `LEGACY_JFIF_VERSIONS` 0x0100/0x0101).
- `JpegStream.kt` — `JpegStreamScanner.scan(reader, start, limit)`: bounded marker walk (fill bytes, standalone
  markers, length checks, 100,000-segment cap) and entropy-data scan in 64 KiB chunks counting RSTm (order checked)
  and stopping at the first other marker; `JpegHeaders` parses SOF/SOS/DQT/DHT/DRI/DNL inside their segments.
  Independent of `JpegWalker` (the display tree), which skips RSTs and merges trailers.
- `JpegContext.kt` — shared state (stream, first frame, lines incl. DNL, payload cache, Exif APP1 + `ExifTiff`).
- `JpegT81Rules.kt`, `JpegJfifRules.kt`, `ExifTiff.kt` (in-memory TIFF reader for APP1/APP2 ≤ 64 KiB + `ExifTagTable`),
  `JpegExifRules.kt`, `JpegMpfRules.kt` (returns resolved secondary-image ranges), `JpegTrailer.kt` (reuses
  `motionphoto.walkBoxes` and `SefIntegrityAnalyzer`), `JpegConformanceChecker.kt`
  (`check(reader): ConformanceReport`, format `"JPEG"`, findings in catalog order, exceptions other than
  `CancellationException` → `inspectionError`).
- Wiring: `containerConformanceFor` (`ui/ContentCheckPlan.kt`) adds `ContainerFormat.JPEG → JpegConformanceChecker`
  (content-sniffed, so a JPEG named `.heic` is still checked as JPEG). Content Check, CLI `check` / `--case`
  (`containerConformance`) and the AI prompt (`출처:` lines) need no other change: they already render any
  `ConformanceReport` with citations. The Structure row keeps `JpegIntegrity`'s observations.

## Testing

- Synthetic builder (`SyntheticJpeg.kt`) + unit tests per rule group.
- Real fixtures (`docs/validation/jpeg-conformance-fixtures/{generate.sh,build.py}` → `app/src/test/resources/jpeg-conformance/`,
  26 files, 0.5-4 KB): ffmpeg default (default-settings fixture, PASS), cjpeg default/progressive/restart, sips;
  `exif-mpf.jpg` = ffmpeg image data + a spec-complete Exif APP1 (all M and R tags, ExifVersion 0300, ffmpeg
  thumbnail without APPn/COM) + Baseline MPF APP2 (primary `A0030000` + large thumbnail class 1 `40010001`, ffmpeg
  32x24 image) written by `build.py` because exiftool is not installed; variants each FAIL exactly their rule
  (listed in the plan with verified byte offsets).
- Manual: the Samsung copies above via `./gradlew -q :app:run --args="check <copy>"`; results must match the table.

## Review decisions (2026-10-25, applied)

1. `jfif.version`: 1.00 and 1.01 → WARN citing the same T.871 §10.1 sentence, detail "matches the earlier JFIF 1.0x
   edition"; every other value ≠ 0x0102 stays FAIL.
2. Exif citations use the latest edition, CIPA DC-008-2026 (Exif 3.1); all exif.* pages and quotes re-verified.
3. `exif.tags.recommended` stays WARN, reported as one row listing every missing R tag name.
4. DC-007-2026 confirmed.
