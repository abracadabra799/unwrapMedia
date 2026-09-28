# Motion Photo Integrity Check Per-Category Tables Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign the "모션포토 정합성 검사" window around 3-4 independent
per-format correctness categories (Google XMP config, SEF motion-photo
fields, SEF structural integrity, +mpvd for HEIC) instead of flat
scattered checks, adding real new validation the current implementation
is missing (XMP PresentationTimestampUs/Padding/Mime, SEF
`MotionPhoto_AutoPlay` payload bounds) and fixing a real detection bug
(SEF format detection currently triggers on bare `sefd` presence instead
of requiring `MotionPhoto_Data`).

**Architecture:** `MotionPhotoExtractor.kt` gains a `padding` field on the
existing `DirectoryVideoInfo` and a new `findPresentationTimestampUs`
helper. `MotionPhotoIntegrityAnalyzer.kt`'s `analyzeGoogleXmpSection`
gains 3 new checks and a `videoDurationUs` parameter (computed once by
the orchestrator via a new `probeVideoDurationUs`, reused rather than
extracting the video a third time). `SefIntegrityAnalyzer.kt` gains a
`checkMotionPhotoAutoPlay` mirroring the existing `checkMotionPhotoData`.
Both `detectedFormats`'s `SAMSUNG_SEF` condition and `Main.kt`'s
`hasMotionPhoto` gate switch from bare `sefd` presence to requiring
`MotionPhoto_Data` specifically. `SefIntegrityWindow.kt`'s table
composables (`SeverityBadge`, `DirectoryEntryTable`, etc.) move to a new
shared file, `SefIntegrityTableComponents.kt`, and
`MotionPhotoIntegrityWindow.kt` is rebuilt around 4 category sections
reusing those same composables instead of a stopgap synthesized flat list.

**Tech Stack:** Kotlin, Compose Desktop, JUnit5 (`kotlin("test-junit5")`), ffmpeg/ffprobe (already bundled).

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-09-28-motion-photo-integrity-category-tables-design.md`.
- No cross-format position reconciliation (comparing whether SEF/XMP/mpvd
  resolved video ranges agree with each other) — explicitly out of scope,
  corrected during brainstorming.
- `Item:Padding`'s "correctness" is checked only as "parses to a
  non-negative integer" — no deeper semantic cross-check exists to
  validate it against.
- `MotionPhoto_AutoPlay`'s payload check uses the exact same defensive
  12-byte-shape guard `MotionPhoto_Data` already has (WARNING, not a
  crash or false CRITICAL, when the field isn't exactly 12 bytes) — no
  real JPEG sample with an AutoPlay preview was available to confirm its
  shape either way, so assume it can vary the same way `MotionPhoto_Data`
  does.
- `SAMSUNG_SEF` format detection and the `hasMotionPhoto` menu gate must
  both require `MotionPhoto_Data` specifically, not bare `sefd` presence
  — `MotionPhoto_Data` is mandatory for a file to actually be a SEF motion
  photo; `MotionPhoto_AutoPlay`/`MotionPhoto_Version` are optional and
  their absence is never flagged.

---

## File Structure

| File | Change |
|---|---|
| `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoExtractor.kt` | Add `padding` to `DirectoryVideoInfo`, add `findPresentationTimestampUs` (Task 1). |
| `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt` | Add `probeVideoDurationUs`, extend `analyzeGoogleXmpSection` with 3 new checks + `videoDurationUs` param, reorder `analyze()` (Task 1); fix `detectedFormats`'s `SAMSUNG_SEF` condition (Task 2). |
| `app/src/test/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzerTest.kt` | Update 8 existing `analyzeGoogleXmpSection` calls for the new param, extend `tempFileWithFtypAt`/`googleDirectoryXmp` fixtures, add new tests (Task 1). |
| `app/src/main/kotlin/com/multiviewer/parser/SefIntegrityAnalyzer.kt` | Add `checkMotionPhotoAutoPlay`, wire into dispatch (Task 2). |
| `app/src/test/kotlin/com/multiviewer/parser/SefIntegrityAnalyzerTest.kt` | Add `MotionPhoto_AutoPlay` bounds tests (Task 2). |
| `app/src/main/kotlin/com/multiviewer/Main.kt` | Fix `hasMotionPhoto`'s SEF branch (Task 2). |
| `app/src/main/kotlin/com/multiviewer/ui/SefIntegrityTableComponents.kt` | New — shared table composables extracted from `SefIntegrityWindow.kt` (Task 3). |
| `app/src/main/kotlin/com/multiviewer/ui/SefIntegrityWindow.kt` | Remove the now-shared composables, import from the new file (Task 3). |
| `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityWindow.kt` | Rebuilt around 4 category sections (Task 3). |

---

### Task 1: Google XMP validation extensions (PresentationTimestampUs, Padding, Mime)

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoExtractor.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzerTest.kt`

**Interfaces:**
- Consumes: existing `DirectoryVideoInfo`, `findMotionPhotoInDirectory`, `findMicroVideoOffset`, `correctMp4StartOffset`, `extractEmbeddedVideo`, `EmbeddedVideo`, `readProcessOutputWithTimeout`, `FfmpegLocator`.
- Produces: `internal fun findPresentationTimestampUs(document: Document): Long?`, `internal fun probeVideoDurationUs(file: File, video: EmbeddedVideo): Long?`, extended `internal fun analyzeGoogleXmpSection(root: BoxNode, reader: ByteReader, videoDurationUs: Long?): List<SefCheckResult>` — the new 3rd parameter is consumed by Task 3's window (unchanged usage) via the orchestrator, which Task 2 also touches.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzerTest.kt`, find:
```kotlin
private fun tempFileWithFtypAt(ftypBoxStart: Long, totalSize: Long): File {
    val bytes = ByteArray(totalSize.toInt())
    "ftyp".toByteArray(Charsets.US_ASCII).copyInto(bytes, (ftypBoxStart + 4).toInt())
    val tmp = File.createTempFile("motion-photo-integrity-test", ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(bytes)
    return tmp
}
```
Replace with:
```kotlin
private fun tempFileWithFtypAt(ftypBoxStart: Long, totalSize: Long, majorBrand: String = "isom"): File {
    val bytes = ByteArray(totalSize.toInt())
    "ftyp".toByteArray(Charsets.US_ASCII).copyInto(bytes, (ftypBoxStart + 4).toInt())
    majorBrand.toByteArray(Charsets.US_ASCII).copyInto(bytes, (ftypBoxStart + 8).toInt())
    val tmp = File.createTempFile("motion-photo-integrity-test", ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(bytes)
    return tmp
}
```
(Adding a real 4-byte `major_brand` right after "ftyp" so the new Mime
check has something real to compare against — every existing caller of
this helper gets `majorBrand="isom"` by default, which is exactly what
this plan's new Mime check expects to match the fixtures' existing
hardcoded `Item:Mime="video/mp4"`, so no existing test's assertions
change meaning.)

Find:
```kotlin
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
```
Replace with:
```kotlin
private fun googleDirectoryXmp(
    declaredLength: Long,
    mime: String = "video/mp4",
    padding: String? = null,
    presentationTimestampUs: Long? = null,
): String {
    val topLevelTimestampAttr = if (presentationTimestampUs != null) "\n            GCamera:MotionPhotoPresentationTimestampUs=\"$presentationTimestampUs\"" else ""
    val paddingAttr = if (padding != null) " Item:Padding=\"$padding\"" else ""
    return """
    <x:xmpmeta xmlns:x="adobe:ns:meta/">
      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description
            xmlns:Container="http://ns.google.com/photos/1.0/container/"
            xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
            xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
            GCamera:MotionPhoto="1"$topLevelTimestampAttr>
          <Container:Directory>
            <rdf:Seq>
              <rdf:li rdf:parseType="Resource">
                <Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/>
              </rdf:li>
              <rdf:li rdf:parseType="Resource">
                <Container:Item Item:Semantic="MotionPhoto" Item:Mime="$mime" Item:Length="$declaredLength"$paddingAttr/>
              </rdf:li>
            </rdf:Seq>
          </Container:Directory>
        </rdf:Description>
      </rdf:RDF>
    </x:xmpmeta>
""".trimIndent()
}
```
(All new parameters default to the exact original hardcoded values/shape
— every existing `googleDirectoryXmp(declaredLength)` call site keeps
working unchanged.)

Now find all 8 existing calls of the form `analyzeGoogleXmpSection(root, reader)`
in this file and add a 3rd argument, `null`, to each (the new
`videoDurationUs` parameter — `null` is correct for all 8, since none of
these fixtures declare a `PresentationTimestampUs`, so the new timestamp
check never fires regardless of what duration value is passed):
```kotlin
analyzeGoogleXmpSection(root, reader, null)
```
(This appears in the 8 test methods:
`analyzeGoogleXmpSection returns empty when there is no XMP at all`,
`analyzeGoogleXmpSection returns empty when XMP exists but has no motion-photo markers`,
`analyzeGoogleXmpSection reports PASS when the declared Directory Length exactly matches the real ftyp position`,
`analyzeGoogleXmpSection reports WARNING when the real ftyp is found only after correction`,
`analyzeGoogleXmpSection reports CRITICAL when no ftyp is found anywhere in the search window`,
`analyzeGoogleXmpSection reports CRITICAL when the declared length exceeds the file size`,
`analyzeGoogleXmpSection detects the legacy MicroVideo schema and still verifies the offset`,
`analyzeGoogleXmpSection reports WARNING when the XMP text has motion-photo markers but fails to parse`
— every one currently calls `analyzeGoogleXmpSection(root, reader)` with exactly this text, so a
simple find-and-replace-all of `analyzeGoogleXmpSection(root, reader)` →
`analyzeGoogleXmpSection(root, reader, null)` across the file covers all 8.)

Add these 6 new test methods inside `class MotionPhotoIntegrityAnalyzerTest { ... }`
(anywhere among the other `analyzeGoogleXmpSection` tests):
```kotlin
    @Test
    fun `analyzeGoogleXmpSection reports PASS for a valid non-negative Padding`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val approxStart = fileSize - declaredLength
        val file = tempFileWithFtypAt(approxStart, fileSize)
        val root = rootWithXmp(googleDirectoryXmp(declaredLength, padding = "8"), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, null)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.PASS && it.label.contains("Padding") })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports WARNING for a non-numeric Padding`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val approxStart = fileSize - declaredLength
        val file = tempFileWithFtypAt(approxStart, fileSize)
        val root = rootWithXmp(googleDirectoryXmp(declaredLength, padding = "not-a-number"), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, null)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.WARNING && it.label.contains("Padding") })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports PASS when declared Mime matches the real ftyp major_brand`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val approxStart = fileSize - declaredLength
        val file = tempFileWithFtypAt(approxStart, fileSize, majorBrand = "isom")
        val root = rootWithXmp(googleDirectoryXmp(declaredLength, mime = "video/mp4"), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, null)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.PASS && it.label.contains("Mime") })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports WARNING when declared Mime disagrees with the real ftyp major_brand`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val approxStart = fileSize - declaredLength
        val file = tempFileWithFtypAt(approxStart, fileSize, majorBrand = "qt  ")
        val root = rootWithXmp(googleDirectoryXmp(declaredLength, mime = "video/mp4"), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, null)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.WARNING && it.label.contains("Mime") })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports PASS when PresentationTimestampUs falls within the real video duration`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val approxStart = fileSize - declaredLength
        val file = tempFileWithFtypAt(approxStart, fileSize)
        val root = rootWithXmp(googleDirectoryXmp(declaredLength, presentationTimestampUs = 500_000L), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, videoDurationUs = 1_000_000L)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.PASS && it.label.contains("셔터") })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports WARNING when PresentationTimestampUs exceeds the real video duration`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val approxStart = fileSize - declaredLength
        val file = tempFileWithFtypAt(approxStart, fileSize)
        val root = rootWithXmp(googleDirectoryXmp(declaredLength, presentationTimestampUs = 5_000_000L), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, videoDurationUs = 1_000_000L)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.WARNING && it.label.contains("셔터") })
        }
    }

    @Test
    fun `probeVideoDurationUs returns the real duration of a real clip`() {
        // generateRealClip (defined further down in this file, near the analyzeDecodability
        // tests) produces a 320x240, 10fps, exactly-1-second clip via real ffmpeg.
        val clip = generateRealClip("duration-probe")
        val video = embeddedVideoCoveringWholeFile(clip)
        val durationUs = probeVideoDurationUs(clip, video)
        assertTrue(durationUs != null && durationUs in 900_000..1_100_000) // ~1s, allow encoder rounding
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.ui.MotionPhotoIntegrityAnalyzerTest"`
Expected: FAIL to compile (`findPresentationTimestampUs`, the 3rd
`analyzeGoogleXmpSection` parameter, and the new `DirectoryVideoInfo`
field don't exist yet; the 8 existing calls also won't compile against
the new signature until Step 4's implementation changes are applied
alongside this test file).

- [ ] **Step 3: Implement — `MotionPhotoExtractor.kt`**

Find:
```kotlin
internal data class DirectoryVideoInfo(val length: Long, val mimeType: String?)
```
Replace with:
```kotlin
internal data class DirectoryVideoInfo(val length: Long, val mimeType: String?, val padding: String?)
```

Find:
```kotlin
internal fun findMotionPhotoInDirectory(document: Document): DirectoryVideoInfo? {
    val items = document.getElementsByTagNameNS("*", "li")
    for (i in 0 until items.length) {
        val li = items.item(i) as? Element ?: continue
        val semantic = findPropertyValue(li, "Semantic") ?: continue
        if (semantic != "MotionPhoto") continue
        val length = findPropertyValue(li, "Length")?.toLongOrNull() ?: continue
        return DirectoryVideoInfo(length, findPropertyValue(li, "Mime"))
    }
    return null
}
```
Replace with:
```kotlin
internal fun findMotionPhotoInDirectory(document: Document): DirectoryVideoInfo? {
    val items = document.getElementsByTagNameNS("*", "li")
    for (i in 0 until items.length) {
        val li = items.item(i) as? Element ?: continue
        val semantic = findPropertyValue(li, "Semantic") ?: continue
        if (semantic != "MotionPhoto") continue
        val length = findPropertyValue(li, "Length")?.toLongOrNull() ?: continue
        return DirectoryVideoInfo(length, findPropertyValue(li, "Mime"), findPropertyValue(li, "Padding"))
    }
    return null
}
```

Find:
```kotlin
internal fun findMicroVideoOffset(document: Document): Long? {
    val descriptions = document.getElementsByTagNameNS("*", "Description")
    for (i in 0 until descriptions.length) {
        val description = descriptions.item(i) as? Element ?: continue
        findPropertyValue(description, "MicroVideoOffset")?.toLongOrNull()?.let { return it }
    }
    return null
}
```
Add right after it (keep the found block unchanged, add a new function next to it):
```kotlin
internal fun findMicroVideoOffset(document: Document): Long? {
    val descriptions = document.getElementsByTagNameNS("*", "Description")
    for (i in 0 until descriptions.length) {
        val description = descriptions.item(i) as? Element ?: continue
        findPropertyValue(description, "MicroVideoOffset")?.toLongOrNull()?.let { return it }
    }
    return null
}

// The shutter-click timestamp within the embedded video, in microseconds -- checks both the
// current (MotionPhotoPresentationTimestampUs) and legacy (MicroVideoPresentationTimestampUs)
// attribute names, matching findMotionPhotoInDirectory/findMicroVideoOffset's own dual-schema
// handling elsewhere in this file.
internal fun findPresentationTimestampUs(document: Document): Long? {
    val descriptions = document.getElementsByTagNameNS("*", "Description")
    for (i in 0 until descriptions.length) {
        val description = descriptions.item(i) as? Element ?: continue
        findPropertyValue(description, "MotionPhotoPresentationTimestampUs")?.toLongOrNull()?.let { return it }
        findPropertyValue(description, "MicroVideoPresentationTimestampUs")?.toLongOrNull()?.let { return it }
    }
    return null
}
```

- [ ] **Step 4: Implement — `MotionPhotoIntegrityAnalyzer.kt`**

Find:
```kotlin
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
Replace with:
```kotlin
internal fun analyzeGoogleXmpSection(root: BoxNode, reader: ByteReader, videoDurationUs: Long?): List<SefCheckResult> {
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
    var resolvedOffset: Long? = null
    if (declaredHasFtyp) {
        checks.add(SefCheckResult(SefIntegritySeverity.PASS, "구글 모션포토 오프셋 일치", "선언된 오프셋($approxStart)에서 실제 ftyp를 확인했습니다"))
        resolvedOffset = approxStart
    } else {
        val corrected = correctMp4StartOffset(reader, approxStart)
        if (corrected != approxStart) {
            checks.add(SefCheckResult(SefIntegritySeverity.WARNING, "구글 모션포토 오프셋 불일치", "선언된 오프셋($approxStart)이 아니라 ${corrected - approxStart}바이트 떨어진 위치($corrected)에서 실제 ftyp를 찾았습니다 -- XMP 선언값이 정확하지 않습니다"))
            resolvedOffset = corrected
        } else {
            checks.add(SefCheckResult(SefIntegritySeverity.CRITICAL, "구글 모션포토 비디오 위치", "선언된 오프셋 근방에서 ftyp를 찾지 못했습니다 -- 비디오 데이터가 없거나 심각하게 손상되었습니다"))
        }
    }

    // Item:Padding -- structural validity only (a non-negative integer); this codebase's own
    // extraction logic doesn't use Padding for any byte-offset computation, so there's no
    // independent cross-check to validate its exact value against.
    fromDirectory?.padding?.let { padding ->
        val paddingValue = padding.toLongOrNull()
        checks.add(
            if (paddingValue != null && paddingValue >= 0)
                SefCheckResult(SefIntegritySeverity.PASS, "구글 모션포토 Padding", "Item:Padding=$padding")
            else
                SefCheckResult(SefIntegritySeverity.WARNING, "구글 모션포토 Padding", "Item:Padding=\"$padding\"은 0 이상의 정수가 아닙니다"),
        )
    }

    // Item:Mime -- compare the declared MIME against the real resolved video's own ftyp major_brand
    // (ftyp layout: 4-byte size, 4-byte "ftyp" tag, then major_brand -- resolvedOffset+8).
    if (resolvedOffset != null) {
        fromDirectory?.mimeType?.let { declaredMime ->
            val majorBrand = try {
                reader.readFourCC(resolvedOffset + 8)
            } catch (e: Exception) {
                null
            }
            if (majorBrand != null) {
                val expectedMime = if (majorBrand.trim() == "qt") "video/quicktime" else "video/mp4"
                checks.add(
                    if (declaredMime == expectedMime)
                        SefCheckResult(SefIntegritySeverity.PASS, "구글 모션포토 Mime", "Item:Mime=\"$declaredMime\"이 실제 컨테이너(major_brand=\"${majorBrand.trim()}\")와 일치합니다")
                    else
                        SefCheckResult(SefIntegritySeverity.WARNING, "구글 모션포토 Mime", "Item:Mime=\"$declaredMime\"이 실제 컨테이너(major_brand=\"${majorBrand.trim()}\", 예상 \"$expectedMime\")와 다릅니다"),
                )
            }
        }
    }

    // PresentationTimestampUs -- the shutter-click moment should fall within the video's real duration.
    val presentationTimestampUs = findPresentationTimestampUs(document)
    if (presentationTimestampUs != null) {
        checks.add(
            when {
                videoDurationUs == null -> SefCheckResult(SefIntegritySeverity.SKIPPED, "구글 모션포토 셔터 타임스탬프", "비디오 길이를 확인할 수 없어 검증을 건너뜁니다")
                presentationTimestampUs in 0..videoDurationUs -> SefCheckResult(SefIntegritySeverity.PASS, "구글 모션포토 셔터 타임스탬프", "PresentationTimestampUs=${presentationTimestampUs}us (비디오 길이 ${videoDurationUs}us 이내)")
                else -> SefCheckResult(SefIntegritySeverity.WARNING, "구글 모션포토 셔터 타임스탬프", "PresentationTimestampUs=${presentationTimestampUs}us 가 비디오 길이(${videoDurationUs}us) 범위를 벗어납니다")
            },
        )
    }

    return checks
}

// Probes the resolved video's real duration (needed to validate PresentationTimestampUs falls
// within it). Extracts to a temp file the same way analyzeDecodability does -- a second
// extraction+ffprobe pass of the same bytes, accepted as a simplicity tradeoff on this
// non-hot-path analyzer rather than threading a shared temp file between the two functions.
internal fun probeVideoDurationUs(file: File, video: com.multiviewer.parser.EmbeddedVideo): Long? {
    val temp = File.createTempFile("motion-photo-duration-probe", ".${video.extension}")
    return try {
        com.multiviewer.parser.extractEmbeddedVideo(file, video, temp)
        val processBuilder = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error",
            "-show_entries", "format=duration",
            "-of", "csv=p=0",
            temp.absolutePath,
        )
        FfmpegLocator.configureEnvironment(processBuilder)
        val process = processBuilder.start()
        val output = readProcessOutputWithTimeout(process, 30) { process.inputStream.bufferedReader().readText().trim() }
        process.waitFor()
        output?.toDoubleOrNull()?.let { (it * 1_000_000).toLong() }
    } catch (e: Exception) {
        null
    } finally {
        temp.delete()
    }
}
```

Find:
```kotlin
            val googleChecks = analyzeGoogleXmpSection(root, reader)
            val appleChecks = analyzeAppleMpvdSection(root, file.length())
            val video = try {
                com.multiviewer.parser.findEmbeddedVideo(root, reader)
            } catch (e: Exception) {
                null
            }
            val decodeChecks = analyzeDecodability(file, video)
```
Replace with:
```kotlin
            val video = try {
                com.multiviewer.parser.findEmbeddedVideo(root, reader)
            } catch (e: Exception) {
                null
            }
            val videoDurationUs = video?.let { probeVideoDurationUs(file, it) }
            val googleChecks = analyzeGoogleXmpSection(root, reader, videoDurationUs)
            val appleChecks = analyzeAppleMpvdSection(root, file.length())
            val decodeChecks = analyzeDecodability(file, video)
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.ui.MotionPhotoIntegrityAnalyzerTest"`
Expected: PASS, all tests (14 existing + 6 new = 20).

- [ ] **Step 6: Compile and run the full test suite**

Run: `./gradlew compileKotlin` then `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MotionPhotoExtractor.kt app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt app/src/test/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzerTest.kt
git commit -m "feat: validate XMP PresentationTimestampUs, Padding, and Mime

analyzeGoogleXmpSection previously only validated the declared video
length/offset. Adds three real checks the Google Motion Photo XMP
schema also carries: Item:Padding (structurally valid non-negative
integer), Item:Mime (matches the resolved video's real ftyp
major_brand), and PresentationTimestampUs (the shutter-click moment
falls within the video's real duration, probed via a new
probeVideoDurationUs). All three are additive -- a missing attribute is
simply not checked, not flagged.

See docs/superpowers/specs/2026-09-28-motion-photo-integrity-category-tables-design.md"
```

---

### Task 2: SEF `MotionPhoto_AutoPlay` payload check + motion-photo detection fix

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/SefIntegrityAnalyzer.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/SefIntegrityAnalyzerTest.kt`

**Interfaces:**
- Consumes: existing `SefFieldBlock`, `SefCheckResult`, `SefIntegritySeverity`; Task 1's reordered `MotionPhotoIntegrityAnalyzer.analyze()` body.
- Produces: `MotionPhoto_AutoPlay` now appears in `SefIntegrityReport.semanticChecks` with dedicated bounds validation; `detectedFormats`'s `SAMSUNG_SEF` and `Main.kt`'s `hasMotionPhoto` both now require `MotionPhoto_Data` specifically — consumed by Task 3's window (no interface change, same `MotionPhotoIntegrityReport` shape).

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/com/multiviewer/parser/SefIntegrityAnalyzerTest.kt`,
the existing `` `MotionPhoto_Data bounds check passes within the file and
fails past it` `` test (around line 344) is the exact template to mirror.
It confirms `checkMotionPhotoData` reads `video_offset`/`video_length` as
**big-endian** uint32 (via `reader.readUInt32`, distinct from this test
file's own little-endian `putUInt32LE` helper used elsewhere for SEF
directory/block headers) and builds the payload as `"mpv2".toByteArray()`
(the literal 4 ASCII bytes) followed by a manually-indexed 8-byte array —
NOT via `putUInt32LE`. Add these 2 new test methods anywhere inside
`class SefIntegrityAnalyzerTest { ... }`, copying that exact construction
style:
```kotlin
    @Test
    fun `MotionPhoto_AutoPlay bounds check passes within the file and fails past it`() {
        val payloadOk = "mpv2".toByteArray() + ByteArray(8).also {
            it[3] = 10 // video_offset = 10 (big-endian uint32)
            it[7] = 5  // video_length = 5 (big-endian uint32)
        }
        val trailerOk = buildSefTrailer(listOf(SefTestField(0x0a30, "MotionPhoto_AutoPlay", payloadOk)))
        byteReaderOf(trailerOk, "sef-autoplay-ok").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailerOk.size.toLong(), 1000L)
            val check = report.semanticChecks.first { it.label.contains("MotionPhoto_AutoPlay") }
            assertEquals(SefIntegritySeverity.PASS, check.severity)
        }

        val payloadBad = "mpv2".toByteArray() + ByteArray(8).also {
            it[0] = 0x7F.toByte() // video_offset = a huge number
            it[1] = 0xFF.toByte()
            it[2] = 0xFF.toByte()
            it[3] = 0xFF.toByte()
        }
        val trailerBad = buildSefTrailer(listOf(SefTestField(0x0a30, "MotionPhoto_AutoPlay", payloadBad)))
        byteReaderOf(trailerBad, "sef-autoplay-bad").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailerBad.size.toLong(), 1000L)
            val check = report.semanticChecks.first { it.label.contains("MotionPhoto_AutoPlay") }
            assertEquals(SefIntegritySeverity.CRITICAL, check.severity)
        }
    }

    @Test
    fun `MotionPhoto_AutoPlay with a non-12-byte payload is WARNING, not a crash`() {
        val trailer = buildSefTrailer(listOf(SefTestField(0x0a30, "MotionPhoto_AutoPlay", "not-a-pointer-payload".toByteArray())))
        byteReaderOf(trailer, "sef-autoplay-wrong-shape").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailer.size.toLong(), trailer.size.toLong())
            val check = report.semanticChecks.first { it.label.contains("MotionPhoto_AutoPlay") }
            assertEquals(SefIntegritySeverity.WARNING, check.severity)
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.SefIntegrityAnalyzerTest"`
Expected: FAIL — `MotionPhoto_AutoPlay` currently falls through to the
generic `checkTextOrJson` branch, so `report.semanticChecks.first {
it.label.contains("MotionPhoto_AutoPlay") }` won't find a matching label
(it'll find a label like `"Entry #1 (MotionPhoto_AutoPlay) encoding"`
instead of the dedicated `"Entry #1 MotionPhoto_AutoPlay bounds"` this
step expects) — confirm the actual failure mode before proceeding, since
`.first { }` throwing `NoSuchElementException` vs. finding the wrong
label are both valid "this fails" outcomes depending on exact label text.

- [ ] **Step 3: Implement**

In `app/src/main/kotlin/com/multiviewer/parser/SefIntegrityAnalyzer.kt`, find:
```kotlin
                    fb.name == "MotionPhoto_Data" ->
                        if (fb.dataLength == 12)
                            checkMotionPhotoData(reader, fb, fileLength)
                        else
                            SefCheckResult(SefIntegritySeverity.WARNING, "Entry #${fb.entryIndex} MotionPhoto_Data bounds", "Expected exactly 12 bytes, found ${fb.dataLength} -- cannot verify bounds")
                    else -> checkTextOrJson(reader, fb)
```
Replace with:
```kotlin
                    fb.name == "MotionPhoto_Data" ->
                        if (fb.dataLength == 12)
                            checkMotionPhotoData(reader, fb, fileLength)
                        else
                            SefCheckResult(SefIntegritySeverity.WARNING, "Entry #${fb.entryIndex} MotionPhoto_Data bounds", "Expected exactly 12 bytes, found ${fb.dataLength} -- cannot verify bounds")
                    fb.name == "MotionPhoto_AutoPlay" ->
                        if (fb.dataLength == 12)
                            checkMotionPhotoAutoPlay(reader, fb, fileLength)
                        else
                            SefCheckResult(SefIntegritySeverity.WARNING, "Entry #${fb.entryIndex} MotionPhoto_AutoPlay bounds", "Expected exactly 12 bytes, found ${fb.dataLength} -- cannot verify bounds")
                    else -> checkTextOrJson(reader, fb)
```

Find:
```kotlin
private fun checkMotionPhotoData(reader: ByteReader, fb: SefFieldBlock, fileLength: Long): SefCheckResult {
    val videoOffset = reader.readUInt32(fb.dataStart + 4)
    val videoLength = reader.readUInt32(fb.dataStart + 8)
    val end = videoOffset + videoLength
    return if (end <= fileLength) {
        SefCheckResult(SefIntegritySeverity.PASS, "Entry #${fb.entryIndex} MotionPhoto_Data bounds", "video_offset=$videoOffset + video_length=$videoLength = $end, within file length $fileLength")
    } else {
        SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #${fb.entryIndex} MotionPhoto_Data bounds", "video_offset=$videoOffset + video_length=$videoLength = $end, EXCEEDS file length $fileLength")
    }
}
```
Add right after it:
```kotlin
private fun checkMotionPhotoData(reader: ByteReader, fb: SefFieldBlock, fileLength: Long): SefCheckResult {
    val videoOffset = reader.readUInt32(fb.dataStart + 4)
    val videoLength = reader.readUInt32(fb.dataStart + 8)
    val end = videoOffset + videoLength
    return if (end <= fileLength) {
        SefCheckResult(SefIntegritySeverity.PASS, "Entry #${fb.entryIndex} MotionPhoto_Data bounds", "video_offset=$videoOffset + video_length=$videoLength = $end, within file length $fileLength")
    } else {
        SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #${fb.entryIndex} MotionPhoto_Data bounds", "video_offset=$videoOffset + video_length=$videoLength = $end, EXCEEDS file length $fileLength")
    }
}

// Mirrors checkMotionPhotoData exactly -- MotionPhoto_AutoPlay (the preview clip) uses the same
// 12-byte offset+length pointer shape when present, but unlike MotionPhoto_Data it's optional, so
// this is only ever invoked for entries that actually exist.
private fun checkMotionPhotoAutoPlay(reader: ByteReader, fb: SefFieldBlock, fileLength: Long): SefCheckResult {
    val videoOffset = reader.readUInt32(fb.dataStart + 4)
    val videoLength = reader.readUInt32(fb.dataStart + 8)
    val end = videoOffset + videoLength
    return if (end <= fileLength) {
        SefCheckResult(SefIntegritySeverity.PASS, "Entry #${fb.entryIndex} MotionPhoto_AutoPlay bounds", "video_offset=$videoOffset + video_length=$videoLength = $end, within file length $fileLength")
    } else {
        SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #${fb.entryIndex} MotionPhoto_AutoPlay bounds", "video_offset=$videoOffset + video_length=$videoLength = $end, EXCEEDS file length $fileLength")
    }
}
```

In `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt`,
find (this is the post-Task-1 state of `analyze()`):
```kotlin
            val detectedFormats = buildList {
                if (sefdNode != null) add(MotionPhotoFormat.SAMSUNG_SEF)
                if (googleChecks.isNotEmpty()) add(MotionPhotoFormat.GOOGLE_XMP)
                if (appleChecks.isNotEmpty()) add(MotionPhotoFormat.APPLE_MPVD)
            }
```
Replace with:
```kotlin
            val detectedFormats = buildList {
                // MotionPhoto_Data is mandatory for a file to actually BE a SEF motion photo --
                // MotionPhoto_AutoPlay/MotionPhoto_Version are optional. A bare sefd box (ordinary
                // SEF-tagged EXIF metadata, no motion video at all) must not count as "detected".
                if (sefSection?.directoryEntries?.any { it.name == "MotionPhoto_Data" } == true) {
                    add(MotionPhotoFormat.SAMSUNG_SEF)
                }
                if (googleChecks.isNotEmpty()) add(MotionPhotoFormat.GOOGLE_XMP)
                if (appleChecks.isNotEmpty()) add(MotionPhotoFormat.APPLE_MPVD)
            }
```

In `app/src/main/kotlin/com/multiviewer/Main.kt`, find:
```kotlin
                val hasMotionPhoto = currentTab?.root?.let { r ->
                    findFirst(r) { it.type == "sefd" } != null ||
                        findFirst(r) { it.type == "mpvd" || it.type == "EmbeddedVideoData" } != null ||
                        findFirst(r) {
                            it.fields.any { f ->
                                f.name == "xmp" && (f.value.contains("MotionPhoto", ignoreCase = true) || f.value.contains("MicroVideo", ignoreCase = true))
                            }
                        } != null
                } ?: false
```
Replace with:
```kotlin
                val hasMotionPhoto = currentTab?.root?.let { r ->
                    (findFirst(r) { it.type == "sefd" }?.children?.any { it.type == "MotionPhoto_Data" } == true) ||
                        findFirst(r) { it.type == "mpvd" || it.type == "EmbeddedVideoData" } != null ||
                        findFirst(r) {
                            it.fields.any { f ->
                                f.name == "xmp" && (f.value.contains("MotionPhoto", ignoreCase = true) || f.value.contains("MicroVideo", ignoreCase = true))
                            }
                        } != null
                } ?: false
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.SefIntegrityAnalyzerTest"`
Expected: PASS, all tests (existing suite + 2 new).

- [ ] **Step 5: Compile and run the full test suite**

Run: `./gradlew compileKotlin` then `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures. Pay attention to
`MotionPhotoIntegrityAnalyzerTest` in particular — none of its existing
fixtures declare a `MotionPhoto_Data` field, so none of them were
depending on the old bare-`sefd` detection behavior; this change should
not require any updates there.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/SefIntegrityAnalyzer.kt app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt app/src/main/kotlin/com/multiviewer/Main.kt app/src/test/kotlin/com/multiviewer/parser/SefIntegrityAnalyzerTest.kt
git commit -m "fix: require MotionPhoto_Data for SEF motion-photo detection; add MotionPhoto_AutoPlay bounds check

MotionPhoto_Data is mandatory for a file to actually be a SEF motion
photo; MotionPhoto_AutoPlay/MotionPhoto_Version are optional. But both
detectedFormats's SAMSUNG_SEF condition and Main.kt's hasMotionPhoto
menu gate only checked for a bare sefd box -- confirmed against real
data: 15 real JPEGs found during this feature's own dogfooding all have
a sefd box with ordinary EXIF-style SEF fields (HDR info, color
profile, capture mode) but no MotionPhoto_Data at all. Both are not
motion photos, yet both the menu and the report treated them as if
they were. Also adds checkMotionPhotoAutoPlay (mirrors the existing
checkMotionPhotoData exactly), closing the one gap the standalone SEF
window deliberately left open for its own generic-purpose scope but
which is directly in scope for a motion-photo-specific window.

See docs/superpowers/specs/2026-09-28-motion-photo-integrity-category-tables-design.md"
```

---

### Task 3: Per-category tables in the report window

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/SefIntegrityTableComponents.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/SefIntegrityWindow.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityWindow.kt`

**Interfaces:**
- Consumes: `MotionPhotoIntegrityReport` (Tasks 1-2's fields, unchanged shape), `SefDirectoryEntryRow`, `SefCheckResult`, `SefIntegritySeverity`.
- Produces: `SeverityBadge`, `severityColor`, `severityBadgeText`, `CheckRow`, `CheckSection`, `DirectoryEntryCountSummary`, `DirectoryEntryTableHeader`, `DirectoryEntryTableRow`, `DirectoryEntryTable` — all promoted from `private` to `internal` in the new shared file, consumed by both windows.

This task has no new unit tests (Compose UI rendering, matching both
windows' existing precedent of no dedicated test file). Verify via
`./gradlew compileKotlin` and Task 4's manual verification.

- [ ] **Step 1: Create the shared table components file**

Create `app/src/main/kotlin/com/multiviewer/ui/SefIntegrityTableComponents.kt`:
```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefDirectoryEntryRow
import com.multiviewer.parser.SefIntegritySeverity

// Shared between SefIntegrityWindow.kt (the standalone "SEF 무결성 검사" window) and
// MotionPhotoIntegrityWindow.kt (which embeds the same SEF directory-entry table as one of its
// per-category sections) -- unlike this codebase's usual per-file-duplicate convention for small
// helpers (e.g. SefIntegrityAnalyzer.kt's readUInt16LE/32LE), a real table's worth of composables
// is large enough that duplicating it would mean fixing every future table bug twice.

internal fun severityColor(severity: SefIntegritySeverity): Color = when (severity) {
    SefIntegritySeverity.PASS -> Color(0xFF2E7D32)
    SefIntegritySeverity.INFO -> Color(0xFF1565C0)
    SefIntegritySeverity.WARNING -> Color(0xFFF57F17)
    SefIntegritySeverity.CRITICAL -> Color(0xFFC62828)
    SefIntegritySeverity.SKIPPED -> Color(0xFF757575)
}

internal fun severityBadgeText(severity: SefIntegritySeverity): String = when (severity) {
    SefIntegritySeverity.PASS -> "✓ PASS"
    SefIntegritySeverity.INFO -> "ℹ INFO"
    SefIntegritySeverity.WARNING -> "⚠ WARNING"
    SefIntegritySeverity.CRITICAL -> "✗ CRITICAL"
    SefIntegritySeverity.SKIPPED -> "⊘ SKIPPED"
}

@Composable
internal fun SeverityBadge(severity: SefIntegritySeverity) {
    val color = severityColor(severity)
    Text(
        severityBadgeText(severity),
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
internal fun CheckRow(check: SefCheckResult) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SeverityBadge(check.severity)
        Column {
            Text(check.label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(check.detail, fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
internal fun CheckSection(title: String, checks: List<SefCheckResult>) {
    if (checks.isEmpty()) return
    Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    checks.forEach { CheckRow(it) }
}

@Composable
internal fun DirectoryEntryCountSummary(declaredCount: Long?, foundCount: Int, severity: SefIntegritySeverity) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeverityBadge(severity)
        Text(
            if (declaredCount == null) {
                "SEFH 선언 엔트리 수: 확인 불가 (상위 검사 실패)"
            } else {
                "SEFH 선언 엔트리 수: ${declaredCount}개, 실제 발견: ${foundCount}개"
            },
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

@Composable
internal fun DirectoryEntryTableHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("#", modifier = Modifier.width(28.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("이름", modifier = Modifier.width(140.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("마커", modifier = Modifier.width(64.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("선언 오프셋", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("선언 길이", modifier = Modifier.width(80.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("실제 위치(시작~끝)", modifier = Modifier.width(170.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("범위 내", modifier = Modifier.width(60.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("마커 일치", modifier = Modifier.width(70.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("상태", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
internal fun DirectoryEntryTableRow(row: SefDirectoryEntryRow) {
    val color = if (row.status == SefIntegritySeverity.CRITICAL) severityColor(SefIntegritySeverity.CRITICAL) else Color.Unspecified
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("${row.entryIndex}", modifier = Modifier.width(28.dp), fontSize = 11.sp, color = color)
        Text(row.name ?: "—", modifier = Modifier.width(140.dp), fontSize = 11.sp, color = color)
        Text(row.markerHex, modifier = Modifier.width(64.dp), fontSize = 11.sp, color = color)
        Text("${row.declaredOffset}", modifier = Modifier.width(90.dp), fontSize = 11.sp, color = color)
        Text("${row.declaredLength}", modifier = Modifier.width(80.dp), fontSize = 11.sp, color = color)
        Text("${row.computedDataStart}~${row.computedDataEnd}", modifier = Modifier.width(170.dp), fontSize = 11.sp, color = color)
        Text(if (row.inBounds) "✓" else "✗", modifier = Modifier.width(60.dp), fontSize = 11.sp, color = color)
        Text(
            when (row.markerMatches) {
                true -> "✓"
                false -> "✗"
                null -> "—"
            },
            modifier = Modifier.width(70.dp), fontSize = 11.sp, color = color,
        )
        Box(modifier = Modifier.width(90.dp)) { SeverityBadge(row.status) }
    }
}

@Composable
internal fun DirectoryEntryTable(declaredCount: Long?, entries: List<SefDirectoryEntryRow>, countSeverity: SefIntegritySeverity, title: String = "SEFH 디렉토리 엔트리") {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    DirectoryEntryCountSummary(declaredCount, entries.size, countSeverity)
    if (entries.isEmpty()) return
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.horizontalScroll(scrollState)) {
        DirectoryEntryTableHeader()
        entries.forEach { DirectoryEntryTableRow(it) }
    }
}
```
(`DirectoryEntryTable` gained one new optional `title` parameter, defaulting
to the exact string the standalone SEF window already uses, so Task 3's
next step doesn't need to change that window's call site at all; Task 3's
MotionPhotoIntegrityWindow.kt usage below passes a different title for its
own "SEF 모션포토 관련 필드" vs. "SEF 전체 구조 무결성" sections.)

- [ ] **Step 2: Remove the now-duplicated composables from `SefIntegrityWindow.kt`**

Find (the entire block from the `severityColor` function through the end
of the `DirectoryEntryTable` function — everything now living in the new
shared file):
```kotlin
private fun severityColor(severity: SefIntegritySeverity): Color = when (severity) {
    SefIntegritySeverity.PASS -> Color(0xFF2E7D32)
    SefIntegritySeverity.INFO -> Color(0xFF1565C0)
    SefIntegritySeverity.WARNING -> Color(0xFFF57F17)
    SefIntegritySeverity.CRITICAL -> Color(0xFFC62828)
    SefIntegritySeverity.SKIPPED -> Color(0xFF757575)
}

private fun severityBadgeText(severity: SefIntegritySeverity): String = when (severity) {
    SefIntegritySeverity.PASS -> "✓ PASS"
    SefIntegritySeverity.INFO -> "ℹ INFO"
    SefIntegritySeverity.WARNING -> "⚠ WARNING"
    SefIntegritySeverity.CRITICAL -> "✗ CRITICAL"
    SefIntegritySeverity.SKIPPED -> "⊘ SKIPPED"
}

@Composable
private fun SeverityBadge(severity: SefIntegritySeverity) {
    val color = severityColor(severity)
    Text(
        severityBadgeText(severity),
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
private fun CheckRow(check: SefCheckResult) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SeverityBadge(check.severity)
        Column {
            Text(check.label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(check.detail, fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun CheckSection(title: String, checks: List<SefCheckResult>) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    checks.forEach { CheckRow(it) }
}

@Composable
private fun DirectoryEntryCountSummary(declaredCount: Long?, foundCount: Int, severity: SefIntegritySeverity) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeverityBadge(severity)
        Text(
            if (declaredCount == null) {
                "SEFH 선언 엔트리 수: 확인 불가 (상위 검사 실패)"
            } else {
                "SEFH 선언 엔트리 수: ${declaredCount}개, 실제 발견: ${foundCount}개"
            },
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun DirectoryEntryTableHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("#", modifier = Modifier.width(28.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("이름", modifier = Modifier.width(140.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("마커", modifier = Modifier.width(64.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("선언 오프셋", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("선언 길이", modifier = Modifier.width(80.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("실제 위치(시작~끝)", modifier = Modifier.width(170.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("범위 내", modifier = Modifier.width(60.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("마커 일치", modifier = Modifier.width(70.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("상태", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
private fun DirectoryEntryTableRow(row: SefDirectoryEntryRow) {
    val color = if (row.status == SefIntegritySeverity.CRITICAL) Color(0xFFC62828) else Color.Unspecified
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("${row.entryIndex}", modifier = Modifier.width(28.dp), fontSize = 11.sp, color = color)
        Text(row.name ?: "—", modifier = Modifier.width(140.dp), fontSize = 11.sp, color = color)
        Text(row.markerHex, modifier = Modifier.width(64.dp), fontSize = 11.sp, color = color)
        Text("${row.declaredOffset}", modifier = Modifier.width(90.dp), fontSize = 11.sp, color = color)
        Text("${row.declaredLength}", modifier = Modifier.width(80.dp), fontSize = 11.sp, color = color)
        Text("${row.computedDataStart}~${row.computedDataEnd}", modifier = Modifier.width(170.dp), fontSize = 11.sp, color = color)
        Text(if (row.inBounds) "✓" else "✗", modifier = Modifier.width(60.dp), fontSize = 11.sp, color = color)
        Text(
            when (row.markerMatches) {
                true -> "✓"
                false -> "✗"
                null -> "—"
            },
            modifier = Modifier.width(70.dp), fontSize = 11.sp, color = color,
        )
        Box(modifier = Modifier.width(90.dp)) { SeverityBadge(row.status) }
    }
}

@Composable
private fun DirectoryEntryTable(declaredCount: Long?, entries: List<SefDirectoryEntryRow>, countSeverity: SefIntegritySeverity) {
    Text("SEFH 디렉토리 엔트리", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    DirectoryEntryCountSummary(declaredCount, entries.size, countSeverity)
    if (entries.isEmpty()) return
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.horizontalScroll(scrollState)) {
        DirectoryEntryTableHeader()
        entries.forEach { DirectoryEntryTableRow(it) }
    }
}
```
Delete this entire block (all composables now come from
`SefIntegrityTableComponents.kt` in the same package — no import needed,
same-package visibility applies automatically).

Now trim this file's own import list down to only what its remaining code
(`SefIntegrityWindow` itself) actually uses — find:
```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefDirectoryEntryRow
import com.multiviewer.parser.SefIntegrityAnalyzer
import com.multiviewer.parser.SefIntegrityReport
import com.multiviewer.parser.SefIntegritySeverity
import java.io.File
```
Replace with:
```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.SefIntegrityAnalyzer
import com.multiviewer.parser.SefIntegrityReport
import com.multiviewer.parser.SefIntegritySeverity
import java.io.File
```
(`SefCheckResult`/`SefDirectoryEntryRow` are no longer referenced by name
in this file's remaining code, only inside the shared components file;
`FontWeight`/`RoundedCornerShape`/`Box`/`width`/`horizontalScroll`/
`rememberScrollState`/`fillMaxWidth` also moved with the composables that
used them. `SefIntegrityWindow`'s own remaining body still references
`SeverityBadge`, `CheckSection`, `DirectoryEntryTable`, `SefIntegritySeverity`
— all still resolve correctly, either same-package or already imported.)

- [ ] **Step 3: Rebuild `MotionPhotoIntegrityWindow.kt` around 4 category sections**

Find the entire file-private helper block (everything from
`motionPhotoSeverityColor` through the end of `motionPhotoFormatLabel`):
```kotlin
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
```
Replace with (drops the now-shared severity/badge/row/section composables,
keeps `motionPhotoFormatLabel` since it's specific to this window's own enum):
```kotlin
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
```

Find the window body's `LazyColumn` block:
```kotlin
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                        currentReport.sefSection?.let { sef ->
                            item { MotionPhotoCheckSection("Samsung SEF - 구조적 검사", sef.structuralChecks) }
                            item {
                                val countLabel = "SEFH 선언 엔트리 수"
                                val countDetail = if (sef.declaredEntryCount == null) {
                                    "확인 불가 (상위 검사 실패)"
                                } else {
                                    "선언: ${sef.declaredEntryCount}개, 실제 발견: ${sef.directoryEntries.size}개"
                                }
                                val entryChecks = listOf(SefCheckResult(sef.declaredEntryCountSeverity, countLabel, countDetail)) +
                                    sef.directoryEntries.map { row ->
                                        SefCheckResult(
                                            row.status,
                                            "Entry #${row.entryIndex}${row.name?.let { " ($it)" } ?: ""} (marker ${row.markerHex})",
                                            "declared offset=${row.declaredOffset}, length=${row.declaredLength}, 실제 위치=${row.computedDataStart}~${row.computedDataEnd}, 범위 내=${row.inBounds}, 마커 일치=${row.markerMatches}",
                                        )
                                    }
                                MotionPhotoCheckSection("Samsung SEF - 디렉토리 엔트리", entryChecks)
                            }
                            item { MotionPhotoCheckSection("Samsung SEF - 필드별 의미론 검사", sef.semanticChecks) }
                        }
                        item { MotionPhotoCheckSection("구글 모션포토 (XMP)", currentReport.googleXmpChecks) }
                        item { MotionPhotoCheckSection(motionPhotoFormatLabel(MotionPhotoFormat.APPLE_MPVD), currentReport.appleMpvdChecks) }
                        item { MotionPhotoCheckSection("임베디드 비디오 디코딩 확인", currentReport.decodeChecks) }
                    }
```
Replace with (4 category sections: 1 Google XMP config, 2 SEF
motion-photo fields, 3 SEF structural integrity, +4 HEIC mpvd, matching
this plan's design exactly — `CheckSection`/`DirectoryEntryTable` now come
from the shared file, same-package, no new import needed since Step 4
below adds the necessary imports anyway):
```kotlin
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                        // 1. 구글 XMP 모션포토 구성
                        item { CheckSection("구글 XMP 모션포토 구성", currentReport.googleXmpChecks) }

                        currentReport.sefSection?.let { sef ->
                            // 2. SEF 모션포토 관련 필드 (MotionPhoto_Data/AutoPlay/Version only)
                            item {
                                val motionPhotoEntries = sef.directoryEntries.filter { it.name?.startsWith("MotionPhoto") == true }
                                DirectoryEntryTable(sef.declaredEntryCount, motionPhotoEntries, sef.declaredEntryCountSeverity, title = "SEF 모션포토 관련 필드")
                            }
                            item {
                                val motionPhotoSemanticChecks = sef.semanticChecks.filter { it.label.contains("MotionPhoto") }
                                CheckSection("SEF 모션포토 필드 의미론 검사", motionPhotoSemanticChecks)
                            }

                            // 3. SEF 전체 구조 무결성 (identical to the standalone SEF window's own table)
                            item { CheckSection("SEF 구조적 검사", sef.structuralChecks) }
                            item { DirectoryEntryTable(sef.declaredEntryCount, sef.directoryEntries, sef.declaredEntryCountSeverity, title = "SEF 전체 구조 무결성") }
                            item { CheckSection("SEF 필드별 의미론 검사", sef.semanticChecks) }
                        }

                        // 4. HEIC mpvd 박스 (HEIC only -- simply absent from currentReport.appleMpvdChecks for JPEG)
                        item { CheckSection(motionPhotoFormatLabel(MotionPhotoFormat.APPLE_MPVD), currentReport.appleMpvdChecks) }

                        item { CheckSection("임베디드 비디오 디코딩 확인", currentReport.decodeChecks) }
                    }
```

- [ ] **Step 4: Update `MotionPhotoIntegrityWindow.kt`'s imports**

Find:
```kotlin
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
```
Replace with:
```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.parser.BoxNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
```
(`SefCheckResult`/`SefIntegritySeverity` are no longer referenced by name
in this file — the filtering in Step 3 uses `it.label`/`it.name` on
already-typed lists, no explicit type annotation needed; `SeverityBadge`
now resolves from the shared components file's `SeverityBadge`, and this
window still uses its own local `MotionPhotoSeverityBadge`... — **wait,
check this**: the window's header still calls `MotionPhotoSeverityBadge(currentReport.overallSeverity)`,
which Step 3 just removed the definition of. Find:
```kotlin
                        MotionPhotoSeverityBadge(currentReport.overallSeverity)
```
Replace with:
```kotlin
                        SeverityBadge(currentReport.overallSeverity)
```
— this now resolves to the shared file's `SeverityBadge`, same as every
other severity badge in this rebuilt window.)

- [ ] **Step 5: Compile and run the full test suite**

Run: `./gradlew compileKotlin` then `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/SefIntegrityTableComponents.kt app/src/main/kotlin/com/multiviewer/ui/SefIntegrityWindow.kt app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityWindow.kt
git commit -m "feat: per-category tables in the motion photo integrity window

Extracts SefIntegrityWindow.kt's table composables (SeverityBadge,
DirectoryEntryTable, etc.) into a new shared file so both windows reuse
the exact same table-rendering code instead of one of them faking it
with synthesized flat text. MotionPhotoIntegrityWindow.kt is rebuilt
around 4 category sections matching what this window is actually for
(confirming the file satisfies every motion-photo-format condition):
구글 XMP 모션포토 구성, SEF 모션포토 관련 필드, SEF 전체 구조 무결성,
and (HEIC only) HEIC mpvd 박스 -- replacing the previous flat,
undifferentiated check list.

See docs/superpowers/specs/2026-09-28-motion-photo-integrity-category-tables-design.md"
```

---

### Task 4: Manual verification

- [ ] **Step 1**: Run `./gradlew compileKotlin` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 2**: Run the full suite once more (`./gradlew test`) as a final check.
- [ ] **Step 3 (manual/programmatic)**: Using the same throwaway-test
  pattern established during this feature's own design investigation, run
  `MotionPhotoIntegrityAnalyzer.analyze()` against the two real files
  already used for this design (`~/Downloads/20260715_223835_motion.heic`,
  `~/Downloads/20260718_200439_motion.jpg`) and confirm: `detectedFormats`
  still correctly includes `SAMSUNG_SEF` for both (both have real
  `MotionPhoto_Data`), the new Padding/Mime/PresentationTimestampUs checks
  appear in `googleXmpChecks` with sensible values, and `overallSeverity`
  is unchanged from before this plan's changes for both files (no new
  CRITICAL introduced by the new checks on files already known to be
  valid).
- [ ] **Step 4 (manual/programmatic)**: Run the same analysis against one
  of the 15 plain SEF-tagged (non-motion-photo) JPEGs found during this
  design's investigation (e.g. `~/Downloads/attachments/20260817_230555.jpg`)
  and confirm `detectedFormats` no longer includes `SAMSUNG_SEF` for it
  (closing the detection-gate bug this plan fixes) — and separately, in
  the live GUI, confirm the "모션포토 정합성 검사" menu item is now
  correctly disabled for this file (was previously incorrectly enabled).
- [ ] **Step 5 (manual, GUI)**: Open the real HEIC motion photo in the
  live app and visually confirm the 4 category sections render correctly
  (구글 XMP 모션포토 구성 / SEF 모션포토 관련 필드 / SEF 전체 구조 무결성 /
  HEIC mpvd 박스), the SEF tables show real data with readable text (not
  the Material2-invisible-text bug from earlier in this session), and the
  standalone "SEF 무결성 검사" window still renders identically to before
  this plan (confirming the shared-component extraction didn't change its
  behavior).
