# JPEG / JFIF / Exif / MPF Conformance (phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Content Check's Container Conformance row checks JPEG files rule by rule against ITU-T T.81, T.871 (JFIF),
CIPA DC-008-2026 (Exif 3.1) and DC-007-2026 (MPF); every rule carries a verified page citation.

**Architecture:** New package `com.multiviewer.parser.conformance.jpeg` (catalog, bounded marker/entropy scanner,
rule groups, checker). `containerConformanceFor` dispatches `ContainerFormat.JPEG` to `JpegConformanceChecker`; the
existing report/CLI/JSON/prompt plumbing renders it unchanged.

**Tech Stack:** Kotlin, JDK 21, kotlin.test; ffmpeg 8.1.2, libjpeg-turbo 3.2.0 `cjpeg`, macOS `sips`, python3 for fixtures.

Spec: `docs/superpowers/specs/2026-10-24-jpeg-exif-conformance-design.md` (rule table, quotes and pages = authoritative).

**Pre-validated:** every code block below was compiled and run on this branch before the plan was written
(`./gradlew :app:test` → 2806+ tests green, the 25 new JPEG tests included), then removed so the branch holds documents
only. Implement it as written; if a step's RED fails for a different reason than "unresolved reference / assertion",
stop and report.

## Global Constraints

- Work in `.worktrees/jpeg-conformance` on `feat/jpeg-conformance`; run `git branch --show-current` before every commit
  (subagents have committed into the main repo before). Commit trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Status mapping: violated rule → FAIL, except `JpegRules.WARN_RULES` (`t81.marker.reserved`, `t81.trailing`,
  `exif.tags.recommended`, `mpf.endian`) → WARN; satisfied → PASS; not evaluable → NOT_CHECKED with the reason; no
  JFIF/Exif/MPF segment → NOT_APPLICABLE. Never INFO, never a PASS without evaluation. One exception inside a FAIL-level
  rule (user decision 2026-10-25): `jfif.version` 1.00 / 1.01 (`JpegRules.LEGACY_JFIF_VERSIONS`) → WARN with "matches the
  earlier JFIF 1.0x edition"; any other value ≠ 1.02 → FAIL. `exif.tags.recommended` reports one row listing every
  missing R tag.
- Never throw out of the checker except `CancellationException`; all reads bounded (segment payloads ≤ 64 KiB, entropy
  data in 64 KiB chunks, ≤ 100,000 segments, ≤ 10,000 MP entries, after-EOI `ftyp` search ≤ 4 MiB).
- Kotlin string templates: a `$name` directly followed by Hangul is parsed as one identifier — always write `${name}개`.
- Never modify or commit real camera files from `~/Downloads`; copy to a temp dir for manual checks.
- Titles `"한국어 / English"`, details bilingual; no UI code changes (Material3 tables already render findings).

## File map

| File | Task |
|---|---|
| `parser/conformance/jpeg/JpegRules.kt` | 1 |
| `docs/validation/jpeg-conformance-fixtures/{generate.sh,build.py}`, `app/src/test/resources/jpeg-conformance/*.jpg` | 2 |
| `parser/conformance/jpeg/JpegStream.kt`, test `SyntheticJpeg.kt`, `JpegStreamScannerTest.kt` | 3 |
| `parser/conformance/jpeg/{JpegContext,JpegT81Rules,JpegConformanceChecker}.kt`, test `JpegT81RulesTest.kt` | 4 |
| `parser/conformance/jpeg/JpegJfifRules.kt`, test `JpegJfifRulesTest.kt` | 5 |
| `parser/conformance/jpeg/{ExifTiff,JpegExifRules}.kt` | 6 |
| `parser/conformance/jpeg/{JpegMpfRules,JpegTrailer}.kt` | 7 |
| `ui/ContentCheckPlan.kt`, tests `JpegFixtureTest.kt`, `CheckFileTest.kt`, `ContentCheckPlanTest.kt`, `docs/validation/spec-citations/README.md`, spec sync | 8 |

(`parser/…` = `app/src/main/kotlin/com/multiviewer/parser/…`; tests under `app/src/test/kotlin/com/multiviewer/…`.)

---

### Task 1: Rule catalog with verified citations

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/jpeg/JpegRules.kt`, test
`app/src/test/kotlin/com/multiviewer/parser/conformance/jpeg/JpegRulesTest.kt`.

- [ ] **Step 1: Failing test**

```kotlin
package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.conformance.DEFINITIONAL_SUFFIX
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JpegRulesTest {
    @Test fun `every rule is cited to a verified PDF page`() {
        assertEquals(39, JpegRules.all.size)
        assertEquals(JpegRules.all.size, JpegRules.all.map { it.id }.toSet().size)
        JpegRules.all.forEach { r ->
            val c = r.citation!!
            assertTrue(c.verified, r.id)
            assertEquals("#page=${c.page}", "#" + c.url.substringAfter("#"), r.id)
            assertEquals(c.url, r.reference, r.id)
            assertTrue(r.title.contains(" / "), r.id)
        }
        assertEquals(setOf("t81.marker.reserved", "t81.trailing", "exif.tags.recommended", "mpf.endian"), JpegRules.WARN_RULES)
        assertEquals(13, JpegRules.all.count { it.id.startsWith("t81.") })
        assertEquals(6, JpegRules.all.count { it.id.startsWith("jfif.") })
        assertEquals(11, JpegRules.all.count { it.id.startsWith("exif.") })
        assertEquals(9, JpegRules.all.count { it.id.startsWith("mpf.") })
        assertEquals(setOf("t81.marker.reserved", "t81.segment.length", "t81.dht", "t81.trailing", "exif.ifd.structure", "exif.tag.type", "mpf.count", "mpf.images"),
            JpegRules.all.filter { it.citation!!.section.endsWith(DEFINITIONAL_SUFFIX) }.map { it.id }.toSet())
    }
}
```

- [ ] **Step 2:** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.jpeg.JpegRulesTest'` → RED (unresolved `JpegRules`).
- [ ] **Step 3: Implement** (sections/pages are the spec table's; do not change a page without re-reading the PDF):

```kotlin
package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.conformance.ConformanceRule
import com.multiviewer.parser.conformance.DEFINITIONAL_SUFFIX
import com.multiviewer.parser.conformance.citedRule

/**
 * JPEG / JFIF / Exif / MPF rule catalog. Every rule was checked against the original PDF: [ConformanceRule.citation]
 * carries the physical PDF page (`#page=N`, 1-based) and the quoted sentence is listed in
 * docs/superpowers/specs/2026-10-24-jpeg-exif-conformance-design.md.
 */
object JpegRules {
    /** CCITT Rec. T.81 (09/1992), the ITU original as hosted by W3C (itu.int sells T.81; it is not a free download). */
    const val T81_DOCUMENT = "ITU-T T.81 (09/92)"
    const val T81_URL = "https://www.w3.org/Graphics/JPEG/itu-t81.pdf"
    const val T871_DOCUMENT = "ITU-T T.871 (05/2011)"
    const val T871_URL = "https://www.itu.int/rec/dologin_pub.asp?lang=e&id=T-REC-T.871-201105-I!!PDF-E&type=items"
    /** CIPA serves the PDF only by POST after accepting its terms: the link opens the download page; the page is of that PDF. */
    const val EXIF_DOCUMENT = "CIPA DC-008-Translation-2026 (Exif 3.1)"
    const val EXIF_URL = "https://www.cipa.jp/std/documents/download_e.html?CIPA_DC-008-2026-E"
    const val MPF_DOCUMENT = "CIPA DC-007-Translation-2026 (MPF)"
    const val MPF_URL = "https://www.cipa.jp/std/documents/download_e.html?CIPA_DC-007-2026_E"

    const val SCOPE_T81 = "JPEG 마커 구문 / JPEG marker syntax (T.81)"
    const val SCOPE_JFIF = "JFIF APP0 (T.871)"
    const val SCOPE_EXIF = "Exif APP1 (DC-008)"
    const val SCOPE_MPF = "MPF APP2 (DC-007)"

    private class Def(val id: String, val ko: String, val en: String, val doc: String, val base: String,
                      val section: String, val page: Int, val scope: String)

    private fun t81(id: String, ko: String, en: String, section: String, page: Int) = Def(id, ko, en, T81_DOCUMENT, T81_URL, section, page, SCOPE_T81)
    private fun jfif(id: String, ko: String, en: String, section: String, page: Int) = Def(id, ko, en, T871_DOCUMENT, T871_URL, section, page, SCOPE_JFIF)
    private fun exif(id: String, ko: String, en: String, section: String, page: Int) = Def(id, ko, en, EXIF_DOCUMENT, EXIF_URL, section, page, SCOPE_EXIF)
    private fun mpf(id: String, ko: String, en: String, section: String, page: Int) = Def(id, ko, en, MPF_DOCUMENT, MPF_URL, section, page, SCOPE_MPF)

    private val defs = listOf(
        t81("t81.soi", "SOI로 시작", "Starts with SOI", "B.2.1 High-level syntax", 38),
        t81("t81.marker", "마커 코드", "Marker codes", "B.1.1.3 Marker assignments", 35),
        t81("t81.marker.reserved", "예약 마커", "Reserved markers", "B.1.1.3 Marker assignments, Table B.1$DEFINITIONAL_SUFFIX", 36),
        t81("t81.segment.length", "세그먼트 길이", "Marker segment length", "B.1.1.4 Marker segments$DEFINITIONAL_SUFFIX", 37),
        t81("t81.frame.structure", "프레임·스캔 구성", "Frame and scan structure", "B.2.1 High-level syntax", 39),
        t81("t81.frame.header", "프레임 헤더", "Frame header (SOFn)", "B.2.2 Frame header syntax, Table B.2", 40),
        t81("t81.scan.header", "스캔 헤더", "Scan header (SOS)", "B.2.3 Scan header syntax, Table B.3", 41),
        t81("t81.dqt", "양자화 테이블 정의", "Quantization table specification (DQT)", "B.2.4.1 Quantization table-specification syntax, Table B.4", 44),
        t81("t81.dht", "허프만 테이블 정의", "Huffman table specification (DHT)", "B.2.4.2 Huffman table-specification syntax, Table B.5$DEFINITIONAL_SUFFIX", 45),
        t81("t81.tables", "테이블 참조", "Table references", "B.2.1 High-level syntax", 39),
        t81("t81.restart", "재시작 간격·마커", "Restart interval and markers", "B.2.1 High-level syntax", 39),
        t81("t81.eoi", "EOI로 끝남", "Ends with EOI", "B.2.1 High-level syntax", 38),
        t81("t81.trailing", "EOI 이후 데이터", "Data after EOI", "B.2.1 High-level syntax$DEFINITIONAL_SUFFIX", 38),

        jfif("jfif.position", "JFIF APP0 위치", "JFIF APP0 position", "6.3 JFIF APP0 marker segment", 9),
        jfif("jfif.fields", "JFIF APP0 필드", "JFIF APP0 fields", "10.1 JFIF file syntax", 12),
        jfif("jfif.version", "JFIF 버전", "JFIF version", "10.1 JFIF file syntax (version)", 12),
        jfif("jfif.frame", "JFIF 프레임 제약", "JFIF frame constraints", "6.1 JPEG compression", 8),
        jfif("jfif.extension", "JFIF 확장 APP0", "JFIF extension APP0 (JFXX)", "6.4 APP0 marker used to specify JFIF extensions", 9),
        jfif("jfif.app0", "응용 APP0 식별자", "Application-specific APP0 identifiers", "6.5 Application marker segments used for application-specific information", 9),

        exif("exif.app1.position", "Exif APP1 위치", "Exif APP1 position", "4.7.2.1 Order of APP1 recording", 116),
        exif("exif.tiff.header", "TIFF 헤더", "TIFF header", "4.5.2 Table 1 TIFF Headers", 31),
        exif("exif.ifd.structure", "IFD 구조·오프셋", "IFD structure and offsets", "4.6.2 IFD Structure$DEFINITIONAL_SUFFIX", 38),
        exif("exif.ifd.order", "태그 오름차순", "Tags in ascending order", "4.6.2 IFD Structure", 39),
        exif("exif.tag.type", "태그 Type·Count", "Tag Type and Count", "4.6.2 IFD Structure (Type) / 4.6.3-4.6.8 tag definitions$DEFINITIONAL_SUFFIX", 38),
        exif("exif.tag.ascii", "ASCII·UTF-8 값", "ASCII and UTF-8 values", "4.6.2 IFD Structure (Type)", 38),
        exif("exif.tags.mandatory", "필수 태그", "Mandatory tags", "4.6.9 Tag Support Levels, Table 17-21 (M)", 104),
        exif("exif.tags.not_allowed", "기록 금지 태그", "Tags not allowed", "4.6.9 Tag Support Levels, Table 17-21 (N, J)", 105),
        exif("exif.tags.recommended", "권장 태그", "Recommended tags", "4.6.9 Tag Support Levels, Table 17-21 (R)", 105),
        exif("exif.dimensions", "유효 이미지 크기", "Valid image dimensions", "4.6.6.3.1 PixelXDimension", 59),
        exif("exif.thumbnail", "압축 썸네일", "Compressed thumbnail", "4.5.8 Basic Structure of Thumbnail Data", 37),

        mpf("mpf.count", "MP 확장 APP2 개수", "One MP Extensions APP2", "5.2.1 The MP Format Identifier$DEFINITIONAL_SUFFIX", 15),
        mpf("mpf.header", "MP 헤더", "MP header", "5.2.1 The MP Format Identifier", 14),
        mpf("mpf.endian", "MP 바이트 순서", "MP byte order", "5.2.2.1 MP Endian", 15),
        mpf("mpf.index", "MP Index IFD 필수 태그", "MP Index IFD mandatory tags", "6.1 Baseline MP File (Table 6)", 34),
        mpf("mpf.index.order", "MP Index IFD 태그 순서", "MP Index IFD tag order", "5.2.3 MP Index IFD", 15),
        mpf("mpf.version", "MP 형식 버전", "MP Format Version", "5.2.3.1 MP Format Version", 16),
        mpf("mpf.entries", "MP Entry 개수·첫 오프셋", "MP Entry count and first offset", "5.2.3.3 MP Entry", 17),
        mpf("mpf.images", "개별 이미지 위치", "Individual image locations", "5.2.3.3.2-5.2.3.3.3 Individual Image Size / Data Offset$DEFINITIONAL_SUFFIX", 20),
        mpf("mpf.type", "MP 유형 코드", "MP Type Code", "5.2.3.3.1 Individual Image Attribute, Table 4 MP Types", 19),
    )

    val all: List<ConformanceRule> = defs.map { d ->
        citedRule(d.id, "${d.ko} / ${d.en}", d.scope, d.doc, d.section, d.page, "${d.base}#page=${d.page}")
    }

    /** jfif.version is FAIL-level but reports the earlier JFIF editions 1.00 / 1.01 as WARN (user decision 2026-10-25). */
    val LEGACY_JFIF_VERSIONS = setOf(0x0100, 0x0101)

    /** Rules whose violation is WARN (recommendation / unexplained data); every other rule FAILs. */
    val WARN_RULES = setOf("t81.marker.reserved", "t81.trailing", "exif.tags.recommended", "mpf.endian")

    private val byId = all.associateBy { it.id }

    fun rule(id: String): ConformanceRule = byId.getValue(id)
}
```

- [ ] **Step 4:** test GREEN. **Step 5:** commit `feat: add JPEG/JFIF/Exif/MPF conformance rule catalog`.

---

### Task 2: Real-file fixtures (before any rule code — AGENTS.md rules 1-2)

**Files:** Create `docs/validation/jpeg-conformance-fixtures/generate.sh` (executable), `.../build.py`; run it to create
`app/src/test/resources/jpeg-conformance/` (26 files, commit them all).

- [ ] **Step 1:** write `generate.sh`:

```bash
#!/usr/bin/env bash
# Regenerates the JPEG conformance fixtures used by JpegFixtureTest. Default encoder settings (AGENTS.md rule 2);
# -fflags +bitexact keeps the ffmpeg output byte-stable.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here/../../../app/src/test/resources/jpeg-conformance}"
mkdir -p "$out"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }

ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 -fflags +bitexact "$out/ffmpeg-default.jpg"
ff -f lavfi -i testsrc2=s=16x12 -frames:v 1 -fflags +bitexact "$out/ffmpeg-thumb.jpg"
ff -f lavfi -i testsrc2=s=32x24 -frames:v 1 -fflags +bitexact "$out/ffmpeg-second.jpg"
ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 "$tmp/src.ppm"
ff -f lavfi -i testsrc2=s=32x24 -frames:v 1 "$tmp/thumb.ppm"
ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 "$tmp/src.png"
cjpeg -outfile "$out/cjpeg-default.jpg" "$tmp/src.ppm"
cjpeg -progressive -outfile "$out/cjpeg-progressive.jpg" "$tmp/src.ppm"
cjpeg -restart 1 -outfile "$out/cjpeg-restart.jpg" "$tmp/src.ppm"
cjpeg -restart 1B -outfile "$out/cjpeg-restart-thumb.jpg" "$tmp/thumb.ppm"
if command -v sips >/dev/null 2>&1; then
  sips -s format jpeg "$tmp/src.png" --out "$out/sips.jpg" >/dev/null
else
  echo "sips not found (macOS only): keeping the committed sips.jpg" >&2
fi
python3 -I "$here/build.py" "$out"
ls -l "$out"
```

- [ ] **Step 2:** write `build.py` (Exif/MPF writer + byte patches; offsets are found by walking markers, never hard-coded):

```python
"""Builds the Exif/MPF fixture and the byte-patched variants for JpegFixtureTest.

Usage: python3 -I build.py <out dir>   (needs ffmpeg-default.jpg, ffmpeg-thumb.jpg, ffmpeg-second.jpg, cjpeg-restart.jpg,
cjpeg-restart-thumb.jpg in <out dir>)

ffmpeg writes no Exif and exiftool is not installed, so the Exif APP1 and MPF APP2 are written here from CIPA DC-008-2026
(Exif 3.1; ExifVersion "0300" kept, every M and R tag of the compressed-image column) and DC-007-2026 (Baseline MP file: Baseline MP Primary Image
+ one Large Thumbnail class 1). All image data comes from ffmpeg / cjpeg output unchanged.
"""
import struct
import sys
from pathlib import Path

out = Path(sys.argv[1])


def segments(b):
    """(marker, offset, total length incl. marker) of every marker segment before SOS, then the rest."""
    assert b[:2] == b'\xff\xd8'
    pos, segs = 2, []
    while True:
        m = b[pos + 1]
        if m == 0xDA:
            return segs, pos
        n = struct.unpack('>H', b[pos + 2:pos + 4])[0]
        segs.append((m, pos, 2 + n))
        pos += 2 + n


def strip_app_com(b):
    """SOI + every non-APPn/non-COM segment + scan..EOI (a JPEG stream without APPn/COM)."""
    segs, sos = segments(b)
    body = b''.join(b[o:o + n] for m, o, n in segs if not (0xE0 <= m <= 0xEF or m == 0xFE))
    return b'\xff\xd8' + body + b[sos:]


def seg(marker, payload):
    return bytes([0xFF, marker]) + struct.pack('>H', len(payload) + 2) + payload


# ---- little-endian TIFF writer -------------------------------------------------------------------------------
SIZES = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 9: 4, 10: 8, 129: 1}


def ascii_(s, nul=True):
    return (2, s.encode('ascii') + (b'\0' if nul else b''))


def short(*v): return (3, b''.join(struct.pack('<H', x) for x in v))
def long_(*v): return (4, b''.join(struct.pack('<I', x) for x in v))
def rational(n, d): return (5, struct.pack('<II', n, d))
def undef(b): return (7, b)


def ifd_bytes(entries, start, next_off, value_start):
    """entries: list of (tag, (type, data)) already in the wanted order. Returns (ifd, values)."""
    ifd = struct.pack('<H', len(entries))
    values = b''
    for tag, (typ, data) in entries:
        count = len(data) // SIZES[typ]
        if len(data) <= 4:
            field = data.ljust(4, b'\0')
        else:
            field = struct.pack('<I', value_start + len(values))
            values += data + (b'\0' if len(data) % 2 else b'')
        ifd += struct.pack('<HHI', tag, typ, count) + field
    ifd += struct.pack('<I', next_off)
    return ifd, values


def exif_app1(thumb, o):
    """Exif APP1 payload ('Exif\\0\\0' + TIFF). o: dict of variant switches."""
    ifd0 = [(0x010E, ascii_('unwrapMedia fixture')), (0x010F, ascii_('unwrapMedia', nul=not o.get('make_no_nul'))),
            (0x0110, ascii_('fixture')), (0x0112, short(1)), (0x011A, rational(72, 1)), (0x011B, rational(72, 1)),
            (0x0128, short(2)), (0x0132, ascii_('2026:10:24 12:00:00')), (0x0213, short(1)), (0x8769, long_(0))]
    if o.get('image_width'):
        ifd0.insert(0, (0x0100, short(64)))
    if o.get('unsorted'):
        ifd0[1], ifd0[2] = ifd0[2], ifd0[1]
    exif = [(0x829A, rational(1, 100)), (0x9000, undef(b'0300')), (0x9101, undef(b'\x01\x02\x03\x00')), (0x9209, short(0)),
            (0xA001, short(1)), (0xA002, short(64)), (0xA003, short(48)), (0xA402, short(0)), (0xA403, short(0)),
            (0xA406, short(0)), (0xA460, short(1))]
    if o.get('no_colorspace'):
        exif = [e for e in exif if e[0] != 0xA001]
    ifd1 = [(0x0103, short(6)), (0x0201, long_(0)), (0x0202, long_(len(thumb)))]
    # layout: header(8) | IFD0 | values0 | ExifIFD | valuesE | IFD1 | values1 | thumbnail
    def size(entries):
        ifd, vals = ifd_bytes(entries, 0, 0, 0)
        return len(ifd), len(vals)
    i0, v0 = size(ifd0)
    ie, ve = size(exif)
    i1, v1 = size(ifd1)
    off0 = 8
    offe = off0 + i0 + v0
    off1 = offe + ie + ve
    offt = off1 + i1 + v1
    ifd0 = [(t, long_(offe)) if t == 0x8769 else (t, v) for t, v in ifd0]
    ifd1 = [(t, long_(offt)) if t == 0x0201 else (t, v) for t, v in ifd1]
    next0 = off0 if o.get('ifd_loop') else off1
    b0, vals0 = ifd_bytes(ifd0, off0, next0, off0 + i0)
    be, valse = ifd_bytes(exif, offe, 0, offe + ie)
    b1, vals1 = ifd_bytes(ifd1, off1, 0, off1 + i1)
    tiff = b'II*\0' + struct.pack('<I', off0) + b0 + vals0 + be + valse + b1 + vals1 + thumb
    assert len(tiff) == offt + len(thumb)
    return b'Exif\0\0' + tiff


def mpf_app2(primary_size, second_rel, second_size, o):
    entries = struct.pack('<IIIHH', 0xA0030000, primary_size + o.get('size_delta', 0), 0, 2, 0)
    entries += struct.pack('<IIIHH', 0x40000000 | o.get('type2', 0x010001), second_size, second_rel, 0, 0)
    ifd_len = 2 + 3 * 12 + 4
    ifd = struct.pack('<H', 3)
    ifd += struct.pack('<HHI', 0xB000, 7, 4) + b'0100'
    ifd += struct.pack('<HHII', 0xB001, 4, 1, 2)
    ifd += struct.pack('<HHII', 0xB002, 7, 32, 8 + ifd_len)
    ifd += struct.pack('<I', 0)
    return b'MPF\0' + b'II*\0' + struct.pack('<I', 8) + ifd + entries


def build(o):
    primary = strip_app_com((out / 'ffmpeg-default.jpg').read_bytes())
    thumb_src = 'cjpeg-restart-thumb.jpg' if o.get('thumb_rst') else 'ffmpeg-thumb.jpg'
    thumb = strip_app_com((out / thumb_src).read_bytes())
    second = strip_app_com((out / 'ffmpeg-second.jpg').read_bytes())
    app1 = seg(0xE1, exif_app1(thumb, o))
    app2_probe = seg(0xE2, mpf_app2(0, 0, 0, o))
    primary_size = len(primary) + len(app1) + len(app2_probe)
    base = 2 + len(app1) + 8  # offset of the MP Endian field
    app2 = seg(0xE2, mpf_app2(primary_size, primary_size - base, len(second), o))
    assert len(app2) == len(app2_probe)
    return primary[:2] + app1 + app2 + primary[2:] + second


def patch(src, name, edits):
    b = bytearray((out / src).read_bytes())
    for off, data in edits:
        b[off:off + len(data)] = data
    (out / name).write_bytes(bytes(b))


def find_marker(b, marker, nth=0):
    """Offset of the nth marker segment 0xFF<marker> before SOS (or SOS itself)."""
    segs, sos = segments(b)
    hits = [o for m, o, n in segs if m == marker] + ([sos] if marker == 0xDA else [])
    return hits[nth]


(out / 'exif-mpf.jpg').write_bytes(build({}))
for name, o in {'exif-unsorted.jpg': {'unsorted': 1}, 'exif-no-colorspace.jpg': {'no_colorspace': 1},
                'exif-imagewidth.jpg': {'image_width': 1}, 'exif-ifd-loop.jpg': {'ifd_loop': 1},
                'exif-ascii-nonull.jpg': {'make_no_nul': 1}, 'exif-thumb-rst.jpg': {'thumb_rst': 1},
                'mpf-type0.jpg': {'type2': 0x000000}, 'mpf-size.jpg': {'size_delta': 14494}}.items():
    (out / name).write_bytes(build(o))

ff = (out / 'ffmpeg-default.jpg').read_bytes()
(out / 'no-eoi.jpg').write_bytes(ff[:-2])
(out / 'trailing-garbage.jpg').write_bytes(ff + bytes(16))
dqt = find_marker(ff, 0xDB)
patch('ffmpeg-default.jpg', 'no-dqt.jpg', [(dqt + 1, b'\xfe')])                 # DQT -> COM
sof = find_marker(ff, 0xC0)
patch('ffmpeg-default.jpg', 'sof-h0.jpg', [(sof + 11, bytes([ff[sof + 11] & 0x0F]))])  # component 1: H 1 -> 0
sos = find_marker(ff, 0xDA)
ns = ff[sos + 4]
patch('ffmpeg-default.jpg', 'scan-se.jpg', [(sos + 5 + 2 * ns + 1, b'\x3e')])  # Se 63 -> 62
app0 = find_marker(ff, 0xE0)
com = find_marker(ff, 0xFE)
a0 = ff[app0:app0 + 2 + struct.unpack('>H', ff[app0 + 2:app0 + 4])[0]]
c0 = ff[com:com + 2 + struct.unpack('>H', ff[com + 2:com + 4])[0]]
assert app0 == 2 and com == 2 + len(a0)
(out / 'jfif-not-first.jpg').write_bytes(ff[:2] + c0 + a0 + ff[2 + len(a0) + len(c0):])  # COM before JFIF APP0
patch('ffmpeg-default.jpg', 'jfif-density0.jpg', [(app0 + 12, b'\x00\x00')])     # Hdensity 1 -> 0

cr = (out / 'cjpeg-restart.jpg').read_bytes()
dri = find_marker(cr, 0xDD)
patch('cjpeg-restart.jpg', 'dri-zero.jpg', [(dri + 4, b'\x00\x00')])            # Ri 4 -> 0
rst0 = cr.index(b'\xff\xd0', find_marker(cr, 0xDA))
patch('cjpeg-restart.jpg', 'rst-order.jpg', [(rst0 + 1, b'\xd1')])               # first RST0 -> RST1
```

- [ ] **Step 3:** run `docs/validation/jpeg-conformance-fixtures/generate.sh`, then dump the bytes independently
  (`xxd`, `djpeg file >/dev/null`) and compare with the verified layouts below (measured 2026-10-24, macOS 15.1.1,
  ffmpeg 8.1.2, libjpeg-turbo 3.2.0). If sizes or markers differ, record the new values in this plan and the spec before
  continuing; the `JpegFixtureTest` expectations (Task 8) depend only on the marker structure, not on the sizes.

| File | Bytes | Markers | SOF | Notes |
|---|---|---|---|---|
| `ffmpeg-default.jpg` | 1821 | D8 E0 FE DB C4 C0 DA D9 | SOF0 P8 64x48 Nf3, C1 2x2 Tq0, C2/C3 1x1 Tq0 | JFIF 1.02, units 0, density 1x1; one DQT, one DHT (4 tables); 12 MCUs, no DRI |
| `cjpeg-default.jpg` | 1676 | D8 E0 DB DB C0 C4 C4 C4 C4 DA D9 | SOF0 64x48, Tq 0/1/1 | JFIF **1.01** |
| `cjpeg-progressive.jpg` | 1528 | D8 E0 DB DB C2 C4 C4 DA C4 DA … D9 | SOF2 | 10 scans (DC interleaved first) |
| `cjpeg-restart.jpg` | 1689 | … C4 DD DA D9 | SOF0 | DRI Ri=4, 12 MCUs → 2 RSTs (RST0 @ first, RST1) |
| `sips.jpg` | 2489 | D8 E0 E1 ED C0 C4×4 DB DB DD DA D9 | SOF0 64x48 | JFIF 1.01 72 dpi, Exif APP1 (MM) second, Photoshop APP13; DRI Ri=4, 2 RSTs |
| `exif-mpf.jpg` | 3519 | D8 E1 E2 DB C4 C0 DA D9 + secondary JPEG | as ffmpeg | APP1 @2 len 0x346 `Exif\0\0II*\0`, 0th IFD @8 (10 entries 010E..8769), Exif IFD 11 entries (829A..A460, ExifVersion `0300`), 1st IFD (0103=6, 0201, 0202), thumbnail @TIFF+398, 432 bytes (SOF0, no APPn/COM/RST). APP2 @842: `FF E2 00 58 'MPF\0' 49 49 2A 00 08 00 00 00`, 3 tags B000 `0100` / B001 2 / B002 count 32 @0x32, next 0; entry 1 `A0030000` size 2715 offset 0 dep (2,0); entry 2 `40010001` size 804 offset 1865 (→ abs 2715 = primary end). Primary EOI end 2715, 804 bytes after EOI |
| `exif-thumb-rst.jpg` | 4073 | as exif-mpf | | thumbnail = cjpeg 32x24 `-restart 1B` without APP0: DRI + 3 RSTs |

Variants (each fails exactly the listed rule; cjpeg-based ones also keep the source's `jfif.version` WARN):
`no-eoi` (last 2 bytes cut) → `t81.eoi`; `trailing-garbage` (+16 zero bytes) → `t81.trailing` WARN; `no-dqt` (DQT
marker byte → 0xFE COM) → `t81.tables`; `sof-h0` (SOF component 1 H 1→0 at SOF+11) → `t81.frame.header`; `scan-se`
(SOS Se 63→62 at SOS+6+2Ns) → `t81.scan.header`; `jfif-not-first` (COM moved before APP0) → `jfif.position`;
`jfif-density0` (APP0+12 Hdensity → 0) → `jfif.fields`; `dri-zero` (DRI+4 Ri → 0) → `t81.restart`; `rst-order` (first
RST0 → RST1) → `t81.restart`; `exif-unsorted` (Make/Model swapped) → `exif.ifd.order`; `exif-no-colorspace` →
`exif.tags.mandatory`; `exif-imagewidth` (0th IFD ImageWidth added) → `exif.tags.not_allowed`; `exif-ifd-loop` (0th
IFD next → 0th IFD) → `exif.ifd.structure`; `exif-ascii-nonull` (Make without NUL) → `exif.tag.ascii`;
`exif-thumb-rst` → `exif.thumbnail`; `mpf-type0` (entry 2 type 0x000000, as Samsung gain maps) → `mpf.type`;
`mpf-size` (entry 1 size +14494, as the S26 C2PA case) → `mpf.images`.

- [ ] **Step 4:** commit `test: add JPEG conformance fixtures` (scripts + 26 files).

---

### Task 3: Bounded marker / entropy-data scanner

**Files:** Create `parser/conformance/jpeg/JpegStream.kt`; tests `SyntheticJpeg.kt` (shared builder used by later
tasks — `check()`/`status()`/`ids()` compile only after Task 4; add those four helpers in Task 4) and `JpegStreamScannerTest.kt`.

- [ ] **Step 1: Failing tests**

```kotlin
package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.byteReaderOf
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus

/** Marker segment 0xFF<marker> + Lx + payload. */
internal fun seg(marker: Int, vararg payload: Int): ByteArray =
    byteArrayOf(0xFF.toByte(), marker.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) +
        payload.map { it.toByte() }.toByteArray()

internal fun seg(marker: Int, payload: ByteArray): ByteArray =
    byteArrayOf(0xFF.toByte(), marker.toByte(), ((payload.size + 2) shr 8).toByte(), (payload.size + 2).toByte()) + payload

internal val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
internal val EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
internal fun rst(m: Int) = byteArrayOf(0xFF.toByte(), (0xD0 + m).toByte())

internal fun jfifApp0(major: Int = 1, minor: Int = 2, units: Int = 0, hd: Int = 1, vd: Int = 1) =
    seg(0xE0, 0x4A, 0x46, 0x49, 0x46, 0x00, major, minor, units, hd shr 8, hd and 0xFF, vd shr 8, vd and 0xFF, 0, 0)

/** DQT with one 8-bit table at [tq] (all elements 1). */
internal fun dqt(tq: Int = 0, pq: Int = 0) = seg(0xDB, byteArrayOf(((pq shl 4) or tq).toByte()) + ByteArray(if (pq == 0) 64 else 128) { 1 })

/** DHT with one table class [tc], destination [th]: one code of length 1 → value 0. */
internal fun dht(tc: Int, th: Int) = seg(0xC4, byteArrayOf(((tc shl 4) or th).toByte(), 1) + ByteArray(15) + byteArrayOf(0))

/** SOF[n] for [comps] = (id, h, v, tq). */
internal fun sof(marker: Int = 0xC0, p: Int = 8, y: Int = 16, x: Int = 16, comps: List<IntArray> = listOf(intArrayOf(1, 1, 1, 0))) =
    seg(marker, byteArrayOf(p.toByte(), (y shr 8).toByte(), y.toByte(), (x shr 8).toByte(), x.toByte(), comps.size.toByte()) +
        comps.flatMap { listOf(it[0].toByte(), ((it[1] shl 4) or it[2]).toByte(), it[3].toByte()) }.toByteArray())

/** SOS for [comps] = (selector, td, ta). */
internal fun sos(comps: List<IntArray> = listOf(intArrayOf(1, 0, 0)), ss: Int = 0, se: Int = 63, ah: Int = 0, al: Int = 0) =
    seg(0xDA, byteArrayOf(comps.size.toByte()) + comps.flatMap { listOf(it[0].toByte(), ((it[1] shl 4) or it[2]).toByte()) }.toByteArray() +
        byteArrayOf(ss.toByte(), se.toByte(), ((ah shl 4) or al).toByte()))

internal fun dri(ri: Int) = seg(0xDD, ri shr 8, ri and 0xFF)

/** Entropy-coded filler bytes (no 0xFF). */
internal fun ecs(n: Int = 4) = ByteArray(n) { 0x55 }

/** A minimal valid baseline JFIF image: 16x16 grey, one scan, 4 MCUs of 8x8. */
internal fun baseline(
    app: ByteArray = jfifApp0(),
    tables: ByteArray = dqt() + dht(0, 0) + dht(1, 0),
    frame: ByteArray = sof(),
    scan: ByteArray = sos() + ecs(),
    tail: ByteArray = EOI,
) = SOI + app + tables + frame + scan + tail

internal fun check(bytes: ByteArray): ConformanceReport = byteReaderOf(bytes).use { JpegConformanceChecker.check(it) }
internal fun ConformanceReport.status(id: String): ConformanceStatus = findings.single { it.rule.id == id }.status
internal fun ConformanceReport.ids(vararg s: ConformanceStatus): Set<String> = findings.filter { it.status in s }.map { it.rule.id }.toSet()
internal fun ConformanceReport.dump(): List<String> = findings.filter { it.status != ConformanceStatus.PASS && it.status != ConformanceStatus.NOT_APPLICABLE }
    .map { "${it.status} ${it.rule.id} ${it.details}" }
```

```kotlin
package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.byteReaderOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JpegStreamScannerTest {
    private fun scan(b: ByteArray) = byteReaderOf(b).use { JpegStreamScanner.scan(it) }

    @Test fun `walks markers to EOI and records the entropy data`() {
        val b = baseline()
        val s = scan(b)
        assertEquals(listOf(0xD8, 0xE0, 0xDB, 0xC4, 0xC4, 0xC0, 0xDA, 0xD9), s.segments.map { it.marker })
        assertEquals(b.size.toLong(), s.end)
        assertEquals(1, s.entropy.size)
        assertEquals(0L, s.entropy[0].restartCount)
        assertTrue(s.markerProblems.isEmpty() && s.lengthProblems.isEmpty() && !s.truncated)
    }

    @Test fun `counts restart markers, skips stuffed bytes and fill bytes, finds out-of-order RST`() {
        val data = ecs() + byteArrayOf(0xFF.toByte(), 0) + rst(0) + ecs() + rst(1) + ecs() + rst(3) + ecs()
        val s = scan(baseline(scan = sos() + data, tail = byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + EOI))
        assertEquals(3L, s.entropy[0].restartCount)
        assertEquals(s.entropy[0].start + 4 + 2 + 2 + 4 + 2 + 4, s.entropy[0].restartOutOfOrder)
        assertTrue(s.eoi != null)
    }

    @Test fun `truncated scan data and bad lengths are reported, never thrown`() {
        val cut = baseline().let { it.copyOf(it.size - 2) }
        val t = scan(cut)
        assertTrue(t.truncated); assertNull(t.eoi)
        val bad = SOI + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x00)
        val l = scan(bad)
        assertEquals(1, l.lengthProblems.size); assertTrue(l.truncated)
        val garbage = scan(SOI + byteArrayOf(0x12, 0x34))
        assertEquals(1, garbage.markerProblems.size)
    }

    @Test fun `entropy data crossing the 64 KiB chunk boundary is scanned`() {
        val big = ByteArray(70_000) { 0x11 }.also { it[65_535] = 0xFF.toByte(); it[65_536] = 0 }
        val s = scan(baseline(scan = sos() + big + rst(0) + ecs()))
        assertEquals(1L, s.entropy[0].restartCount)
        assertTrue(s.eoi != null)
    }
}
```

- [ ] **Step 2:** RED. **Step 3: Implement**

```kotlin
package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.ByteReader

/** A standalone marker ([length] 0) or a marker segment; [offset] is the 0xFF right before [marker] (after fill bytes). */
data class JpegSegment(val marker: Int, val offset: Long, val length: Int) {
    val standalone: Boolean get() = length == 0
    val payload: Long get() = offset + 4
    val payloadSize: Int get() = (length - 2).coerceAtLeast(0)
    val end: Long get() = if (standalone) offset + 2 else offset + 2 + length
    val range: LongRange get() = offset until end
}

/** Entropy-coded data after the SOS at [sosIndex]: every ECS of the scan and the RSTm markers between them. */
data class JpegEntropy(
    val sosIndex: Int,
    val start: Long,
    /** Offset of the marker that ends the scan data, or the scan limit when the data is cut off. */
    val end: Long,
    val restartCount: Long,
    /** Offset of the first RSTm whose m is not (number of earlier RSTs) mod 8. */
    val restartOutOfOrder: Long?,
    val firstRestart: Long?,
)

data class JpegStreamProblem(val offset: Long, val message: String)

/** Markers of one JPEG image from its first marker to its EOI (never past [end]); reads are bounded. */
data class JpegStream(
    val start: Long,
    val segments: List<JpegSegment>,
    val entropy: List<JpegEntropy>,
    val eoi: JpegSegment?,
    /** Exclusive end of the image: after EOI, or where the walk stopped. */
    val end: Long,
    /** Bytes that are not a marker where T.81 requires one (0xFF00 / non-0xFF). */
    val markerProblems: List<JpegStreamProblem>,
    /** Marker segments whose length parameter is < 2 or runs past the limit. */
    val lengthProblems: List<JpegStreamProblem>,
    /** The limit was reached before an EOI. */
    val truncated: Boolean,
    /** More than [JpegStreamScanner.MAX_SEGMENTS] segments: the walk stopped early. */
    val capped: Boolean,
) {
    fun index(segment: JpegSegment): Int = segments.indexOf(segment)
}

object JpegStreamScanner {
    const val MAX_SEGMENTS = 100_000
    private const val CHUNK = 64 * 1024
    private val STANDALONE = setOf(0x01, 0xD8, 0xD9) + (0xD0..0xD7)

    fun scan(reader: ByteReader, start: Long = 0, limit: Long = reader.length): JpegStream {
        val segments = ArrayList<JpegSegment>()
        val entropy = ArrayList<JpegEntropy>()
        val markerProblems = ArrayList<JpegStreamProblem>()
        val lengthProblems = ArrayList<JpegStreamProblem>()
        var pos = start
        var eoi: JpegSegment? = null
        var stoppedAtLimit = false
        var capped = false
        while (true) {
            if (segments.size >= MAX_SEGMENTS) { capped = true; break }
            if (pos + 2 > limit) { stoppedAtLimit = true; break }
            val first = reader.readUInt8(pos)
            if (first != 0xFF) {
                markerProblems += JpegStreamProblem(pos, "@0x%X: 0x%02X — 마커 자리에 0xFF가 아님 / not a marker".format(pos, first))
                break
            }
            var q = pos + 1
            while (q < limit && reader.readUInt8(q) == 0xFF) q++ // fill bytes (B.1.1.2)
            if (q >= limit) { stoppedAtLimit = true; break }
            val marker = reader.readUInt8(q)
            val at = q - 1
            if (marker == 0x00) {
                markerProblems += JpegStreamProblem(at, "@0x%X: 0xFF00 — 마커 자리에 채움 바이트 / stuffed byte where a marker is required".format(at))
                break
            }
            if (marker in STANDALONE) {
                val seg = JpegSegment(marker, at, 0)
                segments += seg
                pos = seg.end
                if (marker == 0xD9) { eoi = seg; break }
                continue
            }
            if (at + 4 > limit) {
                lengthProblems += JpegStreamProblem(at, "@0x%X: 0xFF%02X 길이 필드가 잘림 / length field cut off".format(at, marker))
                stoppedAtLimit = true
                break
            }
            val length = reader.readUInt16(at + 2)
            if (length < 2) {
                lengthProblems += JpegStreamProblem(at, "@0x%X: 0xFF%02X 길이 %d < 2 / length below 2".format(at, marker, length))
                break
            }
            if (at + 2 + length > limit) {
                lengthProblems += JpegStreamProblem(at, "@0x%X: 0xFF%02X 길이 %d — 파일 끝을 넘음 / runs past the end (%d)".format(at, marker, length, limit))
                stoppedAtLimit = true
                break
            }
            val seg = JpegSegment(marker, at, length)
            segments += seg
            pos = seg.end
            if (marker == 0xDA) {
                val e = scanEntropy(reader, segments.size - 1, pos, limit)
                entropy += e
                pos = e.end
            }
        }
        return JpegStream(start, segments, entropy, eoi, eoi?.end ?: minOf(pos, limit), markerProblems, lengthProblems,
            truncated = eoi == null && stoppedAtLimit, capped = capped)
    }

    /** Scans entropy-coded data in 64 KiB chunks: skips 0xFF00 and fill bytes, counts RSTm, stops at any other marker. */
    private fun scanEntropy(reader: ByteReader, sosIndex: Int, start: Long, limit: Long): JpegEntropy {
        var pos = start
        var count = 0L
        var outOfOrder: Long? = null
        var first: Long? = null
        while (pos < limit) {
            val n = minOf(CHUNK.toLong(), limit - pos).toInt()
            val buf = reader.readBytes(pos, n)
            var i = 0
            while (i < n) {
                if (buf[i] != 0xFF.toByte()) { i++; continue }
                if (i + 1 >= n) {
                    if (pos + i + 1 >= limit) return JpegEntropy(sosIndex, start, limit, count, outOfOrder, first)
                    break // re-read this 0xFF with the next chunk
                }
                val next = buf[i + 1].toInt() and 0xFF
                when {
                    next == 0x00 -> i += 2
                    next == 0xFF -> i += 1
                    next in 0xD0..0xD7 -> {
                        val at = pos + i
                        if (first == null) first = at
                        if (outOfOrder == null && next - 0xD0 != (count % 8).toInt()) outOfOrder = at
                        count++
                        i += 2
                    }
                    else -> return JpegEntropy(sosIndex, start, pos + i, count, outOfOrder, first)
                }
            }
            pos += i
        }
        return JpegEntropy(sosIndex, start, limit, count, outOfOrder, first)
    }
}

enum class JpegProcess { BASELINE, EXTENDED, PROGRESSIVE, LOSSLESS, DIFFERENTIAL }

data class FrameComponent(val id: Int, val h: Int, val v: Int, val tq: Int)

data class JpegFrame(
    val segment: JpegSegment,
    val precision: Int,
    val lines: Int,
    val samplesPerLine: Int,
    /** Nf as written. */
    val declaredCount: Int,
    /** Components actually present in the header (may be fewer than Nf when the segment is short). */
    val components: List<FrameComponent>,
) {
    val marker: Int get() = segment.marker
    val process: JpegProcess get() = processOf(marker)
    val huffman: Boolean get() = marker in 0xC0..0xC7
    val dct: Boolean get() = process != JpegProcess.LOSSLESS && marker !in setOf(0xC7, 0xCF)
    val hMax: Int get() = components.maxOfOrNull { it.h }?.coerceAtLeast(1) ?: 1
    val vMax: Int get() = components.maxOfOrNull { it.v }?.coerceAtLeast(1) ?: 1

    companion object {
        val SOF_MARKERS = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)

        fun processOf(marker: Int): JpegProcess = when (marker) {
            0xC0 -> JpegProcess.BASELINE
            0xC1, 0xC9 -> JpegProcess.EXTENDED
            0xC2, 0xCA -> JpegProcess.PROGRESSIVE
            0xC3, 0xCB -> JpegProcess.LOSSLESS
            else -> JpegProcess.DIFFERENTIAL
        }
    }
}

data class ScanComponent(val selector: Int, val td: Int, val ta: Int)

data class JpegScanHeader(
    val segment: JpegSegment,
    val declaredCount: Int,
    val components: List<ScanComponent>,
    val ss: Int, val se: Int, val ah: Int, val al: Int,
    /** False when the segment is too short to hold Ss/Se/Ah/Al after the component list. */
    val complete: Boolean,
)

data class QuantTable(val segment: JpegSegment, val pq: Int, val tq: Int, val hasZero: Boolean)
data class HuffmanTable(val segment: JpegSegment, val tc: Int, val th: Int, val valueCount: Int)

/** Parses headers from segment payloads; every read stays inside the segment. */
object JpegHeaders {
    fun payload(reader: ByteReader, s: JpegSegment): ByteArray =
        if (s.payloadSize == 0) ByteArray(0) else reader.readBytes(s.payload, s.payloadSize)

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
    private fun ByteArray.u16(i: Int) = (u8(i) shl 8) or u8(i + 1)

    fun frame(reader: ByteReader, s: JpegSegment): JpegFrame? {
        val p = payload(reader, s)
        if (p.size < 6) return null
        val nf = p.u8(5)
        val n = minOf(nf, (p.size - 6) / 3)
        val comps = (0 until n).map { i -> FrameComponent(p.u8(6 + 3 * i), p.u8(7 + 3 * i) shr 4, p.u8(7 + 3 * i) and 15, p.u8(8 + 3 * i)) }
        return JpegFrame(s, p.u8(0), p.u16(1), p.u16(3), nf, comps)
    }

    fun scan(reader: ByteReader, s: JpegSegment): JpegScanHeader? {
        val p = payload(reader, s)
        if (p.isEmpty()) return null
        val ns = p.u8(0)
        val n = minOf(ns, (p.size - 1) / 2)
        val comps = (0 until n).map { i -> ScanComponent(p.u8(1 + 2 * i), p.u8(2 + 2 * i) shr 4, p.u8(2 + 2 * i) and 15) }
        val tail = 1 + 2 * ns
        val complete = n == ns && p.size >= tail + 3
        return if (complete) JpegScanHeader(s, ns, comps, p.u8(tail), p.u8(tail + 1), p.u8(tail + 2) shr 4, p.u8(tail + 2) and 15, true)
        else JpegScanHeader(s, ns, comps, 0, 0, 0, 0, false)
    }

    /** Tables of one DQT segment; [problems] gets one line per table that runs past the segment. */
    fun quantTables(reader: ByteReader, s: JpegSegment, problems: MutableList<String>): List<QuantTable> {
        val p = payload(reader, s)
        val out = ArrayList<QuantTable>()
        var i = 0
        while (i < p.size) {
            val pq = p.u8(i) shr 4
            val tq = p.u8(i) and 15
            val size = if (pq == 0) 64 else 128
            if (i + 1 + size > p.size) {
                problems += "DQT @0x%X: 테이블 %d이(가) 세그먼트 끝을 넘음 / table runs past the segment (Lq=%d)".format(s.offset, out.size, s.length)
                break
            }
            val zero = (0 until 64).any { k -> if (pq == 0) p.u8(i + 1 + k) == 0 else p.u16(i + 1 + 2 * k) == 0 }
            out += QuantTable(s, pq, tq, zero)
            i += 1 + size
        }
        return out
    }

    fun huffmanTables(reader: ByteReader, s: JpegSegment, problems: MutableList<String>): List<HuffmanTable> {
        val p = payload(reader, s)
        val out = ArrayList<HuffmanTable>()
        var i = 0
        while (i < p.size) {
            if (i + 17 > p.size) {
                problems += "DHT @0x%X: 테이블 %d의 BITS(16바이트)가 잘림 / BITS cut off (Lh=%d)".format(s.offset, out.size, s.length)
                break
            }
            val mt = (1..16).sumOf { p.u8(i + it) }
            if (i + 17 + mt > p.size) {
                problems += "DHT @0x%X: 테이블 %d의 값 %d개가 세그먼트 끝을 넘음 / %d values run past the segment (Lh=%d)".format(s.offset, out.size, mt, mt, s.length)
                break
            }
            out += HuffmanTable(s, p.u8(i) shr 4, p.u8(i) and 15, mt)
            i += 17 + mt
        }
        return out
    }

    /** Ri of a DRI segment, or null when Lr != 4. */
    fun restartInterval(reader: ByteReader, s: JpegSegment): Int? =
        if (s.length != 4) null else reader.readUInt16(s.payload)

    /** NL of a DNL segment, or null when Ld != 4. */
    fun numberOfLines(reader: ByteReader, s: JpegSegment): Int? =
        if (s.length != 4) null else reader.readUInt16(s.payload)
}
```

- [ ] **Step 4:** GREEN (`--tests '*JpegStreamScannerTest'`). **Step 5:** commit `feat: scan JPEG markers and restart markers for conformance`.

---

### Task 4: Context, T.81 rules and the checker

**Files:** Create `JpegContext.kt`, `JpegT81Rules.kt`, `JpegConformanceChecker.kt`; test `JpegT81RulesTest.kt`.
In this task leave out of `JpegContext` the two `exifSegment`/`exif` vals (added in Task 6) and in the checker call
only `JpegT81Rules.evaluate(ctx)` (Tasks 5-7 add one line each: JFIF, Exif, MPF + trailer).

- [ ] **Step 1: Failing tests** (`t81.trailing` is added in Task 7: until then skip the last test's two trailing asserts)

```kotlin
package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import kotlin.test.Test
import kotlin.test.assertEquals

class JpegT81RulesTest {
    private fun failsOnly(bytes: ByteArray, vararg ids: String) {
        val r = check(bytes)
        assertEquals(ids.toSet(), r.ids(FAIL, WARN), r.dump().toString())
    }

    @Test fun `minimal baseline JFIF image passes every rule`() {
        val r = check(baseline())
        assertEquals(emptySet(), r.ids(FAIL, WARN, NOT_CHECKED), r.dump().toString())
        assertEquals("JPEG", r.format)
    }

    @Test fun `bytes that are not a marker fail marker, and the cut-off stream fails eoi`() {
        val r = check(SOI + jfifApp0() + byteArrayOf(0x12, 0x34) + dqt())
        assertEquals(FAIL, r.status("t81.marker"))
        assertEquals(FAIL, r.status("t81.eoi"))
        assertEquals(NOT_CHECKED, r.status("t81.frame.structure"))
        val len = check(SOI + jfifApp0() + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x00))
        assertEquals(FAIL, len.status("t81.segment.length"))
    }

    @Test fun `file not starting with SOI fails soi`() {
        val r = check(byteArrayOf(0xFF.toByte(), 0xE0.toByte()) + baseline().copyOfRange(4, baseline().size))
        assertEquals(FAIL, r.status("t81.soi"))
    }

    @Test fun `reserved marker warns`() = failsOnly(baseline(tables = seg(0xF3, 0) + dqt() + dht(0, 0) + dht(1, 0)), "t81.marker.reserved")

    @Test fun `frame header fields`() {
        failsOnly(baseline(app = ByteArray(0), frame = sof(p = 12)), "t81.frame.header")                  // baseline P=8 only (no JFIF)
        failsOnly(baseline(frame = sof(comps = listOf(intArrayOf(1, 5, 1, 0)))), "t81.frame.header")      // H 1-4
        failsOnly(baseline(frame = sof(x = 0)), "t81.frame.header")                                      // X >= 1
    }

    @Test fun `scan header fields`() {
        failsOnly(baseline(scan = sos(se = 62) + ecs()), "t81.scan.header")
        failsOnly(baseline(scan = sos(listOf(intArrayOf(9, 0, 0))) + ecs()), "t81.scan.header")          // Cs not in frame
        failsOnly(baseline(tables = dqt() + dht(0, 0) + dht(1, 0) + dht(0, 2) + dht(1, 2), scan = sos(listOf(intArrayOf(1, 2, 2))) + ecs()),
            "t81.scan.header", "t81.dht")                                                                // baseline Td/Ta, Th 0-1
    }

    @Test fun `frame structure`() {
        failsOnly(baseline(frame = sof() + sof()), "t81.frame.structure")
        failsOnly(baseline(frame = sof(y = 0)), "t81.frame.structure")                                   // Y=0 without DNL
        assertEquals(NOT_CHECKED, check(baseline(frame = sof(0xC5))).status("t81.frame.structure"))       // differential → hierarchical
    }

    @Test fun `table definitions and references`() {
        failsOnly(baseline(tables = dht(0, 0) + dht(1, 0)), "t81.tables")                                // no DQT
        failsOnly(baseline(tables = dqt() + dht(0, 0)), "t81.tables")                                    // no AC table
        failsOnly(baseline(tables = dqt(tq = 4) + dqt() + dht(0, 0) + dht(1, 0)), "t81.dqt")
        failsOnly(baseline(tables = dqt(pq = 1) + dht(0, 0) + dht(1, 0)), "t81.dqt")                     // Pq=1 with P=8
        failsOnly(baseline(tables = dqt() + dht(2, 0) + dht(0, 0) + dht(1, 0)), "t81.dht")
    }

    @Test fun `restart interval and markers`() {
        // 16x16, one component 1x1 → 4 MCUs. Ri=1 → 3 RSTs.
        val ok = sos() + ecs() + rst(0) + ecs() + rst(1) + ecs() + rst(2) + ecs()
        assertEquals(PASS, check(baseline(tables = dqt() + dht(0, 0) + dht(1, 0) + dri(1), scan = ok)).status("t81.restart"))
        failsOnly(baseline(tables = dqt() + dht(0, 0) + dht(1, 0) + dri(1), scan = sos() + ecs() + rst(0) + ecs()), "t81.restart") // count
        failsOnly(baseline(scan = ok), "t81.restart")                                                    // RST while Ri=0
        failsOnly(baseline(tables = dqt() + dht(0, 0) + dht(1, 0) + dri(1),
            scan = sos() + ecs() + rst(0) + ecs() + rst(2) + ecs() + rst(3) + ecs()), "t81.restart")    // order
        failsOnly(baseline(tables = dqt() + dht(0, 0) + dht(1, 0) + seg(0xDD, 0, 1, 0)), "t81.restart") // Lr != 4
    }

    @Test fun `MCU count follows interleaving`() {
        val f = JpegFrame(JpegSegment(0xC0, 0, 17), 8, 48, 64, 3, listOf(FrameComponent(1, 2, 2, 0), FrameComponent(2, 1, 1, 1), FrameComponent(3, 1, 1, 1)))
        fun s(vararg ids: Int) = JpegScanHeader(JpegSegment(0xDA, 0, 12), ids.size, ids.map { ScanComponent(it, 0, 0) }, 0, 63, 0, 0, true)
        assertEquals(12L, JpegT81Rules.mcuCount(f, s(1, 2, 3), 48))   // 4 x 3 MCUs of 16x16
        assertEquals(48L, JpegT81Rules.mcuCount(f, s(1), 48))         // 8 x 6 luma blocks
        assertEquals(12L, JpegT81Rules.mcuCount(f, s(2), 48))         // 4 x 3 chroma blocks
    }

    @Test fun `missing EOI fails and trailing data is judged only after EOI`() {
        failsOnly(baseline(tail = ByteArray(0)), "t81.eoi")
        assertEquals(NOT_CHECKED, check(baseline(tail = ByteArray(0))).status("t81.trailing"))
        failsOnly(baseline(tail = EOI + ByteArray(8)), "t81.trailing")
    }
}
```

- [ ] **Step 2:** RED. **Step 3: Implement**

```kotlin
package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus

/** Shared, read-only state for the JPEG rule groups (primary image only). */
internal class JpegContext(val reader: ByteReader, val stream: JpegStream) {
    val length: Long = reader.length
    val segments: List<JpegSegment> get() = stream.segments
    val sofSegments: List<JpegSegment> = segments.filter { it.marker in JpegFrame.SOF_MARKERS }
    val frame: JpegFrame? by lazy { sofSegments.firstOrNull()?.let { JpegHeaders.frame(reader, it) } }

    /** DHP, EXP or a differential SOF: hierarchical mode, outside phase 1. */
    val hierarchical: Boolean = segments.any { it.marker == 0xDE || it.marker == 0xDF } ||
        sofSegments.any { JpegFrame.processOf(it.marker) == JpegProcess.DIFFERENTIAL }

    /** Number of lines: Y, or NL of the DNL that follows the first scan when Y is 0; null when unknown. */
    val lines: Int? by lazy {
        val f = frame ?: return@lazy null
        if (f.lines != 0) return@lazy f.lines
        val firstSos = segments.indexOfFirst { it.marker == 0xDA }
        segments.getOrNull(firstSos + 1)?.takeIf { it.marker == 0xDC }?.let { JpegHeaders.numberOfLines(reader, it) }
    }

    private val payloads = HashMap<JpegSegment, ByteArray>()
    fun payload(s: JpegSegment): ByteArray = payloads.getOrPut(s) { JpegHeaders.payload(reader, s) }

    fun startsWith(s: JpegSegment, prefix: ByteArray): Boolean {
        if (s.payloadSize < prefix.size) return false
        val p = payload(s)
        return prefix.indices.all { p[it] == prefix[it] }
    }

    /** The first Exif APP1 ("Exif\0") of the primary image and its TIFF structure. */
    val exifSegment: JpegSegment? by lazy { segments.firstOrNull { it.marker == 0xE1 && startsWith(it, EXIF_ID) } }
    val exif: ExifTiff? by lazy { exifSegment?.let { s -> ExifTiff.read(payload(s).copyOfRange(6.coerceAtMost(s.payloadSize), s.payloadSize), s.payload + 6) } }

    fun finding(id: String, violations: List<String>, ranges: List<LongRange>, pass: List<String>): ConformanceFinding {
        val status = when {
            violations.isEmpty() -> ConformanceStatus.PASS
            id in JpegRules.WARN_RULES -> ConformanceStatus.WARN
            else -> ConformanceStatus.FAIL
        }
        return ConformanceFinding(JpegRules.rule(id), status, if (violations.isEmpty()) pass else capped(violations), ranges.take(MAX_LISTED))
    }

    fun skip(id: String, status: ConformanceStatus, reason: String): ConformanceFinding =
        ConformanceFinding(JpegRules.rule(id), status, listOf(reason), emptyList())

    companion object {
        const val MAX_LISTED = 50
        val EXIF_ID = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00)
        val JFIF_ID = byteArrayOf(0x4A, 0x46, 0x49, 0x46, 0x00)
        val JFXX_ID = byteArrayOf(0x4A, 0x46, 0x58, 0x58, 0x00)
        val MPF_ID = byteArrayOf(0x4D, 0x50, 0x46, 0x00)

        fun capped(lines: List<String>): List<String> =
            if (lines.size <= MAX_LISTED) lines else lines.take(MAX_LISTED) + "… 외 ${lines.size - MAX_LISTED}건 / ${lines.size - MAX_LISTED} more"

        fun hex(offset: Long): String = "0x%X".format(offset)
        fun markerName(m: Int): String = when (m) {
            0xD8 -> "SOI"; 0xD9 -> "EOI"; 0xDA -> "SOS"; 0xDB -> "DQT"; 0xC4 -> "DHT"; 0xDD -> "DRI"; 0xDC -> "DNL"
            0xCC -> "DAC"; 0xFE -> "COM"; 0xDE -> "DHP"; 0xDF -> "EXP"; 0x01 -> "TEM"; 0xC8 -> "JPG"
            in 0xD0..0xD7 -> "RST${m - 0xD0}"
            in 0xE0..0xEF -> "APP${m - 0xE0}"
            in 0xF0..0xFD -> "JPG${m - 0xF0}"
            in JpegFrame.SOF_MARKERS -> "SOF${m - 0xC0}"
            else -> "RES(0x%02X)".format(m)
        }
    }
}
```

```kotlin
package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.hex
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.markerName

/** ITU-T T.81 Annex B interchange-format rules for the primary image (SOI .. EOI). */
internal object JpegT81Rules {
    private const val HIERARCHICAL = "계층(hierarchical) 모드(DHP/EXP/차분 SOF) — 1단계 검사 범위 밖 / hierarchical mode is outside phase 1"

    fun evaluate(ctx: JpegContext): List<ConformanceFinding> = listOf(
        soi(ctx), marker(ctx), reserved(ctx), segmentLength(ctx), frameStructure(ctx), frameHeader(ctx), scanHeader(ctx),
        dqt(ctx), dht(ctx), tables(ctx), restart(ctx), eoi(ctx),
    )

    private fun soi(ctx: JpegContext): ConformanceFinding {
        val first = ctx.segments.firstOrNull()
        return if (first?.marker == 0xD8) ctx.finding("t81.soi", emptyList(), emptyList(), listOf("SOI @${hex(first.offset)}"))
        else ctx.finding("t81.soi", listOf("파일이 SOI(0xFFD8)로 시작하지 않음 / does not begin with SOI" +
            (first?.let { " — 첫 마커 ${markerName(it.marker)} @${hex(it.offset)}" } ?: "")), listOf(0L until minOf(2L, ctx.length)), emptyList())
    }

    private fun marker(ctx: JpegContext): ConformanceFinding {
        val p = ctx.stream.markerProblems
        return ctx.finding("t81.marker", p.map { it.message }, p.map { it.offset until minOf(it.offset + 2, ctx.length) },
            listOf("마커 ${ctx.segments.size}개 모두 0xFF + 유효 코드 / all ${ctx.segments.size} markers are 0xFF + a valid code"))
    }

    private val RESERVED = setOf(0x01, 0xC8) + (0x02..0xBF) + (0xF0..0xFD)

    private fun reserved(ctx: JpegContext): ConformanceFinding {
        val r = ctx.segments.filter { it.marker in RESERVED }
        return ctx.finding("t81.marker.reserved", r.map { "${markerName(it.marker)} @${hex(it.offset)}: 예약 마커 / reserved marker (Table B.1)" },
            r.map { it.range }, listOf("예약 마커 없음 / no reserved markers"))
    }

    private fun segmentLength(ctx: JpegContext): ConformanceFinding {
        val p = ctx.stream.lengthProblems
        return ctx.finding("t81.segment.length", p.map { it.message }, p.map { it.offset until minOf(it.offset + 4, ctx.length) },
            listOf("모든 마커 세그먼트 길이가 파일 안에 있음 / every marker segment length fits"))
    }

    private fun stoppedEarly(ctx: JpegContext) = ctx.stream.truncated || ctx.stream.markerProblems.isNotEmpty() ||
        ctx.stream.lengthProblems.isNotEmpty() || ctx.stream.capped

    private fun frameStructure(ctx: JpegContext): ConformanceFinding {
        val id = "t81.frame.structure"
        if (ctx.hierarchical) return ctx.skip(id, NOT_CHECKED, HIERARCHICAL)
        val segs = ctx.segments
        val sofs = ctx.sofSegments
        val sosIdx = segs.indices.filter { segs[it].marker == 0xDA }
        if ((sofs.isEmpty() || sosIdx.isEmpty()) && stoppedEarly(ctx))
            return ctx.skip(id, NOT_CHECKED, "스트림이 SOF/SOS 전에 끝나 판단 불가 / stream ends before SOF/SOS")
        val v = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        if (sofs.isEmpty()) v += "프레임 헤더(SOFn) 없음 / no frame header"
        if (sofs.size > 1) { v += "프레임 헤더 ${sofs.size}개 — 1개여야 함 / ${sofs.size} frames, the interchange format has one"; ranges += sofs.drop(1).map { it.range } }
        if (sosIdx.isEmpty()) v += "스캔(SOS) 없음 / no scan"
        val sofIdx = segs.indexOfFirst { it.marker in JpegFrame.SOF_MARKERS }
        sosIdx.firstOrNull()?.let { if (sofIdx < 0 || it < sofIdx) { v += "SOS @${hex(segs[it].offset)}가 프레임 헤더보다 앞 / scan before the frame header"; ranges += segs[it].range } }
        segs.withIndex().filter { it.value.marker == 0xDC }.forEach { (i, s) ->
            if (sosIdx.isEmpty() || i != sosIdx.first() + 1) { v += "DNL @${hex(s.offset)}가 첫 스캔 바로 뒤가 아님 / DNL does not immediately follow the first scan"; ranges += s.range }
        }
        if (ctx.frame?.lines == 0 && ctx.lines == null) v += "Y=0인데 첫 스캔 뒤 DNL 없음 / Y = 0 without a DNL after the first scan"
        return ctx.finding(id, v, ranges, listOf("${sofs.firstOrNull()?.let { markerName(it.marker) }} 1개, 스캔 ${sosIdx.size}개 / one frame, ${sosIdx.size} scan(s)"))
    }

    private fun frameHeader(ctx: JpegContext): ConformanceFinding {
        val id = "t81.frame.header"
        if (ctx.hierarchical) return ctx.skip(id, NOT_CHECKED, HIERARCHICAL)
        val sof = ctx.sofSegments.firstOrNull() ?: return ctx.skip(id, NOT_APPLICABLE, "프레임 헤더 없음 / no frame header")
        val f = ctx.frame ?: return ctx.finding(id, listOf("${markerName(sof.marker)} @${hex(sof.offset)}: 길이 ${sof.length} — 고정 필드(6바이트)보다 짧음 / too short"), listOf(sof.range), emptyList())
        val at = "${markerName(f.marker)} @${hex(sof.offset)}"
        val v = ArrayList<String>()
        if (sof.length != 8 + 3 * f.declaredCount) v += "$at: Lf=${sof.length} ≠ 8 + 3×Nf (${8 + 3 * f.declaredCount})"
        val pOk = when (f.process) {
            JpegProcess.BASELINE -> f.precision == 8
            JpegProcess.EXTENDED, JpegProcess.PROGRESSIVE -> f.precision == 8 || f.precision == 12
            else -> f.precision in 2..16
        }
        if (!pOk) v += "$at: P=${f.precision} — ${f.process} 허용값 아님 / not allowed for ${f.process}"
        if (f.samplesPerLine < 1) v += "$at: X=0 — 1 이상이어야 함 / X must be 1-65535"
        val nfMax = if (f.process == JpegProcess.PROGRESSIVE) 4 else 255
        if (f.declaredCount !in 1..nfMax) v += "$at: Nf=${f.declaredCount} — 1-$nfMax"
        f.components.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { v += "$at: 성분 ID $it 중복 / duplicate component identifier $it" }
        f.components.forEach { c ->
            if (c.h !in 1..4 || c.v !in 1..4) v += "$at: 성분 ${c.id} H=${c.h} V=${c.v} — 1-4"
            val tqOk = if (f.process == JpegProcess.LOSSLESS) c.tq == 0 else c.tq in 0..3
            if (!tqOk) v += "$at: 성분 ${c.id} Tq=${c.tq} — ${if (f.process == JpegProcess.LOSSLESS) "0" else "0-3"}"
        }
        return ctx.finding(id, v, if (v.isEmpty()) emptyList() else listOf(sof.range),
            listOf("$at: P=${f.precision}, Y=${f.lines}, X=${f.samplesPerLine}, Nf=${f.declaredCount}, " +
                f.components.joinToString { "C${it.id} ${it.h}x${it.v} Tq${it.tq}" }))
    }

    private fun scanHeader(ctx: JpegContext): ConformanceFinding {
        val id = "t81.scan.header"
        if (ctx.hierarchical) return ctx.skip(id, NOT_CHECKED, HIERARCHICAL)
        val sos = ctx.segments.filter { it.marker == 0xDA }
        if (sos.isEmpty()) return ctx.skip(id, NOT_APPLICABLE, "스캔 없음 / no scan")
        val f = ctx.frame ?: return ctx.skip(id, NOT_CHECKED, "프레임 헤더를 읽을 수 없어 판단 불가 / frame header unreadable")
        val v = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val order = f.components.map { it.id }
        sos.forEach { s ->
            val at = "SOS @${hex(s.offset)}"
            val before = v.size
            val h = JpegHeaders.scan(ctx.reader, s)
            if (h == null || !h.complete) {
                v += "$at: 길이 ${s.length} — Ns 성분과 Ss/Se/Ah/Al을 담기에 짧음 / too short"
            } else {
                if (s.length != 6 + 2 * h.declaredCount) v += "$at: Ls=${s.length} ≠ 6 + 2×Ns (${6 + 2 * h.declaredCount})"
                if (h.declaredCount !in 1..4) v += "$at: Ns=${h.declaredCount} — 1-4"
                val idx = h.components.map { order.indexOf(it.selector) }
                h.components.filter { it.selector !in order }.forEach { v += "$at: Cs=${it.selector}가 프레임 성분에 없음 / not a frame component" }
                if (h.components.map { it.selector }.toSet().size != h.components.size) v += "$at: 같은 성분 중복 / duplicate component selector"
                if (idx.filter { it >= 0 }.zipWithNext().any { (a, b) -> b <= a }) v += "$at: 성분 순서가 프레임 순서와 다름 / order differs from the frame header"
                if (h.components.size > 1) {
                    val sum = h.components.sumOf { c -> f.components.firstOrNull { it.id == c.selector }?.let { it.h * it.v } ?: 0 }
                    if (sum > 10) v += "$at: ΣH×V=$sum > 10"
                }
                val tMax = if (f.process == JpegProcess.BASELINE) 1 else 3
                h.components.forEach { c ->
                    if (c.td > tMax) v += "$at: 성분 ${c.selector} Td=${c.td} — 0-$tMax"
                    val taOk = if (f.process == JpegProcess.LOSSLESS) c.ta == 0 else c.ta <= tMax
                    if (!taOk) v += "$at: 성분 ${c.selector} Ta=${c.ta} — ${if (f.process == JpegProcess.LOSSLESS) "0" else "0-$tMax"}"
                }
                val spectral = "Ss=${h.ss} Se=${h.se} Ah=${h.ah} Al=${h.al}"
                when (f.process) {
                    JpegProcess.BASELINE, JpegProcess.EXTENDED ->
                        if (h.ss != 0 || h.se != 63 || h.ah != 0 || h.al != 0) v += "$at: $spectral — 순차 DCT는 Ss=0 Se=63 Ah=0 Al=0 / sequential DCT requires 0, 63, 0, 0"
                    JpegProcess.PROGRESSIVE -> {
                        if (h.ss > h.se || h.se > 63) v += "$at: $spectral — Ss ≤ Se ≤ 63"
                        if (h.ss == 0 && h.se != 0) v += "$at: $spectral — Ss=0이면 Se=0 (DC 스캔) / Se must be 0 when Ss is 0"
                        if (h.ah > 13 || h.al > 13) v += "$at: $spectral — Ah, Al 0-13"
                        if (h.declaredCount > 1 && h.ss != 0) v += "$at: 프로그레시브 AC 스캔은 성분 1개만 / progressive AC scans are not interleaved"
                    }
                    JpegProcess.LOSSLESS ->
                        if (h.ss !in 1..7 || h.se != 0 || h.ah != 0 || h.al > 15) v += "$at: $spectral — 무손실은 Ss 1-7, Se=0, Ah=0, Al 0-15 / lossless requires these"
                    JpegProcess.DIFFERENTIAL -> Unit
                }
            }
            if (v.size > before) ranges += s.range
        }
        return ctx.finding(id, v, ranges, listOf("스캔 헤더 ${sos.size}개 / ${sos.size} scan header(s)"))
    }

    private fun dqt(ctx: JpegContext): ConformanceFinding {
        val id = "t81.dqt"
        val segs = ctx.segments.filter { it.marker == 0xDB }
        if (segs.isEmpty()) return ctx.skip(id, NOT_APPLICABLE, "DQT 없음 / no DQT")
        val f = ctx.frame
        val v = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        var count = 0
        segs.forEach { s ->
            val before = v.size
            val tables = JpegHeaders.quantTables(ctx.reader, s, v)
            count += tables.size
            val at = "DQT @${hex(s.offset)}"
            if (tables.isEmpty() && v.size == before) v += "$at: 테이블 없음 / no table"
            tables.forEach { t ->
                if (t.pq > 1) v += "$at: Pq=${t.pq} — 0 또는 1 / 0 or 1"
                if (t.tq > 3) v += "$at: Tq=${t.tq} — 0-3"
                if (t.pq != 0 && f != null && (f.precision == 8 || f.process == JpegProcess.BASELINE))
                    v += "$at: Tq=${t.tq} Pq=1 — 8비트 정밀도(P=8)에서는 Pq=0 / Pq shall be zero for 8-bit precision"
                if (t.hasZero) v += "$at: Tq=${t.tq}에 0인 양자화 값 / a zero quantization element (Qk 1-255, 1-65535)"
            }
            if (v.size > before) ranges += s.range
        }
        return ctx.finding(id, v, ranges, listOf("DQT ${segs.size}개, 테이블 ${count}개 / ${segs.size} DQT, $count table(s)"))
    }

    private fun dht(ctx: JpegContext): ConformanceFinding {
        val id = "t81.dht"
        val segs = ctx.segments.filter { it.marker == 0xC4 }
        if (segs.isEmpty()) return ctx.skip(id, NOT_APPLICABLE, "DHT 없음 / no DHT")
        val f = ctx.frame
        val v = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        var count = 0
        segs.forEach { s ->
            val before = v.size
            val tables = JpegHeaders.huffmanTables(ctx.reader, s, v)
            count += tables.size
            val at = "DHT @${hex(s.offset)}"
            if (tables.isEmpty() && v.size == before) v += "$at: 테이블 없음 / no table"
            tables.forEach { t ->
                val tcOk = if (f?.process == JpegProcess.LOSSLESS) t.tc == 0 else t.tc <= 1
                if (!tcOk) v += "$at: Tc=${t.tc} — ${if (f?.process == JpegProcess.LOSSLESS) "0" else "0 또는 1 / 0 or 1"}"
                val thMax = if (f?.process == JpegProcess.BASELINE) 1 else 3
                if (t.th > thMax) v += "$at: Th=${t.th} — 0-$thMax"
            }
            if (v.size > before) ranges += s.range
        }
        return ctx.finding(id, v, ranges, listOf("DHT ${segs.size}개, 테이블 ${count}개 / ${segs.size} DHT, $count table(s)"))
    }

    private fun tables(ctx: JpegContext): ConformanceFinding {
        val id = "t81.tables"
        if (ctx.hierarchical) return ctx.skip(id, NOT_CHECKED, HIERARCHICAL)
        val f = ctx.frame ?: return ctx.skip(id, NOT_CHECKED, "프레임 헤더를 읽을 수 없어 판단 불가 / frame header unreadable")
        if (ctx.segments.none { it.marker == 0xDA }) return ctx.skip(id, NOT_CHECKED, "스캔 없음 / no scan")
        val q = HashSet<Int>()
        val h = HashSet<Pair<Int, Int>>()
        val v = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val sink = ArrayList<String>()
        ctx.segments.forEach { s ->
            when (s.marker) {
                0xDB -> JpegHeaders.quantTables(ctx.reader, s, sink).forEach { q += it.tq }
                0xC4 -> JpegHeaders.huffmanTables(ctx.reader, s, sink).forEach { h += it.tc to it.th }
                0xDA -> {
                    val sh = JpegHeaders.scan(ctx.reader, s) ?: return@forEach
                    val before = v.size
                    sh.components.forEach { c ->
                        val fc = f.components.firstOrNull { it.id == c.selector } ?: return@forEach
                        if (f.dct && fc.tq !in q) v += "SOS @${hex(s.offset)}: 성분 ${c.selector}의 양자화 테이블 Tq=${fc.tq} 미정의 / not defined before the scan"
                        if (f.huffman) {
                            val needDc = f.process != JpegProcess.PROGRESSIVE || (sh.ss == 0 && sh.ah == 0)
                            val needAc = f.process != JpegProcess.LOSSLESS && (f.process != JpegProcess.PROGRESSIVE || sh.se > 0)
                            if (needDc && (0 to c.td) !in h) v += "SOS @${hex(s.offset)}: 성분 ${c.selector}의 DC 허프만 테이블 Td=${c.td} 미정의 / not defined before the scan"
                            if (needAc && (1 to c.ta) !in h) v += "SOS @${hex(s.offset)}: 성분 ${c.selector}의 AC 허프만 테이블 Ta=${c.ta} 미정의 / not defined before the scan"
                        }
                    }
                    if (v.size > before) ranges += s.range
                }
            }
        }
        val coding = if (f.huffman) "허프만 / Huffman" else "산술(기본 조건화 허용) / arithmetic (default conditioning allowed)"
        return ctx.finding(id, v, ranges, listOf("모든 스캔의 테이블이 스캔 전에 정의됨 ($coding) / every scan's tables are defined before it"))
    }

    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

    /** MCUs in a DCT scan: interleaved → MCU grid of the frame; one component → that component's 8x8 blocks. */
    internal fun mcuCount(f: JpegFrame, scan: JpegScanHeader, lines: Int): Long? {
        val x = f.samplesPerLine.toLong()
        val y = lines.toLong()
        if (scan.components.size > 1) return ceilDiv(x, 8L * f.hMax) * ceilDiv(y, 8L * f.vMax)
        val c = f.components.firstOrNull { it.id == scan.components.firstOrNull()?.selector } ?: return null
        val w = ceilDiv(x * c.h, f.hMax.toLong())
        val h = ceilDiv(y * c.v, f.vMax.toLong())
        return ceilDiv(w, 8) * ceilDiv(h, 8)
    }

    private fun restart(ctx: JpegContext): ConformanceFinding {
        val id = "t81.restart"
        if (ctx.hierarchical) return ctx.skip(id, NOT_CHECKED, HIERARCHICAL)
        val f = ctx.frame
        val v = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val notes = ArrayList<String>()
        var ri: Int? = 0 // SOI disables restart (B.2.4.4); null = unknown after a malformed DRI
        ctx.segments.forEachIndexed { i, s ->
            when (s.marker) {
                0xDD -> {
                    ri = JpegHeaders.restartInterval(ctx.reader, s)
                    if (ri == null) { v += "DRI @${hex(s.offset)}: Lr=${s.length} ≠ 4"; ranges += s.range }
                }
                in 0xD0..0xD7 -> { v += "${markerName(s.marker)} @${hex(s.offset)}: 스캔 데이터 밖의 재시작 마커 / restart marker outside scan data"; ranges += s.range }
                0xDA -> {
                    val e = ctx.stream.entropy.firstOrNull { it.sosIndex == i } ?: return@forEachIndexed
                    val at = "SOS @${hex(s.offset)}"
                    val interval = ri
                    when {
                        interval == null -> notes += "$at: Ri를 알 수 없어 RST 개수 미검사 / Ri unknown"
                        interval == 0 && e.restartCount > 0 -> {
                            v += "$at: 재시작 비활성(Ri=0)인데 RST ${e.restartCount}개 @${hex(e.firstRestart!!)} / restart markers while restart is disabled"
                            ranges += e.firstRestart until e.firstRestart + 2
                        }
                        interval > 0 -> {
                            e.restartOutOfOrder?.let { v += "$at: RST 번호가 0-7 순환 순서가 아님 @${hex(it)} / RSTm out of modulo-8 sequence"; ranges += it until it + 2 }
                            val sh = JpegHeaders.scan(ctx.reader, s)
                            val lines = ctx.lines
                            val truncatedScan = ctx.stream.truncated && e === ctx.stream.entropy.last()
                            val mcus = if (f != null && f.dct && sh != null && sh.complete && lines != null) mcuCount(f, sh, lines) else null
                            when {
                                truncatedScan -> notes += "$at: 스캔 데이터가 잘려 RST 개수 미검사 / scan data cut off"
                                mcus == null -> notes += "$at: MCU 수를 계산할 수 없어(무손실·DNL 미상 등) RST 개수 미검사 / MCU count unknown"
                                else -> {
                                    val expected = ceilDiv(mcus, interval.toLong()) - 1
                                    if (e.restartCount != expected) {
                                        v += "$at: RST ${e.restartCount}개 ≠ 예상 $expected (MCU $mcus, Ri $interval) / restart marker count"
                                        ranges += e.start until e.end
                                    } else notes += "$at: Ri=$interval, MCU $mcus, RST ${e.restartCount}개 (예상 $expected) / as expected"
                                }
                            }
                        }
                        else -> notes += "$at: 재시작 없음 / restart disabled"
                    }
                }
            }
        }
        return ctx.finding(id, v, ranges, notes.ifEmpty { listOf("스캔 없음 / no scan") })
    }

    private fun eoi(ctx: JpegContext): ConformanceFinding {
        val id = "t81.eoi"
        if (ctx.stream.capped) return ctx.skip(id, NOT_CHECKED, "세그먼트가 ${JpegStreamScanner.MAX_SEGMENTS}개를 넘어 중단 / too many segments")
        val e = ctx.stream.eoi
        if (e != null) return ctx.finding(id, emptyList(), emptyList(), listOf("EOI @${hex(e.offset)}"))
        val why = when {
            ctx.stream.truncated -> "파일 끝(${ctx.length})까지 EOI 없음 — 잘린 파일 / no EOI before the end of the file (truncated)"
            else -> "마커 구문 오류로 EOI에 도달하지 못함 @${hex(ctx.stream.end)} / marker syntax error before EOI"
        }
        return ctx.finding(id, listOf(why), listOf(ctx.stream.end until ctx.length), emptyList())
    }
}
```

```kotlin
package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceReport
import java.util.concurrent.CancellationException

object JpegConformanceChecker {
    const val PROFILE = "ITU-T T.81 + T.871 (JFIF) + CIPA DC-008-2026 (Exif 3.1) + DC-007-2026 (MPF) phase 1"

    val LIMITATIONS = listOf(
        "엔트로피 부호화 데이터는 해독하지 않습니다(RSTm 마커만 셉니다) / Entropy-coded data is not decoded (only RSTm markers are counted)",
        "계층(hierarchical) 모드, XMP, ICC, MakerNote 내부, 비압축 Exif 썸네일, MPF 2번째 이후 이미지의 내부는 1단계에서 검사하지 않습니다 / " +
            "Hierarchical mode, XMP, ICC, MakerNote contents, uncompressed Exif thumbnails and the inside of MPF secondary images are not checked in phase 1",
        "Exif·MPF 규칙은 첫(주) 이미지의 APP1/APP2만 검사합니다 / Exif and MPF rules check the first (primary) image's APP1/APP2 only",
    )

    private val ORDER = JpegRules.all.withIndex().associate { it.value.id to it.index }

    fun check(reader: ByteReader): ConformanceReport {
        val findings = ArrayList<ConformanceFinding>()
        val error = try {
            val ctx = JpegContext(reader, JpegStreamScanner.scan(reader))
            findings += JpegT81Rules.evaluate(ctx)
            findings += JpegJfifRules.evaluate(ctx)
            findings += JpegExifRules.evaluate(ctx)
            val mpf = JpegMpfRules.evaluate(ctx)
            findings += mpf.findings
            findings += JpegTrailer.evaluate(ctx, mpf.images)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "검사 중 오류: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim()
        }
        return ConformanceReport(
            format = "JPEG",
            profile = PROFILE,
            findings = findings.sortedBy { ORDER[it.rule.id] ?: Int.MAX_VALUE },
            limitations = LIMITATIONS,
            inspectionError = error,
        )
    }
}
```

- [ ] **Step 4:** GREEN. **Step 5:** commit `feat: check JPEG T.81 marker syntax, headers, tables and restart markers`.

---

### Task 5: JFIF (T.871) rules

**Files:** Create `JpegJfifRules.kt`; test `JpegJfifRulesTest.kt`; add `findings += JpegJfifRules.evaluate(ctx)` to the checker.

- [ ] **Step 1: Failing tests**

```kotlin
package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JpegJfifRulesTest {
    private fun failsOnly(bytes: ByteArray, vararg ids: String) = assertEquals(ids.toSet(), check(bytes).ids(FAIL, WARN), check(bytes).dump().toString())
    private fun jfxx(code: Int) = seg(0xE0, 0x4A, 0x46, 0x58, 0x58, 0x00, code)

    @Test fun `no JFIF APP0 makes every JFIF rule not applicable`() {
        val r = check(baseline(app = ByteArray(0)))
        listOf("jfif.position", "jfif.fields", "jfif.version", "jfif.frame", "jfif.extension", "jfif.app0").forEach { assertEquals(NOT_APPLICABLE, r.status(it), it) }
    }

    @Test fun `position, fields and version`() {
        failsOnly(baseline(app = seg(0xFE, 0x41) + jfifApp0()), "jfif.position")
        failsOnly(baseline(app = jfifApp0(hd = 0)), "jfif.fields")
        failsOnly(baseline(app = jfifApp0(units = 3)), "jfif.fields")
        // 1.00 / 1.01 (libjpeg-turbo default) = earlier JFIF editions → WARN; anything else ≠ 1.02 → FAIL.
        listOf(0, 1).forEach { minor ->
            val r = check(baseline(app = jfifApp0(minor = minor)))
            assertEquals(WARN, r.status("jfif.version"), r.dump().toString())
            assertEquals(emptySet(), r.ids(FAIL))
            assertTrue(r.findings.single { it.rule.id == "jfif.version" }.details.single().contains("earlier JFIF 1.0$minor edition"))
        }
        assertEquals(FAIL, check(baseline(app = jfifApp0(minor = 3))).status("jfif.version"))
        assertEquals(FAIL, check(baseline(app = jfifApp0(major = 2, minor = 1))).status("jfif.version"))
    }

    @Test fun `frame constraints`() {
        failsOnly(baseline(frame = sof(comps = listOf(intArrayOf(1, 1, 1, 0), intArrayOf(2, 1, 1, 0)))), "jfif.frame")   // Nf = 2
        failsOnly(baseline(frame = sof(comps = listOf(intArrayOf(7, 1, 1, 0))), scan = sos(listOf(intArrayOf(7, 0, 0))) + ecs()), "jfif.frame") // C1 != 1
    }

    @Test fun `extensions and application APP0s`() {
        assertEquals(com.multiviewer.parser.conformance.ConformanceStatus.PASS, check(baseline(app = jfifApp0() + jfxx(0x10))).status("jfif.extension"))
        failsOnly(baseline(app = jfifApp0() + jfxx(0x12)), "jfif.extension")
        failsOnly(baseline(app = jfifApp0() + seg(0xE1, 0x41, 0) + jfxx(0x10)), "jfif.extension")
        failsOnly(baseline(app = jfifApp0() + jfifApp0()), "jfif.app0")
        failsOnly(baseline(app = jfifApp0() + seg(0xE0, 0x41, 0x42)), "jfif.app0")    // no zero-terminated identifier
    }
}
```

- [ ] **Step 2:** RED. **Step 3: Implement**

```kotlin
package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.JFIF_ID
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.JFXX_ID
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.hex
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.markerName

/** ITU-T T.871 (JFIF) rules; they apply only when the file carries a JFIF APP0 ("JFIF\0"). */
internal object JpegJfifRules {
    private val IDS = listOf("jfif.position", "jfif.fields", "jfif.version", "jfif.frame", "jfif.extension", "jfif.app0")
    private val EXTENSION_CODES = setOf(0x10, 0x11, 0x13)

    fun evaluate(ctx: JpegContext): List<ConformanceFinding> {
        val app0 = ctx.segments.filter { it.marker == 0xE0 }
        val jfif = app0.filter { ctx.startsWith(it, JFIF_ID) }
        val first = jfif.firstOrNull() ?: return IDS.map { ctx.skip(it, NOT_APPLICABLE, "JFIF APP0 없음 / no JFIF APP0") }
        return listOf(position(ctx, first), fields(ctx, first), version(ctx, first), frame(ctx), extension(ctx, first), app0(ctx, first, app0))
    }

    private fun position(ctx: JpegContext, s: JpegSegment): ConformanceFinding {
        val ok = ctx.segments.getOrNull(0)?.marker == 0xD8 && ctx.segments.getOrNull(1) == s
        val prev = ctx.segments.getOrNull(ctx.segments.indexOf(s) - 1)
        return ctx.finding("jfif.position",
            if (ok) emptyList() else listOf("JFIF APP0 @${hex(s.offset)}가 SOI 바로 뒤가 아님 — 앞: ${prev?.let { markerName(it.marker) + " @" + hex(it.offset) }} / does not immediately follow SOI"),
            if (ok) emptyList() else listOf(s.range), listOf("JFIF APP0 @${hex(s.offset)} = SOI 바로 뒤 / immediately after SOI"))
    }

    private fun fields(ctx: JpegContext, s: JpegSegment): ConformanceFinding {
        val p = ctx.payload(s)
        if (p.size < 14) return ctx.finding("jfif.fields", listOf("JFIF APP0 @${hex(s.offset)}: Lp=${s.length} — 필수 필드(16바이트)보다 짧음 / shorter than 16"), listOf(s.range), emptyList())
        val units = p[7].toInt() and 0xFF
        val hd = ((p[8].toInt() and 0xFF) shl 8) or (p[9].toInt() and 0xFF)
        val vd = ((p[10].toInt() and 0xFF) shl 8) or (p[11].toInt() and 0xFF)
        val tw = p[12].toInt() and 0xFF
        val th = p[13].toInt() and 0xFF
        val k = tw * th
        val v = ArrayList<String>()
        if (s.length != 16 + 3 * k) v += "Lp=${s.length} ≠ 16 + 3×k (k = ${tw}×$th, ${16 + 3 * k})"
        if (units > 2) v += "units=$units — 0, 1, 2만 정의 / only 0, 1, 2 are defined"
        if (hd == 0 || vd == 0) v += "Hdensity=$hd, Vdensity=$vd — 0이 아니어야 함 / must be non-zero"
        return ctx.finding("jfif.fields", v.map { "JFIF APP0 @${hex(s.offset)}: $it" }, if (v.isEmpty()) emptyList() else listOf(s.range),
            listOf("Lp=${s.length}, units=$units, density ${hd}x$vd, 썸네일 ${tw}x$th"))
    }

    private fun version(ctx: JpegContext, s: JpegSegment): ConformanceFinding {
        val p = ctx.payload(s)
        if (p.size < 7) return ctx.skip("jfif.version", NOT_CHECKED, "버전 필드가 없음 / no version field")
        val major = p[5].toInt() and 0xFF
        val minor = p[6].toInt() and 0xFF
        val ok = major == 1 && minor == 2
        val legacy = ((major shl 8) or minor) in JpegRules.LEGACY_JFIF_VERSIONS
        val detail = if (legacy) "JFIF APP0 @${hex(s.offset)}: version %d.%02d — 이전 JFIF %d.%02d 판과 일치, T.871은 X'0102'를 요구 / matches the earlier JFIF %d.%02d edition; T.871 requires X'0102'".format(major, minor, major, minor, major, minor)
            else "JFIF APP0 @${hex(s.offset)}: version %d.%02d (0x%02X%02X) ≠ 0x0102 — T.871은 1.02를 요구 / T.871 requires X'0102'".format(major, minor, major, minor)
        val f = ctx.finding("jfif.version", if (ok) emptyList() else listOf(detail),
            if (ok) emptyList() else listOf(s.offset + 9 until s.offset + 11), listOf("version 1.02"))
        return if (legacy) f.copy(status = com.multiviewer.parser.conformance.ConformanceStatus.WARN) else f
    }

    private fun frame(ctx: JpegContext): ConformanceFinding {
        val f = ctx.frame ?: return ctx.skip("jfif.frame", NOT_CHECKED, "프레임 헤더를 읽을 수 없음 / frame header unreadable")
        val v = ArrayList<String>()
        if (f.declaredCount != 1 && f.declaredCount != 3) v += "Nf=${f.declaredCount} — JFIF는 1 또는 3 성분 / JFIF has 1 or 3 colour channels"
        if (f.precision != 8) v += "P=${f.precision} — JFIF는 8비트 / JFIF has 8 bits per channel"
        val ids = f.components.map { it.id }
        val expected = if (f.declaredCount == 1) listOf(1) else listOf(1, 2, 3)
        if ((f.declaredCount == 1 || f.declaredCount == 3) && ids != expected)
            v += "성분 ID ${ids} ≠ $expected (§10.1: C1=1 Y, C2=2 Cb, C3=3 Cr, p.13) / component identifiers"
        return ctx.finding("jfif.frame", v.map { "${markerName(f.marker)} @${hex(f.segment.offset)}: $it" },
            if (v.isEmpty()) emptyList() else listOf(f.segment.range), listOf("Nf=${f.declaredCount}, P=${f.precision}, 성분 ID $ids"))
    }

    private fun extension(ctx: JpegContext, jfif: JpegSegment): ConformanceFinding {
        val jfxx = ctx.segments.filter { it.marker == 0xE0 && ctx.startsWith(it, JFXX_ID) }
        if (jfxx.isEmpty()) return ctx.skip("jfif.extension", NOT_APPLICABLE, "JFXX 확장 없음 / no JFIF extension")
        val start = ctx.segments.indexOf(jfif) + 1
        val run = ctx.segments.drop(start).takeWhile { it in jfxx }
        val v = ArrayList<String>()
        jfxx.filter { it !in run }.forEach { v += "JFXX @${hex(it.offset)}: JFIF APP0 바로 뒤(연속)가 아님 / does not immediately follow the JFIF APP0" }
        jfxx.forEach { s ->
            val p = ctx.payload(s)
            if (p.size < 6) v += "JFXX @${hex(s.offset)}: extension_code 없음 / no extension_code"
            else if ((p[5].toInt() and 0xFF) !in EXTENSION_CODES) v += "JFXX @${hex(s.offset)}: extension_code=0x%02X — 0x10/0x11/0x13만 정의 (§10.2, p.13) / undefined".format(p[5].toInt() and 0xFF)
        }
        return ctx.finding("jfif.extension", v, if (v.isEmpty()) emptyList() else jfxx.map { it.range }, listOf("JFXX ${jfxx.size}개, JFIF 바로 뒤 / immediately after the JFIF APP0"))
    }

    private fun app0(ctx: JpegContext, jfif: JpegSegment, app0: List<JpegSegment>): ConformanceFinding {
        val v = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        app0.filter { it != jfif && ctx.startsWith(it, JFIF_ID) }.forEach { v += "APP0 @${hex(it.offset)}: 두 번째 \"JFIF\" 식별자 — 응용 APP0는 JFIF/JFXX 식별자를 쓸 수 없음 / application APP0 shall not use \"JFIF\""; ranges += it.range }
        val others = app0.filter { !ctx.startsWith(it, JFIF_ID) && !ctx.startsWith(it, JFXX_ID) }
        others.forEach { s ->
            val p = ctx.payload(s)
            if (p.isEmpty() || p.none { it == 0.toByte() }) { v += "APP0 @${hex(s.offset)}: 0으로 끝나는 식별 문자열이 없음 / no zero-terminated identifier string"; ranges += s.range }
        }
        return ctx.finding("jfif.app0", v, ranges, listOf("응용 APP0 ${others.size}개, 식별자 정상 / ${others.size} application APP0(s) with identifiers"))
    }
}
```

- [ ] **Step 4:** GREEN (all JPEG tests). **Step 5:** commit `feat: check JFIF APP0 rules`.

---

### Task 6: Exif APP1 rules

**Files:** Create `ExifTiff.kt` (TIFF reader + DC-008-2026 (Exif 3.1) tag Type/Count table — 160 tags incl. the seven
new 3.1 tags, each row's physical page re-read from the 2026 PDF),
`JpegExifRules.kt`; add the `exifSegment`/`exif` vals to `JpegContext` and `findings += JpegExifRules.evaluate(ctx)`
to the checker.

- [ ] **Step 1: Failing tests** — create `JpegFixtureTest.kt` from Task 8 with only the `exif-*` rows of the variant
  map plus `sips.jpg` (`jfif.version` already exists), and assert `exif-mpf.jpg` has no Exif FAIL/WARN. RED.
- [ ] **Step 2: Implement**

```kotlin
package com.multiviewer.parser.conformance.jpeg

enum class ExifIfd(val label: String) { IFD0("0th IFD"), IFD1("1st IFD"), EXIF("Exif IFD"), GPS("GPS IFD"), INTEROP("Interoperability IFD"), MP_INDEX("MP Index IFD") }

/** One 12-byte IFD entry. Offsets are relative to the TIFF header (byte-order mark). */
data class TiffEntry(val tag: Int, val type: Int, val count: Long, val entryOffset: Int, val valueOffset: Long) {
    /** Value size in bytes, or null for a type whose size is unknown. */
    val byteSize: Long? get() = TYPE_SIZES[type]?.let { it * count }
    val inline: Boolean get() = (byteSize ?: Long.MAX_VALUE) <= 4

    companion object {
        val TYPE_SIZES: Map<Int, Long> = mapOf(1 to 1L, 2 to 1L, 3 to 2L, 4 to 4L, 5 to 8L, 6 to 1L, 7 to 1L, 8 to 2L,
            9 to 4L, 10 to 8L, 11 to 4L, 12 to 8L, 13 to 4L, 129 to 1L)
    }
}

data class TiffIfd(val kind: ExifIfd, val offset: Long, val entries: List<TiffEntry>, val next: Long) {
    fun entry(tag: Int): TiffEntry? = entries.firstOrNull { it.tag == tag }
}

/**
 * A TIFF structure held in memory (an Exif APP1 or MPF APP2 payload, at most 64 KiB). [base] is the absolute file offset
 * of the TIFF header, for byte ranges. Reading never throws: problems are collected instead.
 */
class ExifTiff private constructor(val bytes: ByteArray, val base: Long) {
    var littleEndian = false
        private set
    val headerProblems = ArrayList<String>()
    val structureProblems = ArrayList<String>()
    val ifds = LinkedHashMap<ExifIfd, TiffIfd>()
    val headerOk: Boolean get() = headerProblems.isEmpty()

    fun u8(o: Long): Int = bytes[o.toInt()].toInt() and 0xFF
    fun u16(o: Long): Int = if (littleEndian) u8(o) or (u8(o + 1) shl 8) else (u8(o) shl 8) or u8(o + 1)
    fun u32(o: Long): Long = if (littleEndian) u16(o).toLong() or (u16(o + 2).toLong() shl 16) else (u16(o).toLong() shl 16) or u16(o + 2).toLong()
    fun inRange(o: Long, size: Long): Boolean = o >= 0 && size >= 0 && o + size <= bytes.size

    /** SHORT/LONG value of a single-count entry, or null. */
    fun uint(e: TiffEntry): Long? = when {
        e.count != 1L -> null
        e.type == 3 -> u16(e.valueOffset).toLong()
        e.type == 4 || e.type == 13 -> u32(e.valueOffset)
        else -> null
    }

    fun valueBytes(e: TiffEntry): ByteArray? {
        val size = e.byteSize ?: return null
        if (!inRange(e.valueOffset, size) || size > Int.MAX_VALUE) return null
        return bytes.copyOfRange(e.valueOffset.toInt(), (e.valueOffset + size).toInt())
    }

    /** Reads the IFD at [offset]; null (with a structure problem) when it does not fit. */
    fun readIfd(kind: ExifIfd, offset: Long): TiffIfd? {
        val where = "${kind.label} @${offset}"
        if (offset < 8 || !inRange(offset, 2)) { structureProblems += "$where: IFD 오프셋이 데이터 밖 / IFD offset outside the data (${bytes.size} bytes)"; return null }
        val n = u16(offset)
        if (!inRange(offset, 2L + 12L * n + 4)) { structureProblems += "$where: 항목 ${n}개 + 다음 IFD 오프셋이 데이터 끝을 넘음 / $n entries overrun the data"; return null }
        val entries = (0 until n).map { i ->
            val e = offset + 2 + 12L * i
            val type = u16(e + 2)
            val count = u32(e + 4)
            val size = TiffEntry.TYPE_SIZES[type]?.let { it * count }
            val valueOffset = if (size != null && size <= 4) e + 8 else u32(e + 8)
            TiffEntry(u16(e), type, count, e.toInt(), valueOffset)
        }
        entries.filter { it.byteSize != null && !it.inline && !inRange(it.valueOffset, it.byteSize!!) }.forEach {
            structureProblems += "$where: 태그 0x%04X 값(오프셋 %d, %d바이트)이 데이터 밖 / value outside the data".format(it.tag, it.valueOffset, it.byteSize)
        }
        return TiffIfd(kind, offset, entries, u32(offset + 2 + 12L * n))
    }

    companion object {
        /** TIFF header + Exif IFD tree (0th → 1st, 0th → Exif/GPS, Exif → Interoperability). */
        fun read(bytes: ByteArray, base: Long): ExifTiff {
            val t = ExifTiff(bytes, base)
            if (!t.readHeader()) return t
            t.walkExif()
            return t
        }

        /** Header only (MPF): byte order, 42, first IFD offset. */
        fun header(bytes: ByteArray, base: Long): ExifTiff = ExifTiff(bytes, base).also { it.readHeader() }
    }

    var firstIfd: Long = 0
        private set

    private fun readHeader(): Boolean {
        if (bytes.size < 8) { headerProblems += "TIFF 헤더 8바이트가 없음 (${bytes.size}바이트) / no 8-byte TIFF header"; return false }
        littleEndian = when {
            bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() -> true
            bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte() -> false
            else -> { headerProblems += "바이트 순서 0x%02X%02X — \"II\"/\"MM\"이 아님 / byte order is not II or MM".format(u8(0), u8(1)); return false }
        }
        if (u16(2) != 42) { headerProblems += "식별값 ${u16(2)} ≠ 42 (002A.H)"; return false }
        firstIfd = u32(4)
        if (firstIfd < 8 || firstIfd + 2 > bytes.size) { headerProblems += "0th IFD 오프셋 $firstIfd — 데이터(${bytes.size}바이트) 밖 / outside the data"; return false }
        return true
    }

    private fun walkExif() {
        val visited = HashSet<Long>()
        fun visit(kind: ExifIfd, offset: Long): TiffIfd? {
            if (!visited.add(offset)) { structureProblems += "${kind.label} @$offset: 이미 읽은 IFD를 다시 가리킴(순환/공유) / IFD loop or shared IFD"; return null }
            return readIfd(kind, offset)?.also { ifds[kind] = it }
        }
        val ifd0 = visit(ExifIfd.IFD0, firstIfd) ?: return
        if (ifd0.next != 0L) {
            visit(ExifIfd.IFD1, ifd0.next)?.let { ifd1 ->
                if (ifd1.next != 0L) structureProblems += "1st IFD @${ifd1.offset}: 다음 IFD 오프셋 ${ifd1.next} — APP1에는 0th/1st IFD 최대 2개 (§4.5.4, p.32) / at most two IFDs"
            }
        }
        fun pointer(from: TiffIfd, tag: Int, kind: ExifIfd) {
            val e = from.entry(tag) ?: return
            val off = uint(e)
            if (off == null) structureProblems += "${from.kind.label}: ${kind.label} 포인터(0x%04X)를 읽을 수 없음 (Type ${e.type}, Count ${e.count}) / unreadable pointer".format(tag)
            else visit(kind, off)
        }
        pointer(ifd0, 0x8769, ExifIfd.EXIF)
        pointer(ifd0, 0x8825, ExifIfd.GPS)
        ifds[ExifIfd.EXIF]?.let { pointer(it, 0xA005, ExifIfd.INTEROP) }
    }
}

/** One tag definition of CIPA DC-008-2026 (Type, Count, physical PDF page of its definition). */
data class ExifTagDef(val group: ExifTagGroup, val tag: Int, val name: String, val types: Set<Int>, val count: Long?, val page: Int)

enum class ExifTagGroup { TIFF, EXIF, GPS, INTEROP }

fun ExifIfd.group(): ExifTagGroup = when (this) {
    ExifIfd.IFD0, ExifIfd.IFD1, ExifIfd.MP_INDEX -> ExifTagGroup.TIFF
    ExifIfd.EXIF -> ExifTagGroup.EXIF
    ExifIfd.GPS -> ExifTagGroup.GPS
    ExifIfd.INTEROP -> ExifTagGroup.INTEROP
}

/** Tag Type/Count definitions copied from CIPA DC-008-Translation-2026 (Exif 3.1) §4.6.3-4.6.8 ("Tag = / Type = / Count ="). */
object ExifTagTable {
    private fun d(g: ExifTagGroup, tag: Int, name: String, types: Set<Int>, count: Long?, page: Int) = ExifTagDef(g, tag, name, types, count, page)
    private val TIFF = ExifTagGroup.TIFF
    private val EXIF = ExifTagGroup.EXIF
    private val GPS = ExifTagGroup.GPS
    private val INTEROP = ExifTagGroup.INTEROP

    val all: List<ExifTagDef> = listOf(
        d(TIFF, 0x8769, "ExifIFDPointer", setOf(4), 1, 39),
        d(TIFF, 0x8825, "GPSInfoIFDPointer", setOf(4), 1, 40),
        d(EXIF, 0xA005, "InteroperabilityIFDPointer", setOf(4), 1, 40),
        d(TIFF, 0x0100, "ImageWidth", setOf(3, 4), 1, 43),
        d(TIFF, 0x0101, "ImageLength", setOf(3, 4), 1, 43),
        d(TIFF, 0x0102, "BitsPerSample", setOf(3), 3, 43),
        d(TIFF, 0x0103, "Compression", setOf(3), 1, 44),
        d(TIFF, 0x0106, "PhotometricInterpretation", setOf(3), 1, 44),
        d(TIFF, 0x0112, "Orientation", setOf(3), 1, 44),
        d(TIFF, 0x0115, "SamplesPerPixel", setOf(3), 1, 48),
        d(TIFF, 0x011A, "XResolution", setOf(5), 1, 48),
        d(TIFF, 0x011B, "YResolution", setOf(5), 1, 48),
        d(TIFF, 0x011C, "PlanarConfiguration", setOf(3), 1, 49),
        d(TIFF, 0x0128, "ResolutionUnit", setOf(3), 1, 49),
        d(TIFF, 0x0212, "YCbCrSubSampling", setOf(3), 2, 49),
        d(TIFF, 0x0213, "YCbCrPositioning", setOf(3), 1, 50),
        d(TIFF, 0x0111, "StripOffsets", setOf(3, 4), null, 50),
        d(TIFF, 0x0116, "RowsPerStrip", setOf(3, 4), 1, 51),
        d(TIFF, 0x0117, "StripByteCounts", setOf(3, 4), null, 51),
        d(TIFF, 0x0201, "JPEGInterchangeFormat", setOf(4), 1, 51),
        d(TIFF, 0x0202, "JPEGInterchangeFormatLength", setOf(4), 1, 51),
        d(TIFF, 0x012D, "TransferFunction", setOf(3), 768, 52),
        d(TIFF, 0x013E, "WhitePoint", setOf(5), 2, 52),
        d(TIFF, 0x013F, "PrimaryChromaticities", setOf(5), 6, 52),
        d(TIFF, 0x0211, "YCbCrCoefficients", setOf(5), 3, 52),
        d(TIFF, 0x0214, "ReferenceBlackWhite", setOf(5), 6, 52),
        d(TIFF, 0x010E, "ImageDescription", setOf(2, 129), null, 53),
        d(TIFF, 0x010F, "Make", setOf(2, 129), null, 53),
        d(TIFF, 0x0110, "Model", setOf(2, 129), null, 53),
        d(TIFF, 0x0131, "Software", setOf(2, 129), null, 53),
        d(TIFF, 0x0132, "DateTime", setOf(2), 20, 54),
        d(TIFF, 0x013B, "Artist", setOf(2, 129), null, 54),
        d(TIFF, 0x8298, "Copyright", setOf(2, 129), null, 54),
        d(EXIF, 0x9000, "ExifVersion", setOf(7), 4, 58),
        d(EXIF, 0xA000, "FlashpixVersion", setOf(7), 4, 58),
        d(EXIF, 0xA001, "ColorSpace", setOf(3), 1, 58),
        d(EXIF, 0xA500, "Gamma", setOf(5), 1, 59),
        d(EXIF, 0xA002, "PixelXDimension", setOf(3, 4), 1, 59),
        d(EXIF, 0xA003, "PixelYDimension", setOf(3, 4), 1, 59),
        d(EXIF, 0x9101, "ComponentsConfiguration", setOf(7), 4, 60),
        d(EXIF, 0x9102, "CompressedBitsPerPixel", setOf(5), 1, 60),
        d(EXIF, 0x927C, "MakerNote", setOf(7), null, 60),
        d(EXIF, 0x9286, "UserComment", setOf(7), null, 60),
        d(EXIF, 0xA004, "RelatedSoundFile", setOf(2), 13, 63),
        d(EXIF, 0x9003, "DateTimeOriginal", setOf(2), 20, 63),
        d(EXIF, 0x9004, "DateTimeDigitized", setOf(2), 20, 64),
        d(EXIF, 0x9010, "OffsetTime", setOf(2), 7, 64),
        d(EXIF, 0x9011, "OffsetTimeOriginal", setOf(2), 7, 64),
        d(EXIF, 0x9012, "OffsetTimeDigitized", setOf(2), 7, 65),
        d(EXIF, 0x9290, "SubsecTime", setOf(2), null, 65),
        d(EXIF, 0x9291, "SubsecTimeOriginal", setOf(2), null, 65),
        d(EXIF, 0x9292, "SubsecTimeDigitized", setOf(2), null, 65),
        d(EXIF, 0x829A, "ExposureTime", setOf(5), 1, 66),
        d(EXIF, 0x829D, "FNumber", setOf(5), 1, 66),
        d(EXIF, 0x8822, "ExposureProgram", setOf(3), 1, 66),
        d(EXIF, 0x8824, "SpectralSensitivity", setOf(2), null, 67),
        d(EXIF, 0x8827, "PhotographicSensitivity", setOf(3), null, 67),
        d(EXIF, 0x8828, "OECF", setOf(7), null, 67),
        d(EXIF, 0x8830, "SensitivityType", setOf(3), 1, 68),
        d(EXIF, 0x8831, "StandardOutputSensitivity", setOf(4), 1, 69),
        d(EXIF, 0x8832, "RecommendedExposureIndex", setOf(4), 1, 69),
        d(EXIF, 0x8833, "ISOSpeed", setOf(4), 1, 69),
        d(EXIF, 0x8834, "ISOSpeedLatitudeyyy", setOf(4), 1, 70),
        d(EXIF, 0x8835, "ISOSpeedLatitudezzz", setOf(4), 1, 70),
        d(EXIF, 0x9201, "ShutterSpeedValue", setOf(10), 1, 70),
        d(EXIF, 0x9202, "ApertureValue", setOf(5), 1, 70),
        d(EXIF, 0x9203, "BrightnessValue", setOf(10), 1, 70),
        d(EXIF, 0x9204, "ExposureBiasValue", setOf(10), 1, 70),
        d(EXIF, 0x9205, "MaxApertureValue", setOf(5), 1, 71),
        d(EXIF, 0x9206, "SubjectDistance", setOf(5), 1, 71),
        d(EXIF, 0x9207, "MeteringMode", setOf(3), 1, 71),
        d(EXIF, 0x9208, "LightSource", setOf(3), 1, 71),
        d(EXIF, 0x9209, "Flash", setOf(3), 1, 72),
        d(EXIF, 0x9214, "SubjectArea", setOf(3), null, 73),
        d(EXIF, 0x920A, "FocalLength", setOf(5), 1, 75),
        d(EXIF, 0xA20B, "FlashEnergy", setOf(5), 1, 75),
        d(EXIF, 0xA20E, "FocalPlaneXResolution", setOf(5), 1, 76),
        d(EXIF, 0xA20F, "FocalPlaneYResolution", setOf(5), 1, 77),
        d(EXIF, 0xA210, "FocalPlaneResolutionUnit", setOf(3), 1, 77),
        d(EXIF, 0xA214, "SubjectLocation", setOf(3), 2, 77),
        d(EXIF, 0xA215, "ExposureIndex", setOf(5), 1, 77),
        d(EXIF, 0xA217, "SensingMethod", setOf(3), 1, 78),
        d(EXIF, 0xA300, "FileSource", setOf(7), 1, 78),
        d(EXIF, 0xA301, "SceneType", setOf(7), 1, 78),
        d(EXIF, 0xA302, "CFAPattern", setOf(7), null, 78),
        d(EXIF, 0xA401, "CustomRendered", setOf(3), 1, 80),
        d(EXIF, 0xA402, "ExposureMode", setOf(3), 1, 80),
        d(EXIF, 0xA403, "WhiteBalance", setOf(3), 1, 80),
        d(EXIF, 0xA404, "DigitalZoomRatio", setOf(5), 1, 80),
        d(EXIF, 0xA405, "FocalLengthIn35mmFilm", setOf(3), 1, 81),
        d(EXIF, 0xA406, "SceneCaptureType", setOf(3), 1, 81),
        d(EXIF, 0xA407, "GainControl", setOf(3), 1, 81),
        d(EXIF, 0xA408, "Contrast", setOf(3), 1, 81),
        d(EXIF, 0xA409, "Saturation", setOf(3), 1, 82),
        d(EXIF, 0xA40A, "Sharpness", setOf(3), 1, 82),
        d(EXIF, 0xA40B, "DeviceSettingDescription", setOf(7), null, 82),
        d(EXIF, 0xA40C, "SubjectDistanceRange", setOf(3), 1, 83),
        d(EXIF, 0xA460, "CompositeImage", setOf(3), 1, 86),
        d(EXIF, 0xA461, "SourceImageNumberOfCompositeImage", setOf(3), 2, 86),
        d(EXIF, 0xA462, "SourceExposureTimesOfCompositeImage", setOf(7), null, 87),
        d(EXIF, 0x9400, "Temperature", setOf(10), 1, 88),
        d(EXIF, 0x9401, "Humidity", setOf(5), 1, 88),
        d(EXIF, 0x9402, "Pressure", setOf(5), 1, 89),
        d(EXIF, 0x9403, "WaterDepth", setOf(10), 1, 89),
        d(EXIF, 0x9404, "Acceleration", setOf(5), 1, 89),
        d(EXIF, 0x9405, "CameraElevationAngle", setOf(10), 1, 89),
        d(EXIF, 0xA420, "ImageUniqueID", setOf(2), 33, 90),
        d(EXIF, 0xA430, "CameraOwnerName", setOf(2, 129), null, 90),
        d(EXIF, 0xA431, "BodySerialNumber", setOf(2), null, 90),
        d(EXIF, 0xA432, "LensSpecification", setOf(5), 4, 90),
        d(EXIF, 0xA433, "LensMake", setOf(2, 129), null, 91),
        d(EXIF, 0xA434, "LensModel", setOf(2, 129), null, 91),
        d(EXIF, 0xA435, "LensSerialNumber", setOf(2), null, 91),
        d(EXIF, 0xA436, "ImageTitle", setOf(2, 129), null, 91),
        d(EXIF, 0xA437, "Photographer", setOf(2, 129), null, 91),
        d(EXIF, 0xA438, "ImageEditor", setOf(2, 129), null, 92),
        d(EXIF, 0xA439, "CameraFirmware", setOf(2, 129), null, 92),
        d(EXIF, 0xA43A, "RAWDevelopingSoftware", setOf(2, 129), null, 92),
        d(EXIF, 0xA43B, "ImageEditingSoftware", setOf(2, 129), null, 92),
        d(EXIF, 0xA43C, "MetadataEditingSoftware", setOf(2, 129), null, 93),
        d(EXIF, 0x9287, "LearningOptOutIn", setOf(7), null, 61),
        d(EXIF, 0xA40D, "DevelopmentType", setOf(3), 1, 84),
        d(EXIF, 0xA40E, "DevelopmentTypeDescription", setOf(129), null, 85),
        d(EXIF, 0xA40F, "DistortionCorrection", setOf(3), 1, 85),
        d(EXIF, 0xA410, "ChromaticAberrationCorrection", setOf(3), 1, 85),
        d(EXIF, 0xA411, "ShadingCorrection", setOf(3), 1, 85),
        d(EXIF, 0xA412, "NoiseReduction", setOf(3), 1, 85),
        d(GPS, 0x0000, "GPSVersionID", setOf(1), 4, 94),
        d(GPS, 0x0001, "GPSLatitudeRef", setOf(2), 2, 95),
        d(GPS, 0x0002, "GPSLatitude", setOf(5), 3, 95),
        d(GPS, 0x0003, "GPSLongitudeRef", setOf(2), 2, 95),
        d(GPS, 0x0004, "GPSLongitude", setOf(5), 3, 95),
        d(GPS, 0x0005, "GPSAltitudeRef", setOf(1), 1, 96),
        d(GPS, 0x0006, "GPSAltitude", setOf(5), 1, 96),
        d(GPS, 0x0007, "GPSTimeStamp", setOf(5), 3, 96),
        d(GPS, 0x0008, "GPSSatellites", setOf(2), null, 97),
        d(GPS, 0x0009, "GPSStatus", setOf(2), 2, 97),
        d(GPS, 0x000A, "GPSMeasureMode", setOf(2), 2, 97),
        d(GPS, 0x000B, "GPSDOP", setOf(5), 1, 97),
        d(GPS, 0x000C, "GPSSpeedRef", setOf(2), 2, 98),
        d(GPS, 0x000D, "GPSSpeed", setOf(5), 1, 98),
        d(GPS, 0x000E, "GPSTrackRef", setOf(2), 2, 98),
        d(GPS, 0x000F, "GPSTrack", setOf(5), 1, 98),
        d(GPS, 0x0010, "GPSImgDirectionRef", setOf(2), 2, 98),
        d(GPS, 0x0011, "GPSImgDirection", setOf(5), 1, 99),
        d(GPS, 0x0012, "GPSMapDatum", setOf(2), null, 99),
        d(GPS, 0x0013, "GPSDestLatitudeRef", setOf(2), 2, 99),
        d(GPS, 0x0014, "GPSDestLatitude", setOf(5), 3, 99),
        d(GPS, 0x0015, "GPSDestLongitudeRef", setOf(2), 2, 100),
        d(GPS, 0x0016, "GPSDestLongitude", setOf(5), 3, 100),
        d(GPS, 0x0017, "GPSDestBearingRef", setOf(2), 2, 100),
        d(GPS, 0x0018, "GPSDestBearing", setOf(5), 1, 100),
        d(GPS, 0x0019, "GPSDestDistanceRef", setOf(2), 2, 101),
        d(GPS, 0x001A, "GPSDestDistance", setOf(5), 1, 101),
        d(GPS, 0x001B, "GPSProcessingMethod", setOf(7), null, 101),
        d(GPS, 0x001C, "GPSAreaInformation", setOf(7), null, 102),
        d(GPS, 0x001D, "GPSDateStamp", setOf(2), 11, 102),
        d(GPS, 0x001E, "GPSDifferential", setOf(3), 1, 102),
        d(GPS, 0x001F, "GPSHPositioningError", setOf(5), 1, 103),
        d(INTEROP, 0x0001, "InteroperabilityIndex", setOf(2), null, 103),
    )

    private val byKey = all.associateBy { it.group to it.tag }

    fun def(ifd: ExifIfd, tag: Int): ExifTagDef? = byKey[ifd.group() to tag]

    fun name(ifd: ExifIfd, tag: Int): String = def(ifd, tag)?.name ?: "0x%04X".format(tag)

    /** Field types listed in §4.6.2 ("The following types are used in Exif"). */
    val EXIF_TYPES = setOf(1, 2, 3, 4, 5, 7, 9, 10, 129)
}
```

```kotlin
package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.hex
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.markerName

/**
 * CIPA DC-008-2026 (Exif 3.1) rules for the primary image's Exif APP1 ("Exif\0\0"), compressed (JPEG) primary image
 * column of Tables 17-21. They apply only when such an APP1 exists.
 */
internal object JpegExifRules {
    private val IDS = listOf("exif.app1.position", "exif.tiff.header", "exif.ifd.structure", "exif.ifd.order", "exif.tag.type",
        "exif.tag.ascii", "exif.tags.mandatory", "exif.tags.not_allowed", "exif.tags.recommended", "exif.dimensions", "exif.thumbnail")
    private const val HEADER_BAD = "TIFF 헤더 오류로 판단 불가 / TIFF header invalid"

    /** Table 17/18 "M" (compressed column) and §4.6.7.1.1 GPSVersionID (mandatory when a GPS tag is present). */
    private val MANDATORY_0TH = mapOf(0x8769 to "ExifIFDPointer", 0x0213 to "YCbCrPositioning")
    private val MANDATORY_EXIF = mapOf(0x9000 to "ExifVersion", 0x9101 to "ComponentsConfiguration", 0xA001 to "ColorSpace",
        0xA002 to "PixelXDimension", 0xA003 to "PixelYDimension")
    /** Table 21 "M" for a compressed thumbnail. */
    private val MANDATORY_1ST = mapOf(0x0103 to "Compression", 0x0201 to "JPEGInterchangeFormat", 0x0202 to "JPEGInterchangeFormatLength")
    /** Table 17 "N"/"J" (compressed column). */
    private val NOT_ALLOWED_0TH = mapOf(0x0100 to "ImageWidth", 0x0101 to "ImageLength", 0x0102 to "BitsPerSample", 0x0103 to "Compression",
        0x0106 to "PhotometricInterpretation", 0x0111 to "StripOffsets", 0x0115 to "SamplesPerPixel", 0x0116 to "RowsPerStrip",
        0x0117 to "StripByteCounts", 0x011C to "PlanarConfiguration", 0x0201 to "JPEGInterchangeFormat",
        0x0202 to "JPEGInterchangeFormatLength", 0x0212 to "YCbCrSubSampling")
    /** Table 21 "N"/"J" (compressed thumbnail column). */
    private val NOT_ALLOWED_1ST = mapOf(0x0100 to "ImageWidth", 0x0101 to "ImageLength", 0x0102 to "BitsPerSample",
        0x0106 to "PhotometricInterpretation", 0x0111 to "StripOffsets", 0x0115 to "SamplesPerPixel", 0x0116 to "RowsPerStrip",
        0x0117 to "StripByteCounts", 0x011C to "PlanarConfiguration", 0x0212 to "YCbCrSubSampling")
    /** Table 17/18 "R" (compressed column). */
    private val RECOMMENDED_0TH = mapOf(0x010E to "ImageDescription", 0x010F to "Make", 0x0110 to "Model", 0x0112 to "Orientation", 0x0132 to "DateTime")
    private val RECOMMENDED_EXIF = mapOf(0x829A to "ExposureTime", 0x9209 to "Flash", 0xA402 to "ExposureMode", 0xA403 to "WhiteBalance",
        0xA406 to "SceneCaptureType", 0xA460 to "CompositeImage")

    fun evaluate(ctx: JpegContext): List<ConformanceFinding> {
        val app1 = ctx.exifSegment ?: return IDS.map { ctx.skip(it, NOT_APPLICABLE, "Exif APP1 없음 / no Exif APP1") }
        val tiff = ctx.exif!!
        val head = listOf(position(ctx, app1), header(ctx, app1, tiff))
        if (!tiff.headerOk) return head + IDS.drop(2).map { ctx.skip(it, NOT_CHECKED, HEADER_BAD) }
        return head + listOf(structure(ctx, app1, tiff), order(ctx, tiff), types(ctx, tiff), ascii(ctx, tiff), mandatory(ctx, tiff),
            notAllowed(ctx, tiff), recommended(ctx, tiff), dimensions(ctx, tiff), thumbnail(ctx, app1, tiff))
    }

    private fun position(ctx: JpegContext, s: JpegSegment): ConformanceFinding {
        val ok = ctx.segments.getOrNull(0)?.marker == 0xD8 && ctx.segments.getOrNull(1) == s
        val prev = ctx.segments.getOrNull(ctx.segments.indexOf(s) - 1)
        return ctx.finding("exif.app1.position",
            if (ok) emptyList() else listOf("Exif APP1 @${hex(s.offset)}가 SOI 바로 뒤가 아님 — 앞: ${prev?.let { markerName(it.marker) + " @" + hex(it.offset) }} / not immediately after SOI"),
            if (ok) emptyList() else listOf(s.range), listOf("Exif APP1 @${hex(s.offset)} = SOI 바로 뒤 / immediately after SOI"))
    }

    private fun header(ctx: JpegContext, s: JpegSegment, t: ExifTiff): ConformanceFinding {
        val v = ArrayList<String>()
        val p = ctx.payload(s)
        if (p.size < 6 || p[5] != 0.toByte()) v += "Exif ID 코드가 \"Exif\" + 00.H 2바이트가 아님 / Exif ID code is not 'Exif' + two 00.H (§4.7.2.3, p.116)"
        v += t.headerProblems
        return ctx.finding("exif.tiff.header", v.map { "Exif APP1 @${hex(s.offset)}: $it" }, if (v.isEmpty()) emptyList() else listOf(s.range),
            listOf("${if (t.littleEndian) "II" else "MM"}, 42, 0th IFD 오프셋 ${t.firstIfd}"))
    }

    private fun structure(ctx: JpegContext, s: JpegSegment, t: ExifTiff): ConformanceFinding =
        ctx.finding("exif.ifd.structure", t.structureProblems.map { "Exif APP1 @${hex(s.offset)}: $it" },
            if (t.structureProblems.isEmpty()) emptyList() else listOf(s.range),
            listOf("IFD: " + t.ifds.values.joinToString { "${it.kind.label} (${it.entries.size})" }))

    private fun order(ctx: JpegContext, t: ExifTiff): ConformanceFinding {
        val v = t.ifds.values.mapNotNull { ifd ->
            val tags = ifd.entries.map { it.tag }
            val bad = tags.zipWithNext().firstOrNull { (a, b) -> b <= a } ?: return@mapNotNull null
            "${ifd.kind.label}: 태그 0x%04X 뒤에 0x%04X — 오름차순 아님(중복 포함) / not in ascending order".format(bad.first, bad.second)
        }
        return ctx.finding("exif.ifd.order", v, emptyList(), listOf("모든 IFD의 태그가 오름차순 / every IFD is in ascending tag order"))
    }

    private fun types(ctx: JpegContext, t: ExifTiff): ConformanceFinding {
        val v = ArrayList<String>()
        t.ifds.values.forEach { ifd ->
            ifd.entries.forEach { e ->
                val def = ExifTagTable.def(ifd.kind, e.tag)
                val name = ExifTagTable.name(ifd.kind, e.tag)
                if (e.type !in ExifTagTable.EXIF_TYPES) v += "${ifd.kind.label} $name: Type ${e.type} — Exif에서 쓰는 Type이 아님 / not an Exif type (§4.6.2, p.38)"
                else if (def != null && (e.type !in def.types || (def.count != null && e.count != def.count)))
                    v += "${ifd.kind.label} $name: Type ${e.type} Count ${e.count} ≠ 정의 Type ${def.types.joinToString("/")} Count ${def.count ?: "Any"} (p.${def.page})"
            }
        }
        return ctx.finding("exif.tag.type", v, emptyList(), listOf("정의된 태그의 Type·Count 일치 / Type and Count match the definitions"))
    }

    private fun ascii(ctx: JpegContext, t: ExifTiff): ConformanceFinding {
        val v = ArrayList<String>()
        t.ifds.values.forEach { ifd ->
            ifd.entries.filter { it.type == 2 || it.type == 129 }.forEach { e ->
                val b = t.valueBytes(e) ?: return@forEach
                val name = ExifTagTable.name(ifd.kind, e.tag)
                if (b.isEmpty() || b.last() != 0.toByte()) v += "${ifd.kind.label} $name: ${if (e.type == 2) "ASCII" else "UTF-8"} Count ${e.count}에 NULL 종료 미포함 / count does not include the NULL terminator"
                if (e.type == 129 && b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte())
                    v += "${ifd.kind.label} $name: UTF-8 BOM 사용 / BOM shall not be used (p.39)"
            }
        }
        return ctx.finding("exif.tag.ascii", v, emptyList(), listOf("ASCII/UTF-8 값이 NULL로 끝남 / ASCII and UTF-8 values are NULL-terminated"))
    }

    private fun compressedThumbnail(t: ExifTiff): Boolean? = t.ifds[ExifIfd.IFD1]?.let { ifd -> ifd.entry(0x0103)?.let { t.uint(it) == 6L } }

    private fun mandatory(ctx: JpegContext, t: ExifTiff): ConformanceFinding {
        val v = ArrayList<String>()
        val ifd0 = t.ifds[ExifIfd.IFD0]
        MANDATORY_0TH.forEach { (tag, name) -> if (ifd0?.entry(tag) == null) v += "0th IFD: $name 없음 / missing" }
        val exif = t.ifds[ExifIfd.EXIF]
        MANDATORY_EXIF.forEach { (tag, name) -> if (exif?.entry(tag) == null) v += "Exif IFD: $name 없음 / missing" }
        t.ifds[ExifIfd.GPS]?.let { if (it.entries.isNotEmpty() && it.entry(0x0000) == null) v += "GPS IFD: GPSVersionID 없음 — GPS 태그가 있으면 필수 (§4.6.7.1.1, p.94) / mandatory when a GPS tag is present" }
        val notes = ArrayList<String>()
        t.ifds[ExifIfd.IFD1]?.let { ifd1 ->
            if (ifd1.entry(0x0103) == null) v += "1st IFD: Compression 없음 / missing"
            when (compressedThumbnail(t)) {
                true -> MANDATORY_1ST.forEach { (tag, name) -> if (ifd1.entry(tag) == null) v += "1st IFD: $name 없음 / missing" }
                else -> notes += "1st IFD가 JPEG 압축 썸네일이 아님 — 비압축 썸네일 필수 태그는 1단계 범위 밖 / uncompressed thumbnail tags not checked"
            }
        }
        return ctx.finding("exif.tags.mandatory", v, emptyList(), listOf("필수(M) 태그 모두 있음 / all mandatory tags present") + notes)
    }

    private fun notAllowed(ctx: JpegContext, t: ExifTiff): ConformanceFinding {
        val v = ArrayList<String>()
        t.ifds[ExifIfd.IFD0]?.let { ifd -> NOT_ALLOWED_0TH.forEach { (tag, name) -> if (ifd.entry(tag) != null) v += "0th IFD: $name — 압축 주 이미지에는 기록 금지(N/J) / not allowed for a compressed primary image" } }
        if (compressedThumbnail(t) == true) t.ifds[ExifIfd.IFD1]?.let { ifd ->
            NOT_ALLOWED_1ST.forEach { (tag, name) -> if (ifd.entry(tag) != null) v += "1st IFD: $name — 압축 썸네일에는 기록 금지(N/J) / not allowed for a compressed thumbnail" }
        }
        return ctx.finding("exif.tags.not_allowed", v, emptyList(), listOf("기록 금지(N/J) 태그 없음 / no tags that are not allowed"))
    }

    private fun recommended(ctx: JpegContext, t: ExifTiff): ConformanceFinding {
        val ifd0 = t.ifds[ExifIfd.IFD0]
        val exif = t.ifds[ExifIfd.EXIF]
        val missing = RECOMMENDED_0TH.filter { ifd0?.entry(it.key) == null }.map { "0th IFD ${it.value}" } +
            RECOMMENDED_EXIF.filter { exif?.entry(it.key) == null }.map { "Exif IFD ${it.value}" }
        // One row listing every missing tag name (user decision 2026-10-25).
        val v = if (missing.isEmpty()) emptyList() else listOf("권장(R) 태그 ${missing.size}개 없음 / ${missing.size} recommended tag(s) missing: " + missing.joinToString(", "))
        return ctx.finding("exif.tags.recommended", v, emptyList(), listOf("권장(R) 태그 모두 있음 / all recommended tags present"))
    }

    private fun dimensions(ctx: JpegContext, t: ExifTiff): ConformanceFinding {
        val exif = t.ifds[ExifIfd.EXIF]
        val px = exif?.entry(0xA002)?.let { t.uint(it) }
        val py = exif?.entry(0xA003)?.let { t.uint(it) }
        if (px == null || py == null) return ctx.skip("exif.dimensions", NOT_APPLICABLE, "PixelX/YDimension 없음 또는 읽을 수 없음 (필수 태그 규칙 참고) / missing or unreadable")
        val f = ctx.frame ?: return ctx.skip("exif.dimensions", NOT_CHECKED, "프레임 헤더를 읽을 수 없음 / frame header unreadable")
        val lines = ctx.lines ?: return ctx.skip("exif.dimensions", NOT_CHECKED, "줄 수(Y/DNL)를 알 수 없음 / number of lines unknown")
        val v = ArrayList<String>()
        if (px < 1 || px > f.samplesPerLine) v += "PixelXDimension $px — 1..SOF X(${f.samplesPerLine}) 범위 밖 (§4.8.1 Table 23, p.128) / outside 1..X"
        if (py != lines.toLong()) v += "PixelYDimension $py ≠ SOF Y $lines — SOF는 유효 이미지 높이와 같음 (§4.8.1, p.129) / differs from the SOF height"
        return ctx.finding("exif.dimensions", v, emptyList(), listOf("PixelX/Y ${px}x$py, SOF ${f.samplesPerLine}x$lines"))
    }

    private fun thumbnail(ctx: JpegContext, app1: JpegSegment, t: ExifTiff): ConformanceFinding {
        val id = "exif.thumbnail"
        val ifd1 = t.ifds[ExifIfd.IFD1] ?: return ctx.skip(id, NOT_APPLICABLE, "1st IFD(썸네일) 없음 / no thumbnail IFD")
        if (compressedThumbnail(t) != true) return ctx.skip(id, NOT_CHECKED, "JPEG 압축 썸네일이 아님(Compression≠6) — 1단계 범위 밖 / not a compressed thumbnail")
        val off = ifd1.entry(0x0201)?.let { t.uint(it) }
        val len = ifd1.entry(0x0202)?.let { t.uint(it) }
        if (off == null || len == null) return ctx.skip(id, NOT_CHECKED, "JPEGInterchangeFormat/Length 없음 (필수 태그 규칙 참고) / missing")
        if (!t.inRange(off, len) || len < 4) return ctx.finding(id, listOf("썸네일 오프셋 $off + 길이 $len — APP1 데이터(${t.bytes.size}바이트) 밖 / outside the APP1"), listOf(app1.range), emptyList())
        val abs = t.base + off
        val stream = JpegStreamScanner.scan(ctx.reader, abs, abs + len)
        val v = ArrayList<String>()
        if (stream.segments.firstOrNull()?.marker != 0xD8 || stream.segments.first().offset != abs) v += "썸네일이 SOI로 시작하지 않음 / does not start with SOI"
        if (stream.eoi == null || stream.end != abs + len) v += "썸네일이 SOI..EOI로 정확히 끝나지 않음 (EOI ${stream.eoi?.let { hex(it.offset) } ?: "없음"}) / not exactly SOI..EOI"
        stream.segments.filter { it.marker in 0xE0..0xEF || it.marker == 0xFE }.forEach { v += "썸네일 안의 ${markerName(it.marker)} @${hex(it.offset)} — APPn/COM 기록 금지 / APPn and COM shall not be recorded" }
        val rst = stream.entropy.sumOf { it.restartCount }
        if (rst > 0) v += "썸네일 안의 재시작 마커 ${rst}개 — 기록 금지 / restart markers shall not be recorded"
        stream.segments.firstOrNull { it.marker in JpegFrame.SOF_MARKERS }?.let { if (it.marker != 0xC0) v += "썸네일 ${markerName(it.marker)} — Baseline(SOF0)이 아님 / not Baseline DCT" }
        return ctx.finding(id, v, if (v.isEmpty()) emptyList() else listOf(abs until abs + len), listOf("썸네일 @${hex(abs)} ${len}바이트, SOF0, APPn/COM/RST 없음"))
    }
}
```

- [ ] **Step 3:** GREEN. **Step 4:** commit `feat: check Exif APP1 structure and tag support levels`.

---

### Task 7: MPF APP2 rules and data after EOI

**Files:** Create `JpegMpfRules.kt`, `JpegTrailer.kt`; checker adds
`val mpf = JpegMpfRules.evaluate(ctx); findings += mpf.findings; findings += JpegTrailer.evaluate(ctx, mpf.images)`.

- [ ] **Step 1: Failing tests:** add the `mpf-*`, `no-eoi`, `trailing-garbage` rows to `JpegFixtureTest`, the
  "exif-mpf trailing details mention MPF" assert, the citation test, and re-enable the trailing asserts of
  `JpegT81RulesTest`. RED.
- [ ] **Step 2: Implement**

```kotlin
package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.MPF_ID
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.hex

/** One MP Entry (16 bytes) of the MP Index IFD. */
data class MpEntry(val index: Int, val attribute: Long, val size: Long, val dataOffset: Long, val dependent1: Int, val dependent2: Int) {
    val typeCode: Int get() = (attribute and 0xFFFFFF).toInt()
    val dataFormat: Int get() = ((attribute shr 24) and 7).toInt()
}

/** MPF findings plus the byte ranges of the secondary images that resolved to SOI..EOI (used to explain data after EOI). */
internal class MpfResult(val findings: List<ConformanceFinding>, val images: List<LongRange>)

/** CIPA DC-007-2026 (Multi-Picture Format) rules for the MP Extensions APP2 ("MPF\0") of the first individual image. */
internal object JpegMpfRules {
    private val IDS = listOf("mpf.count", "mpf.header", "mpf.endian", "mpf.index", "mpf.index.order", "mpf.version", "mpf.entries", "mpf.images", "mpf.type")
    private const val BASELINE_PRIMARY = 0x030000
    /** Table 4 codes other than the type-addition range xy000z. */
    private val TABLE4 = setOf(0x030000, 0x010001, 0x010002, 0x010003, 0x010004, 0x010005, 0x040000, 0x050000, 0x020001, 0x020002, 0x020003, 0x000000)
    /** "×" in the Baseline MP file (.JPG) column. */
    private val NOT_IN_BASELINE = setOf(0x020001, 0x020002, 0x020003, 0x000000)
    /** "×" in the Extended MP file (.MPO) column. */
    private val NOT_IN_EXTENDED = setOf(0x030000, 0x040000, 0x050000)
    private val ASCII_0100 = byteArrayOf(0x30, 0x31, 0x30, 0x30)

    fun typeAddition(code: Int): Boolean =
        (code shr 20) and 0xF in 8..15 && (code shr 16) and 0xF in 1..15 && code and 0xFFF0 == 0 && code and 0xF in 1..15

    fun evaluate(ctx: JpegContext): MpfResult {
        val segs = ctx.segments.filter { it.marker == 0xE2 && ctx.startsWith(it, MPF_ID) }
        if (segs.isEmpty()) return MpfResult(IDS.map { ctx.skip(it, NOT_APPLICABLE, "MPF APP2 없음 / no MP Extensions") }, emptyList())
        val s = segs.first()
        val count = ctx.finding("mpf.count",
            if (segs.size == 1) emptyList() else listOf("MP 확장 APP2 ${segs.size}개 (${segs.joinToString { hex(it.offset) }}) — 개별 이미지당 1개 / only one per individual image"),
            segs.drop(1).map { it.range }, listOf("MPF APP2 @${hex(s.offset)}"))
        val p = ctx.payload(s)
        val t = ExifTiff.header(p.copyOfRange(4, p.size), s.payload + 4)
        val header = ctx.finding("mpf.header", t.headerProblems.map { "MPF APP2 @${hex(s.offset)}: $it" },
            if (t.headerOk) emptyList() else listOf(s.range), listOf("MP Endian ${if (t.littleEndian) "II" else "MM"}, Offset to First IFD ${t.firstIfd}"))
        if (!t.headerOk) return MpfResult(listOf(count, header) + IDS.drop(2).map { ctx.skip(it, NOT_CHECKED, "MP 헤더 오류로 판단 불가 / MP header invalid") }, emptyList())

        val exif = ctx.exif?.takeIf { it.headerOk }
        val endian = if (exif == null) ctx.skip("mpf.endian", NOT_APPLICABLE, "Exif TIFF 헤더 없음 / no Exif TIFF header")
        else ctx.finding("mpf.endian",
            if (exif.littleEndian == t.littleEndian) emptyList() else listOf("MP Endian ${if (t.littleEndian) "II" else "MM"} ≠ Exif ${if (exif.littleEndian) "II" else "MM"} / differs from the Exif TIFF header"),
            emptyList(), listOf("MP·Exif 모두 ${if (t.littleEndian) "II" else "MM"} / both ${if (t.littleEndian) "II" else "MM"}"))

        val ifd = t.readIfd(ExifIfd.MP_INDEX, t.firstIfd)
        if (ifd == null) {
            val index = ctx.finding("mpf.index", t.structureProblems.ifEmpty { listOf("MP Index IFD를 읽을 수 없음 / unreadable") }, listOf(s.range), emptyList())
            return MpfResult(listOf(count, header, endian, index) + IDS.drop(4).map { ctx.skip(it, NOT_CHECKED, "MP Index IFD를 읽을 수 없음 / MP Index IFD unreadable") }, emptyList())
        }
        val n = ifd.entry(0xB001)?.let { t.uint(it) }
        val entries = readEntries(t, ifd, n)
        val baseline = entries.firstOrNull()?.typeCode == BASELINE_PRIMARY
        val images = ArrayList<LongRange>()
        return MpfResult(listOf(count, header, endian, index(ctx, t, ifd, n, baseline), order(ctx, ifd), version(ctx, t, ifd),
            entriesRule(ctx, t, ifd, n, entries), imagesRule(ctx, t, entries, images), type(ctx, entries)), images)
    }

    private fun readEntries(t: ExifTiff, ifd: TiffIfd, n: Long?): List<MpEntry> {
        val e = ifd.entry(0xB002) ?: return emptyList()
        if (e.type != 7) return emptyList()
        val count = minOf(e.count / 16, n ?: Long.MAX_VALUE, 10_000)
        if (!t.inRange(e.valueOffset, count * 16)) return emptyList()
        return (0 until count.toInt()).map { i ->
            val o = e.valueOffset + 16L * i
            MpEntry(i, t.u32(o), t.u32(o + 4), t.u32(o + 8), t.u16(o + 12), t.u16(o + 14))
        }
    }

    private fun index(ctx: JpegContext, t: ExifTiff, ifd: TiffIfd, n: Long?, baseline: Boolean): ConformanceFinding {
        val v = ArrayList<String>()
        v += t.structureProblems
        fun need(tag: Int, name: String, type: Int, count: Long?) {
            val e = ifd.entry(tag)
            if (e == null) { v += "$name(0x%04X) 없음 (Table 6: M) / missing".format(tag); return }
            if (e.type != type || (count != null && e.count != count)) v += "$name: Type ${e.type} Count ${e.count} ≠ Table 3 Type $type Count ${count ?: "16×NumberOfImages"} (p.16)"
        }
        need(0xB000, "MPFVersion", 7, 4)
        need(0xB001, "NumberOfImages", 4, 1)
        need(0xB002, "MPEntry", 7, null)
        ifd.entry(0xB003)?.let { if (it.type != 7 || (n != null && it.count != 33 * n)) v += "ImageUIDList: Type ${it.type} Count ${it.count} ≠ UNDEFINED 33×NumberOfImages (p.16)" }
        ifd.entry(0xB004)?.let { if (it.type != 4 || it.count != 1L) v += "TotalFrames: Type ${it.type} Count ${it.count} ≠ LONG 1 (p.16)" }
        if (baseline && ifd.next != 0L) v += "Baseline MP: MP Index IFD의 Offset to Next IFD ${ifd.next} ≠ 0 — MP Attribute IFD 기록 금지 (§6.1.1.1, p.34) / shall be NULL"
        return ctx.finding("mpf.index", v, emptyList(), listOf("MPFVersion, NumberOfImages=${n ?: "?"}, MPEntry 있음 / present"))
    }

    private fun order(ctx: JpegContext, ifd: TiffIfd): ConformanceFinding {
        val bad = ifd.entries.map { it.tag }.zipWithNext().firstOrNull { (a, b) -> b <= a }
        return ctx.finding("mpf.index.order",
            bad?.let { listOf("MP Index IFD: 태그 0x%04X 뒤에 0x%04X — 태그 ID 순서 아님 / not in tag ID order".format(it.first, it.second)) } ?: emptyList(),
            emptyList(), listOf("태그 ID 오름차순 / in tag ID order"))
    }

    private fun version(ctx: JpegContext, t: ExifTiff, ifd: TiffIfd): ConformanceFinding {
        val e = ifd.entry(0xB000) ?: return ctx.skip("mpf.version", NOT_CHECKED, "MPFVersion 없음 (mpf.index 참고) / missing")
        val b = t.valueBytes(e) ?: return ctx.skip("mpf.version", NOT_CHECKED, "MPFVersion 값을 읽을 수 없음 / unreadable")
        val ok = b.contentEquals(ASCII_0100)
        return ctx.finding("mpf.version",
            if (ok) emptyList() else listOf("MPFVersion ${b.joinToString(" ") { "%02X".format(it) }} ≠ \"0100\" (NULL 종료 없음) / shall be ASCII \"0100\""),
            emptyList(), listOf("MPFVersion \"0100\""))
    }

    private fun entriesRule(ctx: JpegContext, t: ExifTiff, ifd: TiffIfd, n: Long?, entries: List<MpEntry>): ConformanceFinding {
        val e = ifd.entry(0xB002)
        if (e == null || n == null) return ctx.skip("mpf.entries", NOT_CHECKED, "NumberOfImages/MPEntry 없음 (mpf.index 참고) / missing")
        val v = ArrayList<String>()
        if (n < 1) v += "NumberOfImages $n — 1 이상 / at least 1"
        if (e.count != 16 * n) v += "MPEntry Count ${e.count} ≠ 16×NumberOfImages (${16 * n}) — MP Entry 수가 이미지 수와 다름 / entries do not match the number of images"
        if (entries.isEmpty() && n >= 1) v += "MPEntry 값이 APP2 데이터 밖 / MP Entry values outside the APP2"
        entries.firstOrNull()?.let { if (it.dataOffset != 0L) v += "MP Entry #1 Data Offset ${it.dataOffset} ≠ 0 — 첫 개별 이미지는 NULL (§5.2.3.3.3, p.20) / shall be NULL" }
        return ctx.finding("mpf.entries", v, emptyList(), listOf("이미지 ${n}개, MP Entry ${entries.size}개 / $n images"))
    }

    private fun imagesRule(ctx: JpegContext, t: ExifTiff, entries: List<MpEntry>, images: MutableList<LongRange>): ConformanceFinding {
        if (entries.isEmpty()) return ctx.skip("mpf.images", NOT_CHECKED, "MP Entry를 읽을 수 없음 / no readable MP Entry")
        val v = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val pass = ArrayList<String>()
        entries.forEach { e ->
            val label = "MP Entry #${e.index + 1} (Type 0x%06X)".format(e.typeCode)
            if (e.index == 0) {
                val primary = ctx.stream.end - ctx.stream.start
                when {
                    ctx.stream.eoi == null -> pass += "$label: 주 이미지 EOI 없음 — 크기 미검사 / primary EOI missing, size not checked"
                    e.size != primary -> { v += "$label: Individual Image Size ${e.size} ≠ 주 이미지 SOI..EOI ${primary}바이트 (차이 ${e.size - primary}) / differs from the primary image"; ranges += 0L until minOf(ctx.length, maxOf(e.size, primary)) }
                    else -> pass += "$label: 주 이미지 ${primary}바이트 / primary image size matches"
                }
                return@forEach
            }
            val abs = t.base + e.dataOffset
            val end = abs + e.size
            when {
                e.size < 4 || end > ctx.length -> { v += "$label: @${hex(abs)} + ${e.size}바이트 — 파일(${ctx.length}) 밖 / outside the file"; ranges += abs until minOf(end, ctx.length) }
                ctx.reader.readUInt16(abs) != 0xFFD8 -> { v += "$label: @${hex(abs)}가 SOI가 아님 (0x%04X) / does not point at SOI".format(ctx.reader.readUInt16(abs)); ranges += abs until abs + 2 }
                ctx.reader.readUInt16(end - 2) != 0xFFD9 -> { v += "$label: @${hex(abs)}+${e.size}의 끝이 EOI가 아님 (0x%04X) / size does not end at EOI".format(ctx.reader.readUInt16(end - 2)); ranges += end - 2 until end }
                else -> { images += abs until end; pass += "$label: @${hex(abs)}..${hex(end)} SOI..EOI" }
            }
        }
        return ctx.finding("mpf.images", v, ranges, pass)
    }

    private fun type(ctx: JpegContext, entries: List<MpEntry>): ConformanceFinding {
        if (entries.isEmpty()) return ctx.skip("mpf.type", NOT_CHECKED, "MP Entry를 읽을 수 없음 / no readable MP Entry")
        val baseline = entries.first().typeCode == BASELINE_PRIMARY
        val v = ArrayList<String>()
        entries.forEach { e ->
            val label = "MP Entry #${e.index + 1}: Type 0x%06X".format(e.typeCode)
            if (e.dataFormat != 0) v += "$label — Image Data Format ${e.dataFormat} (0=JPEG 외 예약) / reserved data format"
            val known = e.typeCode in TABLE4 || typeAddition(e.typeCode)
            when {
                !known -> v += "$label — Table 4에 없는 코드는 사용 제한 / not a Table 4 code (restricted)"
                baseline && e.typeCode in NOT_IN_BASELINE -> v += "$label — Baseline MP 파일(.JPG)에서 사용 금지(×) / shall not be used in a Baseline MP file"
                !baseline && (e.typeCode in NOT_IN_EXTENDED || typeAddition(e.typeCode)) -> v += "$label — Extended MP 파일(.MPO)에서 사용 금지(×) / shall not be used in an Extended MP file"
            }
        }
        return ctx.finding("mpf.type", v, emptyList(),
            listOf((if (baseline) "Baseline MP 파일 / Baseline MP file: " else "Extended MP 파일 / Extended MP file: ") + entries.joinToString { "0x%06X".format(it.typeCode) }))
    }
}
```

```kotlin
package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.SefIntegrityAnalyzer
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.hex
import com.multiviewer.parser.motionphoto.walkBoxes

/** A byte range after the primary EOI and what explains it ([known] false = unexplained). */
data class TrailerRegion(val start: Long, val end: Long, val label: String, val known: Boolean)

/**
 * T.81 defines nothing after EOI. Data there is PASS when every byte is explained by a known structure (MPF secondary
 * images that resolved to SOI..EOI, an ISO-BMFF video = Google Motion Photo, a Samsung SEF trailer ending in "SEFT"),
 * WARN otherwise.
 */
internal object JpegTrailer {
    private const val FTYP_SEARCH = 4L * 1024 * 1024

    fun regions(ctx: JpegContext, mpfImages: List<LongRange>): List<TrailerRegion> {
        val len = ctx.length
        val out = ArrayList<TrailerRegion>()
        var pos = ctx.stream.end
        var guard = 0
        while (pos < len && guard++ < 10_000) {
            val image = mpfImages.firstOrNull { it.first == pos }
            if (image != null) {
                out += TrailerRegion(pos, image.last + 1, "MPF 개별 이미지 / MPF individual image", true); pos = image.last + 1; continue
            }
            val video = videoEnd(ctx, pos)
            if (video != null) {
                out += TrailerRegion(pos, video, "ISO-BMFF 동영상 (모션 포토) / ISO-BMFF video (motion photo)", true); pos = video; continue
            }
            if (sefExplains(ctx, pos)) {
                out += TrailerRegion(pos, len, "Samsung SEF 트레일러 / Samsung SEF trailer", true); pos = len; continue
            }
            val next = nextKnownStart(ctx, pos, mpfImages) ?: len
            out += TrailerRegion(pos, next, "알 수 없는 데이터 / unexplained data", false)
            pos = next
        }
        return out
    }

    private fun videoEnd(ctx: JpegContext, pos: Long): Long? {
        if (pos + 8 > ctx.length || ctx.reader.readFourCC(pos + 4) != "ftyp") return null
        val boxes = walkBoxes(ctx.reader, pos, ctx.length).takeWhile { !it.truncated }
        return boxes.lastOrNull()?.end?.takeIf { it > pos }
    }

    private fun sefExplains(ctx: JpegContext, pos: Long): Boolean {
        val len = ctx.length
        if (len - pos < 12 || ctx.reader.readFourCC(len - 4) != "SEFT") return false
        val report = try { SefIntegrityAnalyzer.analyze(ctx.reader, pos, 0, len - pos, len) } catch (e: java.util.concurrent.CancellationException) { throw e } catch (_: Exception) { return false }
        return report.overallSeverity != SefIntegritySeverity.CRITICAL
    }

    /** The next MPF image start or ISO-BMFF `ftyp` box after [pos] (searched within 4 MiB), or null. */
    private fun nextKnownStart(ctx: JpegContext, pos: Long, mpfImages: List<LongRange>): Long? {
        val image = mpfImages.map { it.first }.filter { it > pos }.minOrNull()
        val limit = minOf(ctx.length, pos + FTYP_SEARCH, image ?: Long.MAX_VALUE)
        var at = pos + 1
        while (at + 8 <= limit) {
            val n = minOf(64L * 1024, limit - at).toInt()
            val buf = ctx.reader.readBytes(at, n)
            for (i in 0..n - 8) {
                if (buf[i + 4] == 'f'.code.toByte() && buf[i + 5] == 't'.code.toByte() && buf[i + 6] == 'y'.code.toByte() && buf[i + 7] == 'p'.code.toByte()) {
                    val size = ((buf[i].toLong() and 0xFF) shl 24) or ((buf[i + 1].toLong() and 0xFF) shl 16) or ((buf[i + 2].toLong() and 0xFF) shl 8) or (buf[i + 3].toLong() and 0xFF)
                    if (size in 16..4096) return at + i
                }
            }
            if (n < 8) break
            at += n - 7
        }
        return image
    }

    fun evaluate(ctx: JpegContext, mpfImages: List<LongRange>): ConformanceFinding {
        val id = "t81.trailing"
        val eoi = ctx.stream.eoi ?: return ctx.skip(id, NOT_CHECKED, "EOI 없음 — 판단 불가 / no EOI")
        if (ctx.stream.end >= ctx.length) return ctx.finding(id, emptyList(), emptyList(), listOf("EOI @${hex(eoi.offset)} 뒤 데이터 없음 / nothing after EOI"))
        val regions = regions(ctx, mpfImages)
        val lines = regions.map { "${it.label}: ${hex(it.start)}..${hex(it.end)} (${it.end - it.start}바이트)" }
        val unknown = regions.filter { !it.known }
        val note = "T.81은 EOI 이후 데이터를 정의하지 않음 / T.81 defines no data after EOI"
        return if (unknown.isEmpty()) ctx.finding(id, emptyList(), emptyList(), listOf("$note — 모두 알려진 구조로 설명됨 / all explained by known structures") + lines)
        else ctx.finding(id, lines.filterIndexed { i, _ -> !regions[i].known } +
            ("$note — 설명된 영역 / explained: " + lines.filterIndexed { i, _ -> regions[i].known }.joinToString("; ").ifEmpty { "없음 / none" }),
            unknown.map { it.start until it.end }, emptyList())
    }
}
```

- [ ] **Step 3:** GREEN. **Step 4:** commit `feat: check MPF APP2 and data after EOI`.

---

### Task 8: Wiring, full fixture test, citation README, manual check, spec sync

**Files:** modify `app/src/main/kotlin/com/multiviewer/ui/ContentCheckPlan.kt`,
`app/src/test/kotlin/com/multiviewer/cli/CheckFileTest.kt`, `app/src/test/kotlin/com/multiviewer/ui/ContentCheckPlanTest.kt`;
complete `JpegFixtureTest.kt`; `docs/validation/spec-citations/README.md`; spec.

- [ ] **Step 1: Failing tests.** Final `JpegFixtureTest.kt`:

```kotlin
package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real tool output (ffmpeg, libjpeg-turbo cjpeg, macOS sips), the Exif/MPF fixture written by build.py on top of
 * ffmpeg image data, and byte-level variants — docs/validation/jpeg-conformance-fixtures/generate.sh.
 */
class JpegFixtureTest {
    private fun fixture(name: String) = File(javaClass.getResource("/jpeg-conformance/$name")!!.toURI())
    private fun run(name: String): ConformanceReport = ByteReader.open(fixture(name)).use { JpegConformanceChecker.check(it) }

    @Test fun `default ffmpeg output and the spec-complete Exif+MPF file have no FAIL, WARN or NOT_CHECKED`() {
        listOf("ffmpeg-default.jpg", "exif-mpf.jpg").forEach { name ->
            val r = run(name)
            assertNull(r.inspectionError, name)
            assertEquals(emptySet(), r.ids(FAIL, WARN, NOT_CHECKED), "$name: ${r.dump()}")
            assertEquals(PASS, r.overall, name)
        }
        val r = run("exif-mpf.jpg")
        assertTrue(r.findings.single { it.rule.id == "t81.trailing" }.details.any { it.contains("MPF") })
    }

    @Test fun `libjpeg-turbo writes JFIF 1_01, which only warns on jfif version`() {
        listOf("cjpeg-default.jpg", "cjpeg-progressive.jpg", "cjpeg-restart.jpg").forEach { name ->
            val r = run(name)
            assertEquals(emptySet(), r.ids(FAIL), "$name: ${r.dump()}")
            assertEquals(setOf("jfif.version"), r.ids(WARN), "$name: ${r.dump()}")
            assertEquals(WARN, r.overall, name)
        }
        assertTrue(run("cjpeg-restart.jpg").findings.single { it.rule.id == "t81.restart" }.details.single().contains("RST 2"))
    }

    @Test fun `macOS sips (ImageIO) output fails Exif position and mandatory tags`() {
        val r = run("sips.jpg")
        assertEquals(setOf("exif.app1.position", "exif.tags.mandatory"), r.ids(FAIL), r.dump().toString())
        assertEquals(setOf("jfif.version", "exif.tags.recommended"), r.ids(WARN))
        val rec = r.findings.single { it.rule.id == "exif.tags.recommended" }
        assertEquals(1, rec.details.size, rec.details.toString())
        assertTrue(rec.details.single().contains("0th IFD ImageDescription") && rec.details.single().contains("Exif IFD CompositeImage"), rec.details.toString())
    }

    @Test fun `each variant fails exactly its targeted rule`() {
        val cjpeg = setOf("jfif.version")
        val expected = mapOf(
            "no-eoi.jpg" to setOf("t81.eoi"),
            "trailing-garbage.jpg" to setOf("t81.trailing"),
            "no-dqt.jpg" to setOf("t81.tables"),
            "sof-h0.jpg" to setOf("t81.frame.header"),
            "scan-se.jpg" to setOf("t81.scan.header"),
            "jfif-not-first.jpg" to setOf("jfif.position"),
            "jfif-density0.jpg" to setOf("jfif.fields"),
            "dri-zero.jpg" to setOf("t81.restart") + cjpeg,
            "rst-order.jpg" to setOf("t81.restart") + cjpeg,
            "exif-unsorted.jpg" to setOf("exif.ifd.order"),
            "exif-no-colorspace.jpg" to setOf("exif.tags.mandatory"),
            "exif-imagewidth.jpg" to setOf("exif.tags.not_allowed"),
            "exif-ifd-loop.jpg" to setOf("exif.ifd.structure"),
            "exif-ascii-nonull.jpg" to setOf("exif.tag.ascii"),
            "exif-thumb-rst.jpg" to setOf("exif.thumbnail"),
            "mpf-type0.jpg" to setOf("mpf.type"),
            "mpf-size.jpg" to setOf("mpf.images"),
        )
        expected.forEach { (name, ids) ->
            val r = run(name)
            assertNull(r.inspectionError, name)
            assertEquals(ids, r.ids(FAIL, WARN), "$name: ${r.dump()}")
        }
        assertEquals(WARN, run("trailing-garbage.jpg").overall)
    }

    @Test fun `FAIL findings carry a verified citation with the physical PDF page`() {
        val f = run("mpf-type0.jpg").findings.single { it.rule.id == "mpf.type" }
        val c = f.rule.citation!!
        assertTrue(c.verified)
        assertEquals(19, c.page)
        assertTrue(c.url.endsWith("#page=19"), c.url)
    }
}
```

Wiring tests (the old ones asserted that a JPEG gets *no* container report; they now use a PNG and a JPEG test is added):

```diff
diff --git a/app/src/test/kotlin/com/multiviewer/cli/CheckFileTest.kt b/app/src/test/kotlin/com/multiviewer/cli/CheckFileTest.kt
index 1eb219a3..6e655e91 100644
--- a/app/src/test/kotlin/com/multiviewer/cli/CheckFileTest.kt
+++ b/app/src/test/kotlin/com/multiviewer/cli/CheckFileTest.kt
@@ -191,13 +191,23 @@ class CheckFileTest {
     }
 
     @Test
-    fun `checkFile omits containerConformance for non-ISO-BMFF files`() {
-        val file = File.createTempFile("check-iso-", ".jpg").also {
-            it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.jpegBytes())
+    fun `checkFile omits containerConformance for formats without a container checker`() {
+        val file = File.createTempFile("check-iso-", ".png").also {
+            it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.pngBytes())
         }
         val json = checkJson(file)
         assertTrue(!json.contains("containerConformance"), json)
     }
+
+    @Test
+    fun `checkFile includes the JPEG containerConformance report`() {
+        val file = File.createTempFile("check-jpeg-", ".jpg").also {
+            it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.jpegBytes())
+        }
+        val json = checkJson(file)
+        assertTrue(json.contains("\"containerConformance\""), json)
+        assertTrue(json.contains("t81.soi"), json)
+    }
     @Test
     fun `analysis case includes containerConformance with the check JSON shape`() {
         val file = movie("nomoov.mp4", com.multiviewer.parser.conformance.isobmff.MovieSpec(dropBox = "moov"))
@@ -235,9 +245,9 @@ class CheckFileTest {
     }
 
     @Test
-    fun `analysis case omits containerConformance for non-ISO-BMFF files`() {
-        val file = File.createTempFile("check-iso-", ".jpg").also {
-            it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.jpegBytes())
+    fun `analysis case omits containerConformance for formats without a container checker`() {
+        val file = File.createTempFile("check-iso-", ".png").also {
+            it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.pngBytes())
         }
         val result = checkFile(file, includeCase = true) as CheckResult.Success
         assertTrue(!result.analysisCaseJson!!.contains("containerConformance"))
diff --git a/app/src/test/kotlin/com/multiviewer/ui/ContentCheckPlanTest.kt b/app/src/test/kotlin/com/multiviewer/ui/ContentCheckPlanTest.kt
index 6d372569..6e8e9b81 100644
--- a/app/src/test/kotlin/com/multiviewer/ui/ContentCheckPlanTest.kt
+++ b/app/src/test/kotlin/com/multiviewer/ui/ContentCheckPlanTest.kt
@@ -276,8 +276,11 @@ class ContentCheckPlanTest {
 
     @Test
     fun isoBmffConformanceIsOnlyComputedForMp4MovAndM4a() {
+        val png = File.createTempFile("ccp-iso-", ".png").also { it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.pngBytes()) }
+        assertNull(containerConformanceFor(png))
         val jpeg = File.createTempFile("ccp-iso-", ".jpg").also { it.deleteOnExit(); it.writeBytes(jpegBytes()) }
-        assertNull(containerConformanceFor(jpeg))
+        assertEquals("JPEG", containerConformanceFor(jpeg)?.format)
+        assertEquals(com.multiviewer.parser.conformance.jpeg.JpegConformanceChecker.PROFILE, containerConformanceFor(jpeg)?.profile)
         val mp4 = containerConformanceFor(movieFile("x.mp4"))
         assertEquals("ISO-BMFF", mp4?.format)
         // Content decides, not the extension.
```

- [ ] **Step 2:** RED. **Step 3: Implement the dispatch:**

```diff
diff --git a/app/src/main/kotlin/com/multiviewer/ui/ContentCheckPlan.kt b/app/src/main/kotlin/com/multiviewer/ui/ContentCheckPlan.kt
index e9a42f59..0bcaccd8 100644
--- a/app/src/main/kotlin/com/multiviewer/ui/ContentCheckPlan.kt
+++ b/app/src/main/kotlin/com/multiviewer/ui/ContentCheckPlan.kt
@@ -4,6 +4,7 @@ import com.multiviewer.parser.BoxNode
 import com.multiviewer.parser.ByteReader
 import com.multiviewer.parser.ContainerFormat
 import com.multiviewer.parser.conformance.heif.HeifConformanceChecker
+import com.multiviewer.parser.conformance.jpeg.JpegConformanceChecker
 import com.multiviewer.parser.conformance.isobmff.IsoBmffConformanceChecker
 import java.util.concurrent.CancellationException
 import com.multiviewer.parser.conformance.ConformanceReport
@@ -109,13 +110,15 @@ private val ISO_BMFF_CONTAINERS = setOf(ContainerFormat.MP4, ContainerFormat.MOV
  */
 fun containerConformanceFor(file: File): ConformanceReport? {
     val format = confidentFormat(file)
-    val heif = when (format) {
-        in ISO_BMFF_CONTAINERS -> false
-        ContainerFormat.HEIF, ContainerFormat.AVIF -> true
-        else -> return null
-    }
+    if (format !in ISO_BMFF_CONTAINERS && format != ContainerFormat.HEIF && format != ContainerFormat.AVIF && format != ContainerFormat.JPEG) return null
     return try {
-        ByteReader.open(file).use { if (heif) HeifConformanceChecker.check(it) else IsoBmffConformanceChecker.check(it) }
+        ByteReader.open(file).use {
+            when (format) {
+                ContainerFormat.JPEG -> JpegConformanceChecker.check(it)
+                ContainerFormat.HEIF, ContainerFormat.AVIF -> HeifConformanceChecker.check(it)
+                else -> IsoBmffConformanceChecker.check(it)
+            }
+        }
     } catch (e: CancellationException) {
         throw e
     } catch (e: Exception) {
@@ -123,6 +126,7 @@ fun containerConformanceFor(file: File): ConformanceReport? {
         val (name, profile) = when (format) {
             ContainerFormat.AVIF -> "AVIF" to HeifConformanceChecker.AVIF_PROFILE
             ContainerFormat.HEIF -> "HEIF" to HeifConformanceChecker.HEIF_PROFILE
+            ContainerFormat.JPEG -> "JPEG" to JpegConformanceChecker.PROFILE
             else -> "ISO-BMFF" to "ISO/IEC 14496-12 phase 1"
         }
         ConformanceReport(name, profile, emptyList(), inspectionError = message,
```

- [ ] **Step 4:** full `./gradlew :app:test` + `:app:compileKotlin` GREEN (`ThumbnailDiskCacheTest` is the known flaky one).
- [ ] **Step 5: README.** Add a "JPEG catalog" section to `docs/validation/spec-citations/README.md` with the spec's
  source table (4 PDFs, editions, citation URLs, sha256) and the two notes (T.81 not free on itu.int → W3C copy of
  the original; CIPA POST-only download → download page + `#page=N`).
- [ ] **Step 6: Manual** (copies only, never committed): copy the seven Samsung JPEGs listed in the spec to a temp dir
  and run `./gradlew -q :app:run --args="check <copy>"`; confirm `containerConformance.format == "JPEG"`, no
  `inspectionError`, and the FAIL/WARN set of the spec's "Expected outcome" table (S26: `mpf.images` difference
  -14494; no JFIF row since Samsung writes no JFIF APP0). Report differences instead of changing rules. GUI: open one Samsung JPEG in Content Check (⌘⇧C), check the
  "컨테이너 포맷 규격" row lists the rules grouped by the four scopes and the 출처 links open.
- [ ] **Step 7:** sync the spec (status line, any measured differences); commits `feat: show JPEG conformance in
  Content Check and CLI`, `docs: add JPEG citation sources`, `docs: sync JPEG conformance spec with results`.
