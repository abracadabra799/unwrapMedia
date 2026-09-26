# Motion Photo Integrity Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A new "모션포토 정합성 검사" menu that validates a motion photo's
embedded-video reference across all three formats this app recognizes
(Samsung SEF, Google XMP MotionPhoto/MicroVideo, HEIC `mpvd`) in
one unified report.

**Architecture:** New `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt`
holds all check logic: delegates unchanged to the existing
`SefIntegrityAnalyzer` for SEF, adds new pure-logic checks for Google XMP
and Apple `mpvd`, and a format-independent `ffprobe` decode check. Seven
symbols in `MotionPhotoExtractor.kt` (`parser` package) are promoted from
`private` to `internal` so the new analyzer can reuse them instead of
reimplementing XMP parsing. A new `MotionPhotoIntegrityWindow.kt` mirrors
`SefIntegrityWindow.kt`'s report-rendering pattern, wired to a new menu item
under the existing "모션포토" menu in `Main.kt`.

**Tech Stack:** Kotlin, Compose Desktop, JUnit5 (`kotlin("test-junit5")`), ffmpeg/ffprobe (already bundled).

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-09-26-motion-photo-integrity-check-design.md`.
- No changes to `SefIntegrityAnalyzer.kt` — reused exactly as-is via its
  existing public `analyze(reader, offset, headerSize, size, fileLength):
  SefIntegrityReport` signature.
- No changes to the existing "SEF 무결성 검사" menu item or
  `SefIntegrityWindow.kt` — the new menu item is additive.
- Reuse `SefCheckResult(severity, label, detail)` and `SefIntegritySeverity`
  (`PASS`/`INFO`/`WARNING`/`CRITICAL`/`SKIPPED`) for the Google and Apple
  sections too — do not define a second, near-identical result type.
- `MotionPhotoIntegrityAnalyzer.analyze(file, root)` opens exactly ONE
  `ByteReader` for the entire analysis (SEF delegation, Google XMP checks,
  and the `findEmbeddedVideo` call for the decode check all share it) — this
  project has already had to fix redundant-`ByteReader.open` bugs twice;
  never open a second reader per section.
- No color-coded severity thresholds beyond the existing PASS/INFO/WARNING/
  CRITICAL/SKIPPED vocabulary already established by `SefIntegrityWindow`.

---

## File Structure

| File | Change |
|---|---|
| `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoExtractor.kt` | Promote 7 symbols from `private` to `internal` (Task 1). |
| `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt` | New — `MotionPhotoFormat` enum, `MotionPhotoIntegrityReport` data class, `analyzeGoogleXmpSection` (Task 1); `analyzeAppleMpvdSection`, `analyzeDecodability`, top-level `MotionPhotoIntegrityAnalyzer.analyze(file, root)` orchestrator (Task 2). |
| `app/src/test/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzerTest.kt` | New — unit tests for the pure-logic Google XMP checks (Task 1) and Apple `mpvd` checks (Task 2). |
| `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityWindow.kt` | New — report window (Task 3). |
| `app/src/main/kotlin/com/multiviewer/Main.kt` | New menu item + window-open wiring under the existing "모션포토" menu (Task 3). |
| `app/src/main/kotlin/com/multiviewer/ui/I18n.kt` | New `menuMotionPhotoIntegrityCheck` label (Task 3). |

---

### Task 1: Promote XMP helpers to `internal` + Google XMP integrity checks

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoExtractor.kt`
- Create: `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzerTest.kt` (new file)

**Interfaces:**
- Consumes (after promotion, all now `internal` in `com.multiviewer.parser`):
  `DirectoryVideoInfo(length: Long, mimeType: String?)`,
  `MP4_START_SEARCH_WINDOW: Long`,
  `correctMp4StartOffset(reader: ByteReader, approxStart: Long): Long`,
  `parseXmpDocument(xmpText: String): Document`,
  `findMotionPhotoInDirectory(document: Document): DirectoryVideoInfo?`,
  `findMicroVideoOffset(document: Document): Long?`,
  `findPropertyValue(element: Element, localName: String): String?`.
  Also consumes existing public `com.multiviewer.parser.findFirst`,
  `BoxNode`, `BoxField`, `ByteReader`, and existing public
  `com.multiviewer.parser.SefCheckResult`, `SefIntegritySeverity`.
- Produces: `enum class MotionPhotoFormat { SAMSUNG_SEF, GOOGLE_XMP, APPLE_MPVD }`,
  `data class MotionPhotoIntegrityReport(detectedFormats: List<MotionPhotoFormat>, sefSection: SefIntegrityReport?, googleXmpChecks: List<SefCheckResult>, appleMpvdChecks: List<SefCheckResult>, decodeChecks: List<SefCheckResult>, overallSeverity: SefIntegritySeverity)`,
  `internal fun analyzeGoogleXmpSection(root: BoxNode, reader: ByteReader): List<SefCheckResult>` — consumed by Task 2's orchestrator.

- [ ] **Step 1: Promote the 7 symbols in `MotionPhotoExtractor.kt`**

In `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoExtractor.kt`, change
these 7 declarations from `private` to `internal` (no other changes to their
bodies):

Find:
```kotlin
private data class DirectoryVideoInfo(val length: Long, val mimeType: String?)
```
Replace with:
```kotlin
internal data class DirectoryVideoInfo(val length: Long, val mimeType: String?)
```

Find:
```kotlin
private const val MP4_START_SEARCH_WINDOW = 1024L
```
Replace with:
```kotlin
internal const val MP4_START_SEARCH_WINDOW = 1024L
```

Find:
```kotlin
private fun correctMp4StartOffset(reader: ByteReader, approxStart: Long): Long {
```
Replace with:
```kotlin
internal fun correctMp4StartOffset(reader: ByteReader, approxStart: Long): Long {
```

Find:
```kotlin
private fun parseXmpDocument(xmpText: String): Document {
```
Replace with:
```kotlin
internal fun parseXmpDocument(xmpText: String): Document {
```

Find:
```kotlin
private fun findMotionPhotoInDirectory(document: Document): DirectoryVideoInfo? {
```
Replace with:
```kotlin
internal fun findMotionPhotoInDirectory(document: Document): DirectoryVideoInfo? {
```

Find:
```kotlin
private fun findMicroVideoOffset(document: Document): Long? {
```
Replace with:
```kotlin
internal fun findMicroVideoOffset(document: Document): Long? {
```

Find:
```kotlin
private fun findPropertyValue(element: Element, localName: String): String? {
```
Replace with:
```kotlin
internal fun findPropertyValue(element: Element, localName: String): String? {
```

Confirmed by grep during design: none of these 7 names collide with any
identically-named symbol elsewhere in the codebase, so this promotion is
safe (unlike the SEF Integrity Check project's `readUInt16LE`/`readUInt32LE`,
which DID collide and had to stay `private`-duplicated per file instead).

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzerTest.kt`:

```kotlin
package com.multiviewer.ui

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.SefIntegritySeverity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Builds a temp file of `totalSize` zero bytes with the literal ASCII "ftyp" placed so that
// reading 4 bytes at (ftypBoxStart + 4) returns "ftyp" -- matching correctMp4StartOffset's own
// read pattern (box size field, then 4-byte box type).
private fun tempFileWithFtypAt(ftypBoxStart: Long, totalSize: Long): File {
    val bytes = ByteArray(totalSize.toInt())
    "ftyp".toByteArray(Charsets.US_ASCII).copyInto(bytes, (ftypBoxStart + 4).toInt())
    val tmp = File.createTempFile("motion-photo-integrity-test", ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(bytes)
    return tmp
}

private fun tempFileWithNoFtyp(totalSize: Long): File {
    val tmp = File.createTempFile("motion-photo-integrity-test-noftyp", ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(ByteArray(totalSize.toInt()))
    return tmp
}

private fun googleDirectoryXmp(declaredLength: Long): String = """
    <x:xmpmeta xmlns:x="adobe:ns:meta/">
      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description
            xmlns:Container="http://ns.google.com/photos/1.0/container/"
            xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
            xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
            GCamera:MotionPhoto="1">
          <Container:Directory>
            <rdf:Seq>
              <rdf:li rdf:parseType="Resource">
                <Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/>
              </rdf:li>
              <rdf:li rdf:parseType="Resource">
                <Container:Item Item:Semantic="MotionPhoto" Item:Mime="video/mp4" Item:Length="$declaredLength"/>
              </rdf:li>
            </rdf:Seq>
          </Container:Directory>
        </rdf:Description>
      </rdf:RDF>
    </x:xmpmeta>
""".trimIndent()

private fun googleMicroVideoXmp(declaredOffset: Long): String = """
    <x:xmpmeta xmlns:x="adobe:ns:meta/">
      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description
            xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
            GCamera:MicroVideo="1"
            GCamera:MicroVideoVersion="1"
            GCamera:MicroVideoOffset="$declaredOffset"/>
      </rdf:RDF>
    </x:xmpmeta>
""".trimIndent()

private fun rootWithXmp(xmpText: String, fileSize: Long): BoxNode = BoxNode(
    type = "root", offset = 0, headerSize = 0, size = fileSize,
    children = listOf(
        BoxNode(
            type = "APP1", offset = 2, headerSize = 4, size = xmpText.length.toLong(),
            fields = listOf(BoxField("xmp", xmpText, 2, xmpText.length.toLong())),
        ),
    ),
)

class MotionPhotoIntegrityAnalyzerTest {
    @Test
    fun `analyzeGoogleXmpSection returns empty when there is no XMP at all`() {
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 1000)
        val file = tempFileWithNoFtyp(1000)
        ByteReader.open(file).use { reader ->
            assertEquals(emptyList(), analyzeGoogleXmpSection(root, reader))
        }
    }

    @Test
    fun `analyzeGoogleXmpSection returns empty when XMP exists but has no motion-photo markers`() {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description/></rdf:RDF></x:xmpmeta>"""
        val file = tempFileWithNoFtyp(1000)
        val root = rootWithXmp(xmp, 1000)
        ByteReader.open(file).use { reader ->
            assertEquals(emptyList(), analyzeGoogleXmpSection(root, reader))
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports PASS when the declared Directory Length exactly matches the real ftyp position`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val approxStart = fileSize - declaredLength
        val file = tempFileWithFtypAt(approxStart, fileSize)
        val root = rootWithXmp(googleDirectoryXmp(declaredLength), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.INFO && it.detail.contains("Directory") })
            assertTrue(checks.any { it.severity == SefIntegritySeverity.PASS })
            assertTrue(checks.none { it.severity == SefIntegritySeverity.WARNING || it.severity == SefIntegritySeverity.CRITICAL })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports WARNING when the real ftyp is found only after correction`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val approxStart = fileSize - declaredLength // 800
        val realStart = approxStart + 5 // off by 5 bytes, still within the +-1024 search window
        val file = tempFileWithFtypAt(realStart, fileSize)
        val root = rootWithXmp(googleDirectoryXmp(declaredLength), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.WARNING && it.detail.contains("5") })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports CRITICAL when no ftyp is found anywhere in the search window`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val file = tempFileWithNoFtyp(fileSize)
        val root = rootWithXmp(googleDirectoryXmp(declaredLength), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.CRITICAL })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports CRITICAL when the declared length exceeds the file size`() {
        val fileSize = 1000L
        val file = tempFileWithNoFtyp(fileSize)
        val root = rootWithXmp(googleDirectoryXmp(declaredLength = 5000L), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.CRITICAL })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection detects the legacy MicroVideo schema and still verifies the offset`() {
        val fileSize = 1000L
        val declaredOffset = 200L
        val approxStart = fileSize - declaredOffset
        val file = tempFileWithFtypAt(approxStart, fileSize)
        val root = rootWithXmp(googleMicroVideoXmp(declaredOffset), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.INFO && it.detail.contains("MicroVideo") })
            assertTrue(checks.any { it.severity == SefIntegritySeverity.PASS })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports WARNING when the XMP text has motion-photo markers but fails to parse`() {
        val xmp = "MotionPhoto <this is not <<valid xml"
        val file = tempFileWithNoFtyp(1000)
        val root = rootWithXmp(xmp, 1000)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader)
            assertEquals(1, checks.size)
            assertEquals(SefIntegritySeverity.WARNING, checks.single().severity)
        }
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.ui.MotionPhotoIntegrityAnalyzerTest"`
Expected: FAIL to compile (`analyzeGoogleXmpSection`, `MotionPhotoFormat`, etc. don't exist yet).

- [ ] **Step 4: Implement**

Create `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt`:

```kotlin
package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefIntegrityReport
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.correctMp4StartOffset
import com.multiviewer.parser.findFirst
import com.multiviewer.parser.findMicroVideoOffset
import com.multiviewer.parser.findMotionPhotoInDirectory
import com.multiviewer.parser.parseXmpDocument

enum class MotionPhotoFormat { SAMSUNG_SEF, GOOGLE_XMP, APPLE_MPVD }

data class MotionPhotoIntegrityReport(
    val detectedFormats: List<MotionPhotoFormat>,
    val sefSection: SefIntegrityReport?,
    val googleXmpChecks: List<SefCheckResult>,
    val appleMpvdChecks: List<SefCheckResult>,
    val decodeChecks: List<SefCheckResult>,
    val overallSeverity: SefIntegritySeverity,
)

// Verifies a Google-format (Container:Directory "MotionPhoto" semantic, current schema, or the
// legacy GCamera:MicroVideoOffset attribute) motion photo's declared video length/offset against
// the file's real bytes. Unlike MotionPhotoExtractor.kt's findGoogleMotionPhotoVideo (which
// silently self-heals a wrong offset via correctMp4StartOffset so extraction still works), this
// reports the correction as a finding instead of hiding it -- the whole point of an integrity
// check is surfacing exactly this kind of silently-tolerated inaccuracy.
internal fun analyzeGoogleXmpSection(root: BoxNode, reader: ByteReader): List<SefCheckResult> {
    val xmpText = findFirst(root) { it.fields.any { field -> field.name == "xmp" } }
        ?.fields?.find { it.name == "xmp" }?.value
        ?: return emptyList()
    if (!xmpText.contains("MotionPhoto", ignoreCase = true) && !xmpText.contains("MicroVideo", ignoreCase = true)) {
        return emptyList()
    }

    val checks = mutableListOf<SefCheckResult>()
    val document = try {
        parseXmpDocument(xmpText)
    } catch (e: Throwable) {
        // Untrusted input: a crafted/deeply-nested XMP document can throw StackOverflowError or
        // OutOfMemoryError (both Error, not Exception) -- matches findGoogleMotionPhotoVideo's own
        // Throwable catch for this exact risk.
        return listOf(SefCheckResult(SefIntegritySeverity.WARNING, "구글 모션포토 XMP 파싱", "XMP를 파싱할 수 없습니다: ${e.message ?: e.toString()}"))
    }

    val fromDirectory = findMotionPhotoInDirectory(document)
    val microVideoOffset = findMicroVideoOffset(document)
    if (fromDirectory == null && microVideoOffset == null) {
        checks.add(SefCheckResult(SefIntegritySeverity.WARNING, "구글 모션포토 스키마 감지", "MotionPhoto/MicroVideo 마커는 있지만 Length/Offset 값을 찾을 수 없습니다"))
        return checks
    }

    val declaredLength = fromDirectory?.length ?: microVideoOffset!!
    val schemaLabel = if (fromDirectory != null) {
        "Container:Directory (현재 스키마, Item:Semantic=\"MotionPhoto\")"
    } else {
        "GCamera:MicroVideoOffset (레거시 스키마)"
    }
    checks.add(SefCheckResult(SefIntegritySeverity.INFO, "구글 모션포토 스키마", "감지된 스키마: $schemaLabel, 선언된 길이=$declaredLength"))

    if (declaredLength <= 0 || declaredLength > root.size) {
        checks.add(SefCheckResult(SefIntegritySeverity.CRITICAL, "구글 모션포토 길이 값", "선언된 길이($declaredLength)가 파일 크기(${root.size})를 벗어납니다"))
        return checks
    }

    val approxStart = root.size - declaredLength
    val declaredHasFtyp = try {
        reader.readFourCC(approxStart + 4) == "ftyp"
    } catch (e: Exception) {
        false
    }
    if (declaredHasFtyp) {
        checks.add(SefCheckResult(SefIntegritySeverity.PASS, "구글 모션포토 오프셋 일치", "선언된 오프셋($approxStart)에서 실제 ftyp를 확인했습니다"))
    } else {
        val corrected = correctMp4StartOffset(reader, approxStart)
        if (corrected != approxStart) {
            checks.add(SefCheckResult(SefIntegritySeverity.WARNING, "구글 모션포토 오프셋 불일치", "선언된 오프셋($approxStart)이 아니라 ${corrected - approxStart}바이트 떨어진 위치($corrected)에서 실제 ftyp를 찾았습니다 -- XMP 선언값이 정확하지 않습니다"))
        } else {
            checks.add(SefCheckResult(SefIntegritySeverity.CRITICAL, "구글 모션포토 비디오 위치", "선언된 오프셋 근방에서 ftyp를 찾지 못했습니다 -- 비디오 데이터가 없거나 심각하게 손상되었습니다"))
        }
    }

    return checks
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.ui.MotionPhotoIntegrityAnalyzerTest"`
Expected: PASS, all 8 tests.

- [ ] **Step 6: Compile and run the full test suite**

Run: `./gradlew compileKotlin` then `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures (confirms the 7 `internal` promotions didn't collide with anything elsewhere in the module).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MotionPhotoExtractor.kt app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt app/src/test/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzerTest.kt
git commit -m "feat: Google XMP motion-photo offset/length integrity checks

Promotes 7 XMP-parsing helpers in MotionPhotoExtractor.kt from private
to internal (confirmed no naming collisions elsewhere) so the new
analyzer can reuse them instead of reimplementing XMP parsing. New
analyzeGoogleXmpSection detects which schema (current Container:Directory
vs legacy GCamera:MicroVideoOffset) a file actually uses and verifies
the declared video length/offset against the file's real ftyp position
-- surfacing correctMp4StartOffset's silent self-healing as a WARNING/
CRITICAL finding instead of quietly tolerating it.

See docs/superpowers/specs/2026-09-26-motion-photo-integrity-check-design.md"
```

---

### Task 2: Apple `mpvd` checks + format-independent decode check + orchestrator

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzerTest.kt`

**Interfaces:**
- Consumes: `analyzeGoogleXmpSection` (Task 1), `SefIntegrityAnalyzer.analyze(...)` (existing, `com.multiviewer.parser`), `findEmbeddedVideo(root, reader): EmbeddedVideo?` and `extractEmbeddedVideo(source, video, destination)` (existing, `com.multiviewer.parser`), `FfmpegLocator.ffprobePath()` (existing, `com.multiviewer.ui`).
- Produces: `internal fun analyzeAppleMpvdSection(root: BoxNode, fileLength: Long): List<SefCheckResult>`, `internal fun analyzeDecodability(file: File, video: EmbeddedVideo?): List<SefCheckResult>`, `object MotionPhotoIntegrityAnalyzer { fun analyze(file: File, root: BoxNode): MotionPhotoIntegrityReport }` — consumed by Task 3's report window.

- [ ] **Step 1: Write the failing tests**

No new imports needed in `app/src/test/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzerTest.kt` — these tests only use types already imported by Task 1 (`BoxNode`, `BoxField`, `SefIntegritySeverity`, `File`).

Add these test methods inside the existing `class MotionPhotoIntegrityAnalyzerTest { ... }` (after the last Task 1 test method, before the closing brace):

```kotlin
    @Test
    fun `analyzeAppleMpvdSection returns empty when there is no mpvd box`() {
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 1000)
        assertEquals(emptyList(), analyzeAppleMpvdSection(root, 1000))
    }

    @Test
    fun `analyzeAppleMpvdSection reports CRITICAL when the mpvd box overruns the file`() {
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 1000,
            children = listOf(BoxNode(type = "mpvd", offset = 900, headerSize = 8, size = 500)),
        )
        val checks = analyzeAppleMpvdSection(root, fileLength = 1000)
        assertTrue(checks.any { it.severity == SefIntegritySeverity.CRITICAL })
    }

    @Test
    fun `analyzeAppleMpvdSection reports PASS with the major_brand when a valid ftyp child exists`() {
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 1000,
            children = listOf(
                BoxNode(
                    type = "mpvd", offset = 100, headerSize = 8, size = 500,
                    children = listOf(
                        BoxNode(
                            type = "ftyp", offset = 108, headerSize = 8, size = 20,
                            fields = listOf(BoxField("major_brand", "mp42", 108, 4)),
                        ),
                    ),
                ),
            ),
        )
        val checks = analyzeAppleMpvdSection(root, fileLength = 1000)
        assertTrue(checks.any { it.severity == SefIntegritySeverity.PASS && it.detail.contains("mp42") })
    }

    @Test
    fun `analyzeAppleMpvdSection reports WARNING when mpvd has no ftyp child`() {
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 1000,
            children = listOf(BoxNode(type = "mpvd", offset = 100, headerSize = 8, size = 500, children = emptyList())),
        )
        val checks = analyzeAppleMpvdSection(root, fileLength = 1000)
        assertTrue(checks.any { it.severity == SefIntegritySeverity.WARNING })
    }

    @Test
    fun `analyzeDecodability returns empty when there is no video`() {
        assertEquals(emptyList(), analyzeDecodability(File.createTempFile("no-video-test", ".bin").apply { deleteOnExit() }, null))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.ui.MotionPhotoIntegrityAnalyzerTest"`
Expected: FAIL to compile (`analyzeAppleMpvdSection`, `analyzeDecodability` don't exist yet).

- [ ] **Step 3: Implement**

First, add one new import to the top of
`app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt`
(Task 1's version of this file has no `File` type usage yet, so this
import is missing until now):
```kotlin
import java.io.File
```

Then add, after `analyzeGoogleXmpSection`:

```kotlin
// Verifies an Apple/QuickTime-style embedded video (an "mpvd" or "EmbeddedVideoData" box, used by
// this app's HEIC motion-photo path) is positioned within the file and has a real ftyp child --
// the same box shape findEmbeddedVideo already reads to extract the video, but this reports on
// its structural validity instead of just extracting it.
internal fun analyzeAppleMpvdSection(root: BoxNode, fileLength: Long): List<SefCheckResult> {
    val mpvdNode = com.multiviewer.parser.findFirst(root) { it.type == "mpvd" || it.type == "EmbeddedVideoData" }
        ?: return emptyList()

    if (mpvdNode.offset < 0 || mpvdNode.offset + mpvdNode.size > fileLength) {
        return listOf(SefCheckResult(SefIntegritySeverity.CRITICAL, "mpvd 박스 범위", "mpvd 박스가 파일 범위를 벗어납니다 (offset=${mpvdNode.offset}, size=${mpvdNode.size}, file=$fileLength)"))
    }

    val ftypChild = mpvdNode.children.find { it.type == "ftyp" }
    return if (ftypChild == null) {
        listOf(SefCheckResult(SefIntegritySeverity.WARNING, "mpvd 내부 ftyp", "mpvd 박스 내부에서 ftyp 자식 박스를 찾지 못했습니다"))
    } else {
        val majorBrand = ftypChild.fields.find { it.name == "major_brand" }?.value?.trim() ?: "알 수 없음"
        listOf(SefCheckResult(SefIntegritySeverity.PASS, "mpvd 내부 ftyp", "major_brand=\"$majorBrand\""))
    }
}

// Format-independent: extracts whichever video findEmbeddedVideo resolved (any of the 3 formats)
// to a temp file and runs a real ffprobe on it, to catch corruption/truncation that pure
// offset/length arithmetic can't -- every other check in this file validates declared *positions*,
// this is the only one that validates the actual bytes decode.
internal fun analyzeDecodability(file: File, video: com.multiviewer.parser.EmbeddedVideo?): List<SefCheckResult> {
    if (video == null) return emptyList()
    val temp = File.createTempFile("motion-photo-decode-check", ".${video.extension}")
    return try {
        com.multiviewer.parser.extractEmbeddedVideo(file, video, temp)
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1",
            temp.absolutePath,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        if (exitCode == 0) {
            listOf(SefCheckResult(SefIntegritySeverity.PASS, "임베디드 비디오 디코딩 확인", "ffprobe로 정상적으로 스트림 정보를 읽었습니다: $output"))
        } else {
            listOf(SefCheckResult(SefIntegritySeverity.CRITICAL, "임베디드 비디오 디코딩 확인", "ffprobe가 실패했습니다 (exit=$exitCode): $output"))
        }
    } catch (e: Exception) {
        listOf(SefCheckResult(SefIntegritySeverity.CRITICAL, "임베디드 비디오 디코딩 확인", "ffprobe 실행 실패: ${e.message}"))
    } finally {
        temp.delete()
    }
}

// Top-level orchestrator: opens exactly ONE ByteReader for the whole analysis (SEF delegation,
// Google XMP checks, and findEmbeddedVideo all share it) -- this project has already had to fix
// redundant-ByteReader.open bugs twice, so this never opens a second reader per section. Blocking
// (file I/O + one ffprobe subprocess call): callers must invoke via withContext(Dispatchers.IO).
object MotionPhotoIntegrityAnalyzer {
    fun analyze(file: File, root: BoxNode): MotionPhotoIntegrityReport {
        return ByteReader.open(file).use { reader ->
            val sefdNode = com.multiviewer.parser.findFirst(root) { it.type == "sefd" }
            val sefSection = sefdNode?.let { sefd ->
                com.multiviewer.parser.SefIntegrityAnalyzer.analyze(reader, sefd.offset, sefd.headerSize, sefd.size, file.length())
            }
            val googleChecks = analyzeGoogleXmpSection(root, reader)
            val appleChecks = analyzeAppleMpvdSection(root, file.length())
            val video = try {
                com.multiviewer.parser.findEmbeddedVideo(root, reader)
            } catch (e: Exception) {
                null
            }
            val decodeChecks = analyzeDecodability(file, video)

            val detectedFormats = buildList {
                if (sefdNode != null) add(MotionPhotoFormat.SAMSUNG_SEF)
                if (googleChecks.isNotEmpty()) add(MotionPhotoFormat.GOOGLE_XMP)
                if (appleChecks.isNotEmpty()) add(MotionPhotoFormat.APPLE_MPVD)
            }

            val allSeverities = (sefSection?.let { it.structuralChecks + it.semanticChecks } ?: emptyList()) +
                googleChecks + appleChecks + decodeChecks
            val overall = when {
                allSeverities.any { it.severity == SefIntegritySeverity.CRITICAL } -> SefIntegritySeverity.CRITICAL
                allSeverities.any { it.severity == SefIntegritySeverity.WARNING } -> SefIntegritySeverity.WARNING
                else -> SefIntegritySeverity.PASS
            }

            MotionPhotoIntegrityReport(detectedFormats, sefSection, googleChecks, appleChecks, decodeChecks, overall)
        }
    }
}
```

(The fully-qualified `com.multiviewer.parser.xxx` calls above avoid adding
a long import block for symbols only used once or twice each; if you
prefer, add proper `import` lines instead — either is fine, just be
consistent within the file.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.ui.MotionPhotoIntegrityAnalyzerTest"`
Expected: PASS, all 13 tests (8 from Task 1 + 5 new).

- [ ] **Step 5: Compile and run the full test suite**

Run: `./gradlew compileKotlin` then `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt app/src/test/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzerTest.kt
git commit -m "feat: Apple mpvd checks, ffprobe decode check, and orchestrator

analyzeAppleMpvdSection verifies the mpvd/EmbeddedVideoData box (HEIC
motion-photo path) is within the file and has a real ftyp child.
analyzeDecodability is the one format-independent check in this file:
whichever format resolved a video via the existing findEmbeddedVideo,
this extracts it and runs a real ffprobe on it, catching corruption/
truncation that pure offset/length checks can't. The top-level
MotionPhotoIntegrityAnalyzer.analyze(file, root) ties SEF delegation,
Google XMP, Apple mpvd, and the decode check together behind exactly
one shared ByteReader for the whole analysis.

See docs/superpowers/specs/2026-09-26-motion-photo-integrity-check-design.md"
```

---

### Task 3: Report window + menu item

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityWindow.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/I18n.kt`

**Interfaces:**
- Consumes: `MotionPhotoIntegrityAnalyzer.analyze(file, root): MotionPhotoIntegrityReport`, `MotionPhotoIntegrityReport`, `MotionPhotoFormat` (Tasks 1-2).
- Produces: `@Composable fun MotionPhotoIntegrityWindow(file: File, root: BoxNode, themeMode: ThemeMode = ThemeMode.DARK, onCloseRequest: () -> Unit)` — a top-level report window, opened from `Main.kt`.

This task has no new pure-logic unit tests (Compose UI wiring + menu
integration, matching this codebase's precedent — `SefIntegrityWindow.kt`
has no dedicated test file either). Verify via `./gradlew compileKotlin`
and Task 4's manual verification.

- [ ] **Step 1: Add the menu label to `I18n.kt`**

Find (near the existing SEF/Motion Photo menu labels):
```kotlin
    fun menuSefIntegrityCheck(lang: AppLanguage) = if (lang == AppLanguage.KO) "SEF 무결성 검사" else "SEF Integrity Check"
```

Add right after it:
```kotlin
    fun menuMotionPhotoIntegrityCheck(lang: AppLanguage) = if (lang == AppLanguage.KO) "모션포토 정합성 검사" else "Motion Photo Integrity Check"
```

- [ ] **Step 2: Create `MotionPhotoIntegrityWindow.kt`**

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefIntegritySeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Duplicated from SefIntegrityWindow.kt rather than shared -- small, file-scoped private UI
// helpers, matching this codebase's established per-file-copy convention for trivial helpers
// (see SefIntegrityAnalyzer.kt's own comment on readUInt16LE/readUInt32LE for the same reasoning).
private fun motionPhotoSeverityColor(severity: SefIntegritySeverity): Color = when (severity) {
    SefIntegritySeverity.PASS -> Color(0xFF2E7D32)
    SefIntegritySeverity.INFO -> Color(0xFF1565C0)
    SefIntegritySeverity.WARNING -> Color(0xFFF57F17)
    SefIntegritySeverity.CRITICAL -> Color(0xFFC62828)
    SefIntegritySeverity.SKIPPED -> Color(0xFF757575)
}

private fun motionPhotoSeverityBadgeText(severity: SefIntegritySeverity): String = when (severity) {
    SefIntegritySeverity.PASS -> "✓ PASS"
    SefIntegritySeverity.INFO -> "ℹ INFO"
    SefIntegritySeverity.WARNING -> "⚠ WARNING"
    SefIntegritySeverity.CRITICAL -> "✗ CRITICAL"
    SefIntegritySeverity.SKIPPED -> "⊘ SKIPPED"
}

@Composable
private fun MotionPhotoSeverityBadge(severity: SefIntegritySeverity) {
    val color = motionPhotoSeverityColor(severity)
    Text(
        motionPhotoSeverityBadgeText(severity),
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .border(1.dp, color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun MotionPhotoCheckRow(check: SefCheckResult) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        MotionPhotoSeverityBadge(check.severity)
        Column {
            Text(check.label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(check.detail, fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun MotionPhotoCheckSection(title: String, checks: List<SefCheckResult>) {
    if (checks.isEmpty()) return
    Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    checks.forEach { MotionPhotoCheckRow(it) }
}

// "mpvd" is not Apple-specific in this app's real-world usage -- it's the box this app's own
// MotionPhotoBuilder.createSamsungHeicMotionPhoto writes for ANY HEIC motion photo, Samsung
// included (confirmed against MotionPhotoBuilder.kt: its HEIC path writes an "mpvd" box, not a
// "sefd" trailer -- SEF is JPEG-only in this app's real-world file support). The MotionPhotoFormat
// enum constant stays APPLE_MPVD (an internal identifier, already committed in Task 1 -- not worth
// reopening for a naming nuance), but user-facing text must not say "Apple/QuickTime", since that
// would misattribute Samsung's own HEIC output to Apple.
private fun motionPhotoFormatLabel(format: MotionPhotoFormat): String = when (format) {
    MotionPhotoFormat.SAMSUNG_SEF -> "삼성 SEF"
    MotionPhotoFormat.GOOGLE_XMP -> "구글 모션포토 (XMP)"
    MotionPhotoFormat.APPLE_MPVD -> "HEIC 임베디드 비디오 (mpvd)"
}

@Composable
fun MotionPhotoIntegrityWindow(
    file: File,
    root: BoxNode,
    themeMode: ThemeMode = ThemeMode.DARK,
    onCloseRequest: () -> Unit,
) {
    var report by remember(file) { mutableStateOf<MotionPhotoIntegrityReport?>(null) }
    var isLoading by remember(file) { mutableStateOf(true) }
    var error by remember(file) { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        isLoading = true
        try {
            report = withContext(Dispatchers.IO) { MotionPhotoIntegrityAnalyzer.analyze(file, root) }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        isLoading = false
    }

    Window(onCloseRequest = onCloseRequest, state = rememberWindowState(width = 640.dp, height = 720.dp), title = "모션포토 정합성 검사 - ${file.name}") {
        Column(modifier = Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp)) {
            val currentReport = report
            when {
                error != null -> Text("오류: $error", color = Color.Red)
                isLoading || currentReport == null -> Text("분석 중...")
                currentReport.detectedFormats.isEmpty() -> Text("이 파일에서 모션포토 형식을 감지하지 못했습니다.")
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MotionPhotoSeverityBadge(currentReport.overallSeverity)
                        Text("감지된 형식: ${currentReport.detectedFormats.joinToString(", ") { motionPhotoFormatLabel(it) }}", fontSize = 13.sp)
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                        currentReport.sefSection?.let { sef ->
                            item { MotionPhotoCheckSection("Samsung SEF - 구조적 검사", sef.structuralChecks) }
                            item { MotionPhotoCheckSection("Samsung SEF - 필드별 의미론 검사", sef.semanticChecks) }
                        }
                        item { MotionPhotoCheckSection("구글 모션포토 (XMP)", currentReport.googleXmpChecks) }
                        item { MotionPhotoCheckSection(motionPhotoFormatLabel(MotionPhotoFormat.APPLE_MPVD), currentReport.appleMpvdChecks) }
                        item { MotionPhotoCheckSection("임베디드 비디오 디코딩 확인", currentReport.decodeChecks) }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Wire the menu item and window into `Main.kt`**

Find the existing SEF menu item and `hasSefData` gate:
```kotlin
                val hasSefData = currentTab?.root?.let { root -> findFirst(root) { it.type == "sefd" } } != null
                Item(
                    I18n.menuSefIntegrityCheck(language),
                    enabled = hasSefData,
                    onClick = { sefIntegrityWindowOpen = true },
                )
```

This is inside the analysis-tools menu, not the "모션포토" menu — the new
item goes in the "모션포토" `Menu(...)` block instead. Find:
```kotlin
            Menu(I18n.menuMotionPhoto(language)) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val isImage = currentTab != null && !currentTab.isLoading && currentTab.type == MediaType.IMAGE
                val isHeic = currentTab?.file?.extension?.lowercase(java.util.Locale.US) in setOf("heic", "heif")
```

Add, right after that block's last existing `Item(...)` (the
`menuMotionFrameDropAnalysis` one) and before the `Menu`'s closing `}`:
```kotlin
                Separator()
                val hasMotionPhoto = currentTab?.root?.let { r ->
                    findFirst(r) { it.type == "sefd" } != null ||
                        findFirst(r) { it.type == "mpvd" || it.type == "EmbeddedVideoData" } != null ||
                        findFirst(r) {
                            it.fields.any { f ->
                                f.name == "xmp" && (f.value.contains("MotionPhoto", ignoreCase = true) || f.value.contains("MicroVideo", ignoreCase = true))
                            }
                        } != null
                } ?: false
                Item(
                    I18n.menuMotionPhotoIntegrityCheck(language),
                    enabled = hasMotionPhoto,
                    onClick = { motionPhotoIntegrityWindowOpen = true },
                )
```

Find the existing window-open state declaration:
```kotlin
        var sefIntegrityWindowOpen by remember { mutableStateOf(false) }
```

Add right after it:
```kotlin
        var motionPhotoIntegrityWindowOpen by remember { mutableStateOf(false) }
```

Find the existing SEF window-open block:
```kotlin
            if (sefIntegrityWindowOpen) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val sefdNode = currentTab?.root?.let { root -> findFirst(root) { it.type == "sefd" } }
                if (sefdNode != null && currentTab != null) {
                    SefIntegrityWindow(
                        file = currentTab.file,
                        sefdOffset = sefdNode.offset,
                        sefdHeaderSize = sefdNode.headerSize,
                        sefdSize = sefdNode.size,
                        themeMode = themeMode,
                        onCloseRequest = { sefIntegrityWindowOpen = false },
                    )
                } else {
                    sefIntegrityWindowOpen = false
                }
            }
```

Add right after it:
```kotlin
            if (motionPhotoIntegrityWindowOpen) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val currentRoot = currentTab?.root
                if (currentRoot != null && currentTab != null) {
                    MotionPhotoIntegrityWindow(
                        file = currentTab.file,
                        root = currentRoot,
                        themeMode = themeMode,
                        onCloseRequest = { motionPhotoIntegrityWindowOpen = false },
                    )
                } else {
                    motionPhotoIntegrityWindowOpen = false
                }
            }
```

- [ ] **Step 4: Compile and run the full test suite**

Run: `./gradlew compileKotlin` then `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityWindow.kt app/src/main/kotlin/com/multiviewer/Main.kt app/src/main/kotlin/com/multiviewer/ui/I18n.kt
git commit -m "feat: Motion Photo Integrity Check report window and menu item

New menu item under the existing 모션포토 menu, enabled whenever any of
the 3 recognized motion-photo formats' markers are present (broader
than hasSefData, which only gates the standalone SEF-only menu). Report
window mirrors SefIntegrityWindow.kt's LaunchedEffect+isLoading pattern
and severity-badge styling, with one section per format actually
detected plus the format-independent decode-check section.

See docs/superpowers/specs/2026-09-26-motion-photo-integrity-check-design.md"
```

---

### Task 4: Manual verification

- [ ] **Step 1**: Run `./gradlew compileKotlin` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 2**: Run the full suite once more (`./gradlew test`) as a final check.
- [ ] **Step 3 (manual)**: Using `MotionPhotoBuilder` (the same approach used
  for the SEF Integrity Check's own manual verification — a throwaway,
  uncommitted test script, written/run/deleted), generate a real Samsung
  HEIC motion photo, a real Google Motion Photo JPEG (current
  `Container:Directory` schema), and if feasible a legacy
  `GCamera:MicroVideoOffset` one, from real HEIC/JPEG + MP4 sources. Open
  each in the app and confirm: the menu item is enabled, the correct
  format section(s) appear (and only those), all checks show `PASS`, and
  the decode-check section shows `PASS`.
- [ ] **Step 4 (manual)**: Open a real Samsung SEF motion photo and confirm
  the "Samsung SEF" section's content in this new window matches what the
  existing standalone "SEF 무결성 검사" menu already shows for the same
  file (same structural/semantic check results) — confirms the delegation
  is genuinely unchanged, not a divergent reimplementation.
- [ ] **Step 5 (manual)**: Deliberately corrupt a synthesized motion photo's
  embedded video bytes (e.g. truncate the file after the video's `ftyp`
  box, or zero out a chunk in the middle) while leaving its declared
  offset/length metadata unchanged, and confirm the decode-check section
  reports `CRITICAL` even though the offset/length checks still report
  `PASS` — this is the specific gap this feature closes that no prior
  check caught.
- [ ] **Step 6 (manual)**: Open a file with no motion-photo markers at all
  and confirm the menu item is disabled (grayed out), not clickable into
  an empty/error window.
