# CLI Dump Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a headless `unwrapMedia dump <file>` command that prints the parsed structure tree as pretty-printed JSON to stdout, without touching the GUI toolkit.

**Architecture:** A small generic `JsonValue` tree + pretty-printer (Task 1) is the only JSON-writing primitive in the codebase; a `BoxNode`-to-`JsonValue` mapping (Task 2) converts the existing parser output into it; a `dumpFile`/`DumpResult` layer (Task 3) wraps `parseFile` with error handling and reuses `AppState`'s existing extension allow-lists; a thin `runDumpCommand` (Task 4) turns that into stdout/stderr output and an exit code; and `main()` (Task 5) branches to it before ever calling Compose's `application { ... }`.

**Tech Stack:** Kotlin, no new dependencies (hand-written JSON writer — this project has no JSON library today).

## Global Constraints

- Never initialize the GUI toolkit (Compose/AWT) when the `dump` subcommand is used — the branch in `main()` must happen before any Compose/AWT class is touched, since a headless CI runner has no display.
- Exit code `0` on successful parse, regardless of whether the resulting tree contains `warnings` — never non-zero purely because the file has structural warnings. Exit code `1` for any failure (missing argument, missing file, unsupported extension, parse exception).
- Output is stdout only (no `--out` flag), pretty-printed with 2-space indentation (no `--compact` flag).
- Raw pixel formats (`.raw`/`.rgb`/`.rgba`/`.yuv`) are out of scope — only formats `parseFile()` can parse from magic bytes alone (the existing `IMAGE_EXTENSIONS`/`VIDEO_EXTENSIONS`/`AUDIO_EXTENSIONS` in `AppState.kt`) are supported by `dump`.
- No JSON parsing/round-trip library — this feature only ever writes JSON.
- Spec: `docs/superpowers/specs/2026-07-28-cli-dump-mode-design.md`

---

### Task 1: JsonValue tree and pretty-printer

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/cli/JsonValue.kt`
- Test: `app/src/test/kotlin/com/multiviewer/cli/JsonValueTest.kt`

**Interfaces:**
- Produces: `sealed class JsonValue` with four cases (`JObject`, `JArray`, `JString`, `JNumber`) and `fun JsonValue.render(indent: Int = 0): String`, both in package `com.multiviewer.cli`. Task 2 consumes these exact names/shapes to build a `BoxNode` → `JsonValue` mapping.

This is a generic, `BoxNode`-agnostic JSON value tree and printer — no knowledge of this app's parser types. Keeping it generic and separately testable avoids hand-rolling indentation arithmetic once per call site (a fragile, easy-to-get-off-by-one approach) — one recursive `render` function handles nesting uniformly for both objects and arrays.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/cli/JsonValueTest.kt`:

```kotlin
package com.multiviewer.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonValueTest {
    @Test
    fun `renders a flat object with string, number, and array values`() {
        val value = JsonValue.JObject(
            listOf(
                "name" to JsonValue.JString("test"),
                "count" to JsonValue.JNumber(5),
                "tags" to JsonValue.JArray(listOf(JsonValue.JString("a"), JsonValue.JString("b"))),
            ),
        )

        val expected = "{\n" +
            "  \"name\": \"test\",\n" +
            "  \"count\": 5,\n" +
            "  \"tags\": [\n" +
            "    \"a\",\n" +
            "    \"b\"\n" +
            "  ]\n" +
            "}"
        assertEquals(expected, value.render())
    }

    @Test
    fun `renders nested objects with increasing indentation`() {
        val value = JsonValue.JObject(
            listOf(
                "outer" to JsonValue.JObject(
                    listOf("inner" to JsonValue.JString("deep")),
                ),
            ),
        )

        val expected = "{\n" +
            "  \"outer\": {\n" +
            "    \"inner\": \"deep\"\n" +
            "  }\n" +
            "}"
        assertEquals(expected, value.render())
    }

    @Test
    fun `renders empty objects and arrays compactly`() {
        assertEquals("{}", JsonValue.JObject(emptyList()).render())
        assertEquals("[]", JsonValue.JArray(emptyList()).render())
    }

    @Test
    fun `escapes quotes, backslashes, and newlines in strings`() {
        // Actual characters in `input`: " a \ " b \ \ c \ n d  (a double-quote, then a, a
        // backslash, a double-quote, b, two backslashes, c, a literal newline, d)
        val input = "a\"b\\c\nd"
        val rendered = JsonValue.JString(input).render()
        // Expected characters in `rendered`: " a \" b \\ c \n d "  (each real double-quote and
        // backslash from the input is escaped, and the real newline becomes the two characters
        // backslash-n) -- verified by printing the actual char codes before writing this
        // assertion, not hand-derived.
        assertEquals("\"a\\\"b\\\\c\\nd\"", rendered)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.cli.JsonValueTest"`
Expected: FAIL to compile — `JsonValue` doesn't exist yet.

- [ ] **Step 3: Create the implementation**

Create `app/src/main/kotlin/com/multiviewer/cli/JsonValue.kt`:

```kotlin
package com.multiviewer.cli

// A minimal, BoxNode-agnostic JSON value tree and pretty-printer -- this project has no JSON
// library dependency, and this shape (no polymorphism beyond these four cases, no maps) doesn't
// need one. One recursive `render` handles indentation uniformly for both JObject and JArray,
// rather than hand-rolling padding arithmetic separately at each call site.
sealed class JsonValue {
    data class JObject(val entries: List<Pair<String, JsonValue>>) : JsonValue()
    data class JArray(val items: List<JsonValue>) : JsonValue()
    data class JString(val value: String) : JsonValue()
    data class JNumber(val value: Long) : JsonValue()
}

fun JsonValue.render(indent: Int = 0): String {
    val pad = "  ".repeat(indent)
    val childPad = "  ".repeat(indent + 1)
    return when (this) {
        is JsonValue.JObject -> if (entries.isEmpty()) {
            "{}"
        } else {
            "{\n" + entries.joinToString(",\n") { (key, value) ->
                "$childPad${jsonString(key)}: ${value.render(indent + 1)}"
            } + "\n$pad}"
        }
        is JsonValue.JArray -> if (items.isEmpty()) {
            "[]"
        } else {
            "[\n" + items.joinToString(",\n") { "$childPad${it.render(indent + 1)}" } + "\n$pad]"
        }
        is JsonValue.JString -> jsonString(value)
        is JsonValue.JNumber -> value.toString()
    }
}

private fun jsonString(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append("\"")
    return sb.toString()
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.cli.JsonValueTest"`
Expected: PASS (4/4)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/cli/JsonValue.kt app/src/test/kotlin/com/multiviewer/cli/JsonValueTest.kt
git commit -m "Add a generic JsonValue tree and pretty-printer for CLI dump mode"
```

---

### Task 2: BoxNode-to-JSON mapping

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/cli/BoxNodeJson.kt`
- Test: `app/src/test/kotlin/com/multiviewer/cli/BoxNodeJsonTest.kt`

**Interfaces:**
- Consumes: `JsonValue` (`JObject`/`JArray`/`JString`/`JNumber`) and `JsonValue.render()` from Task 1 (same package, no import needed).
- Consumes: `BoxNode(type, offset, headerSize, size, children, fields, warnings, summary, table, grid)`, `BoxField(name, value, offset, length)`, `TableData(columns, fieldWidths, entriesStart, entryCount)`, `GridData(columns, rows, values)` from `com.multiviewer.parser` (`BoxNode.kt`) — already-existing types, unchanged by this task.
- Produces: `fun buildDumpJson(file: File, root: BoxNode): String` in package `com.multiviewer.cli`. Task 3 consumes this exact name/signature.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/multiviewer/cli/BoxNodeJsonTest.kt`:

```kotlin
package com.multiviewer.cli

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.GridData
import com.multiviewer.parser.TableData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class BoxNodeJsonTest {
    @Test
    fun `buildDumpJson wraps the tree with file name and size, omitting empty collections and null summary`() {
        val child = BoxNode(
            type = "SOF0", offset = 2L, headerSize = 4, size = 15L,
            fields = listOf(BoxField("width", "640", 9L, 2L)),
        )
        val root = BoxNode(type = "root", offset = 0L, headerSize = 0, size = 17L, children = listOf(child))
        val file = File.createTempFile("dump-test-", ".bin")
        file.deleteOnExit()
        file.writeBytes(ByteArray(17))

        val json = buildDumpJson(file, root)

        val expected = "{\n" +
            "  \"file\": \"${file.name}\",\n" +
            "  \"fileSize\": 17,\n" +
            "  \"root\": {\n" +
            "    \"type\": \"root\",\n" +
            "    \"offset\": 0,\n" +
            "    \"headerSize\": 0,\n" +
            "    \"size\": 17,\n" +
            "    \"children\": [\n" +
            "      {\n" +
            "        \"type\": \"SOF0\",\n" +
            "        \"offset\": 2,\n" +
            "        \"headerSize\": 4,\n" +
            "        \"size\": 15,\n" +
            "        \"fields\": [\n" +
            "          {\n" +
            "            \"name\": \"width\",\n" +
            "            \"value\": \"640\",\n" +
            "            \"offset\": 9,\n" +
            "            \"length\": 2\n" +
            "          }\n" +
            "        ]\n" +
            "      }\n" +
            "    ]\n" +
            "  }\n" +
            "}"
        assertEquals(expected, json)
        file.delete()
    }

    @Test
    fun `includes summary, warnings, table, and grid only when present`() {
        val node = BoxNode(
            type = "QuantizationTable", offset = 0L, headerSize = 1, size = 65L,
            warnings = listOf("Declared length 999 extends past the end of the file"),
            summary = "precision=0, destination_id=0, quality~50%",
            table = TableData(columns = listOf("id", "name"), fieldWidths = listOf(4, 20), entriesStart = 10L, entryCount = 3L),
            grid = GridData(columns = 2, rows = 2, values = listOf("1", "2", "3", "4")),
        )

        val json = node.toJsonValue().render()

        assertEquals(
            "{\n" +
                "  \"type\": \"QuantizationTable\",\n" +
                "  \"offset\": 0,\n" +
                "  \"headerSize\": 1,\n" +
                "  \"size\": 65,\n" +
                "  \"summary\": \"precision=0, destination_id=0, quality~50%\",\n" +
                "  \"warnings\": [\n" +
                "    \"Declared length 999 extends past the end of the file\"\n" +
                "  ],\n" +
                "  \"table\": {\n" +
                "    \"columns\": [\n" +
                "      \"id\",\n" +
                "      \"name\"\n" +
                "    ],\n" +
                "    \"fieldWidths\": [\n" +
                "      4,\n" +
                "      20\n" +
                "    ],\n" +
                "    \"entriesStart\": 10,\n" +
                "    \"entryCount\": 3\n" +
                "  },\n" +
                "  \"grid\": {\n" +
                "    \"columns\": 2,\n" +
                "    \"rows\": 2,\n" +
                "    \"values\": [\n" +
                "      \"1\",\n" +
                "      \"2\",\n" +
                "      \"3\",\n" +
                "      \"4\"\n" +
                "    ]\n" +
                "  }\n" +
                "}",
            json,
        )
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.cli.BoxNodeJsonTest"`
Expected: FAIL to compile — `buildDumpJson` and `toJsonValue` don't exist yet.

- [ ] **Step 3: Create the implementation**

Create `app/src/main/kotlin/com/multiviewer/cli/BoxNodeJson.kt`:

```kotlin
package com.multiviewer.cli

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.GridData
import com.multiviewer.parser.TableData
import java.io.File

fun buildDumpJson(file: File, root: BoxNode): String {
    val wrapper = JsonValue.JObject(
        listOf(
            "file" to JsonValue.JString(file.name),
            "fileSize" to JsonValue.JNumber(file.length()),
            "root" to root.toJsonValue(),
        ),
    )
    return wrapper.render()
}

fun BoxNode.toJsonValue(): JsonValue {
    val entries = mutableListOf(
        "type" to JsonValue.JString(type),
        "offset" to JsonValue.JNumber(offset),
        "headerSize" to JsonValue.JNumber(headerSize.toLong()),
        "size" to JsonValue.JNumber(size),
    )
    summary?.let { entries.add("summary" to JsonValue.JString(it)) }
    if (fields.isNotEmpty()) {
        entries.add("fields" to JsonValue.JArray(fields.map { it.toJsonValue() }))
    }
    if (warnings.isNotEmpty()) {
        entries.add("warnings" to JsonValue.JArray(warnings.map { JsonValue.JString(it) }))
    }
    table?.let { entries.add("table" to it.toJsonValue()) }
    grid?.let { entries.add("grid" to it.toJsonValue()) }
    if (children.isNotEmpty()) {
        entries.add("children" to JsonValue.JArray(children.map { it.toJsonValue() }))
    }
    return JsonValue.JObject(entries)
}

private fun BoxField.toJsonValue(): JsonValue = JsonValue.JObject(
    listOf(
        "name" to JsonValue.JString(name),
        "value" to JsonValue.JString(value),
        "offset" to JsonValue.JNumber(offset),
        "length" to JsonValue.JNumber(length),
    ),
)

private fun TableData.toJsonValue(): JsonValue = JsonValue.JObject(
    listOf(
        "columns" to JsonValue.JArray(columns.map { JsonValue.JString(it) }),
        "fieldWidths" to JsonValue.JArray(fieldWidths.map { JsonValue.JNumber(it.toLong()) }),
        "entriesStart" to JsonValue.JNumber(entriesStart),
        "entryCount" to JsonValue.JNumber(entryCount),
    ),
)

private fun GridData.toJsonValue(): JsonValue = JsonValue.JObject(
    listOf(
        "columns" to JsonValue.JNumber(columns.toLong()),
        "rows" to JsonValue.JNumber(rows.toLong()),
        "values" to JsonValue.JArray(values.map { JsonValue.JString(it) }),
    ),
)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.cli.BoxNodeJsonTest"`
Expected: PASS (2/2)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/cli/BoxNodeJson.kt app/src/test/kotlin/com/multiviewer/cli/BoxNodeJsonTest.kt
git commit -m "Add BoxNode-to-JSON mapping for CLI dump mode"
```

---

### Task 3: dumpFile with extension validation and error handling

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/cli/DumpFile.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt:14,27,35`
- Test: `app/src/test/kotlin/com/multiviewer/cli/DumpFileTest.kt`

**Interfaces:**
- Consumes: `buildDumpJson(file: File, root: BoxNode): String` from Task 2 (`com.multiviewer.cli`, same package, no import needed).
- Consumes: `parseFile(path: File): BoxNode` from `com.multiviewer.parser` (`ParseFile.kt`) — already exists, unchanged.
- Consumes: `IMAGE_EXTENSIONS`, `VIDEO_EXTENSIONS`, `AUDIO_EXTENSIONS` (`List<String>`) from `com.multiviewer.ui` (`AppState.kt`) — this task makes them non-private (see Step 1).
- Produces: `sealed class DumpResult { data class Success(val json: String); data class Failure(val message: String) }` and `fun dumpFile(file: File): DumpResult`, both in package `com.multiviewer.cli`. Task 4 consumes these exact names/shapes.

- [ ] **Step 1: Make AppState's extension lists non-private**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, remove the `private` modifier from these three declarations so `dumpFile` (a different package) can reuse the same extension lists the GUI already validates against, instead of duplicating them:

Line 14, change:
```kotlin
private val IMAGE_EXTENSIONS = listOf(
```
to:
```kotlin
val IMAGE_EXTENSIONS = listOf(
```

Line 27, change:
```kotlin
private val VIDEO_EXTENSIONS = listOf("mp4", "mov", "m4v")
```
to:
```kotlin
val VIDEO_EXTENSIONS = listOf("mp4", "mov", "m4v")
```

Line 35, change:
```kotlin
private val AUDIO_EXTENSIONS = listOf("m4a", "mp3", "wav")
```
to:
```kotlin
val AUDIO_EXTENSIONS = listOf("m4a", "mp3", "wav")
```

`RAW_PIXEL_EXTENSIONS` (a separate `private val` further down the file) stays private and untouched — raw pixel dumps are out of scope for this feature (see Global Constraints).

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/cli/DumpFileTest.kt`:

```kotlin
package com.multiviewer.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DumpFileTest {
    @Test
    fun `dumpFile returns Failure when the file does not exist`() {
        val result = dumpFile(File("/nonexistent/path/does-not-exist.jpg"))

        assertTrue(result is DumpResult.Failure)
        assertTrue((result as DumpResult.Failure).message.contains("not found"), "Expected a 'not found' message, got: ${result.message}")
    }

    @Test
    fun `dumpFile returns Failure for an unsupported extension`() {
        val file = File.createTempFile("dump-test-", ".xyz")
        file.deleteOnExit()
        file.writeBytes(ByteArray(4))

        val result = dumpFile(file)

        assertTrue(result is DumpResult.Failure)
        assertTrue((result as DumpResult.Failure).message.contains("xyz"), "Expected the message to mention the extension, got: ${result.message}")
        file.delete()
    }

    @Test
    fun `dumpFile returns Success with a JSON tree for a real PNG`() {
        val file = File.createTempFile("dump-test-", ".png")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=red:size=32x24",
            "-frames:v", "1", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val result = dumpFile(file)

        assertTrue(result is DumpResult.Success)
        val json = (result as DumpResult.Success).json
        assertTrue(json.contains("\"file\": \"${file.name}\""), "Expected the file name in the output, got: $json")
        assertTrue(json.contains("\"IHDR\""), "Expected a PNG IHDR node in the output, got: $json")
        file.delete()
    }

    @Test
    fun `dumpFile returns Success with a JSON tree for a real MP4`() {
        val file = File.createTempFile("dump-test-", ".mp4")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=64x48:rate=10",
            file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val result = dumpFile(file)

        assertTrue(result is DumpResult.Success)
        val json = (result as DumpResult.Success).json
        assertTrue(json.contains("\"ftyp\""), "Expected an ftyp box in the output, got: $json")
        file.delete()
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.cli.DumpFileTest"`
Expected: FAIL to compile — `DumpResult` and `dumpFile` don't exist yet.

- [ ] **Step 4: Create the implementation**

Create `app/src/main/kotlin/com/multiviewer/cli/DumpFile.kt`:

```kotlin
package com.multiviewer.cli

import com.multiviewer.parser.parseFile
import com.multiviewer.ui.AUDIO_EXTENSIONS
import com.multiviewer.ui.IMAGE_EXTENSIONS
import com.multiviewer.ui.VIDEO_EXTENSIONS
import java.io.File

sealed class DumpResult {
    data class Success(val json: String) : DumpResult()
    data class Failure(val message: String) : DumpResult()
}

// Reuses the same extension allow-lists the GUI validates against (AppState.kt) rather than
// duplicating them -- raw pixel formats are deliberately excluded (RAW_PIXEL_EXTENSIONS, not
// imported here): they need width/height/format parameters this simple CLI has no way to supply.
fun dumpFile(file: File): DumpResult {
    if (!file.exists()) {
        return DumpResult.Failure("File not found: ${file.path}")
    }
    val extension = file.extension.lowercase()
    val supported = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS + AUDIO_EXTENSIONS
    if (extension !in supported) {
        return DumpResult.Failure("Unsupported extension: .$extension (supported: ${supported.joinToString(", ")})")
    }
    return try {
        val root = parseFile(file)
        DumpResult.Success(buildDumpJson(file, root))
    } catch (e: Exception) {
        DumpResult.Failure("Failed to parse ${file.path}: ${e.message ?: e.toString()}")
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.cli.DumpFileTest"`
Expected: PASS (4/4)

- [ ] **Step 6: Run the full suite (confirms the AppState.kt visibility change didn't break anything)**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/cli/DumpFile.kt app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/test/kotlin/com/multiviewer/cli/DumpFileTest.kt
git commit -m "Add dumpFile: parse-and-serialize with extension validation and error handling"
```

---

### Task 4: runDumpCommand (stdout/stderr + exit code)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/cli/DumpCommand.kt`
- Test: `app/src/test/kotlin/com/multiviewer/cli/DumpCommandTest.kt`

**Interfaces:**
- Consumes: `DumpResult` (`Success`/`Failure`) and `dumpFile(file: File): DumpResult` from Task 3 (`com.multiviewer.cli`, same package, no import needed).
- Produces: `fun runDumpCommand(args: List<String>): Int` in package `com.multiviewer.cli`. Task 5 consumes this exact name/signature.

This is the CLI argument-parsing and exit-code layer, kept separate from `dumpFile` (Task 3) so tests can call it directly and read its return value instead of needing to spawn a real process or intercept `System.exit`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/cli/DumpCommandTest.kt`:

```kotlin
package com.multiviewer.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DumpCommandTest {
    // Captures stdout/stderr around a block, restoring the real streams afterward -- runDumpCommand
    // writes directly to System.out/System.err (it's the layer whose whole job is doing that), so
    // this is the only way to observe its output without spawning a real process.
    private fun captureOutput(block: () -> Int): Triple<Int, String, String> {
        val originalOut = System.out
        val originalErr = System.err
        val outBuffer = ByteArrayOutputStream()
        val errBuffer = ByteArrayOutputStream()
        System.setOut(PrintStream(outBuffer))
        System.setErr(PrintStream(errBuffer))
        val exitCode = try {
            block()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        return Triple(exitCode, outBuffer.toString(), errBuffer.toString())
    }

    @Test
    fun `returns exit code 1 and prints usage to stderr when no file argument is given`() {
        val (exitCode, stdout, stderr) = captureOutput { runDumpCommand(emptyList()) }

        assertEquals(1, exitCode)
        assertEquals("", stdout)
        assertTrue(stderr.contains("Usage"), "Expected a usage message, got: $stderr")
    }

    @Test
    fun `returns exit code 1 and prints the failure message to stderr for a missing file`() {
        val (exitCode, stdout, stderr) = captureOutput { runDumpCommand(listOf("/nonexistent/does-not-exist.jpg")) }

        assertEquals(1, exitCode)
        assertEquals("", stdout)
        assertTrue(stderr.contains("not found"), "Expected a 'not found' message, got: $stderr")
    }

    @Test
    fun `returns exit code 0 and prints JSON to stdout for a real PNG`() {
        val file = File.createTempFile("dump-command-test-", ".png")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=blue:size=16x16",
            "-frames:v", "1", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val (exitCode, stdout, stderr) = captureOutput { runDumpCommand(listOf(file.absolutePath)) }

        assertEquals(0, exitCode)
        assertEquals("", stderr)
        assertTrue(stdout.contains("\"IHDR\""), "Expected a PNG IHDR node in stdout, got: $stdout")
        file.delete()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.cli.DumpCommandTest"`
Expected: FAIL to compile — `runDumpCommand` doesn't exist yet.

- [ ] **Step 3: Create the implementation**

Create `app/src/main/kotlin/com/multiviewer/cli/DumpCommand.kt`:

```kotlin
package com.multiviewer.cli

import java.io.File

fun runDumpCommand(args: List<String>): Int {
    val path = args.firstOrNull()
    if (path == null) {
        System.err.println("Usage: unwrapMedia dump <file>")
        return 1
    }
    return when (val result = dumpFile(File(path))) {
        is DumpResult.Success -> {
            println(result.json)
            0
        }
        is DumpResult.Failure -> {
            System.err.println(result.message)
            1
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.cli.DumpCommandTest"`
Expected: PASS (3/3)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/cli/DumpCommand.kt app/src/test/kotlin/com/multiviewer/cli/DumpCommandTest.kt
git commit -m "Add runDumpCommand: argument parsing, stdout/stderr, exit codes"
```

---

### Task 5: Wire `dump` into main()

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt:112`

**Interfaces:**
- Consumes: `runDumpCommand(args: List<String>): Int` from Task 4 (`com.multiviewer.cli` package — new import needed, `Main.kt` is in package `com.multiviewer`).

No automated test for this task — it's a two-line branch in front of the existing, already-tested `application { ... }` GUI body, and this codebase has no process-spawning test harness for `main()` itself (see the design spec's Testing section). Verified by compiling, running the full suite (confirms the GUI path still works exactly as before — nothing inside `application { ... }` changes), and a manual CLI smoke test against a packaged build.

- [ ] **Step 1: Split main() into the dump branch and the (unchanged) GUI body**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, add this import alongside the existing imports (anywhere in the import block, e.g. right after `import com.multiviewer.parser.extractEmbeddedVideo`):

```kotlin
import com.multiviewer.cli.runDumpCommand
import kotlin.system.exitProcess
```

Then change line 112 from:

```kotlin
fun main() = application {
```

to:

```kotlin
fun main(args: Array<String>) {
    if (args.firstOrNull() == "dump") {
        exitProcess(runDumpCommand(args.drop(1)))
    }
    runGuiApplication()
}

private fun runGuiApplication() = application {
```

Nothing else in the file changes — everything from `val appState = remember { AppState() }` (the current line 113) through the file's final closing braces stays byte-for-byte identical, now running as the body of `runGuiApplication()` instead of `main()`. This is a pure text substitution of one line into the block above; no reflow or re-indentation is needed since Kotlin doesn't require it to compile.

- [ ] **Step 2: Compile**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL, no errors.

- [ ] **Step 3: Run the full test suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass (including every test from Tasks 1-4).

- [ ] **Step 4: Manual smoke test — GUI path still works**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew :app:run` in the background, confirm the "Starting unwrapMedia..." banner appears in the log with no exceptions, confirm the process is alive, then kill it. This confirms `args.firstOrNull() == "dump"` being false for a normal launch (Gradle's `:app:run` task passes no args) correctly falls through to `runGuiApplication()` unchanged.

- [ ] **Step 5: Manual smoke test — dump path works from a real packaged binary**

`:app:run` goes through Gradle's own application plugin, which doesn't cleanly support passing CLI args through to a Compose Desktop entry point — build and run the actual packaged distribution instead:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew :app:packageDistributionForCurrentOS
```

Then find and run the packaged binary's `dump` command against a real file (e.g. any `.jpg`/`.png`/`.mp4` on hand), for example on macOS:

```bash
find app/build/compose/binaries -name "unwrapMedia" -type f
# then, using the path that command prints:
<that-path>/unwrapMedia dump /path/to/some/real/file.jpg
```

Expected: pretty-printed JSON on stdout, exit code 0 (check with `echo $?` immediately after). Try it once more against a nonexistent file and confirm a clear error on stderr with exit code 1 (`echo $?`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "Wire the dump subcommand into main(), before the GUI toolkit is ever touched"
```
