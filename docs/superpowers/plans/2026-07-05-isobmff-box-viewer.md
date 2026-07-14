# ISOBMFF Box Viewer (MVP) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a cross-platform (Windows/Linux/macOS) desktop app that opens MP4/MOV/3GP/HEIC files, shows their ISOBMFF box/atom structure as a tree with decoded semantic fields for known box types, and highlights the corresponding byte range in a hex dump when a tree node is selected.

**Architecture:** Two independent layers in a single Gradle module. (1) A parser layer (`com.multiviewer.parser`) — a pure-Kotlin, UI-free recursive box walker plus a registry of per-box-type semantic decoders, fully unit tested. (2) A Compose for Desktop UI layer (`com.multiviewer.ui`) that consumes the parser's output tree and renders tabs, a box tree, a hex dump, a field panel, and a paginated table view for large sample tables. The UI layer only depends on the parser's public data model; it never touches file bytes directly except for its own hex-dump rendering.

**Tech Stack:** Kotlin 2.0.21, Compose Multiplatform (Desktop) 1.7.3, Kotlin/JVM (no Android target), kotlin-test + JUnit 5 for tests, Gradle Kotlin DSL.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-05-isobmff-box-viewer-design.md`
- Target platforms: Windows, Linux, macOS desktop only — no Android target in this module.
- Read-only viewer — no file editing/writing features.
- Base package: `com.multiviewer` (parser code under `com.multiviewer.parser`, UI code under `com.multiviewer.ui`).
- Source layout: Kotlin/JVM convention — `app/src/main/kotlin/...` and `app/src/test/kotlin/...` (not `src/main/java`).
- MVP box type coverage: `ftyp`, `mvhd`, `tkhd`, `mdhd`, `hdlr`, `stsd`, `ispe`, and the sample-table family `stts`/`stsc`/`stco`/`co64`/`stss`/`ctts`/`stsz`. Generic containers: `moov`, `trak`, `mdia`, `minf`, `dinf`, `edts`, `udta`, `stbl`, `iprp`, `ipco`. FullBox container: `meta`. Everything else falls back to a raw leaf node (type/size/offset only).
- Large sample tables (`stts`/`stsc`/`stco`/`co64`/`stss`/`ctts`/`stsz`) are summarized in the tree (entry count only) and their full contents are only materialized in a separate paginated table view — never as individual tree child nodes.
- Malformed/out-of-range boxes must produce a warning and let parsing continue (never throw and abort the whole file).
- **Not in this plan** (explicitly deferred, do not implement): `iloc` semantic decoding, codec-specific parsing inside `stsd` sample entries (e.g. `avcC`/`hvcC` internals), `ipma` property-association semantics. These box types still show up in the tree via the generic container/leaf fallback.
- JAVA_HOME for command-line Gradle runs on this machine: Android Studio's bundled JBR at `/Applications/Android Studio.app/Contents/jbr/Contents/Home` (JDK 21). Every "Run:" command below assumes this is exported, e.g. `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.

---

## Part 1 — Project Setup

### Task 1: Convert the Android scaffold to a Kotlin/JVM + Compose Desktop project

**Files:**
- Delete: `app/src/main/AndroidManifest.xml`
- Delete: `app/src/main/res/` (entire directory)
- Delete: `app/src/main/java/` (entire directory — `MainActivity.kt`, `ui/theme/*`)
- Delete: `app/src/androidTest/` (entire directory)
- Delete: `app/src/test/java/com/example/multiviewer/ExampleUnitTest.kt`
- Delete: `app/proguard-rules.pro`
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts` (root)
- Modify: `app/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Create: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Produces: a runnable Compose Desktop entry point `com.multiviewer.MainKt` that later tasks will extend with real UI.

- [ ] **Step 1: Remove the Android-specific source files**

```bash
rm -rf app/src/main/AndroidManifest.xml app/src/main/res app/src/main/java app/src/androidTest app/proguard-rules.pro
rm -f app/src/test/java/com/example/multiviewer/ExampleUnitTest.kt
rmdir -p app/src/test/java/com/example/multiviewer 2>/dev/null || true
```

- [ ] **Step 2: Replace `gradle/libs.versions.toml` with Kotlin/JVM + Compose Desktop versions**

```toml
[versions]
kotlin = "2.0.21"
composeMultiplatform = "1.7.3"

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
```

- [ ] **Step 3: Replace root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}
```

- [ ] **Step 4: Replace `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "multiViewer"
include(":app")
```

- [ ] **Step 5: Replace `app/build.gradle.kts`**

```kotlin
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.multiviewer.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.multiviewer.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "multiViewer"
            packageVersion = "1.0.0"
        }
    }
}
```

- [ ] **Step 6: Create the entry point `app/src/main/kotlin/com/multiviewer/Main.kt`**

```kotlin
package com.multiviewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "multiViewer") {
        MaterialTheme {
            Text("multiViewer")
        }
    }
}
```

- [ ] **Step 7: Verify the app runs**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew run
```
Expected: Gradle builds successfully and a window titled "multiViewer" opens showing the text "multiViewer". Close the window to let the command return.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "chore: convert scaffold to Kotlin/JVM + Compose Desktop"
```

---

## Part 2 — Box Walker Core

### Task 2: Core data model and `ByteReader`

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/ByteReader.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/BoxNode.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/TestSupport.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/ByteReaderTest.kt`

**Interfaces:**
- Produces: `class ByteReader` with `readUInt8/readUInt16/readUInt32/readUInt64/readFourCC/readBytes(offset, len)/length/close()`, and `ByteReader.open(file: File)`. Also `data class BoxField(name, value, offset, length)`, `data class TableData(columns, rows)`, `data class BoxNode(type, offset, headerSize, size, children, fields, warnings, summary, table)` — these are consumed by every later parser and UI task.

- [ ] **Step 1: Write the failing test for `ByteReader`**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class ByteReaderTest {
    @Test
    fun `reads big-endian integers and fourcc at given offsets`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x18, // uint32 = 24
                0x66, 0x74, 0x79, 0x70, // "ftyp"
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2A, // uint64 = 42
                0x01, 0x02, // uint16 = 258
                0x7F,       // uint8 = 127
            )
        )
        assertEquals(24L, reader.readUInt32(0))
        assertEquals("ftyp", reader.readFourCC(4))
        assertEquals(42L, reader.readUInt64(8))
        assertEquals(258, reader.readUInt16(16))
        assertEquals(127, reader.readUInt8(18))
        assertEquals(19L, reader.length)
        reader.close()
    }

    @Test
    fun `reads a byte range`() {
        val reader = byteReaderOf(byteArrayOf(1, 2, 3, 4, 5))
        val bytes = reader.readBytes(1, 3)
        assertEquals(listOf<Byte>(2, 3, 4), bytes.toList())
        reader.close()
    }
}
```

- [ ] **Step 2: Add the test helper**

```kotlin
package com.multiviewer.parser

import java.io.File

fun byteReaderOf(bytes: ByteArray): ByteReader {
    val tmp = File.createTempFile("multiviewer-test", ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(bytes)
    return ByteReader.open(tmp)
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test --tests "com.multiviewer.parser.ByteReaderTest"
```
Expected: FAIL — compilation error, `ByteReader` and `BoxNode.kt` types don't exist yet.

- [ ] **Step 4: Implement `ByteReader`**

```kotlin
package com.multiviewer.parser

import java.io.File
import java.io.RandomAccessFile

class ByteReader private constructor(private val raf: RandomAccessFile) : AutoCloseable {
    val length: Long get() = raf.length()

    fun readUInt8(offset: Long): Int {
        raf.seek(offset)
        return raf.readUnsignedByte()
    }

    fun readUInt16(offset: Long): Int {
        val buf = ByteArray(2)
        raf.seek(offset)
        raf.readFully(buf)
        return ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
    }

    fun readUInt32(offset: Long): Long {
        val buf = ByteArray(4)
        raf.seek(offset)
        raf.readFully(buf)
        return ((buf[0].toLong() and 0xFF) shl 24) or
            ((buf[1].toLong() and 0xFF) shl 16) or
            ((buf[2].toLong() and 0xFF) shl 8) or
            (buf[3].toLong() and 0xFF)
    }

    fun readUInt64(offset: Long): Long {
        val hi = readUInt32(offset)
        val lo = readUInt32(offset + 4)
        return (hi shl 32) or lo
    }

    fun readFourCC(offset: Long): String {
        val buf = ByteArray(4)
        raf.seek(offset)
        raf.readFully(buf)
        return String(buf, Charsets.US_ASCII)
    }

    fun readBytes(offset: Long, len: Int): ByteArray {
        val buf = ByteArray(len)
        raf.seek(offset)
        raf.readFully(buf)
        return buf
    }

    override fun close() = raf.close()

    companion object {
        fun open(file: File): ByteReader = ByteReader(RandomAccessFile(file, "r"))
    }
}
```

- [ ] **Step 5: Implement the data model**

```kotlin
package com.multiviewer.parser

data class BoxField(
    val name: String,
    val value: String,
    val offset: Long,
    val length: Long,
)

data class TableData(
    val columns: List<String>,
    val rows: List<List<Long>>,
)

data class BoxNode(
    val type: String,
    val offset: Long,
    val headerSize: Int,
    val size: Long,
    val children: List<BoxNode> = emptyList(),
    val fields: List<BoxField> = emptyList(),
    val warnings: List<String> = emptyList(),
    val summary: String? = null,
    val table: TableData? = null,
)
```

- [ ] **Step 6: Run the test to verify it passes**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.ByteReaderTest"
```
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ByteReader.kt app/src/main/kotlin/com/multiviewer/parser/BoxNode.kt app/src/test/kotlin/com/multiviewer/parser/TestSupport.kt app/src/test/kotlin/com/multiviewer/parser/ByteReaderTest.kt
git commit -m "feat(parser): add ByteReader and core box data model"
```

---

### Task 3: Box walker — basic header parsing (32-bit, 64-bit, size-0)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/BoxWalker.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/BoxRegistry.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/BoxWalkerTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, `BoxNode`, `BoxField` from Task 2.
- Produces: `object BoxRegistry { fun register(type: String, decoder: BoxDecoder); fun decoderFor(type: String): BoxDecoder }`, `interface BoxDecoder { fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode }`, `object LeafBoxDecoder : BoxDecoder`, `fun parseBoxes(reader: ByteReader, rangeStart: Long, rangeEnd: Long): List<BoxNode>` — every later decoder task and the UI's `parseFile` depend on these exact names.

- [ ] **Step 1: Write the failing test for basic header parsing**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoxWalkerTest {
    @Test
    fun `parses a simple 32-bit size box`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x08, // size = 8
                0x66, 0x72, 0x65, 0x65, // "free"
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes.size)
        assertEquals("free", boxes[0].type)
        assertEquals(0L, boxes[0].offset)
        assertEquals(8, boxes[0].headerSize)
        assertEquals(8L, boxes[0].size)
        reader.close()
    }

    @Test
    fun `parses a 64-bit extended size box (size field is 1)`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x01, // size = 1 -> read 64-bit size next
                0x6D, 0x64, 0x61, 0x74, // "mdat"
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, // largesize = 16
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes.size)
        assertEquals("mdat", boxes[0].type)
        assertEquals(16, boxes[0].headerSize)
        assertEquals(16L, boxes[0].size)
        reader.close()
    }

    @Test
    fun `size 0 means the box extends to the end of the parsed range`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x00, // size = 0 -> extends to end
                0x6D, 0x64, 0x61, 0x74, // "mdat"
                0x11, 0x22, 0x33,       // 3 bytes of payload
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes.size)
        assertEquals(11L, boxes[0].size)
        reader.close()
    }

    @Test
    fun `parses two sibling boxes back to back`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x08, 0x66, 0x72, 0x65, 0x65, // "free", size 8
                0x00, 0x00, 0x00, 0x08, 0x73, 0x6B, 0x69, 0x70, // "skip", size 8
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(listOf("free", "skip"), boxes.map { it.type })
        assertEquals(0L, boxes[0].offset)
        assertEquals(8L, boxes[1].offset)
        reader.close()
    }

    @Test
    fun `unknown box types fall back to a leaf node with no children`() {
        val reader = byteReaderOf(
            byteArrayOf(0x00, 0x00, 0x00, 0x08, 0x66, 0x72, 0x65, 0x65)
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertTrue(boxes[0].children.isEmpty())
        assertTrue(boxes[0].fields.isEmpty())
        reader.close()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.BoxWalkerTest"
```
Expected: FAIL — `parseBoxes` doesn't exist yet.

- [ ] **Step 3: Implement `BoxRegistry` and the decoder interface**

```kotlin
package com.multiviewer.parser

interface BoxDecoder {
    fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode
}

object LeafBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode = BoxNode(type = type, offset = offset, headerSize = headerSize, size = size, warnings = warnings)
}

object BoxRegistry {
    private val decoders = mutableMapOf<String, BoxDecoder>()

    fun register(type: String, decoder: BoxDecoder) {
        decoders[type] = decoder
    }

    fun decoderFor(type: String): BoxDecoder = decoders[type] ?: LeafBoxDecoder
}
```

- [ ] **Step 4: Implement `parseBoxes` (basic header parsing only, no malformed-size handling yet)**

```kotlin
package com.multiviewer.parser

fun parseBoxes(reader: ByteReader, rangeStart: Long, rangeEnd: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    var pos = rangeStart
    while (pos < rangeEnd) {
        val size32 = reader.readUInt32(pos)
        val type = reader.readFourCC(pos + 4)
        var headerSize = 8
        var size: Long

        if (size32 == 1L) {
            size = reader.readUInt64(pos + 8)
            headerSize = 16
        } else if (size32 == 0L) {
            size = rangeEnd - pos
        } else {
            size = size32
        }

        val decoder = BoxRegistry.decoderFor(type)
        result.add(decoder.decode(reader, type, pos, headerSize, size, emptyList()))
        pos += size
    }
    return result
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.BoxWalkerTest"
```
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/BoxWalker.kt app/src/main/kotlin/com/multiviewer/parser/BoxRegistry.kt app/src/test/kotlin/com/multiviewer/parser/BoxWalkerTest.kt
git commit -m "feat(parser): add box walker with basic header parsing"
```

---

### Task 4: Box walker — malformed size handling

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/BoxWalker.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/BoxWalkerTest.kt`

**Interfaces:**
- Consumes: everything from Task 3.
- Produces: `parseBoxes` now also handles truncated headers and out-of-range declared sizes, appending human-readable warnings to `BoxNode.warnings` instead of throwing.

- [ ] **Step 1: Add failing tests for malformed input**

```kotlin
    @Test
    fun `too few bytes for a box header produces a trailing-bytes warning and stops`() {
        val reader = byteReaderOf(byteArrayOf(0x00, 0x00, 0x00)) // only 3 bytes
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes.size)
        assertEquals("?", boxes[0].type)
        assertTrue(boxes[0].warnings.single().contains("too short"))
        reader.close()
    }

    @Test
    fun `declared size smaller than header size produces a warning and clamps to the parent end`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x04, // size = 4, smaller than the 8-byte header
                0x66, 0x72, 0x65, 0x65, // "free"
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes.size)
        assertTrue(boxes[0].warnings.single().contains("smaller than header size"))
        assertEquals(8L, boxes[0].size)
        reader.close()
    }

    @Test
    fun `declared size extending past the parent range produces a warning and clamps`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x64, // size = 100, way past the 8 bytes available
                0x66, 0x72, 0x65, 0x65, // "free"
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes.size)
        assertTrue(boxes[0].warnings.single().contains("extends"))
        assertEquals(8L, boxes[0].size)
        reader.close()
    }

    @Test
    fun `truncated 64-bit size header produces a warning and stops`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x01, // size = 1 -> expects a 64-bit size next
                0x6D, 0x64, 0x61, 0x74, // "mdat"
                0x00, 0x00,             // only 2 of the required 8 bytes present
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes.size)
        assertTrue(boxes[0].warnings.single().contains("only"))
        reader.close()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.BoxWalkerTest"
```
Expected: FAIL on the 4 new cases (no warnings are produced yet, or wrong sizes).

- [ ] **Step 3: Rewrite `parseBoxes` with malformed-size handling**

```kotlin
package com.multiviewer.parser

fun parseBoxes(reader: ByteReader, rangeStart: Long, rangeEnd: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    var pos = rangeStart
    while (pos < rangeEnd) {
        val remaining = rangeEnd - pos
        if (remaining < 8) {
            result.add(
                BoxNode(
                    type = "?",
                    offset = pos,
                    headerSize = 0,
                    size = remaining,
                    warnings = listOf("Trailing $remaining byte(s): too short for a box header"),
                )
            )
            break
        }

        val size32 = reader.readUInt32(pos)
        val type = reader.readFourCC(pos + 4)
        var headerSize = 8
        var size: Long

        if (size32 == 1L) {
            if (remaining < 16) {
                result.add(
                    BoxNode(
                        type = type,
                        offset = pos,
                        headerSize = 8,
                        size = remaining,
                        warnings = listOf("Declared a 64-bit size but only $remaining byte(s) remain"),
                    )
                )
                break
            }
            size = reader.readUInt64(pos + 8)
            headerSize = 16
        } else if (size32 == 0L) {
            size = remaining
        } else {
            size = size32
        }

        val warnings = mutableListOf<String>()
        if (size < headerSize) {
            warnings.add("Declared size $size is smaller than header size $headerSize")
            size = remaining
        } else if (pos + size > rangeEnd) {
            warnings.add("Declared size $size extends ${pos + size - rangeEnd} byte(s) past the end of its parent")
            size = remaining
        }

        val decoder = BoxRegistry.decoderFor(type)
        result.add(decoder.decode(reader, type, pos, headerSize, size, warnings))
        pos += size
    }
    return result
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.BoxWalkerTest"
```
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/BoxWalker.kt app/src/test/kotlin/com/multiviewer/parser/BoxWalkerTest.kt
git commit -m "feat(parser): handle malformed box sizes with warnings instead of throwing"
```

---

### Task 5: Generic container decoder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/ContainerBoxDecoder.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/ContainerBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `BoxDecoder`, `parseBoxes`, `BoxRegistry` from Tasks 3–4.
- Produces: `class ContainerBoxDecoder(childOffsetInPayload: Int = 0, summarize: Boolean = false) : BoxDecoder` — reused directly by Task 6 (`meta`, offset 4) and Task 12 (`stsd`, offset 8, summarize true), and registered as-is for `moov`/`trak`/`mdia`/`minf`/`dinf`/`edts`/`udta`/`stbl`/`iprp`/`ipco` in Task 16.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class ContainerBoxDecoderTest {
    @Test
    fun `recurses into children starting right after the box header`() {
        BoxRegistry.register("box1", ContainerBoxDecoder())
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x18, 0x62, 0x6F, 0x78, 0x31, // "box1", size 24 (8 header + 16 children)
                0x00, 0x00, 0x00, 0x08, 0x66, 0x72, 0x65, 0x65, // child "free", size 8
                0x00, 0x00, 0x00, 0x08, 0x73, 0x6B, 0x69, 0x70, // child "skip", size 8
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes.size)
        assertEquals(listOf("free", "skip"), boxes[0].children.map { it.type })
        reader.close()
    }

    @Test
    fun `summarize option reports child count as the summary`() {
        BoxRegistry.register("box2", ContainerBoxDecoder(summarize = true))
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x10, 0x62, 0x6F, 0x78, 0x32, // "box2", size 16
                0x00, 0x00, 0x00, 0x08, 0x66, 0x72, 0x65, 0x65, // child "free", size 8
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals("1 entries", boxes[0].summary)
        reader.close()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.ContainerBoxDecoderTest"
```
Expected: FAIL — `ContainerBoxDecoder` doesn't exist yet.

- [ ] **Step 3: Implement `ContainerBoxDecoder`**

```kotlin
package com.multiviewer.parser

class ContainerBoxDecoder(
    private val childOffsetInPayload: Int = 0,
    private val summarize: Boolean = false,
) : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val payloadStart = offset + headerSize + childOffsetInPayload
        val payloadEnd = offset + size
        val children = parseBoxes(reader, payloadStart, payloadEnd)
        val summary = if (summarize) "${children.size} entries" else null
        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            children = children,
            warnings = warnings,
            summary = summary,
        )
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.ContainerBoxDecoderTest"
```
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ContainerBoxDecoder.kt app/src/test/kotlin/com/multiviewer/parser/ContainerBoxDecoderTest.kt
git commit -m "feat(parser): add generic container box decoder"
```

---

### Task 6: `meta` box decoder (FullBox container)

**Files:**
- Create: `app/src/test/kotlin/com/multiviewer/parser/MetaBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `ContainerBoxDecoder` from Task 5.
- Produces: nothing new — this task only proves the existing `ContainerBoxDecoder(childOffsetInPayload = 4)` correctly models `meta`'s 4-byte version/flags header before its children. Registration into the shared `BoxRegistry` bootstrap happens in Task 16.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class MetaBoxDecoderTest {
    @Test
    fun `meta box skips 4 bytes of version and flags before recursing into children`() {
        BoxRegistry.register("meta", ContainerBoxDecoder(childOffsetInPayload = 4))
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x14, 0x6D, 0x65, 0x74, 0x61, // "meta", size 20
                0x00, 0x00, 0x00, 0x00,                         // version/flags
                0x00, 0x00, 0x00, 0x08, 0x68, 0x64, 0x6C, 0x72, // child "hdlr", size 8
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes[0].children.size)
        assertEquals("hdlr", boxes[0].children[0].type)
        assertEquals(12L, boxes[0].children[0].offset)
        reader.close()
    }
}
```

- [ ] **Step 2: Run the test**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.MetaBoxDecoderTest"
```
Expected: PASS immediately — `ContainerBoxDecoder` already supports this via `childOffsetInPayload`. This test exists to lock the behavior in before Task 16 wires it into the shared registry.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/kotlin/com/multiviewer/parser/MetaBoxDecoderTest.kt
git commit -m "test(parser): verify meta box FullBox header handling"
```

---

## Part 3 — Semantic Decoders

### Task 7: `ftyp` decoder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/FtypBoxDecoder.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/FtypBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `BoxDecoder`, `BoxNode`, `BoxField`, `ByteReader` from Tasks 2–3.
- Produces: `object FtypBoxDecoder : BoxDecoder`, registered for type `"ftyp"` in Task 16.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class FtypBoxDecoderTest {
    @Test
    fun `decodes major brand, minor version, and compatible brands`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x68, 0x65, 0x69, 0x63, // major_brand "heic"
                0x00, 0x00, 0x00, 0x00, // minor_version 0
                0x6D, 0x69, 0x66, 0x31, // compatible_brand "mif1"
                0x68, 0x65, 0x69, 0x63, // compatible_brand "heic"
            )
        )
        val node = FtypBoxDecoder.decode(reader, "ftyp", 0, 8, 8 + 16, emptyList())
        assertEquals("heic", node.fields[0].value)
        assertEquals("0", node.fields[1].value)
        assertEquals(listOf("mif1", "heic"), node.fields.drop(2).map { it.value })
        assertEquals("heic, 2 compatible brand(s)", node.summary)
        reader.close()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.FtypBoxDecoderTest"
```
Expected: FAIL — `FtypBoxDecoder` doesn't exist.

- [ ] **Step 3: Implement `FtypBoxDecoder`**

```kotlin
package com.multiviewer.parser

object FtypBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 8) {
            w.add("Box too short to contain major_brand and minor_version")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val majorBrand = reader.readFourCC(payloadStart)
        val minorVersion = reader.readUInt32(payloadStart + 4)
        val fields = mutableListOf(
            BoxField("major_brand", majorBrand, payloadStart, 4),
            BoxField("minor_version", minorVersion.toString(), payloadStart + 4, 4),
        )
        var pos = payloadStart + 8
        val brands = mutableListOf<String>()
        while (pos + 4 <= payloadEnd) {
            val brand = reader.readFourCC(pos)
            brands.add(brand)
            fields.add(BoxField("compatible_brand", brand, pos, 4))
            pos += 4
        }
        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            fields = fields,
            warnings = w,
            summary = "$majorBrand, ${brands.size} compatible brand(s)",
        )
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.FtypBoxDecoderTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/FtypBoxDecoder.kt app/src/test/kotlin/com/multiviewer/parser/FtypBoxDecoderTest.kt
git commit -m "feat(parser): add ftyp box decoder"
```

---

### Task 8: Shared binary helper + `mvhd` decoder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/BinaryUtil.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/MvhdBoxDecoder.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/MvhdBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `ByteReader` from Task 2.
- Produces: `internal fun readUIntOfWidth(reader: ByteReader, offset: Long, width: Int): Long` (reused by Tasks 9, 10, 14, 15). `object MvhdBoxDecoder : BoxDecoder`, registered for `"mvhd"` in Task 16.

- [ ] **Step 1: Write the failing test (version 0, 32-bit fields)**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class MvhdBoxDecoderTest {
    @Test
    fun `decodes version 0 timescale and duration`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x00, // version 0, flags 0
                0x00, 0x00, 0x00, 0x00, // creation_time
                0x00, 0x00, 0x00, 0x00, // modification_time
                0x00, 0x00, 0x02, 0x58, // timescale = 600
                0x00, 0x00, 0x04, 0xB0.toByte(), // duration = 1200
            )
        )
        val node = MvhdBoxDecoder.decode(reader, "mvhd", 0, 8, 8 + 20, emptyList())
        assertEquals("timescale=600, duration=2.000s", node.summary)
        reader.close()
    }

    @Test
    fun `too short for declared version fields produces a warning`() {
        val reader = byteReaderOf(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        val node = MvhdBoxDecoder.decode(reader, "mvhd", 0, 8, 8 + 6, emptyList())
        assertEquals(1, node.warnings.size)
        reader.close()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.MvhdBoxDecoderTest"
```
Expected: FAIL — types don't exist.

- [ ] **Step 3: Implement `BinaryUtil.kt`**

```kotlin
package com.multiviewer.parser

internal fun readUIntOfWidth(reader: ByteReader, offset: Long, width: Int): Long = when (width) {
    4 -> reader.readUInt32(offset)
    8 -> reader.readUInt64(offset)
    else -> error("Unsupported field width: $width")
}
```

- [ ] **Step 4: Implement `MvhdBoxDecoder`**

```kotlin
package com.multiviewer.parser

object MvhdBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 4) {
            w.add("Box too short to contain a FullBox header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val version = reader.readUInt8(payloadStart)
        val timeFieldWidth = if (version == 1) 8 else 4
        val needed = 4 + timeFieldWidth * 3 + 4
        if (payloadEnd - payloadStart < needed) {
            w.add("Box too short for mvhd version $version fields")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        var pos = payloadStart + 4
        pos += timeFieldWidth // creation_time (not surfaced)
        pos += timeFieldWidth // modification_time (not surfaced)
        val timescaleOffset = pos
        val timescale = reader.readUInt32(pos)
        pos += 4
        val durationOffset = pos
        val duration = readUIntOfWidth(reader, pos, timeFieldWidth)
        val fields = listOf(
            BoxField("version", version.toString(), payloadStart, 1),
            BoxField("timescale", timescale.toString(), timescaleOffset, 4),
            BoxField("duration", duration.toString(), durationOffset, timeFieldWidth.toLong()),
        )
        val summary = if (timescale > 0) {
            "timescale=$timescale, duration=${"%.3f".format(duration.toDouble() / timescale.toDouble())}s"
        } else {
            "timescale=$timescale"
        }
        return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, summary = summary)
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.MvhdBoxDecoderTest"
```
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/BinaryUtil.kt app/src/main/kotlin/com/multiviewer/parser/MvhdBoxDecoder.kt app/src/test/kotlin/com/multiviewer/parser/MvhdBoxDecoderTest.kt
git commit -m "feat(parser): add mvhd box decoder"
```

---

### Task 9: `tkhd` decoder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/TkhdBoxDecoder.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/TkhdBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `readUIntOfWidth` from Task 8.
- Produces: `object TkhdBoxDecoder : BoxDecoder`, registered for `"tkhd"` in Task 16.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class TkhdBoxDecoderTest {
    @Test
    fun `decodes version 0 track_ID, duration, width and height`() {
        val body = ByteArray(4 + 4 + 4 + 4 + 4 + 4 + 8 + 2 + 2 + 2 + 2 + 36 + 4 + 4)
        // version/flags = 0 (already zero-filled)
        // creation_time, modification_time = 0 (already zero-filled)
        writeUInt32(body, 12, 7L)          // track_ID = 7
        // reserved (4 bytes) already zero
        writeUInt32(body, 20, 10L)         // duration = 10
        // reserved[2] + layer + alternate_group + volume + reserved + matrix = 56 bytes, already zero
        writeUInt32(body, 20 + 4 + 56, 1920L * 65536L) // width = 1920.0 as 16.16 fixed point
        writeUInt32(body, 20 + 4 + 56 + 4, 1080L * 65536L) // height = 1080.0

        val reader = byteReaderOf(body)
        val node = TkhdBoxDecoder.decode(reader, "tkhd", 0, 0, body.size.toLong(), emptyList())
        assertEquals("track_ID=7, 1920x1080", node.summary)
        reader.close()
    }
}

private fun writeUInt32(bytes: ByteArray, offset: Int, value: Long) {
    bytes[offset] = ((value shr 24) and 0xFF).toByte()
    bytes[offset + 1] = ((value shr 16) and 0xFF).toByte()
    bytes[offset + 2] = ((value shr 8) and 0xFF).toByte()
    bytes[offset + 3] = (value and 0xFF).toByte()
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.TkhdBoxDecoderTest"
```
Expected: FAIL — `TkhdBoxDecoder` doesn't exist.

- [ ] **Step 3: Implement `TkhdBoxDecoder`**

```kotlin
package com.multiviewer.parser

object TkhdBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 4) {
            w.add("Box too short to contain a FullBox header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val version = reader.readUInt8(payloadStart)
        val timeFieldWidth = if (version == 1) 8 else 4
        val fixedTail = 8 + 2 + 2 + 2 + 2 + 36 + 4 + 4
        val totalNeeded = 4 + timeFieldWidth * 2 + 4 + 4 + timeFieldWidth + fixedTail
        if (payloadEnd - payloadStart < totalNeeded) {
            w.add("Box too short for tkhd version $version fields")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        var pos = payloadStart + 4
        pos += timeFieldWidth // creation_time (not surfaced)
        pos += timeFieldWidth // modification_time (not surfaced)
        val trackIdOffset = pos
        val trackId = reader.readUInt32(pos)
        pos += 4
        pos += 4 // reserved
        val durationOffset = pos
        val duration = readUIntOfWidth(reader, pos, timeFieldWidth)
        pos += timeFieldWidth
        pos += 8 + 2 + 2 + 2 + 2 + 36 // reserved[2], layer, alternate_group, volume, reserved, matrix
        val widthOffset = pos
        val widthRaw = reader.readUInt32(pos)
        pos += 4
        val heightOffset = pos
        val heightRaw = reader.readUInt32(pos)
        val width = widthRaw / 65536.0
        val height = heightRaw / 65536.0
        val fields = listOf(
            BoxField("track_ID", trackId.toString(), trackIdOffset, 4),
            BoxField("duration", duration.toString(), durationOffset, timeFieldWidth.toLong()),
            BoxField("width", width.toString(), widthOffset, 4),
            BoxField("height", height.toString(), heightOffset, 4),
        )
        return BoxNode(
            type, offset, headerSize, size, fields = fields, warnings = w,
            summary = "track_ID=$trackId, ${"%.0f".format(width)}x${"%.0f".format(height)}",
        )
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.TkhdBoxDecoderTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/TkhdBoxDecoder.kt app/src/test/kotlin/com/multiviewer/parser/TkhdBoxDecoderTest.kt
git commit -m "feat(parser): add tkhd box decoder"
```

---

### Task 10: `mdhd` decoder (with packed language)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/MdhdBoxDecoder.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/MdhdBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `readUIntOfWidth` from Task 8, `ByteReader.readUInt16` from Task 2.
- Produces: `object MdhdBoxDecoder : BoxDecoder` and `internal fun unpackLanguage(packed: Int): String`, registered for `"mdhd"` in Task 16.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class MdhdBoxDecoderTest {
    @Test
    fun `decodes timescale, duration and packed ISO-639-2 language`() {
        // "und" (undetermined) = 0x55C4 packed: u=0x15, n=0x0E, d=0x04 -> ((0x15<<10)|(0x0E<<5)|0x04)
        val packed = ((('u' - 0x60) and 0x1F) shl 10) or ((('n' - 0x60) and 0x1F) shl 5) or (('d' - 0x60) and 0x1F)
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version 0, flags 0
            0x00, 0x00, 0x00, 0x00, // creation_time
            0x00, 0x00, 0x00, 0x00, // modification_time
            0x00, 0x00, 0x03, 0xE8.toByte(), // timescale = 1000
            0x00, 0x00, 0x07, 0xD0.toByte(), // duration = 2000
            ((packed shr 8) and 0xFF).toByte(), (packed and 0xFF).toByte(), // language
            0x00, 0x00, // pre_defined
        )
        val reader = byteReaderOf(body)
        val node = MdhdBoxDecoder.decode(reader, "mdhd", 0, 0, body.size.toLong(), emptyList())
        assertEquals("timescale=1000, duration=2.000s, language=und", node.summary)
        reader.close()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.MdhdBoxDecoderTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement `MdhdBoxDecoder`**

```kotlin
package com.multiviewer.parser

object MdhdBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 4) {
            w.add("Box too short to contain a FullBox header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val version = reader.readUInt8(payloadStart)
        val timeFieldWidth = if (version == 1) 8 else 4
        val totalNeeded = 4 + timeFieldWidth * 2 + 4 + timeFieldWidth + 2
        if (payloadEnd - payloadStart < totalNeeded) {
            w.add("Box too short for mdhd version $version fields")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        var pos = payloadStart + 4
        pos += timeFieldWidth // creation_time (not surfaced)
        pos += timeFieldWidth // modification_time (not surfaced)
        val timescaleOffset = pos
        val timescale = reader.readUInt32(pos)
        pos += 4
        val durationOffset = pos
        val duration = readUIntOfWidth(reader, pos, timeFieldWidth)
        pos += timeFieldWidth
        val languageOffset = pos
        val language = unpackLanguage(reader.readUInt16(pos))
        val fields = listOf(
            BoxField("timescale", timescale.toString(), timescaleOffset, 4),
            BoxField("duration", duration.toString(), durationOffset, timeFieldWidth.toLong()),
            BoxField("language", language, languageOffset, 2),
        )
        val summary = if (timescale > 0) {
            "timescale=$timescale, duration=${"%.3f".format(duration.toDouble() / timescale.toDouble())}s, language=$language"
        } else {
            "timescale=$timescale, language=$language"
        }
        return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, summary = summary)
    }
}

internal fun unpackLanguage(packed: Int): String {
    val c1 = ((packed shr 10) and 0x1F) + 0x60
    val c2 = ((packed shr 5) and 0x1F) + 0x60
    val c3 = (packed and 0x1F) + 0x60
    return "${c1.toChar()}${c2.toChar()}${c3.toChar()}"
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.MdhdBoxDecoderTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MdhdBoxDecoder.kt app/src/test/kotlin/com/multiviewer/parser/MdhdBoxDecoderTest.kt
git commit -m "feat(parser): add mdhd box decoder"
```

---

### Task 11: `hdlr` decoder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/HdlrBoxDecoder.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/HdlrBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `ByteReader.readBytes`, `BoxDecoder` from Task 2–3.
- Produces: `object HdlrBoxDecoder : BoxDecoder`, registered for `"hdlr"` in Task 16.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class HdlrBoxDecoderTest {
    @Test
    fun `decodes handler_type and name`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x00, 0x00, 0x00, 0x00, // pre_defined
            0x76, 0x69, 0x64, 0x65, // handler_type "vide"
            0x00, 0x00, 0x00, 0x00, // reserved[0]
            0x00, 0x00, 0x00, 0x00, // reserved[1]
            0x00, 0x00, 0x00, 0x00, // reserved[2]
            0x56, 0x69, 0x64, 0x65, 0x6F, 0x00, // name "Video\0"
        )
        val reader = byteReaderOf(body)
        val node = HdlrBoxDecoder.decode(reader, "hdlr", 0, 0, body.size.toLong(), emptyList())
        assertEquals("vide: Video", node.summary)
        reader.close()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.HdlrBoxDecoderTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement `HdlrBoxDecoder`**

```kotlin
package com.multiviewer.parser

object HdlrBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        val needed = 4 + 4 + 4 + 12
        if (payloadEnd - payloadStart < needed) {
            w.add("Box too short for hdlr fixed fields")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val handlerTypeOffset = payloadStart + 8
        val handlerType = reader.readFourCC(handlerTypeOffset)
        val nameOffset = payloadStart + needed
        val nameBytes = reader.readBytes(nameOffset, (payloadEnd - nameOffset).toInt())
        val name = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000')
        val fields = listOf(
            BoxField("handler_type", handlerType, handlerTypeOffset, 4),
            BoxField("name", name, nameOffset, nameBytes.size.toLong()),
        )
        return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, summary = "$handlerType: $name")
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.HdlrBoxDecoderTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/HdlrBoxDecoder.kt app/src/test/kotlin/com/multiviewer/parser/HdlrBoxDecoderTest.kt
git commit -m "feat(parser): add hdlr box decoder"
```

---

### Task 12: `stsd` decoder (reusing `ContainerBoxDecoder`)

**Files:**
- Create: `app/src/test/kotlin/com/multiviewer/parser/StsdBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `ContainerBoxDecoder` from Task 5.
- Produces: nothing new — proves `ContainerBoxDecoder(childOffsetInPayload = 8, summarize = true)` correctly models `stsd`'s FullBox header + `entry_count` before its sample-entry children. Registered in Task 16.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class StsdBoxDecoderTest {
    @Test
    fun `stsd skips version, flags and entry_count before recursing into sample entries`() {
        BoxRegistry.register("stsd", ContainerBoxDecoder(childOffsetInPayload = 8, summarize = true))
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x1C, 0x73, 0x74, 0x73, 0x64, // "stsd", size 28
                0x00, 0x00, 0x00, 0x00,                         // version/flags
                0x00, 0x00, 0x00, 0x01,                         // entry_count = 1
                0x00, 0x00, 0x00, 0x0C, 0x68, 0x76, 0x63, 0x31, // sample entry "hvc1", size 12
                0x00, 0x00, 0x00, 0x00,                         // (dummy payload to reach size 12)
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes[0].children.size)
        assertEquals("hvc1", boxes[0].children[0].type)
        assertEquals("1 entries", boxes[0].summary)
        reader.close()
    }
}
```

- [ ] **Step 2: Run the test**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.StsdBoxDecoderTest"
```
Expected: PASS immediately (same reasoning as Task 6 — this locks the behavior in ahead of Task 16's registration).

- [ ] **Step 3: Commit**

```bash
git add app/src/test/kotlin/com/multiviewer/parser/StsdBoxDecoderTest.kt
git commit -m "test(parser): verify stsd sample-entry recursion"
```

---

### Task 13: `ispe` decoder (HEIC image size)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/IspeBoxDecoder.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/IspeBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `BoxDecoder`, `ByteReader` from Task 2–3.
- Produces: `object IspeBoxDecoder : BoxDecoder`, registered for `"ispe"` in Task 16.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class IspeBoxDecoderTest {
    @Test
    fun `decodes image_width and image_height`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x00, 0x00, 0x0F, 0x00, // image_width = 3840
            0x00, 0x00, 0x08, 0x70, // image_height = 2160
        )
        val reader = byteReaderOf(body)
        val node = IspeBoxDecoder.decode(reader, "ispe", 0, 0, body.size.toLong(), emptyList())
        assertEquals("3840x2160", node.summary)
        reader.close()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.IspeBoxDecoderTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement `IspeBoxDecoder`**

```kotlin
package com.multiviewer.parser

object IspeBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 12) {
            w.add("Box too short for ispe fields")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val widthOffset = payloadStart + 4
        val width = reader.readUInt32(widthOffset)
        val heightOffset = payloadStart + 8
        val height = reader.readUInt32(heightOffset)
        val fields = listOf(
            BoxField("image_width", width.toString(), widthOffset, 4),
            BoxField("image_height", height.toString(), heightOffset, 4),
        )
        return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, summary = "${width}x${height}")
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.IspeBoxDecoderTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/IspeBoxDecoder.kt app/src/test/kotlin/com/multiviewer/parser/IspeBoxDecoderTest.kt
git commit -m "feat(parser): add ispe box decoder"
```

---

### Task 14: Generic fixed-width sample-table decoder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/FixedWidthTableDecoder.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/FixedWidthTableDecoderTest.kt`

**Interfaces:**
- Consumes: `readUIntOfWidth` from Task 8, `TableData` from Task 2.
- Produces: `class FixedWidthTableDecoder(columns: List<String>, fieldWidths: List<Int>) : BoxDecoder` — registered for `stts` (`["sample_count","sample_delta"]`, `[4,4]`), `stsc` (`["first_chunk","samples_per_chunk","sample_description_index"]`, `[4,4,4]`), `stco` (`["chunk_offset"]`, `[4]`), `co64` (`["chunk_offset"]`, `[8]`), `stss` (`["sample_number"]`, `[4]`), `ctts` (`["sample_count","sample_offset"]`, `[4,4]`) in Task 16.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class FixedWidthTableDecoderTest {
    @Test
    fun `decodes entry_count and rows, summarizing instead of exposing rows as children`() {
        val decoder = FixedWidthTableDecoder(listOf("sample_count", "sample_delta"), listOf(4, 4))
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x00, 0x00, 0x00, 0x02, // entry_count = 2
            0x00, 0x00, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x14, // row 1: (10, 20)
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1E, // row 2: (1, 30)
        )
        val reader = byteReaderOf(body)
        val node = decoder.decode(reader, "stts", 0, 0, body.size.toLong(), emptyList())
        assertEquals("2 entries", node.summary)
        assertEquals(listOf("sample_count", "sample_delta"), node.table?.columns)
        assertEquals(listOf(listOf(10L, 20L), listOf(1L, 30L)), node.table?.rows)
        assertEquals(true, node.children.isEmpty())
        reader.close()
    }

    @Test
    fun `declared entry_count larger than available bytes truncates with a warning`() {
        val decoder = FixedWidthTableDecoder(listOf("chunk_offset"), listOf(4))
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x00, 0x00, 0x00, 0x64, // entry_count = 100 (way more than available)
            0x00, 0x00, 0x00, 0x01, // only 1 entry actually fits
        )
        val reader = byteReaderOf(body)
        val node = decoder.decode(reader, "stco", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.table?.rows?.size)
        assertEquals(1, node.warnings.size)
        reader.close()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.FixedWidthTableDecoderTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement `FixedWidthTableDecoder`**

```kotlin
package com.multiviewer.parser

class FixedWidthTableDecoder(
    private val columns: List<String>,
    private val fieldWidths: List<Int>,
) : BoxDecoder {
    init {
        require(columns.size == fieldWidths.size) { "columns and fieldWidths must be the same size" }
    }

    private val entryWidth = fieldWidths.sum()

    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 8) {
            w.add("Box too short to contain a FullBox header and entry count")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val entryCount = reader.readUInt32(payloadStart + 4)
        val entriesStart = payloadStart + 8
        val available = payloadEnd - entriesStart
        val fitCount = if (entryWidth == 0) 0 else available / entryWidth
        val actualCount = minOf(entryCount, fitCount)
        if (actualCount < entryCount) {
            w.add("Declared $entryCount entries but only enough space for $fitCount")
        }
        val rows = mutableListOf<List<Long>>()
        var pos = entriesStart
        repeat(actualCount.toInt()) {
            val row = mutableListOf<Long>()
            var fieldPos = pos
            for (width in fieldWidths) {
                row.add(readUIntOfWidth(reader, fieldPos, width))
                fieldPos += width
            }
            rows.add(row)
            pos += entryWidth
        }
        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            warnings = w,
            summary = "$entryCount entries",
            table = TableData(columns, rows),
        )
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.FixedWidthTableDecoderTest"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/FixedWidthTableDecoder.kt app/src/test/kotlin/com/multiviewer/parser/FixedWidthTableDecoderTest.kt
git commit -m "feat(parser): add generic fixed-width sample table decoder"
```

---

### Task 15: `stsz` decoder (irregular header)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/StszBoxDecoder.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/StszBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `TableData`, `BoxField` from Task 2.
- Produces: `class StszBoxDecoder : BoxDecoder`, registered for `"stsz"` in Task 16.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class StszBoxDecoderTest {
    @Test
    fun `uniform sample size is reported as fields, not a table`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x00, 0x00, 0x04, 0x00, // sample_size = 1024 (uniform)
            0x00, 0x00, 0x00, 0x05, // sample_count = 5
        )
        val reader = byteReaderOf(body)
        val node = StszBoxDecoder().decode(reader, "stsz", 0, 0, body.size.toLong(), emptyList())
        assertEquals("5 samples, uniform size 1024", node.summary)
        assertEquals(null, node.table)
        reader.close()
    }

    @Test
    fun `sample_size 0 means variable sizes follow as a table`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x00, 0x00, 0x00, 0x00, // sample_size = 0 (variable)
            0x00, 0x00, 0x00, 0x02, // sample_count = 2
            0x00, 0x00, 0x01, 0x00, // size[0] = 256
            0x00, 0x00, 0x02, 0x00, // size[1] = 512
        )
        val reader = byteReaderOf(body)
        val node = StszBoxDecoder().decode(reader, "stsz", 0, 0, body.size.toLong(), emptyList())
        assertEquals("2 entries (variable size)", node.summary)
        assertEquals(listOf(listOf(256L), listOf(512L)), node.table?.rows)
        reader.close()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.StszBoxDecoderTest"
```
Expected: FAIL.

- [ ] **Step 3: Implement `StszBoxDecoder`**

```kotlin
package com.multiviewer.parser

class StszBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 12) {
            w.add("Box too short to contain a FullBox header, sample_size and sample_count")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val sampleSizeOffset = payloadStart + 4
        val sampleSize = reader.readUInt32(sampleSizeOffset)
        val sampleCountOffset = payloadStart + 8
        val sampleCount = reader.readUInt32(sampleCountOffset)

        if (sampleSize != 0L) {
            return BoxNode(
                type = type, offset = offset, headerSize = headerSize, size = size, warnings = w,
                fields = listOf(
                    BoxField("sample_size", sampleSize.toString(), sampleSizeOffset, 4),
                    BoxField("sample_count", sampleCount.toString(), sampleCountOffset, 4),
                ),
                summary = "$sampleCount samples, uniform size $sampleSize",
            )
        }

        val entriesStart = payloadStart + 12
        val available = payloadEnd - entriesStart
        val fitCount = available / 4
        val actualCount = minOf(sampleCount, fitCount)
        if (actualCount < sampleCount) {
            w.add("Declared $sampleCount entries but only enough space for $fitCount")
        }
        val rows = mutableListOf<List<Long>>()
        var pos = entriesStart
        repeat(actualCount.toInt()) {
            rows.add(listOf(reader.readUInt32(pos)))
            pos += 4
        }
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size, warnings = w,
            summary = "$sampleCount entries (variable size)",
            table = TableData(listOf("sample_size"), rows),
        )
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.StszBoxDecoderTest"
```
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/StszBoxDecoder.kt app/src/test/kotlin/com/multiviewer/parser/StszBoxDecoderTest.kt
git commit -m "feat(parser): add stsz box decoder"
```

---

### Task 16: Registry bootstrap, `parseFile` entry point, and end-to-end integration test

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt`

**Interfaces:**
- Consumes: every decoder from Tasks 5–15.
- Produces: `fun registerAllDecoders()` (idempotent), `fun parseFile(path: java.io.File): BoxNode` — this is the single function the UI layer (Part 4) calls to go from a file path to a full parsed tree.

- [ ] **Step 1: Write the failing integration test**

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ParseFileIntegrationTest {
    @Test
    fun `parses a synthetic minimal mp4 into a full box tree with decoded fields`() {
        val ftyp = box("ftyp", byteArrayOf(0x69, 0x73, 0x6F, 0x6D, 0x00, 0x00, 0x00, 0x00))
        val mvhd = fullBox("mvhd", version = 0, body = uint32(0) + uint32(0) + uint32(600) + uint32(1200))
        val tkhd = fullBox(
            "tkhd", version = 0,
            body = uint32(0) + uint32(0) + uint32(7) + uint32(0) + uint32(10) + ByteArray(8 + 2 + 2 + 2 + 2 + 36) + uint32(1920L * 65536L) + uint32(1080L * 65536L),
        )
        val mdhd = fullBox("mdhd", version = 0, body = uint32(0) + uint32(0) + uint32(1000) + uint32(2000) + byteArrayOf(0x55, 0xC4.toByte()) + byteArrayOf(0, 0))
        val hdlr = fullBox("hdlr", version = 0, body = uint32(0) + "vide".toByteArray() + ByteArray(12) + "Video\u0000".toByteArray())
        val mdia = box("mdia", mdhd + hdlr)
        val trak = box("trak", tkhd + mdia)
        val moov = box("moov", mvhd + trak)
        val mdat = box("mdat", byteArrayOf(0x01, 0x02, 0x03))

        val bytes = ftyp + moov + mdat
        val tmp = File.createTempFile("multiviewer-integration", ".mp4")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)

        val root = parseFile(tmp)

        assertEquals(3, root.children.size)
        assertEquals(listOf("ftyp", "moov", "mdat"), root.children.map { it.type })

        val moovNode = root.children[1]
        assertEquals(listOf("mvhd", "trak"), moovNode.children.map { it.type })
        assertNotNull(moovNode.children[0].summary)

        val trakNode = moovNode.children[1]
        val mdiaNode = trakNode.children.single { it.type == "mdia" }
        assertEquals(listOf("mdhd", "hdlr"), mdiaNode.children.map { it.type })
        assertEquals("vide: Video", mdiaNode.children[1].summary)
    }
}

private fun uint32(value: Long): ByteArray = byteArrayOf(
    ((value shr 24) and 0xFF).toByte(),
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    (value and 0xFF).toByte(),
)

private fun box(type: String, body: ByteArray): ByteArray {
    val size = 8 + body.size
    return uint32(size.toLong()) + type.toByteArray(Charsets.US_ASCII) + body
}

private fun fullBox(type: String, version: Int, body: ByteArray): ByteArray {
    val fullBoxHeader = byteArrayOf(version.toByte(), 0, 0, 0)
    return box(type, fullBoxHeader + body)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.ParseFileIntegrationTest"
```
Expected: FAIL — `parseFile` and `registerAllDecoders` don't exist yet.

- [ ] **Step 3: Implement the decoder registration bootstrap**

```kotlin
package com.multiviewer.parser

private var registered = false

fun registerAllDecoders() {
    if (registered) return
    registered = true

    BoxRegistry.register("ftyp", FtypBoxDecoder)
    BoxRegistry.register("mvhd", MvhdBoxDecoder)
    BoxRegistry.register("tkhd", TkhdBoxDecoder)
    BoxRegistry.register("mdhd", MdhdBoxDecoder)
    BoxRegistry.register("hdlr", HdlrBoxDecoder)
    BoxRegistry.register("ispe", IspeBoxDecoder)

    BoxRegistry.register("stsd", ContainerBoxDecoder(childOffsetInPayload = 8, summarize = true))
    BoxRegistry.register("meta", ContainerBoxDecoder(childOffsetInPayload = 4))
    for (containerType in listOf("moov", "trak", "mdia", "minf", "dinf", "edts", "udta", "stbl", "iprp", "ipco")) {
        BoxRegistry.register(containerType, ContainerBoxDecoder())
    }

    BoxRegistry.register("stts", FixedWidthTableDecoder(listOf("sample_count", "sample_delta"), listOf(4, 4)))
    BoxRegistry.register(
        "stsc",
        FixedWidthTableDecoder(listOf("first_chunk", "samples_per_chunk", "sample_description_index"), listOf(4, 4, 4)),
    )
    BoxRegistry.register("stco", FixedWidthTableDecoder(listOf("chunk_offset"), listOf(4)))
    BoxRegistry.register("co64", FixedWidthTableDecoder(listOf("chunk_offset"), listOf(8)))
    BoxRegistry.register("stss", FixedWidthTableDecoder(listOf("sample_number"), listOf(4)))
    BoxRegistry.register("ctts", FixedWidthTableDecoder(listOf("sample_count", "sample_offset"), listOf(4, 4)))
    BoxRegistry.register("stsz", StszBoxDecoder())
}
```

- [ ] **Step 4: Implement `parseFile`**

```kotlin
package com.multiviewer.parser

import java.io.File

fun parseFile(path: File): BoxNode {
    registerAllDecoders()
    ByteReader.open(path).use { reader ->
        val children = parseBoxes(reader, 0, reader.length)
        return BoxNode(type = "root", offset = 0, headerSize = 0, size = reader.length, children = children)
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.ParseFileIntegrationTest"
```
Expected: PASS.

- [ ] **Step 6: Run the full parser test suite**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.*"
```
Expected: PASS — all tests from Tasks 2–16 (~30 tests total).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/Decoders.kt app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt
git commit -m "feat(parser): wire up decoder registry and parseFile entry point"
```

---

## Part 4 — Desktop UI

### Task 17: App shell — window, tab state, file-open dialog

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes: `parseFile(File): BoxNode` from Task 16.
- Produces: `class TabState(val file: File)` with mutable `root: BoxNode?`, `error: String?`, `selected: BoxNode?`; `class AppState` with `tabs: SnapshotStateList<TabState>`, `selectedTabIndex: MutableState<Int>`, `fun openFile(file: File)` — consumed by Tasks 18–24.

- [ ] **Step 1: Implement `AppState`**

```kotlin
package com.multiviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.parseFile
import java.io.File

class TabState(val file: File) {
    var root: BoxNode? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    var selected: BoxNode? by mutableStateOf(null)
}

class AppState {
    val tabs = mutableStateListOf<TabState>()
    var selectedTabIndex by mutableStateOf(0)

    fun openFile(file: File) {
        val existingIndex = tabs.indexOfFirst { it.file.absolutePath == file.absolutePath }
        if (existingIndex >= 0) {
            selectedTabIndex = existingIndex
            return
        }
        val tab = TabState(file)
        tabs.add(tab)
        selectedTabIndex = tabs.size - 1
        try {
            tab.root = parseFile(file)
        } catch (e: Exception) {
            tab.error = e.message ?: "Failed to open file"
        }
    }
}
```

- [ ] **Step 2: Wire the app shell into `Main.kt` with a tab bar and an "Open File" button**

```kotlin
package com.multiviewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.multiviewer.ui.AppState
import java.awt.FileDialog
import java.awt.Frame

fun main() = application {
    val appState = remember { AppState() }

    Window(onCloseRequest = ::exitApplication, title = "multiViewer") {
        MaterialTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                Button(onClick = {
                    val dialog = FileDialog(null as Frame?, "Open file", FileDialog.LOAD)
                    dialog.isVisible = true
                    val fileName = dialog.file
                    val directory = dialog.directory
                    if (fileName != null && directory != null) {
                        appState.openFile(java.io.File(directory, fileName))
                    }
                }) {
                    Text("Open File")
                }

                if (appState.tabs.isNotEmpty()) {
                    TabRow(selectedTabIndex = appState.selectedTabIndex) {
                        appState.tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = index == appState.selectedTabIndex,
                                onClick = { appState.selectedTabIndex = index },
                                text = { Text(tab.file.name) },
                            )
                        }
                    }

                    val currentTab = appState.tabs[appState.selectedTabIndex]
                    when {
                        currentTab.error != null -> Text("Error: ${currentTab.error}")
                        currentTab.root != null -> Text("Loaded ${currentTab.file.name}: ${currentTab.root!!.children.size} top-level boxes")
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Verify it runs and can open a file**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew run
```
Expected: window opens with an "Open File" button. Click it, pick any `.mp4`/`.mov` file (or any file) from disk — a tab appears with the file name, and the text below shows the top-level box count (or an error message if the file isn't a valid box file).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "feat(ui): add app shell with tabs and file-open dialog"
```

---

### Task 18: Drag-and-drop file opening

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes: `AppState.openFile` from Task 17.
- Produces: nothing new for later tasks — this task only adds an input path.

- [ ] **Step 1: Attach an AWT `DropTarget` to the window's content pane**

Replace the `Window { ... }` block in `Main.kt` with a version that registers drag-and-drop on `window` (available as a receiver inside the `Window` content lambda):

```kotlin
package com.multiviewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.multiviewer.ui.AppState
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File

fun main() = application {
    val appState = remember { AppState() }

    Window(onCloseRequest = ::exitApplication, title = "multiViewer") {
        LaunchedEffect(Unit) {
            window.contentPane.dropTarget = DropTarget(window.contentPane, object : DropTargetAdapter() {
                override fun drop(event: DropTargetDropEvent) {
                    event.acceptDrop(DnDConstants.ACTION_COPY)
                    @Suppress("UNCHECKED_CAST")
                    val files = event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                    files.firstOrNull()?.let { appState.openFile(it) }
                    event.dropComplete(true)
                }
            })
        }

        MaterialTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                Button(onClick = {
                    val dialog = FileDialog(null as Frame?, "Open file", FileDialog.LOAD)
                    dialog.isVisible = true
                    val fileName = dialog.file
                    val directory = dialog.directory
                    if (fileName != null && directory != null) {
                        appState.openFile(File(directory, fileName))
                    }
                }) {
                    Text("Open File")
                }

                if (appState.tabs.isNotEmpty()) {
                    TabRow(selectedTabIndex = appState.selectedTabIndex) {
                        appState.tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = index == appState.selectedTabIndex,
                                onClick = { appState.selectedTabIndex = index },
                                text = { Text(tab.file.name) },
                            )
                        }
                    }

                    val currentTab = appState.tabs[appState.selectedTabIndex]
                    when {
                        currentTab.error != null -> Text("Error: ${currentTab.error}")
                        currentTab.root != null -> Text("Loaded ${currentTab.file.name}: ${currentTab.root!!.children.size} top-level boxes")
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify manually**

Run:
```bash
./gradlew run
```
Expected: with the window open, drag an `.mp4`/`.mov`/`.heic` file from Finder/Explorer onto the window — a new tab opens for it, same as using the "Open File" button.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "feat(ui): support opening files via drag and drop"
```

---

### Task 19: Box tree view component

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/BoxTreeView.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes: `BoxNode`, `TabState.selected` from Tasks 2, 17.
- Produces: `@Composable fun BoxTreeView(root: BoxNode, selected: BoxNode?, onSelect: (BoxNode) -> Unit)` — consumed by Task 21 (selection drives hex highlight) and Task 22 (selection drives field panel).

- [ ] **Step 1: Implement `BoxTreeView`**

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.multiviewer.parser.BoxNode

private data class FlatRow(val node: BoxNode, val depth: Int)

@Composable
fun BoxTreeView(root: BoxNode, selected: BoxNode?, onSelect: (BoxNode) -> Unit) {
    val expanded = remember { mutableStateOf(setOf<BoxNode>()) }
    val rows = remember(root, expanded.value) { flatten(root, 0, expanded.value) }

    LazyColumn {
        items(rows) { row ->
            val isSelected = row.node === selected
            Text(
                text = buildLabel(row.node),
                modifier = Modifier
                    .background(if (isSelected) Color.LightGray else Color.Transparent)
                    .padding(start = (row.depth * 16).dp, top = 2.dp, bottom = 2.dp)
                    .clickable {
                        onSelect(row.node)
                        if (row.node.children.isNotEmpty()) {
                            expanded.value = if (row.node in expanded.value) {
                                expanded.value - row.node
                            } else {
                                expanded.value + row.node
                            }
                        }
                    },
            )
        }
    }
}

private fun flatten(node: BoxNode, depth: Int, expanded: Set<BoxNode>): List<FlatRow> {
    val rows = mutableListOf(FlatRow(node, depth))
    if (node.children.isNotEmpty() && node in expanded) {
        for (child in node.children) {
            rows.addAll(flatten(child, depth + 1, expanded))
        }
    }
    return rows
}

private fun buildLabel(node: BoxNode): String {
    val warningPrefix = if (node.warnings.isNotEmpty()) "⚠ " else ""
    val summarySuffix = node.summary?.let { " — $it" } ?: ""
    return "$warningPrefix${node.type}$summarySuffix"
}
```

- [ ] **Step 2: Show the tree for the selected tab's root in `Main.kt`**

In `Main.kt`, replace the `currentTab.root != null -> Text(...)` branch with:

```kotlin
currentTab.root != null -> com.multiviewer.ui.BoxTreeView(
    root = currentTab.root!!,
    selected = currentTab.selected,
    onSelect = { currentTab.selected = it },
)
```

- [ ] **Step 3: Verify manually**

Run:
```bash
./gradlew run
```
Expected: opening a file now shows an indented, clickable tree of its top-level boxes (and, for `moov`/`trak`/`mdia`, their children on click-to-expand). Boxes with warnings show a "⚠" prefix.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/BoxTreeView.kt app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "feat(ui): add box tree view with expand/collapse and warning icons"
```

---

### Task 20: Hex dump view component

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/HexView.kt`

**Interfaces:**
- Consumes: nothing from the parser directly — opens its own read-only `RandomAccessFile` handle on the tab's file, independent of the parser's `ByteReader` (per the spec's parser/UI independence boundary).
- Produces: `@Composable fun HexView(file: File, highlightRange: LongRange?, listState: LazyListState)` — consumed by Task 21, which supplies `highlightRange` from tree selection and owns the `LazyListState` for auto-scroll.

- [ ] **Step 1: Implement `HexView`**

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import java.io.File
import java.io.RandomAccessFile

private const val BYTES_PER_ROW = 16

@Composable
fun HexView(file: File, highlightRange: LongRange?, listState: LazyListState) {
    val raf = remember(file) { RandomAccessFile(file, "r") }
    DisposableEffect(raf) {
        onDispose { raf.close() }
    }
    val rowCount = ((raf.length() + BYTES_PER_ROW - 1) / BYTES_PER_ROW).toInt()

    LazyColumn(state = listState) {
        items(rowCount) { rowIndex ->
            val rowStart = rowIndex.toLong() * BYTES_PER_ROW
            val rowLength = minOf(BYTES_PER_ROW.toLong(), raf.length() - rowStart).toInt()
            val buf = ByteArray(rowLength)
            raf.seek(rowStart)
            raf.readFully(buf)

            Text(buildAnnotatedString {
                append("%08X  ".format(rowStart))
                for (i in buf.indices) {
                    val byteOffset = rowStart + i
                    val isHighlighted = highlightRange?.contains(byteOffset) == true
                    val hex = "%02X ".format(buf[i])
                    if (isHighlighted) {
                        withStyle(SpanStyle(background = Color.Yellow)) { append(hex) }
                    } else {
                        append(hex)
                    }
                }
            })
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run:
```bash
./gradlew compileKotlin
```
Expected: BUILD SUCCESSFUL. (Wiring `HexView` into the visible layout happens in Task 21, alongside highlight sync — testing it standalone here would mean throwing away the harness immediately after.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/HexView.kt
git commit -m "feat(ui): add hex dump view component"
```

---

### Task 21: Tree-selection to hex-highlight synchronization

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes: `BoxTreeView` (Task 19), `HexView` (Task 20), `TabState.selected` (Task 17).
- Produces: the visible two-pane (tree | hex) layout from the design spec, with the selected node's `offset until offset+size` range highlighted and auto-scrolled to in the hex view.

- [ ] **Step 1: Replace the tree-only branch in `Main.kt` with a tree+hex `Row`, and auto-scroll on selection change**

```kotlin
package com.multiviewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.multiviewer.ui.AppState
import com.multiviewer.ui.BoxTreeView
import com.multiviewer.ui.HexView
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File

private const val BYTES_PER_ROW = 16

fun main() = application {
    val appState = remember { AppState() }

    Window(onCloseRequest = ::exitApplication, title = "multiViewer") {
        LaunchedEffect(Unit) {
            window.contentPane.dropTarget = DropTarget(window.contentPane, object : DropTargetAdapter() {
                override fun drop(event: DropTargetDropEvent) {
                    event.acceptDrop(DnDConstants.ACTION_COPY)
                    @Suppress("UNCHECKED_CAST")
                    val files = event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                    files.firstOrNull()?.let { appState.openFile(it) }
                    event.dropComplete(true)
                }
            })
        }

        MaterialTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                Button(onClick = {
                    val dialog = FileDialog(null as Frame?, "Open file", FileDialog.LOAD)
                    dialog.isVisible = true
                    val fileName = dialog.file
                    val directory = dialog.directory
                    if (fileName != null && directory != null) {
                        appState.openFile(File(directory, fileName))
                    }
                }) {
                    Text("Open File")
                }

                if (appState.tabs.isNotEmpty()) {
                    TabRow(selectedTabIndex = appState.selectedTabIndex) {
                        appState.tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = index == appState.selectedTabIndex,
                                onClick = { appState.selectedTabIndex = index },
                                text = { Text(tab.file.name) },
                            )
                        }
                    }

                    val currentTab = appState.tabs[appState.selectedTabIndex]
                    val hexListState = remember(currentTab) { androidx.compose.foundation.lazy.LazyListState() }

                    LaunchedEffect(currentTab.selected) {
                        val sel = currentTab.selected
                        if (sel != null) {
                            hexListState.scrollToItem((sel.offset / BYTES_PER_ROW).toInt())
                        }
                    }

                    when {
                        currentTab.error != null -> Text("Error: ${currentTab.error}")
                        currentTab.root != null -> Row(modifier = Modifier.fillMaxSize()) {
                            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                BoxTreeView(
                                    root = currentTab.root!!,
                                    selected = currentTab.selected,
                                    onSelect = { currentTab.selected = it },
                                )
                            }
                            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                HexView(
                                    file = currentTab.file,
                                    highlightRange = currentTab.selected?.let { it.offset until (it.offset + it.size) },
                                    listState = hexListState,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify manually**

Run:
```bash
./gradlew run
```
Expected: tree on the left, hex dump on the right. Clicking a tree node highlights its byte range in yellow in the hex view and scrolls the hex view to that row.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "feat(ui): sync hex view highlight and scroll with tree selection"
```

---

### Task 22: Field panel

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/FieldPanel.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes: `BoxNode.fields` from Task 2.
- Produces: `@Composable fun FieldPanel(node: BoxNode?)` — placed below the tree+hex row in `Main.kt`; Task 23 branches this same slot to a table view when `node.table != null`.

- [ ] **Step 1: Implement `FieldPanel`**

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.multiviewer.parser.BoxNode

@Composable
fun FieldPanel(node: BoxNode?) {
    if (node == null || node.fields.isEmpty()) return
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        items(node.fields) { field ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("${field.name}: ", modifier = Modifier.padding(end = 4.dp))
                Text(field.value)
            }
        }
    }
}
```

- [ ] **Step 2: Add `FieldPanel` below the tree+hex `Row` in `Main.kt`**

Inside the `currentTab.root != null ->` branch, wrap the existing `Row(...)` and add `FieldPanel` beneath it:

```kotlin
currentTab.root != null -> Column(modifier = Modifier.fillMaxSize()) {
    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            BoxTreeView(
                root = currentTab.root!!,
                selected = currentTab.selected,
                onSelect = { currentTab.selected = it },
            )
        }
        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            HexView(
                file = currentTab.file,
                highlightRange = currentTab.selected?.let { it.offset until (it.offset + it.size) },
                listState = hexListState,
            )
        }
    }
    com.multiviewer.ui.FieldPanel(currentTab.selected)
}
```

- [ ] **Step 3: Verify manually**

Run:
```bash
./gradlew run
```
Expected: selecting a node like `mvhd`, `tkhd`, or `mdhd` shows its decoded fields (e.g. `timescale: 600`, `duration: 1200`) in a panel below the tree/hex row. Selecting a node with no fields (e.g. a generic container) shows an empty panel.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FieldPanel.kt app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "feat(ui): add field panel for selected box"
```

---

### Task 23: Paginated table view for large sample tables

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/TableView.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes: `BoxNode.table: TableData?` from Task 2.
- Produces: `@Composable fun TableView(table: TableData)` — shown instead of `FieldPanel` when the selected node has a non-null `table`.

- [ ] **Step 1: Implement `TableView` with page navigation (200 rows per page)**

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.multiviewer.parser.TableData

private const val PAGE_SIZE = 200

@Composable
fun TableView(table: TableData) {
    var page by remember(table) { mutableStateOf(0) }
    val pageCount = ((table.rows.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
    val start = page * PAGE_SIZE
    val end = minOf(start + PAGE_SIZE, table.rows.size)

    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Row {
            Text(table.columns.joinToString("  |  "))
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(table.rows.subList(start, end)) { row ->
                Text(row.joinToString("  |  ") { it.toString() })
            }
        }
        Row {
            Button(onClick = { page = (page - 1).coerceAtLeast(0) }, enabled = page > 0) {
                Text("Previous")
            }
            Text(" Page ${page + 1} / $pageCount (${table.rows.size} total entries) ")
            Button(onClick = { page = (page + 1).coerceAtMost(pageCount - 1) }, enabled = page < pageCount - 1) {
                Text("Next")
            }
        }
    }
}
```

`Modifier.weight(1f)` on the `LazyColumn` is required, not cosmetic: the outer `Column` here gets a fixed height from its parent (Main.kt's `weight(0.3f)` wrapper added in Task 22's fix), and within that fixed height, a `LazyColumn` with no weight would greedily fill the entire available height ahead of its unweighted `Row` siblings — the same layout bug fixed in Task 22, but here it would push the "Previous"/"Next" pagination buttons (the footer `Row`) off the bottom of the visible area instead of collapsing a sibling to zero. Weighting the `LazyColumn` and leaving the header/footer `Row`s unweighted is the standard Compose "list with header and footer" pattern: unweighted siblings get their natural size first, and the weighted list fills exactly what's left over (scrolling internally instead of overflowing).

- [ ] **Step 2: Branch between `FieldPanel` and `TableView` in `Main.kt`**

Replace the `Column(modifier = Modifier.weight(0.3f).fillMaxWidth()) { com.multiviewer.ui.FieldPanel(currentTab.selected) }` block (added in Task 22's fix) with:

```kotlin
Column(modifier = Modifier.weight(0.3f).fillMaxWidth()) {
    val selectedNode = currentTab.selected
    if (selectedNode?.table != null) {
        com.multiviewer.ui.TableView(selectedNode.table!!)
    } else {
        com.multiviewer.ui.FieldPanel(selectedNode)
    }
}
```

The `weight(0.3f)` wrapper is required, not cosmetic: without a weight/height constraint here, the unweighted `LazyColumn` inside `FieldPanel`/`TableView` would claim the `Column`'s entire height ahead of the sibling weighted `Row` (tree+hex), collapsing the tree+hex view to zero height the moment a node is selected. Keep both the `Row` and this `Column` weighted so Compose divides space between them proportionally.

- [ ] **Step 3: Verify manually**

Run:
```bash
./gradlew run
```
Expected: selecting a `stts`/`stco`/`stsz`/etc. node (which the tree shows as e.g. "stco — 1,204 entries") replaces the field panel with a paginated table showing 200 rows per page and Previous/Next buttons, instead of trying to render all rows as tree children.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/TableView.kt app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "feat(ui): add paginated table view for large sample tables"
```

---

### Task 24: End-to-end smoke test and packaging sanity check

**Files:**
- None created — this task is verification only.

**Interfaces:**
- Consumes: the complete app from Tasks 1–23.

- [ ] **Step 1: Run the full test suite**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test
```
Expected: BUILD SUCCESSFUL, all parser tests pass (Tasks 2–16).

- [ ] **Step 2: Manual smoke test with a real file**

Run:
```bash
./gradlew run
```
Expected, using a real `.mp4`/`.mov`/`.heic` file from your own samples:
- Open it via the "Open File" button, then again via drag-and-drop onto the window — both open a tab.
- Open a second, different file — a second tab appears; switching tabs preserves each file's tree/selection state.
- Expand `moov` → `trak` → `mdia`, click `mdhd`/`hdlr`/`tkhd` and confirm the field panel shows sensible values (timescale, duration, track ID, width/height, handler name).
- Click a `stts`/`stco`/`stsz`-family node and confirm the table view appears with pagination instead of the field panel.
- Click any node and confirm the hex view scrolls to and highlights that byte range.
- If you have a deliberately truncated/corrupted file, open it and confirm a "⚠" appears on the affected node instead of the app crashing.

- [ ] **Step 3: Verify native packaging works for the current OS**

Run:
```bash
./gradlew packageDistributionForCurrentOS
```
Expected: BUILD SUCCESSFUL, producing a native installer (`.dmg` on macOS) under `app/build/compose/binaries/main/`.

- [ ] **Step 4: Commit (only if Steps 1–3 required any fixes)**

```bash
git add -A
git commit -m "fix: address issues found in end-to-end smoke test"
```

If no fixes were needed, skip this step — there is nothing to commit.

---

## Part 5 — Follow-up fixes from the final whole-branch review

Two Important findings surfaced by the whole-branch code review after Task 24, both tracked as real (non-blocking) gaps rather than implementation slips. These two tasks close them.

### Task 25: Correct `meta` box parsing for QuickTime `.mov` (plain box, not FullBox)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/MetaBoxDecoder.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/MetaBoxDecoderTest.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`

**Context:** `registerAllDecoders()` currently registers `"meta"` as `ContainerBoxDecoder(childOffsetInPayload = 4)`, unconditionally skipping 4 bytes of version/flags before recursing into children. This is correct for the ISO/IEC 14496-12 `meta` box (used by MP4 and HEIC) but wrong for QuickTime `.mov` files, where `meta` is historically a **plain** box with no version/flags — the children start immediately at the box's payload start. Since `.mov` is an explicitly supported format, parsing it with the wrong offset misreads the first child's size/type, which the box walker then reports as a "smaller than header size" or "extends past parent" warning rather than crashing — visible, but wrong.

**Detection heuristic:** every `meta` box's very first child is mandated by spec to be `hdlr` in both the plain (QuickTime) and FullBox (ISO) layouts. A box's on-disk shape is `size(4 bytes) + type(4-byte FourCC) + payload`. So:
- In a **plain** `meta` box, the first child's FourCC sits at `payloadStart + 4` (right after that child's own 4-byte size field).
- In a **FullBox** `meta` box, there are 4 extra bytes (version/flags) before the first child even starts, so the first child's FourCC sits at `payloadStart + 8`.

Peek 4 bytes at `payloadStart + 4`: if they look like a plausible FourCC (all 4 bytes printable ASCII, `0x20`–`0x7E`), treat `meta` as plain (no skip). Otherwise, default to the FullBox behavior (skip 4 bytes) — this preserves all existing MP4/HEIC behavior exactly, since a real FourCC won't look like a printable string in that position for those files (it's actually `hdlr`'s *size* field there, all zero/small binary bytes, not printable).

**Interfaces:**
- Consumes: `BoxDecoder`, `ByteReader`, `parseBoxes` from Tasks 2–3.
- Produces: `object MetaBoxDecoder : BoxDecoder`, replacing the inline `ContainerBoxDecoder(childOffsetInPayload = 4)` registration for `"meta"` in `Decoders.kt`. Does not change any other registration.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class MetaBoxDecoderTest {
    @Test
    fun `plain QuickTime-style meta box (no version-flags) has its first child at the payload start`() {
        // meta box: 8-byte header, size 20 (8 header + 12 payload)
        // payload is just one child: hdlr, size 12 (8-byte header + 4 dummy payload bytes)
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x0C, 0x68, 0x64, 0x6C, 0x72, // child "hdlr", size 12
            0x00, 0x00, 0x00, 0x00, // dummy payload to reach size 12
        )
        val reader = byteReaderOf(
            byteArrayOf(0x00, 0x00, 0x00, 0x14, 0x6D, 0x65, 0x74, 0x61) + body // "meta", size 20
        )
        val node = MetaBoxDecoder.decode(reader, "meta", 0, 8, 20, emptyList())
        assertEquals(1, node.children.size)
        assertEquals("hdlr", node.children[0].type)
        assertEquals(8L, node.children[0].offset)
        reader.close()
    }

    @Test
    fun `ISO-style meta box with version-flags skips 4 bytes before the first child`() {
        // meta box payload: 4-byte version/flags, then one child hdlr, size 12
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x00, 0x00, 0x00, 0x0C, 0x68, 0x64, 0x6C, 0x72, // child "hdlr", size 12
            0x00, 0x00, 0x00, 0x00, // dummy payload to reach size 12
        )
        val reader = byteReaderOf(
            byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x6D, 0x65, 0x74, 0x61) + body // "meta", size 24
        )
        val node = MetaBoxDecoder.decode(reader, "meta", 0, 8, 24, emptyList())
        assertEquals(1, node.children.size)
        assertEquals("hdlr", node.children[0].type)
        assertEquals(12L, node.children[0].offset)
        reader.close()
    }

    @Test
    fun `too short to peek a fourcc defaults to FullBox behavior without crashing`() {
        // meta box, size 8 (header only, empty payload) — nothing to peek, must not throw
        val reader = byteReaderOf(byteArrayOf(0x00, 0x00, 0x00, 0x08, 0x6D, 0x65, 0x74, 0x61))
        val node = MetaBoxDecoder.decode(reader, "meta", 0, 8, 8, emptyList())
        assertEquals(0, node.children.size)
        reader.close()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test --tests "com.multiviewer.parser.MetaBoxDecoderTest"
```
Expected: FAIL — `MetaBoxDecoder` doesn't exist yet.

- [ ] **Step 3: Implement `MetaBoxDecoder`**

```kotlin
package com.multiviewer.parser

object MetaBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        val childOffsetInPayload = if (isPlainBoxLayout(reader, payloadStart, payloadEnd)) 0 else 4
        val children = parseBoxes(reader, payloadStart + childOffsetInPayload, payloadEnd)
        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            children = children,
            warnings = warnings,
        )
    }
}

private fun isPlainBoxLayout(reader: ByteReader, payloadStart: Long, payloadEnd: Long): Boolean {
    val fourCcOffset = payloadStart + 4
    if (fourCcOffset + 4 > payloadEnd) return false
    val bytes = reader.readBytes(fourCcOffset, 4)
    return bytes.all { (it.toInt() and 0xFF) in 0x20..0x7E }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.MetaBoxDecoderTest"
```
Expected: PASS (3 tests).

- [ ] **Step 5: Register `MetaBoxDecoder` in place of the inline `ContainerBoxDecoder`**

In `Decoders.kt`, replace:
```kotlin
    BoxRegistry.register("meta", ContainerBoxDecoder(childOffsetInPayload = 4))
```
with:
```kotlin
    BoxRegistry.register("meta", MetaBoxDecoder)
```

- [ ] **Step 6: Run the full parser test suite**

Run:
```bash
./gradlew test
```
Expected: PASS — all existing tests still pass (the Task 6 `MetaBoxDecoderTest`-equivalent test from that task registers its own local `ContainerBoxDecoder(childOffsetInPayload = 4)` directly and is unaffected by this change), plus the 3 new tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MetaBoxDecoder.kt app/src/test/kotlin/com/multiviewer/parser/MetaBoxDecoderTest.kt app/src/main/kotlin/com/multiviewer/parser/Decoders.kt
git commit -m "fix(parser): detect QuickTime plain-box meta layout vs ISO FullBox layout"
```

---

### Task 26: Lazy row decoding for large sample tables (avoid eager in-memory materialization)

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/BoxNode.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/FixedWidthTableDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/StszBoxDecoder.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/TableRowReader.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/FixedWidthTableDecoderTest.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/StszBoxDecoderTest.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/TableRowReaderTest.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/TableView.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Context:** `FixedWidthTableDecoder` and `StszBoxDecoder`'s variable-size branch currently decode every row into `TableData.rows: List<List<Long>>` during `decode()`, even though the UI only ever displays 200 rows at a time (`TableView`'s pagination). For a file with millions of samples, this means allocating millions of boxed `List<Long>` objects up front just to parse the tree — bounded by file size (no unbounded blowup) but working against the "don't explode large tables" scalability goal. This task changes `TableData` to carry table *metadata* (byte offset of the first entry, entry count, and field widths) instead of pre-decoded rows, and adds a small `readTableRow` function that decodes exactly one row on demand, given an open reader. `TableView` (which already opens its own `ByteReader` independent of the parser, per the established parser/UI I/O boundary — see `HexView`) calls this function only for the rows on the currently visible page.

**Interfaces:**
- Consumes: `ByteReader`, `readUIntOfWidth` from Tasks 2, 8.
- Produces: `TableData(columns: List<String>, fieldWidths: List<Int>, entriesStart: Long, entryCount: Long)` (replaces the old `rows`-based shape — this is a breaking change to `TableData`'s public shape, consumed by `TableView`). `fun readTableRow(reader: ByteReader, entriesStart: Long, fieldWidths: List<Int>, rowIndex: Long): List<Long>` — consumed by `TableView`. `TableView`'s signature changes to `TableView(file: File, table: TableData)` — consumed by `Main.kt`.

- [ ] **Step 1: Update `TableData`'s shape in `BoxNode.kt`**

Replace:
```kotlin
data class TableData(
    val columns: List<String>,
    val rows: List<List<Long>>,
)
```
with:
```kotlin
data class TableData(
    val columns: List<String>,
    val fieldWidths: List<Int>,
    val entriesStart: Long,
    val entryCount: Long,
)
```

- [ ] **Step 2: Write the failing test for `readTableRow`**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class TableRowReaderTest {
    @Test
    fun `reads a two-field row at the correct file offset`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x14, // row 0: (10, 20)
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1E, // row 1: (1, 30)
        )
        val reader = byteReaderOf(body)
        assertEquals(listOf(10L, 20L), readTableRow(reader, entriesStart = 0, fieldWidths = listOf(4, 4), rowIndex = 0))
        assertEquals(listOf(1L, 30L), readTableRow(reader, entriesStart = 0, fieldWidths = listOf(4, 4), rowIndex = 1))
        reader.close()
    }

    @Test
    fun `reads a single 8-byte-wide field row (e.g. co64 chunk offsets)`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, // row 0: 65536
        )
        val reader = byteReaderOf(body)
        assertEquals(listOf(65536L), readTableRow(reader, entriesStart = 0, fieldWidths = listOf(8), rowIndex = 0))
        reader.close()
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.TableRowReaderTest"
```
Expected: FAIL — `readTableRow` doesn't exist yet.

- [ ] **Step 4: Implement `readTableRow`**

```kotlin
package com.multiviewer.parser

fun readTableRow(reader: ByteReader, entriesStart: Long, fieldWidths: List<Int>, rowIndex: Long): List<Long> {
    val entryWidth = fieldWidths.sum()
    var pos = entriesStart + rowIndex * entryWidth
    val row = mutableListOf<Long>()
    for (width in fieldWidths) {
        row.add(readUIntOfWidth(reader, pos, width))
        pos += width
    }
    return row
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.TableRowReaderTest"
```
Expected: PASS (2 tests).

- [ ] **Step 6: Update `FixedWidthTableDecoder` to stop eagerly decoding rows**

Replace the body of `decode` from the `val rows = mutableListOf<List<Long>>()` loop onward:

```kotlin
package com.multiviewer.parser

class FixedWidthTableDecoder(
    private val columns: List<String>,
    private val fieldWidths: List<Int>,
) : BoxDecoder {
    init {
        require(columns.size == fieldWidths.size) { "columns and fieldWidths must be the same size" }
    }

    private val entryWidth = fieldWidths.sum()

    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 8) {
            w.add("Box too short to contain a FullBox header and entry count")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val entryCount = reader.readUInt32(payloadStart + 4)
        val entriesStart = payloadStart + 8
        val available = payloadEnd - entriesStart
        val fitCount = if (entryWidth == 0) 0 else available / entryWidth
        val actualCount = minOf(entryCount, fitCount)
        if (actualCount < entryCount) {
            w.add("Declared $entryCount entries but only enough space for $fitCount")
        }
        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            warnings = w,
            summary = pluralize(entryCount, "entry", "entries"),
            table = TableData(columns, fieldWidths, entriesStart, actualCount),
        )
    }
}
```

- [ ] **Step 7: Update `FixedWidthTableDecoderTest` for the new `TableData` shape**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class FixedWidthTableDecoderTest {
    @Test
    fun `decodes entry_count and records table metadata, summarizing instead of exposing rows as children`() {
        val decoder = FixedWidthTableDecoder(listOf("sample_count", "sample_delta"), listOf(4, 4))
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x00, 0x00, 0x00, 0x02, // entry_count = 2
            0x00, 0x00, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x14, // row 1: (10, 20)
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1E, // row 2: (1, 30)
        )
        val reader = byteReaderOf(body)
        val node = decoder.decode(reader, "stts", 0, 0, body.size.toLong(), emptyList())
        assertEquals("2 entries", node.summary)
        assertEquals(listOf("sample_count", "sample_delta"), node.table?.columns)
        assertEquals(listOf(4, 4), node.table?.fieldWidths)
        assertEquals(8L, node.table?.entriesStart)
        assertEquals(2L, node.table?.entryCount)
        assertEquals(true, node.children.isEmpty())
        reader.close()
    }

    @Test
    fun `declared entry_count larger than available bytes truncates with a warning`() {
        val decoder = FixedWidthTableDecoder(listOf("chunk_offset"), listOf(4))
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x00, 0x00, 0x00, 0x64, // entry_count = 100 (way more than available)
            0x00, 0x00, 0x00, 0x01, // only 1 entry actually fits
        )
        val reader = byteReaderOf(body)
        val node = decoder.decode(reader, "stco", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1L, node.table?.entryCount)
        assertEquals(1, node.warnings.size)
        reader.close()
    }
}
```

- [ ] **Step 8: Run the updated test to verify it passes**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.FixedWidthTableDecoderTest"
```
Expected: PASS (2 tests).

- [ ] **Step 9: Update `StszBoxDecoder`'s variable-size branch the same way**

Replace the body of `decode` from `val entriesStart = payloadStart + 12` onward:

```kotlin
package com.multiviewer.parser

object StszBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 12) {
            w.add("Box too short to contain a FullBox header, sample_size and sample_count")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val sampleSizeOffset = payloadStart + 4
        val sampleSize = reader.readUInt32(sampleSizeOffset)
        val sampleCountOffset = payloadStart + 8
        val sampleCount = reader.readUInt32(sampleCountOffset)

        if (sampleSize != 0L) {
            return BoxNode(
                type = type, offset = offset, headerSize = headerSize, size = size, warnings = w,
                fields = listOf(
                    BoxField("sample_size", sampleSize.toString(), sampleSizeOffset, 4),
                    BoxField("sample_count", sampleCount.toString(), sampleCountOffset, 4),
                ),
                summary = "${pluralize(sampleCount, "sample", "samples")}, uniform size $sampleSize",
            )
        }

        val entriesStart = payloadStart + 12
        val available = payloadEnd - entriesStart
        val fitCount = available / 4
        val actualCount = minOf(sampleCount, fitCount)
        if (actualCount < sampleCount) {
            w.add("Declared $sampleCount entries but only enough space for $fitCount")
        }
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size, warnings = w,
            summary = "${pluralize(sampleCount, "entry", "entries")} (variable size)",
            table = TableData(listOf("sample_size"), listOf(4), entriesStart, actualCount),
        )
    }
}
```

- [ ] **Step 10: Update `StszBoxDecoderTest` for the new `TableData` shape**

Replace the second test (`sample_size 0 means variable sizes follow as a table`) with:

```kotlin
    @Test
    fun `sample_size 0 means variable sizes follow as a table`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x00, 0x00, 0x00, 0x00, // sample_size = 0 (variable)
            0x00, 0x00, 0x00, 0x02, // sample_count = 2
            0x00, 0x00, 0x01, 0x00, // size[0] = 256
            0x00, 0x00, 0x02, 0x00, // size[1] = 512
        )
        val reader = byteReaderOf(body)
        val node = StszBoxDecoder.decode(reader, "stsz", 0, 0, body.size.toLong(), emptyList())
        assertEquals("2 entries (variable size)", node.summary)
        assertEquals(12L, node.table?.entriesStart)
        assertEquals(2L, node.table?.entryCount)
        assertEquals(listOf("sample_size"), node.table?.columns)
        assertEquals(listOf(4), node.table?.fieldWidths)
        reader.close()
    }
```

The first test (`uniform sample size is reported as fields, not a table`) is unaffected — leave it as-is.

- [ ] **Step 11: Run the parser test suite**

Run:
```bash
./gradlew test --tests "com.multiviewer.parser.*"
```
Expected: PASS — all parser tests green with the new `TableData` shape.

- [ ] **Step 12: Update `TableView` to read rows on demand**

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.TableData
import com.multiviewer.parser.readTableRow
import java.io.File

private const val PAGE_SIZE = 200

@Composable
fun TableView(file: File, table: TableData) {
    var page by remember(table) { mutableStateOf(0) }
    val pageCount = (((table.entryCount + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)).toInt()
    val start = page.toLong() * PAGE_SIZE
    val end = minOf(start + PAGE_SIZE, table.entryCount)

    val reader = remember(file) { ByteReader.open(file) }
    DisposableEffect(reader) {
        onDispose { reader.close() }
    }

    val rows = remember(table, page) {
        (start until end).map { rowIndex -> readTableRow(reader, table.entriesStart, table.fieldWidths, rowIndex) }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Row {
            Text(table.columns.joinToString("  |  "))
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(rows) { row ->
                Text(row.joinToString("  |  ") { it.toString() })
            }
        }
        Row {
            Button(onClick = { page = (page - 1).coerceAtLeast(0) }, enabled = page > 0) {
                Text("Previous")
            }
            Text(" Page ${page + 1} / $pageCount (${table.entryCount} total entries) ")
            Button(onClick = { page = (page + 1).coerceAtMost(pageCount - 1) }, enabled = page < pageCount - 1) {
                Text("Next")
            }
        }
    }
}
```

Note: `ByteReader.open` and `.close()` are the same API `HexView` uses for its own independent `RandomAccessFile`-backed reads — this keeps `TableView` consistent with the established parser/UI I/O boundary (the UI does its own file reads for display; the parser's own `ByteReader` from `parseFile` was already closed by the time the UI renders).

- [ ] **Step 13: Update `Main.kt`'s call site**

In `Main.kt`, change:
```kotlin
                                if (selectedNode?.table != null) {
                                    com.multiviewer.ui.TableView(selectedNode.table!!)
                                } else {
```
to:
```kotlin
                                if (selectedNode?.table != null) {
                                    com.multiviewer.ui.TableView(currentTab.file, selectedNode.table!!)
                                } else {
```

- [ ] **Step 14: Verify it compiles and the full suite passes**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test
```
Expected: BUILD SUCCESSFUL, all tests passing.

Run:
```bash
./gradlew run
```
Expected: launches without exceptions (same process-level verification as prior UI tasks — no interactive GUI access in this sandbox).

- [ ] **Step 15: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/BoxNode.kt app/src/main/kotlin/com/multiviewer/parser/FixedWidthTableDecoder.kt app/src/main/kotlin/com/multiviewer/parser/StszBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/TableRowReader.kt app/src/test/kotlin/com/multiviewer/parser/FixedWidthTableDecoderTest.kt app/src/test/kotlin/com/multiviewer/parser/StszBoxDecoderTest.kt app/src/test/kotlin/com/multiviewer/parser/TableRowReaderTest.kt app/src/main/kotlin/com/multiviewer/ui/TableView.kt app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "perf(parser,ui): decode large sample table rows lazily instead of eagerly at parse time"
```
