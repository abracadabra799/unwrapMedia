package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppStateTest {
    private fun tempFile(name: String): File {
        val tmp = File.createTempFile(name, ".bin")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(4))
        return tmp
    }

    @Test
    fun `openFile rejects a third distinct file and sets openError`() {
        val appState = AppState()
        val file1 = tempFile("appstate-test-1")
        val file2 = tempFile("appstate-test-2")
        val file3 = tempFile("appstate-test-3")

        appState.openFile(file1)
        appState.openFile(file2)
        assertEquals(2, appState.tabs.size)
        assertEquals(null, appState.openError)

        appState.openFile(file3)
        assertEquals(2, appState.tabs.size)
        assertEquals("You can only have 2 files open at a time.", appState.openError)
    }

    @Test
    fun `openFile re-opening an already-open file switches to it without being rejected`() {
        val appState = AppState()
        val file1 = tempFile("appstate-test-a")
        val file2 = tempFile("appstate-test-b")

        appState.openFile(file1)
        appState.openFile(file2)
        appState.openFile(file1)

        assertEquals(2, appState.tabs.size)
        assertEquals(0, appState.selectedTabIndex)
        assertEquals(null, appState.openError)
    }

    @Test
    fun `closeTab on the last remaining tab returns to an empty tab list`() {
        val appState = AppState()
        appState.tabs.add(TabState(File("dummy.bin")))
        appState.selectedTabIndex = 0

        appState.closeTab(0)

        assertTrue(appState.tabs.isEmpty())
        assertEquals(0, appState.selectedTabIndex)
    }

    @Test
    fun `closeTab on the selected tab selects the tab that slides into its position`() {
        val appState = AppState()
        appState.tabs.add(TabState(File("a.bin")))
        appState.tabs.add(TabState(File("b.bin")))
        appState.tabs.add(TabState(File("c.bin")))
        appState.selectedTabIndex = 1

        appState.closeTab(1)

        assertEquals(listOf("a.bin", "c.bin"), appState.tabs.map { it.file.name })
        assertEquals(1, appState.selectedTabIndex)
    }

    @Test
    fun `closeTab on a tab before the selected tab shifts the selection index down`() {
        val appState = AppState()
        appState.tabs.add(TabState(File("a.bin")))
        appState.tabs.add(TabState(File("b.bin")))
        appState.tabs.add(TabState(File("c.bin")))
        appState.selectedTabIndex = 2

        appState.closeTab(0)

        assertEquals(listOf("b.bin", "c.bin"), appState.tabs.map { it.file.name })
        assertEquals(1, appState.selectedTabIndex)
    }

    @Test
    fun `closeTab on a tab after the selected tab leaves the selection index unchanged`() {
        val appState = AppState()
        appState.tabs.add(TabState(File("a.bin")))
        appState.tabs.add(TabState(File("b.bin")))
        appState.tabs.add(TabState(File("c.bin")))
        appState.selectedTabIndex = 0

        appState.closeTab(2)

        assertEquals(listOf("a.bin", "b.bin"), appState.tabs.map { it.file.name })
        assertEquals(0, appState.selectedTabIndex)
    }

    @Test
    fun `closeTab on the last tab when it is selected selects the new last tab`() {
        val appState = AppState()
        appState.tabs.add(TabState(File("a.bin")))
        appState.tabs.add(TabState(File("b.bin")))
        appState.selectedTabIndex = 1

        appState.closeTab(1)

        assertEquals(listOf("a.bin"), appState.tabs.map { it.file.name })
        assertEquals(0, appState.selectedTabIndex)
    }
}
