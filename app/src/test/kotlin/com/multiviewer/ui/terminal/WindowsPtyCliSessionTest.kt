package com.multiviewer.ui.terminal

import com.multiviewer.util.AiCliType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch

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
    }

    @Test
    fun startWritesLaunchLineToThePtyOffTheCallerThread() {
        val fake = FakeProcess()
        val s = session(fake)
        s.start()
        // launch line is written on the "ai-cli-launch" daemon thread, not synchronously
        await { fake.out.toString("UTF-8").contains("& 'C:\\tools\\claude.cmd'") }
        val written = fake.out.toString("UTF-8")
        assertTrue(written.contains("exit \$LASTEXITCODE"))
        assertTrue(written.endsWith("\r"))
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
    fun destroyIsSafeBeforeStartAndWhenRepeated() {
        val fake = FakeProcess()
        val s = session(fake)
        s.destroy() // never started
        s.start()
        s.destroy()
        s.destroy() // idempotent
        assertTrue(fake.destroyed)
    }
}
