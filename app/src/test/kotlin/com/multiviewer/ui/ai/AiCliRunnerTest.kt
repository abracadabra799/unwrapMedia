package com.multiviewer.ui.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AiCliRunnerTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"), "fake CLIs are POSIX shell scripts")
        dir = Files.createTempDirectory("ai cli 테스트 ").toFile()
    }

    @AfterTest
    fun tearDown() { if (::dir.isInitialized) dir.deleteRecursively() }

    private fun script(name: String, body: String): File =
        File(dir, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }

    private val adapter = ClaudeCliAdapter()

    private fun run(exe: File, prompt: String = "q", timeoutMillis: Long = 10_000, maxOutput: Int = 512 * 1024, current: () -> Boolean = { true }) =
        runBlocking { runAiCli(adapter, exe, prompt, timeoutMillis, maxOutput, current) }

    @Test
    fun `executables are found in directories with spaces and non-ASCII names`() {
        val exe = script("claude", "exit 0")
        assertEquals(exe.absoluteFile, resolveExecutable("claude", listOf(File("/nonexistent"), dir), windows = false))
        assertEquals(null, resolveExecutable("codex", listOf(dir), windows = false))
    }

    @Test
    fun `windows resolution follows PATHEXT order and wraps shims in a quoted cmd line`() {
        File(dir, "codex.cmd").writeText("@echo off")
        File(dir, "codex.exe").writeText("MZ")
        assertEquals(File(dir, "codex.exe").absoluteFile, resolveExecutable("codex", listOf(dir), windows = true, pathExt = ".COM;.EXE;.CMD"))
        assertEquals(File(dir, "codex.cmd").absoluteFile, resolveExecutable("codex", listOf(dir), windows = true, pathExt = ".CMD;.EXE"))

        val shim = File("C:\\Users\\김 동\\AppData\\Roaming\\npm\\claude.cmd")
        assertEquals(
            listOf("cmd.exe", "/d", "/s", "/c", "\"\"C:\\Users\\김 동\\AppData\\Roaming\\npm\\claude.cmd\" -p --tools \"\"\""),
            launchCommand(shim, listOf("-p", "--tools", ""), windows = true, comSpec = "cmd.exe"),
        )
        val native = File("C:\\Tools\\claude.exe")
        assertEquals(listOf(native.path, "-p"), launchCommand(native, listOf("-p"), windows = true, comSpec = "cmd.exe"))
    }

    @Test
    fun `the prompt goes through stdin intact and the cli runs in an app-owned temp dir that is removed`() {
        val exe = script("claude", """pwd > "${dir.absolutePath}/cwd.txt"; echo "ARGS:$*"; cat""")
        val prompt = "안녕 \"quoted\" \$(rm -rf /) `x` & | ;\nline2"
        val result = assertIs<AiCliResult.Answer>(run(exe, prompt))
        assertTrue(result.text.contains(prompt), result.text)
        assertTrue(result.text.contains("ARGS:-p --output-format text --tools  --strict-mcp-config --no-session-persistence"), result.text)
        val cwd = File(File(dir, "cwd.txt").readText().trim())
        assertTrue(cwd.name.startsWith("unwrapmedia-ai-"), cwd.path)
        assertFalse(cwd.exists(), "temp dir must be cleaned up")
    }

    @Test
    fun `a missing login is reported as such, not as an answer`() {
        val exe = script("claude", "echo 'Invalid API key · Please run /login' >&2; exit 1")
        val result = assertIs<AiCliResult.Failed>(run(exe))
        assertEquals(AiFailure.AUTH_REQUIRED, result.reason)
    }

    @Test
    fun `empty output with exit 0 is a failure`() {
        val exe = script("claude", "exit 0")
        assertEquals(AiFailure.EMPTY_OUTPUT, assertIs<AiCliResult.Failed>(run(exe)).reason)
    }

    @Test
    fun `a hung cli times out and is killed`() {
        val pidFile = File(dir, "pid")
        val exe = script("claude", """echo $$ > "${pidFile.absolutePath}"; sleep 30""")
        val started = System.nanoTime()
        assertIs<AiCliResult.TimedOut>(run(exe, timeoutMillis = 500))
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
        val pid = pidFile.readText().trim().toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `oversized output is capped and flagged`() {
        val exe = script("claude", "head -c 3000000 /dev/zero | tr '\\0' 'a'")
        val result = assertIs<AiCliResult.Answer>(run(exe, maxOutput = 64 * 1024))
        assertTrue(result.truncated)
        assertTrue(result.text.length <= 64 * 1024, result.text.length.toString())
    }

    @Test
    fun `cancellation terminates the process tree promptly`() {
        val pidFile = File(dir, "child")
        val exe = script("claude", """sleep 30 & echo $! > "${pidFile.absolutePath}"; wait""")
        runBlocking {
            val call = async(Dispatchers.Default) { runAiCli(adapter, exe, "q", 30_000, 1024, { true }) }
            while (!pidFile.exists() || pidFile.readText().isBlank()) delay(20)
            val started = System.nanoTime()
            call.cancel()
            withTimeout(5_000) { runCatching { call.await() } }
            assertTrue(System.nanoTime() - started < 5_000_000_000L)
        }
        val child = pidFile.readText().trim().toLong()
        Thread.sleep(300)
        assertFalse(ProcessHandle.of(child).map { it.isAlive }.orElse(false), "child sleep must be killed")
    }

    @Test
    fun `a stale file never launches the cli`() {
        val marker = File(dir, "ran")
        val exe = script("claude", """touch "${marker.absolutePath}"; echo hi""")
        assertIs<AiCliResult.StaleFile>(run(exe, current = { false }))
        assertFalse(marker.exists())
    }

    @Test
    fun `capabilities are unknown until verified and images need verification`() {
        val caps = AiProviderCapabilities(AiProviderId.CLAUDE)
        assertEquals(CapabilityState.UNKNOWN, caps.text)
        assertFalse(caps.canAttachImage)
        assertFalse(caps.copy(image = CapabilityState.UNKNOWN).canAttachImage)
        assertTrue(caps.copy(image = CapabilityState.VERIFIED).canAttachImage)
        assertEquals(CapabilityState.VERIFIED, caps.afterAnswer().text)
        assertEquals(CapabilityState.UNSUPPORTED, caps.afterFailure(AiFailure.AUTH_REQUIRED).text)
    }

    @Test
    fun `prompt carries rules, bounded history and the current context only`() {
        val history = (1..20).map { AiMessage(it.toLong(), if (it % 2 == 1) AiRole.USER else AiRole.ASSISTANT, "turn $it " + "x".repeat(10_000), AiProviderId.CLAUDE, it.toLong(), "OLD CONTEXT $it") } +
            AiMessage(99, AiRole.NOTICE, "timed out", null, 99)
        val prompt = buildAiCliPrompt(history, "CURRENT CONTEXT", "what is wrong?")
        assertTrue(prompt.contains("CURRENT CONTEXT"))
        assertFalse(prompt.contains("OLD CONTEXT"))
        assertFalse(prompt.contains("timed out"))
        assertFalse(prompt.contains("turn 1 "))
        assertTrue(prompt.contains("turn 20 "))
        assertTrue(prompt.contains("what is wrong?"))
        assertTrue(prompt.length < 60_000, prompt.length.toString())
    }
}
