# CLI Check Mode Design

## Goal

Add a headless `unwrapMedia check <file>` command that reports the same structural warnings the GUI's Detailed Properties panel already surfaces (the "⚠ N개의 구조적 이상 징후" summary), as JSON on stdout -- the second, deferred half of the "developer tool" CLI work started by `dump` (`docs/superpowers/specs/2026-07-28-cli-dump-mode-design.md`).

## Background

This was originally floated as "feed a `dump`-produced JSON back into the app to check spec compliance." Two decisions during brainstorming reshaped it:

1. **Scope**: unwrapMedia's parser already attaches `warnings: List<String>` to `BoxNode`s wherever it detects a byte-level structural problem (truncated tables, declared sizes that overrun the file, unsupported construction methods -- 40 distinct warning call sites across the parser today). This is real, already-computed signal. New *semantic* spec-conformance rules (required-box presence, cross-field reference validation, etc.) are a different and much larger undertaking, explicitly out of scope here -- this feature only surfaces what the parser already knows.
2. **Mechanism**: re-reading a previously-dumped JSON file back in was rejected in favor of `check` re-parsing the source file directly, the same way `dump` does. This needs no new JSON-parsing dependency (this project still only ever *writes* JSON) and stays architecturally identical to `dump`.

A third small correction happened mid-design: the initial plain-text warning-list output was replaced with JSON, since that's the actual common convention for CI-facing lint/validation tools (ESLint `--format=json`, `jpeginfo`-style checkers, etc.) -- plain text would have been a one-off invention where a convention already exists, and it costs nothing extra since `dump`'s `JsonValue`/`render()` (`app/src/main/kotlin/com/multiviewer/cli/JsonValue.kt`) is reused as-is.

## Scope

### A. Extract `collectWarnings`/`WarningEntry` into the parser layer

`data class WarningEntry(val node: BoxNode, val warning: String)` and `fun collectWarnings(root: BoxNode): List<WarningEntry>` currently live in `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt` (used only by `DetailedPropertiesPanel`'s no-selection warnings summary). Neither has any real dependency on Compose or any UI type -- they operate purely on `BoxNode`. Move both into a new parser-layer file (`app/src/main/kotlin/com/multiviewer/parser/Warnings.kt`), update `ImageInspectorUI.kt`'s one call site to import from there, and move the existing `collectWarnings` tests (currently in `app/src/test/kotlin/com/multiviewer/ui/ImageInspectorUITest.kt`) to a matching parser-layer test file. No behavior change -- this is a pure relocation so both the GUI and the new CLI `check` command can share one implementation, keeping with the "cli package depends only on parser, never ui" boundary the `dump` feature already established.

### B. Shared parse-and-validate helper (refactor)

`dumpFile` (`app/src/main/kotlin/com/multiviewer/cli/DumpFile.kt`, already shipped) and the new `checkFile` need identical file-validation logic: does the file exist, is its extension supported (`IMAGE_EXTENSIONS + VIDEO_EXTENSIONS + AUDIO_EXTENSIONS` from `AppState.kt`, `RAW_PIXEL_EXTENSIONS` excluded), and does `parseFile` throw. Extracting this avoids duplicating it a second time:

```kotlin
// app/src/main/kotlin/com/multiviewer/cli/ParseForCli.kt
sealed class CliParseResult {
    data class Success(val file: File, val root: BoxNode) : CliParseResult()
    data class Failure(val message: String) : CliParseResult()
}
fun parseForCli(file: File): CliParseResult
```

`dumpFile` is refactored to call `parseForCli` and build `DumpResult` from its `Success`/`Failure`; behavior is unchanged (verified by the existing `DumpFileTest` continuing to pass unmodified). `checkFile` (new) does the same, building `CheckResult` from the same `CliParseResult`.

`runDumpCommand`/`runCheckCommand` (the stdout/stderr/exit-code layer) stay as two small, separate functions rather than being unified behind a further shared abstraction -- each is ~15 lines, and forcing a generic wrapper over two call sites this size would be premature abstraction for a real but modest amount of duplication.

### C. `checkFile` and JSON output

```kotlin
sealed class CheckResult {
    data class Success(val json: String) : CheckResult()
    data class Failure(val message: String) : CheckResult()
}
fun checkFile(file: File): CheckResult
```

On success, builds JSON via `buildCheckJson(file: File, warnings: List<WarningEntry>): String`, reusing Task 1's `JsonValue`/`render()` exactly as `buildDumpJson` does:

```json
{
  "file": "photo.jpg",
  "warningCount": 2,
  "warnings": [
    { "type": "QuantizationTable", "offset": 20, "message": "Declared size 999 extends 934 byte(s) past the end of its parent" },
    { "type": "HuffmanTable", "offset": 6699, "message": "declares 12 code(s) but not enough symbol data remains" }
  ]
}
```

`type`/`offset` come from the `WarningEntry.node`'s `type`/`offset`; `message` is the `WarningEntry.warning` string; `warnings` is already offset-sorted by `collectWarnings`. `warningCount` is `warnings.size`, included explicitly rather than making a CI script `jq '.warnings | length'` for the common case.

### D. CLI wiring

`main()` (`app/src/main/kotlin/com/multiviewer/Main.kt`) grows a second branch alongside `dump`:

```kotlin
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "dump" -> exitProcess(runDumpCommand(args.drop(1)))
        "check" -> exitProcess(runCheckCommand(args.drop(1)))
        else -> runGuiApplication()
    }
}
```

Same headless-safety property as `dump`: this branch is decided before `runGuiApplication()`/`application{}` is ever reached, so `check` never touches Compose/AWT either.

## Exit Codes (deliberately mirrors `dump`)

`0` when the file parsed successfully, regardless of `warningCount` (even a file with 50 warnings exits 0) -- never non-zero purely because the *content* has findings, only for genuine tool failure (missing argument, missing file, unsupported extension, parse exception, matching `dump`'s existing exit-code philosophy exactly). A CI script that wants pass/fail behavior reads `warningCount` from the JSON itself (e.g. `[ "$(unwrapMedia check f.jpg | jq .warningCount)" = "0" ]`) rather than the tool imposing one policy on what counts as "failing."

## Non-Goals

- **New semantic spec-conformance rules** (required-box presence, cross-field reference validation, value-range checks beyond what the parser already flags while walking bytes) -- only already-computed `BoxNode.warnings` are surfaced; no new validation logic is added to any decoder.
- **Reading a previously-dumped `dump` JSON file back in** -- `check` re-parses the source media file directly, same as `dump`. No JSON-parsing dependency is added.
- **Any change to `dump`'s own output or behavior** beyond the internal `parseForCli` refactor (which is behavior-preserving, verified by its existing tests).

## Testing

- `collectWarnings`/`WarningEntry` relocation: move the existing tests verbatim to the parser layer; no new test content, just location. Confirm the moved tests still pass and `ImageInspectorUI.kt`'s one call site still compiles against the new import.
- `parseForCli`: unit tests for not-found, unsupported-extension, and a real ffmpeg-fixture success case (mirrors `DumpFileTest`'s existing structure).
- `dumpFile` post-refactor: the existing `DumpFileTest` suite must pass unmodified -- this is the regression check that the refactor preserved behavior.
- `checkFile`/`buildCheckJson`: unit test with a hand-built `BoxNode` tree containing known warnings at known offsets, asserting the exact JSON string (same style as `BoxNodeJsonTest`). Plus a real ffmpeg-fixture test using a source known to produce at least one parser warning if one is readily producible, otherwise a real fixture asserting `warningCount: 0` and an empty `warnings` array (a clean file is itself a valid, worthwhile case to cover).
- `runCheckCommand`: same stream-capture test pattern as `DumpCommandTest` -- no-args failure, missing-file failure, and a real-file success case asserting exit code `0` and JSON on stdout regardless of warning count.
- `main()` wiring: no automated test (same rationale as `dump`'s Task 5) -- manual smoke test via the packaged binary, checking both `dump <file>` (still works, regression check) and `check <file>` (new) against a real file.
