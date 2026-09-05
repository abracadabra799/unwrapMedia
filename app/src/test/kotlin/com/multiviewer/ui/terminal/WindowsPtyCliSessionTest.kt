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
        fail<Unit>("state never satisfied predicate; last = ${s.state}")
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
