# Google Motion Photo 1.0 Spec Compliance (phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Check every motion photo against Google Motion Photo Format 1.0 (XMP, directory → byte layout, HEIC/AVIF
`mpvd`, GainMap item, legacy MicroVideo, filename), MUST → FAIL, SHOULD → WARN, replacing the old Google-XMP and
"Apple mpvd" checks.

**Architecture:** New `parser/motionphoto/` files: an XMP reader (`GoogleMotionPhotoXmp.kt`), the rule engine
(`GoogleMotionPhotoSpecChecker.kt`) and byte-layout rules (`GoogleMotionPhotoLayout.kt`). `MotionPhotoIntegrityAnalyzer`
runs the checker for every detected motion photo; `MotionPhotoIntegrityReport.googleSpec` replaces `googleXmpChecks` and
`appleMpvdChecks`; the UI, findings, AI snapshot and CLI JSON render it.

**Tech Stack:** Kotlin, JDK 21, Compose Desktop Material3, kotlin.test/JUnit5, javax.xml DOM (existing
`parseXmpDocument`), ffmpeg/python3 for fixtures.

Spec: `docs/superpowers/specs/2026-10-22-google-motion-photo-spec-compliance-design.md`.

## Global Constraints

- Severity: spec MUST / MUST NOT / REQUIRED / "Required" → `SefIntegritySeverity.CRITICAL` (FAIL); SHOULD or no keyword → `WARNING` (WARN); informational → `INFO`; not evaluable → `SKIPPED` — never PASS (AGENTS.md rule 7).
- Namespaces matched by URI: Camera `http://ns.google.com/photos/1.0/camera/`, Container `http://ns.google.com/photos/1.0/container/`, Item `http://ns.google.com/photos/1.0/container/item/`. Prefix is irrelevant (Samsung uses `GCamera`).
- Filename regex (§2.1): `^([^\s/\\][^/\\]*MP)\.(JPG|jpg|JPEG|jpeg|HEIC|heic|AVIF|avif)$`.
- Allowed `Item:Mime`: `image/jpeg`, `image/heic`, `image/avif`, `video/mp4`, `video/quicktime`.
- User-visible titles bilingual `"한국어 / English"`; details Korean (matches existing motion-photo rows).
- Material3 only (`androidx.compose.material3.Text`); no new dependencies; no blocking I/O on the UI thread (the analyzer already runs on `Dispatchers.IO`).
- Work in worktree `.worktrees/google-motion-photo-spec` on branch `feat/google-motion-photo-spec`; before every commit run `git branch --show-current` and confirm it prints `feat/google-motion-photo-spec`.
- Test command: `./gradlew :app:test --tests '<class>'`; full suite `./gradlew :app:test` (`ThumbnailDiskCacheTest` is known flaky).

---

### Task 1: Motion Photo XMP reader

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoXmp.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/motionphoto/GmpTestXmp.kt` (shared test XMP builder)
- Test: `app/src/test/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoXmpTest.kt`

**Interfaces:**
- Consumes: `com.multiviewer.parser.parseXmpDocument(xmpText: String): org.w3c.dom.Document` (existing, internal, same module).
- Produces: `GMP_CAMERA_NS`, `GMP_CONTAINER_NS`, `GMP_ITEM_NS` (String consts); `data class GmpDirectoryItem(index: Int, properties: Map<String, String>)` with `mime`, `semantic`, `length`, `padding: String?`; `data class GmpXmp(camera: Map<String, String>, wrongNamespace: List<String>, directory: List<GmpDirectoryItem>?)`; `fun parseGmpXmp(xmpText: String): GmpXmp` (throws on malformed XML). Test helper `internal fun gmpXmp(...)`: String.

- [ ] **Step 1: Write the shared test XMP builder**

`app/src/test/kotlin/com/multiviewer/parser/motionphoto/GmpTestXmp.kt`:

```kotlin
package com.multiviewer.parser.motionphoto

/** Samsung-shaped XMP (attributes on rdf:Description, one Container:Item per rdf:li); each test overrides one part. */
internal fun gmpXmp(
    cameraNs: String = GMP_CAMERA_NS,
    camera: Map<String, String> = mapOf(
        "MotionPhoto" to "1", "MotionPhotoVersion" to "1", "MotionPhotoPresentationTimestampUs" to "0",
    ),
    items: List<Map<String, String>>? = listOf(
        mapOf("Semantic" to "Primary", "Mime" to "image/jpeg"),
        mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "100"),
    ),
    directoryNs: String = GMP_CONTAINER_NS,
    itemNs: String = GMP_ITEM_NS,
    extraDescriptionAttrs: String = "",
): String {
    val cameraAttrs = camera.entries.joinToString("\n") { (k, v) -> "      GCamera:$k=\"$v\"" }
    val directory = items?.let { list ->
        val lis = list.joinToString("\n") { props ->
            val attrs = props.entries.joinToString(" ") { (k, v) -> "Item:$k=\"$v\"" }
            "          <rdf:li rdf:parseType=\"Resource\"><Container:Item $attrs/></rdf:li>"
        }
        "      <Container:Directory>\n        <rdf:Seq>\n$lis\n        </rdf:Seq>\n      </Container:Directory>"
    } ?: ""
    return """<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
      xmlns:GCamera="$cameraNs"
      xmlns:Container="$directoryNs"
      xmlns:Item="$itemNs"
$cameraAttrs $extraDescriptionAttrs>
$directory
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>"""
}
```

- [ ] **Step 2: Write the failing tests**

`app/src/test/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoXmpTest.kt`:

```kotlin
package com.multiviewer.parser.motionphoto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleMotionPhotoXmpTest {
    @Test
    fun `reads camera properties by URI whatever the prefix and the directory in order`() {
        val xmp = parseGmpXmp(gmpXmp())
        assertEquals("1", xmp.camera["MotionPhoto"])
        assertEquals("0", xmp.camera["MotionPhotoPresentationTimestampUs"])
        assertTrue(xmp.wrongNamespace.isEmpty(), xmp.wrongNamespace.toString())
        val items = xmp.directory!!
        assertEquals(listOf("Primary", "MotionPhoto"), items.map { it.semantic })
        assertEquals(listOf(0, 1), items.map { it.index })
        assertEquals("100", items[1].length)
        assertNull(items[0].length)
    }

    @Test
    fun `reads element-form properties`() {
        val text = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about="" xmlns:Camera="$GMP_CAMERA_NS" xmlns:Container="$GMP_CONTAINER_NS" xmlns:Item="$GMP_ITEM_NS">
<Camera:MotionPhoto>1</Camera:MotionPhoto>
<Container:Directory><rdf:Seq>
<rdf:li rdf:parseType="Resource"><Container:Item><rdf:Description><Item:Mime>image/jpeg</Item:Mime><Item:Semantic>Primary</Item:Semantic></rdf:Description></Container:Item></rdf:li>
<rdf:li rdf:parseType="Resource"><Container:Item><rdf:Description><Item:Mime>video/mp4</Item:Mime><Item:Semantic>MotionPhoto</Item:Semantic><Item:Length>42</Item:Length></rdf:Description></Container:Item></rdf:li>
</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val xmp = parseGmpXmp(text)
        assertEquals("1", xmp.camera["MotionPhoto"])
        assertEquals(listOf("Primary", "MotionPhoto"), xmp.directory!!.map { it.semantic })
        assertEquals("42", xmp.directory!![1].length)
    }

    @Test
    fun `camera property under a non-spec URI is reported and not read`() {
        val xmp = parseGmpXmp(gmpXmp(cameraNs = "http://ns.google.com/photos/1.0/camera"))
        assertNull(xmp.camera["MotionPhoto"])
        assertTrue(xmp.wrongNamespace.any { it.startsWith("MotionPhoto ") }, xmp.wrongNamespace.toString())
    }

    @Test
    fun `directory under a non-spec URI is reported and treated as absent`() {
        val xmp = parseGmpXmp(gmpXmp(directoryNs = "http://example.com/container/"))
        assertNull(xmp.directory)
        assertTrue(xmp.wrongNamespace.any { it.startsWith("Directory ") }, xmp.wrongNamespace.toString())
    }

    @Test
    fun `item property under a non-spec URI is reported`() {
        val xmp = parseGmpXmp(gmpXmp(itemNs = "http://example.com/item/"))
        assertTrue(xmp.wrongNamespace.any { it.startsWith("Item:Mime ") }, xmp.wrongNamespace.toString())
        assertNull(xmp.directory!![0].mime)
    }

    @Test
    fun `no directory gives null`() {
        assertNull(parseGmpXmp(gmpXmp(items = null)).directory)
    }

    @Test
    fun `malformed XML throws`() {
        assertFailsWith<Exception> { parseGmpXmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><broken") }
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.motionphoto.GoogleMotionPhotoXmpTest'`
Expected: compilation FAIL — `Unresolved reference 'parseGmpXmp'` / `GMP_CAMERA_NS`.

- [ ] **Step 4: Implement the reader**

`app/src/main/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoXmp.kt`:

```kotlin
package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.parseXmpDocument
import org.w3c.dom.Element
import org.w3c.dom.Node

const val GMP_CAMERA_NS = "http://ns.google.com/photos/1.0/camera/"
const val GMP_CONTAINER_NS = "http://ns.google.com/photos/1.0/container/"
const val GMP_ITEM_NS = "http://ns.google.com/photos/1.0/container/item/"
private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
private const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"

private val CAMERA_PROPERTIES = setOf(
    "MotionPhoto", "MotionPhotoVersion", "MotionPhotoPresentationTimestampUs",
    "MicroVideo", "MicroVideoVersion", "MicroVideoOffset", "MicroVideoPresentationTimestampUs",
)
private val ITEM_PROPERTIES = setOf("Mime", "Semantic", "Length", "Padding")

/** One `Container:Item` of the directory, in `rdf:Seq` order; values are the raw XMP strings. */
data class GmpDirectoryItem(val index: Int, val properties: Map<String, String>) {
    val mime: String? get() = properties["Mime"]
    val semantic: String? get() = properties["Semantic"]
    val length: String? get() = properties["Length"]
    val padding: String? get() = properties["Padding"]
}

/**
 * The Motion Photo 1.0 view of a primary image's XMP. Properties are matched by namespace URI, never by
 * prefix (Samsung writes the Camera namespace as `GCamera:`). A Motion Photo property under any other URI
 * goes to [wrongNamespace] instead, because a spec reader would not find it.
 */
data class GmpXmp(
    val camera: Map<String, String>,
    val wrongNamespace: List<String>,
    /** Null when there is no `Container:Directory` in the Container namespace. */
    val directory: List<GmpDirectoryItem>?,
)

/** Throws on malformed XML; the caller reports `gmp.xmp.parse`. */
fun parseGmpXmp(xmpText: String): GmpXmp {
    val document = parseXmpDocument(xmpText)
    val camera = linkedMapOf<String, String>()
    val wrong = mutableListOf<String>()
    var directory: List<GmpDirectoryItem>? = null
    val all = document.getElementsByTagNameNS("*", "*")
    for (i in 0 until all.length) {
        val element = all.item(i) as? Element ?: continue
        val attributes = element.attributes
        for (a in 0 until attributes.length) {
            val attr = attributes.item(a)
            val name = attr.localName ?: continue
            if (attr.namespaceURI == GMP_CAMERA_NS) {
                camera.putIfAbsent(name, attr.nodeValue.trim())
            } else if (name in CAMERA_PROPERTIES && attr.namespaceURI != XMLNS_NS) {
                wrong += "$name (${attr.namespaceURI ?: "네임스페이스 없음"})"
            }
        }
        val local = element.localName ?: continue
        if (element.namespaceURI == GMP_CAMERA_NS && !hasElementChildren(element)) {
            camera.putIfAbsent(local, element.textContent.trim())
        } else if (local in CAMERA_PROPERTIES && element.namespaceURI != GMP_CAMERA_NS) {
            wrong += "$local (${element.namespaceURI ?: "네임스페이스 없음"})"
        }
        if (local == "Directory") {
            if (element.namespaceURI == GMP_CONTAINER_NS) {
                if (directory == null) directory = readDirectory(element, wrong)
            } else {
                wrong += "Directory (${element.namespaceURI ?: "네임스페이스 없음"})"
            }
        }
    }
    return GmpXmp(camera, wrong.distinct(), directory)
}

private fun readDirectory(directory: Element, wrong: MutableList<String>): List<GmpDirectoryItem> {
    val seq = childElements(directory).firstOrNull { it.namespaceURI == RDF_NS && it.localName == "Seq" } ?: return emptyList()
    return childElements(seq).filter { it.namespaceURI == RDF_NS && it.localName == "li" }.mapIndexed { index, li ->
        val properties = linkedMapOf<String, String>()
        collectItemProperties(li, properties, wrong)
        GmpDirectoryItem(index, properties)
    }
}

// Item properties may be attributes (Samsung: on the nested Container:Item) or child elements (rdf:Description form).
private fun collectItemProperties(element: Element, properties: MutableMap<String, String>, wrong: MutableList<String>) {
    val attributes = element.attributes
    for (a in 0 until attributes.length) {
        val attr = attributes.item(a)
        val name = attr.localName ?: continue
        if (attr.namespaceURI == GMP_ITEM_NS) {
            properties.putIfAbsent(name, attr.nodeValue.trim())
        } else if (name in ITEM_PROPERTIES && attr.namespaceURI != XMLNS_NS) {
            wrong += "Item:$name (${attr.namespaceURI ?: "네임스페이스 없음"})"
        }
    }
    for (child in childElements(element)) {
        val name = child.localName ?: continue
        if (child.namespaceURI == GMP_ITEM_NS && !hasElementChildren(child)) {
            properties.putIfAbsent(name, child.textContent.trim())
        } else {
            if (name in ITEM_PROPERTIES) wrong += "Item:$name (${child.namespaceURI ?: "네임스페이스 없음"})"
            collectItemProperties(child, properties, wrong)
        }
    }
}

private fun childElements(element: Element): List<Element> {
    val nodes = element.childNodes
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
}

private fun hasElementChildren(element: Element): Boolean {
    val nodes = element.childNodes
    return (0 until nodes.length).any { nodes.item(it).nodeType == Node.ELEMENT_NODE }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.motionphoto.GoogleMotionPhotoXmpTest'`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git branch --show-current   # must print feat/google-motion-photo-spec
git add app/src/main/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoXmp.kt app/src/test/kotlin/com/multiviewer/parser/motionphoto/GmpTestXmp.kt app/src/test/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoXmpTest.kt
git commit -m "feat: read Google Motion Photo XMP by namespace URI"
```

---

### Task 2: Spec checker — XMP, directory and filename rules

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoSpecChecker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoSpecCheckerTest.kt`

**Interfaces:**
- Consumes: Task 1 `parseGmpXmp`, `GmpXmp`, `GmpDirectoryItem`; existing `com.multiviewer.parser.SefIntegritySeverity`, `SefCheckResult(severity, label, detail)`, `ContainerFormat`, `sniffFormat(reader)`, `findFirst(node, predicate)`, `ByteReader`, `BoxNode`.
- Produces: `enum class SpecRequirement { MUST, SHOULD, INFO }`; `data class GoogleSpecFinding(ruleId: String, section: String, requirement: SpecRequirement, severity: SefIntegritySeverity, title: String, detail: String)`; `data class GmpSpecInput(fileName: String, format: ContainerFormat?, xmpText: String?, videoDurationUs: Long?)`; `object GoogleMotionPhotoSpecChecker { fun check(file: File, root: BoxNode, reader: ByteReader, videoDurationUs: Long?): List<GoogleSpecFinding>; fun check(input: GmpSpecInput, reader: ByteReader): List<GoogleSpecFinding> }`; internal helpers `spec(...)`, `specSkip(...)`, `specInfo(...)`, `const val HDR_GAINMAP_NS`; extensions `GoogleSpecFinding.toSefCheckResult(): SefCheckResult`, `GoogleSpecFinding.groupLabel(): String`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoSpecCheckerTest.kt`:

```kotlin
package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.ContainerFormat
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.byteReaderOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleMotionPhotoSpecCheckerTest {
    private fun findings(
        xmp: String?,
        format: ContainerFormat? = ContainerFormat.JPEG,
        fileName: String = "PXL_1.MP.jpg",
        durationUs: Long? = 1_000_000,
    ): List<GoogleSpecFinding> = byteReaderOf(ByteArray(16)).use {
        GoogleMotionPhotoSpecChecker.check(GmpSpecInput(fileName, format, xmp, durationUs), it)
    }

    private fun List<GoogleSpecFinding>.rule(id: String) = single { it.ruleId == id }

    // XMP-level rules only: the byte-layout rules (Task 3) run on the dummy 16-byte file and are asserted elsewhere.
    private fun List<GoogleSpecFinding>.xmpRules() = filter {
        listOf("gmp.ns", "gmp.camera", "gmp.dir", "gmp.legacy", "gmp.filename", "gmp.xmp").any(it.ruleId::startsWith) ||
            (it.ruleId.startsWith("gmp.item") && it.ruleId != "gmp.item.video_mime")
    }

    private val bad = setOf(SefIntegritySeverity.CRITICAL, SefIntegritySeverity.WARNING)

    @Test
    fun `compliant JPEG XMP has no FAIL or WARN`() {
        val result = findings(gmpXmp()).xmpRules()
        assertTrue(result.none { it.severity in bad }, result.joinToString("\n"))
        assertEquals(SefIntegritySeverity.PASS, result.rule("gmp.camera.motionphoto").severity)
        assertEquals(SpecRequirement.MUST, result.rule("gmp.camera.motionphoto").requirement)
        assertEquals("3.1", result.rule("gmp.camera.motionphoto").section)
    }

    @Test
    fun `missing XMP fails MotionPhoto and Directory`() {
        val result = findings(null)
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.camera.motionphoto").severity)
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.dir.present").severity)
    }

    @Test
    fun `unparsable XMP fails and the remaining XMP rules are skipped, never passed`() {
        val result = findings("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><broken")
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.xmp.parse").severity)
        assertEquals(SefIntegritySeverity.SKIPPED, result.rule("gmp.xmp.rules").severity)
        assertTrue(result.none { it.ruleId.startsWith("gmp.camera") && it.severity == SefIntegritySeverity.PASS })
    }

    @Test
    fun `wrong Camera namespace URI fails the namespace rule and MotionPhoto`() {
        val result = findings(gmpXmp(cameraNs = "http://ns.google.com/photos/1.0/camera"))
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.ns.uris").severity)
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.camera.motionphoto").severity)
    }

    @Test
    fun `MotionPhoto 0 fails`() {
        val result = findings(gmpXmp(camera = mapOf("MotionPhoto" to "0", "MotionPhotoVersion" to "1")))
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.camera.motionphoto").severity)
    }

    @Test
    fun `missing MotionPhotoVersion warns`() {
        val result = findings(gmpXmp(camera = mapOf("MotionPhoto" to "1")))
        assertEquals(SefIntegritySeverity.WARNING, result.rule("gmp.camera.version").severity)
    }

    @Test
    fun `presentation timestamp rules`() {
        fun pts(value: String?, duration: Long? = 1_000_000) = findings(
            gmpXmp(camera = mapOf("MotionPhoto" to "1", "MotionPhotoVersion" to "1") +
                (if (value != null) mapOf("MotionPhotoPresentationTimestampUs" to value) else emptyMap())),
            durationUs = duration,
        ).rule("gmp.camera.pts").severity
        assertEquals(SefIntegritySeverity.PASS, pts("-1"))
        assertEquals(SefIntegritySeverity.PASS, pts("500000"))
        assertEquals(SefIntegritySeverity.WARNING, pts("-5"))
        assertEquals(SefIntegritySeverity.WARNING, pts("2000000"))
        assertEquals(SefIntegritySeverity.WARNING, pts("abc"))
        assertEquals(SefIntegritySeverity.SKIPPED, pts("500000", duration = null))
        assertEquals(SefIntegritySeverity.INFO, pts(null))
    }

    @Test
    fun `legacy MicroVideo only fails the directory rule and notes the legacy fields`() {
        val result = findings(gmpXmp(camera = mapOf("MicroVideo" to "1", "MicroVideoOffset" to "100"), items = null))
        val dir = result.rule("gmp.dir.present")
        assertEquals(SefIntegritySeverity.CRITICAL, dir.severity)
        assertTrue(dir.detail.contains("MicroVideo"), dir.detail)
        assertEquals(SefIntegritySeverity.INFO, result.rule("gmp.legacy.microvideo").severity)
        assertEquals(SefIntegritySeverity.SKIPPED, result.rule("gmp.dir.items").severity)
    }

    @Test
    fun `legacy fields next to a directory are only informational`() {
        val result = findings(gmpXmp(camera = mapOf("MotionPhoto" to "1", "MotionPhotoVersion" to "1", "MicroVideoOffset" to "100")))
        assertEquals(SefIntegritySeverity.PASS, result.rule("gmp.dir.present").severity)
        assertEquals(SefIntegritySeverity.INFO, result.rule("gmp.legacy.microvideo").severity)
    }

    @Test
    fun `first item must be Primary and Primary and MotionPhoto must be unique`() {
        val swapped = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "100"),
            mapOf("Semantic" to "Primary", "Mime" to "image/jpeg"),
        )))
        assertEquals(SefIntegritySeverity.CRITICAL, swapped.rule("gmp.dir.primary_first").severity)
        val twoVideos = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary", "Mime" to "image/jpeg"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "50"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "50"),
        )))
        assertEquals(SefIntegritySeverity.CRITICAL, twoVideos.rule("gmp.dir.motionphoto_unique").severity)
        assertEquals(SefIntegritySeverity.PASS, twoVideos.rule("gmp.dir.primary_unique").severity)
    }

    @Test
    fun `Mime and Semantic are required and Mime must be an allowed value`() {
        val result = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary"),
            mapOf("Mime" to "video/x-matroska", "Length" to "100"),
        )))
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.item.mime_required").severity)
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.item.semantic_required").severity)
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.item.mime_value").severity)
    }

    @Test
    fun `primary Mime must match the real image format`() {
        val result = findings(gmpXmp(), format = ContainerFormat.HEIF, fileName = "a.MP.heic")
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.item.primary_mime").severity)
    }

    @Test
    fun `secondary items need a Length and the video Length must be positive`() {
        val missing = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary", "Mime" to "image/jpeg"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4"),
        )))
        assertEquals(SefIntegritySeverity.CRITICAL, missing.rule("gmp.item.length_required").severity)
        val sharedGainMap = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary", "Mime" to "image/heic", "Padding" to "8"),
            mapOf("Semantic" to "GainMap", "Mime" to "image/heic", "Length" to "0"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "100"),
        )), format = ContainerFormat.HEIF, fileName = "a.MP.heic")
        assertEquals(SefIntegritySeverity.PASS, sharedGainMap.rule("gmp.item.length_required").severity)
    }

    @Test
    fun `non-zero primary Length warns`() {
        val result = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary", "Mime" to "image/jpeg", "Length" to "5"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "100"),
        )))
        assertEquals(SefIntegritySeverity.WARNING, result.rule("gmp.item.primary_length").severity)
    }

    @Test
    fun `Padding on a later item fails (Samsung writes it on the video item)`() {
        val result = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary", "Mime" to "image/jpeg", "Padding" to "0"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "100", "Padding" to "0"),
        )))
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.item.padding_first_only").severity)
        assertEquals(SefIntegritySeverity.WARNING, result.rule("gmp.item.padding_jpeg").severity)
    }

    @Test
    fun `positive JPEG Padding passes`() {
        val result = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary", "Mime" to "image/jpeg", "Padding" to "16"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "100"),
        )))
        assertEquals(SefIntegritySeverity.PASS, result.rule("gmp.item.padding_jpeg").severity)
    }

    @Test
    fun `HEIC Padding must be 8`() {
        fun heic(padding: String?) = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary", "Mime" to "image/heic") + (if (padding != null) mapOf("Padding" to padding) else emptyMap()),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "100"),
        )), format = ContainerFormat.HEIF, fileName = "a.MP.heic").rule("gmp.item.padding_isobmff").severity
        assertEquals(SefIntegritySeverity.PASS, heic("8"))
        assertEquals(SefIntegritySeverity.CRITICAL, heic("0"))
        assertEquals(SefIntegritySeverity.CRITICAL, heic(null))
    }

    @Test
    fun `filename pattern is a SHOULD`() {
        assertEquals(SefIntegritySeverity.WARNING, findings(gmpXmp(), fileName = "20260718_200439.jpg").rule("gmp.filename").severity)
        assertEquals(SefIntegritySeverity.PASS, findings(gmpXmp(), fileName = "PXL_20231010_123456789.MP.jpg").rule("gmp.filename").severity)
        assertEquals(SefIntegritySeverity.PASS, findings(gmpXmp(), fileName = "abcMP.HEIC").rule("gmp.filename").severity)
        assertEquals(SpecRequirement.SHOULD, findings(gmpXmp()).rule("gmp.filename").requirement)
    }

    @Test
    fun `presentation helpers carry section, requirement and group`() {
        val finding = findings(gmpXmp(), fileName = "x.jpg").rule("gmp.filename")
        assertEquals("§2.1 [SHOULD] 파일명 / Filename", finding.toSefCheckResult().label)
        assertEquals("파일명 / Filename", finding.groupLabel())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.motionphoto.GoogleMotionPhotoSpecCheckerTest'`
Expected: compilation FAIL — `Unresolved reference 'GoogleMotionPhotoSpecChecker'`.

- [ ] **Step 3: Implement the checker (XMP + filename)**

`app/src/main/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoSpecChecker.kt`:

```kotlin
package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.ContainerFormat
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.findFirst
import com.multiviewer.parser.sniffFormat
import java.io.File

enum class SpecRequirement { MUST, SHOULD, INFO }

/** One Google Motion Photo 1.0 rule result; [section] is the spec section number (e.g. "3.3"). */
data class GoogleSpecFinding(
    val ruleId: String,
    val section: String,
    val requirement: SpecRequirement,
    val severity: SefIntegritySeverity,
    val title: String,
    val detail: String,
)

data class GmpSpecInput(
    val fileName: String,
    val format: ContainerFormat?,
    val xmpText: String?,
    val videoDurationUs: Long?,
)

internal const val HDR_GAINMAP_NS = "http://ns.adobe.com/hdr-gain-map/1.0/"
private val FILENAME_PATTERN = Regex("""^([^\s/\\][^/\\]*MP)\.(JPG|jpg|JPEG|jpeg|HEIC|heic|AVIF|avif)$""")
private val ALLOWED_MIMES = listOf("image/jpeg", "image/heic", "image/avif", "video/mp4", "video/quicktime")

/** PASS when [ok]; otherwise MUST → CRITICAL, SHOULD → WARNING, INFO → INFO. */
internal fun spec(ruleId: String, section: String, requirement: SpecRequirement, ok: Boolean, title: String, detail: String) =
    GoogleSpecFinding(
        ruleId, section, requirement,
        if (ok) SefIntegritySeverity.PASS else when (requirement) {
            SpecRequirement.MUST -> SefIntegritySeverity.CRITICAL
            SpecRequirement.SHOULD -> SefIntegritySeverity.WARNING
            SpecRequirement.INFO -> SefIntegritySeverity.INFO
        },
        title, detail,
    )

/** A rule that could not be evaluated: SKIPPED, never PASS. */
internal fun specSkip(ruleId: String, section: String, requirement: SpecRequirement, title: String, reason: String) =
    GoogleSpecFinding(ruleId, section, requirement, SefIntegritySeverity.SKIPPED, title, "검사하지 못함: $reason")

internal fun specInfo(ruleId: String, section: String, title: String, detail: String) =
    GoogleSpecFinding(ruleId, section, SpecRequirement.INFO, SefIntegritySeverity.INFO, title, detail)

/** Row shape of the existing motion-photo tables: "§3.3 [MUST] title". */
fun GoogleSpecFinding.toSefCheckResult(): SefCheckResult = SefCheckResult(severity, "§$section [${requirement.name}] $title", detail)

fun GoogleSpecFinding.groupLabel(): String = when {
    ruleId.startsWith("gmp.dir") || ruleId.startsWith("gmp.item") -> "Container 디렉터리 / Directory"
    ruleId.startsWith("gmp.layout") || ruleId.startsWith("gmp.gainmap") -> "배치 / Layout"
    ruleId.startsWith("gmp.mpvd") -> "HEIC·AVIF mpvd"
    ruleId == "gmp.filename" -> "파일명 / Filename"
    else -> "Camera XMP"
}

/**
 * Google Motion Photo Format 1.0 (developer.android.com/media/platform/motion-photo-format), the first-priority
 * standard for every motion photo: MUST → FAIL, SHOULD → WARN. Blocking file reads only, no subprocess.
 */
object GoogleMotionPhotoSpecChecker {
    fun check(file: File, root: BoxNode, reader: ByteReader, videoDurationUs: Long?): List<GoogleSpecFinding> {
        val xmp = findFirst(root) { node -> node.fields.any { it.name == "xmp" } }?.fields?.find { it.name == "xmp" }?.value
        return check(GmpSpecInput(file.name, sniffFormat(reader), xmp, videoDurationUs), reader)
    }

    fun check(input: GmpSpecInput, reader: ByteReader): List<GoogleSpecFinding> = buildList {
        checkXmp(input, this)
        val nameOk = FILENAME_PATTERN.matches(input.fileName)
        add(spec("gmp.filename", "2.1", SpecRequirement.SHOULD, nameOk, "파일명 / Filename",
            if (nameOk) "\"${input.fileName}\"이 권장 패턴(…MP.jpg/heic/avif)과 일치합니다"
            else "\"${input.fileName}\"이 권장 패턴 ${FILENAME_PATTERN.pattern} 과 다릅니다 — 스펙상 리더는 XMP와 영상을 무시할 수 있습니다"))
    }

    private fun checkXmp(input: GmpSpecInput, out: MutableList<GoogleSpecFinding>): GmpXmp? {
        val text = input.xmpText
        if (text == null) {
            out += spec("gmp.camera.motionphoto", "3.1", SpecRequirement.MUST, false, "Camera:MotionPhoto",
                "XMP 패킷이 없습니다 — Camera:MotionPhoto=1이 없으므로 스펙 리더는 이 파일을 모션포토로 취급하지 않습니다")
            out += spec("gmp.dir.present", "3.2", SpecRequirement.MUST, false, "Container:Directory",
                "XMP 패킷이 없어 Container:Directory가 없습니다")
            return null
        }
        val xmp = try {
            parseGmpXmp(text)
        } catch (e: Throwable) {
            // Untrusted input: deep nesting can throw StackOverflowError (an Error), as in findGoogleMotionPhotoVideo.
            out += spec("gmp.xmp.parse", "3", SpecRequirement.MUST, false, "XMP 파싱 / XMP parse",
                "XMP를 파싱할 수 없습니다: ${e.message ?: e.toString()}")
            out += specSkip("gmp.xmp.rules", "3", SpecRequirement.MUST, "XMP 규칙 / XMP rules",
                "XMP를 읽지 못해 Camera·Container·Item 규칙을 평가하지 않았습니다")
            return null
        }
        val nsOk = xmp.wrongNamespace.isEmpty()
        out += spec("gmp.ns.uris", "3", SpecRequirement.MUST, nsOk, "네임스페이스 URI / Namespace URIs",
            if (nsOk) "Camera·Container·Item 속성이 모두 스펙 URI를 사용합니다"
            else "스펙 URI가 아닌 네임스페이스의 모션포토 속성: ${xmp.wrongNamespace.joinToString(", ")} — 스펙 리더는 이 값을 찾지 못합니다")
        checkCamera(input, xmp, out)
        checkDirectory(input, xmp, out)
        return xmp
    }

    private fun checkCamera(input: GmpSpecInput, xmp: GmpXmp, out: MutableList<GoogleSpecFinding>) {
        val motionPhoto = xmp.camera["MotionPhoto"]
        out += spec("gmp.camera.motionphoto", "3.1", SpecRequirement.MUST, motionPhoto == "1", "Camera:MotionPhoto",
            when (motionPhoto) {
                "1" -> "Camera:MotionPhoto=1"
                null -> "Camera:MotionPhoto가 없습니다 — 스펙 리더는 이 파일을 모션포토로 취급하지 않습니다"
                else -> "Camera:MotionPhoto=\"$motionPhoto\" — 1이 아니면 영상이 붙어 있어도 모션포토로 취급되지 않습니다"
            })

        val version = xmp.camera["MotionPhotoVersion"]
        out += spec("gmp.camera.version", "3.1", SpecRequirement.SHOULD, version == "1", "Camera:MotionPhotoVersion",
            when (version) {
                "1" -> "Camera:MotionPhotoVersion=1"
                null -> "Camera:MotionPhotoVersion이 없습니다 (이 스펙은 버전 1을 정의합니다)"
                else -> "Camera:MotionPhotoVersion=\"$version\" — 이 스펙은 버전 1을 정의합니다"
            })

        val ptsTitle = "Camera:MotionPhotoPresentationTimestampUs"
        val pts = xmp.camera["MotionPhotoPresentationTimestampUs"]
        val ptsValue = pts?.toLongOrNull()
        val duration = input.videoDurationUs
        out += when {
            pts == null -> specInfo("gmp.camera.pts", "3.1", ptsTitle, "없음 — 리더는 영상 길이의 절반 직전 프레임을 사용합니다")
            ptsValue == null -> spec("gmp.camera.pts", "3.1", SpecRequirement.SHOULD, false, ptsTitle, "\"$pts\"는 Long 정수가 아닙니다")
            ptsValue == -1L -> spec("gmp.camera.pts", "3.1", SpecRequirement.SHOULD, true, ptsTitle, "-1 (미지정)")
            ptsValue < -1L -> spec("gmp.camera.pts", "3.1", SpecRequirement.SHOULD, false, ptsTitle, "$ptsValue — 음수는 -1(미지정)만 허용됩니다")
            duration == null -> specSkip("gmp.camera.pts", "3.1", SpecRequirement.SHOULD, ptsTitle, "영상 길이를 확인할 수 없어 범위를 검사하지 않았습니다 (값=$ptsValue)")
            else -> spec("gmp.camera.pts", "3.1", SpecRequirement.SHOULD, ptsValue <= duration, ptsTitle,
                "$ptsValue us (영상 길이 $duration us)" + if (ptsValue <= duration) "" else " — 영상 길이를 벗어납니다")
        }

        val legacy = xmp.camera.keys.filter { it.startsWith("MicroVideo") }
        if (legacy.isNotEmpty()) {
            out += specInfo("gmp.legacy.microvideo", "3.1", "레거시 MicroVideo 필드 / Legacy MicroVideo fields",
                "${legacy.joinToString(", ")} — Motion Photo 1.0에서 삭제된 필드이며 리더는 무시해야 합니다" +
                    if (xmp.directory == null) ". Container:Directory가 없어 1.0 리더는 영상을 찾지 못합니다" else "")
        }
    }

    private fun checkDirectory(input: GmpSpecInput, xmp: GmpXmp, out: MutableList<GoogleSpecFinding>) {
        val items = xmp.directory
        if (items.isNullOrEmpty()) {
            val legacyOnly = xmp.camera.keys.any { it.startsWith("MicroVideo") }
            out += spec("gmp.dir.present", "3.2", SpecRequirement.MUST, false, "Container:Directory",
                if (items == null) "Container:Directory가 없습니다" + if (legacyOnly) " (삭제된 레거시 MicroVideo V1 형식만 있음)" else ""
                else "Container:Directory에 항목이 없습니다")
            out += specSkip("gmp.dir.items", "3.3", SpecRequirement.MUST, "디렉터리 항목 / Directory items",
                "Container:Directory 항목이 없어 Item 규칙을 평가하지 않았습니다")
            return
        }
        fun label(item: GmpDirectoryItem) = "#${item.index + 1}(${item.semantic ?: "?"})"

        out += spec("gmp.dir.present", "3.2", SpecRequirement.MUST, true, "Container:Directory",
            "항목 ${items.size}개: ${items.joinToString(", ") { it.semantic ?: "?" }}")
        out += spec("gmp.dir.primary_first", "3.2", SpecRequirement.MUST, items[0].semantic == "Primary",
            "첫 항목 = Primary / First item is Primary", "첫 항목 Item:Semantic=\"${items[0].semantic ?: "없음"}\"")
        val primaries = items.count { it.semantic == "Primary" }
        out += spec("gmp.dir.primary_unique", "3.4", SpecRequirement.MUST, primaries == 1,
            "Primary 항목 1개 / One Primary item", "Primary 항목 ${primaries}개")
        val motions = items.count { it.semantic == "MotionPhoto" }
        out += spec("gmp.dir.motionphoto_unique", "3.4", SpecRequirement.MUST, motions == 1,
            "MotionPhoto 항목 1개 / One MotionPhoto item", "MotionPhoto 항목 ${motions}개")

        val noMime = items.filter { it.mime == null }
        out += spec("gmp.item.mime_required", "3.3", SpecRequirement.MUST, noMime.isEmpty(), "Item:Mime 필수 / Item:Mime required",
            if (noMime.isEmpty()) "모든 항목에 Item:Mime이 있습니다" else "Item:Mime 없는 항목: ${noMime.joinToString { label(it) }}")
        val noSemantic = items.filter { it.semantic == null }
        out += spec("gmp.item.semantic_required", "3.3", SpecRequirement.MUST, noSemantic.isEmpty(), "Item:Semantic 필수 / Item:Semantic required",
            if (noSemantic.isEmpty()) "모든 항목에 Item:Semantic이 있습니다" else "Item:Semantic 없는 항목: ${noSemantic.joinToString { label(it) }}")
        val badMime = items.filter { it.mime != null && it.mime !in ALLOWED_MIMES }
        out += spec("gmp.item.mime_value", "3.4", SpecRequirement.MUST, badMime.isEmpty(), "Item:Mime 허용값 / Item:Mime values",
            if (badMime.isEmpty()) "모든 Item:Mime이 허용값입니다"
            else "허용되지 않은 Item:Mime: ${badMime.joinToString { "${label(it)}=\"${it.mime}\"" }} (허용: ${ALLOWED_MIMES.joinToString()})")

        val primary = items[0]
        val expectedPrimaryMime = when (input.format) {
            ContainerFormat.JPEG -> "image/jpeg"
            ContainerFormat.HEIF -> "image/heic"
            ContainerFormat.AVIF -> "image/avif"
            else -> null
        }
        out += spec("gmp.item.primary_mime", "3.3", SpecRequirement.MUST,
            expectedPrimaryMime != null && primary.mime == expectedPrimaryMime, "Primary Item:Mime",
            if (expectedPrimaryMime == null) "기본 이미지가 JPEG·HEIC·AVIF가 아닙니다 (감지 형식: ${input.format?.displayName ?: "알 수 없음"})"
            else "첫 항목 Item:Mime=\"${primary.mime ?: "없음"}\", 실제 이미지 ${input.format!!.displayName} → \"$expectedPrimaryMime\"")

        val lengthProblems = items.drop(1).mapNotNull { item ->
            val raw = item.length
            val value = raw?.toLongOrNull()
            when {
                raw == null -> "${label(item)} Item:Length 없음"
                value == null || value < 0 -> "${label(item)} Item:Length=\"$raw\"는 0 이상의 정수가 아님"
                value == 0L && item.semantic == "MotionPhoto" -> "${label(item)} Item:Length=0 — 영상 항목은 양수여야 함"
                else -> null
            }
        }
        out += spec("gmp.item.length_required", "3.3", SpecRequirement.MUST, lengthProblems.isEmpty(),
            "보조 항목 Item:Length / Secondary Item:Length",
            if (lengthProblems.isEmpty()) "모든 보조 항목에 유효한 Item:Length가 있습니다 (0은 앞 항목과 데이터 공유)" else lengthProblems.joinToString("; "))
        val primaryLengthOk = primary.length == null || primary.length == "0"
        out += spec("gmp.item.primary_length", "3.3", SpecRequirement.SHOULD, primaryLengthOk, "Primary Item:Length",
            if (primary.length == null) "생략됨 (0으로 간주)"
            else "Primary Item:Length=\"${primary.length}\"" + if (primaryLengthOk) "" else " — 0이어야 합니다")

        val laterPadding = items.drop(1).filter { it.padding != null }
        out += spec("gmp.item.padding_first_only", "3.3", SpecRequirement.MUST, laterPadding.isEmpty(),
            "Item:Padding은 첫 항목만 / Padding only on the first item",
            if (laterPadding.isEmpty()) "첫 항목 외에는 Item:Padding이 없습니다"
            else "첫 항목이 아닌데 Item:Padding이 있음: ${laterPadding.joinToString { "${label(it)}=\"${it.padding}\"" }}")
        val padding = primary.padding
        val paddingValue = padding?.toLongOrNull()
        if (input.format == ContainerFormat.HEIF || input.format == ContainerFormat.AVIF) {
            out += spec("gmp.item.padding_isobmff", "3.3", SpecRequirement.MUST, paddingValue == 8L, "HEIC/AVIF Primary Item:Padding",
                if (padding == null) "Item:Padding이 없습니다 — HEIC/AVIF는 mpvd 박스 헤더 길이 8이 필수입니다"
                else "Item:Padding=\"$padding\"" + if (paddingValue == 8L) "" else " — mpvd 박스 헤더 길이 8이어야 합니다")
        } else {
            val ok = padding == null || (paddingValue != null && paddingValue > 0)
            out += spec("gmp.item.padding_jpeg", "3.3", SpecRequirement.SHOULD, ok, "JPEG Primary Item:Padding",
                if (padding == null) "생략됨" else "Item:Padding=\"$padding\"" + if (ok) "" else " — 스펙은 양의 정수로 정의합니다")
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.motionphoto.GoogleMotionPhotoSpecCheckerTest'`
Expected: PASS (19 tests).

- [ ] **Step 5: Commit**

```bash
git branch --show-current   # must print feat/google-motion-photo-spec
git add app/src/main/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoSpecChecker.kt app/src/test/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoSpecCheckerTest.kt
git commit -m "feat: check Google Motion Photo 1.0 XMP, directory and filename rules"
```

---

### Task 3: Spec checker — byte layout and HEIC/AVIF mpvd rules

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoLayout.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoSpecChecker.kt` (`checkXmp` returns the parsed XMP; `check(input, reader)` calls `checkLayout`)
- Test: `app/src/test/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoLayoutTest.kt`

**Interfaces:**
- Consumes: Task 1 `GmpXmp`, `GmpDirectoryItem`; Task 2 `spec`, `specSkip`, `GmpSpecInput`, `HDR_GAINMAP_NS`, `SpecRequirement`; existing `com.multiviewer.parser.correctMp4StartOffset(reader, approxStart): Long` (internal, same module); test helpers `fileOf`, `hex` (package `com.multiviewer.parser`).
- Produces: `internal data class GmpBox(type, offset, headerSize, size, rawSize, truncated)`; `internal fun walkBoxes(reader, start, limit): List<GmpBox>`; `internal fun primaryJpegEnd(reader): Long?`; `internal fun checkLayout(input: GmpSpecInput, xmp: GmpXmp?, reader: ByteReader, out: MutableList<GoogleSpecFinding>)`. Rule IDs: `gmp.layout`, `gmp.layout.video_start`, `gmp.item.video_mime`, `gmp.layout.packed`, `gmp.layout.video_last`, `gmp.gainmap.item`, `gmp.mpvd.present`, `gmp.mpvd.size`, `gmp.mpvd.last`, `gmp.mpvd.length`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoLayoutTest.kt`:

```kotlin
package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.ContainerFormat
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.fileOf
import com.multiviewer.parser.hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleMotionPhotoLayoutTest {
    private fun be32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8L + payload.size) + type.toByteArray(Charsets.US_ASCII) + payload
    private fun mp4(brand: String = "isom") =
        box("ftyp", brand.toByteArray(Charsets.US_ASCII) + be32(0) + "isommp41".toByteArray(Charsets.US_ASCII)) +
            box("mdat", ByteArray(40) { 1 }) + box("moov", ByteArray(24))

    // SOI, APP0, SOS, entropy data with FF00 stuffing and an RST marker, EOI.
    private val jpeg = hex("FFD8 FFE0 0006 4A464946 FFDA 0008 010100003F00 1234FF0056FFD078 FFD9")

    private fun check(bytes: ByteArray, xmp: String, format: ContainerFormat = ContainerFormat.JPEG, name: String = "PXL_1.MP.jpg") =
        ByteReader.open(fileOf(bytes)).use { GoogleMotionPhotoSpecChecker.check(GmpSpecInput(name, format, xmp, 1_000_000), it) }

    private fun List<GoogleSpecFinding>.rule(id: String) = single { it.ruleId == id }
    private fun jpegItems(videoLength: Int, primary: Map<String, String> = emptyMap(), video: Map<String, String> = emptyMap()) = listOf(
        mapOf("Semantic" to "Primary", "Mime" to "image/jpeg") + primary,
        mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "$videoLength") + video,
    )
    private val bad = setOf(SefIntegritySeverity.CRITICAL, SefIntegritySeverity.WARNING)

    @Test
    fun `primaryJpegEnd walks segments and entropy data to the EOI`() {
        ByteReader.open(fileOf(jpeg + mp4())).use { assertEquals(jpeg.size.toLong(), primaryJpegEnd(it)) }
        ByteReader.open(fileOf(mp4())).use { assertNull(primaryJpegEnd(it)) }
    }

    @Test
    fun `walkBoxes stops at bytes that are not a box`() {
        val bytes = mp4() + ByteArray(10) { 0x0E } + "SEFT".toByteArray()
        ByteReader.open(fileOf(bytes)).use { reader ->
            val boxes = walkBoxes(reader, 0, reader.length)
            assertEquals(listOf("ftyp", "mdat", "moov"), boxes.map { it.type })
            assertEquals(mp4().size.toLong(), boxes.last().end)
        }
    }

    @Test
    fun `compliant JPEG motion photo has no FAIL or WARN`() {
        val video = mp4()
        val result = check(jpeg + video, gmpXmp(items = jpegItems(video.size)))
        assertTrue(result.none { it.severity in bad }, result.joinToString("\n"))
        listOf("gmp.layout.video_start", "gmp.layout.packed", "gmp.layout.video_last", "gmp.item.video_mime")
            .forEach { assertEquals(SefIntegritySeverity.PASS, result.rule(it).severity, it) }
    }

    @Test
    fun `Samsung-style JPEG fails video start and trailing bytes but is packed`() {
        val sefPrefix = ByteArray(161) { 0x0E }
        val sefTrailer = ByteArray(20) { 0x0E } + "SEFT".toByteArray()
        val video = mp4()
        val declared = sefPrefix.size + video.size + sefTrailer.size
        val result = check(jpeg + sefPrefix + video + sefTrailer, gmpXmp(items = jpegItems(declared)))
        assertEquals(SefIntegritySeverity.PASS, result.rule("gmp.layout.packed").severity)
        val start = result.rule("gmp.layout.video_start")
        assertEquals(SefIntegritySeverity.CRITICAL, start.severity)
        assertTrue(start.detail.contains("161"), start.detail)
        val last = result.rule("gmp.layout.video_last")
        assertEquals(SefIntegritySeverity.CRITICAL, last.severity)
        assertTrue(last.detail.contains("SEF"), last.detail)
    }

    @Test
    fun `gap between image and video fails packing unless Padding declares it`() {
        val video = mp4()
        val bytes = jpeg + ByteArray(10) + video
        val gap = check(bytes, gmpXmp(items = jpegItems(video.size))).rule("gmp.layout.packed")
        assertEquals(SefIntegritySeverity.CRITICAL, gap.severity)
        assertTrue(gap.detail.contains("10바이트"), gap.detail)
        assertEquals(SefIntegritySeverity.PASS,
            check(bytes, gmpXmp(items = jpegItems(video.size, primary = mapOf("Padding" to "10")))).rule("gmp.layout.packed").severity)
    }

    @Test
    fun `Length larger than the file fails video start`() {
        val result = check(jpeg + mp4(), gmpXmp(items = jpegItems(100_000)))
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.layout.video_start").severity)
    }

    @Test
    fun `QuickTime video declared as mp4 fails the video Mime rule`() {
        val video = mp4(brand = "qt  ")
        val result = check(jpeg + video, gmpXmp(items = jpegItems(video.size)))
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.item.video_mime").severity)
    }

    @Test
    fun `Ultra HDR needs a GainMap item before the video`() {
        val video = mp4()
        val hdr = "xmlns:hdrgm=\"$HDR_GAINMAP_NS\" hdrgm:Version=\"1.0\""
        val missing = check(jpeg + video, gmpXmp(items = jpegItems(video.size), extraDescriptionAttrs = hdr))
        assertEquals(SefIntegritySeverity.CRITICAL, missing.rule("gmp.gainmap.item").severity)

        val gainMap = jpeg
        val withGainMap = check(jpeg + gainMap + video, gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary", "Mime" to "image/jpeg"),
            mapOf("Semantic" to "GainMap", "Mime" to "image/jpeg", "Length" to "${gainMap.size}"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "${video.size}"),
        ), extraDescriptionAttrs = hdr))
        assertEquals(SefIntegritySeverity.PASS, withGainMap.rule("gmp.gainmap.item").severity)
        assertEquals(SefIntegritySeverity.PASS, withGainMap.rule("gmp.layout.packed").severity)
    }

    private val heicFtyp = box("ftyp", "heic".toByteArray() + be32(0) + "mif1heic".toByteArray())
    private fun heicItems(videoLength: Int, padding: String? = "8") = listOf(
        mapOf("Semantic" to "Primary", "Mime" to "image/heic") + (if (padding != null) mapOf("Padding" to padding) else emptyMap()),
        mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "$videoLength"),
    )

    @Test
    fun `compliant HEIC motion photo passes the mpvd and layout rules`() {
        val video = mp4()
        val bytes = heicFtyp + box("meta", ByteArray(20)) + box("mpvd", video)
        val result = check(bytes, gmpXmp(items = heicItems(video.size)), ContainerFormat.HEIF, "a.MP.heic")
        assertTrue(result.none { it.severity in bad }, result.joinToString("\n"))
        listOf("gmp.mpvd.present", "gmp.mpvd.size", "gmp.mpvd.last", "gmp.mpvd.length", "gmp.layout.packed",
            "gmp.layout.video_start", "gmp.layout.video_last").forEach { assertEquals(SefIntegritySeverity.PASS, result.rule(it).severity, it) }
    }

    @Test
    fun `Samsung-style HEIC with a box after mpvd fails`() {
        val video = mp4()
        val mpvd = box("mpvd", video)
        val sefd = box("sefd", ByteArray(30))
        val bytes = heicFtyp + box("meta", ByteArray(20)) + mpvd + sefd
        val result = check(bytes, gmpXmp(items = heicItems(mpvd.size + sefd.size, padding = "0")), ContainerFormat.HEIF, "a.heic")
        listOf("gmp.item.padding_isobmff", "gmp.mpvd.last", "gmp.mpvd.length", "gmp.layout.video_start", "gmp.layout.video_last")
            .forEach { assertEquals(SefIntegritySeverity.CRITICAL, result.rule(it).severity, it) }
        assertTrue(result.rule("gmp.mpvd.last").detail.contains("sefd"))
    }

    @Test
    fun `HEIC without a top-level mpvd fails`() {
        val video = mp4()
        val result = check(heicFtyp + box("meta", ByteArray(20)) + video, gmpXmp(items = heicItems(video.size)), ContainerFormat.HEIF, "a.MP.heic")
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.mpvd.present").severity)
    }

    @Test
    fun `mpvd with size 0 fails`() {
        val video = mp4()
        val bytes = heicFtyp + box("meta", ByteArray(20)) + be32(0) + "mpvd".toByteArray() + video
        val result = check(bytes, gmpXmp(items = heicItems(video.size)), ContainerFormat.HEIF, "a.MP.heic")
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.mpvd.size").severity)
    }

    @Test
    fun `no usable MotionPhoto item skips the layout rules instead of passing them`() {
        val result = check(jpeg + mp4(), gmpXmp(items = listOf(mapOf("Semantic" to "Primary", "Mime" to "image/jpeg"))))
        assertEquals(SefIntegritySeverity.SKIPPED, result.rule("gmp.layout").severity)
        assertTrue(result.none { it.ruleId.startsWith("gmp.layout.") })
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.motionphoto.GoogleMotionPhotoLayoutTest'`
Expected: compilation FAIL — `Unresolved reference 'primaryJpegEnd'` / `walkBoxes`.

- [ ] **Step 3: Implement the layout rules**

`app/src/main/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoLayout.kt`:

```kotlin
package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.ContainerFormat
import com.multiviewer.parser.correctMp4StartOffset

internal data class GmpBox(
    val type: String,
    val offset: Long,
    val headerSize: Int,
    val size: Long,
    /** The size field as written: 0 means "to end of file" (forbidden for mpvd). */
    val rawSize: Long,
    /** The box claims more bytes than remain before the walk limit. */
    val truncated: Boolean,
) {
    val end: Long get() = offset + size
}

/**
 * Consecutive ISOBMFF boxes in [start, limit). Stops at the first header that is not a box (e.g. a SEF
 * trailer after an MP4); an overrunning box is kept with truncated=true and ends the walk.
 */
internal fun walkBoxes(reader: ByteReader, start: Long, limit: Long): List<GmpBox> {
    val boxes = mutableListOf<GmpBox>()
    var pos = start
    while (pos + 8 <= limit) {
        val raw = reader.readUInt32(pos)
        val type = reader.readFourCC(pos + 4)
        if (!type.all { it in ' '..'~' }) break
        var header = 8
        val size = when (raw) {
            1L -> {
                if (pos + 16 > limit) break
                header = 16
                reader.readUInt64(pos + 8)
            }
            0L -> limit - pos
            else -> raw
        }
        if (size < header) break
        if (pos + size > limit) {
            boxes += GmpBox(type, pos, header, size, raw, truncated = true)
            break
        }
        boxes += GmpBox(type, pos, header, size, raw, truncated = false)
        pos += size
    }
    return boxes
}

/** End (exclusive) of the primary JPEG: through the EOI that closes its scan data. Null when the markers can't be walked. */
internal fun primaryJpegEnd(reader: ByteReader): Long? {
    val length = reader.length
    if (length < 4 || reader.readUInt8(0) != 0xFF || reader.readUInt8(1) != 0xD8) return null
    var pos = 2L
    while (pos + 2 <= length) {
        if (reader.readUInt8(pos) != 0xFF) return null
        val marker = reader.readUInt8(pos + 1)
        if (marker == 0xFF) { pos++; continue }
        if (marker == 0xD9) return pos + 2
        if (marker == 0x01 || marker in 0xD0..0xD7) { pos += 2; continue }
        if (pos + 4 > length) return null
        val segmentLength = reader.readUInt16(pos + 2)
        if (segmentLength < 2) return null
        pos += 2 + segmentLength
        if (marker == 0xDA) pos = endOfEntropyData(reader, pos) ?: return null
    }
    return null
}

// Entropy-coded data runs until a marker other than FF00 stuffing, RSTn or FF fill; returns that marker's offset.
private fun endOfEntropyData(reader: ByteReader, start: Long): Long? {
    val length = reader.length
    var pos = start
    while (pos < length - 1) {
        val chunk = reader.readBytes(pos, minOf(65536L, length - pos).toInt())
        for (i in 0 until chunk.size - 1) {
            if (chunk[i] == 0xFF.toByte()) {
                val next = chunk[i + 1].toInt() and 0xFF
                if (next != 0x00 && next !in 0xD0..0xD7 && next != 0xFF) return pos + i
            }
        }
        pos += chunk.size - 1 // the last byte is re-read with its successor
    }
    return null
}

private fun isFtyp(reader: ByteReader, offset: Long) =
    offset >= 0 && offset + 12 <= reader.length && reader.readFourCC(offset + 4) == "ftyp"

/** §3.2–3.4 byte layout and §4 mpvd rules. Never throws on malformed input: an unreadable file becomes a SKIP. */
internal fun checkLayout(input: GmpSpecInput, xmp: GmpXmp?, reader: ByteReader, out: MutableList<GoogleSpecFinding>) {
    try {
        layoutRules(input, xmp, reader, out)
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (e: Exception) {
        out += specSkip("gmp.layout", "3.3", SpecRequirement.MUST, "항목 배치 / Item layout",
            "파일을 읽는 중 오류가 발생했습니다: ${e.message ?: e.toString()}")
    }
}

private fun layoutRules(input: GmpSpecInput, xmp: GmpXmp?, reader: ByteReader, out: MutableList<GoogleSpecFinding>) {
    val fileLength = reader.length
    val isobmff = input.format == ContainerFormat.HEIF || input.format == ContainerFormat.AVIF
    val topLevel = if (isobmff) walkBoxes(reader, 0, fileLength) else emptyList()
    val mpvd = topLevel.firstOrNull { it.type == "mpvd" }
    if (isobmff) checkMpvdBox(topLevel, mpvd, fileLength, out)

    val items = xmp?.directory.orEmpty()
    val motionIndex = items.indexOfFirst { it.semantic == "MotionPhoto" }
    val motion = items.getOrNull(motionIndex)
    val length = motion?.length?.toLongOrNull()?.takeIf { it > 0 }
    if (motion == null || length == null) {
        out += specSkip("gmp.layout", "3.3", SpecRequirement.MUST, "항목 배치 / Item layout",
            "유효한 Item:Length를 가진 MotionPhoto 항목이 없어 영상 위치·배치 규칙을 평가하지 않았습니다")
        return
    }
    val startTitle = "영상 시작 위치 / Video start"
    if (length > fileLength) {
        out += spec("gmp.layout.video_start", "3.3", SpecRequirement.MUST, false, startTitle,
            "MotionPhoto Item:Length=$length 가 파일 크기 $fileLength 보다 큽니다")
        return
    }
    val declaredStart = fileLength - length
    val ftypAtDeclared = isFtyp(reader, declaredStart)
    val videoStart = if (ftypAtDeclared) declaredStart
    else correctMp4StartOffset(reader, declaredStart).takeIf { it != declaredStart && isFtyp(reader, it) }
    out += spec("gmp.layout.video_start", "3.3", SpecRequirement.MUST, ftypAtDeclared, startTitle,
        when {
            ftypAtDeclared -> "파일 끝 − Item:Length($length) = $declaredStart 에 영상 ftyp가 있습니다"
            videoStart != null -> "파일 끝 − Item:Length = $declaredStart 이지만 실제 영상 ftyp는 ${videoStart - declaredStart}바이트 떨어진 $videoStart 에 있습니다 — 항목 바이트는 영상 파일 원본이어야 합니다"
            else -> "파일 끝 − Item:Length = $declaredStart 근처(±1024바이트)에서 영상 ftyp를 찾지 못했습니다"
        })

    val mimeTitle = "영상 Item:Mime / Video Item:Mime"
    if (videoStart != null) {
        val brand = reader.readFourCC(videoStart + 8).trim()
        val expected = if (brand == "qt") "video/quicktime" else "video/mp4"
        out += spec("gmp.item.video_mime", "3.4", SpecRequirement.MUST, motion.mime == expected, mimeTitle,
            "Item:Mime=\"${motion.mime ?: "없음"}\", 실제 ftyp major_brand=\"$brand\" → \"$expected\"")
    } else {
        out += specSkip("gmp.item.video_mime", "3.4", SpecRequirement.MUST, mimeTitle, "영상 ftyp를 찾지 못했습니다")
    }

    val packedTitle = "빈틈없는 배치 / Tightly packed"
    val primaryEnd = if (isobmff) mpvd?.offset else primaryJpegEnd(reader)
    if (primaryEnd == null || motionIndex < 1) {
        out += specSkip("gmp.layout.packed", "3.2", SpecRequirement.MUST, packedTitle,
            if (primaryEnd == null) "기본 이미지의 끝을 확인할 수 없습니다" else "MotionPhoto가 첫 항목입니다")
    } else {
        val padding = items[0].padding?.toLongOrNull() ?: 0L
        val between = items.subList(1, motionIndex).sumOf { it.length?.toLongOrNull()?.coerceAtLeast(0) ?: 0L }
        val expected = primaryEnd + padding + between
        out += spec("gmp.layout.packed", "3.2", SpecRequirement.MUST, expected == declaredStart, packedTitle,
            "기본 이미지 끝 $primaryEnd + Padding $padding" + (if (between > 0) " + 앞 항목 Length $between" else "") +
                " = $expected, 파일 끝 − Item:Length = $declaredStart" +
                if (expected == declaredStart) "" else " — ${declaredStart - expected}바이트 차이")
    }

    val lastTitle = "영상이 파일 끝 / Video at end of file"
    if (videoStart != null) {
        val limit = if (mpvd != null && videoStart in mpvd.offset until mpvd.end) mpvd.end.coerceAtMost(fileLength) else fileLength
        val boxes = walkBoxes(reader, videoStart, limit)
        val truncated = boxes.lastOrNull()?.takeIf { it.truncated }
        val videoEnd = boxes.lastOrNull()?.let { if (it.truncated) it.offset else it.end } ?: videoStart
        val trailing = fileLength - videoEnd
        val sef = trailing > 0 && fileLength >= 4 && reader.readFourCC(fileLength - 4) == "SEFT"
        out += spec("gmp.layout.video_last", "3.4", SpecRequirement.MUST, truncated == null && trailing == 0L, lastTitle,
            when {
                truncated != null -> "영상의 ${truncated.type} 박스(size ${truncated.size})가 $limit 을 넘어 잘렸습니다"
                trailing == 0L -> "영상 컨테이너가 파일 끝($fileLength)에서 끝납니다"
                else -> "영상 컨테이너가 $videoEnd 에서 끝나고 뒤에 ${trailing}바이트가 더 있습니다" +
                    (if (sef) " (삼성 SEF 트레일러)" else "") + " — MotionPhoto 항목 뒤에는 어떤 바이트도 올 수 없습니다"
            })
    } else {
        out += specSkip("gmp.layout.video_last", "3.4", SpecRequirement.MUST, lastTitle, "영상 ftyp를 찾지 못했습니다")
    }

    if (input.xmpText?.contains(HDR_GAINMAP_NS) == true) {
        val gainIndex = items.indexOfFirst { it.semantic == "GainMap" }
        out += spec("gmp.gainmap.item", "3.4", SpecRequirement.MUST, gainIndex in 1 until motionIndex, "GainMap 항목 / GainMap item",
            when {
                gainIndex < 0 -> "게인맵(hdrgm) 이미지인데 GainMap 항목이 없습니다"
                gainIndex > motionIndex -> "GainMap 항목(#${gainIndex + 1})이 MotionPhoto 항목(#${motionIndex + 1}) 뒤에 있습니다"
                else -> "GainMap 항목 #${gainIndex + 1}이 MotionPhoto 항목 앞에 있습니다"
            })
    }

    if (mpvd != null && !mpvd.truncated) {
        val payload = mpvd.size - mpvd.headerSize
        out += spec("gmp.mpvd.length", "4", SpecRequirement.MUST, payload == length, "mpvd 데이터 = 영상 Length / mpvd payload = Length",
            "mpvd 데이터 $payload 바이트, MotionPhoto Item:Length $length" + if (payload == length) "" else " — ${length - payload}바이트 차이")
    }
}

private fun checkMpvdBox(topLevel: List<GmpBox>, mpvd: GmpBox?, fileLength: Long, out: MutableList<GoogleSpecFinding>) {
    out += spec("gmp.mpvd.present", "4", SpecRequirement.MUST, mpvd != null, "최상위 mpvd 박스 / Top-level mpvd box",
        if (mpvd != null) "mpvd @ ${mpvd.offset}, size ${mpvd.size}"
        else "최상위 mpvd 박스가 없습니다 (최상위 박스: ${topLevel.joinToString { it.type }})")
    if (mpvd == null) return
    out += spec("gmp.mpvd.size", "4", SpecRequirement.MUST, mpvd.rawSize != 0L && !mpvd.truncated, "mpvd 크기 / mpvd size",
        when {
            mpvd.rawSize == 0L -> "mpvd size 필드가 0입니다 (스펙상 금지)"
            mpvd.truncated -> "mpvd(size ${mpvd.size})가 파일 끝($fileLength)을 넘습니다"
            else -> "size ${mpvd.size}"
        })
    val after = topLevel.filter { it.offset > mpvd.offset }
    val trailingBytes = fileLength - mpvd.end
    out += spec("gmp.mpvd.last", "4", SpecRequirement.MUST, after.isEmpty() && trailingBytes <= 0, "mpvd가 마지막 박스 / mpvd is the last box",
        when {
            after.isNotEmpty() -> "mpvd 뒤에 박스가 있습니다: ${after.joinToString { "${it.type}(${it.size})" }}"
            trailingBytes > 0 -> "mpvd 뒤에 박스가 아닌 ${trailingBytes}바이트가 있습니다"
            else -> "mpvd가 파일의 마지막 박스입니다"
        })
}
```

- [ ] **Step 4: Wire the layout rules into the checker**

In `GoogleMotionPhotoSpecChecker.kt`, replace the body of `check(input, reader)`:

```kotlin
    fun check(input: GmpSpecInput, reader: ByteReader): List<GoogleSpecFinding> = buildList {
        val xmp = checkXmp(input, this)
        checkLayout(input, xmp, reader, this)
        val nameOk = FILENAME_PATTERN.matches(input.fileName)
        add(spec("gmp.filename", "2.1", SpecRequirement.SHOULD, nameOk, "파일명 / Filename",
            if (nameOk) "\"${input.fileName}\"이 권장 패턴(…MP.jpg/heic/avif)과 일치합니다"
            else "\"${input.fileName}\"이 권장 패턴 ${FILENAME_PATTERN.pattern} 과 다릅니다 — 스펙상 리더는 XMP와 영상을 무시할 수 있습니다"))
    }
```

- [ ] **Step 5: Run the checker tests**

Run: `./gradlew :app:test --tests 'com.multiviewer.parser.motionphoto.*'`
Expected: PASS (GoogleMotionPhotoXmpTest 7, GoogleMotionPhotoSpecCheckerTest 19, GoogleMotionPhotoLayoutTest 13).

- [ ] **Step 6: Commit**

```bash
git branch --show-current   # must print feat/google-motion-photo-spec
git add app/src/main/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoLayout.kt app/src/main/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoSpecChecker.kt app/src/test/kotlin/com/multiviewer/parser/motionphoto/GoogleMotionPhotoLayoutTest.kt
git commit -m "feat: check Google Motion Photo byte layout and HEIC mpvd rules"
```

---

### Task 4: Run the checker for every motion photo and show it everywhere

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzer.kt` (report model, `analyze`, delete `analyzeGoogleXmpSection` and `analyzeAppleMpvdSection`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/MotionPhotoReportContent.kt:39-80`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ContentFindingMappers.kt:160-161`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ContentCheckSnapshot.kt:342,356`
- Modify: `app/src/main/kotlin/com/multiviewer/cli/ImageIntegrityJson.kt:55-85`
- Modify tests: `app/src/test/kotlin/com/multiviewer/ui/MotionPhotoIntegrityAnalyzerTest.kt`, `ContentFindingMappersTest.kt:51-64`, `MotionPhotoIntegrationTest.kt:56-59`, `ContentCheckSnapshotTest.kt:216-239,305-308`, `app/src/test/kotlin/com/multiviewer/cli/ImageIntegrityJsonTest.kt`

**Interfaces:**
- Consumes: Task 2/3 `GoogleMotionPhotoSpecChecker.check(file, root, reader, videoDurationUs)`, `GoogleSpecFinding`, `SpecRequirement`, `toSefCheckResult()`, `groupLabel()`.
- Produces: `MotionPhotoIntegrityReport(detectedFormats, sefSection, googleSpec: List<GoogleSpecFinding>, decodeChecks, overallSeverity, deepAnalysis = null)` (positional order kept, `googleXmpChecks`/`appleMpvdChecks` removed); `internal fun hasGoogleMotionPhotoXmp(root: BoxNode): Boolean`; JSON section `google_motion_photo` with `ruleId`, `specSection`, `requirement`.

- [ ] **Step 1: Write the failing analyzer tests**

In `MotionPhotoIntegrityAnalyzerTest.kt`:

1. Delete these tests (their rules move to the checker tests): every test whose name starts with `` `analyzeGoogleXmpSection `` (12 near the top plus `analyzeGoogleXmpSection reports SKIPPED for PresentationTimestampUs when video duration is unavailable`) and every test whose name starts with `` `analyzeAppleMpvdSection `` (5).
2. Delete the now-unused private helpers `tempFileWithFtypAt`, `tempFileWithNoFtyp`, `googleDirectoryXmp`, `googleMicroVideoXmp`, `rootWithXmp`.
3. In `` `detectedFormats includes SAMSUNG_SEF for a well-formed sefd trailer with MotionPhoto_Data` ``, append after the existing assertion:

```kotlin
        // Google Motion Photo 1.0 is checked for every motion photo: a SEF-only file has no Camera:MotionPhoto.
        assertEquals(SefIntegritySeverity.CRITICAL, report.googleSpec.single { it.ruleId == "gmp.camera.motionphoto" }.severity)
        assertEquals(SefIntegritySeverity.CRITICAL, report.overallSeverity)
```

4. Add at the end of the class:

```kotlin
    @Test
    fun `a bare sefd trailer without motion data runs no Google spec rules`() {
        val trailerBytes = buildSefTrailer(listOf(SefTestField(0x0a01, "Image_UTC_Data", "1700000000000".toByteArray())))
        val file = File.createTempFile("motion-photo-plain-sef-spec", ".bin")
        file.deleteOnExit()
        file.writeBytes(trailerBytes)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = trailerBytes.size.toLong(),
            children = listOf(BoxNode(type = "sefd", offset = 0, headerSize = 0, size = trailerBytes.size.toLong())))
        assertTrue(MotionPhotoIntegrityAnalyzer.analyze(file, root).googleSpec.isEmpty())
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.MotionPhotoIntegrityAnalyzerTest'`
Expected: compilation FAIL — `Unresolved reference 'googleSpec'`.

- [ ] **Step 3: Change the report model and the analyzer**

In `MotionPhotoIntegrityAnalyzer.kt`:

a) Replace the report class:

```kotlin
data class MotionPhotoIntegrityReport(
    val detectedFormats: List<MotionPhotoFormat>,
    val sefSection: SefIntegrityReport?,
    /** Google Motion Photo 1.0 compliance: the first-priority standard, checked for every detected motion photo. */
    val googleSpec: List<GoogleSpecFinding>,
    val decodeChecks: List<SefCheckResult>,
    val overallSeverity: SefIntegritySeverity,
    val deepAnalysis: MotionPhotoDeepAnalysis? = null,
)
```

b) Delete `analyzeGoogleXmpSection` (with its leading comment block) and `analyzeAppleMpvdSection` (with its leading comment block). Keep `probeVideoDurationUs` and `analyzeDecodability`. Add:

```kotlin
/** The primary XMP declares a Google motion photo (1.0 Camera/Container fields or the legacy MicroVideo ones). */
internal fun hasGoogleMotionPhotoXmp(root: BoxNode): Boolean =
    findFirst(root) { node -> node.fields.any { it.name == "xmp" } }?.fields?.find { it.name == "xmp" }?.value
        ?.let { it.contains("MotionPhoto", ignoreCase = true) || it.contains("MicroVideo", ignoreCase = true) } == true
```

c) Add imports `com.multiviewer.parser.motionphoto.GoogleMotionPhotoSpecChecker` and `com.multiviewer.parser.motionphoto.GoogleSpecFinding` (and `com.multiviewer.parser.findFirst` if the file does not import it yet — it currently uses the fully qualified `com.multiviewer.parser.findFirst`; either form is fine).

d) In `analyze`, replace the block from `val googleChecks = analyzeGoogleXmpSection(...)` through the closing `}` of `val detectedFormats = buildList { ... }` with:

```kotlin
            val hasGoogleXmp = hasGoogleMotionPhotoXmp(root)
            val hasMpvd = com.multiviewer.parser.findFirst(root) { it.type == "mpvd" || it.type == "EmbeddedVideoData" } != null
            val detectedFormats = buildList {
                // (keep the existing SEF comment block and sefEntriesUntrustworthy logic verbatim here)
                val sefEntriesUntrustworthy = sefSection != null && (
                    sefSection.structuralChecks.any { it.severity == SefIntegritySeverity.CRITICAL } ||
                        sefSection.directoryEntries.any { it.status == SefIntegritySeverity.CRITICAL } ||
                        sefSection.declaredEntryCountSeverity == SefIntegritySeverity.CRITICAL
                )
                if (sefSection?.directoryEntries?.any { it.name == "MotionPhoto_Data" } == true || sefEntriesUntrustworthy) {
                    add(MotionPhotoFormat.SAMSUNG_SEF)
                }
                if (hasGoogleXmp) add(MotionPhotoFormat.GOOGLE_XMP)
                if (hasMpvd) add(MotionPhotoFormat.APPLE_MPVD)
            }
            // Google Motion Photo 1.0 is the first-priority standard: every detected motion photo is held to it,
            // Samsung SEF-only files included (they fail §3.1 Camera:MotionPhoto).
            val googleSpec = if (detectedFormats.isNotEmpty()) {
                GoogleMotionPhotoSpecChecker.check(file, root, reader, videoDurationUs)
            } else {
                emptyList()
            }
```

e) In `allSeverities`, replace `(googleChecks + appleChecks + decodeChecks).map { it.severity }` with `googleSpec.map { it.severity } + decodeChecks.map { it.severity }`.

f) Replace the final constructor call with `MotionPhotoIntegrityReport(detectedFormats, sefSection, googleSpec, decodeChecks, overall, deepAnalysis)`.

- [ ] **Step 4: Update the consumers**

`MotionPhotoReportContent.kt`:
- In `motionPhotoFormatLabel`, change the `APPLE_MPVD` branch to `MotionPhotoFormat.APPLE_MPVD -> "HEIC/AVIF mpvd (구글 모션포토)"`.
- Replace `item { CheckSection("구글 XMP 모션포토 구성", report.googleXmpChecks) }` (and its `// 1.` comment) with:

```kotlin
            // 1. Google Motion Photo 1.0 — first-priority spec for every motion photo, one table per rule group
            report.googleSpec.groupBy { it.groupLabel() }.forEach { (group, findings) ->
                item { CheckSection("구글 모션포토 1.0 · $group", findings.map { it.toSefCheckResult() }) }
            }
```

- Delete the `// 4. HEIC mpvd 박스 ...` comment and `item { CheckSection(motionPhotoFormatLabel(MotionPhotoFormat.APPLE_MPVD), report.appleMpvdChecks) }`.
- Add imports `com.multiviewer.parser.motionphoto.groupLabel` and `com.multiviewer.parser.motionphoto.toSefCheckResult`.

`ContentFindingMappers.kt` — replace lines 160–161 with:

```kotlin
    addAll(report.googleSpec.map { it.toSefCheckResult().toFinding("motion.${it.ruleId}", FindingStage.MOTION_PHOTO) })
```

(add import `com.multiviewer.parser.motionphoto.toSefCheckResult`).

`ContentCheckSnapshot.kt` — replace `m.googleXmpChecks.forEach { add("Google XMP" to it) }` with
`m.googleSpec.forEach { add("Google Motion Photo 1.0" to it.toSefCheckResult()) }`, delete
`m.appleMpvdChecks.forEach { add("HEIC mpvd" to it) }`, add import `com.multiviewer.parser.motionphoto.toSefCheckResult`.

`ImageIntegrityJson.kt` — next to the local `fun check(section: String, c: SefCheckResult)` add:

```kotlin
    fun specCheck(f: GoogleSpecFinding) = JsonValue.JObject(
        listOf(
            "section" to JsonValue.JString("google_motion_photo"),
            "status" to JsonValue.JString(f.severity.toCheckStatus().name),
            "label" to JsonValue.JString(f.title),
            "detail" to JsonValue.JString(scrubPaths(f.detail, file)),
            "ruleId" to JsonValue.JString(f.ruleId),
            "specSection" to JsonValue.JString(f.section),
            "requirement" to JsonValue.JString(f.requirement.name),
        ),
    )
```

replace `report.googleXmpChecks.forEach { add(check("google_xmp", it)) }` with `report.googleSpec.forEach { add(specCheck(it)) }`, delete `report.appleMpvdChecks.forEach { add(check("heic_mpvd", it)) }`, add import `com.multiviewer.parser.motionphoto.GoogleSpecFinding`.

- [ ] **Step 5: Update the other tests to the new report shape**

`ContentFindingMappersTest.kt` — replace `labelsMotionPhotoSeverityAndEvidence` body:

```kotlin
    @Test fun labelsMotionPhotoSeverityAndEvidence() {
        val report = MotionPhotoIntegrityReport(
            detectedFormats = listOf(MotionPhotoFormat.GOOGLE_XMP), sefSection = null,
            googleSpec = listOf(GoogleSpecFinding("gmp.layout.video_start", "3.3", SpecRequirement.MUST,
                SefIntegritySeverity.WARNING, "Offset mismatch", "Declared offset differs by 12 bytes")),
            decodeChecks = emptyList(), overallSeverity = SefIntegritySeverity.WARNING,
        )

        val finding = motionPhotoFindings(report).single()

        assertEquals(CheckStatus.WARN, finding.status)
        assertEquals("§3.3 [MUST] Offset mismatch", finding.title)
        assertEquals("motion.gmp.layout.video_start", finding.id)
        assertEquals("Declared offset differs by 12 bytes", finding.verifiedFacts.single())
        assertNull(finding.hypothesis)
    }
```

`MotionPhotoIntegrationTest.kt` — `report(...)` helper becomes:

```kotlin
    private fun report(formats: List<MotionPhotoFormat>, severity: SefIntegritySeverity) = MotionPhotoIntegrityReport(
        detectedFormats = formats, sefSection = null, googleSpec = emptyList(),
        decodeChecks = emptyList(), overallSeverity = severity,
    )
```

`ContentCheckSnapshotTest.kt` — in `motionReportListsNonPassChecksAndError` replace the `googleXmpChecks = listOf(...)` and `appleMpvdChecks = emptyList(),` lines with:

```kotlin
            googleSpec = listOf(
                GoogleSpecFinding("gmp.camera.motionphoto", "3.1", SpecRequirement.MUST, SefIntegritySeverity.PASS, "XMP present", "ok"),
                GoogleSpecFinding("gmp.layout.video_start", "3.3", SpecRequirement.MUST, SefIntegritySeverity.CRITICAL, "Video offset", "declared offset beyond EOF"),
            ),
```

and at line ~305 replace the positional construction with:

```kotlin
        val motion = MotionPhotoIntegrityReport(
            listOf(MotionPhotoFormat.GOOGLE_XMP), null,
            listOf(GoogleSpecFinding("gmp.layout.video_start", "3.3", SpecRequirement.MUST, SefIntegritySeverity.WARNING, "Motion offset", "Offset is 12 bytes late")),
            emptyList(), SefIntegritySeverity.WARNING,
        )
```

`ImageIntegrityJsonTest.kt` — in all three `MotionPhotoIntegrityReport(...)` calls replace `googleXmpChecks = ...,` + `appleMpvdChecks = emptyList(),` with `googleSpec = ...,`: in `motionPhotoJson maps severities and sections` use
`googleSpec = listOf(GoogleSpecFinding("gmp.camera.version", "3.1", SpecRequirement.SHOULD, SefIntegritySeverity.WARNING, "xmp", "odd")),`
and replace its google assertion with:

```kotlin
        assertTrue(Regex("\"section\": \"google_motion_photo\",\\s*\"status\": \"WARN\"").containsMatchIn(out), out)
        assertTrue(out.contains("\"ruleId\": \"gmp.camera.version\""), out)
        assertTrue(out.contains("\"requirement\": \"SHOULD\""), out)
```

the other two use `googleSpec = emptyList(),`.

Add to each modified test file the imports `com.multiviewer.parser.motionphoto.GoogleSpecFinding` and `com.multiviewer.parser.motionphoto.SpecRequirement` where used.

- [ ] **Step 6: Run the affected tests, then the full suite**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.MotionPhotoIntegrityAnalyzerTest' --tests 'com.multiviewer.ui.ContentFindingMappersTest' --tests 'com.multiviewer.ui.MotionPhotoIntegrationTest' --tests 'com.multiviewer.ui.ContentCheckSnapshotTest' --tests 'com.multiviewer.cli.ImageIntegrityJsonTest' --tests 'com.multiviewer.parser.motionphoto.*'`
Expected: PASS.
Run: `./gradlew :app:test`
Expected: PASS (only `ThumbnailDiskCacheTest` may flake). `grep -rn "googleXmpChecks\|appleMpvdChecks\|analyzeGoogleXmpSection\|analyzeAppleMpvdSection" app/src` prints only the one historical comment in `MotionPhotoBuilder.kt:425` — reword it to "the motion-photo spec checker's XMP parse" so no stale name remains.

- [ ] **Step 7: Commit**

```bash
git branch --show-current   # must print feat/google-motion-photo-spec
git add -A app/src
git commit -m "feat: hold every motion photo to the Google Motion Photo 1.0 spec"
```

---

### Task 5: Real-file fixtures, integration test and manual Samsung check

**Files:**
- Create: `docs/validation/google-motion-photo-fixtures/generate.sh`
- Create: `docs/validation/google-motion-photo-fixtures/make_fixtures.py`
- Create (generated): `app/src/test/resources/google-motion-photo/PXL_compliant.MP.jpg`, `samsung-style.jpg`, `legacy-microvideo.MP.jpg`
- Test: `app/src/test/kotlin/com/multiviewer/ui/GoogleMotionPhotoFixtureTest.kt`

**Interfaces:**
- Consumes: Task 4 `MotionPhotoIntegrityAnalyzer.analyze(file, root)` and `report.googleSpec`; existing `parseFile(File)`.
- Produces: fixtures only.

- [ ] **Step 1: Write the fixture generator**

`docs/validation/google-motion-photo-fixtures/make_fixtures.py`:

```python
"""Build JPEG motion photos for the Google Motion Photo 1.0 checker from a real JPEG and a real MP4.

Each variant injects an XMP APP1 right after SOI and appends the video exactly as described below.
"""
import sys

jpeg_path, mp4_path, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
jpeg = open(jpeg_path, "rb").read()
video = open(mp4_path, "rb").read()
assert jpeg[:2] == b"\xff\xd8" and video[4:8] == b"ftyp"

CAMERA = "http://ns.google.com/photos/1.0/camera/"
CONTAINER = "http://ns.google.com/photos/1.0/container/"
ITEM = "http://ns.google.com/photos/1.0/container/item/"


def xmp(camera_attrs, items):
    lis = "".join(
        '<rdf:li rdf:parseType="Resource"><Container:Item %s/></rdf:li>'
        % " ".join('Item:%s="%s"' % kv for kv in item)
        for item in items
    )
    directory = "<Container:Directory><rdf:Seq>%s</rdf:Seq></Container:Directory>" % lis if items else ""
    return (
        '<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
        '<rdf:Description rdf:about="" xmlns:GCamera="%s" xmlns:Container="%s" xmlns:Item="%s" %s>%s'
        "</rdf:Description></rdf:RDF></x:xmpmeta>" % (CAMERA, CONTAINER, ITEM, camera_attrs, directory)
    ).encode()


def with_xmp(xmp_bytes):
    payload = b"http://ns.adobe.com/xap/1.0/\x00" + xmp_bytes
    return jpeg[:2] + b"\xff\xe1" + (len(payload) + 2).to_bytes(2, "big") + payload + jpeg[2:]


def write(name, data):
    open("%s/%s" % (out_dir, name), "wb").write(data)


# 1. Compliant: video appended right after the EOI, Length = video size, no Padding anywhere.
camera = 'GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="-1"'
write("PXL_compliant.MP.jpg", with_xmp(xmp(camera, [
    [("Semantic", "Primary"), ("Mime", "image/jpeg")],
    [("Semantic", "MotionPhoto"), ("Mime", "video/mp4"), ("Length", str(len(video)))],
])) + video)

# 2. Samsung-style: 161 non-box bytes before the MP4 and a trailer ending "SEFT" after it; Length covers
#    everything after the EOI; Padding="0" on both items; legacy MicroVideo fields; non-"MP" filename.
prefix = b"\x00\x00\x01\x0a" + bytes(157)
trailer = bytes(40) + b"SEFT"
declared = len(prefix) + len(video) + len(trailer)
samsung_camera = camera.replace('"-1"', '"0"') + ' GCamera:MicroVideo="1" GCamera:MicroVideoOffset="%d"' % declared
write("samsung-style.jpg", with_xmp(xmp(samsung_camera, [
    [("Semantic", "Primary"), ("Mime", "image/jpeg"), ("Padding", "0")],
    [("Semantic", "MotionPhoto"), ("Mime", "video/mp4"), ("Length", str(declared)), ("Padding", "0")],
])) + prefix + video + trailer)

# 3. Legacy MicroVideo V1 only (no Container:Directory).
write("legacy-microvideo.MP.jpg", with_xmp(xmp(
    'GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="%d"' % len(video), None,
)) + video)
```

`docs/validation/google-motion-photo-fixtures/generate.sh`:

```bash
#!/usr/bin/env bash
# Google Motion Photo 1.0 checker fixtures: a real ffmpeg JPEG + a real libx264 MP4 (ffmpeg defaults, avc1),
# combined by make_fixtures.py into a compliant file and two violating variants (see that script).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../../app/src/test/resources/google-motion-photo"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$out"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=64x48' -frames:v 1 "$work/still.jpg"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=size=64x48:rate=8:duration=1' -pix_fmt yuv420p \
  -c:v libx264 "$work/video.mp4"
python3 -I "$here/make_fixtures.py" "$work/still.jpg" "$work/video.mp4" "$out"
```

- [ ] **Step 2: Generate the fixtures and check them independently**

Run:
```bash
chmod +x docs/validation/google-motion-photo-fixtures/generate.sh
docs/validation/google-motion-photo-fixtures/generate.sh
ls -la app/src/test/resources/google-motion-photo/
python3 -I -c "
d=open('app/src/test/resources/google-motion-photo/PXL_compliant.MP.jpg','rb').read()
e=d.find(b'\xff\xd9')+2; print('EOI end', e, 'ftyp at', d.find(b'ftyp')-4, 'len', len(d))"
```
Expected: three files of a few KB; for the compliant file `ftyp at` equals `EOI end` (the video starts right after the EOI) — if the test JPEG happens to contain `FFD9` inside an APP segment, check with `ffprobe` instead: `ffprobe -v error -show_entries format=format_name app/src/test/resources/google-motion-photo/PXL_compliant.MP.jpg` must print `image2`/`jpeg_pipe` without errors.

- [ ] **Step 3: Write the integration test**

`app/src/test/kotlin/com/multiviewer/ui/GoogleMotionPhotoFixtureTest.kt`:

```kotlin
package com.multiviewer.ui

import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleMotionPhotoFixtureTest {
    private fun analyze(name: String): MotionPhotoIntegrityReport {
        val file = File(javaClass.getResource("/google-motion-photo/$name")!!.toURI())
        return MotionPhotoIntegrityAnalyzer.analyze(file, parseFile(file))
    }

    private fun MotionPhotoIntegrityReport.rule(id: String) = googleSpec.single { it.ruleId == id }.severity

    @Test
    fun `compliant JPEG motion photo passes every Google rule and decodes`() {
        val report = analyze("PXL_compliant.MP.jpg")
        val bad = report.googleSpec.filter { it.severity == SefIntegritySeverity.CRITICAL || it.severity == SefIntegritySeverity.WARNING }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
        assertEquals(SefIntegritySeverity.PASS, report.rule("gmp.layout.packed"))
        assertEquals(SefIntegritySeverity.PASS, report.rule("gmp.layout.video_last"))
        assertEquals(SefIntegritySeverity.PASS, report.decodeChecks.single().severity)
        assertTrue(MotionPhotoFormat.GOOGLE_XMP in report.detectedFormats)
    }

    @Test
    fun `Samsung-style JPEG fails the MUST rules it breaks`() {
        val report = analyze("samsung-style.jpg")
        assertEquals(SefIntegritySeverity.CRITICAL, report.rule("gmp.layout.video_start"))
        assertEquals(SefIntegritySeverity.CRITICAL, report.rule("gmp.layout.video_last"))
        assertEquals(SefIntegritySeverity.CRITICAL, report.rule("gmp.item.padding_first_only"))
        assertEquals(SefIntegritySeverity.PASS, report.rule("gmp.layout.packed"))
        assertEquals(SefIntegritySeverity.WARNING, report.rule("gmp.item.padding_jpeg"))
        assertEquals(SefIntegritySeverity.WARNING, report.rule("gmp.filename"))
        assertEquals(SefIntegritySeverity.INFO, report.rule("gmp.legacy.microvideo"))
        assertEquals(SefIntegritySeverity.CRITICAL, report.overallSeverity)
    }

    @Test
    fun `legacy MicroVideo-only file is still recognized but fails 1_0`() {
        val report = analyze("legacy-microvideo.MP.jpg")
        assertTrue(MotionPhotoFormat.GOOGLE_XMP in report.detectedFormats)
        assertEquals(SefIntegritySeverity.CRITICAL, report.rule("gmp.dir.present"))
        assertEquals(SefIntegritySeverity.CRITICAL, report.rule("gmp.camera.motionphoto"))
        assertEquals(SefIntegritySeverity.PASS, report.decodeChecks.single().severity) // still extracted and played
    }
}
```

- [ ] **Step 4: Run it**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.GoogleMotionPhotoFixtureTest'`
Expected: PASS (3 tests). If the compliant file reports `gmp.layout.packed` FAIL, print `report.googleSpec` and compare the detail's "기본 이미지 끝" with Step 2's `EOI end` before changing any code.

- [ ] **Step 5: Manual check against the real Samsung files (not committed)**

Copy the user's files into the scratchpad (never modify `~/Downloads`) and run the CLI:

```bash
S="$(mktemp -d)"; cp ~/Downloads/20260718_200439_motion.jpg ~/Downloads/20260715_223835_motion.heic ~/Downloads/20260715_223828.heic "$S"/
for f in "$S"/*; do
  ./gradlew -q :app:run --args="check $f --decode" > "$f.out" 2>/dev/null
  # Parser trace lines may precede the JSON on stdout: decode from the first '{'.
  python3 -I -c "
import json,sys; t=open(sys.argv[1]).read(); d,_=json.JSONDecoder().raw_decode(t[t.index('{'):])
for c in d['analysis']['imageIntegrity']['motionPhoto']['checks']:
    if c['section']=='google_motion_photo' and c['status'] not in ('PASS','INFO'): print(c['status'], c['ruleId'], '-', c['detail'][:120])" "$f.out"
  echo "-- $f"
done
```

Expected (from the spec's "Observed Samsung files"): JPEG → FAIL `gmp.layout.video_start` (+161), `gmp.layout.video_last` (SEF), `gmp.item.padding_first_only`; WARN `gmp.item.padding_jpeg`, `gmp.filename`. `…_motion.heic` → FAIL `gmp.item.padding_isobmff`, `gmp.mpvd.last`, `gmp.mpvd.length`, `gmp.layout.video_start`, `gmp.layout.video_last`, `gmp.item.padding_first_only`. `20260715_223828.heic` → same minus `padding_isobmff`; `gmp.item.length_required` PASS (GainMap Length 0). If the JSON path differs, locate the `motionPhoto` object with `grep -n motionPhoto` on the raw output first. Record the actual output in the task report.

- [ ] **Step 6: Commit**

```bash
git branch --show-current   # must print feat/google-motion-photo-spec
git add docs/validation/google-motion-photo-fixtures app/src/test/resources/google-motion-photo app/src/test/kotlin/com/multiviewer/ui/GoogleMotionPhotoFixtureTest.kt
git commit -m "test: add Google Motion Photo 1.0 real-file fixtures"
```

---

## Follow-ups (not in this plan)

- Phase 2 spec: §5 video tracks/AAC audio and §6 `meta` track.
- `MotionPhotoBuilder` (the app's "모션포토 생성") writes `Item:Padding="0"` on the video item, so files the app creates will FAIL `gmp.item.padding_first_only`; fixing the writer is a separate change.
