# AI CLI Terminal — Plain Shell Model — Design

Date: 2026-09-06
Status: Approved for planning
Scope: Windows only. macOS/Linux keep the existing external-terminal behavior.

## Problem

The embedded AI CLI terminal (`2026-09-05-embedded-ai-cli-terminal-design.md`)
launches a **specific** CLI for the user: each `▶ <CLI>` button spawns
`powershell.exe` and immediately runs `& <cli>; exit`. In practice the app ends
up owning a workflow it can't control:

- CLI detection is unreliable on Windows (user-level npm dirs missing from the
  JVM's PATH), so buttons vanish or need special-casing.
- Each CLI has its own first-run login / onboarding TUI. The app can't tell
  "ready for a prompt" from "showing a login menu", which broke Claude's
  browser-login flow and fragmented Codex's paste.
- `& <cli>; exit` kills the shell the moment the CLI exits (or self-updates),
  leaving the user at a dead panel wondering what to do.
- Enterprise accounts can't use `agy` yet and must fall back to `gemini`, so the
  "only show detected CLIs" model fights the user.

Benchmarking comparable tools (VS Code / Cursor integrated terminal, JetBrains,
k9s `s`, lazygit custom commands, `gh copilot`): almost none auto-launch a
third-party interactive tool with its own auth. They provide a plain shell in the
right context and let the user drive; context handoff is via clipboard or `@`
references.

## Goals

- On Windows, one action — **`▶ AI CLI 터미널 열기`** — docks a plain interactive
  PowerShell on the right of the AI prompt window.
- The user runs whichever AI CLI they want by typing it (or a one-click chip),
  logs in themselves, and pastes the diagnostic prompt with the existing
  **`프롬프트 붙여넣기`** button once inside the CLI.
- The shell is long-lived: exiting a CLI (`/exit`) returns to `PS>`, and the user
  can start another CLI in the same session.
- Remove all per-CLI launch, detection, and switch machinery.

## Non-Goals

- Changing macOS/Linux behavior (they keep `AiCliDetector.launchInteractiveCli`,
  now shell-only — see below).
- Auto-injecting the prompt (already removed).
- Multi-tab / concurrent shell sessions.
- A general-purpose terminal outside the AI prompt window.
- User-configurable launch commands (chips are a fixed list).

## Interaction Model

**Bottom action bar** of `AiPromptPreviewWindow`:

- The four `▶ <CLI>` buttons are replaced by a single **`▶ AI CLI 터미널 열기`**
  button.
- On Windows: docks the terminal panel on the right, grows the window width,
  copies the prompt to the clipboard.
- On macOS/Linux: opens the OS terminal at the media file's folder (no CLI) and
  copies the prompt to the clipboard.

**Embedded terminal panel** (right dock, Windows only):

- Header row: `● PowerShell` · status · `프롬프트 붙여넣기` · `세션 종료`
  (when `Exited`/`Failed`: `↻ PowerShell 다시 시작` · `닫기`).
- Chip row: `[claude] [codex] [agy] [gemini]` + grey hint
  "또는 원하는 명령을 직접 입력".
  - Chip click → `terminalStarter.sendString("<command>\r", true)` — types the
    command **and submits it** (runs immediately). The user can still type
    anything directly; chips are just shortcuts for the common case.
- Guidance line per state (reuse existing `guidance` when-block, reworded for the
  shell model).
- Below: the JediTerm `SwingPanel`.

**Shell lifecycle:**

- `▶ AI CLI 터미널 열기` starts an interactive `powershell.exe` (no `-Command`),
  cwd = media file's folder, with a one-line UTF-8 prelude injected
  (`chcp 65001; [Console]::OutputEncoding = [Console]::InputEncoding = UTF8;
  $OutputEncoding = UTF8`).
- Running a CLI and exiting it (`/exit`) returns to `PS>`; the panel stays
  `Running` (the PowerShell process is alive).
- `Exited` happens only when the user types `exit` in PowerShell or clicks
  `세션 종료` / closes the window.

## Component Changes

### `WindowsPtyCliSession` → `WindowsShellSession`

- Rename. Drop `cli: AiCliType` and `binPath` params; keep `workingDir`,
  `promptText`.
- Remove the `_ttyConnector?.write(PtyCliCommand.launchLine(...) + "\r")` launch
  thread. Replace with a single write of the UTF-8 prelude line.
- `state`, `process`, `ttyConnector`, `isAlive`, `destroy()` (descendant kill)
  unchanged. Exit watcher thread unchanged.

### `PtyCliCommand`

- Delete `launchLine(cli, binPath)`.
- Add `fun utf8Prelude(): String` returning the `chcp 65001; ...UTF8...` line
  (extracted from the current `launchLine` prelude), **without** the trailing
  `; <invoke>; exit ...`.
- Keep `powershellArgv()` and `pastePayload()` unchanged.

### `EmbeddedTerminalPanel`

- `session: WindowsShellSession`. Header label `● PowerShell` instead of
  `● ${session.displayName}`.
- Add a chip row: `AiCliType.entries.forEach { cli -> Chip(cli.displayName) { sendCommand(cli.command) } }`
  where `sendCommand(c)` = `widget?.terminalStarter?.sendString("$c\r", true)`,
  guarded on `state == Running` (no-op otherwise).
- `pastePrompt()` unchanged (still gated on `readySignal.isReady`, still no
  trailing CR).
- `BracketedPasteSignal`: change `onBracketedPasteMode(enabled)` to track the
  **current** state — `isReady = enabled` — not sticky-true. So `프롬프트
  붙여넣기` is enabled only while a CLI has bracketed-paste on, and disables when
  the user returns to the bare `PS>` prompt. (Ink apps don't toggle the mode on
  redraw, so no flicker.)
- `onRestart` label/guidance reworded ("PowerShell 다시 시작").

### `AnalysisWindows` (`AiPromptPreviewWindow`)

- Replace the `cliButtons` list + 4-button render with one
  `▶ AI CLI 터미널 열기` button.
  - Windows: `statusMessage = startShellSession()` (or `pending... ` — see next).
  - Non-Windows: `AiCliDetector.openShellAt(tab.file.parentFile)` +
    `ClipboardUtil.copyToClipboard(promptText)`.
- Remove `pendingSwitchCli` state and the CLI-switch `CliConfirmDialog`. There is
  no "switch" concept — one shell, user-driven. On Windows, if the terminal is
  already open the button is a no-op with a `statusMessage` ("터미널이 이미
  열려 있습니다").
- Keep `confirmCloseWhileRunning` dialog (close-with-live-session).
- Rename `startCliSession(cli)` → `startShellSession()`; drop the `cli` param and
  the `findBinary` lookup. `onRestart` calls `startShellSession()`.
- `terminalWidth` / `maxWindowWidth` / `windowGrowth` / `hadPanelVisible` growth
  logic unchanged.

### `AiCliType` (kept, reduced)

```kotlin
enum class AiCliType(val displayName: String, val command: String) {
    CLAUDE("Claude Code", "claude"),
    CODEX("Codex", "codex"),
    AGY("Antigravity", "agy"),
    GEMINI("Gemini CLI", "gemini"),
}
```

- Drop `binaryName`, `isAvailable`.
- Used only for chip labels + the command string sent on chip click.

### `AiCliDetector`

After this change, the only remaining callers of the CLI machinery are gone
(`AiCliType.isAvailable`, `AiPromptPreviewWindow.startCliSession`,
`launchInteractiveCli`). Delete, with their tests:

- `findBinary`, `pickFromLookupOutput`, `candidateFileNames`,
  `WINDOWS_RUNNABLE_EXTS`, `candidatePaths`, `windowsExtraPaths`
- `launchInteractiveCli` and its `runner.ps1` / `$PROMPT` generation
- `AiCliDetectorTest` in full

Add `openShellAt(dir: File?): Boolean` — opens the OS terminal at `dir` with **no
CLI**, reusing `launchInteractiveCli`'s terminal-discovery mechanics minus the
prompt/CLI parts:

- macOS: `open -a Terminal <dir>`.
- Linux: existing `x-terminal-emulator` / `gnome-terminal` / `konsole`
  discovery with `--working-directory=<dir>`.
- Windows: not used by this path (Windows uses the embedded shell).

`openWebAi` and the Web AI (Chrome) buttons / URL-edit UI are entirely unchanged.

## Retained (no change)

`UrlHyperlinkFilter`, `pickCliTerminalFont`, dark terminal background,
`forceActionOnMouseReporting`, `chcp 65001` encoding, right-dock + vertical
splitter + width growth, `ClipboardUtil.sanitizeForClipboard`,
`AiDiagnosticPromptBuilder.sanitizePrompt`, the "close with live session"
confirm dialog.

## Edge Cases

- **Chip click / paste while `Exited`** — no-op; guidance points to
  `↻ PowerShell 다시 시작`.
- **`powershell.exe` not found** — `Failed` state + guidance ("PowerShell을
  시작하지 못했습니다").
- **Chip click while already inside a CLI** — `<command>\r` goes to that CLI as
  input (user's responsibility; the hint explains chips run a command at the
  shell). Acceptable.
- **Paste at bare `PS>`** — prevented: `프롬프트 붙여넣기` is disabled when
  bracketed-paste mode is off.
- **Rapid chip clicks** — `sendString` is serialized on JediTerm's writer
  thread; safe.
- **CLI self-updates and exits** (the Codex case) — shell returns to `PS>`,
  panel still `Running`; user re-runs via chip. No dead panel.

## Testing

- `PtyCliCommandTest`: delete the three `launchLine*` tests; add one asserting
  `utf8Prelude()` contains `chcp 65001` and `[Console]::InputEncoding` and does
  **not** contain `exit`.
- `WindowsPtyCliSessionTest` → `WindowsShellSessionTest`: drop the `cli` arg;
  keep start → `Running`, process-exit → `Exited`, `destroy()` kills the fake
  process + descendants.
- `AiCliDetectorTest`: deleted in full (all its subjects — `findBinary`,
  `pickFromLookupOutput` — are removed).
- New `AiCliTypeTest`: 4 values, `command` strings (`claude`/`codex`/`agy`/
  `gemini`), order (agy before gemini).
- `CliTerminalSettingsTest` unchanged (font, URL filter).
- Full suite green.

## Rollback

Single-feature, no schema or persisted state. Revert the commit(s); the previous
per-CLI-button model returns. No user data migration.
