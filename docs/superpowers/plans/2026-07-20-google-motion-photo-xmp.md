# Google Motion Photo XMP Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `findEmbeddedVideo` also recognize Google/Pixel Motion Photo JPEGs (no Samsung SEFD trailer), which record the embedded video's location in XMP metadata rather than a directory-style field, using both the current Container:Directory spec and the legacy GCamera:MicroVideoOffset format.

**Architecture:** A new, purely-additive fallback path inside `MotionPhotoExtractor.kt` — reached only when neither of the existing Samsung/HEIC signals (`mpvd`, SEFD `MotionPhoto_Data`) is found. Uses the JDK's built-in XML DOM parser (no new dependency) to read the XMP text already captured as a string field elsewhere in the box tree.

**Tech Stack:** Kotlin 2.0.21, `javax.xml.parsers.DocumentBuilder` (JDK built-in, `org.w3c.dom`), `kotlin.test`.

## Global Constraints

- This change is additive only — the existing `mpvd`/SEFD detection paths, and their priority over anything new, must be unaffected. `AppState.kt`/`Main.kt` are not touched (the `EmbeddedVideo`/`findEmbeddedVideo` contract is unchanged).
- Match Google's two documented XMP schemes exactly:
  - Current spec: `Container:Directory` → item with `Item:Semantic="MotionPhoto"` → its `Item:Length` is the byte distance back from end-of-file (`start = fileSize - length`, `end = fileSize`). `Item:Mime == "video/quicktime"` → `.mov`, else `.mp4`.
  - Legacy: `GCamera:MicroVideoOffset` — identical end-of-file-relative math, always `.mp4`.
- Match elements/attributes by **local name only** (not namespace prefix) — real encoders use varying prefixes (`GCamera` vs `Camera`) for the same namespace.
- Handle both real-world RDF serializations: properties as XML elements (`<Item:Semantic>MotionPhoto</Item:Semantic>`) and as XML attributes (`Item:Semantic="MotionPhoto"`).
- Since this parses untrusted file content, the `DocumentBuilderFactory` must have DOCTYPE declarations disabled (`http://apache.org/xml/features/disallow-doctype-decl` = `true`) to prevent XXE.
- Any parse failure (malformed/truncated XMP) must be caught and treated as "not found" — never throw out of `findEmbeddedVideo`.

---

### Task 1: Google Motion Photo XMP detection

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoExtractor.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MotionPhotoExtractorTest.kt`

**Interfaces:**
- Consumes: `BoxNode` (existing — `type`, `offset`, `headerSize`, `size`, `children`, `fields`), `BoxField` (`name`, `value`), `findFirst(node: BoxNode, predicate: (BoxNode) -> Boolean): BoxNode?` (existing, package-visible).
- Produces: no new public symbols — this task only extends the existing `fun findEmbeddedVideo(root: BoxNode): EmbeddedVideo?` to additionally recognize Google XMP-based Motion Photos. `EmbeddedVideo` and `extractEmbeddedVideo` are unchanged.

- [ ] **Step 1: Write the failing tests**

Add these 7 tests to the end of the `MotionPhotoExtractorTest` class in `app/src/test/kotlin/com/multiviewer/parser/MotionPhotoExtractorTest.kt` (immediately before the class's closing `}`):

```kotlin
    @Test
    fun `finds video via Google Container Directory XMP in element form`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
                  <Camera:MotionPhoto>1</Camera:MotionPhoto>
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource">
                        <Item:Mime>image/jpeg</Item:Mime>
                        <Item:Semantic>Primary</Item:Semantic>
                        <Item:Length>0</Item:Length>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Item:Mime>video/mp4</Item:Mime>
                        <Item:Semantic>MotionPhoto</Item:Semantic>
                        <Item:Length>12345</Item:Length>
                      </rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("xmp", xmp, 0, 0)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 100000, children = listOf(app1))

        val video = findEmbeddedVideo(root)

        assertEquals(100000L - 12345L, video?.start)
        assertEquals(100000L, video?.end)
        assertEquals("mp4", video?.extension)
    }

    @Test
    fun `finds video via Google Container Directory XMP in attribute form`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource" Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0"/>
                      <rdf:li rdf:parseType="Resource" Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="54321"/>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("xmp", xmp, 0, 0)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 200000, children = listOf(app1))

        val video = findEmbeddedVideo(root)

        assertEquals(200000L - 54321L, video?.start)
        assertEquals(200000L, video?.end)
        assertEquals("mp4", video?.extension)
    }

    @Test
    fun `detects mov extension from Item Mime video quicktime`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource" Item:Mime="video/quicktime" Item:Semantic="MotionPhoto" Item:Length="9999"/>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("xmp", xmp, 0, 0)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 50000, children = listOf(app1))

        val video = findEmbeddedVideo(root)

        assertEquals("mov", video?.extension)
    }

    @Test
    fun `falls back to legacy GCamera MicroVideoOffset when no Container Directory is present`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                    GCamera:MicroVideo="1"
                    GCamera:MicroVideoVersion="1"
                    GCamera:MicroVideoOffset="4022143"/>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("xmp", xmp, 0, 0)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 5000000, children = listOf(app1))

        val video = findEmbeddedVideo(root)

        assertEquals(5000000L - 4022143L, video?.start)
        assertEquals(5000000L, video?.end)
        assertEquals("mp4", video?.extension)
    }

    @Test
    fun `malformed XMP text does not throw and returns null`() {
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("xmp", "<not valid xml", 0, 0)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 1000, children = listOf(app1))

        assertEquals(null, findEmbeddedVideo(root))
    }

    @Test
    fun `XMP with no motion-photo markers at all returns null`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:creator>someone</dc:creator>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("xmp", xmp, 0, 0)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 1000, children = listOf(app1))

        assertEquals(null, findEmbeddedVideo(root))
    }

    @Test
    fun `prefers the sefd MotionPhoto_Data field over Google XMP when both are present`() {
        val ftyp = BoxNode(
            type = "ftyp", offset = 220, headerSize = 8, size = 16,
            fields = listOf(BoxField("major_brand", "mp42", 220, 4)),
        )
        val dataField = BoxNode(
            type = "MotionPhoto_Data", offset = 200, headerSize = 4, size = 4096,
            children = listOf(ftyp),
        )
        val sefd = BoxNode(type = "sefd", offset = 50, headerSize = 0, size = 4200, children = listOf(dataField))
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource" Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="999"/>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("xmp", xmp, 0, 0)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 100000, children = listOf(sefd, app1))

        val video = findEmbeddedVideo(root)

        assertEquals(204L, video?.start)
        assertEquals(4296L, video?.end)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.MotionPhotoExtractorTest"`
Expected: FAIL — all 7 new tests fail because the XMP fallback doesn't exist yet (the first six get `null` back where a value was expected; the last one already passes today since the sefd path already wins, but leave it here as a regression guard through this task's change).

- [ ] **Step 3: Implement the XMP fallback**

Replace the full contents of `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoExtractor.kt` with:

```kotlin
package com.multiviewer.parser

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

data class EmbeddedVideo(val start: Long, val end: Long, val extension: String)

fun findEmbeddedVideo(root: BoxNode): EmbeddedVideo? {
    val videoNode = root.children.find { it.type == "mpvd" }
        ?: findFirst(root) { it.type == "sefd" }
            ?.children
            ?.filter { it.children.firstOrNull()?.type == "ftyp" }
            ?.let { candidates -> candidates.find { it.type == "MotionPhoto_Data" } ?: candidates.firstOrNull() }
    if (videoNode != null) {
        val majorBrand = videoNode.children.find { it.type == "ftyp" }
            ?.fields?.find { it.name == "major_brand" }?.value
        val extension = if (majorBrand?.trim() == "qt") "mov" else "mp4"
        return EmbeddedVideo(videoNode.offset + videoNode.headerSize, videoNode.offset + videoNode.size, extension)
    }
    return findGoogleMotionPhotoVideo(root)
}

fun extractEmbeddedVideo(source: File, video: EmbeddedVideo, destination: File) {
    val chunkSizeLimit = 1L shl 20 // 1 MB
    ByteReader.open(source).use { reader ->
        destination.outputStream().use { out ->
            var offset = video.start
            while (offset < video.end) {
                val chunkSize = minOf(chunkSizeLimit, video.end - offset).toInt()
                out.write(reader.readBytes(offset, chunkSize))
                offset += chunkSize
            }
        }
    }
}

private data class DirectoryVideoInfo(val length: Long, val mimeType: String?)

private fun findGoogleMotionPhotoVideo(root: BoxNode): EmbeddedVideo? {
    val xmpText = findFirst(root) { it.fields.any { field -> field.name == "xmp" } }
        ?.fields?.find { it.name == "xmp" }?.value
        ?: return null
    val document = try {
        parseXmpDocument(xmpText)
    } catch (e: Exception) {
        return null
    }
    val fromDirectory = findMotionPhotoInDirectory(document)
    val length = fromDirectory?.length ?: findMicroVideoOffset(document) ?: return null
    if (length <= 0 || length > root.size) return null
    val extension = if (fromDirectory?.mimeType == "video/quicktime") "mov" else "mp4"
    return EmbeddedVideo(root.size - length, root.size, extension)
}

private fun parseXmpDocument(xmpText: String): Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.isNamespaceAware = true
    val builder = factory.newDocumentBuilder()
    return builder.parse(InputSource(StringReader(xmpText)))
}

private fun findMotionPhotoInDirectory(document: Document): DirectoryVideoInfo? {
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

private fun findMicroVideoOffset(document: Document): Long? {
    val descriptions = document.getElementsByTagNameNS("*", "Description")
    for (i in 0 until descriptions.length) {
        val description = descriptions.item(i) as? Element ?: continue
        findPropertyValue(description, "MicroVideoOffset")?.toLongOrNull()?.let { return it }
    }
    return null
}

private fun findPropertyValue(element: Element, localName: String): String? {
    val attributes = element.attributes
    for (i in 0 until attributes.length) {
        val attribute = attributes.item(i)
        if (attribute.localName == localName) return attribute.nodeValue
    }
    val children = element.childNodes
    for (i in 0 until children.length) {
        val child = children.item(i) as? Element ?: continue
        if (child.localName == localName) return child.textContent.trim()
        findPropertyValue(child, localName)?.let { return it }
    }
    return null
}
```

This is a full-file replacement: the top of the file (`EmbeddedVideo`, `findEmbeddedVideo`, `extractEmbeddedVideo`) is the same logic as before with `findEmbeddedVideo`'s tail restructured from `?: return null` into an `if (videoNode != null) { ... }` block that falls through to the new `findGoogleMotionPhotoVideo` otherwise; everything below `extractEmbeddedVideo` is new.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.MotionPhotoExtractorTest"`
Expected: PASS (12 tests: 5 existing + 7 new)

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (142 existing + 7 new = 149)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MotionPhotoExtractor.kt \
        app/src/test/kotlin/com/multiviewer/parser/MotionPhotoExtractorTest.kt
git commit -m "feat: support Google Motion Photo XMP (Container:Directory + legacy MicroVideoOffset)"
```

- [ ] **Step 7: Manual verification note**

There is no real (non-Samsung) Google/Pixel Motion Photo sample file available in this environment to test end-to-end. The 7 new tests exercise `findEmbeddedVideo`'s XMP-parsing logic directly and thoroughly (both RDF serialization forms, both XMP schemes, the extension rule, graceful failure, and Samsung-priority), which is the correct level for this pure-parsing change — no additional manual GUI verification is needed beyond what the existing Motion Photo menu item already got when it shipped.
