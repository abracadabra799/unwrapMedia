# CLI Dump Mode Design

## Goal

Add a headless `unwrapMedia dump <file>` command that serializes the parsed structure tree to JSON on stdout, so the box/marker/IFD structure this app already parses can be consumed by scripts and CI pipelines instead of only being viewable in the GUI. This is the first of two sub-projects toward "developer tool" usability (see Non-Goals) -- a follow-up sub-project (feeding a dumped JSON back into the app for spec-compliance checking) is deliberately out of scope here and will be brainstormed separately once this exists and its output shape is known.

## Background

unwrapMedia's `main()` (`app/src/main/kotlin/com/multiviewer/Main.kt`) currently takes no arguments and unconditionally calls Compose Desktop's `application { ... }`, which starts the GUI event loop. There is no way to get parsed output without opening a window -- not viable in a headless CI environment, and not scriptable.

The existing `BoxNode`/`BoxField`/`TableData`/`GridData` model (`BoxNode.kt`) already carries everything the GUI's structure tree and Detailed Properties panel show: node type, byte offset/size, decoded fields, warnings, and structural metadata. Critically, this model never embeds large binary payloads directly (e.g. `ThumbnailImage` nodes store only offset+size; the GUI reads the actual thumbnail bytes from the source file on demand when displayed) -- so a full-tree JSON dump stays proportional to node count, not file size, even for large images/videos.

The project has no JSON library dependency today (`app/build.gradle.kts`).

## Scope

### A. Entry point

`main()` gains an `args: Array<String>` parameter and branches before touching any Compose/AWT machinery:

```kotlin
fun main(args: Array<String>) {
    if (args.firstOrNull() == "dump") {
        exitProcess(runDumpCommand(args.drop(1)))
    }
    runGuiApplication() // the existing `application { ... }` body, moved into its own function
}
```

This is the critical safety property: a headless CI runner invoking `dump` must never initialize the GUI toolkit at all (no display available, so doing so risks a crash or hang) -- the branch happens as the very first statement in `main`, before any Compose/AWT class is touched.

Any first argument other than `"dump"` (including no arguments at all) falls through to the existing GUI path, unchanged.

### B. Dump command

`runDumpCommand(args: List<String>): Int` (new file, e.g. `app/src/main/kotlin/com/multiviewer/cli/DumpCommand.kt`) is a thin argument-parsing and exit-code wrapper around a testable core:

```kotlin
fun runDumpCommand(args: List<String>): Int {
    val path = args.firstOrNull()
    if (path == null) {
        System.err.println("Usage: unwrapMedia dump <file>")
        return 1
    }
    val result = dumpFile(File(path))
    when (result) {
        is DumpResult.Success -> println(result.json)
        is DumpResult.Failure -> System.err.println(result.message)
    }
    return if (result is DumpResult.Success) 0 else 1
}
```

`dumpFile(file: File): DumpResult` is the pure, testable core (no `System.exit`, no `println` -- returns a value):

```kotlin
sealed class DumpResult {
    data class Success(val json: String) : DumpResult()
    data class Failure(val message: String) : DumpResult()
}

fun dumpFile(file: File): DumpResult {
    if (!file.exists()) return DumpResult.Failure("File not found: ${file.path}")
    val extension = file.extension.lowercase()
    val supported = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS + AUDIO_EXTENSIONS // from AppState.kt; RAW_PIXEL_EXTENSIONS excluded, see Non-Goals
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

`IMAGE_EXTENSIONS`/`VIDEO_EXTENSIONS`/`AUDIO_EXTENSIONS` are currently `private val` in `AppState.kt` (`app/src/main/kotlin/com/multiviewer/ui/AppState.kt`) -- this task removes the `private` modifier on those three (only; `RAW_PIXEL_EXTENSIONS` stays private, unused by this feature) so `dumpFile` can reuse the same extension list the GUI already validates against, rather than duplicating it.

Exit codes: `0` on successful parse (regardless of whether the resulting tree contains `warnings`), `1` for any failure (missing file, unsupported extension, parse exception, missing argument). Deliberately never non-zero purely because the parsed file *has* structural warnings -- "does this file have warnings" is answered by the JSON's own `warnings` arrays, and a CI script decides what counts as a failure for its own purposes (e.g. `jq '.. | .warnings? // empty' out.json`) rather than this tool imposing one policy.

### C. JSON shape and serialization

New file `app/src/main/kotlin/com/multiviewer/cli/JsonWriter.kt`. No new dependency -- a small hand-written writer, since `BoxNode`'s shape (strings, numbers, nullable strings, lists, no polymorphism) doesn't need a general-purpose serialization framework, and avoiding one keeps the existing zero-JSON-library dependency footprint and build configuration unchanged.

Top-level shape (`buildDumpJson`, pretty-printed with 2-space indentation -- readable when pasted directly into a bug report, and `jq`/any JSON parser handles pretty-printed input identically to compact):

```json
{
  "file": "photo.jpg",
  "fileSize": 7120245,
  "root": {
    "type": "root",
    "offset": 0,
    "headerSize": 0,
    "size": 7120245,
    "children": [
      { "type": "SOI", "offset": 0, "headerSize": 2, "size": 2 },
      { "type": "DQT", "offset": 2, "headerSize": 4, "size": 69, "children": [ ... ] }
    ]
  }
}
```

`BoxNode` fields map directly: `type`/`offset`/`headerSize`/`size` always present; `children`/`fields`/`warnings` present only when non-empty (omitted otherwise, keeping output compact for the common case of leaf nodes with no fields); `summary` present only when non-null; `table` (as `{"columns": [...], "fieldWidths": [...], "entriesStart": ..., "entryCount": ...}`) and `grid` (as `{"columns": ..., "rows": ..., "values": [...]}`) present only when non-null. `BoxField` maps to `{"name": ..., "value": ..., "offset": ..., "length": ...}`.

String values (node `type`, field `name`/`value`, `summary`, file path) are escaped for `"`, `\`, control characters (including newlines, relevant for multi-line XMP/comment field values), and non-ASCII is passed through as-is (output is UTF-8, not `\uXXXX`-escaped -- valid JSON either way, and keeps Korean/other non-Latin field values human-readable in the raw output).

## Non-Goals

- **Feeding a dumped JSON back into the app for spec-compliance checking** -- explicitly deferred to a follow-up sub-project, brainstormed separately once this dump format exists and its output is seen in practice (much of "is this file spec-compliant" may already be answered by the existing `warnings` arrays this dump already exposes, which could shrink that follow-up's scope significantly).
- **Raw pixel dump support** (`--width`/`--height`/`--format` args for `.raw`/`.rgb`/`.rgba`/`.yuv`) -- these formats need parameters the CLI has no way to supply yet; only formats `parseFile()` can parse from magic bytes alone are in scope.
- **A `--out <file>` flag or any output destination besides stdout** -- shell redirection (`> out.json`) already covers this.
- **A JSON parsing/round-trip library** -- this feature only ever writes JSON, never reads it back; no parser dependency is needed.
- **A `--compact`/minified output flag** -- pretty-printed is the only mode; not worth the flag surface for a first version.

## Testing

- **`JsonWriter`**: unit tests building small, hand-constructed `BoxNode` trees (matching this project's existing `JpegWalkerTest`/`MotionPhotoExtractorTest` style of literal, non-mocked fixtures) and asserting the exact expected JSON string, covering: a leaf node with no fields, a node with fields, nested children, a node with `table`/`grid`, and string-escaping (a field value containing `"`, `\`, and a newline).
- **`dumpFile`**: real-file integration tests using `ProcessBuilder("ffmpeg", ...)`-generated fixtures (this project's established pattern for real media test fixtures) for at least one image format (e.g. PNG) and one video format (e.g. MP4), asserting the returned `DumpResult.Success.json` contains expected top-level keys and at least one known marker/box type. Plus failure-path tests: nonexistent file, unsupported extension -- both asserting `DumpResult.Failure` with a message, not an exception escaping.
- **`runDumpCommand`**: tests asserting the correct exit code (`0`/`1`) for a success case and each failure case, without spawning a real process or calling `System.exit` (tests call the function directly and read its `Int` return value).
- No test invokes `main()` itself (that would require an out-of-process test to observe real `System.exit` / real stdout, which this project's test setup doesn't have) -- `runDumpCommand`'s return value is treated as equivalent coverage for the exit-code logic, and manual verification of `main()`'s branching (`./gradlew :app:packageDistributionForCurrentOS` then invoking the packaged binary with `dump`, per this project's established manual-smoke-test convention) covers the one bit of wiring the automated tests can't reach.
