# AI CLI Terminal — Plain Shell Model — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the per-CLI launch buttons in the AI prompt window with one action that docks a plain interactive PowerShell on the right; the user runs whichever AI CLI they want (typed, or a one-click chip), logs in, and pastes the prompt.

**Architecture:** The embedded terminal (Windows only, JediTerm + pty4j ConPTY) stops running `& <cli>; exit` and just runs `powershell.exe` interactively with a one-line UTF-8 prelude. The `WindowsPtyCliSession` (per-CLI) becomes `WindowsShellSession` (shell-only). All CLI detection, the CLI-switch dialog, and `launchInteractiveCli` are deleted. `AiCliType` shrinks to `{displayName, command}` and is used only to label the quick-launch chips. macOS/Linux get `AiCliDetector.openShellAt(dir)` (a plain terminal at the folder) instead of the per-CLI external launcher.

**Tech Stack:** Kotlin, Compose for Desktop (1.7.3), JediTerm 3.74 + pty4j 0.13.12 (Windows ConPTY), JUnit 5.

## Global Constraints

- Kotlin stays 2.0.21; do not change the Kotlin toolchain or the `-Xskip-metadata-version-check` / `kotlin-stdlib:2.0.21` force in `app/build.gradle.kts`.
- Windows only for the embedded terminal. macOS/Linux keep external-terminal behavior.
- No new runtime dependencies. No new bundled assets.
- Build + full test suite (`./gradlew :app:test`) must pass at the end of every task.
- Korean UI copy: match the terse style already in `EmbeddedTerminalPanel` / `AnalysisWindows`.
- Commit messages end with the two trailer lines already used in this repo's history (`Co-Authored-By:` + `Claude-Session:`).

---

### Task 1: Backend — shell-only session

Rip the per-CLI concept out of the non-Compose layer. The embedded panel and prompt window get **minimal compile-keeping edits only** here; their real rework is Tasks 2–3. After this task all four `▶` buttons open a bare PowerShell (temporary, ugly, but green).

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/util/AiCliDetector.kt` (both the `AiCliType` enum and the `AiCliDetector` object)
- Modify: `app/src/main/kotlin/com/multiviewer/util/PtyCliCommand.kt`
- Rename + modify: `app/src/main/kotlin/com/multiviewer/ui/terminal/WindowsPtyCliSession.kt` → `WindowsShellSession.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/terminal/EmbeddedTerminalPanel.kt` (compile-keeping only)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AnalysisWindows.kt` (compile-keeping only)
- Delete: `app/src/test/kotlin/com/multiviewer/util/AiCliDetectorTest.kt`
- Rename + rewrite: `app/src/test/kotlin/com/multiviewer/ui/terminal/WindowsPtyCliSessionTest.kt` → `WindowsShellSessionTest.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/util/PtyCliCommandTest.kt`
- Create: `app/src/test/kotlin/com/multiviewer/util/AiCliTypeTest.kt`

**Interfaces:**
- Produces:
  - `enum class AiCliType(val displayName: String, val command: String)` — values `CLAUDE("Claude Code","claude")`, `CODEX("Codex","codex")`, `AGY("Antigravity","agy")`, `GEMINI("Gemini CLI","gemini")`. No `binaryName`, no `isAvailable`.
  - `PtyCliCommand.utf8Prelude(): String` — one PowerShell statement chain that forces the console to UTF-8; no CLI invocation, no `exit`.
  - `PtyCliCommand.powershellArgv(): Array<String>` — unchanged.
  - `PtyCliCommand.pastePayload(text: String, bracketed: Boolean): String` — unchanged.
  - `class WindowsShellSession(workingDir: File?, val promptText: String, startProcess: () -> Process = <default>)` with `var state: SessionState` (private set), `val ttyConnector: TtyConnector`, `val isAlive: Boolean`, `fun start()`, `fun destroy()`. No `cli`, no `binPath`, no `displayName`.
  - `AiCliDetector.openShellAt(dir: File?): Boolean` — opens the OS terminal at `dir` (macOS/Linux); returns success.
  - `AiCliDetector.openWebAi(url: String): Boolean` — unchanged.
- Consumes: nothing from other tasks.

- [ ] **Step 1: Edit `PtyCliCommandTest.kt`**

**Do not retype the whole file** — the `pastePayload*` tests contain literal
`` escape characters that must be preserved verbatim. Instead:

1. **Delete** these four test functions in full:
   `launchLineForClaudeInvokesBinaryWithUtf8AndExitsWithCliCode`,
   `launchLineForcesConsoleInputEncodingToUtf8SoPastedPromptIsNotMojibake`,
   `launchLineForAgyAddsInteractiveFlag`,
   `launchLineEscapesSingleQuoteInPath`.
2. **Add** the `utf8PreludeForcesConsoleToUtf8WithNoCliInvocationAndNoExit` test
   (shown below) after `powershellArgvIsANoLogoBypassHost`.
3. Leave `powershellArgvIsANoLogoBypassHost` and every `pastePayload*` test
   exactly as-is.

Resulting file (the `pastePayload` asserts below are shown with `ESC` where the
real file has the `` byte — **keep the existing bytes, don't replace them**):

```kotlin
package com.multiviewer.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PtyCliCommandTest {

    @Test
    fun powershellArgvIsANoLogoBypassHost() {
        val argv = PtyCliCommand.powershellArgv()
        assertEquals("powershell.exe", argv.first())
        assertTrue(argv.contains("-NoLogo"))
        assertTrue(argv.contains("-ExecutionPolicy"))
        assertTrue(argv.contains("Bypass"))
        assertFalse(argv.contains("-NoExit"))
    }

    @Test
    fun utf8PreludeForcesConsoleToUtf8WithNoCliInvocationAndNoExit() {
        val p = PtyCliCommand.utf8Prelude()
        assertTrue(p.contains("chcp 65001"), "sets the console code page")
        assertTrue(p.contains("[Console]::InputEncoding"), "sets console input encoding")
        assertTrue(p.contains("[Console]::OutputEncoding"), "sets console output encoding")
        // A plain interactive shell -- it must not launch a CLI or kill itself.
        assertFalse(p.contains("&"), "no CLI invocation")
        assertFalse(p.contains("exit"), "no exit -- the shell stays alive")
    }

    @Test
    fun pastePayloadBracketedWrapsAndNormalizes() {
        val out = PtyCliCommand.pastePayload("line1\r\nline2\n\n", bracketed = true)
        assertEquals("[200~line1\rline2[201~", out)
    }

    @Test
    fun pastePayloadRawNormalizesWithoutMarkers() {
        val out = PtyCliCommand.pastePayload("a\r\nb\n", bracketed = false)
        assertEquals("a\rb", out)
    }

    @Test
    fun pastePayloadHandlesEmptyString() {
        assertEquals("[200~[201~", PtyCliCommand.pastePayload("", bracketed = true))
        assertEquals("", PtyCliCommand.pastePayload("", bracketed = false))
    }

    @Test
    fun pastePayloadKeepsInternalBlankLinesAsCarriageReturns() {
        assertEquals("a\r\rb", PtyCliCommand.pastePayload("a\n\nb", bracketed = false))
    }

    @Test
    fun pastePayloadStripsStrayPasteEndMarker() {
        val out = PtyCliCommand.pastePayload("before[201~after", bracketed = true)
        assertEquals("[200~beforeafter[201~", out)
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

Run: `./gradlew :app:compileTestKotlin`
Expected: FAIL — `utf8Prelude` unresolved.

- [ ] **Step 3: Replace `launchLine` with `utf8Prelude` in `PtyCliCommand.kt`**

Replace the `launchLine(...)` function (and its KDoc) with:

```kotlin
    /**
     * One PowerShell statement chain written into the PTY right after the shell
     * starts. Forces the console to UTF-8 in both directions — `chcp 65001` plus
     * both `[Console]` encodings (wrapped in `try/catch` in case stdin/stdout
     * isn't a real console) plus `$OutputEncoding` — so Korean text survives
     * rendering AND bracketed-paste, which the console otherwise decodes with the
     * legacy OEM code page (cp949 → mojibake). No CLI is launched and the shell is
     * NOT exited: the user runs whichever AI CLI they want and the `PS>` prompt
     * stays available afterwards.
     */
    fun utf8Prelude(): String =
        "chcp 65001 > \$null; " +
            "try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}; " +
            "try { [Console]::InputEncoding = [System.Text.Encoding]::UTF8 } catch {}; " +
            "\$OutputEncoding = [System.Text.Encoding]::UTF8"
```

Leave `powershellArgv()` and `pastePayload()` unchanged. The file no longer references `AiCliType`.

- [ ] **Step 4: Run — expect the rest of the module to fail to compile**

Run: `./gradlew :app:compileKotlin`
Expected: FAIL — `WindowsPtyCliSession.kt` still calls `PtyCliCommand.launchLine`, and `AiCliDetector` / `AnalysisWindows` still reference `AiCliType.binaryName` / `isAvailable`. Fixed in the following steps.

- [ ] **Step 5: Slim the `AiCliType` enum in `AiCliDetector.kt`**

Replace the enum (lines with `enum class AiCliType` … `}` including the `isAvailable` getter) with:

```kotlin
// Declaration order is the chip order in the AI prompt window. agy before gemini:
// enterprise accounts can't use agy yet, so gemini stays the fallback and both
// need a chip. `command` is what a chip click types into the terminal.
enum class AiCliType(val displayName: String, val command: String) {
    CLAUDE("Claude Code", "claude"),
    CODEX("Codex", "codex"),
    AGY("Antigravity", "agy"),
    GEMINI("Gemini CLI", "gemini"),
}
```

- [ ] **Step 6: Gut the CLI-detection code in `AiCliDetector.kt`**

In the `AiCliDetector` object, **delete**: `candidatePaths`, `WINDOWS_RUNNABLE_EXTS`, `windowsExtraPaths`, `candidateFileNames(...)`, `pickFromLookupOutput(...)`, `findBinary(...)`, and the entire `launchInteractiveCli(...)` function. Keep `isWindows` only if still referenced (it is not after these deletions — delete it too). Keep `openWebAi(...)` exactly as is.

Add, above `openWebAi`:

```kotlin
    /**
     * Opens the OS terminal at [dir] with no CLI (macOS/Linux). Windows uses the
     * embedded shell instead and never calls this. Returns whether a terminal was
     * spawned.
     */
    fun openShellAt(dir: File?): Boolean {
        val path = dir?.takeIf { it.isDirectory }?.absolutePath ?: System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        return try {
            when {
                os.contains("mac") ->
                    ProcessBuilder("open", "-a", "Terminal", path).start().waitFor() == 0
                os.contains("win") ->
                    ProcessBuilder("cmd.exe", "/c", "start", "powershell", "-NoLogo")
                        .directory(File(path)).start().let { true }
                else -> {
                    val terminals = listOf("x-terminal-emulator", "gnome-terminal", "konsole", "xterm")
                    terminals.any { t ->
                        runCatching { ProcessBuilder(t, "--working-directory=$path").start() }.isSuccess
                    }
                }
            }
        } catch (_: Throwable) {
            false
        }
    }
```

Keep the `import java.io.File` / `java.awt.Desktop` / `java.net.URI` imports (still used by `openWebAi` / `openShellAt`).

- [ ] **Step 7: Delete `AiCliDetectorTest.kt`**

```bash
git rm app/src/test/kotlin/com/multiviewer/util/AiCliDetectorTest.kt
```

Its only subjects (`findBinary`, `pickFromLookupOutput`) no longer exist.

- [ ] **Step 8: Create `AiCliTypeTest.kt`**

```kotlin
package com.multiviewer.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AiCliTypeTest {

    @Test
    fun fourClisWithDisplayNamesAndCommands() {
        assertEquals(
            listOf("Claude Code", "Codex", "Antigravity", "Gemini CLI"),
            AiCliType.entries.map { it.displayName },
        )
        assertEquals(
            listOf("claude", "codex", "agy", "gemini"),
            AiCliType.entries.map { it.command },
        )
    }

    @Test
    fun agyIsListedBeforeGemini() {
        val names = AiCliType.entries.map { it.name }
        assertTrue(names.indexOf("AGY") < names.indexOf("GEMINI"), "order was $names")
    }
}
```

- [ ] **Step 9: `git mv` and rewrite `WindowsPtyCliSession.kt` → `WindowsShellSession.kt`**

```bash
git mv app/src/main/kotlin/com/multiviewer/ui/terminal/WindowsPtyCliSession.kt \
       app/src/main/kotlin/com/multiviewer/ui/terminal/WindowsShellSession.kt
```

Replace the file contents with:

```kotlin
package com.multiviewer.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jediterm.terminal.TtyConnector
import com.multiviewer.util.PtyCliCommand
import com.multiviewer.util.ProcessManager
import com.pty4j.PtyProcessBuilder
import java.io.File

internal sealed interface SessionState {
    data object Starting : SessionState
    data object Running : SessionState
    data class Exited(val code: Int) : SessionState
    data class Failed(val reason: String) : SessionState
}

/**
 * Owns one interactive PowerShell running inside a ConPTY. Windows only. One
 * session at a time is enforced by the caller. No CLI is launched — the user runs
 * whichever AI CLI they want in the shell; [promptText] is on the clipboard for
 * them to paste.
 */
internal class WindowsShellSession(
    private val workingDir: File?,
    val promptText: String,
    private val startProcess: () -> Process = {
        val dir = workingDir?.takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))
        PtyProcessBuilder()
            .setCommand(PtyCliCommand.powershellArgv())
            .setDirectory(dir.absolutePath)
            .setEnvironment(HashMap(System.getenv()).apply { put("TERM", "xterm-256color") })
            .setInitialColumns(120)
            .setInitialRows(30)
            .setConsole(false)
            .setUseWinConPty(true)
            .setWindowsAnsiColorEnabled(true)
            .start()
    },
) {
    var state: SessionState by mutableStateOf(SessionState.Starting)
        private set

    private var process: Process? = null
    private var _ttyConnector: TtyConnector? = null

    val ttyConnector: TtyConnector
        get() = checkNotNull(_ttyConnector) { "start() has not created a connector" }

    val isAlive: Boolean get() = process?.isAlive == true

    fun start() {
        try {
            val p = startProcess()
            process = ProcessManager.register(p)
            _ttyConnector = PtyCliTtyConnector(p)
            state = SessionState.Running
            // Off the caller thread (the EDT): the PTY pipe may not be drained yet.
            Thread {
                runCatching { _ttyConnector?.write(PtyCliCommand.utf8Prelude() + "\r") }
            }.apply { isDaemon = true; name = "shell-prelude" }.start()
            Thread {
                try {
                    val code = p.waitFor()
                    ProcessManager.unregister(p)
                    state = SessionState.Exited(code)
                } catch (_: InterruptedException) {
                }
            }.apply { isDaemon = true; name = "shell-watch" }.start()
        } catch (t: Throwable) {
            runCatching { process?.destroyForcibly() }
            process?.let { ProcessManager.unregister(it) }
            state = SessionState.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    fun destroy() {
        process?.let { p ->
            // Kill the CLI the user launched (node.exe etc.) too. pty4j's
            // WinConPtyProcess.destroy() only terminates the PowerShell handle and
            // doesn't override toHandle(), so p.descendants() throws — go via
            // ProcessHandle.of(pid). Guard on isAlive so a recycled PID can't point
            // us at a stranger.
            if (p.isAlive) {
                runCatching {
                    java.lang.ProcessHandle.of(p.pid()).ifPresent { h ->
                        h.descendants().forEach { it.destroyForcibly() }
                    }
                }
            }
            runCatching { p.destroyForcibly() }
            ProcessManager.unregister(p)
        }
        runCatching { _ttyConnector?.close() }
    }
}
```

- [ ] **Step 10: `git mv` and rewrite `WindowsPtyCliSessionTest.kt` → `WindowsShellSessionTest.kt`**

```bash
git mv app/src/test/kotlin/com/multiviewer/ui/terminal/WindowsPtyCliSessionTest.kt \
       app/src/test/kotlin/com/multiviewer/ui/terminal/WindowsShellSessionTest.kt
```

Replace the file contents with:

```kotlin
package com.multiviewer.ui.terminal

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch

class WindowsShellSessionTest {

    /** Minimal fake so tests never touch a native PTY. */
    private class FakeProcess : Process() {
        val out = ByteArrayOutputStream()
        private val exitLatch = CountDownLatch(1)
        @Volatile private var alive = true
        @Volatile private var code = 0
        var destroyed = false; private set

        fun simulateExit(exitCode: Int) { code = exitCode; alive = false; exitLatch.countDown() }

        override fun getOutputStream() = out
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { exitLatch.await(); return code }
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else code
        override fun destroy() { destroyed = true; alive = false; exitLatch.countDown() }
        override fun isAlive(): Boolean = alive
    }

    private fun session(fake: FakeProcess, prompt: String = "diag prompt") = WindowsShellSession(
        workingDir = null,
        promptText = prompt,
        startProcess = { fake },
    )

    private fun await(timeoutMs: Long = 2000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        fail<Unit>("condition never satisfied within ${timeoutMs}ms")
    }

    @Test
    fun startTransitionsToRunningImmediately() {
        val fake = FakeProcess()
        val s = session(fake)
        s.start()
        assertEquals(SessionState.Running, s.state)
        assertTrue(s.isAlive)
        assertEquals("diag prompt", s.promptText)
        s.destroy()
    }

    @Test
    fun startWritesUtf8PreludeToThePtyOffTheCallerThread() {
        val fake = FakeProcess()
        val s = session(fake)
        s.start()
        await { fake.out.toString("UTF-8").contains("chcp 65001") }
        val written = fake.out.toString("UTF-8")
        assertTrue(written.contains("[Console]::InputEncoding"))
        assertTrue(written.endsWith("\r"))
        s.destroy()
    }

    @Test
    fun processExitMovesStateToExitedWithCode() {
        val fake = FakeProcess()
        val s = session(fake)
        s.start()
        fake.simulateExit(3)
        await { s.state == SessionState.Exited(3) }
        assertFalse(s.isAlive)
    }

    @Test
    fun startFailureMovesStateToFailed() {
        val s = WindowsShellSession(
            workingDir = null,
            promptText = "p",
            startProcess = { throw IOException("boom") },
        )
        s.start()
        assertTrue(s.state is SessionState.Failed)
        assertEquals("boom", (s.state as SessionState.Failed).reason)
    }

    @Test
    fun destroyForciblyTerminatesProcess() {
        val fake = FakeProcess()
        val s = session(fake)
        s.start()
        s.destroy()
        assertTrue(fake.destroyed)
    }

    @Test
    fun destroyIsSafeBeforeStartAndWhenRepeated() {
        val fake = FakeProcess()
        val s = session(fake)
        s.destroy()
        s.start()
        s.destroy()
        s.destroy()
        assertTrue(fake.destroyed)
    }
}
```

- [ ] **Step 11: Compile-keeping edits to `EmbeddedTerminalPanel.kt`**

- Change the import `import com.multiviewer.util.AiCliDetector` stays; add nothing.
- Change the composable signature `session: WindowsPtyCliSession` → `session: WindowsShellSession`.
- Line with `Text("● ${session.displayName}", ...)` → `Text("● PowerShell", ...)`.
- In the `ended` restart button, `"↻ ${session.displayName} 다시 시작"` → `"↻ PowerShell 다시 시작"`.
- In the `guidance` `when` block, replace every `${session.displayName}` with `PowerShell` (two occurrences, in the `Exited` and `Failed` branches).
- Leave `BracketedPasteSignal`, `pastePrompt()`, the chip-less header, and the factory unchanged (Task 3 reworks them).

- [ ] **Step 12: Compile-keeping edits to `AnalysisWindows.kt`**

- Import: `import com.multiviewer.ui.terminal.WindowsPtyCliSession` → `import com.multiviewer.ui.terminal.WindowsShellSession`.
- `var activeCliSession by remember { mutableStateOf<WindowsPtyCliSession?>(null) }` → `<WindowsShellSession?>`.
- **Delete** the line `var pendingSwitchCli by remember { mutableStateOf<com.multiviewer.util.AiCliType?>(null) }`.
- Replace `fun startCliSession(cli: ...): String? { ... }` with:

```kotlin
    fun startShellSession(): String? {
        val s = WindowsShellSession(tab.file.parentFile, promptText)
        s.start()
        (s.state as? SessionState.Failed)?.let {
            return "PowerShell을 시작하지 못했습니다: ${it.reason}"
        }
        val hadPanelVisible = activeCliSession != null
        activeCliSession?.destroy()
        activeCliSession = s
        // Only grow the window when the panel first appears — a restart of an
        // already-visible panel must not stack another growth on top.
        if (!hadPanelVisible) {
            val current = windowState.size.width
            val target = (current + terminalWidth + 24.dp).coerceIn(current, maxOf(maxWindowWidth, current))
            windowGrowth = target - current
            windowState.size = windowState.size.copy(width = target)
        }
        return "PowerShell 세션 시작 (프롬프트는 클립보드에 복사됨 — CLI 진입 후 붙여넣기)"
    }
```

- `endCliSession()` stays unchanged.
- In the bottom action bar, change the `cliButtons` remember to `val cliButtons = remember { com.multiviewer.util.AiCliType.entries.toList() }` and change the `cliButtons.forEach { (cli, detected) -> ... }` to `cliButtons.forEach { cli -> ... }`; inside, replace the whole `onClick` body with:

```kotlin
                                            onClick = {
                                                ClipboardUtil.copyToClipboard(promptText)
                                                statusMessage = if (isWindows) {
                                                    startShellSession()
                                                } else if (com.multiviewer.util.AiCliDetector.openShellAt(tab.file.parentFile)) {
                                                    "터미널을 열었습니다 (프롬프트 클립보드 복사 완료)"
                                                } else {
                                                    "터미널 실행 실패"
                                                }
                                            },
```

and drop the `detected`-based `accent` styling (use `val accent = AppColors.NeonPurple` and remove the `(미검출)` suffix / alpha branches).

- In the `key(activeCliSession)` block, **delete** `val restartCli = activeCliSession!!.cli` and change `onRestart` to:

```kotlin
                                onRestart = {
                                    ClipboardUtil.copyToClipboard(promptText)
                                    statusMessage = startShellSession()
                                },
```

- **Delete** the entire `pendingSwitchCli?.let { next -> CliConfirmDialog(...) }` block (the "세션 전환" dialog). Keep the `if (confirmCloseWhileRunning) { CliConfirmDialog(...) }` block.

- [ ] **Step 13: Run the build and full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`. New `AiCliTypeTest` (2), rewritten `PtyCliCommandTest` (7), `WindowsShellSessionTest` (6) all pass; `AiCliDetectorTest` gone.

- [ ] **Step 14: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor: WindowsPtyCliSession -> WindowsShellSession (no per-CLI launch)

The embedded terminal now runs a plain interactive PowerShell instead of
`& <cli>; exit`. AiCliType shrinks to {displayName, command}; CLI detection
(findBinary, windowsExtraPaths), launchInteractiveCli, and the CLI-switch
dialog are deleted. AiCliDetector.openShellAt opens a plain terminal on
macOS/Linux. The prompt-window buttons are collapsed to a temporary "open a
shell" action pending the Task 2 UI rework.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo
EOF
)"
```

---

### Task 2: `AiPromptPreviewWindow` — one "터미널 열기" button

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AnalysisWindows.kt` (bottom action bar, ~lines 1058–1115 after Task 1)

**Interfaces:**
- Consumes: `startShellSession()`, `AiCliDetector.openShellAt(dir)`, `activeCliSession: WindowsShellSession?` (Task 1).
- Produces: nothing for later tasks.

- [ ] **Step 1: Replace the CLI-button row with a single button**

In the bottom action bar, replace the whole "Local CLI Buttons" inner `Row(modifier = Modifier.weight(1f).horizontalScroll(...))` block (the `Text("Local CLI:")` + `cliButtons.forEach { ... }`) with:

```kotlin
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Button(
                                        onClick = {
                                            ClipboardUtil.copyToClipboard(promptText)
                                            statusMessage = when {
                                                isWindows && activeCliSession != null ->
                                                    "터미널이 이미 열려 있습니다"
                                                isWindows -> startShellSession()
                                                com.multiviewer.util.AiCliDetector.openShellAt(tab.file.parentFile) ->
                                                    "터미널을 열었습니다 (프롬프트 클립보드 복사 완료)"
                                                else -> "터미널 실행 실패"
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = AppColors.NeonPurple.copy(alpha = 0.2f),
                                            contentColor = AppColors.NeonPurple,
                                        ),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.NeonPurple),
                                        modifier = Modifier.height(30.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        shape = RoundedCornerShape(4.dp),
                                    ) {
                                        Text("▶ AI CLI 터미널 열기", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
```

- [ ] **Step 2: Delete the now-unused `cliButtons` line**

Remove `val cliButtons = remember { com.multiviewer.util.AiCliType.entries.toList() }` from the bottom action bar (it is no longer referenced here — `AiCliType` is now used only by the chips in `EmbeddedTerminalPanel`).

- [ ] **Step 3: Remove the now-unused `horizontalScroll` import if nothing else uses it**

Run: `grep -n "horizontalScroll" app/src/main/kotlin/com/multiviewer/ui/AnalysisWindows.kt`
If the only hit is the `import` line, delete `import androidx.compose.foundation.horizontalScroll`. If `rememberScrollState` is still used elsewhere (it is — the code-viewer `vScroll`/`hScroll`), keep its import.

- [ ] **Step 4: Build and run**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Launch the app and eyeball the window**

Run: `./gradlew :app:run` (macOS dev box — the button shows and, on non-Windows, `openShellAt` opens Terminal.app at the file's folder; the embedded panel is Windows-only so it won't dock here).
Expected: the AI prompt window shows a single `▶ AI CLI 터미널 열기` button where the four `▶ <CLI>` buttons were; `Copy prompt` / `Close` unchanged. Close the app.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AnalysisWindows.kt
git commit -m "$(cat <<'EOF'
feat: single "AI CLI 터미널 열기" button in the AI prompt window

Replaces the four per-CLI buttons. On Windows it docks the embedded shell;
on macOS/Linux it opens the OS terminal at the media file's folder. The
prompt is copied to the clipboard either way.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo
EOF
)"
```

---

### Task 3: `EmbeddedTerminalPanel` — quick-launch chips + prompt-paste gating

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/terminal/EmbeddedTerminalPanel.kt`
- Create: `app/src/test/kotlin/com/multiviewer/ui/terminal/BracketedPasteSignalTest.kt`

**Interfaces:**
- Consumes: `WindowsShellSession` (Task 1), `AiCliType` (Task 1).
- Produces: nothing for later tasks.

- [ ] **Step 1: Write `BracketedPasteSignalTest.kt`**

```kotlin
package com.multiviewer.ui.terminal

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BracketedPasteSignalTest {

    @Test
    fun tracksCurrentStateSoPasteDisablesWhenBackAtTheBareShellPrompt() {
        val sig = BracketedPasteSignal()
        assertFalse(sig.isReady)

        sig.onBracketedPasteMode(true)   // entered a CLI's readline prompt
        assertTrue(sig.isReady)

        sig.onBracketedPasteMode(false)  // /exit -> back at PS>
        assertFalse(sig.isReady)

        sig.onBracketedPasteMode(true)   // ran another CLI
        assertTrue(sig.isReady)
    }
}
```

- [ ] **Step 2: Run — expect failure**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.terminal.BracketedPasteSignalTest"`
Expected: FAIL — after `onBracketedPasteMode(false)` the current sticky impl still reports `isReady == true`.

- [ ] **Step 3: Make `BracketedPasteSignal` track the current state**

In `EmbeddedTerminalPanel.kt`, change:

```kotlin
    fun onBracketedPasteMode(enabled: Boolean) { if (enabled) isReady = true }
```

to:

```kotlin
    fun onBracketedPasteMode(enabled: Boolean) { isReady = enabled }
```

and update the class KDoc comment above `isReady` to:

```kotlin
    // Follows the running program's current bracketed-paste mode. A readline/Ink
    // CLI turns it on at its prompt and off on exit, so this is true exactly while
    // a paste would land in a CLI rather than the bare `PS>` prompt. Ink apps
    // don't toggle it on redraw, so no flicker.
```

- [ ] **Step 4: Run — expect pass**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.terminal.BracketedPasteSignalTest"`
Expected: PASS.

- [ ] **Step 5: Add a `sendCommand` helper and the chip row**

In `EmbeddedTerminalPanel`, next to `fun pastePrompt()`, add:

```kotlin
    // Chip click: type "<cmd>" and submit it at the shell. `true` = user typing.
    fun sendCommand(cmd: String) {
        if (state != SessionState.Running) return
        val starter = widget?.terminalStarter ?: return
        starter.sendString("$cmd\r", true)
    }
```

Then, immediately after the header `Row { ... }` closes and before the `guidance` `when` block, insert:

```kotlin
        if (state == SessionState.Running) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.multiviewer.util.AiCliType.entries.forEach { cli ->
                    TextButton(
                        onClick = { sendCommand(cli.command) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(22.dp),
                    ) {
                        Text(cli.displayName, fontSize = 10.sp, color = AppColors.NeonBlue, maxLines = 1)
                    }
                }
                Text(
                    "또는 원하는 명령을 직접 입력",
                    fontSize = 10.sp,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                )
            }
        }
```

- [ ] **Step 6: Reword the guidance and the paste button for the shell model**

Replace the `guidance` `when` block with:

```kotlin
        val guidance: String? = when (val s = state) {
            SessionState.Starting -> "PowerShell을 여는 중입니다…"
            SessionState.Running ->
                "① 위 칩을 누르거나 직접 명령을 입력해 AI CLI를 실행하세요. " +
                    "② 로그인이 필요하면 진행하세요 (브라우저가 안 열리면 출력된 URL 클릭). " +
                    "③ CLI 프롬프트에서 '프롬프트 붙여넣기' 후 Enter."
            is SessionState.Exited ->
                "PowerShell이 종료되었습니다 (exit ${s.code}). " +
                    "'↻ PowerShell 다시 시작'을 누르면 새 셸이 열리고 프롬프트가 다시 클립보드에 복사됩니다."
            is SessionState.Failed ->
                "PowerShell을 시작하지 못했습니다: ${s.reason}. '↻ PowerShell 다시 시작'으로 재시도하세요."
        }
```

Change the not-ready paste button label from `"붙여넣기(준비 중…)"` to `"붙여넣기(CLI 진입 후)"`.

- [ ] **Step 7: Build and full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`. `BracketedPasteSignalTest` (1) plus all existing terminal tests pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/terminal/EmbeddedTerminalPanel.kt \
        app/src/test/kotlin/com/multiviewer/ui/terminal/BracketedPasteSignalTest.kt
git commit -m "$(cat <<'EOF'
feat: quick-launch chips + shell-model guidance in the embedded terminal

Chip row (claude / codex / agy / gemini) types-and-runs the command; direct
typing still works. BracketedPasteSignal now follows the current bracketed-
paste state so "프롬프트 붙여넣기" is enabled only inside a CLI, not at the bare
PS prompt. Guidance and labels reworded for the plain-shell model.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_013FCbWJi4EtnSbNjb27Rvyo
EOF
)"
```

---

## Self-Review

**1. Spec coverage**

| Spec item | Task |
|---|---|
| Single `▶ AI CLI 터미널 열기` button | Task 2 Step 1 |
| Windows: dock plain PowerShell, grow width, copy prompt | Task 1 Step 12 (`startShellSession`), Task 2 Step 1 |
| macOS/Linux: `openShellAt` + clipboard | Task 1 Step 6, Task 2 Step 1 |
| Chip row types-and-runs command | Task 3 Step 5 |
| Direct typing always available | inherent (real terminal); guidance says so (Task 3 Step 5/6) |
| Long-lived shell (`/exit` → `PS>`, still `Running`) | Task 1 Step 3 (`utf8Prelude` has no `exit`), Step 9 |
| `Exited` only on `exit` / `세션 종료` / window close | Task 1 Step 9 (watcher), existing `endCliSession` / `confirmCloseWhileRunning` |
| Remove `pendingSwitchCli` + switch dialog | Task 1 Step 12 |
| `WindowsPtyCliSession` → `WindowsShellSession` | Task 1 Steps 9–10 |
| `PtyCliCommand.launchLine` → `utf8Prelude()` | Task 1 Step 3 |
| Delete `findBinary`/detection/`launchInteractiveCli` | Task 1 Step 6 |
| `AiCliType` reduced to `{displayName, command}`, kept | Task 1 Step 5 |
| `BracketedPasteSignal` current-state tracking | Task 3 Steps 1–3 |
| Header `● PowerShell` | Task 1 Step 11 |
| Guidance reworded per state | Task 3 Step 6 |
| Retained: `UrlHyperlinkFilter`, `pickCliTerminalFont`, dark bg, `forceActionOnMouseReporting`, `chcp 65001`, right-dock+splitter+growth, `sanitize*`, close-confirm dialog | untouched by all tasks (verified: no step edits them) |
| Edge: chip/paste while `Exited` | Task 3 Step 5 (`if (state != Running) return`), Step 3 (paste gated on `isReady`, false when not Running) |
| Edge: `powershell.exe` missing → `Failed` | Task 1 Step 9 (catch → `Failed`), Step 12 (`startShellSession` surfaces it) |
| Edge: paste at bare `PS>` prevented | Task 3 Step 3 |
| Tests: delete 3 `launchLine*`, add `utf8Prelude` test | Task 1 Step 1 |
| Tests: rename session test, drop `cli` | Task 1 Step 10 |
| Tests: delete `AiCliDetectorTest` | Task 1 Step 7 |
| Tests: new `AiCliTypeTest` | Task 1 Step 8 |
| `CliTerminalSettingsTest` unchanged | not touched |

**2. Placeholder scan** — no "TBD"/"handle edge cases"/"similar to". Every code step shows full code. Task 1 Step 12 gives exact replacement code for `startShellSession` and the button `onClick`.

**3. Type consistency**

- `WindowsShellSession(workingDir: File?, promptText: String, startProcess: () -> Process = ...)` — same signature in Step 9 (source), Step 10 (test), Step 12 (`startShellSession` call: `WindowsShellSession(tab.file.parentFile, promptText)`). ✓
- `PtyCliCommand.utf8Prelude(): String` — defined Step 3, consumed Step 9 (`utf8Prelude() + "\r"`) and tested Step 1. ✓
- `AiCliType.command` — defined Step 5, consumed Task 3 Step 5 (`cli.command`) and tested Step 8. `displayName` used in Task 3 Step 5. ✓
- `AiCliDetector.openShellAt(dir: File?): Boolean` — defined Step 6, consumed Task 1 Step 12 and Task 2 Step 1. ✓
- `BracketedPasteSignal.onBracketedPasteMode(enabled: Boolean)` / `isReady` — signature unchanged (Task 3 only changes the body). Caller `ReadySignalTerminalPanel.setBracketedPasteMode` unchanged. ✓
- `EmbeddedTerminalPanel(session: WindowsShellSession, onEndSession, onRestart, modifier)` — signature unchanged from current (only the `session` type narrows); call site in `AnalysisWindows` `key(activeCliSession)` block still passes `session =`, `onEndSession =`, `onRestart =`, `modifier =`. ✓
- `SessionState.Failed.reason` — used in Task 1 Step 12 (`(s.state as? SessionState.Failed)?.let { it.reason }`) and Task 3 Step 6 (`s.reason` inside `is SessionState.Failed ->`). ✓
