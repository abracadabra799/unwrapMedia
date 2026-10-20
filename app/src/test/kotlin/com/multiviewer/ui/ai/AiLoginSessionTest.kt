package com.multiviewer.ui.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

class AiLoginSessionTest {
    @Test
    fun `Windows invokes selected CLI through PowerShell without shell interpolation`() {
        val path = File("C:\\Users\\O'Brien\\AI Tools\\codex.cmd")
        assertEquals(
            listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NoExit", "-Command", "& 'C:\\Users\\O''Brien\\AI Tools\\codex.cmd'"),
            aiLoginCommand(path, "Windows 11"),
        )
    }

    @Test
    fun `macOS runs the resolved CLI directly`() {
        val path = File("/Applications/AI Tools/claude")
        assertEquals(listOf(path.absolutePath), aiLoginCommand(path, "Mac OS X"))
    }

    @Test
    fun `interactive PTY starts and reports CLI exit on macOS`() {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return
        val session = AiLoginSession(File("/usr/bin/true"))
        try {
            session.start()
            assertTrue(session.state is AiLoginState.Running || session.state is AiLoginState.Exited, session.state.toString())
            val deadline = System.currentTimeMillis() + 3_000
            while (session.state is AiLoginState.Running && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(AiLoginState.Exited(0), session.state)
        } finally {
            session.destroy()
        }
    }
}
