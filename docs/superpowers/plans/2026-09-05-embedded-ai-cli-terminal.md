# Embedded AI CLI Terminal (Windows) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On Windows, run the selected AI CLI (Claude Code / Codex / Gemini CLI / Antigravity) inside an embedded VT100 terminal at the bottom of the "AI Diagnostic Prompt" popup instead of spawning a separate PowerShell window.

**Architecture:** Add `jediterm` (terminal emulator) + `pty4j` (ConPTY pseudo-terminal) as build dependencies. A `WindowsPtyCliSession` owns one `powershell.exe` process behind a PTY and runs the CLI inside it. An `EmbeddedTerminalPanel` Compose component renders a `JediTermWidget` via `SwingPanel` and routes keyboard to the PTY. `AiPromptPreviewWindow` gets a resizable bottom panel, window grow/shrink, and confirm dialogs. macOS/Linux keep the existing `AiCliDetector.launchInteractiveCli` external-terminal path unchanged.

**Tech Stack:** Kotlin 2.0.21, Compose Multiplatform 1.7.3 (Desktop), JediTerm 3.74, pty4j 0.13.12, JUnit 5.

## Global Constraints

- Windows only for the embedded terminal. macOS/Linux MUST keep calling `AiCliDetector.launchInteractiveCli` — do not remove or change that function's behavior.
- No new end-user install requirements. All new libraries ship inside the app bundle; ConPTY is built into Windows 10 1809+ with bundled winpty fallback.
- New Maven repository required: `https://cache-redirector.jetbrains.com/intellij-dependencies` (JediTerm is not on Maven Central). Add it to `settings.gradle.kts` `dependencyResolutionManagement.repositories` (the project uses `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, so per-project repos are rejected).
- Dependency versions are pinned exactly in `gradle/libs.versions.toml`: `jediterm = "3.74"`, `pty4j = "0.13.12"`.
- Prompt is always copied to the clipboard before launch (existing behavior, kept as a fallback for when auto-injection misses).
- Follow existing code style in `AnalysisWindows.kt`: fully-qualified `com.multiviewer.util.X` references are used in that file for util classes; Korean UI strings; `AppColors` / `AppTypography` for styling.
- On any failure starting the embedded session, fall back to `AiCliDetector.launchInteractiveCli` and set a status message.

---

## File Structure

- `gradle/libs.versions.toml` — add `jediterm`, `pty4j` versions + library aliases.
- `settings.gradle.kts` — add the JetBrains intellij-dependencies Maven repo.
- `app/build.gradle.kts` — add 3 `implementation` deps.
- `app/src/main/kotlin/com/multiviewer/util/PtyCliCommand.kt` — **new**, pure functions: PowerShell argv, CLI launch line, bracketed-paste encoding.
- `app/src/test/kotlin/com/multiviewer/util/PtyCliCommandTest.kt` — **new**.
- `app/src/main/kotlin/com/multiviewer/ui/terminal/PtyCliTtyConnector.kt` — **new**, `ProcessTtyConnector` subclass wrapping a `Process` / pty4j `PtyProcess`.
- `app/src/main/kotlin/com/multiviewer/ui/terminal/WindowsPtyCliSession.kt` — **new**, session lifecycle + state (`SessionState` sealed interface lives here).
- `app/src/test/kotlin/com/multiviewer/ui/terminal/WindowsPtyCliSessionTest.kt` — **new**.
- `app/src/main/kotlin/com/multiviewer/ui/terminal/EmbeddedTerminalPanel.kt` — **new**, Compose component (`SwingPanel` + `JediTermWidget` + header + prompt-inject timing).
- `app/src/main/kotlin/com/multiviewer/ui/AnalysisWindows.kt` — **modify** `AiPromptPreviewWindow` (imports, state, `startCliSession`/`endCliSession`, CLI button onClick, layout, divider, dialogs, close handling).

No changes to `packaging/windows/installer.iss` — it bundles the whole `createDistributable` output directory, so new jars are included automatically.

---

## Task 1: Add dependencies and repository

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `settings.gradle.kts:15-21` (the `dependencyResolutionManagement { repositories { ... } }` block)
- Modify: `app/build.gradle.kts:13-18` (the `dependencies { }` block)

**Interfaces:**
- Consumes: nothing.
- Produces: classpath access to `com.jediterm.terminal.ui.JediTermWidget`, `com.jediterm.terminal.ProcessTtyConnector`, `com.jediterm.terminal.TtyConnector`, `com.jediterm.terminal.ui.settings.DefaultSettingsProvider`, `com.jediterm.core.util.TermSize`, `com.pty4j.PtyProcess`, `com.pty4j.PtyProcessBuilder`, `com.pty4j.WinSize`.

- [ ] **Step 1: Add versions and library aliases to `gradle/libs.versions.toml`**

Full file after edit:

```toml
[versions]
kotlin = "2.0.21"
composeMultiplatform = "1.7.3"
jediterm = "3.74"
pty4j = "0.13.12"

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }

[libraries]
jediterm-core = { module = "org.jetbrains.jediterm:jediterm-core", version.ref = "jediterm" }
jediterm-ui = { module = "org.jetbrains.jediterm:jediterm-ui", version.ref = "jediterm" }
pty4j = { module = "org.jetbrains.pty4j:pty4j", version.ref = "pty4j" }
```

- [ ] **Step 2: Add the JetBrains repo to `settings.gradle.kts`**

Replace the `dependencyResolutionManagement` block with:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }
}
```

- [ ] **Step 3: Add dependencies to `app/build.gradle.kts`**

Replace the `dependencies { }` block with:

```kotlin
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.jediterm.core)
    implementation(libs.jediterm.ui)
    implementation(libs.pty4j)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// jediterm-core's POM spuriously pulls kotlin-stdlib 2.4.0 (it is pure Java and
// never uses the stdlib); pin it to the toolchain version so the 2.0.21 compiler
// can read it.
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    }
}

// jediterm-core / jediterm-ui 3.74 also ship a stray META-INF/*.kotlin_module stamped
// with Kotlin metadata version 2.4.0 (jars are pure Java otherwise). The 2.0.21 compiler
// rejects the newer metadata on the classpath scan regardless of the stdlib version, so
// skip the check — safe because these jars contain no real Kotlin classes.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}
```

- [ ] **Step 4: Verify dependency resolution**

Run: `./gradlew :app:dependencies --configuration runtimeClasspath`
Expected: output lists `org.jetbrains.jediterm:jediterm-core:3.74`, `org.jetbrains.jediterm:jediterm-ui:3.74`, `org.jetbrains.pty4j:pty4j:0.13.12`, and transitively `net.java.dev.jna:jna:5.14.0` + `net.java.dev.jna:jna-platform:5.14.0`. No `FAILED` markers. `kotlin-stdlib` must show `-> 2.0.21` (forced, see Step 3).

**Required (not optional):** `jediterm-core:3.74`'s POM spuriously declares `kotlin-stdlib:2.4.0` as a `compile` dependency. jediterm-core, jediterm-ui and pty4j are all pure Java (verified: zero Kotlin-compiled classes, zero `kotlin/` bytecode references) so they never touch the stdlib — but if Gradle resolves `kotlin-stdlib` to `2.4.0` the Kotlin 2.0.21 compiler fails with *"Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.4.0, expected version is 2.0.0"*. The Step 3 `resolutionStrategy` force pins the stdlib back down, and the Step 3 `-Xskip-metadata-version-check` compiler arg handles a second layer: the jediterm jars themselves carry a stray `META-INF/*.kotlin_module` stamped 2.4.0 that the compiler rejects on classpath scan regardless of stdlib version. Both are needed. Do NOT change `kotlin = "2.0.21"` in `libs.versions.toml` — Compose Multiplatform 1.7.3 is version-locked to Kotlin 2.0.x and a toolchain upgrade is out of scope for this feature.

- [ ] **Step 5: Verify the project still compiles**

Run: `./gradlew :app:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml settings.gradle.kts app/build.gradle.kts
git commit -m "build: add jediterm + pty4j deps for embedded AI CLI terminal"
```

---

## Task 2: `PtyCliCommand` pure functions

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/util/PtyCliCommand.kt`
- Test: `app/src/test/kotlin/com/multiviewer/util/PtyCliCommandTest.kt`

**Interfaces:**
- Consumes: `com.multiviewer.util.AiCliType` (existing enum, values `CLAUDE`, `CODEX`, `GEMINI`, `AGY`; property `displayName`).
- Produces:
  - `PtyCliCommand.powershellArgv(): Array<String>`
  - `PtyCliCommand.launchLine(cli: AiCliType, binPath: String): String`
  - `PtyCliCommand.bracketedPaste(text: String): String`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/multiviewer/util/PtyCliCommandTest.kt`:

```kotlin
package com.multiviewer.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PtyCliCommandTest {

    @Test
    fun powershellArgvStartsInteractiveNonExitingShell() {
        val argv = PtyCliCommand.powershellArgv()
        assertEquals("powershell.exe", argv.first())
        assertTrue(argv.contains("-NoExit"))
        assertTrue(argv.contains("-NoLogo"))
        assertTrue(argv.contains("-ExecutionPolicy"))
        assertTrue(argv.contains("Bypass"))
    }

    @Test
    fun launchLineForClaudeInvokesBinaryWithUtf8() {
        val line = PtyCliCommand.launchLine(AiCliType.CLAUDE, "C:\\tools\\claude.cmd")
        assertTrue(line.contains("OutputEncoding"))
        assertTrue(line.contains("& \"C:\\tools\\claude.cmd\""))
        assertFalse(line.contains(" -i"))
    }

    @Test
    fun launchLineForAgyAddsInteractiveFlag() {
        val line = PtyCliCommand.launchLine(AiCliType.AGY, "C:\\tools\\agy.cmd")
        assertTrue(line.contains("& \"C:\\tools\\agy.cmd\" -i"))
    }

    @Test
    fun bracketedPasteWrapsWithMarkersAndSubmits() {
        val out = PtyCliCommand.bracketedPaste("hello")
        assertEquals("\u001B[200~hello\u001B[201~\r", out)
    }

    @Test
    fun bracketedPasteNormalizesCrlfAndTrimsTrailingNewlines() {
        val out = PtyCliCommand.bracketedPaste("a\r\nb\r\n\n")
        assertEquals("\u001B[200~a\nb\u001B[201~\r", out)
    }

    @Test
    fun bracketedPasteHandlesEmptyString() {
        assertEquals("\u001B[200~\u001B[201~\r", PtyCliCommand.bracketedPaste(""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "com.multiviewer.util.PtyCliCommandTest"`
Expected: FAIL — `PtyCliCommand` unresolved / does not compile.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/util/PtyCliCommand.kt`:

```kotlin
package com.multiviewer.util

/**
 * Pure string builders for driving an AI CLI inside a PowerShell PTY session.
 * No I/O, no process launching — kept separate so it is unit-testable.
 */
object PtyCliCommand {

    /** Interactive, non-exiting PowerShell host used as the PTY shell. */
    fun powershellArgv(): Array<String> = arrayOf(
        "powershell.exe", "-NoLogo", "-NoExit", "-ExecutionPolicy", "Bypass",
    )

    /**
     * The PowerShell line written into the PTY to start the CLI. Sets UTF-8
     * output first so multibyte prompt text renders correctly.
     */
    fun launchLine(cli: AiCliType, binPath: String): String {
        val invoke = when (cli) {
            AiCliType.AGY -> "& \"$binPath\" -i"
            else -> "& \"$binPath\""
        }
        return "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; $invoke"
    }

    /**
     * Wraps [text] as an xterm bracketed-paste burst followed by a carriage
     * return so the CLI receives it as one pasted block and then submits.
     * CRLF / lone CR are normalized to LF; trailing newlines are trimmed.
     */
    fun bracketedPaste(text: String): String {
        val body = text.replace("\r\n", "\n").replace("\r", "\n").trimEnd('\n')
        return "\u001B[200~" + body + "\u001B[201~\r"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:test --tests "com.multiviewer.util.PtyCliCommandTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/util/PtyCliCommand.kt app/src/test/kotlin/com/multiviewer/util/PtyCliCommandTest.kt
git commit -m "feat: add PtyCliCommand string builders for embedded AI CLI"
```

---

## Task 3: `PtyCliTtyConnector` + `WindowsPtyCliSession`

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/terminal/PtyCliTtyConnector.kt`
- Create: `app/src/main/kotlin/com/multiviewer/ui/terminal/WindowsPtyCliSession.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/terminal/WindowsPtyCliSessionTest.kt`

**Interfaces:**
- Consumes: `PtyCliCommand` (Task 2); `com.multiviewer.util.AiCliType`; JediTerm `ProcessTtyConnector` / `TtyConnector` / `TermSize`; pty4j `PtyProcess` / `PtyProcessBuilder` / `WinSize`.
- Produces:
  - `class PtyCliTtyConnector(process: Process) : ProcessTtyConnector` — public, `getName()` = `"AI CLI"`, implements `resize(TermSize)`.
  - `sealed interface SessionState { data object Starting; data object Running; data class Exited(val code: Int); data class Failed(val reason: String) }`
  - `class WindowsPtyCliSession(cli: AiCliType, binPath: String, workingDir: File?, promptText: String, startProcess: () -> Process = <real pty4j builder>)` with:
    - `var state: SessionState` (Compose `mutableStateOf`-backed, private setter)
    - `val ttyConnector: TtyConnector` (valid after `start()` succeeds)
    - `val displayName: String`
    - `val isAlive: Boolean`
    - `fun start()`
    - `fun injectPrompt()`
    - `fun resize(columns: Int, rows: Int)`
    - `fun destroy()`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/multiviewer/ui/terminal/WindowsPtyCliSessionTest.kt`:

```kotlin
package com.multiviewer.ui.terminal

import com.multiviewer.util.AiCliType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WindowsPtyCliSessionTest {

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

    private fun session(fake: FakeProcess, prompt: String = "diag prompt") = WindowsPtyCliSession(
        cli = AiCliType.CLAUDE,
        binPath = "C:\\tools\\claude.cmd",
        workingDir = null,
        promptText = prompt,
        startProcess = { fake },
    )

    private fun awaitState(s: WindowsPtyCliSession, predicate: (SessionState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(s.state)) return
            Thread.sleep(10)
        }
        fail("state never satisfied predicate; last = ${s.state}")
    }

    @Test
    fun startTransitionsToRunningAndWritesLaunchLine() {
        val fake = FakeProcess()
        val s = session(fake)
        s.start()
        assertEquals(SessionState.Running, s.state)
        assertTrue(s.isAlive)
        val written = fake.out.toString("UTF-8")
        assertTrue(written.contains("& \"C:\\tools\\claude.cmd\""))
        assertTrue(written.endsWith("\r"))
    }

    @Test
    fun injectPromptWritesBracketedPasteBurst() {
        val fake = FakeProcess()
        val s = session(fake, prompt = "hello world")
        s.start()
        fake.out.reset()
        s.injectPrompt()
        val written = fake.out.toString("UTF-8")
        assertTrue(written.startsWith("\u001B[200~"))
        assertTrue(written.contains("hello world"))
        assertTrue(written.endsWith("\u001B[201~\r"))
    }

    @Test
    fun processExitMovesStateToExitedWithCode() {
        val fake = FakeProcess()
        val s = session(fake)
        s.start()
        fake.simulateExit(3)
        awaitState(s) { it == SessionState.Exited(3) }
        assertFalse(s.isAlive)
    }

    @Test
    fun startFailureMovesStateToFailed() {
        val s = WindowsPtyCliSession(
            cli = AiCliType.CLAUDE,
            binPath = "x",
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
    fun injectPromptAfterExitIsNoOp() {
        val fake = FakeProcess()
        val s = session(fake)
        s.start()
        fake.simulateExit(0)
        awaitState(s) { it is SessionState.Exited }
        fake.out.reset()
        s.injectPrompt()
        assertEquals(0, fake.out.size())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.terminal.WindowsPtyCliSessionTest"`
Expected: FAIL — `WindowsPtyCliSession` / `SessionState` unresolved.

- [ ] **Step 3: Write `PtyCliTtyConnector`**

Create `app/src/main/kotlin/com/multiviewer/ui/terminal/PtyCliTtyConnector.kt`:

```kotlin
package com.multiviewer.ui.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.pty4j.PtyProcess
import com.pty4j.WinSize
import java.nio.charset.StandardCharsets

/**
 * Bridges a [Process] (real: a pty4j [PtyProcess]) to JediTerm. JediTerm's own
 * `PtyProcessTtyConnector` is not shipped in the published jediterm-core /
 * jediterm-ui artifacts, so this small subclass reimplements it.
 */
class PtyCliTtyConnector(
    private val process: Process,
) : ProcessTtyConnector(process, StandardCharsets.UTF_8, null) {

    override fun getName(): String = "AI CLI"

    override fun resize(termSize: TermSize) {
        val p = process
        if (p is PtyProcess && p.isAlive) {
            p.setWinSize(WinSize(termSize.columns, termSize.rows))
        }
    }
}
```

- [ ] **Step 4: Write `WindowsPtyCliSession`**

Create `app/src/main/kotlin/com/multiviewer/ui/terminal/WindowsPtyCliSession.kt`:

```kotlin
package com.multiviewer.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import com.multiviewer.util.AiCliType
import com.multiviewer.util.PtyCliCommand
import com.pty4j.PtyProcessBuilder
import java.io.File

sealed interface SessionState {
    data object Starting : SessionState
    data object Running : SessionState
    data class Exited(val code: Int) : SessionState
    data class Failed(val reason: String) : SessionState
}

/**
 * Owns exactly one AI CLI process running inside a PowerShell ConPTY session.
 * Windows only. One session at a time is enforced by the caller.
 */
class WindowsPtyCliSession(
    private val cli: AiCliType,
    private val binPath: String,
    private val workingDir: File?,
    private val promptText: String,
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

    val displayName: String get() = cli.displayName

    val isAlive: Boolean get() = process?.isAlive == true

    fun start() {
        try {
            val p = startProcess()
            process = p
            _ttyConnector = PtyCliTtyConnector(p)
            state = SessionState.Running
            runCatching { _ttyConnector!!.write(PtyCliCommand.launchLine(cli, binPath) + "\r") }
            Thread {
                try {
                    val code = p.waitFor()
                    state = SessionState.Exited(code)
                } catch (_: InterruptedException) {
                    // session torn down
                }
            }.apply { isDaemon = true; name = "ai-cli-watch" }.start()
        } catch (t: Throwable) {
            state = SessionState.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    fun injectPrompt() {
        if (isAlive) {
            runCatching { ttyConnector.write(PtyCliCommand.bracketedPaste(promptText)) }
        }
    }

    fun resize(columns: Int, rows: Int) {
        runCatching { _ttyConnector?.resize(TermSize(columns, rows)) }
    }

    fun destroy() {
        runCatching { process?.destroyForcibly() }
        runCatching { _ttyConnector?.close() }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.terminal.WindowsPtyCliSessionTest"`
Expected: PASS (6 tests).

- [ ] **Step 6: Run the full test suite (no regressions)**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all existing tests still pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/terminal/PtyCliTtyConnector.kt app/src/main/kotlin/com/multiviewer/ui/terminal/WindowsPtyCliSession.kt app/src/test/kotlin/com/multiviewer/ui/terminal/WindowsPtyCliSessionTest.kt
git commit -m "feat: add WindowsPtyCliSession + PtyCliTtyConnector"
```

---

## Task 4: `EmbeddedTerminalPanel` Compose component

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/terminal/EmbeddedTerminalPanel.kt`

**Interfaces:**
- Consumes: `WindowsPtyCliSession`, `SessionState` (Task 3); `com.multiviewer.ui.AppColors`; JediTerm `JediTermWidget` / `DefaultSettingsProvider`.
- Produces:
  - `const val PROMPT_INJECT_DELAY_MS: Long = 1400` (package-level in this file, referenced by Task 5 only through the panel — not required elsewhere).
  - `@Composable fun EmbeddedTerminalPanel(session: WindowsPtyCliSession, onEndSession: () -> Unit, modifier: Modifier = Modifier)`

- [ ] **Step 1: Write the component**

Create `app/src/main/kotlin/com/multiviewer/ui/terminal/EmbeddedTerminalPanel.kt`:

```kotlin
package com.multiviewer.ui.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.multiviewer.ui.AppColors
import kotlinx.coroutines.delay
import java.awt.Font

const val PROMPT_INJECT_DELAY_MS: Long = 1400

private class CliTerminalSettings : DefaultSettingsProvider() {
    override fun getTerminalFont(): Font = Font(Font.MONOSPACED, Font.PLAIN, 13)
    override fun getTerminalFontSize(): Float = 13f
    override fun audibleBell(): Boolean = false
}

/**
 * Bottom panel of the AI prompt popup: a live VT100 terminal running the CLI.
 * Auto-injects the diagnostic prompt once, [PROMPT_INJECT_DELAY_MS] after mount.
 */
@Composable
fun EmbeddedTerminalPanel(
    session: WindowsPtyCliSession,
    onEndSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = session.state
    var widget by remember(session) { mutableStateOf<JediTermWidget?>(null) }

    LaunchedEffect(session) {
        delay(PROMPT_INJECT_DELAY_MS)
        session.injectPrompt()
    }
    DisposableEffect(session) {
        onDispose { widget?.close() }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("\u25CF ${session.displayName}", fontSize = 11.sp, color = AppColors.NeonPurple)
            Spacer(Modifier.width(10.dp))
            Text(
                when (val s = state) {
                    SessionState.Starting -> "기동 중…"
                    SessionState.Running -> "실행 중"
                    is SessionState.Exited -> "종료됨 (exit ${s.code})"
                    is SessionState.Failed -> "실패: ${s.reason}"
                },
                fontSize = 11.sp,
                color = AppColors.TextSecondary,
            )
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = { session.injectPrompt() }) {
                Text("프롬프트 재주입", fontSize = 11.sp, color = AppColors.NeonPurple)
            }
            TextButton(onClick = onEndSession) {
                Text("세션 종료", fontSize = 11.sp, color = AppColors.TextSecondary)
            }
        }
        SwingPanel(
            background = Color(0xFF13161A),
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = {
                JediTermWidget(120, 30, CliTerminalSettings()).also { w ->
                    w.ttyConnector = session.ttyConnector
                    w.start()
                    widget = w
                }
            },
            update = { w ->
                val panel = w.terminalPanel
                session.resize(panel.columnCount.coerceAtLeast(20), panel.rowCount.coerceAtLeast(5))
            },
        )
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

If `w.terminalPanel.columnCount` / `rowCount` do not resolve against jediterm 3.74, replace the `update` lambda body with a no-op comment `// size is driven by JediTermWidget's own component listener` and rely on JediTerm's internal resize handling. Re-run compile.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/terminal/EmbeddedTerminalPanel.kt
git commit -m "feat: add EmbeddedTerminalPanel Compose component"
```

---

## Task 5: Wire the embedded terminal into `AiPromptPreviewWindow`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AnalysisWindows.kt`
  - imports block `:1-44`
  - `AiPromptPreviewWindow` window state / `onCloseRequest` / `onKeyEvent` `:594-636`
  - bottom action bar state `:782-791`
  - Local CLI button `onClick` `:936-948`
  - end of the main `Column` content (after the bottom-action `Column`, before it closes near `:999`)
  - the `Surface { Column { ... } }` wrapper `:639-644` (wrap in a `Box` to host dialogs)

**Interfaces:**
- Consumes: `EmbeddedTerminalPanel`, `WindowsPtyCliSession`, `SessionState` (Tasks 3-4); existing `com.multiviewer.util.AiCliDetector`, `com.multiviewer.util.AiCliType`, `ClipboardUtil`.
- Produces: no new public API (all changes are internal to the composable).

- [ ] **Step 1: Add imports**

Add these to the import block of `AnalysisWindows.kt` (keep alph/group order loosely consistent with the file):

```kotlin
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.multiviewer.ui.terminal.EmbeddedTerminalPanel
import com.multiviewer.ui.terminal.SessionState
import com.multiviewer.ui.terminal.WindowsPtyCliSession
```

- [ ] **Step 2: Add session state + helpers inside `AiPromptPreviewWindow`**

Immediately after the existing `var editingService by remember { mutableStateOf<WebAiService?>(null) }` / `var customUrlInput` lines (around `:621-622`), add:

```kotlin
val isWindows = remember { System.getProperty("os.name").lowercase().contains("win") }
var activeCliSession by remember { mutableStateOf<WindowsPtyCliSession?>(null) }
var terminalHeight by remember { mutableStateOf(320.dp) }
var pendingSwitchCli by remember { mutableStateOf<com.multiviewer.util.AiCliType?>(null) }
var confirmCloseWhileRunning by remember { mutableStateOf(false) }
val baseWindowHeight = remember { windowState.size.height }

fun startCliSession(cli: com.multiviewer.util.AiCliType) {
    val bin = com.multiviewer.util.AiCliDetector.findBinary(cli.binaryName)
    if (bin == null) {
        pendingSwitchCli = null
        activeCliSession = null
        return
    }
    ClipboardUtil.copyToClipboard(promptText)
    val s = WindowsPtyCliSession(cli, bin, tab.file.parentFile, promptText)
    s.start()
    if (s.state is SessionState.Failed) {
        com.multiviewer.util.AiCliDetector.launchInteractiveCli(cli, promptText, tab.file.parentFile)
        activeCliSession = null
        return
    }
    activeCliSession = s
    windowState.size = windowState.size.copy(
        height = (baseWindowHeight + terminalHeight + 48.dp).coerceAtMost(1200.dp),
    )
}

fun endCliSession() {
    activeCliSession?.destroy()
    activeCliSession = null
    windowState.size = windowState.size.copy(height = baseWindowHeight)
}

val requestClose: () -> Unit = {
    if (activeCliSession?.isAlive == true) {
        confirmCloseWhileRunning = true
    } else {
        activeCliSession?.destroy()
        onCloseRequest()
    }
}

DisposableEffect(Unit) {
    onDispose { activeCliSession?.destroy() }
}
```

Note: `startCliSession` sets a status via the existing `statusMessage` mechanism, but `statusMessage` is declared later in the file (`:785`). To keep it simple, `startCliSession` does NOT touch `statusMessage`; the "not found" / "fallback" messaging is handled in the button `onClick` (Step 4) where `statusMessage` is in scope. Adjust `startCliSession` to return a `Boolean` / `String?` result the caller turns into a message:

Replace the two early `return` bodies and the failure block in `startCliSession` with a `String?` return type:

```kotlin
fun startCliSession(cli: com.multiviewer.util.AiCliType): String? {
    val bin = com.multiviewer.util.AiCliDetector.findBinary(cli.binaryName)
        ?: return "${cli.binaryName} 실행 파일을 찾을 수 없습니다"
    ClipboardUtil.copyToClipboard(promptText)
    val s = WindowsPtyCliSession(cli, bin, tab.file.parentFile, promptText)
    s.start()
    if (s.state is SessionState.Failed) {
        com.multiviewer.util.AiCliDetector.launchInteractiveCli(cli, promptText, tab.file.parentFile)
        return "임베드 터미널 실패 — 외부 창으로 실행"
    }
    activeCliSession = s
    windowState.size = windowState.size.copy(
        height = (baseWindowHeight + terminalHeight + 48.dp).coerceAtMost(1200.dp),
    )
    return "${cli.displayName} 임베드 세션 시작 (프롬프트 자동 입력 예정)"
}
```

- [ ] **Step 3: Route window close + ESC through `requestClose`**

In the `Window(...)` call (`:624-635`), change:
- `onCloseRequest = onCloseRequest,` → `onCloseRequest = requestClose,`
- inside `onKeyEvent`, the Escape branch `onCloseRequest()` → `requestClose()`

- [ ] **Step 4: Replace the Local CLI button `onClick` (`:936-948`)**

Replace the existing `onClick = { ... }` for the CLI `Button` with:

```kotlin
onClick = {
    ClipboardUtil.copyToClipboard(promptText)
    if (isWindows) {
        if (activeCliSession?.isAlive == true) {
            pendingSwitchCli = cli
        } else {
            statusMessage = startCliSession(cli)
        }
    } else {
        val success = com.multiviewer.util.AiCliDetector.launchInteractiveCli(
            cli,
            promptText,
            tab.file.parentFile,
        )
        statusMessage = if (success) {
            "${cli.displayName} 터미널 실행됨 (전체 프롬프트 클립보드 복사 완료: 붙여넣기 가능)"
        } else {
            "${cli.displayName} 실행 실패"
        }
    }
},
```

- [ ] **Step 5: Add the divider + terminal panel to the layout**

Find the end of the bottom-action `Column(modifier = Modifier.fillMaxWidth()) { Row {...} Row {...} }` block (it closes shortly before the outer content `Column` closes, near `:999`). Immediately AFTER that `Column`'s closing brace and BEFORE the outer content `Column` closes, add:

```kotlin
if (isWindows && activeCliSession != null) {
    Spacer(Modifier.height(6.dp))
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(AppColors.Border, RoundedCornerShape(3.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val deltaDp = with(density) { dragAmount.y.toDp() }
                    terminalHeight = (terminalHeight - deltaDp).coerceIn(180.dp, 640.dp)
                }
            },
    )
    Spacer(Modifier.height(4.dp))
    EmbeddedTerminalPanel(
        session = activeCliSession!!,
        onEndSession = { endCliSession() },
        modifier = Modifier.fillMaxWidth().height(terminalHeight),
    )
}
```

(The existing prompt-preview `Box` has `Modifier.weight(1f)`, so it shrinks to make room; the window was grown by `startCliSession` so there is space.)

- [ ] **Step 6: Wrap the content `Column` in a `Box` and add the two dialogs**

Change the `Surface(...) { Column(...) { ... } }` so the `Column` and dialogs are siblings in a `Box`:

- After `Surface(modifier = Modifier.fillMaxSize(), color = AppColors.Background) {` add `Box(Modifier.fillMaxSize()) {`
- Add a matching `}` before the `Surface`'s closing `}`.
- Inside that `Box`, after the content `Column`'s closing brace, add:

```kotlin
pendingSwitchCli?.let { next ->
    AlertDialog(
        onDismissRequest = { pendingSwitchCli = null },
        title = { Text("세션 전환") },
        text = {
            Text("현재 실행 중인 ${activeCliSession?.displayName ?: ""} 세션을 종료하고 ${next.displayName}(으)로 전환할까요?")
        },
        confirmButton = {
            TextButton(onClick = {
                pendingSwitchCli = null
                endCliSession()
                statusMessage = startCliSession(next)
            }) { Text("전환") }
        },
        dismissButton = {
            TextButton(onClick = { pendingSwitchCli = null }) { Text("취소") }
        },
    )
}
if (confirmCloseWhileRunning) {
    AlertDialog(
        onDismissRequest = { confirmCloseWhileRunning = false },
        title = { Text("세션 종료") },
        text = { Text("실행 중인 CLI 세션을 종료하고 창을 닫습니다.") },
        confirmButton = {
            TextButton(onClick = {
                confirmCloseWhileRunning = false
                activeCliSession?.destroy()
                activeCliSession = null
                onCloseRequest()
            }) { Text("종료 후 닫기") }
        },
        dismissButton = {
            TextButton(onClick = { confirmCloseWhileRunning = false }) { Text("취소") }
        },
    )
}
```

- [ ] **Step 7: Verify compilation**

Run: `./gradlew :app:compileKotlin`
Expected: `BUILD SUCCESSFUL`. Fix any unresolved reference (common: missing `Box`/`Spacer`/`height` imports — the file uses `androidx.compose.foundation.layout.*` so they are already imported).

- [ ] **Step 8: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass (no new automated tests in this task; UI wiring is covered by manual testing in Task 6).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AnalysisWindows.kt
git commit -m "feat: run AI CLI in embedded terminal inside prompt popup on Windows"
```

---

## Task 6: Verification build + manual test pass

**Files:** none (verification only). Any fixes discovered here are committed against the file that needs them.

**Interfaces:** none.

- [ ] **Step 1: Full clean build**

Run: `./gradlew clean :app:build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Build the Windows distributable image and check jars are bundled**

Run (on Windows, or a machine that can run `createDistributable`): `./gradlew :app:createDistributable`
Then confirm these files exist under `app/build/compose/binaries/main/app/unwrapMedia/app/`:
- `jediterm-core-3.74.jar`
- `jediterm-ui-3.74.jar`
- `pty4j-0.13.12.jar`
- `jna-5.14.0.jar`, `jna-platform-5.14.0.jar`

Expected: all present (they are what makes the feature work without a separate user install).

- [ ] **Step 3: Manual test on Windows — happy path per CLI**

For each installed CLI (`claude`, `codex`, `gemini`, `agy`):
1. Open a media file, open "Generate AI Prompt…".
2. Click `▶ <CLI>`.
3. Expected: window grows ~320 dp taller; a terminal panel appears at the bottom; PowerShell starts in the media file's folder; the CLI launches; ~1.4 s later the diagnostic prompt appears in the CLI input and is submitted.
4. Type a follow-up and press Enter — expected: input reaches the CLI.
5. Press Ctrl+C — expected: interrupts the CLI, not the app.

- [ ] **Step 4: Manual test — controls**

1. Drag the divider up/down — terminal resizes between ~180 dp and ~640 dp; prompt preview above shrinks/grows.
2. Click **프롬프트 재주입** — the prompt is pasted again.
3. Click **세션 종료** — panel disappears, window returns to original height, `powershell.exe` and the CLI process are gone (check Task Manager).

- [ ] **Step 5: Manual test — lifecycle dialogs**

1. Start a session, then click a different `▶ <CLI>` — expected: "세션 전환" dialog; confirm → old process gone, new session starts.
2. Start a session, then close the popup (X) or press Esc — expected: "세션 종료" dialog; confirm → popup closes and no orphan `powershell.exe` / node process remains.
3. Start a session where the CLI exits immediately (e.g. rename the binary so it errors) — expected: header shows "종료됨 (exit N)"; **세션 종료** clears it; no crash.

- [ ] **Step 6: Manual test — missing CLI + fallback**

1. On a machine without a given CLI — expected: that `▶` button is not shown (unchanged behavior).
2. Temporarily force `WindowsPtyCliSession.start()` failure (e.g. break `powershellArgv` locally) — expected: falls back to the old external PowerShell window and status reads "임베드 터미널 실패 — 외부 창으로 실행". Revert the local change.

- [ ] **Step 7: Regression — macOS/Linux**

On macOS (and Linux if available): click `▶ <CLI>` — expected: an external Terminal window opens exactly as before; no embedded panel; window does not grow.

- [ ] **Step 8: Commit any fixes and finish**

```bash
git add -A
git commit -m "fix: address embedded AI CLI terminal issues from manual testing"
```

(Skip if Step 1-7 needed no changes.)

---

## Self-Review Notes

- **Spec coverage:** dependencies/size/no-install → Task 1; `PtyCliCommand` + bracketed paste → Task 2; `WindowsPtyCliSession` + connector + one-at-a-time lifecycle + state machine + fallback → Task 3; `EmbeddedTerminalPanel` + 1200–1400 ms inject delay + re-inject + end-session → Task 4; window growth/divider/dialogs/close handling/non-Windows preservation → Task 5; native bundling + manual matrix + regression → Task 6.
- **Inject delay:** spec said 1200 ms as a starting value; plan uses `PROMPT_INJECT_DELAY_MS = 1400` as a single named constant, tune during Task 6.
- **Deviation from spec:** `state` is a Compose `mutableStateOf` (not `StateFlow`) since `WindowsPtyCliSession` lives in a UI package and only the Compose layer observes it — avoids adding a coroutines-test dependency and keeps the session's watcher thread able to publish state directly.
- **Deviation from spec:** `PtyProcessTtyConnector` is not in the published jediterm artifacts, so `PtyCliTtyConnector` reimplements its ~15 lines (noted in Task 3).
- **Known non-fatal:** SLF4J may print "No providers were found" at runtime — harmless; do not add a binding unless it proves noisy. The `kotlin-stdlib` force in Task 1 is required (jediterm-core's POM pulls 2.4.0); without it the Kotlin 2.0.21 compiler cannot read the stdlib metadata.
