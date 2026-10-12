# C2PA Manifest Parsing (Phase 2a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Locate and parse C2PA manifest stores in JPEG / HEIF·MP4·MOV / WebP, show the JUMBF boxes in the structure tree (hex-linked), and add an Analyze-menu "C2PA 콘텐츠 자격 증명" window showing manifests, claim, actions, ingredients, assertions, signer certificate (not verified) and thumbnails.

**Architecture:** New pure-Kotlin package `com.multiviewer.parser.c2pa`: a CBOR decoder, a JUMBF box parser over an in-memory store, a per-container locator that reassembles the store and maps logical offsets to file offsets, and a manifest model builder. The JPEG/WebP walkers and a new BMFF `uuid` decoder attach JUMBF nodes to the tree. A Compose window renders the model.

**Tech Stack:** Kotlin 2.0.21, Compose Desktop (Material3), kotlin.test/JUnit 5, JDK `java.security.cert.CertificateFactory` (parse only). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-12-c2pa-manifest-parsing-design.md`

## Global Constraints

- Containers: JPEG (APP11 `JP`), HEIF/HEIC/AVIF/MP4/MOV (top-level `uuid` with UUID `d8fec3d6-1b0e-483c-9297-5828877ec481`, purpose `manifest`), WebP (`C2PA` chunk). PNG `caBX`, remote manifests: out of scope.
- No new dependencies; no CBOR/JSON library. No external process at runtime.
- No signature/chain/hash validation in this phase. The signing certificate is parsed only and always labelled "not verified" / "검증 전".
- Limits: manifest store ≤ 64 MB (`MAX_C2PA_STORE_BYTES = 64 * 1024 * 1024`); CBOR nesting depth 64, item count 1,000,000; JUMBF superbox nesting 32.
- Malformed input never throws out of the public entry points: it becomes warnings / error values.
- All new Compose text uses `androidx.compose.material3.Text`. User-visible strings are bilingual via `I18n` or a local `label(korean, english)` helper.
- Files without APP11 `JP`, a C2PA `uuid` or a `C2PA` chunk must not pay any extra parsing cost.
- Every test command runs from the repo root: `./gradlew :app:test --tests '<FQN>'`. `com.multiviewer.cache.ThumbnailDiskCacheTest` is a known pre-existing flaky test.
- Commits end with a `Co-Authored-By:` trailer naming the model that wrote the commit.

## Verified facts (planning-time inspection, 2026-10-12)

From `contentauth/c2pa-rs` `sdk/tests/fixtures` (MIT/Apache-2.0) and files signed locally with c2patool 0.27.22's built-in test certificate. Values below were read from the raw bytes with an independent Python CBOR/JUMBF dumper and cross-checked with `c2patool <file>` and `openssl x509`.

**JPEG APP11.** Segment = `FF EB`, u16 length, `CI`="JP", u16 `En`, u32 `Z`, then JUMBF bytes. Each segment repeats the 8-byte superbox header (LBox, TBox). Reassembly: segment 1 from its LBox onward; each later segment from 8 bytes past its LBox (skip the repeated LBox+TBox). The reassembled length equals LBox.
- `C.jpg` (132,518 B): one APP11 at file offset 20, En 529, Z 1; JUMBF at file offset 32, LBox 45,872.
- `CA.jpg` (166,864 B): APP11 #1 at 20 (Z 1) carries logical [0, 64000) at file [32, 64032); APP11 #2 at 64032 (Z 2) carries logical [64000, 117241) at file [64052, 117293). LBox 117,241.

**BMFF.** `uuid` box: 8-byte header, 16-byte usertype, u8 version + u24 flags, NUL-terminated purpose, u64 merkle offset, then the JUMBF store.
- `video1.mp4` (828,571 B): top boxes `ftyp, uuid, moov, uuid, free, mdat`; C2PA uuid at 24, size 30,662, purpose `manifest`, store at 69, length 30,617.
- `signed.heif` (307,167 B): top boxes `ftyp, uuid, meta, mdat`; C2PA uuid at 24, size 13,559, store at 69, length 13,514.

**WebP.** `signed_small.webp` (63,854 B): chunks `VP8 `, `C2PA`; `C2PA` chunk at 50,408, payload 13,438 bytes at 50,416.

**JUMBF.** `jumd` payload = 16-byte content-type UUID, u8 toggles, label (UTF-8, NUL-terminated, if toggles & 0x02), u32 ID (if & 0x04), 32-byte signature (if & 0x08), private box (if & 0x10). Standard content-type UUIDs are a four-char code followed by `0011-0010-8000-00AA00389B71` (`c2pa`, `c2ma`, `c2as`, `c2cl`, `c2cs`, `cbor`, `json`). Thumbnails use `40cb0c32-bb8a-489d-a70b-2ad6f47f4369` (embedded file) with content boxes `bfdb` (u8 toggles + NUL-terminated MIME, e.g. `00 "image/jpeg" 00`) and `bidb` (bytes). Toggles `0x13` carries a 24-byte private `c2sh` (salt) box after the label.

**C.jpg JUMBF tree:** `c2pa` → manifest `contentauth:urn:uuid:b2b1f7fa-b119-4de1-9c0d-c97fbea3f2c3` (c2ma) → `c2pa.assertions` (c2as: `c2pa.thumbnail.claim.jpeg` [embedded file], `stds.schema-org.CreativeWork` [json, toggles 0x13], `c2pa.actions` [cbor], `c2pa.hash.data` [cbor]), `c2pa.claim` (c2cl), `c2pa.signature` (c2cs).

**Claims and assertions (raw CBOR):**
- `C.jpg` claim (`c2pa.claim`, v1): `dc:title` "C.jpg", `dc:format` "image/jpeg", `instanceID` "xmp:iid:22704d84-c37f-4733-a207-56c4c2e67b1a", `claim_generator` "make_test_images/0.33.1 c2pa-rs/0.33.1", `claim_generator_info` = array [{name "make_test_images", version "0.33.1"}, {name "c2pa-rs", version "0.33.1"}], `signature` "self#jumbf=c2pa.signature", `assertions` = 4 hashed URIs, `alg` "sha256". Actions: one action `c2pa.created`, softwareAgent "Make Test Images 0.33.1", digitalSourceType "http://cv.iptc.org/newscodes/digitalsourcetype/algorithmicMedia", parameters {name: "gradient"}.
- `CA.jpg` (v1): title "CA.jpg"; assertions labels `c2pa.thumbnail.claim.jpeg`, `c2pa.thumbnail.ingredient.jpeg`, `c2pa.ingredient`, `stds.schema-org.CreativeWork`, `c2pa.actions`, `c2pa.hash.data`. Ingredient: title "A.jpg", format "image/jpeg", relationship "parentOf", instanceID "xmp.iid:813ee422-9736-4cdc-9be6-4e35ed8e41cb", thumbnail url "self#jumbf=c2pa.assertions/c2pa.thumbnail.ingredient.jpeg". Actions: `c2pa.opened`, `c2pa.color_adjustments`.
- `video1.mp4`: two manifests in order `urn:uuid:54281c07-ad34-430e-bea5-112a18facf0b`, `urn:uuid:8da8ffb6-3de4-430a-8924-5b58b561e647` (active = last). Active manifest: claim_generator "TestApp c2patool/0.6.2 c2pa-rs/0.28.2", `claim_generator_info` = CBOR null, title "My Title", format "video/mp4"; ingredient title "video1.mp4", relationship "parentOf", `c2pa_manifest` url "self#jumbf=/c2pa/urn:uuid:54281c07-ad34-430e-bea5-112a18facf0b"; assertions `c2pa.ingredient`, `stds.schema-org.CreativeWork`, `c2pa.actions`, `my.assertion`, `c2pa.hash.bmff.v2`. First manifest's assertions end with `c2pa.hash.bmff`.
- `signed.heif` (`c2pa.claim.v2`): no `claim_generator`; `claim_generator_info` = a single MAP {name "c2patool", version "0.27.22", "org.contentauth.c2pa_rs" "0.90.22"}; title "fixture"; no `dc:format`; `created_assertions` [c2pa.hash.bmff.v3], `gathered_assertions` [c2pa.actions.v2]; assertion labels `c2pa.actions.v2`, `c2pa.hash.bmff.v3` (both toggles 0x13). Action `c2pa.created`, digitalSourceType ".../digitalCapture".
- `signed_small.webp` (v2): title "fixture"; assertion labels `c2pa.actions.v2`, `c2pa.hash.data`.

**COSE_Sign1 (`c2pa.signature` cbor box):** CBOR tag 18 wrapping `[protected bstr, unprotected map, payload (null), signature bstr]`.
- C.jpg / CA.jpg: protected {1: -37 (PS256), 33: [cert, cert]}; unprotected keys "sigTst", "pad"; signature 512 bytes.
- video1.mp4 (both manifests): protected {1: -7 (ES256)}; x5chain in the UNPROTECTED map under the TEXT key "x5chain" (2 certs); signature 64 bytes.
- signed.heif / signed_small.webp: protected {1: -7, 33: [cert, cert]}.
- Leaf certificate subjects (RFC 2253): C2PA test cert `CN=C2PA Signer,OU=FOR TESTING_ONLY,O=C2PA Test Signing Cert,L=Somewhere,ST=CA,C=US`, issuer `CN=Intermediate CA,OU=FOR TESTING_ONLY,O=C2PA Test Intermediate Root CA,L=Somewhere,ST=CA,C=US`. video1's first manifest: subject `CN=Bob,O=Media Publisher Company`, issuer `CN=Media Publisher Company Intermediate CA,O=Media Publisher Company`.

**Galaxy:** the user's unedited capture `20261012_223627.jpg` has no C2PA (Samsung adds it only to AI-edited images). No real Galaxy C2PA file is available.

## File structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/com/multiviewer/parser/c2pa/Cbor.kt` | CBOR value model, decoder, diagnostic notation |
| `.../parser/c2pa/Jumbf.kt` | JUMBF box/description model and parser over a byte array |
| `.../parser/c2pa/C2paLocator.kt` | Per-container store extraction, offset map, `locateC2paStore` |
| `.../parser/c2pa/C2paTree.kt` | JUMBF → `BoxNode` conversion; JPEG post-pass; `MdatUuid` decoder; `hasC2paManifestStore` |
| `.../parser/c2pa/C2paManifest.kt` | Manifest store model + builder (claim, assertions, COSE header, cert parse) |
| `.../parser/JpegWalker.kt` (modify) | call the APP11 post-pass at the end of `parseJpegSegments` |
| `.../parser/WebpWalker.kt` (modify) | `C2PA` chunk gets JUMBF children |
| `.../parser/Decoders.kt` (modify) | register `uuid` |
| `.../ui/C2paWindow.kt` (new) | the window |
| `.../ui/I18n.kt`, `.../Main.kt` (modify) | menu + wiring |
| `app/src/test/resources/c2pa/*` (new) | fixtures |
| `docs/validation/c2pa-fixtures/README.md` (new) | provenance + regeneration |
| tests in `app/src/test/kotlin/com/multiviewer/parser/c2pa/` | one per source file |

Test helpers already available in package `com.multiviewer.parser` (import them): `hex(String): ByteArray` (TestBitWriter.kt), `byteReaderOf(ByteArray)`, `fileOf(ByteArray)` (TestSupport.kt), `parseFile(File)`, `findFirst(BoxNode, (BoxNode) -> Boolean)`.

---

### Task 1: Fixtures

**Files:**
- Create: `app/src/test/resources/c2pa/C.jpg`, `CA.jpg`, `video1.mp4`, `signed.heif`, `signed_small.webp`, `no_manifest.jpg`
- Create: `docs/validation/c2pa-fixtures/README.md`, `docs/validation/c2pa-fixtures/manifest.json`

**Interfaces:**
- Produces: fixtures loaded as `File(javaClass.getResource("/c2pa/<name>")!!.toURI())`.

- [ ] **Step 1: Copy the fixtures**

They already exist from planning in `/private/tmp/claude-501/-Users-dong-kim-AndroidStudioProjects-multiViewer/63be60b7-019a-4a72-9ea0-ab206f4ec2a3/scratchpad/c2pa-samples/`. Copy exactly these six files into `app/src/test/resources/c2pa/` (keep names): `C.jpg`, `CA.jpg`, `video1.mp4`, `signed.heif`, `signed_small.webp`, `no_manifest.jpg`.

If that directory is gone, regenerate: download the first four-plus-`no_manifest.jpg` sources from `https://raw.githubusercontent.com/contentauth/c2pa-rs/main/sdk/tests/fixtures/<name>` (`C.jpg`, `CA.jpg`, `video1.mp4`, `no_manifest.jpg`, `sample1.heif`, `sample1.webp`), then with c2patool 0.27.22 (`brew install c2patool`) run the two commands in Step 2's README. Re-signed files get new manifest UUIDs and certificates times; Tasks 3–6 only assert UUID-independent values for those two files.

Run: `ls -l app/src/test/resources/c2pa/`
Expected sizes (bytes): C.jpg 132518, CA.jpg 166864, video1.mp4 828571, signed.heif 307167, signed_small.webp 63854, no_manifest.jpg 97591.

- [ ] **Step 2: Document provenance**

`docs/validation/c2pa-fixtures/manifest.json`:

```json
{"claim_generator":"unwrapMedia-fixture/1.0","title":"fixture","thumbnail":{"format":"none","identifier":"none"},"assertions":[{"label":"c2pa.actions","data":{"actions":[{"action":"c2pa.created","digitalSourceType":"http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture"}]}}]}
```

`docs/validation/c2pa-fixtures/README.md`:

```markdown
# C2PA test fixtures

Files in `app/src/test/resources/c2pa/`:

| File | Source |
|---|---|
| C.jpg, CA.jpg, video1.mp4, no_manifest.jpg | `contentauth/c2pa-rs` `sdk/tests/fixtures` (MIT OR Apache-2.0), unmodified |
| signed.heif | c2pa-rs `sample1.heif`, signed with c2patool 0.27.22's built-in test certificate |
| signed_small.webp | c2pa-rs `sample1.webp`, signed the same way (no thumbnail, to keep it small) |

Regenerate the two signed files (c2patool warns that it uses its built-in test key — expected):

    c2patool sample1.heif -m docs/validation/c2pa-fixtures/manifest.json -o signed.heif -f
    c2patool sample1.webp -m docs/validation/c2pa-fixtures/manifest.json -o signed_small.webp -f

`c2patool <file>` prints a normalised view (e.g. it reports the v1 label `c2pa.actions` as `c2pa.actions.v2`);
tests assert raw labels and fields as stored in the file.
```

- [ ] **Step 3: Commit**

```bash
git add app/src/test/resources/c2pa docs/validation/c2pa-fixtures
git commit -m "test: add C2PA fixtures"
```
(append the `Co-Authored-By:` trailer)

---

### Task 2: CBOR decoder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/c2pa/Cbor.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/c2pa/CborTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface CborValue`; `data class CborInt(val value: BigInteger)` (with `fun toLongOrNull(): Long?`); `class CborBytes(val value: ByteArray)` (content equality); `data class CborText(val value: String)`; `data class CborArray(val items: List<CborValue>)`; `data class CborMap(val entries: List<Pair<CborValue, CborValue>>)` with `operator fun get(key: String): CborValue?` and `operator fun get(key: Long): CborValue?`; `data class CborTag(val tag: BigInteger, val value: CborValue)`; `data class CborBool(val value: Boolean)`; `data object CborNull`; `data object CborUndefined`; `data class CborSimple(val value: Int)`; `data class CborFloat(val value: Double)`.
  - `fun CborValue.asText(): String?`, `fun CborValue.asBytes(): ByteArray?`, `fun CborValue.asMap(): CborMap?`, `fun CborValue.asArray(): List<CborValue>?`, `fun CborValue.asLong(): Long?`
  - `class CborException(message: String, val offset: Int) : Exception`
  - `data class CborLimits(val maxDepth: Int = 64, val maxItems: Int = 1_000_000)`
  - `fun decodeCbor(bytes: ByteArray, start: Int = 0, end: Int = bytes.size, limits: CborLimits = CborLimits()): CborValue` — decodes the first data item in `[start, end)`; trailing bytes are ignored; throws `CborException`.
  - `fun CborValue.toDiagnostic(maxBytes: Int = 64): String`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.multiviewer.parser.c2pa

import com.multiviewer.parser.hex
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CborTest {
    private fun d(h: String) = decodeCbor(hex(h))
    private fun int(v: Long) = CborInt(BigInteger.valueOf(v))

    @Test
    fun `RFC 8949 Appendix A integers`() {
        assertEquals(int(0), d("00"))
        assertEquals(int(23), d("17"))
        assertEquals(int(24), d("1818"))
        assertEquals(int(1000), d("1903e8"))
        assertEquals(int(1000000), d("1a000f4240"))
        assertEquals(int(1000000000000), d("1b000000e8d4a51000"))
        assertEquals(CborInt(BigInteger("18446744073709551615")), d("1bffffffffffffffff"))
        assertEquals(int(-1), d("20"))
        assertEquals(int(-100), d("3863"))
        assertEquals(int(-1000), d("3903e7"))
        assertEquals(CborInt(BigInteger("-18446744073709551616")), d("3bffffffffffffffff"))
    }

    @Test
    fun `RFC 8949 Appendix A floats and simple values`() {
        assertEquals(CborFloat(0.0), d("f90000"))
        assertEquals(-0.0, (d("f98000") as CborFloat).value)
        assertTrue(1.0 / (d("f98000") as CborFloat).value < 0) // negative zero
        assertEquals(CborFloat(1.0), d("f93c00"))
        assertEquals(CborFloat(1.5), d("f93e00"))
        assertEquals(CborFloat(65504.0), d("f97bff"))
        assertEquals(CborFloat(5.960464477539063e-8), d("f90001"))
        assertEquals(CborFloat(0.00006103515625), d("f90400"))
        assertEquals(CborFloat(-4.0), d("f9c400"))
        assertEquals(CborFloat(100000.0), d("fa47c35000"))
        assertEquals(CborFloat(1.1), d("fb3ff199999999999a"))
        assertEquals(CborFloat(Double.POSITIVE_INFINITY), d("f97c00"))
        assertTrue((d("f97e00") as CborFloat).value.isNaN())
        assertEquals(CborBool(false), d("f4"))
        assertEquals(CborBool(true), d("f5"))
        assertEquals(CborNull, d("f6"))
        assertEquals(CborUndefined, d("f7"))
        assertEquals(CborSimple(16), d("f0"))
        assertEquals(CborSimple(255), d("f8ff"))
    }

    @Test
    fun `RFC 8949 Appendix A strings, arrays, maps, tags`() {
        assertEquals(CborBytes(hex("01020304")), d("4401020304"))
        assertEquals(CborText(""), d("60"))
        assertEquals(CborText("IETF"), d("6449455446"))
        assertEquals(CborText("ü"), d("62c3bc"))
        assertEquals(CborText("水"), d("63e6b0b4"))
        assertEquals(CborArray(listOf(int(1), CborArray(listOf(int(2), int(3))), CborArray(listOf(int(4), int(5))))), d("8301820203820405"))
        val m = d("a26161016162820203") as CborMap
        assertEquals(int(1), m["a"])
        assertEquals(CborArray(listOf(int(2), int(3))), m["b"])
        assertEquals(int(2), (d("a201020304") as CborMap)[1L])
        assertEquals(CborTag(BigInteger.ONE, int(1363896240)), d("c11a514b67b0"))
        assertEquals(CborTag(BigInteger.valueOf(23), CborBytes(hex("01020304"))), d("d74401020304"))
    }

    @Test
    fun `RFC 8949 Appendix A indefinite lengths`() {
        assertEquals(CborBytes(hex("0102030405")), d("5f42010243030405ff"))
        assertEquals(CborText("streaming"), d("7f657374726561646d696e67ff"))
        assertEquals(CborArray(emptyList()), d("9fff"))
        assertEquals(
            CborArray(listOf(int(1), CborArray(listOf(int(2), int(3))), CborArray(listOf(int(4), int(5))))),
            d("9f018202039f0405ffff"),
        )
        val m = d("bf61610161629f0203ffff") as CborMap
        assertEquals(CborArray(listOf(int(2), int(3))), m["b"])
    }

    @Test
    fun `trailing bytes after the first item are ignored`() {
        assertEquals(int(1), d("01ff00"))
    }

    @Test
    fun `errors carry the byte offset`() {
        val truncated = assertFailsWith<CborException> { d("1903") }
        assertEquals(1, truncated.offset)
        assertFailsWith<CborException> { d("62c3") }          // text shorter than declared
        assertFailsWith<CborException> { d("ff") }            // lone break
        assertFailsWith<CborException> { d("5f01ff") }        // non-bytes chunk inside indefinite bytes
        assertFailsWith<CborException> { d("1f") }            // indefinite unsigned int
        assertFailsWith<CborException> { d("fc") }            // reserved additional info
        assertFailsWith<CborException> { d("f818") }          // simple value < 32 in two-byte form
        assertFailsWith<CborException> { d("a1") }            // map missing its pair
    }

    @Test
    fun `depth and item limits`() {
        val deep = hex("81".repeat(70) + "00")
        assertFailsWith<CborException> { decodeCbor(deep) }
        assertEquals(int(0), (0 until 70).fold(decodeCbor(deep, limits = CborLimits(maxDepth = 100))) { v, _ -> (v as CborArray).items[0] })
        assertFailsWith<CborException> { decodeCbor(hex("83010203"), limits = CborLimits(maxItems = 3)) }
    }

    @Test
    fun `accessors`() {
        val m = d("a26161016162820203") as CborMap
        assertEquals(1L, m["a"]?.asLong())
        assertEquals(2, m["b"]?.asArray()?.size)
        assertEquals("IETF", d("6449455446").asText())
        assertEquals(null, d("01").asText())
    }

    @Test
    fun `diagnostic notation`() {
        assertEquals("{\"a\": 1, \"b\": [2, 3]}", d("a26161016162820203").toDiagnostic())
        assertEquals("h'01020304'", d("4401020304").toDiagnostic())
        assertEquals("h'0102'…(+2 bytes)", d("4401020304").toDiagnostic(maxBytes = 2))
        assertEquals("1(1363896240)", d("c11a514b67b0").toDiagnostic())
        assertEquals("[true, false, null, undefined, simple(16), 1.5]", d("86f5f4f6f7f0f93e00").toDiagnostic())
        assertEquals("\"a\\\"b\"", CborText("a\"b").toDiagnostic())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.c2pa.CborTest'`
Expected: FAIL — compilation errors (unresolved `decodeCbor`, `CborInt`…).

- [ ] **Step 3: Implement `Cbor.kt`**

```kotlin
package com.multiviewer.parser.c2pa

import java.math.BigInteger
import kotlin.math.pow

// Minimal RFC 8949 CBOR decoder for C2PA claims, assertions and COSE structures. This codebase
// deliberately has no CBOR/JSON library; values are decoded into an immutable tree.
sealed interface CborValue

data class CborInt(val value: BigInteger) : CborValue {
    fun toLongOrNull(): Long? = if (value.bitLength() < 64) value.toLong() else null
}

class CborBytes(val value: ByteArray) : CborValue {
    override fun equals(other: Any?): Boolean = other is CborBytes && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = "CborBytes(${value.size} bytes)"
}

data class CborText(val value: String) : CborValue
data class CborArray(val items: List<CborValue>) : CborValue

data class CborMap(val entries: List<Pair<CborValue, CborValue>>) : CborValue {
    operator fun get(key: String): CborValue? =
        entries.firstOrNull { (k, _) -> k is CborText && k.value == key }?.second

    operator fun get(key: Long): CborValue? =
        entries.firstOrNull { (k, _) -> k is CborInt && k.value == BigInteger.valueOf(key) }?.second
}

data class CborTag(val tag: BigInteger, val value: CborValue) : CborValue
data class CborBool(val value: Boolean) : CborValue
data object CborNull : CborValue
data object CborUndefined : CborValue
data class CborSimple(val value: Int) : CborValue
data class CborFloat(val value: Double) : CborValue

fun CborValue.asText(): String? = (this as? CborText)?.value
fun CborValue.asBytes(): ByteArray? = (this as? CborBytes)?.value
fun CborValue.asMap(): CborMap? = this as? CborMap
fun CborValue.asArray(): List<CborValue>? = (this as? CborArray)?.items
fun CborValue.asLong(): Long? = (this as? CborInt)?.toLongOrNull()

class CborException(message: String, val offset: Int) : Exception("$message at byte $offset")

data class CborLimits(val maxDepth: Int = 64, val maxItems: Int = 1_000_000)

fun decodeCbor(bytes: ByteArray, start: Int = 0, end: Int = bytes.size, limits: CborLimits = CborLimits()): CborValue =
    CborDecoder(bytes, start, end, limits).read(0)

private const val BREAK = 0xFF

private class CborDecoder(val bytes: ByteArray, start: Int, val end: Int, val limits: CborLimits) {
    var pos = start
    var items = 0

    fun u8(): Int {
        if (pos >= end) throw CborException("unexpected end of data", pos)
        return bytes[pos++].toInt() and 0xFF
    }

    fun take(n: Int): ByteArray {
        if (n < 0 || n > end - pos) throw CborException("length $n exceeds the ${end - pos} bytes left", pos)
        val out = bytes.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun argument(info: Int, itemStart: Int): BigInteger = when {
        info < 24 -> BigInteger.valueOf(info.toLong())
        info == 24 -> BigInteger.valueOf(u8().toLong())
        info == 25 -> BigInteger(1, take(2))
        info == 26 -> BigInteger(1, take(4))
        info == 27 -> BigInteger(1, take(8))
        else -> throw CborException("reserved additional info $info", itemStart)
    }

    fun length(arg: BigInteger, itemStart: Int): Int {
        if (arg > BigInteger.valueOf((end - pos).toLong())) throw CborException("length $arg exceeds the ${end - pos} bytes left", itemStart)
        return arg.toInt()
    }

    fun read(depth: Int): CborValue {
        val itemStart = pos
        if (depth > limits.maxDepth) throw CborException("nesting deeper than ${limits.maxDepth}", itemStart)
        if (++items > limits.maxItems) throw CborException("more than ${limits.maxItems} items", itemStart)
        val initial = u8()
        val major = initial ushr 5
        val info = initial and 0x1F
        if (major == 7) return readMajor7(info, itemStart)
        if (info == 31) return readIndefinite(major, depth, itemStart)
        val arg = argument(info, itemStart)
        return when (major) {
            0 -> CborInt(arg)
            1 -> CborInt(BigInteger.ONE.negate().subtract(arg))
            2 -> CborBytes(take(length(arg, itemStart)))
            3 -> CborText(String(take(length(arg, itemStart)), Charsets.UTF_8))
            4 -> CborArray(List(length(arg, itemStart)) { read(depth + 1) })
            5 -> CborMap(List(length(arg, itemStart)) { read(depth + 1) to read(depth + 1) })
            else -> CborTag(arg, read(depth + 1)) // major 6
        }
    }

    fun readIndefinite(major: Int, depth: Int, itemStart: Int): CborValue {
        fun atBreak(): Boolean {
            if (pos >= end) throw CborException("indefinite item has no break", itemStart)
            return (bytes[pos].toInt() and 0xFF) == BREAK
        }
        return when (major) {
            2, 3 -> {
                val out = java.io.ByteArrayOutputStream()
                while (!atBreak()) {
                    val chunkStart = pos
                    val chunk = read(depth + 1)
                    when {
                        major == 2 && chunk is CborBytes -> out.write(chunk.value)
                        major == 3 && chunk is CborText -> out.write(chunk.value.toByteArray(Charsets.UTF_8))
                        else -> throw CborException("indefinite string chunk of the wrong type", chunkStart)
                    }
                }
                pos++
                if (major == 2) CborBytes(out.toByteArray()) else CborText(String(out.toByteArray(), Charsets.UTF_8))
            }
            4 -> {
                val list = mutableListOf<CborValue>()
                while (!atBreak()) list += read(depth + 1)
                pos++
                CborArray(list)
            }
            5 -> {
                val list = mutableListOf<Pair<CborValue, CborValue>>()
                while (!atBreak()) list += read(depth + 1) to read(depth + 1)
                pos++
                CborMap(list)
            }
            else -> throw CborException("indefinite length not allowed for major type $major", itemStart)
        }
    }

    fun readMajor7(info: Int, itemStart: Int): CborValue = when (info) {
        in 0..19 -> CborSimple(info)
        20 -> CborBool(false)
        21 -> CborBool(true)
        22 -> CborNull
        23 -> CborUndefined
        24 -> {
            val v = u8()
            if (v < 32) throw CborException("simple value $v must use the one-byte form", itemStart)
            CborSimple(v)
        }
        25 -> CborFloat(halfToDouble(BigInteger(1, take(2)).toInt()))
        26 -> CborFloat(java.lang.Float.intBitsToFloat(BigInteger(1, take(4)).toInt()).toDouble())
        27 -> CborFloat(java.lang.Double.longBitsToDouble(BigInteger(1, take(8)).toLong()))
        31 -> throw CborException("unexpected break", itemStart)
        else -> throw CborException("reserved additional info $info", itemStart)
    }
}

private fun halfToDouble(h: Int): Double {
    val sign = if (h and 0x8000 != 0) -1.0 else 1.0
    val exponent = (h shr 10) and 0x1F
    val mantissa = h and 0x3FF
    return sign * when (exponent) {
        0 -> mantissa * 2.0.pow(-24)
        31 -> if (mantissa == 0) Double.POSITIVE_INFINITY else Double.NaN
        else -> (mantissa + 1024) * 2.0.pow(exponent - 25)
    }
}

fun CborValue.toDiagnostic(maxBytes: Int = 64): String = StringBuilder().also { appendDiagnostic(it, this, maxBytes) }.toString()

private fun appendDiagnostic(sb: StringBuilder, v: CborValue, maxBytes: Int) {
    when (v) {
        is CborInt -> sb.append(v.value.toString())
        is CborBytes -> {
            sb.append("h'")
            v.value.take(maxBytes).forEach { sb.append("%02x".format(it)) }
            sb.append("'")
            if (v.value.size > maxBytes) sb.append("…(+${v.value.size - maxBytes} bytes)")
        }
        is CborText -> {
            sb.append('"')
            v.value.forEach { c ->
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    else -> sb.append(c)
                }
            }
            sb.append('"')
        }
        is CborArray -> {
            sb.append('[')
            v.items.forEachIndexed { i, item -> if (i > 0) sb.append(", "); appendDiagnostic(sb, item, maxBytes) }
            sb.append(']')
        }
        is CborMap -> {
            sb.append('{')
            v.entries.forEachIndexed { i, (k, value) ->
                if (i > 0) sb.append(", ")
                appendDiagnostic(sb, k, maxBytes)
                sb.append(": ")
                appendDiagnostic(sb, value, maxBytes)
            }
            sb.append('}')
        }
        is CborTag -> { sb.append(v.tag.toString()).append('('); appendDiagnostic(sb, v.value, maxBytes); sb.append(')') }
        is CborBool -> sb.append(v.value.toString())
        CborNull -> sb.append("null")
        CborUndefined -> sb.append("undefined")
        is CborSimple -> sb.append("simple(${v.value})")
        is CborFloat -> sb.append(
            when {
                v.value.isNaN() -> "NaN"
                v.value == Double.POSITIVE_INFINITY -> "Infinity"
                v.value == Double.NEGATIVE_INFINITY -> "-Infinity"
                else -> v.value.toString()
            },
        )
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.c2pa.CborTest'`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit** — `git add` the two files; message `feat: add CBOR decoder for C2PA` + trailer.

---

### Task 3: JUMBF parser

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/c2pa/Jumbf.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/c2pa/JumbfTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `class JumbfDescription(val contentType: ByteArray, val toggles: Int, val label: String?, val id: Long?, val signature: ByteArray?, val privateBoxType: String?)` with `val contentTypeName: String`
  - `class JumbfBox(val type: String, val offset: Int, val headerSize: Int, val size: Int, val description: JumbfDescription?, val children: List<JumbfBox>, val warnings: List<String>)` with `val label: String?`, `val payloadStart: Int` (= offset + headerSize), `val payloadEnd: Int` (= offset + size), `fun child(label: String): JumbfBox?`, `fun firstContent(type: String): JumbfBox?`
  - `fun parseJumbfBoxes(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): List<JumbfBox>`
  - `fun jumbfContentTypeName(uuid: ByteArray): String`
  - `const val JUMBF_EMBEDDED_FILE_TYPE = "embedded-file"`

Semantics: for a `jumb` superbox whose first inner box is `jumd`, `description` is the parsed `jumd` and `children` are the inner boxes AFTER the `jumd`; for any other box `children` is empty. Offsets are logical offsets into `bytes`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.multiviewer.parser.c2pa

import com.multiviewer.parser.hex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JumbfTest {
    private val iso = hex("00110010800000aa00389b71")
    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = u32(8 + payload.size) + type.toByteArray() + payload
    private fun jumd(fourCc: String, toggles: Int, label: String?, extra: ByteArray = ByteArray(0)) =
        box("jumd", fourCc.toByteArray() + iso + byteArrayOf(toggles.toByte()) + (label?.let { it.toByteArray() + byteArrayOf(0) } ?: ByteArray(0)) + extra)

    @Test
    fun `superbox with description and content`() {
        val bytes = box("jumb", jumd("cbor", 0x03, "c2pa.actions") + box("cbor", hex("a0")))
        val top = parseJumbfBoxes(bytes).single()
        assertEquals("jumb", top.type)
        assertEquals("c2pa.actions", top.label)
        assertEquals("cbor", top.description!!.contentTypeName)
        assertEquals(1, top.children.size)
        val content = top.firstContent("cbor")!!
        assertEquals(hex("a0").toList(), bytes.copyOfRange(content.payloadStart, content.payloadEnd).toList())
        assertTrue(top.warnings.isEmpty())
    }

    @Test
    fun `id, signature and private box toggles`() {
        val extra = u32(7) + ByteArray(32) { 1 } + box("c2sh", ByteArray(16))
        val d = parseJumbfBoxes(box("jumb", jumd("json", 0x02 or 0x04 or 0x08 or 0x10, "x", extra))).single().description!!
        assertEquals("x", d.label)
        assertEquals(7L, d.id)
        assertEquals(32, d.signature!!.size)
        assertEquals("c2sh", d.privateBoxType)
    }

    @Test
    fun `no label toggle and non-standard uuid`() {
        val uuid = hex("40cb0c32bb8a489da70b2ad6f47f4369")
        val bytes = box("jumb", box("jumd", uuid + byteArrayOf(0x01)))
        val d = parseJumbfBoxes(bytes).single().description!!
        assertNull(d.label)
        assertEquals(JUMBF_EMBEDDED_FILE_TYPE, d.contentTypeName)
        assertEquals("01020304-0506-0708-090a-0b0c0d0e0f10", jumbfContentTypeName(hex("0102030405060708090a0b0c0d0e0f10")))
    }

    @Test
    fun `lbox 0 extends to end and lbox 1 uses 64-bit size`() {
        val zero = byteArrayOf(0, 0, 0, 0) + "free".toByteArray() + ByteArray(5)
        assertEquals(13, parseJumbfBoxes(zero).single().size)
        val large = byteArrayOf(0, 0, 0, 1) + "free".toByteArray() + hex("0000000000000014") + ByteArray(4)
        val b = parseJumbfBoxes(large).single()
        assertEquals(16, b.headerSize)
        assertEquals(20, b.size)
    }

    @Test
    fun `truncated box becomes a warning and stops the level`() {
        val bytes = box("free", ByteArray(2)) + u32(100) + "jumb".toByteArray() + ByteArray(4)
        val boxes = parseJumbfBoxes(bytes)
        assertEquals(2, boxes.size)
        assertTrue(boxes[1].warnings.single().contains("past"))
    }

    @Test
    fun `label without terminator and superbox without description warn`() {
        val noNul = box("jumb", box("jumd", "cbor".toByteArray() + iso + byteArrayOf(0x02) + "abc".toByteArray()))
        val top = parseJumbfBoxes(noNul).single()
        assertEquals("abc", top.label)
        assertTrue(top.warnings.any { it.contains("NUL") })
        val noJumd = parseJumbfBoxes(box("jumb", box("cbor", hex("a0")))).single()
        assertNull(noJumd.description)
        assertTrue(noJumd.warnings.any { it.contains("jumd") })
    }

    @Test
    fun `real C jpg store`() {
        val file = File(javaClass.getResource("/c2pa/C.jpg")!!.toURI()).readBytes()
        val store = file.copyOfRange(32, 32 + 45872)
        val top = parseJumbfBoxes(store).single()
        assertEquals("c2pa", top.label)
        assertEquals("c2pa", top.description!!.contentTypeName)
        val manifest = top.children.single()
        assertEquals("contentauth:urn:uuid:b2b1f7fa-b119-4de1-9c0d-c97fbea3f2c3", manifest.label)
        assertEquals("c2ma", manifest.description!!.contentTypeName)
        assertEquals(listOf("c2pa.assertions", "c2pa.claim", "c2pa.signature"), manifest.children.map { it.label })
        val assertions = assertNotNull(manifest.child("c2pa.assertions"))
        assertEquals(
            listOf("c2pa.thumbnail.claim.jpeg", "stds.schema-org.CreativeWork", "c2pa.actions", "c2pa.hash.data"),
            assertions.children.map { it.label },
        )
        val creativeWork = assertions.child("stds.schema-org.CreativeWork")!!
        assertEquals(0x13, creativeWork.description!!.toggles)
        assertEquals("c2sh", creativeWork.description!!.privateBoxType)
        assertEquals(JUMBF_EMBEDDED_FILE_TYPE, assertions.child("c2pa.thumbnail.claim.jpeg")!!.description!!.contentTypeName)
        assertEquals(listOf("bfdb", "bidb"), assertions.child("c2pa.thumbnail.claim.jpeg")!!.children.map { it.type })
        assertTrue(top.warnings.isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.c2pa.JumbfTest'`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement `Jumbf.kt`**

```kotlin
package com.multiviewer.parser.c2pa

// ISO/IEC 19566-5 JUMBF boxes over an in-memory C2PA manifest store. Offsets are logical offsets
// into the store byte array; C2paLocator maps them back to file offsets.

private val ISO_UUID_SUFFIX = byteArrayOf(
    0x00, 0x11, 0x00, 0x10, 0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
)
private val EMBEDDED_FILE_UUID = byteArrayOf(
    0x40, 0xCB.toByte(), 0x0C, 0x32, 0xBB.toByte(), 0x8A.toByte(), 0x48, 0x9D.toByte(),
    0xA7.toByte(), 0x0B, 0x2A, 0xD6.toByte(), 0xF4.toByte(), 0x7F, 0x43, 0x69,
)
const val JUMBF_EMBEDDED_FILE_TYPE = "embedded-file"
private const val MAX_SUPERBOX_DEPTH = 32

fun jumbfContentTypeName(uuid: ByteArray): String = when {
    uuid.size == 16 && uuid.copyOfRange(4, 16).contentEquals(ISO_UUID_SUFFIX) &&
        uuid.copyOfRange(0, 4).all { it in 0x20..0x7E } -> String(uuid, 0, 4, Charsets.US_ASCII)
    uuid.contentEquals(EMBEDDED_FILE_UUID) -> JUMBF_EMBEDDED_FILE_TYPE
    else -> uuid.joinToString("") { "%02x".format(it) }.let {
        if (it.length != 32) it else "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}"
    }
}

class JumbfDescription(
    val contentType: ByteArray,
    val toggles: Int,
    val label: String?,
    val id: Long?,
    val signature: ByteArray?,
    val privateBoxType: String?,
) {
    val contentTypeName: String get() = jumbfContentTypeName(contentType)
}

class JumbfBox(
    val type: String,
    val offset: Int,
    val headerSize: Int,
    val size: Int,
    val description: JumbfDescription?,
    val children: List<JumbfBox>,
    val warnings: List<String>,
) {
    val label: String? get() = description?.label
    val payloadStart: Int get() = offset + headerSize
    val payloadEnd: Int get() = offset + size
    fun child(label: String): JumbfBox? = children.firstOrNull { it.label == label }
    fun firstContent(type: String): JumbfBox? = children.firstOrNull { it.type == type }
}

fun parseJumbfBoxes(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): List<JumbfBox> =
    parseLevel(bytes, start, end, 0)

private fun u32(b: ByteArray, at: Int): Long =
    ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
        ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

private fun fourCc(b: ByteArray, at: Int): String =
    String(CharArray(4) { (b[at + it].toInt() and 0xFF).toChar() })

private fun parseLevel(bytes: ByteArray, start: Int, end: Int, depth: Int): List<JumbfBox> {
    val out = mutableListOf<JumbfBox>()
    var pos = start
    while (pos < end) {
        val remaining = end - pos
        if (remaining < 8) {
            out += JumbfBox("?", pos, 0, remaining, null, emptyList(), listOf("Trailing $remaining byte(s): too short for a box header"))
            break
        }
        val lbox = u32(bytes, pos)
        val type = fourCc(bytes, pos + 4)
        var headerSize = 8
        val size: Long = when (lbox) {
            0L -> remaining.toLong()
            1L -> {
                if (remaining < 16) {
                    out += JumbfBox(type, pos, 8, remaining, null, emptyList(), listOf("64-bit size needs 16 header bytes, $remaining left"))
                    return out
                }
                headerSize = 16
                (u32(bytes, pos + 8) shl 32) or u32(bytes, pos + 12)
            }
            else -> lbox
        }
        if (size < headerSize || size > remaining) {
            out += JumbfBox(type, pos, minOf(headerSize, remaining), remaining, null, emptyList(), listOf("Declared size $size runs past the end of its parent ($remaining byte(s) left)"))
            break
        }
        val boxEnd = pos + size.toInt()
        if (type == "jumb" && depth < MAX_SUPERBOX_DEPTH) {
            val inner = parseLevel(bytes, pos + headerSize, boxEnd, depth + 1)
            val warnings = mutableListOf<String>()
            val first = inner.firstOrNull()
            val description = if (first?.type == "jumd") parseDescription(bytes, first, warnings) else null
            if (description == null) warnings += "Superbox has no jumd description box"
            val children = if (first?.type == "jumd") inner.drop(1) else inner
            out += JumbfBox(type, pos, headerSize, size.toInt(), description, children, warnings + (first?.warnings ?: emptyList()))
        } else {
            val warnings = if (type == "jumb") listOf("Superbox nesting deeper than $MAX_SUPERBOX_DEPTH not expanded") else emptyList()
            out += JumbfBox(type, pos, headerSize, size.toInt(), null, emptyList(), warnings)
        }
        pos = boxEnd
    }
    return out
}

private fun parseDescription(bytes: ByteArray, jumd: JumbfBox, warnings: MutableList<String>): JumbfDescription? {
    val start = jumd.payloadStart
    val end = jumd.payloadEnd
    if (end - start < 17) {
        warnings += "jumd too short (${end - start} bytes) for type and toggles"
        return null
    }
    val type = bytes.copyOfRange(start, start + 16)
    val toggles = bytes[start + 16].toInt() and 0xFF
    var q = start + 17
    var label: String? = null
    if (toggles and 0x02 != 0) {
        var nul = q
        while (nul < end && bytes[nul] != 0.toByte()) nul++
        label = String(bytes, q, nul - q, Charsets.UTF_8)
        if (nul >= end) {
            warnings += "jumd label is not NUL-terminated"
            q = end
        } else {
            q = nul + 1
        }
    }
    var id: Long? = null
    if (toggles and 0x04 != 0) {
        if (end - q >= 4) { id = u32(bytes, q); q += 4 } else warnings += "jumd ID toggle set but fewer than 4 bytes left"
    }
    var signature: ByteArray? = null
    if (toggles and 0x08 != 0) {
        if (end - q >= 32) { signature = bytes.copyOfRange(q, q + 32); q += 32 } else warnings += "jumd signature toggle set but fewer than 32 bytes left"
    }
    var privateType: String? = null
    if (toggles and 0x10 != 0) {
        if (end - q >= 8) privateType = fourCc(bytes, q + 4) else warnings += "jumd private-box toggle set but no box follows"
    }
    return JumbfDescription(type, toggles, label, id, signature, privateType)
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.c2pa.JumbfTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit** — message `feat: parse JUMBF boxes` + trailer.

---

### Task 4: Store locator and offset map

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/c2pa/C2paLocator.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/c2pa/C2paLocatorTest.kt`

**Interfaces:**
- Consumes: `parseJumbfBoxes`, `JumbfBox`; existing `ByteReader` (`readUInt8/16/32`, `readUInt64`, `readBytes`, `readFourCC`, `length`), `BoxNode`, `parseFile`.
- Produces:
  - `enum class C2paContainer { JPEG, BMFF, WEBP }`
  - `data class C2paSpan(val logicalStart: Int, val fileStart: Long, val length: Int)`
  - `class C2paOffsetMap(val spans: List<C2paSpan>)` with `fun toFile(logical: Int): Long` and `fun crossesSpan(logicalStart: Int, length: Int): Boolean`
  - `class C2paStoreLocation(val container: C2paContainer, val bytes: ByteArray, val offsetMap: C2paOffsetMap, val anchorOffsets: List<Long>, val warnings: List<String>)` — `anchorOffsets` are the file offsets of the APP11 segments / uuid box / chunk that carry the store.
  - `const val MAX_C2PA_STORE_BYTES = 64 * 1024 * 1024`
  - `val C2PA_UUID: ByteArray` (16 bytes `d8fec3d61b0e483c92975828877ec481`)
  - `fun readJpegC2paStore(reader: ByteReader, app11Offsets: List<Long>): C2paStoreLocation?` — offsets of APP11 marker starts (the `FF` byte)
  - `fun readBmffC2paStore(reader: ByteReader, uuidBoxOffset: Long, headerSize: Int, boxSize: Long): C2paStoreLocation?`
  - `fun readWebpC2paStore(reader: ByteReader, chunkOffset: Long, payloadSize: Long): C2paStoreLocation?`
  - `fun locateC2paStore(reader: ByteReader, root: BoxNode): C2paStoreLocation?` — dispatches on the root's top-level children: `APP11` nodes → JPEG; `uuid` nodes → BMFF (first whose usertype is C2PA and purpose `manifest`); `C2PA` node → WebP.

JPEG rules: parse each APP11 header (`CI`, `En`, `Z`) and skip segments whose CI ≠ "JP" or that are shorter than 16 bytes of payload; group by `En`; within a group sort by `Z`; warn on duplicate `Z` (keep first) or gaps; reassemble (segment Z=first: bytes from LBox; later: from LBox+8); warn when length ≠ LBox; choose the group whose reassembled bytes start a `jumb` whose description label is `c2pa`; if none, null. Truncate at `MAX_C2PA_STORE_BYTES` with a warning.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.multiviewer.parser.c2pa

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.byteReaderOf
import com.multiviewer.parser.hex
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class C2paLocatorTest {
    private fun fixture(name: String) = File(javaClass.getResource("/c2pa/$name")!!.toURI())
    private fun locate(name: String): C2paStoreLocation? {
        val f = fixture(name)
        val root = parseFile(f)
        return ByteReader.open(f).use { locateC2paStore(it, root) }
    }
    private fun topLabel(loc: C2paStoreLocation) = parseJumbfBoxes(loc.bytes).single().label

    @Test
    fun `single APP11 JPEG`() {
        val loc = assertNotNull(locate("C.jpg"))
        assertEquals(C2paContainer.JPEG, loc.container)
        assertEquals(45872, loc.bytes.size)
        assertEquals(listOf(20L), loc.anchorOffsets)
        assertEquals(32L, loc.offsetMap.toFile(0))
        assertEquals("c2pa", topLabel(loc))
        assertTrue(loc.warnings.isEmpty())
    }

    @Test
    fun `two APP11 segments are reassembled with a piecewise offset map`() {
        val loc = assertNotNull(locate("CA.jpg"))
        assertEquals(117241, loc.bytes.size)
        assertEquals(listOf(20L, 64032L), loc.anchorOffsets)
        assertEquals(32L, loc.offsetMap.toFile(0))
        assertEquals(64031L, loc.offsetMap.toFile(63999))
        assertEquals(64052L, loc.offsetMap.toFile(64000))
        assertTrue(loc.offsetMap.crossesSpan(63990, 20))
        assertFalse(loc.offsetMap.crossesSpan(0, 64000))
        assertEquals("c2pa", topLabel(loc))
        assertTrue(loc.warnings.isEmpty())
    }

    @Test
    fun `BMFF uuid store`() {
        val mp4 = assertNotNull(locate("video1.mp4"))
        assertEquals(C2paContainer.BMFF, mp4.container)
        assertEquals(30617, mp4.bytes.size)
        assertEquals(69L, mp4.offsetMap.toFile(0))
        assertEquals(listOf(24L), mp4.anchorOffsets)
        val heif = assertNotNull(locate("signed.heif"))
        assertEquals(13514, heif.bytes.size)
        assertEquals(69L, heif.offsetMap.toFile(0))
        assertEquals("c2pa", topLabel(heif))
    }

    @Test
    fun `WebP chunk store`() {
        val loc = assertNotNull(locate("signed_small.webp"))
        assertEquals(C2paContainer.WEBP, loc.container)
        assertEquals(13438, loc.bytes.size)
        assertEquals(50416L, loc.offsetMap.toFile(0))
        assertEquals(listOf(50408L), loc.anchorOffsets)
    }

    @Test
    fun `no manifest`() {
        assertNull(locate("no_manifest.jpg"))
    }

    // A 38-byte 'c2pa' superbox (jumb + jumd labelled "c2pa") split 30 + 8 across two JP segments.
    private val superbox = hex("000000266a756d62") + hex("0000001e6a756d64") +
        hex("6332706100110010800000aa00389b71") + hex("03") + hex("6332706100")

    private fun seg(z: Int, data: ByteArray): ByteArray {
        val payload = "JP".toByteArray() + hex("0001") + byteArrayOf(0, 0, 0, z.toByte()) + data
        val len = payload.size + 2
        return hex("ffeb") + byteArrayOf((len shr 8).toByte(), len.toByte()) + payload
    }

    @Test
    fun `synthetic APP11 sequence gap warns but still reassembles`() {
        val file = hex("ffd8") + seg(1, superbox.copyOfRange(0, 30)) + seg(3, superbox.copyOfRange(0, 8) + superbox.copyOfRange(30, 38))
        val reader = byteReaderOf(file)
        val loc = assertNotNull(readJpegC2paStore(reader, listOf(2L, 44L)))
        assertEquals(superbox.toList(), loc.bytes.toList())
        assertTrue(loc.warnings.single().contains("gap"))
        reader.close()
    }

    @Test
    fun `synthetic APP11 length mismatch and non-c2pa store`() {
        // two stray bytes after the superbox: reassembled 40 bytes vs LBox 38
        val padded = hex("ffd8") + seg(1, superbox + hex("0000"))
        val reader = byteReaderOf(padded)
        val loc = assertNotNull(readJpegC2paStore(reader, listOf(2L)))
        assertTrue(loc.warnings.single().contains("declares 38"))
        reader.close()
        val otherLabel = superbox.copyOf().also { it[33] = 'x'.code.toByte() } // label "x2pa"
        val r2 = byteReaderOf(hex("ffd8") + seg(1, otherLabel))
        assertNull(readJpegC2paStore(r2, listOf(2L)))
        r2.close()
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.c2pa.C2paLocatorTest'`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement `C2paLocator.kt`**

```kotlin
package com.multiviewer.parser.c2pa

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader

enum class C2paContainer { JPEG, BMFF, WEBP }

const val MAX_C2PA_STORE_BYTES = 64 * 1024 * 1024

val C2PA_UUID: ByteArray = byteArrayOf(
    0xD8.toByte(), 0xFE.toByte(), 0xC3.toByte(), 0xD6.toByte(), 0x1B, 0x0E, 0x48, 0x3C,
    0x92.toByte(), 0x97.toByte(), 0x58, 0x28, 0x87.toByte(), 0x7E, 0xC4.toByte(), 0x81.toByte(),
)

data class C2paSpan(val logicalStart: Int, val fileStart: Long, val length: Int)

class C2paOffsetMap(val spans: List<C2paSpan>) {
    fun toFile(logical: Int): Long {
        val span = spans.lastOrNull { logical >= it.logicalStart } ?: spans.first()
        return span.fileStart + (logical - span.logicalStart)
    }

    fun crossesSpan(logicalStart: Int, length: Int): Boolean {
        if (length <= 0) return false
        val first = spans.indexOfLast { logicalStart >= it.logicalStart }
        val last = spans.indexOfLast { logicalStart + length - 1 >= it.logicalStart }
        return first != last
    }
}

class C2paStoreLocation(
    val container: C2paContainer,
    val bytes: ByteArray,
    val offsetMap: C2paOffsetMap,
    val anchorOffsets: List<Long>,
    val warnings: List<String>,
)

fun locateC2paStore(reader: ByteReader, root: BoxNode): C2paStoreLocation? {
    val top = root.children
    val app11 = top.filter { it.type == "APP11" }
    if (app11.isNotEmpty()) return readJpegC2paStore(reader, app11.map { it.offset })
    top.firstOrNull { it.type == "C2PA" }?.let { return readWebpC2paStore(reader, it.offset, it.size - 8) }
    for (node in top.filter { it.type == "uuid" }) {
        readBmffC2paStore(reader, node.offset, node.headerSize, node.size)?.let { return it }
    }
    return null
}

private class App11(val offset: Long, val en: Int, val z: Long, val dataStart: Long, val dataEnd: Long)

fun readJpegC2paStore(reader: ByteReader, app11Offsets: List<Long>): C2paStoreLocation? {
    val segments = app11Offsets.mapNotNull { off ->
        if (off + 4 > reader.length) return@mapNotNull null
        val len = reader.readUInt16(off + 2)
        val segEnd = off + 2 + len
        if (len < 2 + 8 + 8 || segEnd > reader.length) return@mapNotNull null
        if (reader.readFourCC(off + 4).substring(0, 2) != "JP") return@mapNotNull null
        App11(off, reader.readUInt16(off + 6), reader.readUInt32(off + 8), off + 12, segEnd)
    }
    for ((_, group) in segments.groupBy { it.en }) {
        val warnings = mutableListOf<String>()
        val ordered = mutableListOf<App11>()
        group.sortedBy { it.z }.forEach { s ->
            if (ordered.any { it.z == s.z }) warnings += "Duplicate APP11 sequence number Z=${s.z} (En=${s.en}) ignored"
            else ordered += s
        }
        ordered.zipWithNext().forEach { (a, b) -> if (b.z != a.z + 1) warnings += "APP11 sequence gap: Z=${a.z} then Z=${b.z} (En=${a.en})" }
        val spans = mutableListOf<C2paSpan>()
        val out = java.io.ByteArrayOutputStream()
        var logical = 0
        var truncated = false
        ordered.forEachIndexed { i, s ->
            val from = if (i == 0) s.dataStart else s.dataStart + 8
            var length = (s.dataEnd - from).coerceAtLeast(0)
            if (logical + length > MAX_C2PA_STORE_BYTES) { length = (MAX_C2PA_STORE_BYTES - logical).toLong(); truncated = true }
            if (length > 0) {
                spans += C2paSpan(logical, from, length.toInt())
                out.write(reader.readBytes(from, length.toInt()))
                logical += length.toInt()
            }
            if (truncated) return@forEachIndexed
        }
        val bytes = out.toByteArray()
        if (bytes.size < 8 || spans.isEmpty()) continue
        if (truncated) warnings += "Manifest store truncated at $MAX_C2PA_STORE_BYTES bytes"
        val lbox = ((bytes[0].toLong() and 0xFF) shl 24) or ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or (bytes[3].toLong() and 0xFF)
        if (!truncated && lbox != bytes.size.toLong()) warnings += "Reassembled APP11 data is ${bytes.size} bytes but the superbox declares $lbox"
        val top = parseJumbfBoxes(bytes).firstOrNull()
        if (top?.type == "jumb" && top.label == "c2pa") {
            return C2paStoreLocation(C2paContainer.JPEG, bytes, C2paOffsetMap(spans), ordered.map { it.offset }, warnings)
        }
    }
    return null
}

fun readBmffC2paStore(reader: ByteReader, uuidBoxOffset: Long, headerSize: Int, boxSize: Long): C2paStoreLocation? {
    val payload = uuidBoxOffset + headerSize
    val end = uuidBoxOffset + boxSize
    if (end - payload < 16 + 4 + 1 + 8) return null
    if (!reader.readBytes(payload, 16).contentEquals(C2PA_UUID)) return null
    var q = payload + 16 + 4
    val purposeBytes = java.io.ByteArrayOutputStream()
    while (q < end) {
        val b = reader.readUInt8(q)
        q++
        if (b == 0) break
        purposeBytes.write(b)
    }
    if (purposeBytes.toString(Charsets.UTF_8) != "manifest") return null
    q += 8 // merkle offset
    if (q >= end) return null
    var length = end - q
    val warnings = mutableListOf<String>()
    if (length > MAX_C2PA_STORE_BYTES) { length = MAX_C2PA_STORE_BYTES.toLong(); warnings += "Manifest store truncated at $MAX_C2PA_STORE_BYTES bytes" }
    val bytes = reader.readBytes(q, length.toInt())
    return C2paStoreLocation(C2paContainer.BMFF, bytes, C2paOffsetMap(listOf(C2paSpan(0, q, bytes.size))), listOf(uuidBoxOffset), warnings)
}

fun readWebpC2paStore(reader: ByteReader, chunkOffset: Long, payloadSize: Long): C2paStoreLocation? {
    if (payloadSize < 8) return null
    val warnings = mutableListOf<String>()
    var length = payloadSize
    if (length > MAX_C2PA_STORE_BYTES) { length = MAX_C2PA_STORE_BYTES.toLong(); warnings += "Manifest store truncated at $MAX_C2PA_STORE_BYTES bytes" }
    val start = chunkOffset + 8
    val bytes = reader.readBytes(start, length.toInt())
    return C2paStoreLocation(C2paContainer.WEBP, bytes, C2paOffsetMap(listOf(C2paSpan(0, start, bytes.size))), listOf(chunkOffset), warnings)
}
```

Note for the implementer: `parseFile` currently produces top-level JPEG nodes typed by `markerName` (`"APP11"`), WebP chunk nodes typed by their FourCC (`"C2PA"`), and BMFF top-level nodes typed by box type (`"uuid"`). Verify these names in `JpegWalker.kt`/`WebpWalker.kt`/`BoxWalker.kt` before relying on them; if a name differs, adapt `locateC2paStore` and say so in the report.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.c2pa.C2paLocatorTest'`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit** — message `feat: locate C2PA manifest stores in JPEG, BMFF and WebP` + trailer.

---

### Task 5: Structure-tree integration

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/c2pa/C2paTree.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt` (`parseJpegSegments`: return `attachC2paToJpegSegments(reader, result)` instead of `result`; add an `APP11` field decoder branch is NOT needed — the post-pass adds fields)
- Modify: `app/src/main/kotlin/com/multiviewer/parser/WebpWalker.kt` (`parseWebpChunks`: after building the `C2PA` chunk node, replace it with `attachC2paToWebpChunk(reader, node)`)
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt` (register `BoxRegistry.register("uuid", C2paUuidBoxDecoder)`)
- Modify: `app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt` (add `"uuid"`)
- Test: `app/src/test/kotlin/com/multiviewer/parser/c2pa/C2paTreeTest.kt`

**Interfaces:**
- Consumes: Task 3/4 APIs; `BoxNode(type, offset, headerSize, size, children, fields, warnings, summary)`, `BoxField(name, value, offset, length)`, `BoxDecoder`, `findFirst`.
- Produces:
  - `fun jumbfToBoxNodes(boxes: List<JumbfBox>, map: C2paOffsetMap, store: ByteArray): List<BoxNode>`
  - `fun attachC2paToJpegSegments(reader: ByteReader, segments: List<BoxNode>): List<BoxNode>`
  - `fun attachC2paToWebpChunk(reader: ByteReader, chunk: BoxNode): BoxNode`
  - `object C2paUuidBoxDecoder : BoxDecoder`
  - `fun hasC2paManifestStore(root: BoxNode): Boolean` — true when the tree has a `jumb` node whose `label` field is `c2pa`.

Node conventions: each JUMBF box becomes `BoxNode(type = box.type, offset = map.toFile(box.offset), headerSize = box.headerSize, size = box.size, children = ...)`. `jumb` nodes get fields `label`, `content_type` (contentTypeName), `toggles` (`0x%02X`), plus `id` / `private_box` when present, and `summary = label`. Their children are a `jumd` node (no children, same fields omitted) followed by the converted content children. Leaf `cbor` boxes get `summary` = top-level map keys joined by ", " when the payload decodes as a CBOR map (catch `CborException` → summary `"CBOR decode failed: <message>"`); leaf `bfdb` gets `summary` = MIME type. Boxes whose `crossesSpan` is true get the warning `"Spans an APP11 segment boundary; the hex highlight includes continuation-segment header bytes"`. JUMBF warnings are copied into node warnings.

JPEG post-pass: collect `APP11` segments (`type == "APP11"`); for each add fields `CI`, `En`, `Z` (offsets 4, 6, 8; lengths 2, 2, 4) when its payload starts with "JP"; locate via `readJpegC2paStore(reader, offsets)`; if found, the first anchor segment gets `children = jumbfToBoxNodes(...)`, `summary = "C2PA manifest store (N segment(s))"` and the location's warnings. Wrap the whole post-pass in try/catch(Exception) returning the input list unchanged plus a warning on the first APP11 — never throw.

WebP: if `chunk.type == "C2PA"`, locate and attach the same way (`summary = "C2PA manifest store"`).

`C2paUuidBoxDecoder`: fields `usertype` (UUID string, offset payload, 16); if C2PA: `version_flags` (4 bytes hex), `purpose`, `merkle_offset`, `summary = "C2PA (purpose)"`, children = JUMBF nodes when purpose is `manifest`; for non-C2PA uuid return the node with only `usertype` (same as before apart from that field).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.multiviewer.parser.c2pa

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.findFirst
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class C2paTreeTest {
    private fun tree(name: String) = parseFile(File(javaClass.getResource("/c2pa/$name")!!.toURI()))
    private fun BoxNode.field(name: String) = fields.firstOrNull { it.name == name }?.value
    private fun BoxNode.jumbs(): List<BoxNode> = children.filter { it.type == "jumb" }

    @Test
    fun `JPEG APP11 carries fields and the JUMBF tree with file offsets`() {
        val root = tree("C.jpg")
        val app11 = root.children.first { it.type == "APP11" }
        assertEquals("JP", app11.field("CI"))
        assertEquals("529", app11.field("En"))
        assertEquals("1", app11.field("Z"))
        val store = app11.jumbs().single()
        assertEquals("c2pa", store.field("label"))
        assertEquals(32L, store.offset)
        assertEquals(45872L, store.size)
        assertEquals("jumd", store.children.first().type)
        val manifest = store.jumbs().single()
        assertEquals("c2ma", manifest.field("content_type"))
        val claim = manifest.jumbs().first { it.field("label") == "c2pa.claim" }
        val cbor = claim.children.first { it.type == "cbor" }
        assertTrue(cbor.summary!!.startsWith("dc:title, dc:format, instanceID"))
        assertTrue(hasC2paManifestStore(root))
    }

    @Test
    fun `split JPEG store warns on boundary-spanning boxes`() {
        val root = tree("CA.jpg")
        val app11s = root.children.filter { it.type == "APP11" }
        assertEquals(2, app11s.size)
        assertTrue(app11s[1].children.isEmpty()) // tree hangs under the first segment only
        val store = app11s[0].jumbs().single()
        assertTrue(store.warnings.any { it.contains("boundary") }) // the store itself spans both segments
        assertTrue(app11s[0].summary!!.contains("2 segment"))
    }

    @Test
    fun `BMFF uuid and WebP chunk`() {
        val mp4 = tree("video1.mp4")
        val uuid = mp4.children.first { it.type == "uuid" }
        assertEquals("d8fec3d6-1b0e-483c-9297-5828877ec481", uuid.field("usertype"))
        assertEquals("manifest", uuid.field("purpose"))
        assertEquals(2, uuid.jumbs().single().jumbs().size)
        val second = mp4.children.filter { it.type == "uuid" }[1]
        assertTrue(second.children.isEmpty()) // merkle uuid has no manifest store
        val webp = tree("signed_small.webp")
        val chunk = webp.children.first { it.type == "C2PA" }
        assertEquals("c2pa", chunk.jumbs().single().field("label"))
        assertEquals(50416L, chunk.jumbs().single().offset)
    }

    @Test
    fun `files without C2PA are unchanged`() {
        val root = tree("no_manifest.jpg")
        assertFalse(hasC2paManifestStore(root))
        assertTrue(root.children.none { it.type == "APP11" && it.children.isNotEmpty() })
    }

    @Test
    fun `heif store has v2 labels`() {
        val root = tree("signed.heif")
        val store = assertNotNull(findFirst(root) { it.type == "jumb" && it.fields.any { f -> f.name == "label" && f.value == "c2pa" } })
        val labels = mutableListOf<String>()
        fun walk(n: BoxNode) { n.field("label")?.let { labels += it }; n.children.forEach(::walk) }
        walk(store)
        assertTrue("c2pa.claim.v2" in labels)
        assertTrue("c2pa.hash.bmff.v3" in labels)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.c2pa.C2paTreeTest'`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement `C2paTree.kt`**

```kotlin
package com.multiviewer.parser.c2pa

import com.multiviewer.parser.BoxDecoder
import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.findFirst

private const val BOUNDARY_WARNING = "Spans an APP11 segment boundary; the hex highlight includes continuation-segment header bytes"

fun jumbfToBoxNodes(boxes: List<JumbfBox>, map: C2paOffsetMap, store: ByteArray): List<BoxNode> = boxes.map { box ->
    val fileOffset = map.toFile(box.offset)
    val warnings = box.warnings + if (map.crossesSpan(box.offset, box.size)) listOf(BOUNDARY_WARNING) else emptyList()
    val d = box.description
    if (box.type == "jumb" && d != null) {
        val jumdFile = map.toFile(box.payloadStart)
        val fields = buildList {
            add(BoxField("label", d.label ?: "", map.toFile(box.payloadStart + 8 + 17), 0))
            add(BoxField("content_type", d.contentTypeName, map.toFile(box.payloadStart + 8), 16))
            add(BoxField("toggles", "0x%02X".format(d.toggles), map.toFile(box.payloadStart + 8 + 16), 1))
            d.id?.let { add(BoxField("id", it.toString(), jumdFile, 0)) }
            d.privateBoxType?.let { add(BoxField("private_box", it, jumdFile, 0)) }
        }
        val jumdNode = BoxNode(type = "jumd", offset = jumdFile, headerSize = 8, size = descriptionSize(store, box))
        BoxNode(
            type = box.type, offset = fileOffset, headerSize = box.headerSize, size = box.size.toLong(),
            children = listOf(jumdNode) + jumbfToBoxNodes(box.children, map, store),
            fields = fields, warnings = warnings, summary = d.label,
        )
    } else {
        BoxNode(
            type = box.type, offset = fileOffset, headerSize = box.headerSize, size = box.size.toLong(),
            children = jumbfToBoxNodes(box.children, map, store), warnings = warnings, summary = leafSummary(box, store),
        )
    }
}

// The jumd box is the first inner box of a superbox; its LBox gives its size.
private fun descriptionSize(store: ByteArray, superbox: JumbfBox): Long {
    val at = superbox.payloadStart
    if (at + 4 > store.size) return 0
    return ((store[at].toLong() and 0xFF) shl 24) or ((store[at + 1].toLong() and 0xFF) shl 16) or
        ((store[at + 2].toLong() and 0xFF) shl 8) or (store[at + 3].toLong() and 0xFF)
}

private fun leafSummary(box: JumbfBox, store: ByteArray): String? = when (box.type) {
    "cbor" -> try {
        val v = decodeCbor(store, box.payloadStart, box.payloadEnd)
        (v as? CborMap)?.entries?.joinToString(", ") { (k, _) -> k.asText() ?: k.toDiagnostic(8) } ?: v.toDiagnostic(16).take(80)
    } catch (e: CborException) {
        "CBOR decode failed: ${e.message}"
    }
    "bfdb" -> if (box.payloadEnd - box.payloadStart > 1) {
        val start = box.payloadStart + 1
        var nul = start
        while (nul < box.payloadEnd && store[nul] != 0.toByte()) nul++
        String(store, start, nul - start, Charsets.UTF_8)
    } else null
    "json" -> "${box.payloadEnd - box.payloadStart} bytes JSON"
    "bidb" -> "${box.payloadEnd - box.payloadStart} bytes"
    else -> null
}

fun attachC2paToJpegSegments(reader: ByteReader, segments: List<BoxNode>): List<BoxNode> {
    if (segments.none { it.type == "APP11" }) return segments
    return try {
        val withFields = segments.map { seg ->
            if (seg.type != "APP11" || seg.size < 12) return@map seg
            if (reader.readFourCC(seg.offset + 4).substring(0, 2) != "JP") return@map seg
            seg.copy(
                fields = seg.fields + listOf(
                    BoxField("CI", "JP", seg.offset + 4, 2),
                    BoxField("En", reader.readUInt16(seg.offset + 6).toString(), seg.offset + 6, 2),
                    BoxField("Z", reader.readUInt32(seg.offset + 8).toString(), seg.offset + 8, 4),
                ),
            )
        }
        val loc = readJpegC2paStore(reader, withFields.filter { it.type == "APP11" }.map { it.offset }) ?: return withFields
        val anchor = loc.anchorOffsets.first()
        val nodes = jumbfToBoxNodes(parseJumbfBoxes(loc.bytes), loc.offsetMap, loc.bytes)
        withFields.map { seg ->
            if (seg.offset != anchor) seg
            else seg.copy(
                children = nodes,
                summary = "C2PA manifest store (${loc.anchorOffsets.size} segment(s))",
                warnings = seg.warnings + loc.warnings,
            )
        }
    } catch (e: Exception) {
        segments.map { if (it.type == "APP11") it.copy(warnings = it.warnings + "C2PA parsing failed: ${e.message}") else it }
    }
}

fun attachC2paToWebpChunk(reader: ByteReader, chunk: BoxNode): BoxNode {
    if (chunk.type != "C2PA") return chunk
    return try {
        val loc = readWebpC2paStore(reader, chunk.offset, chunk.size - 8) ?: return chunk
        chunk.copy(
            children = jumbfToBoxNodes(parseJumbfBoxes(loc.bytes), loc.offsetMap, loc.bytes),
            summary = "C2PA manifest store",
            warnings = chunk.warnings + loc.warnings,
        )
    } catch (e: Exception) {
        chunk.copy(warnings = chunk.warnings + "C2PA parsing failed: ${e.message}")
    }
}

object C2paUuidBoxDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        val payload = offset + headerSize
        if (size - headerSize < 16) return BoxNode(type, offset, headerSize, size, warnings = warnings)
        val usertype = reader.readBytes(payload, 16)
        val uuidField = BoxField("usertype", uuidString(usertype), payload, 16)
        if (!usertype.contentEquals(C2PA_UUID)) return BoxNode(type, offset, headerSize, size, fields = listOf(uuidField), warnings = warnings)
        return try {
            val fields = mutableListOf(uuidField)
            if (size - headerSize >= 20) fields += BoxField("version_flags", "%08x".format(reader.readUInt32(payload + 16)), payload + 16, 4)
            var q = payload + 20
            val sb = StringBuilder()
            while (q < offset + size && reader.readUInt8(q) != 0) { sb.append(reader.readUInt8(q).toChar()); q++ }
            val purposeStart = payload + 20
            fields += BoxField("purpose", sb.toString(), purposeStart, (q - purposeStart + 1))
            q++
            if (q + 8 <= offset + size) fields += BoxField("merkle_offset", reader.readUInt64(q).toString(), q, 8)
            val loc = readBmffC2paStore(reader, offset, headerSize, size)
            val children = loc?.let { jumbfToBoxNodes(parseJumbfBoxes(it.bytes), it.offsetMap, it.bytes) } ?: emptyList()
            BoxNode(type, offset, headerSize, size, children = children, fields = fields, warnings = warnings + (loc?.warnings ?: emptyList()), summary = "C2PA (${sb})")
        } catch (e: Exception) {
            BoxNode(type, offset, headerSize, size, fields = listOf(uuidField), warnings = warnings + "C2PA parsing failed: ${e.message}")
        }
    }
}

private fun uuidString(b: ByteArray): String {
    val h = b.joinToString("") { "%02x".format(it) }
    return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
}

fun hasC2paManifestStore(root: BoxNode): Boolean =
    findFirst(root) { n -> n.type == "jumb" && n.fields.any { it.name == "label" && it.value == "c2pa" } } != null
```

Modify `JpegWalker.kt` `parseJpegSegments`: change the final `return result` to `return com.multiviewer.parser.c2pa.attachC2paToJpegSegments(reader, result)`.

Modify `WebpWalker.kt` `parseWebpChunks`: change `result.add(decodeWebpChunk(reader, type, pos, chunkSize, totalSize))` to `result.add(com.multiviewer.parser.c2pa.attachC2paToWebpChunk(reader, decodeWebpChunk(reader, type, pos, chunkSize, totalSize)))`.

Modify `Decoders.kt`: add `BoxRegistry.register("uuid", com.multiviewer.parser.c2pa.C2paUuidBoxDecoder)` next to the other leaf registrations, and add `"uuid"` to `DecodersRegistrationTest`'s list.

Check `BoxNode` has a `copy` (it is a `data class` in `BoxNode.kt`). Check that no existing code relies on top-level `uuid` nodes having no fields (`grep -rn '"uuid"' app/src/main`), and report what you find.

- [ ] **Step 4: Run to verify pass, then the whole suite**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.c2pa.C2paTreeTest' --tests 'com.multiviewer.parser.DecodersRegistrationTest'`
Expected: PASS.
Run: `./gradlew :app:test`
Expected: no new failures (structure tests for JPEG/WebP/BMFF must still pass — files without C2PA are unchanged).

- [ ] **Step 5: Commit** — message `feat: show C2PA JUMBF boxes in the structure tree` + trailer.

---

### Task 6: Manifest model

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/c2pa/C2paManifest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/c2pa/C2paManifestTest.kt`

**Interfaces:**
- Consumes: Tasks 2–4.
- Produces:
  - `data class C2paAction(val action: String, val softwareAgent: String?, val whenTime: String?, val digitalSourceType: String?, val parameters: String?)` — `parameters` is CBOR diagnostic text
  - `data class C2paIngredient(val title: String?, val format: String?, val relationship: String?, val instanceId: String?, val manifestRef: String?, val thumbnailRef: String?)`
  - `sealed interface C2paAssertionContent` with `data class Actions(val actions: List<C2paAction>)`, `data class Ingredient(val ingredient: C2paIngredient)`, `class Thumbnail(val mimeType: String?, val bytes: ByteArray)`, `data class Cbor(val value: CborValue)`, `data class Json(val text: String)`, `data class Failed(val reason: String)`, `data object Empty`
  - `data class C2paAssertion(val label: String, val contentType: String, val content: C2paAssertionContent, val fileOffset: Long, val size: Int)`
  - `data class C2paClaim(val label: String, val version: Int, val title: String?, val format: String?, val instanceId: String?, val claimGenerator: String?, val generatorInfo: List<String>, val signatureRef: String?, val assertionRefs: List<String>, val alg: String?, val raw: CborValue)`
  - `data class C2paSignatureInfo(val algorithm: String?, val certificateSubject: String?, val certificateIssuer: String?, val chainLength: Int, val error: String?)`
  - `class C2paManifest(val label: String, val claim: C2paClaim?, val claimError: String?, val claimBytes: ByteArray?, val assertions: List<C2paAssertion>, val signature: C2paSignatureInfo?, val signatureBytes: ByteArray?, val warnings: List<String>)`
  - `class C2paManifestStore(val location: C2paStoreLocation, val manifests: List<C2paManifest>, val warnings: List<String>)` with `val active: C2paManifest?` (= last)
  - `fun buildC2paManifestStore(location: C2paStoreLocation): C2paManifestStore`
  - `fun readC2paManifestStore(file: java.io.File, root: BoxNode): C2paManifestStore?` (null when no store; never throws)
  - `fun coseAlgorithmName(alg: Long): String` (-7 ES256, -35 ES384, -36 ES512, -37 PS256, -38 PS384, -39 PS512, -8 EdDSA, else "alg <n>")

Rules:
- Manifests = children of the store whose description content type is `c2ma` or `c2um`; label = description label.
- Claim = child superbox whose content type is `c2cl`; decode its `cbor` content box. version = 2 when the label ends with `.v2`, else 1. `claim_generator_info` may be an array of maps or a single map; each map → "name version" (version omitted when absent). `assertionRefs` = urls from `assertions`, or from `created_assertions` + `gathered_assertions`. `alg` = text `alg`.
- Assertions = children of the `c2as` superbox, in order. Content: embedded-file type → Thumbnail (MIME from `bfdb`, bytes from `bidb`); `cbor` box → decode; label starting `c2pa.actions` → Actions; label starting `c2pa.ingredient` → Ingredient; other CBOR → Cbor; `json` box → Json (UTF-8); no content box → Empty; decode errors → Failed("<message>").
- Actions: from `actions` array; `softwareAgent` may be text or a map (use its `name`); `when` text; `digitalSourceType` text; `parameters` → diagnostic text.
- Ingredient: `dc:title`, `dc:format`, `relationship`, `instanceID` (or `instanceId`), manifest ref = `c2pa_manifest.url` or `active_manifest.url`, thumbnail ref = `thumbnail.url`.
- Signature = child superbox with content type `c2cs`; its `cbor` content is a CBOR tag 18 wrapping a 4-array. Protected header = decode the first element's bytes; `alg` = protected[1]; x5chain = protected[33] or unprotected[33] or unprotected["x5chain"]; it is either bytes or an array of bytes. Parse the first certificate with `CertificateFactory.getInstance("X.509")`; subject/issuer = `X509Certificate.subjectX500Principal.name` / `issuerX500Principal.name`. Any failure → `C2paSignatureInfo(..., error = message)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.multiviewer.parser.c2pa

import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class C2paManifestTest {
    private fun fixture(name: String) = File(javaClass.getResource("/c2pa/$name")!!.toURI())
    private fun store(name: String) = assertNotNull(fixture(name).let { readC2paManifestStore(it, parseFile(it)) })

    @Test
    fun `C jpg v1 claim, actions and PS256 signer`() {
        val s = store("C.jpg")
        val m = s.manifests.single()
        assertEquals("contentauth:urn:uuid:b2b1f7fa-b119-4de1-9c0d-c97fbea3f2c3", m.label)
        val claim = assertNotNull(m.claim)
        assertEquals(1, claim.version)
        assertEquals("C.jpg", claim.title)
        assertEquals("image/jpeg", claim.format)
        assertEquals("xmp:iid:22704d84-c37f-4733-a207-56c4c2e67b1a", claim.instanceId)
        assertEquals("make_test_images/0.33.1 c2pa-rs/0.33.1", claim.claimGenerator)
        assertEquals(listOf("make_test_images 0.33.1", "c2pa-rs 0.33.1"), claim.generatorInfo)
        assertEquals("self#jumbf=c2pa.signature", claim.signatureRef)
        assertEquals(4, claim.assertionRefs.size)
        assertEquals("sha256", claim.alg)
        assertEquals(
            listOf("c2pa.thumbnail.claim.jpeg", "stds.schema-org.CreativeWork", "c2pa.actions", "c2pa.hash.data"),
            m.assertions.map { it.label },
        )
        val thumb = assertIs<C2paAssertionContent.Thumbnail>(m.assertions[0].content)
        assertEquals("image/jpeg", thumb.mimeType)
        assertEquals(0xFF.toByte(), thumb.bytes[0])
        assertIs<C2paAssertionContent.Json>(m.assertions[1].content)
        val action = assertIs<C2paAssertionContent.Actions>(m.assertions[2].content).actions.single()
        assertEquals("c2pa.created", action.action)
        assertEquals("Make Test Images 0.33.1", action.softwareAgent)
        assertEquals("http://cv.iptc.org/newscodes/digitalsourcetype/algorithmicMedia", action.digitalSourceType)
        assertEquals("{\"name\": \"gradient\"}", action.parameters)
        assertIs<C2paAssertionContent.Cbor>(m.assertions[3].content)
        val sig = assertNotNull(m.signature)
        assertEquals("PS256", sig.algorithm)
        assertEquals(2, sig.chainLength)
        assertTrue(sig.certificateSubject!!.startsWith("CN=C2PA Signer"))
        assertTrue(sig.certificateIssuer!!.startsWith("CN=Intermediate CA"))
        assertNull(sig.error)
        assertTrue(m.signatureBytes!!.isNotEmpty())
        assertTrue(m.claimBytes!!.isNotEmpty())
        assertEquals(s.manifests.last(), s.active)
    }

    @Test
    fun `CA jpg ingredient`() {
        val m = store("CA.jpg").active!!
        val ing = m.assertions.firstNotNullOf { (it.content as? C2paAssertionContent.Ingredient)?.ingredient }
        assertEquals("A.jpg", ing.title)
        assertEquals("image/jpeg", ing.format)
        assertEquals("parentOf", ing.relationship)
        assertEquals("xmp.iid:813ee422-9736-4cdc-9be6-4e35ed8e41cb", ing.instanceId)
        assertEquals("self#jumbf=c2pa.assertions/c2pa.thumbnail.ingredient.jpeg", ing.thumbnailRef)
        assertNull(ing.manifestRef)
        val actions = m.assertions.firstNotNullOf { (it.content as? C2paAssertionContent.Actions)?.actions }
        assertEquals(listOf("c2pa.opened", "c2pa.color_adjustments"), actions.map { it.action })
    }

    @Test
    fun `video1 two manifests, unprotected text-key x5chain, ingredient manifest ref`() {
        val s = store("video1.mp4")
        assertEquals(
            listOf("urn:uuid:54281c07-ad34-430e-bea5-112a18facf0b", "urn:uuid:8da8ffb6-3de4-430a-8924-5b58b561e647"),
            s.manifests.map { it.label },
        )
        val active = s.active!!
        assertEquals("TestApp c2patool/0.6.2 c2pa-rs/0.28.2", active.claim!!.claimGenerator)
        assertEquals(emptyList(), active.claim!!.generatorInfo) // CBOR null
        val ing = active.assertions.firstNotNullOf { (it.content as? C2paAssertionContent.Ingredient)?.ingredient }
        assertEquals("self#jumbf=/c2pa/urn:uuid:54281c07-ad34-430e-bea5-112a18facf0b", ing.manifestRef)
        assertEquals("ES256", active.signature!!.algorithm)
        assertEquals("CN=Bob,O=Media Publisher Company", s.manifests[0].signature!!.certificateSubject)
        assertEquals("CN=Media Publisher Company Intermediate CA,O=Media Publisher Company", s.manifests[0].signature!!.certificateIssuer)
        assertEquals("c2pa.hash.bmff", s.manifests[0].assertions.last().label)
        assertEquals("c2pa.hash.bmff.v2", active.assertions.last().label)
    }

    @Test
    fun `heif and webp v2 claims`() {
        for (name in listOf("signed.heif", "signed_small.webp")) {
            val m = store(name).active!!
            val claim = m.claim!!
            assertEquals(2, claim.version, name)
            assertEquals("c2pa.claim.v2", claim.label)
            assertEquals("fixture", claim.title)
            assertNull(claim.format)
            assertNull(claim.claimGenerator)
            assertEquals("c2patool 0.27.22", claim.generatorInfo.first())
            assertEquals(2, claim.assertionRefs.size) // created + gathered
            val action = m.assertions.firstNotNullOf { (it.content as? C2paAssertionContent.Actions)?.actions }.single()
            assertEquals("c2pa.created", action.action)
            assertEquals("http://cv.iptc.org/newscodes/digitalsourcetype/digitalCapture", action.digitalSourceType)
            assertEquals("ES256", m.signature!!.algorithm)
            assertTrue(m.signature!!.certificateSubject!!.startsWith("CN=C2PA Signer"))
        }
    }

    @Test
    fun `no manifest and alg names`() {
        val f = fixture("no_manifest.jpg")
        assertNull(readC2paManifestStore(f, parseFile(f)))
        assertEquals("EdDSA", coseAlgorithmName(-8))
        assertEquals("alg -999", coseAlgorithmName(-999))
    }

    @Test
    fun `corrupt claim CBOR becomes claimError`() {
        val f = fixture("C.jpg")
        val loc = com.multiviewer.parser.ByteReader.open(f).use { locateC2paStore(it, parseFile(f)) }!!
        val bytes = loc.bytes.copyOf()
        // the claim's cbor payload starts with a8 (map of 8); turn it into a reserved initial byte
        val claimCbor = parseJumbfBoxes(bytes).single().children.single().child("c2pa.claim")!!.firstContent("cbor")!!
        bytes[claimCbor.payloadStart] = 0xFC.toByte()
        val s = buildC2paManifestStore(C2paStoreLocation(loc.container, bytes, loc.offsetMap, loc.anchorOffsets, loc.warnings))
        val m = s.manifests.single()
        assertNull(m.claim)
        assertTrue(m.claimError!!.contains("reserved"))
        assertEquals(4, m.assertions.size) // the rest still parses
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.c2pa.C2paManifestTest'`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement `C2paManifest.kt`**

```kotlin
package com.multiviewer.parser.c2pa

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import java.io.ByteArrayInputStream
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

data class C2paAction(val action: String, val softwareAgent: String?, val whenTime: String?, val digitalSourceType: String?, val parameters: String?)

data class C2paIngredient(
    val title: String?, val format: String?, val relationship: String?, val instanceId: String?,
    val manifestRef: String?, val thumbnailRef: String?,
)

sealed interface C2paAssertionContent {
    data class Actions(val actions: List<C2paAction>) : C2paAssertionContent
    data class Ingredient(val ingredient: C2paIngredient) : C2paAssertionContent
    class Thumbnail(val mimeType: String?, val bytes: ByteArray) : C2paAssertionContent
    data class Cbor(val value: CborValue) : C2paAssertionContent
    data class Json(val text: String) : C2paAssertionContent
    data class Failed(val reason: String) : C2paAssertionContent
    data object Empty : C2paAssertionContent
}

data class C2paAssertion(val label: String, val contentType: String, val content: C2paAssertionContent, val fileOffset: Long, val size: Int)

data class C2paClaim(
    val label: String, val version: Int, val title: String?, val format: String?, val instanceId: String?,
    val claimGenerator: String?, val generatorInfo: List<String>, val signatureRef: String?,
    val assertionRefs: List<String>, val alg: String?, val raw: CborValue,
)

data class C2paSignatureInfo(val algorithm: String?, val certificateSubject: String?, val certificateIssuer: String?, val chainLength: Int, val error: String?)

class C2paManifest(
    val label: String,
    val claim: C2paClaim?,
    val claimError: String?,
    val claimBytes: ByteArray?,
    val assertions: List<C2paAssertion>,
    val signature: C2paSignatureInfo?,
    val signatureBytes: ByteArray?,
    val warnings: List<String>,
)

class C2paManifestStore(val location: C2paStoreLocation, val manifests: List<C2paManifest>, val warnings: List<String>) {
    val active: C2paManifest? get() = manifests.lastOrNull()
}

fun coseAlgorithmName(alg: Long): String = when (alg) {
    -7L -> "ES256"; -35L -> "ES384"; -36L -> "ES512"
    -37L -> "PS256"; -38L -> "PS384"; -39L -> "PS512"
    -8L -> "EdDSA"
    else -> "alg $alg"
}

fun readC2paManifestStore(file: File, root: BoxNode): C2paManifestStore? = try {
    ByteReader.open(file).use { reader -> locateC2paStore(reader, root) }?.let { buildC2paManifestStore(it) }
} catch (e: Exception) {
    null
}

fun buildC2paManifestStore(location: C2paStoreLocation): C2paManifestStore {
    val bytes = location.bytes
    val warnings = location.warnings.toMutableList()
    val top = parseJumbfBoxes(bytes).firstOrNull()
    if (top == null || top.label != "c2pa") return C2paManifestStore(location, emptyList(), warnings + "No c2pa manifest store superbox")
    warnings += top.warnings
    val manifests = top.children
        .filter { it.description?.contentTypeName in setOf("c2ma", "c2um") }
        .map { buildManifest(it, bytes, location.offsetMap) }
    return C2paManifestStore(location, manifests, warnings)
}

private fun buildManifest(box: JumbfBox, bytes: ByteArray, map: C2paOffsetMap): C2paManifest {
    val warnings = box.warnings.toMutableList()
    fun part(type: String) = box.children.firstOrNull { it.description?.contentTypeName == type }

    var claim: C2paClaim? = null
    var claimError: String? = null
    var claimBytes: ByteArray? = null
    val claimBox = part("c2cl")
    val claimCbor = claimBox?.firstContent("cbor")
    if (claimCbor == null) {
        claimError = "No claim CBOR box"
    } else {
        claimBytes = bytes.copyOfRange(claimCbor.payloadStart, claimCbor.payloadEnd)
        try {
            claim = parseClaim(claimBox.label ?: "c2pa.claim", decodeCbor(bytes, claimCbor.payloadStart, claimCbor.payloadEnd))
        } catch (e: CborException) {
            claimError = e.message
        }
    }

    val assertions = part("c2as")?.children?.map { parseAssertion(it, bytes, map) } ?: emptyList()

    var signature: C2paSignatureInfo? = null
    var signatureBytes: ByteArray? = null
    val sigCbor = part("c2cs")?.firstContent("cbor")
    if (sigCbor != null) {
        signatureBytes = bytes.copyOfRange(sigCbor.payloadStart, sigCbor.payloadEnd)
        signature = parseSignature(signatureBytes)
    } else {
        warnings += "No signature box"
    }
    return C2paManifest(box.label ?: "", claim, claimError, claimBytes, assertions, signature, signatureBytes, warnings)
}

private fun nameVersion(v: CborValue): String? = v.asMap()?.let { m ->
    val name = m["name"]?.asText() ?: return@let null
    m["version"]?.asText()?.let { "$name $it" } ?: name
}

private fun parseClaim(label: String, v: CborValue): C2paClaim {
    val m = v.asMap() ?: throw CborException("claim is not a CBOR map", 0)
    val info = when (val gi = m["claim_generator_info"]) {
        is CborArray -> gi.items.mapNotNull(::nameVersion)
        is CborMap -> listOfNotNull(nameVersion(gi))
        else -> emptyList()
    }
    fun urls(key: String) = m[key]?.asArray()?.mapNotNull { it.asMap()?.get("url")?.asText() } ?: emptyList()
    val refs = if (m["assertions"] != null) urls("assertions") else urls("created_assertions") + urls("gathered_assertions")
    return C2paClaim(
        label = label,
        version = if (label.endsWith(".v2")) 2 else 1,
        title = m["dc:title"]?.asText(),
        format = m["dc:format"]?.asText(),
        instanceId = m["instanceID"]?.asText(),
        claimGenerator = m["claim_generator"]?.asText(),
        generatorInfo = info,
        signatureRef = m["signature"]?.asText(),
        assertionRefs = refs,
        alg = m["alg"]?.asText(),
        raw = v,
    )
}

private fun parseAssertion(box: JumbfBox, bytes: ByteArray, map: C2paOffsetMap): C2paAssertion {
    val label = box.label ?: ""
    val type = box.description?.contentTypeName ?: "?"
    val content: C2paAssertionContent = try {
        when {
            type == JUMBF_EMBEDDED_FILE_TYPE -> {
                val fd = box.firstContent("bfdb")
                val mime = fd?.takeIf { it.payloadEnd - it.payloadStart > 1 }?.let {
                    var nul = it.payloadStart + 1
                    while (nul < it.payloadEnd && bytes[nul] != 0.toByte()) nul++
                    String(bytes, it.payloadStart + 1, nul - it.payloadStart - 1, Charsets.UTF_8)
                }
                val data = box.firstContent("bidb")
                if (data == null) C2paAssertionContent.Empty
                else C2paAssertionContent.Thumbnail(mime, bytes.copyOfRange(data.payloadStart, data.payloadEnd))
            }
            box.firstContent("cbor") != null -> {
                val c = box.firstContent("cbor")!!
                val v = decodeCbor(bytes, c.payloadStart, c.payloadEnd)
                when {
                    label.startsWith("c2pa.actions") -> C2paAssertionContent.Actions(parseActions(v))
                    label.startsWith("c2pa.ingredient") -> C2paAssertionContent.Ingredient(parseIngredient(v))
                    else -> C2paAssertionContent.Cbor(v)
                }
            }
            box.firstContent("json") != null -> box.firstContent("json")!!.let {
                C2paAssertionContent.Json(String(bytes, it.payloadStart, it.payloadEnd - it.payloadStart, Charsets.UTF_8))
            }
            else -> C2paAssertionContent.Empty
        }
    } catch (e: CborException) {
        C2paAssertionContent.Failed(e.message ?: "CBOR decode failed")
    }
    return C2paAssertion(label, type, content, map.toFile(box.offset), box.size)
}

private fun parseActions(v: CborValue): List<C2paAction> =
    v.asMap()?.get("actions")?.asArray()?.mapNotNull { a ->
        val m = a.asMap() ?: return@mapNotNull null
        C2paAction(
            action = m["action"]?.asText() ?: "?",
            softwareAgent = m["softwareAgent"]?.let { it.asText() ?: it.asMap()?.get("name")?.asText() },
            whenTime = m["when"]?.asText(),
            digitalSourceType = m["digitalSourceType"]?.asText(),
            parameters = m["parameters"]?.toDiagnostic(),
        )
    } ?: emptyList()

private fun parseIngredient(v: CborValue): C2paIngredient {
    val m = v.asMap() ?: return C2paIngredient(null, null, null, null, null, null)
    return C2paIngredient(
        title = m["dc:title"]?.asText(),
        format = m["dc:format"]?.asText(),
        relationship = m["relationship"]?.asText(),
        instanceId = (m["instanceID"] ?: m["instanceId"])?.asText(),
        manifestRef = (m["c2pa_manifest"] ?: m["active_manifest"])?.asMap()?.get("url")?.asText(),
        thumbnailRef = m["thumbnail"]?.asMap()?.get("url")?.asText(),
    )
}

private fun parseSignature(cose: ByteArray): C2paSignatureInfo = try {
    val tagged = decodeCbor(cose)
    val body = ((tagged as? CborTag)?.value ?: tagged).asArray()
        ?: throw CborException("COSE_Sign1 is not an array", 0)
    if (body.size != 4) throw CborException("COSE_Sign1 has ${body.size} elements, expected 4", 0)
    val protectedBytes = body[0].asBytes() ?: ByteArray(0)
    val protectedMap = if (protectedBytes.isEmpty()) CborMap(emptyList()) else decodeCbor(protectedBytes).asMap() ?: CborMap(emptyList())
    val unprotected = body[1].asMap() ?: CborMap(emptyList())
    val alg = protectedMap[1L]?.asLong()?.let(::coseAlgorithmName)
    val chain = (protectedMap[33L] ?: unprotected[33L] ?: unprotected["x5chain"])?.let { c ->
        c.asBytes()?.let { listOf(it) } ?: c.asArray()?.mapNotNull { it.asBytes() }
    } ?: emptyList()
    val leaf = chain.firstOrNull()?.let {
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(it)) as X509Certificate
    }
    C2paSignatureInfo(alg, leaf?.subjectX500Principal?.name, leaf?.issuerX500Principal?.name, chain.size, null)
} catch (e: Exception) {
    C2paSignatureInfo(null, null, null, 0, e.message ?: e.toString())
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.c2pa.C2paManifestTest'`
Expected: PASS (6 tests). If an asserted value differs, stop and report the actual value — do not change expectations.

- [ ] **Step 5: Commit** — message `feat: build C2PA manifest model with claim, assertions and signer` + trailer.

---

### Task 7: C2PA window and menu

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/C2paWindow.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/I18n.kt` (after `menuHdrSeiAnalysis`)
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt` (state + Analyze menu item + window; follow exactly how `hdrMetadataTab` is pinned to its tab, including the close-tab clearing)

**Interfaces:**
- Consumes: `readC2paManifestStore`, `hasC2paManifestStore`, model types from Task 6, `TabState`, `AppLanguage`, `AppColors`, `I18n`.
- Produces: `@Composable fun C2paWindow(tab: TabState, language: AppLanguage, onCloseRequest: () -> Unit)`; `fun I18n.menuC2pa(lang: AppLanguage): String`.

- [ ] **Step 1: I18n**

```kotlin
    fun menuC2pa(lang: AppLanguage) = if (lang == AppLanguage.KO) "C2PA 콘텐츠 자격 증명" else "C2PA Content Credentials"
```

- [ ] **Step 2: `C2paWindow.kt`**

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.parser.c2pa.C2paAssertionContent
import com.multiviewer.parser.c2pa.C2paManifest
import com.multiviewer.parser.c2pa.C2paManifestStore
import com.multiviewer.parser.c2pa.readC2paManifestStore
import com.multiviewer.parser.c2pa.toDiagnostic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun C2paWindow(tab: TabState, language: AppLanguage, onCloseRequest: () -> Unit) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english

    Window(
        onCloseRequest = onCloseRequest,
        state = rememberWindowState(size = DpSize(1100.dp, 800.dp), position = WindowPosition(Alignment.Center)),
        title = "${I18n.menuC2pa(language)} - ${tab.file.name}",
    ) {
        var store by remember(tab.file) { mutableStateOf<C2paManifestStore?>(null) }
        var loading by remember(tab.file) { mutableStateOf(true) }
        var selected by remember(tab.file) { mutableStateOf(0) }
        var tabIndex by remember(tab.file) { mutableStateOf(0) }

        LaunchedEffect(tab.file) {
            val root = tab.root
            store = if (root == null || tab.isLoading) null else withContext(Dispatchers.IO) { readC2paManifestStore(tab.file, root) }
            selected = (store?.manifests?.size ?: 1) - 1
            loading = false
        }

        Column(modifier = Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val s = store
            when {
                loading -> Text(label("읽는 중…", "Reading…"), color = AppColors.TextPrimary)
                s == null || s.manifests.isEmpty() -> Text(label("C2PA 매니페스트가 없습니다", "No C2PA manifest found"), color = AppColors.TextSecondary)
                else -> {
                    Text(
                        "${s.location.container} · ${label("매니페스트", "manifests")} ${s.manifests.size} · ${label("서명 검증 전", "signatures not verified")}",
                        color = AppColors.TextPrimary, fontSize = 13.sp,
                    )
                    s.warnings.forEach { Text("⚠ $it", color = AppColors.NeonYellow, fontSize = 12.sp) }
                    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LazyColumn(modifier = Modifier.width(300.dp).fillMaxHeight()) {
                            items(s.manifests.size) { i ->
                                val m = s.manifests[i]
                                val isActive = i == s.manifests.lastIndex
                                Text(
                                    (if (isActive) "★ " else "") + (m.claim?.title ?: m.label),
                                    color = if (i == selected) AppColors.NeonBlue else AppColors.TextPrimary,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp,
                                    modifier = Modifier.fillMaxWidth()
                                        .background(if (i == selected) AppColors.Selection else Color.Transparent)
                                        .clickable { selected = i }
                                        .padding(6.dp),
                                )
                                Text(m.label, color = AppColors.TextMuted, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
                            }
                        }
                        val m = s.manifests.getOrNull(selected) ?: s.manifests.last()
                        Column(modifier = Modifier.fillMaxSize()) {
                            val tabs = listOf(label("개요", "Overview"), "Actions", "Ingredients", "Assertions", label("썸네일", "Thumbnail"))
                            TabRow(selectedTabIndex = tabIndex) {
                                tabs.forEachIndexed { i, t -> Tab(selected = tabIndex == i, onClick = { tabIndex = i }, text = { Text(t) }) }
                            }
                            SelectionContainer {
                                when (tabIndex) {
                                    0 -> OverviewTab(m, ::label)
                                    1 -> ActionsTab(m, ::label)
                                    2 -> IngredientsTab(m, s, ::label)
                                    3 -> AssertionsTab(m)
                                    else -> ThumbnailTab(m, ::label)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Row2(name: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(name, color = AppColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(200.dp))
        Text(value ?: "-", color = AppColors.TextPrimary, fontSize = 12.sp)
    }
}

@Composable
private fun OverviewTab(m: C2paManifest, label: (String, String) -> String) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        item {
            Row2("label", m.label)
            val c = m.claim
            if (c == null) {
                Text(label("claim 디코딩 실패: ", "Claim decode failed: ") + (m.claimError ?: "?"), color = AppColors.NeonRed, fontSize = 12.sp)
            } else {
                Row2("claim", "${c.label} (v${c.version})")
                Row2("dc:title", c.title)
                Row2("dc:format", c.format)
                Row2("instanceID", c.instanceId)
                Row2("claim_generator", c.claimGenerator)
                Row2("claim_generator_info", c.generatorInfo.joinToString(", ").ifEmpty { null })
                Row2("alg", c.alg)
                Row2(label("assertion 참조 수", "assertion refs"), c.assertionRefs.size.toString())
            }
            val sig = m.signature
            Row2(label("서명 알고리즘", "signature alg"), sig?.algorithm)
            Row2(label("서명 인증서 (검증 전)", "signer cert (not verified)"), sig?.certificateSubject)
            Row2(label("발급자 (검증 전)", "issuer (not verified)"), sig?.certificateIssuer)
            Row2(label("인증서 체인 길이", "chain length"), sig?.chainLength?.toString())
            sig?.error?.let { Text(label("서명 정보 오류: ", "Signature info error: ") + it, color = AppColors.NeonRed, fontSize = 12.sp) }
            m.warnings.forEach { Text("⚠ $it", color = AppColors.NeonYellow, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ActionsTab(m: C2paManifest, label: (String, String) -> String) {
    val actions = m.assertions.flatMap { (it.content as? C2paAssertionContent.Actions)?.actions ?: emptyList() }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        if (actions.isEmpty()) item { Text(label("actions 없음", "No actions"), color = AppColors.TextSecondary, fontSize = 12.sp) }
        items(actions) { a ->
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Text(a.action, color = AppColors.NeonBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row2("softwareAgent", a.softwareAgent)
                Row2("when", a.whenTime)
                Row2("digitalSourceType", a.digitalSourceType)
                Row2("parameters", a.parameters)
            }
        }
    }
}

@Composable
private fun IngredientsTab(m: C2paManifest, store: C2paManifestStore, label: (String, String) -> String) {
    val ingredients = m.assertions.mapNotNull { (it.content as? C2paAssertionContent.Ingredient)?.ingredient }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        if (ingredients.isEmpty()) item { Text(label("ingredient 없음", "No ingredients"), color = AppColors.TextSecondary, fontSize = 12.sp) }
        items(ingredients) { ing ->
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Text(ing.title ?: "?", color = AppColors.NeonBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row2("dc:format", ing.format)
                Row2("relationship", ing.relationship)
                Row2("instanceID", ing.instanceId)
                val inStore = ing.manifestRef?.let { ref -> store.manifests.any { ref.endsWith(it.label) } }
                Row2(label("참조 매니페스트", "manifest ref"), ing.manifestRef?.let { it + if (inStore == true) label("  (이 파일에 있음)", "  (in this store)") else "" })
                ing.thumbnailRef?.let { ref ->
                    val label2 = ref.substringAfterLast('/')
                    val thumb = m.assertions.firstOrNull { it.label == label2 }?.content as? C2paAssertionContent.Thumbnail
                    thumb?.let { ThumbnailImage(it.bytes) }
                }
            }
        }
    }
}

@Composable
private fun AssertionsTab(m: C2paManifest) {
    var open by remember(m) { mutableStateOf<Int?>(null) }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        items(m.assertions.size) { i ->
            val a = m.assertions[i]
            val kind = when (val c = a.content) {
                is C2paAssertionContent.Actions -> "actions (${c.actions.size})"
                is C2paAssertionContent.Ingredient -> "ingredient"
                is C2paAssertionContent.Thumbnail -> "thumbnail ${c.mimeType ?: ""} ${c.bytes.size} B"
                is C2paAssertionContent.Cbor -> "cbor"
                is C2paAssertionContent.Json -> "json"
                is C2paAssertionContent.Failed -> "FAILED: ${c.reason}"
                C2paAssertionContent.Empty -> "empty"
            }
            Text(
                "${a.label}  [${a.contentType}]  $kind  @0x${a.fileOffset.toString(16).uppercase()}",
                color = if (a.content is C2paAssertionContent.Failed) AppColors.NeonRed else AppColors.TextPrimary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clickable { open = if (open == i) null else i }.padding(vertical = 4.dp),
            )
            if (open == i) {
                val text = when (val c = a.content) {
                    is C2paAssertionContent.Cbor -> c.value.toDiagnostic()
                    is C2paAssertionContent.Json -> c.text
                    is C2paAssertionContent.Actions -> c.actions.joinToString("\n") { it.toString() }
                    is C2paAssertionContent.Ingredient -> c.ingredient.toString()
                    is C2paAssertionContent.Failed -> c.reason
                    is C2paAssertionContent.Thumbnail -> "${c.bytes.size} bytes"
                    C2paAssertionContent.Empty -> ""
                }
                Text(text, color = AppColors.TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
            }
        }
    }
}

@Composable
private fun ThumbnailTab(m: C2paManifest, label: (String, String) -> String) {
    val thumb = m.assertions.firstOrNull { it.label.startsWith("c2pa.thumbnail.claim") }?.content as? C2paAssertionContent.Thumbnail
    Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        if (thumb == null) Text(label("claim 썸네일 없음", "No claim thumbnail"), color = AppColors.TextSecondary, fontSize = 12.sp)
        else ThumbnailImage(thumb.bytes)
    }
}

@Composable
private fun ThumbnailImage(bytes: ByteArray) {
    val bitmap = remember(bytes) { runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() }
    if (bitmap == null) Text("(thumbnail decode failed)", color = AppColors.NeonRed, fontSize = 12.sp)
    else Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.heightIn(max = 360.dp))
}
```

- [ ] **Step 3: Wire `Main.kt`**

Mirror the existing `hdrMetadataTab` pattern exactly:
- `var c2paTab by remember { mutableStateOf<TabState?>(null) }` next to `hdrMetadataTab`.
- In the Analyze menu after the HDR/SEI item: `item(I18n.menuC2pa(language), enabled = hasC2pa, onClick = { c2paTab = currentTab })`, where `hasC2pa` is computed like the existing `remember(selectedTabRoot) { … }` value for the HDR menu, using `com.multiviewer.parser.c2pa.hasC2paManifestStore(root)` and requiring `!currentTab.isLoading`. Unlike the HDR item it is NOT gated on `MediaType.VIDEO` (C2PA applies to images too).
- Render `c2paTab?.let { t -> C2paWindow(tab = t, language = language, onCloseRequest = { c2paTab = null }) }` and clear `c2paTab` when its tab is closed, the same way `hdrMetadataTab` is cleared.

- [ ] **Step 4: Compile and full suite**

Run: `./gradlew :app:compileKotlin` then `./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no new failures, no new warnings in touched files. Confirm only `androidx.compose.material3.Text` is imported in `C2paWindow.kt`.

- [ ] **Step 5: Commit** — message `feat: add C2PA content credentials window` + trailer.

- [ ] **Step 6: Manual check (controller / user)**

`./gradlew :app:run`; open each fixture in `app/src/test/resources/c2pa/`:
- Structure tree: APP11 / `uuid` / `C2PA` nodes expand into `jumb`/`jumd`/`cbor`/`json`/`bfdb`/`bidb` nodes; selecting a node highlights its bytes in the hex viewer; `CA.jpg`'s store node shows the boundary warning.
- Analyze → C2PA 콘텐츠 자격 증명: enabled for the five signed fixtures, disabled for `no_manifest.jpg`.
- `CA.jpg`: Overview "CA.jpg", PS256, subject `CN=C2PA Signer,…` marked not verified; Actions shows `c2pa.opened`, `c2pa.color_adjustments`; Ingredients shows `A.jpg` with its thumbnail; Thumbnail tab shows the claim thumbnail.
- `video1.mp4`: two manifests, the second marked ★; its ingredient's manifest ref says "(in this store)".

---

### Task 8: Final verification

- [ ] Run `./gradlew :app:test` — 0 failures apart from the known flaky cache test (must pass when rerun alone).
- [ ] `grep -rn "ProcessBuilder\|ffmpeg" app/src/main/kotlin/com/multiviewer/parser/c2pa app/src/main/kotlin/com/multiviewer/ui/C2paWindow.kt` → no matches.
- [ ] Open a large non-C2PA JPEG and MP4 (any from `app/src/test/resources/hdr/`) and confirm the tree is unchanged and opening time is not noticeably slower.
