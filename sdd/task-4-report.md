# Task 4 Report: `EmbeddedTerminalPanel` Compose component

## Status: DONE

Commit: `8aa758a` — feat: add EmbeddedTerminalPanel Compose component
Branch: `feature/embedded-ai-cli-terminal` (verified before commit)
File added (only): `app/src/main/kotlin/com/multiviewer/ui/terminal/EmbeddedTerminalPanel.kt`

## Step 2: Compile result

`./gradlew :app:compileKotlin` → **BUILD SUCCESSFUL** (Task `:app:compileKotlin` executed, not up-to-date).

## API verification against jediterm-ui 3.74

`javap -p com.jediterm.terminal.ui.JediTermWidget` (jar:
`~/.gradle/caches/modules-2/files-2.1/org.jetbrains.jediterm/jediterm-ui/3.74/.../jediterm-ui-3.74.jar`)

Confirmed present and used as-is:
- `public JediTermWidget(int, int, com.jediterm.terminal.ui.settings.SettingsProvider)` — constructor `JediTermWidget(120, 30, CliTerminalSettings())` resolves.
- `public void setTtyConnector(TtyConnector)` / `public TtyConnector getTtyConnector()` — Kotlin synthetic property `w.ttyConnector = session.ttyConnector` resolves.
- `public void start()` / `public void close()` — resolve.
- `public TerminalPanel getTerminalPanel()` — `w.terminalPanel` resolves.
- `DefaultSettingsProvider.getTerminalFont()` / `getTerminalFontSize()` / `audibleBell()` — all `public`, overrides valid.

## Deviation from the brief (1) — `update` lambda fallback APPLIED

`javap -p com.jediterm.terminal.ui.TerminalPanel` shows:
```
private int getColumnCount();
private int getRowCount();
```
Both accessors are **private** in jediterm 3.74, so the brief's `panel.columnCount` / `panel.rowCount` do **not** resolve from Kotlin.

Per the brief's documented fallback, the `update` lambda body was replaced.

Before (brief):
```kotlin
update = { w ->
    val panel = w.terminalPanel
    session.resize(panel.columnCount.coerceAtLeast(20), panel.rowCount.coerceAtLeast(5))
},
```
After:
```kotlin
update = { w ->
    // size is driven by JediTermWidget's own component listener
},
```
Behavior: terminal resize is left to JediTermWidget's internal component listener; `session.resize(...)` is no longer called from the panel. `WindowsPtyCliSession.resize(cols, rows)` remains available for callers but is unused here.

No other deviations. `w.ttyConnector` setter, the 3-arg constructor, `start()`, `close()` all matched the real API — no adjustments needed. `SwingPanel` hosts the widget fine (structural approach intact).

Note: the `●` in `Text("● ${session.displayName}")` is the literal U+25CF BLACK CIRCLE character (printable, not a control byte), equivalent to the brief's `●`.

## Step 3: Full suite

`./gradlew :app:test` → **BUILD SUCCESSFUL**. Aggregated from `app/build/test-results/test/*.xml`: **838 tests, 0 failures, 0 errors, 0 skipped**. Unchanged from baseline; no new tests added.

## Self-review

- Package, imports, `PROMPT_INJECT_DELAY_MS = 1400`, signature `EmbeddedTerminalPanel(session, onEndSession, modifier)` — all exactly as specified.
- `CliTerminalSettings` private subclass with the three overrides — verbatim.
- `LaunchedEffect(session)` delays then injects prompt once; `DisposableEffect(session)` closes the widget on dispose.
- Header Row: status pill (`●` + displayName), localized state text for all 4 `SessionState` variants (exhaustive `when`), "프롬프트 재주입" and "세션 종료" buttons.
- `SwingPanel` factory builds the widget, wires the tty connector, starts it, stashes it in `widget` state.
- Only deviation is the sanctioned `update` fallback. No extra API/params introduced.
- One benign compiler note possible: unused lambda param `w` in `update` — does not fail the build; left as `w` to stay close to the brief's lambda shape.
