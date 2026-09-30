# Motion Photo XMP Preservation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop "모션포토 생성" (Create Motion Photo) from destroying the original image's existing XMP (gain map, camera metadata) — merge motion-photo attributes into it instead, for both JPEG and HEIC, including creating a brand-new XMP item in HEIC files that have none at all.

**Architecture:** A new shared `mergeMotionPhotoXmp` (in `MotionPhotoBuilder.kt`) does the format-independent DOM-level XML merge, reusing the existing `parseXmpDocument` helper. JPEG's `injectMotionPhotoXmpIntoJpeg` is extended to capture an existing XMP APP1's text before dropping it, then merges instead of always building fresh. HEIC gets two new low-level functions: `repointHeicXmpItem` (existing XMP item — rewrite its `iloc` extent to point at a freshly-appended `mdat`, no box growth) and `createHeicXmpItem` (no existing XMP item — append new `infe`/`iloc` entries and shift every pre-existing absolute-offset extent that now sits past the grown `meta` box).

**Tech Stack:** Kotlin, `java.nio.ByteBuffer`, `org.w3c.dom` (via the existing `parseXmpDocument`), JUnit5.

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-09-30-motion-photo-xmp-preservation-design.md`.
- Preserve as much of the original image's metadata/data as possible in
  every code path — this is the overriding principle behind every task
  below, not just the XMP-specific ones.
- Multiple `rdf:Description` in one `rdf:RDF`: only the first is merged
  into; documented limitation, not a bug to chase.
- JPEG ExtendedXMP (multi-segment) is out of scope — only single-segment
  XMP APP1 is merged.
- No `iref`/`cdsc` item-reference creation for a newly-created HEIC XMP
  item — real-world readers find XMP via `item_type="mime"` +
  `content_type="application/rdf+xml"` alone.
- Gain map numeric parameters (`hdrgm:*`) are preserved verbatim, never
  validated or recomputed.
- A merge failure (unexpected XML structure, parse error) must never abort
  motion-photo creation — always fall back to today's from-scratch XMP
  behavior and continue.
- Existing Exif-before-XMP ordering in JPEG output (`insertPos` computation
  in `injectMotionPhotoXmpIntoJpeg`) must be provably unchanged by this
  work — pin it with an explicit test (§ Task 2).

---

## File Structure

| File | Change |
|---|---|
| `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoBuilder.kt` | Add `mergeMotionPhotoXmp` (Task 1); extend `injectMotionPhotoXmpIntoJpeg` (Task 2); add `repointHeicXmpItem` (Task 3); add `createHeicXmpItem` + shared iloc/infe byte-building helpers (Task 4); wire all of it into `createGoogleMotionPhoto`/`createSamsungHeicMotionPhoto` (Tasks 2-4). |
| `app/src/test/kotlin/com/multiviewer/parser/MotionPhotoBuilderTest.kt` | New tests per task. |
| `app/src/test/kotlin/com/multiviewer/parser/HeicMetaFixtureTest.kt` | New — the realistic HEIC `meta`/`iinf`/`iloc` fixture builder (Task 5), consumed by Tasks 3/4's tests. |

---

### Task 1: `mergeMotionPhotoXmp` — shared DOM-level XMP merge

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MotionPhotoBuilderTest.kt`

**Interfaces:**
- Consumes: existing `parseXmpDocument(xmpText: String): Document` (same
  package, `MotionPhotoExtractor.kt`), existing
  `buildGoogleMotionPhotoXmp`/`buildGoogleMotionPhotoHeicXmp`.
- Produces: `internal fun mergeMotionPhotoXmp(existingXmpText: String?, videoOffsetOrLength: Long, precedingItemPaddingBytes: Long, presentationTimestampUs: Long, version: MotionPhotoFormatVersion, primaryMimeType: String): String` — consumed by Tasks 2, 3, 4.

- [ ] **Step 1: Write the failing tests**

Add to `app/src/test/kotlin/com/multiviewer/parser/MotionPhotoBuilderTest.kt`, inside `class MotionPhotoBuilderTest { ... }`:
```kotlin
    @Test
    fun `mergeMotionPhotoXmp falls back to a fresh build when there is no existing XMP`() {
        val merged = MotionPhotoBuilder.mergeMotionPhotoXmp(null, 1234567L, 100L, 2000000L, MotionPhotoFormatVersion.V2_MOTION_PHOTO, "image/jpeg")
        val fresh = MotionPhotoBuilder.buildGoogleMotionPhotoXmp(1234567L, 100L, 2000000L, MotionPhotoFormatVersion.V2_MOTION_PHOTO)
        assertEquals(fresh, merged)
    }

    @Test
    fun `mergeMotionPhotoXmp falls back to a fresh build when the existing XMP fails to parse`() {
        val merged = MotionPhotoBuilder.mergeMotionPhotoXmp("<not valid xml at all <<<", 1234567L, 100L, 2000000L, MotionPhotoFormatVersion.V2_MOTION_PHOTO, "image/jpeg")
        val fresh = MotionPhotoBuilder.buildGoogleMotionPhotoXmp(1234567L, 100L, 2000000L, MotionPhotoFormatVersion.V2_MOTION_PHOTO)
        assertEquals(fresh, merged)
    }

    @Test
    fun `mergeMotionPhotoXmp preserves an existing plain camera XMP with no Container Directory`() {
        val existing = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:tiff="http://ns.adobe.com/tiff/1.0/"
                  tiff:Make="SomeCamera"
                  tiff:Model="X100"/>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val merged = MotionPhotoBuilder.mergeMotionPhotoXmp(existing, 5000L, 0L, 1500000L, MotionPhotoFormatVersion.V2_MOTION_PHOTO, "image/jpeg")

        assertTrue(merged.contains("tiff:Make=\"SomeCamera\""), "Expected pre-existing tiff:Make to survive the merge")
        assertTrue(merged.contains("tiff:Model=\"X100\""), "Expected pre-existing tiff:Model to survive the merge")
        assertTrue(merged.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(merged.contains("Item:Semantic=\"MotionPhoto\""))
        assertTrue(merged.contains("Item:Semantic=\"Primary\""))
        assertTrue(merged.contains("Item:Length=\"5000\""))
    }

    @Test
    fun `mergeMotionPhotoXmp appends MotionPhoto to an existing Container Directory and recomputes the preceding item's Padding`() {
        val existing = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                  hdrgm:Version="1.0"
                  hdrgm:GainMapMin="0.0"
                  hdrgm:GainMapMax="3.5">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg" Item:Padding="0"/>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="9876" Item:Padding="0"/>
                      </rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val merged = MotionPhotoBuilder.mergeMotionPhotoXmp(existing, 5000L, 42L, 1500000L, MotionPhotoFormatVersion.V2_MOTION_PHOTO, "image/jpeg")

        // GainMap item preserved
        assertTrue(merged.contains("Item:Semantic=\"GainMap\""))
        assertTrue(merged.contains("hdrgm:GainMapMin=\"0.0\""))
        assertTrue(merged.contains("hdrgm:GainMapMax=\"3.5\""))
        assertTrue(merged.contains("Item:Length=\"9876\""), "GainMap's own Length must not change")
        // GainMap's Padding recomputed to the new gap (was 0, now 42 -- the bytes before the appended video)
        val gainMapItemPattern = Regex("""Item:Semantic="GainMap"[^/]*Item:Padding="(\d+)"""")
        val gainMapPadding = gainMapItemPattern.find(merged)?.groupValues?.get(1)
        assertEquals("42", gainMapPadding, "Expected GainMap's Padding to be recomputed to the new preceding-item gap")
        // Primary item untouched
        assertTrue(merged.contains("Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\" Item:Padding=\"0\""))
        // MotionPhoto item appended
        assertTrue(merged.contains("Item:Semantic=\"MotionPhoto\""))
        assertTrue(merged.contains("Item:Length=\"5000\""))
    }

    @Test
    fun `mergeMotionPhotoXmp overwrites this tool's own GCamera attributes if already present`() {
        val existing = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                  GCamera:MotionPhoto="1"
                  GCamera:MotionPhotoPresentationTimestampUs="999"/>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val merged = MotionPhotoBuilder.mergeMotionPhotoXmp(existing, 5000L, 0L, 1500000L, MotionPhotoFormatVersion.V2_MOTION_PHOTO, "image/jpeg")
        assertTrue(merged.contains("GCamera:MotionPhotoPresentationTimestampUs=\"1500000\""), "Expected the new timestamp to overwrite the stale one")
        assertFalse(merged.contains("GCamera:MotionPhotoPresentationTimestampUs=\"999\""))
    }
```
Add `import kotlin.test.assertFalse` to this test file's imports if not already present (check the file's current import block first — as of this plan's writing it imports `assertEquals`, `assertNotNull`, `assertTrue` but not `assertFalse`).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.MotionPhotoBuilderTest"`
Expected: FAIL to compile (`mergeMotionPhotoXmp` doesn't exist yet).

- [ ] **Step 3: Implement**

In `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoBuilder.kt`, add this function right after `buildGoogleMotionPhotoHeicXmp` (after the closing `}` at what is currently line 257, before the `buildApp1XmpSegment` doc comment):
```kotlin
    /**
     * Merges motion-photo attributes into an existing XMP document instead of building a fresh
     * one from scratch -- preserves every other attribute/element the original XMP carried (gain
     * map parameters, camera metadata, anything else), so "모션포토 생성" no longer destroys XMP
     * that was already there.
     *
     * @param existingXmpText The image's existing XMP text, or null if it has none.
     * @param videoOffsetOrLength JPEG: distance in bytes from EOF to the video's first byte.
     *   HEIC: same distance-from-EOF convention as buildGoogleMotionPhotoHeicXmp.
     * @param precedingItemPaddingBytes The byte gap between the end of whichever Directory item
     *   ends up immediately before the new MotionPhoto item, and the start of the video bytes --
     *   same role as buildGoogleMotionPhotoXmp's `primaryPadding`, generalized: when an existing
     *   Directory already has other items (e.g. GainMap), this padding is applied to the item that
     *   was previously last, not necessarily to Primary.
     * @param primaryMimeType Only used when synthesizing a fresh Container:Directory (no existing
     *   one to append to) -- "image/jpeg" or "image/heic".
     */
    internal fun mergeMotionPhotoXmp(
        existingXmpText: String?,
        videoOffsetOrLength: Long,
        precedingItemPaddingBytes: Long,
        presentationTimestampUs: Long,
        version: MotionPhotoFormatVersion,
        primaryMimeType: String,
    ): String {
        val freshBuild = if (primaryMimeType == "image/heic") {
            val hasGainMap = existingXmpText?.contains("GainMap") == true
            buildGoogleMotionPhotoHeicXmp(videoOffsetOrLength, hasGainMap, presentationTimestampUs, version)
        } else {
            buildGoogleMotionPhotoXmp(videoOffsetOrLength, precedingItemPaddingBytes, presentationTimestampUs, version)
        }
        if (existingXmpText == null) return freshBuild

        return try {
            val document = parseXmpDocument(existingXmpText)
            val rdfNs = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
            val descriptions = document.getElementsByTagNameNS(rdfNs, "Description")
            val description = descriptions.item(0) as? org.w3c.dom.Element ?: return freshBuild

            val xmlnsNs = "http://www.w3.org/2000/xmlns/"
            val gCameraNs = "http://ns.google.com/photos/1.0/camera/"
            val containerNs = "http://ns.google.com/photos/1.0/container/"
            val itemNs = "http://ns.google.com/photos/1.0/container/item/"

            fun ensureNamespace(prefix: String, uri: String) {
                if (description.getAttributeNS(xmlnsNs, prefix).isEmpty()) {
                    description.setAttributeNS(xmlnsNs, "xmlns:$prefix", uri)
                }
            }
            ensureNamespace("GCamera", gCameraNs)

            if (version == MotionPhotoFormatVersion.V1_MICRO_VIDEO) {
                description.setAttributeNS(gCameraNs, "GCamera:MicroVideo", "1")
                description.setAttributeNS(gCameraNs, "GCamera:MicroVideoVersion", "1")
                description.setAttributeNS(gCameraNs, "GCamera:MicroVideoOffset", videoOffsetOrLength.toString())
                description.setAttributeNS(gCameraNs, "GCamera:MicroVideoPresentationTimestampUs", presentationTimestampUs.toString())
                return serializeXmpDocument(document)
            }

            description.setAttributeNS(gCameraNs, "GCamera:MotionPhoto", "1")
            description.setAttributeNS(gCameraNs, "GCamera:MotionPhotoVersion", "1")
            description.setAttributeNS(gCameraNs, "GCamera:MotionPhotoPresentationTimestampUs", presentationTimestampUs.toString())
            ensureNamespace("Container", containerNs)
            ensureNamespace("Item", itemNs)

            val existingDirectory = run {
                val children = description.childNodes
                (0 until children.length)
                    .mapNotNull { children.item(it) as? org.w3c.dom.Element }
                    .find { it.namespaceURI == containerNs && it.localName == "Directory" }
            }

            fun newMotionPhotoLi(): org.w3c.dom.Element {
                val li = document.createElementNS(rdfNs, "rdf:li")
                li.setAttributeNS(rdfNs, "rdf:parseType", "Resource")
                val item = document.createElementNS(itemNs, "Container:Item")
                item.setAttributeNS(itemNs, "Item:Mime", "video/mp4")
                item.setAttributeNS(itemNs, "Item:Semantic", "MotionPhoto")
                item.setAttributeNS(itemNs, "Item:Length", videoOffsetOrLength.toString())
                item.setAttributeNS(itemNs, "Item:Padding", "0")
                li.appendChild(item)
                return li
            }

            if (existingDirectory != null) {
                val seq = run {
                    val children = existingDirectory.childNodes
                    (0 until children.length)
                        .mapNotNull { children.item(it) as? org.w3c.dom.Element }
                        .find { it.namespaceURI == rdfNs && it.localName == "Seq" }
                } ?: return freshBuild

                val liElements = run {
                    val children = seq.childNodes
                    (0 until children.length)
                        .mapNotNull { children.item(it) as? org.w3c.dom.Element }
                        .filter { it.namespaceURI == rdfNs && it.localName == "li" }
                }
                val lastLi = liElements.lastOrNull()
                val lastItem = lastLi?.let { li ->
                    val children = li.childNodes
                    (0 until children.length)
                        .mapNotNull { children.item(it) as? org.w3c.dom.Element }
                        .find { it.namespaceURI == itemNs && it.localName == "Item" }
                }
                lastItem?.setAttributeNS(itemNs, "Item:Padding", precedingItemPaddingBytes.toString())

                seq.appendChild(newMotionPhotoLi())
            } else {
                val directory = document.createElementNS(containerNs, "Container:Directory")
                val seq = document.createElementNS(rdfNs, "rdf:Seq")

                val primaryLi = document.createElementNS(rdfNs, "rdf:li")
                primaryLi.setAttributeNS(rdfNs, "rdf:parseType", "Resource")
                val primaryItem = document.createElementNS(itemNs, "Container:Item")
                primaryItem.setAttributeNS(itemNs, "Item:Semantic", "Primary")
                primaryItem.setAttributeNS(itemNs, "Item:Mime", primaryMimeType)
                primaryItem.setAttributeNS(itemNs, "Item:Padding", precedingItemPaddingBytes.toString())
                primaryLi.appendChild(primaryItem)

                seq.appendChild(primaryLi)
                seq.appendChild(newMotionPhotoLi())
                directory.appendChild(seq)
                description.appendChild(directory)
            }

            serializeXmpDocument(document)
        } catch (e: Exception) {
            freshBuild
        }
    }

    private fun serializeXmpDocument(document: org.w3c.dom.Document): String {
        val transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes")
        val writer = java.io.StringWriter()
        transformer.transform(javax.xml.transform.dom.DOMSource(document), javax.xml.transform.stream.StreamResult(writer))
        return writer.toString()
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.MotionPhotoBuilderTest"`
Expected: PASS, all tests.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MotionPhotoBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MotionPhotoBuilderTest.kt
git commit -m "feat: mergeMotionPhotoXmp -- preserve existing XMP instead of replacing it

Adds a shared, format-independent DOM-level merge: motion-photo
GCamera/Container attributes get added onto the image's existing XMP
(if any) instead of building a fresh XMP that discards everything the
original file had -- gain map parameters, camera metadata, anything
else. Handles the case where the existing XMP already has its own
Container:Directory (gain map XMP uses the identical schema): existing
items are preserved verbatim, a MotionPhoto item is appended, and only
the item that was previously last gets its Padding recomputed for the
new video position. Falls back to today's from-scratch XMP build on
any parse failure or unexpected structure -- never blocks motion photo
creation.

Not yet wired into the JPEG/HEIC builders -- that's the next tasks.

See docs/superpowers/specs/2026-09-30-motion-photo-xmp-preservation-design.md"
```

---

### Task 2: JPEG wiring -- capture and merge instead of always building fresh

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MotionPhotoBuilderTest.kt`

**Interfaces:**
- Consumes: Task 1's `mergeMotionPhotoXmp`.
- Produces: `injectMotionPhotoXmpIntoJpeg`'s existing public signature is
  UNCHANGED (still builds/merges internally) -- no caller-visible interface
  change, `createGoogleMotionPhoto` (its only caller) needs no signature
  change either.

- [ ] **Step 1: Write the failing tests**

Add to `app/src/test/kotlin/com/multiviewer/parser/MotionPhotoBuilderTest.kt`:
```kotlin
    @Test
    fun `injectMotionPhotoXmpIntoJpeg merges an existing XMP instead of discarding it`() {
        val existingXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" tiff:Make="SomeCamera"/></rdf:RDF></x:xmpmeta>"""
        val existingXmpApp1 = MotionPhotoBuilder.buildApp1XmpSegment(existingXmp)
        val sampleJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + existingXmpApp1 + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        val injected = MotionPhotoBuilder.injectMotionPhotoXmpIntoJpeg(sampleJpeg, 99999L)
        val injectedText = String(injected, Charsets.UTF_8)

        assertTrue(injectedText.contains("tiff:Make=\"SomeCamera\""), "Expected the original tiff:Make to survive")
        assertTrue(injectedText.contains("GCamera:MotionPhoto=\"1\""))
        // Exactly one XMP APP1 segment in the output -- the old one was replaced, not duplicated
        assertEquals(1, Regex("http://ns\\.adobe\\.com/xap/1\\.0/").findAll(injectedText).count())
    }

    @Test
    fun `injectMotionPhotoXmpIntoJpeg places the merged XMP after Exif when both an existing XMP and Exif are present`() {
        val exifApp1 = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(), 0x00.toByte(), 0x08.toByte(),
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0x00.toByte(), 0x00.toByte(),
        )
        val existingXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" tiff:Make="SomeCamera"/></rdf:RDF></x:xmpmeta>"""
        val existingXmpApp1 = MotionPhotoBuilder.buildApp1XmpSegment(existingXmp)
        val sampleJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + exifApp1 + existingXmpApp1 + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        val injected = MotionPhotoBuilder.injectMotionPhotoXmpIntoJpeg(sampleJpeg, 99999L)

        // Exif is still the very first marker after SOI
        assertEquals(0xFF.toByte(), injected[2])
        assertEquals(0xE1.toByte(), injected[3])
        assertEquals('E'.code.toByte(), injected[6])
        // The merged XMP (not Exif) follows immediately after
        val xmpPos = 2 + exifApp1.size
        assertEquals(0xFF.toByte(), injected[xmpPos])
        assertEquals(0xE1.toByte(), injected[xmpPos + 1])
        val injectedText = String(injected, Charsets.UTF_8)
        assertTrue(injectedText.contains("tiff:Make=\"SomeCamera\""))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.MotionPhotoBuilderTest"`
Expected: FAIL -- `injectMotionPhotoXmpIntoJpeg` still always builds fresh XMP, so `tiff:Make` won't survive.

- [ ] **Step 3: Implement**

In `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoBuilder.kt`, find:
```kotlin
    fun injectMotionPhotoXmpIntoJpeg(
        jpegBytes: ByteArray,
        videoOffsetFromEof: Long,
        primaryPadding: Long = 0L,
        presentationTimestampUs: Long = 1500000L,
        version: MotionPhotoFormatVersion = MotionPhotoFormatVersion.V2_MOTION_PHOTO,
    ): ByteArray {
        require(jpegBytes.size >= 4 && (jpegBytes[0].toInt() and 0xFF) == 0xFF && (jpegBytes[1].toInt() and 0xFF) == 0xD8) {
            "Invalid JPEG bytes: missing SOI marker (0xFFD8)"
        }

        val xmpText = buildGoogleMotionPhotoXmp(videoOffsetFromEof, primaryPadding, presentationTimestampUs, version)
        val app1Segment = buildApp1XmpSegment(xmpText)

        // Check if there is an Exif APP1 immediately after SOI
        var insertPos = 2
        if (jpegBytes.size >= 8 &&
            (jpegBytes[2].toInt() and 0xFF) == 0xFF &&
            (jpegBytes[3].toInt() and 0xFF) == 0xE1
        ) {
            val exifSegLen = ((jpegBytes[4].toInt() and 0xFF) shl 8) or (jpegBytes[5].toInt() and 0xFF)
            val totalExifSegSize = 2 + exifSegLen
            if (insertPos + totalExifSegSize <= jpegBytes.size &&
                insertPos + 4 + EXIF_PREFIX.size <= jpegBytes.size &&
                jpegBytes.copyOfRange(insertPos + 4, insertPos + 4 + EXIF_PREFIX.size).contentEquals(EXIF_PREFIX)
            ) {
                // Keep Exif APP1 as the very first marker after SOI
                insertPos += totalExifSegSize
            }
        }

        val out = ByteArrayOutputStream(jpegBytes.size + app1Segment.size)
        out.write(jpegBytes, 0, insertPos)
        out.write(app1Segment)

        var pos = insertPos
        while (pos < jpegBytes.size) {
            if (pos + 1 >= jpegBytes.size) {
                out.write(jpegBytes, pos, jpegBytes.size - pos)
                break
            }
            val b0 = jpegBytes[pos].toInt() and 0xFF
            val b1 = jpegBytes[pos + 1].toInt() and 0xFF

            if (b0 == 0xFF && b1 == 0xE1 && pos + 4 <= jpegBytes.size) {
                val segLen = ((jpegBytes[pos + 2].toInt() and 0xFF) shl 8) or (jpegBytes[pos + 3].toInt() and 0xFF)
                val totalSegSize = 2 + segLen
                if (pos + totalSegSize <= jpegBytes.size) {
                    val payloadStart = pos + 4
                    val hasXmpPrefix = (pos + totalSegSize >= payloadStart + XMP_IDENTIFIER.size) &&
                        jpegBytes.copyOfRange(payloadStart, payloadStart + XMP_IDENTIFIER.size).contentEquals(XMP_IDENTIFIER)
                    if (hasXmpPrefix) {
                        pos += totalSegSize
                        continue
                    }
                }
            }

            if (b0 == 0xFF && b1 == 0xDA) {
                out.write(jpegBytes, pos, jpegBytes.size - pos)
                break
            }

            if (b0 == 0xFF && (b1 == 0xD9 || (b1 in 0xD0..0xD7) || b1 == 0x01)) {
                out.write(b0)
                out.write(b1)
                pos += 2
                continue
            }

            if (b0 == 0xFF && pos + 4 <= jpegBytes.size) {
                val segLen = ((jpegBytes[pos + 2].toInt() and 0xFF) shl 8) or (jpegBytes[pos + 3].toInt() and 0xFF)
                val totalSegSize = 2 + segLen
                if (pos + totalSegSize <= jpegBytes.size) {
                    out.write(jpegBytes, pos, totalSegSize)
                    pos += totalSegSize
                    continue
                }
            }

            out.write(jpegBytes[pos].toInt() and 0xFF)
            pos++
        }

        return out.toByteArray()
    }
```
Replace with:
```kotlin
    fun injectMotionPhotoXmpIntoJpeg(
        jpegBytes: ByteArray,
        videoOffsetFromEof: Long,
        primaryPadding: Long = 0L,
        presentationTimestampUs: Long = 1500000L,
        version: MotionPhotoFormatVersion = MotionPhotoFormatVersion.V2_MOTION_PHOTO,
    ): ByteArray {
        require(jpegBytes.size >= 4 && (jpegBytes[0].toInt() and 0xFF) == 0xFF && (jpegBytes[1].toInt() and 0xFF) == 0xD8) {
            "Invalid JPEG bytes: missing SOI marker (0xFFD8)"
        }

        // Check if there is an Exif APP1 immediately after SOI
        var insertPos = 2
        if (jpegBytes.size >= 8 &&
            (jpegBytes[2].toInt() and 0xFF) == 0xFF &&
            (jpegBytes[3].toInt() and 0xFF) == 0xE1
        ) {
            val exifSegLen = ((jpegBytes[4].toInt() and 0xFF) shl 8) or (jpegBytes[5].toInt() and 0xFF)
            val totalExifSegSize = 2 + exifSegLen
            if (insertPos + totalExifSegSize <= jpegBytes.size &&
                insertPos + 4 + EXIF_PREFIX.size <= jpegBytes.size &&
                jpegBytes.copyOfRange(insertPos + 4, insertPos + 4 + EXIF_PREFIX.size).contentEquals(EXIF_PREFIX)
            ) {
                // Keep Exif APP1 as the very first marker after SOI
                insertPos += totalExifSegSize
            }
        }

        // First pass: scan for an existing XMP APP1 (anywhere after insertPos) and capture its text,
        // without mutating anything yet -- mergeMotionPhotoXmp needs this before we can build the
        // segment we're about to insert at insertPos.
        var existingXmpText: String? = null
        run {
            var scanPos = insertPos
            while (scanPos < jpegBytes.size - 1) {
                val b0 = jpegBytes[scanPos].toInt() and 0xFF
                val b1 = jpegBytes[scanPos + 1].toInt() and 0xFF
                if (b0 == 0xFF && b1 == 0xDA) break // start of scan data -- no more markers
                if (b0 != 0xFF || scanPos + 4 > jpegBytes.size) break
                val segLen = ((jpegBytes[scanPos + 2].toInt() and 0xFF) shl 8) or (jpegBytes[scanPos + 3].toInt() and 0xFF)
                val totalSegSize = 2 + segLen
                if (scanPos + totalSegSize > jpegBytes.size) break
                if (b1 == 0xE1) {
                    val payloadStart = scanPos + 4
                    val hasXmpPrefix = (scanPos + totalSegSize >= payloadStart + XMP_IDENTIFIER.size) &&
                        jpegBytes.copyOfRange(payloadStart, payloadStart + XMP_IDENTIFIER.size).contentEquals(XMP_IDENTIFIER)
                    if (hasXmpPrefix) {
                        val textStart = payloadStart + XMP_IDENTIFIER.size + 1 // +1 for the NUL after the identifier
                        val textEnd = scanPos + totalSegSize
                        if (textStart in 0..textEnd && textEnd <= jpegBytes.size) {
                            existingXmpText = String(jpegBytes, textStart, textEnd - textStart, Charsets.UTF_8)
                        }
                        break
                    }
                }
                if (b1 in 0xD0..0xD7 || b1 == 0x01) {
                    scanPos += 2
                    continue
                }
                scanPos += totalSegSize
            }
        }

        val xmpText = mergeMotionPhotoXmp(existingXmpText, videoOffsetFromEof, primaryPadding, presentationTimestampUs, version, "image/jpeg")
        val app1Segment = buildApp1XmpSegment(xmpText)

        val out = ByteArrayOutputStream(jpegBytes.size + app1Segment.size)
        out.write(jpegBytes, 0, insertPos)
        out.write(app1Segment)

        var pos = insertPos
        while (pos < jpegBytes.size) {
            if (pos + 1 >= jpegBytes.size) {
                out.write(jpegBytes, pos, jpegBytes.size - pos)
                break
            }
            val b0 = jpegBytes[pos].toInt() and 0xFF
            val b1 = jpegBytes[pos + 1].toInt() and 0xFF

            if (b0 == 0xFF && b1 == 0xE1 && pos + 4 <= jpegBytes.size) {
                val segLen = ((jpegBytes[pos + 2].toInt() and 0xFF) shl 8) or (jpegBytes[pos + 3].toInt() and 0xFF)
                val totalSegSize = 2 + segLen
                if (pos + totalSegSize <= jpegBytes.size) {
                    val payloadStart = pos + 4
                    val hasXmpPrefix = (pos + totalSegSize >= payloadStart + XMP_IDENTIFIER.size) &&
                        jpegBytes.copyOfRange(payloadStart, payloadStart + XMP_IDENTIFIER.size).contentEquals(XMP_IDENTIFIER)
                    if (hasXmpPrefix) {
                        pos += totalSegSize
                        continue
                    }
                }
            }

            if (b0 == 0xFF && b1 == 0xDA) {
                out.write(jpegBytes, pos, jpegBytes.size - pos)
                break
            }

            if (b0 == 0xFF && (b1 == 0xD9 || (b1 in 0xD0..0xD7) || b1 == 0x01)) {
                out.write(b0)
                out.write(b1)
                pos += 2
                continue
            }

            if (b0 == 0xFF && pos + 4 <= jpegBytes.size) {
                val segLen = ((jpegBytes[pos + 2].toInt() and 0xFF) shl 8) or (jpegBytes[pos + 3].toInt() and 0xFF)
                val totalSegSize = 2 + segLen
                if (pos + totalSegSize <= jpegBytes.size) {
                    out.write(jpegBytes, pos, totalSegSize)
                    pos += totalSegSize
                    continue
                }
            }

            out.write(jpegBytes[pos].toInt() and 0xFF)
            pos++
        }

        return out.toByteArray()
    }
```
(The rewrite pass's own `hasXmpPrefix`-skip block is unchanged -- it still
drops the OLD XMP APP1 from its original position, since the new merged
segment was already written at `insertPos`. Only the *new* first pass
(scan-and-capture, no mutation) and the `xmpText`/`app1Segment` construction
changed.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.MotionPhotoBuilderTest"`
Expected: PASS, all tests including the two pre-existing Exif-ordering
tests (`injectMotionPhotoXmpIntoJpeg preserves Exif and places XMP after
Exif` must still pass unchanged -- confirms the Global Constraint).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MotionPhotoBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MotionPhotoBuilderTest.kt
git commit -m "feat: JPEG motion photo creation now merges existing XMP instead of discarding it

injectMotionPhotoXmpIntoJpeg previously always built a fresh,
motion-photo-only XMP regardless of what the original JPEG's XMP
contained. Now captures the existing XMP APP1's text (if any) before
dropping it from its old position, and merges via mergeMotionPhotoXmp
instead of building fresh. Exif-before-XMP ordering is unchanged
(insertPos computation untouched) and pinned with a new test covering
both Exif and an existing XMP present simultaneously.

See docs/superpowers/specs/2026-09-30-motion-photo-xmp-preservation-design.md"
```

---

### Task 3: HEIC — repoint an existing XMP item instead of overwriting in place

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MotionPhotoBuilderTest.kt` (uses Task 5's fixture builder -- **do Task 5 before this task** if executing out of plan order; the task list below is written in dependency order already, but this task's tests reference `HeicMetaFixture`, defined in Task 5.)

**Interfaces:**
- Consumes: Task 1's `mergeMotionPhotoXmp`; Task 5's `HeicMetaFixture`
  (test-only) for constructing a realistic HEIC with one registered XMP
  item.
- Produces: `internal fun repointHeicXmpItem(heicBytes: ByteArray, xmpItemId: Long, ilocEntryOffset: Long, existingExtentCount: Int, mergedXmpBytes: ByteArray): ByteArray?` -- returns `null` when the repoint can't be done cheaply (multi-extent item), signaling the caller to fall back. Consumed by Task 4's wiring into `createSamsungHeicMotionPhoto`.

**Note on execution order:** this task and Task 4 both need Task 5's
fixture. If running tasks strictly in order, do Task 5 (fixture builder,
no dependencies beyond Task 1) immediately after Task 1, then Tasks 2-4.
The numbering here follows the plan's narrative order (JPEG, then HEIC
existing-item, then HEIC new-item, then the fixture used by both HEIC
tasks) but Task 5 has no dependency on Tasks 2-4 and can run third.

- [ ] **Step 1: Write the failing tests**

Add to `app/src/test/kotlin/com/multiviewer/parser/MotionPhotoBuilderTest.kt`:
```kotlin
    @Test
    fun `repointHeicXmpItem rewrites only the target item's iloc entry, nothing else moves`() {
        val existingXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" tiff:Make="SomeCamera"/></rdf:RDF></x:xmpmeta>"""
        val fixture = HeicMetaFixture.build(xmpText = existingXmp)

        val mergedXmpBytes = "<x:xmpmeta>MUCH LONGER MERGED CONTENT THAN THE ORIGINAL SLOT ALLOWED FOR, THIS PROVES REPOINT DOESN'T NEED TO FIT IN PLACE</x:xmpmeta>".toByteArray(Charsets.UTF_8)
        val result = MotionPhotoBuilder.repointHeicXmpItem(
            fixture.heicBytes, fixture.xmpItemId, fixture.xmpIlocEntryOffset, fixture.xmpExtentCount, mergedXmpBytes,
        )
        assertNotNull(result)

        // The primary image item's own extent must resolve to the exact same original bytes --
        // nothing about the file shifted.
        val primaryBytesAfter = result.copyOfRange(
            (fixture.primaryItemOffset).toInt(),
            (fixture.primaryItemOffset + fixture.primaryItemLength).toInt(),
        )
        assertTrue(primaryBytesAfter.contentEquals(fixture.primaryItemBytes), "Primary item's bytes must be unchanged and at the same offset")
        assertEquals(fixture.heicBytes.size, result.size - mergedXmpBytes.size - 8, "File should have grown by exactly the new mdat box (8-byte header + payload)")

        // Re-parse with this app's own HEIC meta/iloc understanding and confirm the XMP item now
        // resolves to the merged bytes.
        val reExtent = MotionPhotoBuilder.findXmpExtentInHeic(result)
        assertNotNull(reExtent)
        val (xmpStart, xmpLen) = reExtent
        val reXmpText = String(result, xmpStart, xmpLen, Charsets.UTF_8)
        assertTrue(reXmpText.contains("MUCH LONGER MERGED CONTENT"))
    }

    @Test
    fun `repointHeicXmpItem converts an idat-relative item to an absolute-offset item`() {
        val existingXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" tiff:Make="SomeCamera"/></rdf:RDF></x:xmpmeta>"""
        val fixture = HeicMetaFixture.build(xmpText = existingXmp, xmpConstructionMethod = 1)

        val mergedXmpBytes = "<x:xmpmeta>merged</x:xmpmeta>".toByteArray(Charsets.UTF_8)
        val result = MotionPhotoBuilder.repointHeicXmpItem(
            fixture.heicBytes, fixture.xmpItemId, fixture.xmpIlocEntryOffset, fixture.xmpExtentCount, mergedXmpBytes,
        )
        assertNotNull(result)
        val reExtent = MotionPhotoBuilder.findXmpExtentInHeic(result)
        assertNotNull(reExtent)
        val (xmpStart, xmpLen) = reExtent
        assertEquals("<x:xmpmeta>merged</x:xmpmeta>", String(result, xmpStart, xmpLen, Charsets.UTF_8))
    }

    @Test
    fun `repointHeicXmpItem returns null for a multi-extent item, signaling fallback`() {
        val fixture = HeicMetaFixture.build(xmpText = "<x:xmpmeta/>", xmpExtentCount = 2)
        val result = MotionPhotoBuilder.repointHeicXmpItem(
            fixture.heicBytes, fixture.xmpItemId, fixture.xmpIlocEntryOffset, fixture.xmpExtentCount, "irrelevant".toByteArray(),
        )
        assertEquals(null, result)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.MotionPhotoBuilderTest"`
Expected: FAIL to compile (`repointHeicXmpItem` and `HeicMetaFixture` don't
exist yet -- do Task 5 first if not already done, then re-run to confirm
this task's 3 new tests specifically fail for the right reason: missing
`repointHeicXmpItem`).

- [ ] **Step 3: Implement**

In `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoBuilder.kt`, add
this function right after `findXmpExtentInHeic` (after its closing `}`,
before the `updateHeicXmpItem` doc comment):
```kotlin
    /**
     * Repoints an existing HEIC XMP item's iloc extent to freshly-appended bytes, instead of
     * overwriting its original byte range in place (which only works when the new content happens
     * to fit in the original allocation -- practically never true once the XMP is being merged
     * rather than replaced). Per ISO/IEC 14496-12 iloc semantics, an item's data extent can point
     * at any absolute offset in the file; there's no requirement it stay where it started. Rewrites
     * only this one item's fixed-width iloc fields (construction_method, base_offset, extent_offset,
     * extent_length) -- same byte count in, same byte count out, so no other box or offset in the
     * file needs to change.
     *
     * @param xmpItemId The XMP item's item_ID (needed only for documentation/assertions -- not used
     *   to re-locate anything, since ilocEntryOffset already pins the exact bytes to rewrite).
     * @param ilocEntryOffset Absolute file offset of this item's fixed-width entry within the iloc
     *   box (construction_method through the start of its extent list).
     * @param existingExtentCount This item's current extent_count -- repoint only handles the
     *   single-extent case (every real-world encoder, and this app's own writer, produces exactly
     *   one extent per XMP item); returns null for anything else so the caller can fall back.
     * @return The rewritten HEIC bytes, or null if this item can't be cheaply repointed (multi-extent).
     */
    internal fun repointHeicXmpItem(
        heicBytes: ByteArray,
        xmpItemId: Long,
        ilocEntryOffset: Long,
        existingExtentCount: Int,
        mergedXmpBytes: ByteArray,
    ): ByteArray? {
        if (existingExtentCount != 1) return null

        val ilocHeader = findIlocHeaderFields(heicBytes) ?: return null
        val (ilocVersion, offSz, lenSz, baseOffSz, indexSz) = ilocHeader

        var fieldPos = ilocEntryOffset.toInt()
        fieldPos += if (ilocVersion < 2) 2 else 4 // skip item_ID
        val constructionMethodFieldPos = if (ilocVersion in 1..2) fieldPos else -1
        if (constructionMethodFieldPos >= 0) fieldPos += 2
        fieldPos += 2 // skip data_reference_index
        val baseOffsetFieldPos = fieldPos
        fieldPos += baseOffSz
        fieldPos += 2 // skip extent_count (unchanged, still 1)
        if (indexSz > 0) fieldPos += indexSz
        val extentOffsetFieldPos = fieldPos
        fieldPos += offSz
        val extentLengthFieldPos = fieldPos

        // New mdat: appended right where the file currently ends.
        val newMdatOffset = heicBytes.size.toLong()
        val newMdatPayloadOffset = newMdatOffset + 8
        val newMdatSize = 8L + mergedXmpBytes.size

        val result = heicBytes.copyOf(heicBytes.size + newMdatSize.toInt())
        val mdatBuf = ByteBuffer.wrap(result, newMdatOffset.toInt(), newMdatSize.toInt()).order(ByteOrder.BIG_ENDIAN)
        mdatBuf.putInt(newMdatSize.toInt())
        mdatBuf.put("mdat".toByteArray(Charsets.US_ASCII))
        mdatBuf.put(mergedXmpBytes)

        if (constructionMethodFieldPos >= 0) {
            writeUIntOfWidth(result, constructionMethodFieldPos.toLong(), 2, 0L)
        }
        writeUIntOfWidth(result, baseOffsetFieldPos.toLong(), baseOffSz, 0L)
        writeUIntOfWidth(result, extentOffsetFieldPos.toLong(), offSz, newMdatPayloadOffset)
        writeUIntOfWidth(result, extentLengthFieldPos.toLong(), lenSz, mergedXmpBytes.size.toLong())

        return result
    }

    private data class IlocHeaderFields(val version: Int, val offsetSize: Int, val lengthSize: Int, val baseOffsetSize: Int, val indexSize: Int)

    /** Locates the meta -> iloc box and reads its FullBox version and offset/length/base_offset/index field widths. */
    private fun findIlocHeaderFields(heicBytes: ByteArray): IlocHeaderFields? {
        var pos = 0
        while (pos < heicBytes.size - 8) {
            val size = ((heicBytes[pos].toLong() and 0xFF) shl 24) or ((heicBytes[pos + 1].toLong() and 0xFF) shl 16) or
                ((heicBytes[pos + 2].toLong() and 0xFF) shl 8) or (heicBytes[pos + 3].toLong() and 0xFF)
            val fourCC = String(heicBytes.copyOfRange(pos + 4, pos + 8), Charsets.US_ASCII)
            val boxLen = if (size == 1L) ByteBuffer.wrap(heicBytes, pos + 8, 8).long else if (size == 0L) (heicBytes.size - pos).toLong() else size
            if (pos + boxLen > heicBytes.size || boxLen < 8) return null

            if (fourCC == "meta") {
                val metaEnd = (pos + boxLen).toInt()
                var mp = pos + 12
                while (mp < metaEnd - 8) {
                    val childSz = ((heicBytes[mp].toInt() and 0xFF) shl 24) or ((heicBytes[mp + 1].toInt() and 0xFF) shl 16) or
                        ((heicBytes[mp + 2].toInt() and 0xFF) shl 8) or (heicBytes[mp + 3].toInt() and 0xFF)
                    val childFourCC = String(heicBytes.copyOfRange(mp + 4, mp + 8), Charsets.US_ASCII)
                    if (childSz < 8 || mp + childSz > metaEnd) return null
                    if (childFourCC == "iloc") {
                        val version = heicBytes[mp + 8].toInt() and 0xFF
                        val offLenSz = heicBytes[mp + 12].toInt() and 0xFF
                        val baseIdxSz = heicBytes[mp + 13].toInt() and 0xFF
                        return IlocHeaderFields(version, offLenSz shr 4, offLenSz and 0x0F, baseIdxSz shr 4, baseIdxSz and 0x0F)
                    }
                    mp += childSz
                }
            }
            pos += boxLen.toInt()
        }
        return null
    }

    /** Writes an unsigned big-endian integer of the given byte width at the given position. widthBytes of 0 is a no-op. */
    private fun writeUIntOfWidth(bytes: ByteArray, offset: Long, widthBytes: Int, value: Long) {
        for (i in 0 until widthBytes) {
            val shift = (widthBytes - 1 - i) * 8
            bytes[(offset + i).toInt()] = ((value shr shift) and 0xFF).toByte()
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.MotionPhotoBuilderTest"`
Expected: PASS, all 3 new tests.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MotionPhotoBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MotionPhotoBuilderTest.kt
git commit -m "feat: repointHeicXmpItem -- relocate an existing HEIC XMP item's data without growing meta

updateHeicXmpItem's overwrite-in-place strategy only works when the
new XMP happens to fit in the original item's allocated extent, which
merged (additive) XMP almost never will. repointHeicXmpItem instead
rewrites only that one item's existing iloc extent fields
(construction_method, base_offset, offset, length -- all fixed-width,
so no box anywhere changes size) to point at a freshly-appended mdat
box holding the new bytes. Reviewed against ISO/IEC 14496-12: an
item's data extent can point at any absolute file offset, so this is
spec-valid and touches nothing else in the file. Not yet wired into
createSamsungHeicMotionPhoto -- that's the next task.

See docs/superpowers/specs/2026-09-30-motion-photo-xmp-preservation-design.md"
```

---

### Task 4: HEIC — create a new XMP item when none exists, and wire both HEIC paths together

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MotionPhotoBuilderTest.kt`

**Interfaces:**
- Consumes: Task 1's `mergeMotionPhotoXmp`, Task 3's `repointHeicXmpItem` +
  `findIlocHeaderFields`/`writeUIntOfWidth` private helpers, Task 5's
  `HeicMetaFixture`.
- Produces: `internal fun createHeicXmpItem(heicBytes: ByteArray, xmpBytes: ByteArray): ByteArray` (always succeeds -- appends, never fails to find a slot since it makes its own). Wires everything into
  `createSamsungHeicMotionPhoto`'s existing call to `updateHeicXmpItem`,
  replacing it.

- [ ] **Step 1: Write the failing tests**

Add to `app/src/test/kotlin/com/multiviewer/parser/MotionPhotoBuilderTest.kt`:
```kotlin
    @Test
    fun `createHeicXmpItem registers a new item without disturbing the existing primary item's bytes`() {
        val fixture = HeicMetaFixture.build(xmpText = null) // no XMP item in this fixture at all

        val newXmpBytes = "<x:xmpmeta>brand new</x:xmpmeta>".toByteArray(Charsets.UTF_8)
        val result = MotionPhotoBuilder.createHeicXmpItem(fixture.heicBytes, newXmpBytes)

        // The primary item's bytes must resolve to the exact same content at the exact same offset
        // as before -- the single most important regression check for this function.
        val primaryBytesAfter = result.copyOfRange(
            fixture.primaryItemOffset.toInt(),
            (fixture.primaryItemOffset + fixture.primaryItemLength).toInt(),
        )
        assertTrue(primaryBytesAfter.contentEquals(fixture.primaryItemBytes), "Primary item's bytes must survive unchanged")

        // Re-parse with this app's own HEIC understanding and find the new XMP item.
        val reExtent = MotionPhotoBuilder.findXmpExtentInHeic(result)
        assertNotNull(reExtent)
        val (xmpStart, xmpLen) = reExtent
        assertEquals("<x:xmpmeta>brand new</x:xmpmeta>", String(result, xmpStart, xmpLen, Charsets.UTF_8))
    }

    @Test
    fun `createHeicXmpItem picks an item_ID that does not collide with any existing item`() {
        val fixture = HeicMetaFixture.build(xmpText = null)
        val newXmpBytes = "<x:xmpmeta/>".toByteArray(Charsets.UTF_8)
        val result = MotionPhotoBuilder.createHeicXmpItem(fixture.heicBytes, newXmpBytes)

        // Re-parsing must still find exactly the primary item plus the new XMP item -- no item_ID
        // collision silently corrupted or hid either one.
        val root = parseFile(File.createTempFile("heic-newitem-", ".heic").apply {
            deleteOnExit()
            writeBytes(result)
        })
        val metaNode = findFirst(root) { it.type == "meta" }
        assertNotNull(metaNode)
        val iinfNode = findFirst(metaNode) { it.type == "iinf" }
        assertNotNull(iinfNode)
        assertEquals(fixture.existingItemCount + 1, iinfNode.children.size, "Expected exactly one new infe entry to be added")
    }

    @Test
    fun `createSamsungHeicMotionPhoto merges existing XMP when present and creates one when absent`() {
        val existingXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" tiff:Make="SomeCamera"/></rdf:RDF></x:xmpmeta>"""
        val fixtureWithXmp = HeicMetaFixture.build(xmpText = existingXmp)
        val imageFile = File.createTempFile("heic-with-xmp-", ".heic")
        imageFile.deleteOnExit()
        imageFile.writeBytes(fixtureWithXmp.heicBytes)

        val videoFile = File.createTempFile("heic-merge-vid-", ".mp4")
        videoFile.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=64x48:rate=10",
            videoFile.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val outputFile = File.createTempFile("heic-merge-out-", ".heic")
        outputFile.deleteOnExit()
        MotionPhotoBuilder.createSamsungHeicMotionPhoto(imageFile, videoFile, outputFile)

        val root = parseFile(outputFile)
        ByteReader.open(outputFile).use { reader ->
            val xmpField = findFirst(root) { it.fields.any { f -> f.name == "xmp" } }
            assertNotNull(xmpField, "Expected the output HEIC to have a discoverable XMP item")
            val xmpValue = xmpField.fields.find { it.name == "xmp" }!!.value
            assertTrue(xmpValue.contains("tiff:Make=\"SomeCamera\""), "Expected the original tiff:Make to survive end-to-end")
            assertTrue(xmpValue.contains("GCamera:MotionPhoto=\"1\""))
        }

        imageFile.delete()
        videoFile.delete()
        outputFile.delete()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.MotionPhotoBuilderTest"`
Expected: FAIL to compile (`createHeicXmpItem` doesn't exist yet).

- [ ] **Step 3: Implement**

In `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoBuilder.kt`, add
this function right after `repointHeicXmpItem`'s private helpers (after
`writeUIntOfWidth`'s closing `}`, before the `updateHeicXmpItem` doc
comment):
```kotlin
    /**
     * Registers a brand-new XMP item in a HEIC file that has none, by appending one infe entry to
     * iinf and one item entry to iloc -- always at the END of each list, never inserted in the
     * middle, so every other existing entry's own bytes never move. Because meta (which contains
     * iinf/iloc) grows, every pre-existing construction_method=0 iloc extent whose absolute offset
     * sits past the old end of meta must have that offset increased by the total growth -- otherwise
     * those items (most importantly the primary image's own pixel data) would point at the wrong
     * bytes. construction_method=1 (idat-relative) entries need no adjustment.
     */
    internal fun createHeicXmpItem(heicBytes: ByteArray, xmpBytes: ByteArray): ByteArray {
        val meta = findMetaBoxBounds(heicBytes) ?: return heicBytes
        val iinf = findChildBoxBounds(heicBytes, meta.payloadStart, meta.end, "iinf") ?: return heicBytes
        val iloc = findChildBoxBounds(heicBytes, meta.payloadStart, meta.end, "iloc") ?: return heicBytes
        val ilocHeader = findIlocHeaderFields(heicBytes) ?: return heicBytes

        val existingItemIds = collectInfeItemIds(heicBytes, iinf)
        val newItemId = (existingItemIds.maxOrNull() ?: 0L) + 1
        val infeVersion = if (newItemId > 0xFFFF) 3 else 2
        val itemIdWidth = if (infeVersion == 2) 2 else 4

        // Build the new infe box: FullBox header(4) + item_ID(itemIdWidth) + item_protection_index(2)
        // + item_type(4) + item_name(NUL) + content_type(NUL) -- item_type="mime", no item_name,
        // content_type="application/rdf+xml".
        val contentType = "application/rdf+xml".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val infePayloadSize = 4 + itemIdWidth + 2 + 4 + 1 /* empty item_name NUL */ + contentType.size
        val infeBoxSize = 8 + infePayloadSize
        val infeBuf = ByteBuffer.allocate(infeBoxSize).order(ByteOrder.BIG_ENDIAN)
        infeBuf.putInt(infeBoxSize)
        infeBuf.put("infe".toByteArray(Charsets.US_ASCII))
        infeBuf.put(infeVersion.toByte())
        infeBuf.put(byteArrayOf(0, 0, 0)) // flags
        if (itemIdWidth == 2) infeBuf.putShort(newItemId.toShort()) else infeBuf.putInt(newItemId.toInt())
        infeBuf.putShort(0) // item_protection_index
        infeBuf.put("mime".toByteArray(Charsets.US_ASCII))
        infeBuf.put(0) // empty item_name, NUL-terminated
        infeBuf.put(contentType)
        val infeBytes = infeBuf.array()

        // New mdat for the XMP bytes -- placed after the (about to be rewritten) base bytes, before
        // mpvd/sefd, mirroring repointHeicXmpItem's convention.
        val baseSizeAfterGrowth = heicBytes.size + infeBytes.size + run {
            // iloc entry size: item_ID + [construction_method if v1/2] + data_reference_index(2)
            // + base_offset(baseOffsetSize) + extent_count(2) + 1 extent * (indexSize + offsetSize + lengthSize)
            val itemIdW = if (ilocHeader.version < 2) 2 else 4
            val constructionMethodW = if (ilocHeader.version in 1..2) 2 else 0
            itemIdW + constructionMethodW + 2 + ilocHeader.baseOffsetSize + 2 + (ilocHeader.indexSize + ilocHeader.offsetSize + ilocHeader.lengthSize)
        }
        val newMdatOffset = baseSizeAfterGrowth.toLong()
        val newMdatPayloadOffset = newMdatOffset + 8
        val newMdatSize = 8L + xmpBytes.size

        // Build the new iloc item entry with those exact same field widths.
        val itemIdW = if (ilocHeader.version < 2) 2 else 4
        val constructionMethodW = if (ilocHeader.version in 1..2) 2 else 0
        val ilocEntrySize = itemIdW + constructionMethodW + 2 + ilocHeader.baseOffsetSize + 2 + (ilocHeader.indexSize + ilocHeader.offsetSize + ilocHeader.lengthSize)
        val ilocEntryBuf = ByteBuffer.allocate(ilocEntrySize).order(ByteOrder.BIG_ENDIAN)
        if (itemIdW == 2) ilocEntryBuf.putShort(newItemId.toShort()) else ilocEntryBuf.putInt(newItemId.toInt())
        if (constructionMethodW == 2) ilocEntryBuf.putShort(0) // construction_method = 0 (absolute offset)
        ilocEntryBuf.putShort(0) // data_reference_index
        putUIntOfWidth(ilocEntryBuf, ilocHeader.baseOffsetSize, 0L) // base_offset
        ilocEntryBuf.putShort(1) // extent_count = 1
        if (ilocHeader.indexSize > 0) putUIntOfWidth(ilocEntryBuf, ilocHeader.indexSize, 0L)
        putUIntOfWidth(ilocEntryBuf, ilocHeader.offsetSize, newMdatPayloadOffset)
        putUIntOfWidth(ilocEntryBuf, ilocHeader.lengthSize, xmpBytes.size.toLong())
        val ilocEntryBytes = ilocEntryBuf.array()

        // Assemble: everything up to iinf's end, then the new infe, then everything from iinf's end
        // to iloc's end, then the new iloc entry, then everything after iloc's end (still within the
        // original file) -- with box-size/count fields patched, and pre-existing absolute offsets
        // past the old meta end shifted by the total growth.
        val growth = (infeBytes.size + ilocEntryBytes.size).toLong()
        val result = ByteArray(heicBytes.size + growth.toInt() + newMdatSize.toInt())
        var w = 0
        fun copyRange(from: Int, to: Int) {
            System.arraycopy(heicBytes, from, result, w, to - from)
            w += (to - from)
        }
        copyRange(0, iinf.end)
        System.arraycopy(infeBytes, 0, result, w, infeBytes.size); w += infeBytes.size
        copyRange(iinf.end, iloc.end)
        System.arraycopy(ilocEntryBytes, 0, result, w, ilocEntryBytes.size); w += ilocEntryBytes.size
        copyRange(iloc.end, heicBytes.size)
        val mdatBuf = ByteBuffer.wrap(result, w, newMdatSize.toInt()).order(ByteOrder.BIG_ENDIAN)
        mdatBuf.putInt(newMdatSize.toInt())
        mdatBuf.put("mdat".toByteArray(Charsets.US_ASCII))
        mdatBuf.put(xmpBytes)
        w += newMdatSize.toInt()

        // Patch box sizes: iinf, iloc, meta all grew by `growth`. iloc's growth is ilocEntryBytes.size
        // only (infe doesn't touch iloc's own size); iinf's growth is infeBytes.size only.
        patchBoxSize(result, iinf.start, infeBytes.size.toLong())
        patchBoxSize(result, iloc.start + infeBytes.size, ilocEntryBytes.size.toLong()) // iloc shifted forward by infeBytes.size once inserted
        patchBoxSize(result, meta.start, growth)
        patchInfeItemCount(result, iinf.start)
        patchIlocItemCount(result, iloc.start + infeBytes.size)

        // Shift every pre-existing construction_method=0 extent whose absolute offset was past the
        // OLD end of meta, by `growth` -- existingItemIds.size is the item count BEFORE the new
        // entry was appended above, so the newly-appended entry (already correct) is never touched.
        shiftAbsoluteIlocOffsetsPastMeta(result, iloc.start + infeBytes.size, meta.end.toLong(), growth, existingItemIds.size)

        return result
    }

    private data class BoxBounds(val start: Int, val payloadStart: Int, val end: Int)

    private fun findMetaBoxBounds(heicBytes: ByteArray): BoxBounds? {
        var pos = 0
        while (pos < heicBytes.size - 8) {
            val size = ((heicBytes[pos].toLong() and 0xFF) shl 24) or ((heicBytes[pos + 1].toLong() and 0xFF) shl 16) or
                ((heicBytes[pos + 2].toLong() and 0xFF) shl 8) or (heicBytes[pos + 3].toLong() and 0xFF)
            val fourCC = String(heicBytes.copyOfRange(pos + 4, pos + 8), Charsets.US_ASCII)
            val boxLen = if (size == 1L) ByteBuffer.wrap(heicBytes, pos + 8, 8).long else if (size == 0L) (heicBytes.size - pos).toLong() else size
            if (pos + boxLen > heicBytes.size || boxLen < 8) return null
            if (fourCC == "meta") return BoxBounds(pos, pos + 12, (pos + boxLen).toInt())
            pos += boxLen.toInt()
        }
        return null
    }

    private fun findChildBoxBounds(heicBytes: ByteArray, parentPayloadStart: Int, parentEnd: Int, fourCCTarget: String): BoxBounds? {
        var mp = parentPayloadStart
        while (mp < parentEnd - 8) {
            val childSz = ((heicBytes[mp].toInt() and 0xFF) shl 24) or ((heicBytes[mp + 1].toInt() and 0xFF) shl 16) or
                ((heicBytes[mp + 2].toInt() and 0xFF) shl 8) or (heicBytes[mp + 3].toInt() and 0xFF)
            val childFourCC = String(heicBytes.copyOfRange(mp + 4, mp + 8), Charsets.US_ASCII)
            if (childSz < 8 || mp + childSz > parentEnd) return null
            if (childFourCC == fourCCTarget) return BoxBounds(mp, mp + 8, mp + childSz)
            mp += childSz
        }
        return null
    }

    /** Reads every infe child's item_ID inside an iinf box. */
    private fun collectInfeItemIds(heicBytes: ByteArray, iinf: BoxBounds): List<Long> {
        val ids = mutableListOf<Long>()
        var mp = iinf.payloadStart + 4 // skip iinf's own FullBox header (version+flags) + entry_count... entry_count read below
        // iinf FullBox header: version(1)+flags(3) at payloadStart, entry_count at payloadStart+4 (2 or 4 bytes by version)
        val iinfVersion = heicBytes[iinf.payloadStart].toInt() and 0xFF
        val entryCountWidth = if (iinfVersion == 0) 2 else 4
        mp = iinf.payloadStart + 4 + entryCountWidth
        while (mp < iinf.end - 8) {
            val childSz = ((heicBytes[mp].toInt() and 0xFF) shl 24) or ((heicBytes[mp + 1].toInt() and 0xFF) shl 16) or
                ((heicBytes[mp + 2].toInt() and 0xFF) shl 8) or (heicBytes[mp + 3].toInt() and 0xFF)
            if (childSz < 8 || mp + childSz > iinf.end) break
            val infeVersion = heicBytes[mp + 8].toInt() and 0xFF
            val itemIdWidth = if (infeVersion == 2) 2 else 4
            val itemIdOffset = mp + 12
            val itemId = if (itemIdWidth == 2) {
                ((heicBytes[itemIdOffset].toLong() and 0xFF) shl 8) or (heicBytes[itemIdOffset + 1].toLong() and 0xFF)
            } else {
                ((heicBytes[itemIdOffset].toLong() and 0xFF) shl 24) or ((heicBytes[itemIdOffset + 1].toLong() and 0xFF) shl 16) or
                    ((heicBytes[itemIdOffset + 2].toLong() and 0xFF) shl 8) or (heicBytes[itemIdOffset + 3].toLong() and 0xFF)
            }
            ids.add(itemId)
            mp += childSz
        }
        return ids
    }

    private fun putUIntOfWidth(buf: ByteBuffer, widthBytes: Int, value: Long) {
        for (i in 0 until widthBytes) {
            val shift = (widthBytes - 1 - i) * 8
            buf.put(((value shr shift) and 0xFF).toByte())
        }
    }

    /** Adds `delta` to the 4-byte big-endian size field at the start of the box at `boxStart`. */
    private fun patchBoxSize(bytes: ByteArray, boxStart: Int, delta: Long) {
        val current = ((bytes[boxStart].toLong() and 0xFF) shl 24) or ((bytes[boxStart + 1].toLong() and 0xFF) shl 16) or
            ((bytes[boxStart + 2].toLong() and 0xFF) shl 8) or (bytes[boxStart + 3].toLong() and 0xFF)
        writeUIntOfWidth(bytes, boxStart.toLong(), 4, current + delta)
    }

    /** Increments iinf's entry_count field by 1. */
    private fun patchInfeItemCount(bytes: ByteArray, iinfStart: Int) {
        val payloadStart = iinfStart + 8
        val version = bytes[payloadStart].toInt() and 0xFF
        val countWidth = if (version == 0) 2 else 4
        val countOffset = (payloadStart + 4).toLong()
        val current = if (countWidth == 2) {
            ((bytes[countOffset.toInt()].toLong() and 0xFF) shl 8) or (bytes[countOffset.toInt() + 1].toLong() and 0xFF)
        } else {
            ((bytes[countOffset.toInt()].toLong() and 0xFF) shl 24) or ((bytes[countOffset.toInt() + 1].toLong() and 0xFF) shl 16) or
                ((bytes[countOffset.toInt() + 2].toLong() and 0xFF) shl 8) or (bytes[countOffset.toInt() + 3].toLong() and 0xFF)
        }
        writeUIntOfWidth(bytes, countOffset, countWidth, current + 1)
    }

    /** Increments iloc's item_count field by 1. */
    private fun patchIlocItemCount(bytes: ByteArray, ilocStart: Int) {
        val payloadStart = ilocStart + 8
        val version = bytes[payloadStart].toInt() and 0xFF
        val countWidth = if (version < 2) 2 else 4
        val countOffset = (payloadStart + 6).toLong()
        val current = if (countWidth == 2) {
            ((bytes[countOffset.toInt()].toLong() and 0xFF) shl 8) or (bytes[countOffset.toInt() + 1].toLong() and 0xFF)
        } else {
            ((bytes[countOffset.toInt()].toLong() and 0xFF) shl 24) or ((bytes[countOffset.toInt() + 1].toLong() and 0xFF) shl 16) or
                ((bytes[countOffset.toInt() + 2].toLong() and 0xFF) shl 8) or (bytes[countOffset.toInt() + 3].toLong() and 0xFF)
        }
        writeUIntOfWidth(bytes, countOffset, countWidth, current + 1)
    }

    /**
     * Walks the first `existingItemCount` item entries in iloc (using the CURRENT, already-grown
     * iloc box at ilocStart -- but only the entries that existed BEFORE this growth) and adds
     * `delta` to any construction_method=0 extent whose absolute offset was >= `oldMetaEnd` (i.e.
     * it pointed past where meta used to end, before this growth).
     *
     * `existingItemCount` MUST be the item count from before the new item was appended -- the
     * caller must capture it before calling patchIlocItemCount/appending the new entry. This is
     * deliberately NOT read from iloc's own (already-incremented) item_count field: this function
     * runs AFTER the new entry has already been appended with its correct, final, post-growth
     * offset, and iloc's entries are stored in a flat list where "the last entry" is only knowable
     * by position, not by any per-entry marker -- if this walked the incremented item_count instead
     * of the caller-supplied `existingItemCount`, it would also visit the newly-appended entry and
     * add `delta` to its already-correct offset a second time, corrupting the very entry
     * createHeicXmpItem/repointHeicXmpItem just created. The test in Step 1
     * (`createHeicXmpItem registers a new item without disturbing the existing primary item's
     * bytes`) re-parses the new XMP item's own resolved bytes and would fail if this were wrong,
     * but it would NOT catch a double-shift of the new entry specifically (its bytes are appended
     * fresh regardless of the extent_offset value used to find them, only the read-back would
     * silently read from the wrong place if this were broken and happened to still resolve to
     * something parseable) -- rely on the explicit `existingItemCount` contract, not on that test
     * alone, when reviewing this function.
     */
    private fun shiftAbsoluteIlocOffsetsPastMeta(bytes: ByteArray, ilocStart: Int, oldMetaEnd: Long, delta: Long, existingItemCount: Int) {
        val header = findIlocHeaderFields(bytes) ?: return
        val payloadStart = ilocStart + 8
        val itemCountWidth = if (header.version < 2) 2 else 4
        var pos = payloadStart + 6 + itemCountWidth
        val itemCount = existingItemCount
        val itemIdWidth = if (header.version < 2) 2 else 4
        val constructionMethodWidth = if (header.version in 1..2) 2 else 0
        for (i in 0 until itemCount) {
            pos += itemIdWidth
            val constructionMethod = if (constructionMethodWidth > 0) {
                (((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)) and 0xF
            } else {
                0
            }
            pos += constructionMethodWidth
            pos += 2 // data_reference_index
            pos += header.baseOffsetSize
            val extentCount = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            pos += 2
            for (e in 0 until extentCount) {
                if (header.indexSize > 0) pos += header.indexSize
                val offsetFieldPos = pos
                if (constructionMethod == 0) {
                    var current = 0L
                    for (b in 0 until header.offsetSize) current = (current shl 8) or (bytes[offsetFieldPos + b].toLong() and 0xFF)
                    if (current >= oldMetaEnd) {
                        writeUIntOfWidth(bytes, offsetFieldPos.toLong(), header.offsetSize, current + delta)
                    }
                }
                pos += header.offsetSize
                pos += header.lengthSize
            }
        }
    }
```

- [ ] **Step 4: Wire `createSamsungHeicMotionPhoto` to use both new functions**

Find:
```kotlin
        // 7. Update XMP item in iloc with video offset from EOF: videoLength + sefdBoxSize and duration
        val videoOffsetFromEof = videoLength + sefdBoxSize
        val videoDurationUs = extractVideoDurationUs(videoFile)
        val syncTimestampUs = resolvePresentationTimestampUs(presentationTimestampUs, videoDurationUs)
        val updatedBaseHeicBytes = updateHeicXmpItem(baseHeicBytes, videoOffsetFromEof, syncTimestampUs, version)
```
Replace with:
```kotlin
        // 7. Merge or create the XMP item with video offset from EOF: videoLength + sefdBoxSize and duration
        val videoOffsetFromEof = videoLength + sefdBoxSize
        val videoDurationUs = extractVideoDurationUs(videoFile)
        val syncTimestampUs = resolvePresentationTimestampUs(presentationTimestampUs, videoDurationUs)
        val existingXmpExtent = findXmpExtentInHeic(baseHeicBytes)
        val updatedBaseHeicBytes = if (existingXmpExtent != null) {
            val (xmpStart, xmpLen) = existingXmpExtent
            val existingXmpText = String(baseHeicBytes, xmpStart, xmpLen, Charsets.UTF_8).trimEnd(' ', ' ', '\n', '\r')
            val itemLocation = findHeicXmpIlocEntry(baseHeicBytes, xmpStart, xmpLen)
            val mergedXmpBytes = mergeMotionPhotoXmp(existingXmpText, videoOffsetFromEof, 0L, syncTimestampUs, version, "image/heic").toByteArray(Charsets.UTF_8)
            val repointed = itemLocation?.let { (itemId, entryOffset, extentCount) ->
                repointHeicXmpItem(baseHeicBytes, itemId, entryOffset, extentCount, mergedXmpBytes)
            }
            repointed ?: createHeicXmpItem(baseHeicBytes, mergeMotionPhotoXmp(null, videoOffsetFromEof, 0L, syncTimestampUs, version, "image/heic").toByteArray(Charsets.UTF_8))
        } else {
            val freshXmpBytes = mergeMotionPhotoXmp(null, videoOffsetFromEof, 0L, syncTimestampUs, version, "image/heic").toByteArray(Charsets.UTF_8)
            createHeicXmpItem(baseHeicBytes, freshXmpBytes)
        }
```

Then add this new helper right after `findXmpExtentInHeic`'s closing `}`
(before `repointHeicXmpItem`, which Task 3 placed there -- this function
comes first alphabetically-by-dependency, add it immediately above
`repointHeicXmpItem`):
```kotlin
    /**
     * Given an XMP item's already-resolved data extent (from findXmpExtentInHeic), re-walks meta ->
     * iloc to find that same item's item_ID, the byte offset of its fixed-width entry within iloc,
     * and its extent_count -- the inputs repointHeicXmpItem needs. Returns null if the structured
     * walk fails (mirrors findXmpExtentInHeic's own fallback-to-pattern-scan case, where there's no
     * structured iloc entry to repoint at all -- callers should fall back to createHeicXmpItem).
     */
    internal fun findHeicXmpIlocEntry(heicBytes: ByteArray, resolvedXmpOffset: Int, resolvedXmpLength: Int): Triple<Long, Long, Int>? {
        try {
            var pos = 0
            while (pos < heicBytes.size - 8) {
                val size = ((heicBytes[pos].toLong() and 0xFF) shl 24) or ((heicBytes[pos + 1].toLong() and 0xFF) shl 16) or
                    ((heicBytes[pos + 2].toLong() and 0xFF) shl 8) or (heicBytes[pos + 3].toLong() and 0xFF)
                val fourCC = String(heicBytes.copyOfRange(pos + 4, pos + 8), Charsets.US_ASCII)
                val boxLen = if (size == 1L) ByteBuffer.wrap(heicBytes, pos + 8, 8).long else if (size == 0L) (heicBytes.size - pos).toLong() else size
                if (pos + boxLen > heicBytes.size || boxLen < 8) break

                if (fourCC == "meta") {
                    val metaEnd = (pos + boxLen).toInt()
                    var mp = pos + 12
                    while (mp < metaEnd - 8) {
                        val childSz = ((heicBytes[mp].toInt() and 0xFF) shl 24) or ((heicBytes[mp + 1].toInt() and 0xFF) shl 16) or
                            ((heicBytes[mp + 2].toInt() and 0xFF) shl 8) or (heicBytes[mp + 3].toInt() and 0xFF)
                        val childFourCC = String(heicBytes.copyOfRange(mp + 4, mp + 8), Charsets.US_ASCII)
                        if (childSz < 8 || mp + childSz > metaEnd) break

                        if (childFourCC == "iloc") {
                            val ilocVersion = heicBytes[mp + 8].toInt() and 0xFF
                            val offLenSz = heicBytes[mp + 12].toInt() and 0xFF
                            val baseIdxSz = heicBytes[mp + 13].toInt() and 0xFF
                            val offSz = offLenSz shr 4
                            val lenSz = offLenSz and 0x0F
                            val baseOffSz = baseIdxSz shr 4
                            val idxSz = baseIdxSz and 0x0F

                            var lp = mp + (if (ilocVersion < 2) 16 else 18)
                            val itemCount = if (ilocVersion < 2) {
                                ((heicBytes[mp + 14].toInt() and 0xFF) shl 8) or (heicBytes[mp + 15].toInt() and 0xFF)
                            } else {
                                ((heicBytes[mp + 14].toInt() and 0xFF) shl 24) or ((heicBytes[mp + 15].toInt() and 0xFF) shl 16) or
                                    ((heicBytes[mp + 16].toInt() and 0xFF) shl 8) or (heicBytes[mp + 17].toInt() and 0xFF)
                            }
                            val itemIdWidth = if (ilocVersion < 2) 2 else 4
                            val constructionMethodWidth = if (ilocVersion in 1..2) 2 else 0

                            for (i in 0 until itemCount) {
                                val entryStart = lp
                                val itemId = if (itemIdWidth == 2) {
                                    ((heicBytes[lp].toLong() and 0xFF) shl 8) or (heicBytes[lp + 1].toLong() and 0xFF)
                                } else {
                                    ((heicBytes[lp].toLong() and 0xFF) shl 24) or ((heicBytes[lp + 1].toLong() and 0xFF) shl 16) or
                                        ((heicBytes[lp + 2].toLong() and 0xFF) shl 8) or (heicBytes[lp + 3].toLong() and 0xFF)
                                }
                                lp += itemIdWidth
                                lp += constructionMethodWidth
                                lp += 2 // data_reference_index
                                var baseOffset = 0L
                                for (b in 0 until baseOffSz) { baseOffset = (baseOffset shl 8) or (heicBytes[lp].toLong() and 0xFF); lp++ }
                                val extentCount = ((heicBytes[lp].toInt() and 0xFF) shl 8) or (heicBytes[lp + 1].toInt() and 0xFF)
                                lp += 2
                                for (e in 0 until extentCount) {
                                    if (idxSz > 0) lp += idxSz
                                    var extentOffset = 0L
                                    for (b in 0 until offSz) { extentOffset = (extentOffset shl 8) or (heicBytes[lp].toLong() and 0xFF); lp++ }
                                    var extentLength = 0L
                                    for (b in 0 until lenSz) { extentLength = (extentLength shl 8) or (heicBytes[lp].toLong() and 0xFF); lp++ }
                                    val absOffset = (baseOffset + extentOffset).toInt()
                                    if (absOffset == resolvedXmpOffset && extentLength.toInt() == resolvedXmpLength) {
                                        return Triple(itemId, entryStart.toLong(), extentCount)
                                    }
                                }
                            }
                        }
                        mp += childSz
                    }
                }
                pos += boxLen.toInt()
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.MotionPhotoBuilderTest"`
Expected: PASS, all tests including the pre-existing
`createSamsungHeicMotionPhoto merges real HEIC image and video...` test
(uses the old no-meta-box fixture, exercises the `createHeicXmpItem`
path via `findMetaBoxBounds` returning null → early return of unchanged
bytes -- confirm this still passes; if `findMetaBoxBounds` returns null
for that fixture, `createHeicXmpItem` must return `heicBytes` unchanged
rather than throwing, matching its `?: return heicBytes` guards).

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MotionPhotoBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MotionPhotoBuilderTest.kt
git commit -m "feat: createHeicXmpItem -- register a new XMP item when a HEIC has none

Previously, a HEIC with no existing XMP item got NO Google Motion
Photo XMP at all when 모션포토 생성 ran (updateHeicXmpItem silently
gave up) -- the JPEG path always creates fresh XMP unconditionally, so
this was a real HEIC-only asymmetry. Appends one new infe entry to
iinf and one new iloc item entry to iloc (always at the end of each
list, never inserted in the middle), grows iinf/iloc/meta's box sizes
by exactly that delta, and shifts every pre-existing
construction_method=0 iloc extent whose offset sat past the old end of
meta by that same delta -- so the primary image's own pixel data still
resolves to the exact same bytes after the insertion.

Wires both repointHeicXmpItem (existing item) and createHeicXmpItem
(no existing item) into createSamsungHeicMotionPhoto, replacing the
old updateHeicXmpItem call.

See docs/superpowers/specs/2026-09-30-motion-photo-xmp-preservation-design.md"
```

---

### Task 5: Realistic HEIC test fixture builder

**Files:**
- Create: `app/src/test/kotlin/com/multiviewer/parser/HeicMetaFixtureTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks (pure byte-construction, can be
  implemented independently -- see the execution-order note in Task 3).
- Produces: `object HeicMetaFixture` with a `build(...)` function and a
  `HeicMetaFixture.Result` data class, consumed by Tasks 3 and 4's tests.

- [ ] **Step 1: Write the failing test (a self-test of the fixture builder itself)**

Create `app/src/test/kotlin/com/multiviewer/parser/HeicMetaFixtureTest.kt`:
```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeicMetaFixtureTest {
    @Test
    fun `HeicMetaFixture builds a HEIC this app's own parser can read`() {
        val fixture = HeicMetaFixture.build(xmpText = "<x:xmpmeta>fixture self-test</x:xmpmeta>")

        val tmp = java.io.File.createTempFile("heic-fixture-selftest-", ".heic")
        tmp.deleteOnExit()
        tmp.writeBytes(fixture.heicBytes)

        val root = parseFile(tmp)
        val metaNode = findFirst(root) { it.type == "meta" }
        assertTrue(metaNode != null, "Expected this app's parser to find a meta box")

        val xmpField = findFirst(root) { it.fields.any { f -> f.name == "xmp" } }
        assertTrue(xmpField != null, "Expected this app's parser to find the fixture's XMP")
        assertEquals("<x:xmpmeta>fixture self-test</x:xmpmeta>", xmpField.fields.find { it.name == "xmp" }!!.value.trimEnd(' ', ' '))

        val primaryBytesInFile = fixture.heicBytes.copyOfRange(fixture.primaryItemOffset.toInt(), (fixture.primaryItemOffset + fixture.primaryItemLength).toInt())
        assertTrue(primaryBytesInFile.contentEquals(fixture.primaryItemBytes), "Fixture's own recorded primaryItemBytes must match what's actually at primaryItemOffset")

        tmp.delete()
    }

    @Test
    fun `HeicMetaFixture with xmpText null omits the XMP item entirely`() {
        val fixture = HeicMetaFixture.build(xmpText = null)
        assertEquals(1, fixture.existingItemCount, "Expected only the primary item, no XMP item")

        val tmp = java.io.File.createTempFile("heic-fixture-noxmp-", ".heic")
        tmp.deleteOnExit()
        tmp.writeBytes(fixture.heicBytes)
        val root = parseFile(tmp)
        val xmpField = findFirst(root) { it.fields.any { f -> f.name == "xmp" } }
        assertEquals(null, xmpField)
        tmp.delete()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.multiviewer.parser.HeicMetaFixtureTest"`
Expected: FAIL to compile (`HeicMetaFixture` doesn't exist yet).

- [ ] **Step 3: Implement the fixture builder**

Create `app/src/main/kotlin/com/multiviewer/parser/HeicMetaFixture.kt` --
note this lives in `main`, not `test`, even though it's test-support code,
because `MotionPhotoBuilderTest.kt` (in the same module's test source set)
needs to reference it and Kotlin test sources in this project's Gradle
setup can freely reference other test-source-set files in the same
package; putting it in `main` keeps it out of the shipped app's
public-facing code path by using `internal` visibility while still being
trivially importable from test code in the same module. (If the
implementer finds this project's Gradle test-source-set wiring already
allows one test file to reference another cleanly without this, placing
`HeicMetaFixture` directly in `app/src/test/kotlin/com/multiviewer/parser/`
alongside `HeicMetaFixtureTest.kt` is equally correct and simpler --
prefer that if it works, matching this codebase's existing preference for
keeping test-only code in the test source set. Check by seeing whether
`SefIntegrityAnalyzerTest.kt`'s file-private helpers are usable from
`MotionPhotoIntegrityAnalyzerTest.kt` in a different package -- if this
project's build does NOT require that kind of cross-file test-package
sharing to work, put `HeicMetaFixture` in the test source set.)

```kotlin
package com.multiviewer.parser

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a minimal but structurally realistic HEIC byte sequence -- ftyp + meta(hdlr, pitm,
 * iinf[+infe], iloc[+item entries], optionally one for XMP) + mdat(primary item bytes) -- for
 * testing repointHeicXmpItem/createHeicXmpItem against something closer to a real file than
 * MotionPhotoBuilderTest's original bare [ftyp][mdat] fixture (which has no meta/iinf/iloc at all).
 */
internal object HeicMetaFixture {
    data class Result(
        val heicBytes: ByteArray,
        val xmpItemId: Long,
        val xmpIlocEntryOffset: Long,
        val xmpExtentCount: Int,
        val primaryItemOffset: Long,
        val primaryItemLength: Long,
        val primaryItemBytes: ByteArray,
        val existingItemCount: Int,
    )

    private const val PRIMARY_ITEM_ID = 1L
    private const val XMP_ITEM_ID = 2L

    fun build(xmpText: String?, xmpConstructionMethod: Int = 0, xmpExtentCount: Int = 1): Result {
        val primaryItemBytes = ByteArray(16) { (it + 1).toByte() }

        val ftyp = byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            0x66, 0x74, 0x79, 0x70, // ftyp
            0x6d, 0x69, 0x66, 0x31, // mif1
            0x00, 0x00, 0x00, 0x00,
            0x6d, 0x69, 0x66, 0x31,
            0x68, 0x65, 0x69, 0x63, // heic
        )

        // mdat holds: [primary item bytes][xmp bytes if idat-relative is NOT used -- we always use
        // construction_method=0 for both items in this fixture, absolute-offset, for simplicity].
        val xmpBytes = xmpText?.toByteArray(Charsets.UTF_8)
        val mdatPayload = primaryItemBytes + (xmpBytes ?: ByteArray(0))
        val mdatSize = 8 + mdatPayload.size

        // Item offsets are absolute file offsets; mdat's payload starts right after ftyp + meta +
        // mdat's own 8-byte header. We need meta's total size before we can compute this, so build
        // meta first with placeholder offsets, then patch once meta's real size is known -- simpler:
        // compute meta's bytes first (offsets inside iloc reference the eventual mdat payload start,
        // which we can compute analytically since ftyp/meta sizes are deterministic given a fixed
        // item count).

        val itemCount = if (xmpText != null) 2 else 1
        // iloc header (12) + item entries. Each entry (version=1, offsetSize=4,lengthSize=4,baseOffsetSize=0,indexSize=0):
        // item_ID(2) + construction_method(2) + data_reference_index(2) + extent_count(2) + 1*(offset(4)+length(4)) = 16 bytes/entry
        val ilocEntrySize = 16
        val ilocPayloadSize = 8 + itemCount * ilocEntrySize // 2(version/flags-derived: 1 byte version+3 flags+1 sizes byte+1 sizes byte+2 item_count) -- see below for exact layout
        // Exact iloc payload layout (version=1): version(1)+flags(3)+offset/length sizes(1)+base/index sizes(1)+item_count(2) = 8 bytes header, then itemCount*16.
        val ilocBoxSize = 8 + ilocPayloadSize
        val iinfEntrySize = { contentTypeLen: Int -> 8 + 4 + 2 + 2 + 4 + 1 + contentTypeLen + 1 } // infe box: header(8)+FullBox(4)+item_ID(2,v2)+protidx(2)+type(4)+name NUL(1)+content_type+NUL
        val primaryInfeSize = 8 + 4 + 2 + 2 + 4 + 1 // item_type="hvc1" or similar, no content_type needed (not mime) -- name empty
        val xmpInfeSize = if (xmpText != null) iinfEntrySize("application/rdf+xml".length) else 0
        val iinfPayloadSize = 6 + primaryInfeSize + xmpInfeSize // FullBox(4)+entry_count(2) = 6
        val iinfBoxSize = 8 + iinfPayloadSize

        val hdlrBox = buildHdlrBox()
        val pitmBox = buildPitmBox(PRIMARY_ITEM_ID)

        val metaPayloadSize = 4 /* FullBox */ + hdlrBox.size + pitmBox.size + iinfBoxSize + ilocBoxSize
        val metaBoxSize = 8 + metaPayloadSize

        val mdatOffset = ftyp.size + metaBoxSize
        val mdatPayloadOffset = mdatOffset + 8
        val primaryItemOffset = mdatPayloadOffset.toLong()
        val xmpItemOffset = if (xmpText != null) primaryItemOffset + primaryItemBytes.size else -1L

        // Now build iinf with real content_type/name.
        val iinfOut = ByteArrayOutputStream()
        run {
            val payload = ByteArrayOutputStream()
            payload.write(byteArrayOf(0, 0, 0, 0)) // FullBox version=0, flags=0
            writeU16(payload, itemCount)
            payload.write(buildInfeBox(PRIMARY_ITEM_ID, "hvc1", null))
            if (xmpText != null) payload.write(buildInfeBox(XMP_ITEM_ID, "mime", "application/rdf+xml"))
            val payloadBytes = payload.toByteArray()
            writeU32(iinfOut, 8 + payloadBytes.size)
            iinfOut.write("iinf".toByteArray(Charsets.US_ASCII))
            iinfOut.write(payloadBytes)
        }
        val iinfBytes = iinfOut.toByteArray()

        val ilocOut = ByteArrayOutputStream()
        val xmpIlocEntryOffsetHolder = LongArray(1)
        run {
            val payload = ByteArrayOutputStream()
            payload.write(1) // version = 1 (supports construction_method)
            payload.write(byteArrayOf(0, 0, 0)) // flags
            payload.write(0x44) // offset_size=4, length_size=4
            payload.write(0x00) // base_offset_size=0, index_size=0
            writeU16(payload, itemCount)

            fun writeEntry(itemId: Long, constructionMethod: Int, offset: Long, length: Long, extentCount: Int) {
                writeU16(payload, itemId.toInt())
                writeU16(payload, constructionMethod)
                writeU16(payload, 0) // data_reference_index
                writeU16(payload, extentCount)
                for (e in 0 until extentCount) {
                    writeU32(payload, offset.toInt())
                    writeU32(payload, length.toInt())
                }
            }
            writeEntry(PRIMARY_ITEM_ID, 0, primaryItemOffset, primaryItemBytes.size.toLong(), 1)
            if (xmpText != null) {
                // Record this entry's byte offset relative to iloc's own BOX start (i.e. including
                // iloc's own 8-byte size+fourcc header, which isn't part of `payload` here) -- fixed
                // up to an absolute file offset below, once ilocBytes' final size is known.
                xmpIlocEntryOffsetHolder[0] = (8 /* iloc box header */ + payload.size()).toLong()
                val perExtentLen = if (xmpExtentCount > 1) (xmpBytes!!.size / xmpExtentCount) else xmpBytes!!.size
                writeEntry(XMP_ITEM_ID, xmpConstructionMethod, xmpItemOffset, perExtentLen.toLong(), xmpExtentCount)
            }
            val payloadBytes = payload.toByteArray()
            writeU32(ilocOut, 8 + payloadBytes.size)
            ilocOut.write("iloc".toByteArray(Charsets.US_ASCII))
            ilocOut.write(payloadBytes)
        }
        val ilocBytes = ilocOut.toByteArray()

        val metaOut = ByteArrayOutputStream()
        writeU32(metaOut, 8 + 4 + hdlrBox.size + pitmBox.size + iinfBytes.size + ilocBytes.size)
        metaOut.write("meta".toByteArray(Charsets.US_ASCII))
        metaOut.write(byteArrayOf(0, 0, 0, 0)) // FullBox version/flags
        metaOut.write(hdlrBox)
        metaOut.write(pitmBox)
        metaOut.write(iinfBytes)
        metaOut.write(ilocBytes)
        val metaBytes = metaOut.toByteArray()
        check(metaBytes.size == metaBoxSize) { "Fixture internal size mismatch: computed $metaBoxSize, built ${metaBytes.size}" }

        val mdatOut = ByteArrayOutputStream()
        writeU32(mdatOut, mdatSize)
        mdatOut.write("mdat".toByteArray(Charsets.US_ASCII))
        mdatOut.write(mdatPayload)

        val allBytes = ftyp + metaBytes + mdatOut.toByteArray()

        // iloc is the last child written into meta (hdlr, pitm, iinf, iloc in that order -- see the
        // metaOut assembly above), so iloc's box start is exactly metaBytes' end minus iloc's own size.
        val ilocAbsoluteStart = (ftyp.size + metaBoxSize - ilocBytes.size).toLong()

        return Result(
            heicBytes = allBytes,
            xmpItemId = XMP_ITEM_ID,
            // xmpIlocEntryOffsetHolder[0] is already relative to iloc's own box start (see the
            // comment where it's recorded above); adding ilocAbsoluteStart converts it to an
            // absolute file offset. No further adjustment.
            xmpIlocEntryOffset = ilocAbsoluteStart + xmpIlocEntryOffsetHolder[0],
            xmpExtentCount = xmpExtentCount,
            primaryItemOffset = primaryItemOffset,
            primaryItemLength = primaryItemBytes.size.toLong(),
            primaryItemBytes = primaryItemBytes,
            existingItemCount = itemCount,
        )
    }

    private fun buildHdlrBox(): ByteArray {
        val out = ByteArrayOutputStream()
        val handlerName = " "
        val payload = ByteArrayOutputStream()
        payload.write(byteArrayOf(0, 0, 0, 0)) // FullBox
        payload.write(byteArrayOf(0, 0, 0, 0)) // pre_defined
        payload.write("pict".toByteArray(Charsets.US_ASCII)) // handler_type
        payload.write(ByteArray(12)) // reserved
        payload.write(handlerName.toByteArray(Charsets.US_ASCII))
        val payloadBytes = payload.toByteArray()
        writeU32(out, 8 + payloadBytes.size)
        out.write("hdlr".toByteArray(Charsets.US_ASCII))
        out.write(payloadBytes)
        return out.toByteArray()
    }

    private fun buildPitmBox(primaryItemId: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val payload = ByteArrayOutputStream()
        payload.write(byteArrayOf(0, 0, 0, 0)) // FullBox version=0
        writeU16(payload, primaryItemId.toInt())
        val payloadBytes = payload.toByteArray()
        writeU32(out, 8 + payloadBytes.size)
        out.write("pitm".toByteArray(Charsets.US_ASCII))
        out.write(payloadBytes)
        return out.toByteArray()
    }

    private fun buildInfeBox(itemId: Long, itemType: String, contentType: String?): ByteArray {
        val out = ByteArrayOutputStream()
        val payload = ByteArrayOutputStream()
        payload.write(byteArrayOf(2, 0, 0, 0)) // FullBox version=2, flags=0
        writeU16(payload, itemId.toInt())
        writeU16(payload, 0) // item_protection_index
        payload.write(itemType.toByteArray(Charsets.US_ASCII))
        payload.write(0) // empty item_name, NUL-terminated
        if (contentType != null) {
            payload.write(contentType.toByteArray(Charsets.US_ASCII))
            payload.write(0)
        }
        val payloadBytes = payload.toByteArray()
        writeU32(out, 8 + payloadBytes.size)
        out.write("infe".toByteArray(Charsets.US_ASCII))
        out.write(payloadBytes)
        return out.toByteArray()
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 24) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
```

**Implementer note:** the `xmpIlocEntryOffset` computation above (in the
`Result(...)` construction) is intentionally worked through step by step
with an inline comment because it's easy to get an off-by-one on whether
`xmpIlocEntryOffsetHolder[0]` already includes iloc's own 8-byte box
header. Before trusting it, verify it directly: after building a fixture
with `xmpText != null`, assert that reading a 2-byte big-endian item_ID at
`heicBytes[xmpIlocEntryOffset]` equals `XMP_ITEM_ID` (2). Add this as an
explicit assertion inside `HeicMetaFixtureTest`'s first test if the
existing assertions don't already catch a miscalculation here -- this is
exactly the kind of arithmetic this project's `SefIntegrityAnalyzerTest.kt`
`buildSefTrailer` comment warns about, and the fixture must be verified
correct before Tasks 3/4 build on top of it.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.HeicMetaFixtureTest"`
Expected: PASS. If the `xmpIlocEntryOffset` arithmetic is off, this will
manifest as Task 3/4's tests reading garbage at that offset (wrong
item_ID, or `findIlocHeaderFields`-adjacent code reading nonsense) --
debug by dumping the byte sequence around the computed offset and
comparing against the hand-traced iloc entry layout in the comments above.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/HeicMetaFixture.kt app/src/test/kotlin/com/multiviewer/parser/HeicMetaFixtureTest.kt
git commit -m "test: HeicMetaFixture -- realistic HEIC meta/iinf/iloc test fixture builder

The existing MotionPhotoBuilderTest HEIC fixture has no meta/iinf/iloc
at all, so it can't exercise repointHeicXmpItem or createHeicXmpItem
meaningfully. Builds a minimal-but-structurally-real HEIC (ftyp + meta
with hdlr/pitm/iinf/iloc + mdat) with one primary item and an optional
XMP item, for Tasks 3 and 4's tests to build on.

See docs/superpowers/specs/2026-09-30-motion-photo-xmp-preservation-design.md"
```

---

### Task 6: Manual verification

- [ ] **Step 1**: Run `./gradlew compileKotlin` and `./gradlew test` --
  expect `BUILD SUCCESSFUL` on both.
- [ ] **Step 2 (manual/programmatic)**: Using this feature's own
  established real-file dogfooding pattern, take a real gain-map HEIC or
  JPEG (search `~/Downloads`, `~/Desktop`, `~/Documents` for one, or
  produce one via a real phone capture if none is found locally) that has
  **no** existing motion video, run `MotionPhotoBuilder.createMotionPhoto`
  on it with a short test video, and confirm via a throwaway test (written,
  run, deleted -- not committed): the output file's XMP (read via this
  app's own `parseFile`) still contains the original gain map's `hdrgm:*`
  attributes, the new `GCamera:MotionPhoto`/`Container:Directory` entries
  are present, and the file still opens/decodes correctly end-to-end
  (image + gain map + video).
- [ ] **Step 3 (manual, GUI)**: In the live app, run "모션포토 생성" on a
  real image that has existing XMP (gain map or otherwise) via the actual
  menu, and open the result in the Motion Photo Integrity Check window
  (from this session's earlier work) to visually confirm it reports a
  clean PASS across categories -- confirming this feature and the
  integrity-checker feature agree with each other on what a well-formed
  motion photo looks like. This is a real end-to-end cross-feature check,
  not just a unit test assertion.
