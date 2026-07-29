# CLI Check Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a headless `unwrapMedia check <file>` command that reports the parser's already-computed structural `warnings` (the same ones the GUI's Detailed Properties panel shows) as JSON on stdout.

**Architecture:** `collectWarnings`/`WarningEntry` moves from the UI layer into the parser layer (Task 1) so both the GUI and the new CLI can share it. A new `parseForCli` (Task 2) extracts the file-validation logic `dumpFile` already has, so `checkFile` (Task 3) doesn't duplicate it — `dumpFile` itself is refactored to use it too, with no behavior change. `checkFile`/`buildCheckJson` (Task 3) build the check-specific JSON via the existing `JsonValue`/`render()` from the `dump` feature. `runCheckCommand` (Task 4) mirrors `runDumpCommand`'s stdout/stderr/exit-code shape. `main()` (Task 5) gains a second subcommand branch.

**Tech Stack:** Kotlin, no new dependencies (reuses `com.multiviewer.cli.JsonValue`/`render()` from the already-shipped `dump` feature).

## Global Constraints

- Exit code `0` when the file parses successfully, regardless of `warningCount` -- even a file with many warnings exits 0. Exit code `1` only for genuine tool failure (missing argument, missing file, unsupported extension, parse exception) -- matches `dump`'s existing exit-code philosophy exactly.
- No new semantic spec-conformance rules added anywhere -- only already-computed `BoxNode.warnings` are surfaced.
- No JSON-parsing dependency -- `check`, like `dump`, only ever writes JSON.
- `check` must never touch the GUI toolkit (Compose/AWT), same safety property `dump` already has.
- Spec: `docs/superpowers/specs/2026-07-29-cli-check-mode-design.md`

---

### Task 1: Move collectWarnings/WarningEntry into the parser layer

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/Warnings.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt:256-266` (remove), imports (add two)
- Create: `app/src/test/kotlin/com/multiviewer/parser/WarningsTest.kt`
- Delete: `app/src/test/kotlin/com/multiviewer/ui/ImageInspectorUITest.kt`

**Interfaces:**
- Produces: `data class WarningEntry(val node: BoxNode, val warning: String)` and `fun collectWarnings(root: BoxNode): List<WarningEntry>`, both in package `com.multiviewer.parser`. Task 3 consumes `collectWarnings` by name.

This is a pure relocation -- identical code, new location, no behavior change. `ImageInspectorUITest.kt` currently contains only these three tests (nothing else), so it's deleted rather than left empty.

- [ ] **Step 1: Create the new test file with the moved tests**

Create `app/src/test/kotlin/com/multiviewer/parser/WarningsTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class WarningsTest {
    @Test
    fun `collectWarnings flattens warnings from every depth, sorted by offset`() {
        val deepChild = BoxNode(
            type = "DHT", offset = 100, headerSize = 4, size = 10,
            warnings = listOf("Huffman table truncated"),
        )
        val midChild = BoxNode(
            type = "APP1", offset = 20, headerSize = 4, size = 50,
            warnings = listOf("Declared length extends past the end of the file"),
            children = listOf(deepChild),
        )
        val cleanChild = BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2)
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 200,
            children = listOf(cleanChild, midChild),
        )

        val warnings = collectWarnings(root)

        assertEquals(2, warnings.size)
        assertEquals("APP1", warnings[0].node.type)
        assertEquals("Declared length extends past the end of the file", warnings[0].warning)
        assertEquals("DHT", warnings[1].node.type)
        assertEquals("Huffman table truncated", warnings[1].warning)
    }

    @Test
    fun `collectWarnings returns an empty list for a tree with no warnings anywhere`() {
        val child = BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 2, children = listOf(child))

        assertEquals(emptyList(), collectWarnings(root))
    }

    @Test
    fun `collectWarnings includes a node with multiple warnings once per warning`() {
        val child = BoxNode(
            type = "DQT", offset = 5, headerSize = 4, size = 10,
            warnings = listOf("first issue", "second issue"),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 20, children = listOf(child))

        val warnings = collectWarnings(root)

        assertEquals(2, warnings.size)
        assertEquals("first issue", warnings[0].warning)
        assertEquals("second issue", warnings[1].warning)
    }
}
```

- [ ] **Step 2: Delete the old test file**

```bash
rm app/src/test/kotlin/com/multiviewer/ui/ImageInspectorUITest.kt
```

- [ ] **Step 3: Run the new test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.WarningsTest"`
Expected: FAIL to compile -- `collectWarnings` doesn't exist in `com.multiviewer.parser` yet.

- [ ] **Step 4: Create Warnings.kt**

Create `app/src/main/kotlin/com/multiviewer/parser/Warnings.kt`:

```kotlin
package com.multiviewer.parser

data class WarningEntry(val node: BoxNode, val warning: String)

fun collectWarnings(root: BoxNode): List<WarningEntry> {
    val entries = mutableListOf<WarningEntry>()
    fun walk(node: BoxNode) {
        node.warnings.forEach { entries.add(WarningEntry(node, it)) }
        node.children.forEach { walk(it) }
    }
    walk(root)
    return entries.sortedBy { it.node.offset }
}
```

- [ ] **Step 5: Remove the old declarations from ImageInspectorUI.kt and add imports**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`, delete these lines (currently 256-266):

```kotlin
data class WarningEntry(val node: BoxNode, val warning: String)

fun collectWarnings(root: BoxNode): List<WarningEntry> {
    val entries = mutableListOf<WarningEntry>()
    fun walk(node: BoxNode) {
        node.warnings.forEach { entries.add(WarningEntry(node, it)) }
        node.children.forEach { walk(it) }
    }
    walk(root)
    return entries.sortedBy { it.node.offset }
}

```

(Leave the blank line and `@Composable fun DetailedPropertiesPanel(tab: TabState) {` that followed them intact -- only the `WarningEntry`/`collectWarnings` block itself is removed.)

Then add these two imports alongside the existing `import com.multiviewer.parser.BoxNode` line near the top of the file:

```kotlin
import com.multiviewer.parser.WarningEntry
import com.multiviewer.parser.collectWarnings
```

`DetailedPropertiesPanel`'s own body (the `collectWarnings(root)` call and the `entry.node`/`entry.warning` field access inside the `items(warnings) { entry -> ... }` block) needs no changes -- only the declarations moved, not their usage.

- [ ] **Step 6: Run the test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.WarningsTest"`
Expected: PASS (3/3)

- [ ] **Step 7: Run the full suite (confirms ImageInspectorUI.kt still compiles and nothing else broke)**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/Warnings.kt app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt app/src/test/kotlin/com/multiviewer/parser/WarningsTest.kt app/src/test/kotlin/com/multiviewer/ui/ImageInspectorUITest.kt
git commit -m "Move collectWarnings/WarningEntry from the UI layer into the parser layer"
```

---

### Task 2: Shared parseForCli; refactor dumpFile to use it

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/cli/ParseForCli.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/cli/DumpFile.kt` (full rewrite, same file)
- Create: `app/src/test/kotlin/com/multiviewer/cli/ParseForCliTest.kt`

**Interfaces:**
- Consumes: `parseFile(path: File): BoxNode` from `com.multiviewer.parser`, `IMAGE_EXTENSIONS`/`VIDEO_EXTENSIONS`/`AUDIO_EXTENSIONS` from `com.multiviewer.ui` -- both already existing, unchanged.
- Produces: `sealed class CliParseResult { data class Success(val file: File, val root: BoxNode); data class Failure(val message: String) }` and `fun parseForCli(file: File): CliParseResult`, both in package `com.multiviewer.cli`. Task 3 consumes both exactly.
- `DumpResult`/`dumpFile` (already existing, package `com.multiviewer.cli`) keep their exact same public signature -- this task only changes their internals.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/cli/ParseForCliTest.kt`:

```kotlin
package com.multiviewer.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ParseForCliTest {
    @Test
    fun `parseForCli returns Failure when the file does not exist`() {
        val result = parseForCli(File("/nonexistent/path/does-not-exist.jpg"))

        assertTrue(result is CliParseResult.Failure)
        assertTrue((result as CliParseResult.Failure).message.contains("not found"), "Expected a 'not found' message, got: ${result.message}")
    }

    @Test
    fun `parseForCli returns Failure for an unsupported extension`() {
        val file = File.createTempFile("parse-for-cli-test-", ".xyz")
        file.deleteOnExit()
        file.writeBytes(ByteArray(4))

        val result = parseForCli(file)

        assertTrue(result is CliParseResult.Failure)
        assertTrue((result as CliParseResult.Failure).message.contains("xyz"), "Expected the message to mention the extension, got: ${result.message}")
        file.delete()
    }

    @Test
    fun `parseForCli returns Success with the parsed root for a real PNG`() {
        val file = File.createTempFile("parse-for-cli-test-", ".png")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=red:size=32x24",
            "-frames:v", "1", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val result = parseForCli(file)

        assertTrue(result is CliParseResult.Success)
        val success = result as CliParseResult.Success
        assertTrue(success.root.children.any { it.type == "IHDR" }, "Expected a PNG IHDR node, got: ${success.root.children.map { it.type }}")
        file.delete()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.cli.ParseForCliTest"`
Expected: FAIL to compile -- `parseForCli`/`CliParseResult` don't exist yet.

- [ ] **Step 3: Create ParseForCli.kt**

Create `app/src/main/kotlin/com/multiviewer/cli/ParseForCli.kt`:

```kotlin
package com.multiviewer.cli

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.parseFile
import com.multiviewer.ui.AUDIO_EXTENSIONS
import com.multiviewer.ui.IMAGE_EXTENSIONS
import com.multiviewer.ui.VIDEO_EXTENSIONS
import java.io.File

sealed class CliParseResult {
    data class Success(val file: File, val root: BoxNode) : CliParseResult()
    data class Failure(val message: String) : CliParseResult()
}

// Shared by dumpFile and checkFile -- both need "does the file exist, is its extension
// supported, does parseFile succeed" before doing anything format-specific with the result.
// Reuses the same extension allow-lists the GUI validates against (AppState.kt) rather than
// duplicating them -- raw pixel formats are deliberately excluded (RAW_PIXEL_EXTENSIONS, not
// imported here): they need width/height/format parameters this simple CLI has no way to supply.
fun parseForCli(file: File): CliParseResult {
    if (!file.exists()) {
        return CliParseResult.Failure("File not found: ${file.path}")
    }
    val extension = file.extension.lowercase()
    val supported = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS + AUDIO_EXTENSIONS
    if (extension !in supported) {
        return CliParseResult.Failure("Unsupported extension: .$extension (supported: ${supported.joinToString(", ")})")
    }
    return try {
        CliParseResult.Success(file, parseFile(file))
    } catch (e: Exception) {
        CliParseResult.Failure("Failed to parse ${file.path}: ${e.message ?: e.toString()}")
    }
}
```

- [ ] **Step 4: Rewrite DumpFile.kt to use parseForCli**

Replace the entire content of `app/src/main/kotlin/com/multiviewer/cli/DumpFile.kt` with:

```kotlin
package com.multiviewer.cli

import java.io.File

sealed class DumpResult {
    data class Success(val json: String) : DumpResult()
    data class Failure(val message: String) : DumpResult()
}

fun dumpFile(file: File): DumpResult = when (val result = parseForCli(file)) {
    is CliParseResult.Success -> DumpResult.Success(buildDumpJson(result.file, result.root))
    is CliParseResult.Failure -> DumpResult.Failure(result.message)
}
```

- [ ] **Step 5: Run the new tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.cli.ParseForCliTest"`
Expected: PASS (3/3)

- [ ] **Step 6: Run the existing DumpFileTest to confirm the refactor didn't change dumpFile's behavior**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.cli.DumpFileTest"`
Expected: PASS (4/4) -- this file is unmodified; passing confirms the refactor preserved `dumpFile`'s exact behavior.

- [ ] **Step 7: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/cli/ParseForCli.kt app/src/main/kotlin/com/multiviewer/cli/DumpFile.kt app/src/test/kotlin/com/multiviewer/cli/ParseForCliTest.kt
git commit -m "Extract parseForCli from dumpFile so checkFile can reuse it"
```

---

### Task 3: checkFile and buildCheckJson

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/cli/CheckFile.kt`
- Test: `app/src/test/kotlin/com/multiviewer/cli/CheckFileTest.kt`

**Interfaces:**
- Consumes: `CliParseResult`/`parseForCli` from Task 2, `JsonValue`/`render()` from `com.multiviewer.cli.JsonValue` (already existing), `WarningEntry`/`collectWarnings` from `com.multiviewer.parser` (Task 1) -- all same-package or already-imported patterns established by earlier tasks.
- Produces: `sealed class CheckResult { data class Success(val json: String); data class Failure(val message: String) }`, `fun checkFile(file: File): CheckResult`, and `fun buildCheckJson(file: File, warnings: List<WarningEntry>): String`, all in package `com.multiviewer.cli`. Task 4 consumes `CheckResult`/`checkFile` exactly.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/cli/CheckFileTest.kt`:

```kotlin
package com.multiviewer.cli

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.WarningEntry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheckFileTest {
    @Test
    fun `buildCheckJson lists each warning with its node type, offset, and message`() {
        val node1 = BoxNode(type = "QuantizationTable", offset = 20L, headerSize = 1, size = 65L)
        val node2 = BoxNode(type = "HuffmanTable", offset = 6699L, headerSize = 1, size = 30L)
        val warnings = listOf(
            WarningEntry(node1, "Declared size 999 extends 934 byte(s) past the end of its parent"),
            WarningEntry(node2, "declares 12 code(s) but not enough symbol data remains"),
        )
        val file = File.createTempFile("check-file-test-", ".jpg")
        file.deleteOnExit()

        val json = buildCheckJson(file, warnings)

        val expected = "{\n" +
            "  \"file\": \"${file.name}\",\n" +
            "  \"warningCount\": 2,\n" +
            "  \"warnings\": [\n" +
            "    {\n" +
            "      \"type\": \"QuantizationTable\",\n" +
            "      \"offset\": 20,\n" +
            "      \"message\": \"Declared size 999 extends 934 byte(s) past the end of its parent\"\n" +
            "    },\n" +
            "    {\n" +
            "      \"type\": \"HuffmanTable\",\n" +
            "      \"offset\": 6699,\n" +
            "      \"message\": \"declares 12 code(s) but not enough symbol data remains\"\n" +
            "    }\n" +
            "  ]\n" +
            "}"
        assertEquals(expected, json)
        file.delete()
    }

    @Test
    fun `buildCheckJson reports an empty warnings array and zero count for a clean tree`() {
        val file = File.createTempFile("check-file-test-", ".jpg")
        file.deleteOnExit()

        val json = buildCheckJson(file, emptyList())

        assertEquals(
            "{\n" +
                "  \"file\": \"${file.name}\",\n" +
                "  \"warningCount\": 0,\n" +
                "  \"warnings\": []\n" +
                "}",
            json,
        )
        file.delete()
    }

    @Test
    fun `checkFile returns Failure when the file does not exist`() {
        val result = checkFile(File("/nonexistent/path/does-not-exist.jpg"))

        assertTrue(result is CheckResult.Failure)
        assertTrue((result as CheckResult.Failure).message.contains("not found"), "Expected a 'not found' message, got: ${result.message}")
    }

    @Test
    fun `checkFile returns Success with warningCount 0 for a clean real PNG`() {
        val file = File.createTempFile("check-file-test-", ".png")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=blue:size=16x16",
            "-frames:v", "1", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val result = checkFile(file)

        assertTrue(result is CheckResult.Success)
        val json = (result as CheckResult.Success).json
        assertTrue(json.contains("\"warningCount\": 0"), "Expected a clean PNG to report 0 warnings, got: $json")
        file.delete()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.cli.CheckFileTest"`
Expected: FAIL to compile -- `checkFile`/`CheckResult`/`buildCheckJson` don't exist yet.

- [ ] **Step 3: Create CheckFile.kt**

Create `app/src/main/kotlin/com/multiviewer/cli/CheckFile.kt`:

```kotlin
package com.multiviewer.cli

import com.multiviewer.parser.WarningEntry
import com.multiviewer.parser.collectWarnings
import java.io.File

sealed class CheckResult {
    data class Success(val json: String) : CheckResult()
    data class Failure(val message: String) : CheckResult()
}

fun checkFile(file: File): CheckResult = when (val result = parseForCli(file)) {
    is CliParseResult.Success -> CheckResult.Success(buildCheckJson(result.file, collectWarnings(result.root)))
    is CliParseResult.Failure -> CheckResult.Failure(result.message)
}

fun buildCheckJson(file: File, warnings: List<WarningEntry>): String {
    val wrapper = JsonValue.JObject(
        listOf(
            "file" to JsonValue.JString(file.name),
            "warningCount" to JsonValue.JNumber(warnings.size.toLong()),
            "warnings" to JsonValue.JArray(warnings.map { it.toJsonValue() }),
        ),
    )
    return wrapper.render()
}

private fun WarningEntry.toJsonValue(): JsonValue = JsonValue.JObject(
    listOf(
        "type" to JsonValue.JString(node.type),
        "offset" to JsonValue.JNumber(node.offset),
        "message" to JsonValue.JString(warning),
    ),
)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.cli.CheckFileTest"`
Expected: PASS (4/4)

- [ ] **Step 5: Run the full suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/cli/CheckFile.kt app/src/test/kotlin/com/multiviewer/cli/CheckFileTest.kt
git commit -m "Add checkFile/buildCheckJson: report parser warnings as JSON"
```

---

### Task 4: runCheckCommand (stdout/stderr + exit code)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/cli/CheckCommand.kt`
- Test: `app/src/test/kotlin/com/multiviewer/cli/CheckCommandTest.kt`

**Interfaces:**
- Consumes: `CheckResult`/`checkFile(file: File): CheckResult` from Task 3 (`com.multiviewer.cli`, same package, no import needed).
- Produces: `fun runCheckCommand(args: List<String>): Int` in package `com.multiviewer.cli`. Task 5 consumes this exact name/signature.

Mirrors `runDumpCommand` (`app/src/main/kotlin/com/multiviewer/cli/DumpCommand.kt`) exactly -- same shape, different result type, kept as a separate small function rather than a shared abstraction (see the plan's Architecture summary).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/cli/CheckCommandTest.kt`:

```kotlin
package com.multiviewer.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheckCommandTest {
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
        val (exitCode, stdout, stderr) = captureOutput { runCheckCommand(emptyList()) }

        assertEquals(1, exitCode)
        assertEquals("", stdout)
        assertTrue(stderr.contains("Usage"), "Expected a usage message, got: $stderr")
    }

    @Test
    fun `returns exit code 1 and prints the failure message to stderr for a missing file`() {
        val (exitCode, stdout, stderr) = captureOutput { runCheckCommand(listOf("/nonexistent/does-not-exist.jpg")) }

        assertEquals(1, exitCode)
        assertEquals("", stdout)
        assertTrue(stderr.contains("not found"), "Expected a 'not found' message, got: $stderr")
    }

    @Test
    fun `returns exit code 0 and prints JSON to stdout for a real clean PNG, regardless of warningCount`() {
        val file = File.createTempFile("check-command-test-", ".png")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=green:size=16x16",
            "-frames:v", "1", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val (exitCode, stdout, stderr) = captureOutput { runCheckCommand(listOf(file.absolutePath)) }

        assertEquals(0, exitCode)
        assertEquals("", stderr)
        assertTrue(stdout.contains("\"warningCount\""), "Expected a warningCount field in stdout, got: $stdout")
        file.delete()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.cli.CheckCommandTest"`
Expected: FAIL to compile -- `runCheckCommand` doesn't exist yet.

- [ ] **Step 3: Create CheckCommand.kt**

Create `app/src/main/kotlin/com/multiviewer/cli/CheckCommand.kt`:

```kotlin
package com.multiviewer.cli

import java.io.File

fun runCheckCommand(args: List<String>): Int {
    val path = args.firstOrNull()
    if (path == null) {
        System.err.println("Usage: unwrapMedia check <file>")
        return 1
    }
    return when (val result = checkFile(File(path))) {
        is CheckResult.Success -> {
            println(result.json)
            0
        }
        is CheckResult.Failure -> {
            System.err.println(result.message)
            1
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.cli.CheckCommandTest"`
Expected: PASS (3/3)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/cli/CheckCommand.kt app/src/test/kotlin/com/multiviewer/cli/CheckCommandTest.kt
git commit -m "Add runCheckCommand: argument parsing, stdout/stderr, exit code"
```

---

### Task 5: Wire `check` into main()

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt:114-119`

**Interfaces:**
- Consumes: `runCheckCommand(args: List<String>): Int` from Task 4 (`com.multiviewer.cli` package -- new import needed).

No automated test for this task, same rationale as `dump`'s own Task 5 -- verified by compiling, running the full suite, and two manual smoke tests against the packaged binary.

- [ ] **Step 1: Add the import**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, add this import alongside the existing `import com.multiviewer.cli.runDumpCommand` line:

```kotlin
import com.multiviewer.cli.runCheckCommand
```

- [ ] **Step 2: Change main()'s dispatch to a when-branch covering both subcommands**

Change (current lines 114-119):

```kotlin
fun main(args: Array<String>) {
    if (args.firstOrNull() == "dump") {
        exitProcess(runDumpCommand(args.drop(1)))
    }
    runGuiApplication()
}
```

to:

```kotlin
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "dump" -> exitProcess(runDumpCommand(args.drop(1)))
        "check" -> exitProcess(runCheckCommand(args.drop(1)))
        else -> runGuiApplication()
    }
}
```

Nothing else in the file changes.

- [ ] **Step 3: Compile**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL, no errors.

- [ ] **Step 4: Run the full test suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass (including every test from Tasks 1-4).

- [ ] **Step 5: Manual smoke test — GUI path still works**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew :app:run` in the background, confirm the "Starting unwrapMedia..." banner appears with no exceptions, confirm the process is alive, then kill it.

- [ ] **Step 6: Manual smoke test — both dump and check work from the packaged binary**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew :app:packageDistributionForCurrentOS
find app/build/compose/binaries -name "unwrapMedia" -type f
```

Using the path that command prints, generate a real test file and try both subcommands:

```bash
ffmpeg -y -f lavfi -i color=red:size=32x24 -frames:v 1 /tmp/check-smoketest.png
<that-path>/unwrapMedia dump /tmp/check-smoketest.png   # should still work (regression check)
echo "dump exit: $?"
<that-path>/unwrapMedia check /tmp/check-smoketest.png  # new -- should print {"file":..., "warningCount": 0, "warnings": []}
echo "check exit: $?"
<that-path>/unwrapMedia check /nonexistent-file.png
echo "check (missing file) exit: $?"
```

Expected: `dump` prints its usual JSON tree with exit 0 (unchanged from before this task). `check` against the clean PNG prints `warningCount: 0` with an empty `warnings` array and exit 0. `check` against a nonexistent file prints a clear stderr error and exits 1.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "Wire the check subcommand into main() alongside dump"
```
