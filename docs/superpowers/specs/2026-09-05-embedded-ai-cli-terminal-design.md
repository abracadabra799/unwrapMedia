# Embedded AI CLI Terminal (Windows) — Design

Date: 2026-09-05
Status: Approved for planning
Scope: Windows only. macOS/Linux keep the existing external-terminal behavior.

## Problem

Today, in the "Generate AI Prompt" popup (`AiPromptPreviewWindow`), clicking a
Local CLI button (`▶ Claude Code` / `Codex` / `Gemini CLI` / `Antigravity`) calls
`AiCliDetector.launchInteractiveCli()`, which on Windows generates a `runner.ps1`
and spawns a **separate** PowerShell window via
`cmd.exe /c start powershell -NoExit -File runner.ps1`. The prompt is copied to
the clipboard and the user pastes it into that external window.

The user wants the CLI session to run **inside** the popup: expand a bottom area
of the popup and connect to the CLI right there, with no external window.

## Goals

- On Windows, clicking a Local CLI button runs the CLI in an embedded terminal
  inside the popup — no external PowerShell window.
- The embedded terminal is a real VT100 emulator so full-screen interactive TUI
  CLIs (Claude Code, Gemini CLI, Codex, agy) render and accept keyboard input
  correctly.
- The diagnostic prompt is auto-injected into the CLI (bracketed paste), with
  clipboard copy retained as a fallback.
- One session at a time, with clear teardown on popup close.

## Non-Goals

- Multi-tab / concurrent CLI sessions.
- Changing macOS/Linux behavior (they keep `launchInteractiveCli`).
- A general-purpose terminal outside the AI prompt popup.
- Detecting "CLI is ready" precisely (a fixed delay is used instead).

## Dependencies (build-time only; nothing new for end users)

Added to `app/build.gradle.kts`:

- `org.jetbrains.jediterm:jediterm-core` (latest 3.x)
- `org.jetbrains.jediterm:jediterm-ui` (latest 3.x)
- `org.jetbrains.pty4j:pty4j` (latest 0.13.x) — bundles ConPTY/winpty native binaries
- Transitively: `net.java.dev.jna:jna`, `net.java.dev.jna:jna-platform`

Approx. +6.5 MB compressed to the dependency set (~10 MB unpacked in the
distributable). The Windows distributable already bundles a JRE, so relative
impact is modest.

End users install nothing new:
- jediterm/pty4j/JNA ship inside the app bundle.
- ConPTY is built into Windows 10 1809+ / Windows 11; pre-1809 falls back to
  bundled winpty.
- The Java runtime is already bundled.
- The AI CLIs themselves still must be installed by the user (unchanged); only
  detected CLIs show a button.

Native-binary trimming for non-Windows platforms is out of scope for this
iteration (keep the full pty4j jar).

## Architecture

```
AiPromptPreviewWindow  (existing popup, 920x700)
 ├─ Box(weight=1f) { existing prompt preview + Web AI / Local CLI buttons }
 └─ if (activeCliSession != null):
     ├─ draggable HorizontalDivider  (adjusts terminalHeightDp)
     └─ EmbeddedTerminalPanel(session, height = terminalHeightDp)   ← NEW
          └─ SwingPanel { JediTermWidget }
               └─ TtyConnector ── WindowsPtyCliSession ── pty4j (ConPTY)
                    └─ powershell.exe -NoLogo -NoExit -ExecutionPolicy Bypass
                         └─ & "<binPath>"   (the AI CLI)
```

### New units

**`PtyCliCommand`** (pure functions, no Compose, no I/O)
- `build(cli: AiCliType, workingDir: File?): List<String>` — returns the
  PowerShell argv used to start the session. Same for all CLI types except the
  post-launch invocation string.
- `launchLine(cli: AiCliType, binPath: String): String` — the PowerShell line
  written into the PTY to start the CLI, e.g.
  `[Console]::OutputEncoding=[Text.Encoding]::UTF8; & "<binPath>"`
  (agy adds `-i`).
- `bracketedPaste(text: String): String` — wraps text as
  `ESC[200~` + normalized body + `ESC[201~` + `\r`. Normalizes CRLF/lone CR to
  `\n` inside the payload.
- What it does: converts a CLI choice into exact strings to feed a PTY.
- Depends on: `AiCliType` only.

**`WindowsPtyCliSession`** (logic; owns the process, no Compose)
- Constructor takes `cli: AiCliType`, `binPath: String`, `workingDir: File?`,
  `promptText: String`, and an injectable clock/delay for testing.
- `start()` —
  1. `PtyProcessBuilder` with `PtyCliCommand.build(...)`, cwd = workingDir,
     `setConsole(false)` (ConPTY), env `TERM=xterm-256color`, UTF-8, initial
     size from the widget.
  2. Wrap `PtyProcess` in pty4j `PtyProcessTtyConnector` exposed as
     `ttyConnector: TtyConnector`.
  3. Write `PtyCliCommand.launchLine(...)` + `\r` to the connector.
  4. After `injectDelayMs` (default 1200), write
     `PtyCliCommand.bracketedPaste(promptText)`.
- `injectPrompt()` — re-runs step 4 on demand.
- `resize(cols, rows)` — forwarded from the widget.
- `destroy()` — `PtyProcess.destroyForcibly()`, close connector.
- `state: StateFlow<SessionState>` where
  `SessionState = Starting | Running | Exited(code: Int) | Failed(reason: String)`.
  A watcher coroutine sets `Exited` when `process.onExit()` completes.
- What it does: owns one CLI process behind a PTY and its lifecycle state.
- Depends on: pty4j, `PtyCliCommand`, `AiCliDetector.findBinary` (caller passes
  the resolved path).

**`EmbeddedTerminalPanel`** (Compose)
- `EmbeddedTerminalPanel(session: WindowsPtyCliSession, onEndSession: () -> Unit, modifier: Modifier)`
- Header row: `● {cli.displayName}` + status text derived from `session.state`
  (`기동 중…` / `실행 중` / `종료됨 (exit N)` / `실패: …`) +
  `[프롬프트 재주입]` (calls `session.injectPrompt()`) +
  `[세션 종료]` (calls `onEndSession`).
- Body: `SwingPanel` factory creates a `JediTermWidget` with a
  `SettingsProvider` (monospaced font: bundled JetBrains Mono if present else
  Consolas; palette from current `ThemeMode`), calls
  `widget.ttyConnector = session.ttyConnector; widget.start()`.
- `update` forwards size → `session.resize(...)`.
- On dispose (leaving composition): `widget.close()`.
- What it does: renders the PTY stream and routes keyboard to it.
- Depends on: jediterm-ui, `WindowsPtyCliSession`, app theme colors.

### Modified units

**`AiPromptPreviewWindow`** (`AnalysisWindows.kt`)
- New state: `activeCliSession: WindowsPtyCliSession?`,
  `terminalHeightDp: Dp` (default 260.dp), `pendingSwitchCli: AiCliType?`,
  `confirmCloseWhileRunning: Boolean`, `baseWindowHeight: Dp`.
- Local CLI button `onClick` (Windows branch):
  1. `ClipboardUtil.copyToClipboard(promptText)` (kept as fallback).
  2. `val bin = AiCliDetector.findBinary(cli.binaryName)` → null ⇒ set
     `statusMessage = "<binaryName> 실행 파일을 찾을 수 없습니다"`, return.
  3. If `activeCliSession?.isAlive == true` ⇒ set `pendingSwitchCli = cli`
     (shows switch-confirm dialog); else `startSession(cli, bin)`.
  - Non-Windows: unchanged call to `launchInteractiveCli`.
- `startSession(cli, bin)`:
  - `activeCliSession = WindowsPtyCliSession(cli, bin, tab.file.parentFile, promptText).also { it.start() }`
  - Grow window once:
    `windowState.size = windowState.size.copy(height = (windowState.size.height + terminalHeightDp).coerceAtMost(screenHeight))`
  - On failure (`SessionState.Failed`): collapse, fall back to
    `launchInteractiveCli`, `statusMessage = "임베드 터미널 실패 — 외부 창으로 실행"`.
- `endSession()`: `activeCliSession?.destroy(); activeCliSession = null`; restore
  window height to `baseWindowHeight`.
- Switch-confirm dialog (`pendingSwitchCli != null`): "현재 세션을 종료하고
  전환할까요?" → confirm: `endSession()` then `startSession(pendingSwitchCli!!)`;
  cancel: clear.
- `onCloseRequest` / ESC handler: if `activeCliSession?.isAlive == true` ⇒ set
  `confirmCloseWhileRunning = true` instead of closing. Confirm dialog: "실행 중인
  CLI 세션을 종료하고 닫습니다." → confirm: `endSession()` then original
  `onCloseRequest()`.
- Layout: wrap existing content in `Column`; existing content gets
  `Modifier.weight(1f)`; when `activeCliSession != null` append a draggable
  `Box`/divider (`pointerInput` drag adjusts `terminalHeightDp`, clamp
  `180.dp..(windowHeight - 200.dp)`) then
  `EmbeddedTerminalPanel(session, onEndSession = ::endSession, Modifier.height(terminalHeightDp).fillMaxWidth())`.

**`AiCliDetector`**
- `findBinary` unchanged.
- `launchInteractiveCli` unchanged — still used by macOS/Linux and by the Windows
  fallback path. No Windows behavior removed from the function itself; the popup
  simply stops calling it on Windows except as fallback.

## Data flow (happy path, Windows)

1. User clicks `▶ Claude Code`.
2. Prompt copied to clipboard; `findBinary("claude")` → `C:\...\claude.cmd`.
3. `WindowsPtyCliSession.start()`:
   - ConPTY starts `powershell.exe -NoLogo -NoExit -ExecutionPolicy Bypass` in
     the media file's folder.
   - Writes `[Console]::OutputEncoding=[Text.Encoding]::UTF8; & "C:\...\claude.cmd"` + CR.
   - State → `Running` when process alive; after 1200 ms writes the
     bracketed-paste-wrapped prompt + CR.
4. `EmbeddedTerminalPanel` mounts, `JediTermWidget` attaches to the connector and
   renders Claude Code's TUI; keyboard now routes to the CLI.
5. Popup window height grew by 260 dp; user drags the divider to taste.
6. User closes popup → "실행 중" confirm → `destroyForcibly()` + connector close →
   window closes.

## Error handling

| Condition | Handling |
|---|---|
| `findBinary` returns null | `statusMessage` only; no panel |
| pty4j `start()` throws / ConPTY unavailable & winpty fails | `SessionState.Failed`; collapse panel; fall back to `launchInteractiveCli`; status note |
| `JediTermWidget` init throws | catch in `SwingPanel` factory; `endSession()`; fall back to external launch |
| CLI process exits early | `SessionState.Exited(code)`; header shows `종료됨 (exit N)`; panel stays until `[세션 종료]` |
| User closes popup mid-session | confirm dialog → `destroy()` then close |
| Switch to another CLI mid-session | confirm dialog → `destroy()` old → start new |

## Testing

### Unit (JUnit5, no real PTY)
- `PtyCliCommand.build()` returns the expected PowerShell argv (cwd handling:
  null workingDir, non-existent dir → user.home).
- `PtyCliCommand.launchLine()` for each `AiCliType` (agy includes `-i`, quoting
  of paths with spaces).
- `PtyCliCommand.bracketedPaste()`: wraps with `\u001B[200~` / `\u001B[201~`,
  trailing `\r`, CRLF and lone-CR in body normalized to `\n`, empty string.
- `WindowsPtyCliSession` state transitions with a fake process handle
  (injected): `Starting → Running → Exited(n)`; `destroy()` from each state.

### Manual (Windows)
- Each detected CLI: launch → TUI renders → prompt auto-injected → typing and
  Ctrl+C work.
- `[프롬프트 재주입]` re-sends prompt.
- Divider drag resizes terminal within clamps.
- Window grows on start, restores on `[세션 종료]`.
- Close popup while running → confirm → process actually terminates (check Task
  Manager, no orphan `powershell.exe`/node).
- Switch CLI mid-session → confirm → old process gone, new one starts.
- No detected CLI → button hidden (unchanged).

### Regression
- macOS/Linux: Local CLI buttons still open an external terminal via
  `launchInteractiveCli`.
- Project compiles and existing `AiDiagnosticPromptBuilderTest` etc. still pass.

## Open questions / assumptions

- Assumes the latest jediterm 3.x + pty4j 0.13.x APIs
  (`PtyProcessBuilder.setConsole`, `PtyProcessTtyConnector`, `JediTermWidget`
  with settable `ttyConnector`). Plan step 1 verifies exact coordinates and API
  against a scratch build.
- 1200 ms inject delay is a starting value; may need tuning per CLI during manual
  testing. Exposed as a `const` for easy change.
- Bundled JetBrains Mono: reuse if the repo already ships it; otherwise Consolas
  is acceptable for v1.
