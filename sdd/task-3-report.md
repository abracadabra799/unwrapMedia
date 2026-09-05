# Task 3 Report: `PtyCliTtyConnector` + `WindowsPtyCliSession`

## Status: DONE_WITH_CONCERNS (one test-file adjustment; see deviations)

## Files changed (all new, committed in `011463b`)
- `app/src/main/kotlin/com/multiviewer/ui/terminal/PtyCliTtyConnector.kt`
- `app/src/main/kotlin/com/multiviewer/ui/terminal/WindowsPtyCliSession.kt`
- `app/src/test/kotlin/com/multiviewer/ui/terminal/WindowsPtyCliSessionTest.kt`

## TDD evidence

### RED (Step 2)
`./gradlew :app:test --tests "com.multiviewer.ui.terminal.WindowsPtyCliSessionTest"` after adding only the test:

```
> Task :app:compileTestKotlin FAILED
e: WindowsPtyCliSessionTest.kt:33:78 Unresolved reference 'WindowsPtyCliSession'.
e: WindowsPtyCliSessionTest.kt:41:65 Unresolved reference 'SessionState'.
e: WindowsPtyCliSessionTest.kt:44:29 Unresolved reference 'state'.
... (16 unresolved-reference errors, all for WindowsPtyCliSession / SessionState / members)
BUILD FAILED
```

### GREEN (Step 5)
After adding `PtyCliTtyConnector.kt` and `WindowsPtyCliSession.kt`:

```
> Task :app:test
BUILD SUCCESSFUL in 1s
```
`TEST-com.multiviewer.ui.terminal.WindowsPtyCliSessionTest.xml`: `tests="6" skipped="0" failures="0" errors="0"`

### Full suite (Step 6)
`./gradlew :app:test` -> `BUILD SUCCESSFUL`. Aggregate across all `app/build/test-results/test/*.xml`:
- Total tests: **838** (832 prior + 6 new)
- Failures/errors: **0**

## API-adjustment deviations

### 1. Implementation files: NONE
Both `PtyCliTtyConnector.kt` and `WindowsPtyCliSession.kt` were transcribed verbatim from the brief and compiled clean on the first attempt against the real jediterm 3.74 / pty4j 0.13.12 APIs. Verified signatures via `javap`:
- `ProcessTtyConnector(Process, Charset, java.util.List<String>)` exists — Kotlin `null` for the 3rd arg is accepted (Java platform type, unannotated).
- `TtyConnector.resize(TermSize)` is a `default` method — overridable in the subclass.
- `TermSize.getColumns()/getRows()` -> Kotlin `.columns/.rows` synthetic properties OK.
- `PtyProcess.setWinSize(WinSize)`, `WinSize(int,int)` — present.
- `PtyProcessBuilder` setters `setCommand(String[])`, `setDirectory`, `setEnvironment`, `setInitialColumns(Integer)`, `setInitialRows(Integer)`, `setConsole`, `setUseWinConPty`, `setWindowsAnsiColorEnabled`, `start()` — all present. `setInitialColumns(120)` autoboxes Int->Integer fine.

### 2. Test file: one line changed (unavoidable — brief's test did not compile)
The brief's test imports `org.junit.jupiter.api.Assertions.*` and calls bare `fail(...)` as the last statement of the `awaitState` helper. JUnit Jupiter's `Assertions.fail` is `<V> V fail(String)`, and Kotlin cannot infer `V` in that position:

```
e: WindowsPtyCliSessionTest.kt:47:9 Not enough information to infer type argument for 'V'.
```

- **Before:** `fail("state never satisfied predicate; last = ${s.state}")`
- **After:**  `fail<Unit>("state never satisfied predicate; last = ${s.state}")`

Behavior identical (throws `AssertionFailedError` either way). Only the explicit type argument was added; nothing else in the test changed. This is the most localized fix; the project's own convention elsewhere is `kotlin.test.fail` (returns `Nothing`), which would have been the alternative.

## Self-review
- Verbatim transcription of both impl files confirmed against the brief.
- No extra public API added. `PtyCliTtyConnector`: `getName()` + `resize(TermSize)` only. `WindowsPtyCliSession`: exactly the members listed in the brief.
- ESC written as the `` escape in the test (matching brief); no literal control bytes in any file.
- `startProcess` real default (pty4j ConPTY builder) compiles on macOS; tests inject `FakeProcess` and never execute it.
- Watcher thread is daemon, named `ai-cli-watch`, swallows `InterruptedException` on teardown.
- `git add` limited to the 3 new files; `sdd/` left untracked. Branch verified `feature/embedded-ai-cli-terminal` before commit.
- Unused imports in the test (`TimeUnit`) are carried from the brief verbatim; Kotlin does not error on them.

## Concern
- The brief's stated Step 6 expectation ("832 + 6 = 838") matches exactly.
- The only deviation is the `fail<Unit>` type argument in the test helper — flagged as DONE_WITH_CONCERNS purely so the reviewer is aware the brief's test text was not byte-for-byte compilable.
