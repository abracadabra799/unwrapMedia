package com.multiviewer.ui.terminal

import com.multiviewer.util.ProcessManager
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

    // ---- leak / stress ----

    private fun shellThreadCount() = Thread.getAllStackTraces().keys.count {
        it.name == "shell-watch" || it.name == "shell-prelude"
    }

    @Test
    fun rapidStartDestroyCyclesLeaveNoTrackedProcessOrThread() {
        val baseProc = ProcessManager.activeCount
        repeat(200) {
            val fake = FakeProcess()
            val s = session(fake)
            s.start()
            s.destroy()
            assertTrue(fake.destroyed)
        }
        await(3000) { ProcessManager.activeCount == baseProc && shellThreadCount() == 0 }
        assertEquals(baseProc, ProcessManager.activeCount, "tracked processes leaked")
        assertEquals(0, shellThreadCount(), "shell daemon threads leaked")
    }

    @Test
    fun restartPatternDestroysThePriorSessionOnly() {
        // mirrors AiPromptPreviewWindow.startShellSession: destroy the prior, keep the new
        val baseProc = ProcessManager.activeCount
        val a = FakeProcess(); val sa = session(a); sa.start()
        val b = FakeProcess(); val sb = session(b); sb.start()
        assertEquals(baseProc + 2, ProcessManager.activeCount)

        sa.destroy()
        assertTrue(a.destroyed)
        assertFalse(b.destroyed)
        assertTrue(sb.isAlive)

        sb.destroy()
        await(2000) { ProcessManager.activeCount == baseProc }
        assertTrue(b.destroyed)
    }

    @Test
    fun destroyAllKillsALiveSessionAndDestroyStaysSafeAfterward() {
        val fake = FakeProcess()
        val s = session(fake)
        s.start()
        assertTrue(ProcessManager.activeCount >= 1)

        ProcessManager.destroyAll()
        assertTrue(fake.destroyed)
        assertEquals(0, ProcessManager.activeCount, "destroyAll must clear the whole set")

        assertDoesNotThrow { s.destroy() } // idempotent after an external kill
        assertDoesNotThrow { s.destroy() }
    }

    @Test
    fun startFailureLeavesNoTrackedProcess() {
        val baseProc = ProcessManager.activeCount
        val s = WindowsShellSession(
            workingDir = null,
            promptText = "p",
            startProcess = { throw IOException("boom") },
        )
        s.start()
        assertTrue(s.state is SessionState.Failed)
        assertEquals(baseProc, ProcessManager.activeCount, "a failed start must not leak a registration")
    }
}
