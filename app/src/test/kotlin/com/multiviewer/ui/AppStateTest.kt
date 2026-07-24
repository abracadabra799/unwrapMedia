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
    fun `openFile rejects a third distinct file and sets statusMessage`() {
        val appState = AppState()
        val file1 = tempFile("appstate-test-1")
        val file2 = tempFile("appstate-test-2")
        val file3 = tempFile("appstate-test-3")

        appState.openFile(file1)
        appState.openFile(file2)
        assertEquals(2, appState.tabs.size)
        assertEquals(null, appState.statusMessage)

        appState.openFile(file3)
        assertEquals(2, appState.tabs.size)
        assertEquals("You can only have 2 files open at a time.", appState.statusMessage)
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
        assertEquals(null, appState.statusMessage)
    }

    @Test
    fun `openFile populates embeddedVideo when the file contains a top-level mpvd box`() {
        // A minimal ISOBMFF file: a top-level ftyp box, followed by an mpvd box
        // whose payload is itself a single nested ftyp box (major_brand "isom").
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x10, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x18, 'm'.code.toByte(), 'p'.code.toByte(), 'v'.code.toByte(), 'd'.code.toByte(),
            0x00, 0x00, 0x00, 0x10, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00, 0x00, 0x00, 0x00,
        )
        val file = File.createTempFile("appstate-motion-photo", ".bin")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val appState = AppState()
        appState.openFile(file)

        val tab = appState.tabs.single()
        assertEquals(null, tab.error)
        assertEquals(24L, tab.embeddedVideo?.start)
        assertEquals(40L, tab.embeddedVideo?.end)
        assertEquals("mp4", tab.embeddedVideo?.extension)
    }

    @Test
    fun `openFile leaves embeddedVideo null when the file has no embedded video`() {
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x10, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00, 0x00, 0x00, 0x00,
        )
        val file = File.createTempFile("appstate-no-motion-photo", ".bin")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val appState = AppState()
        appState.openFile(file)

        val tab = appState.tabs.single()
        assertEquals(null, tab.error)
        assertEquals(null, tab.embeddedVideo)
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

    @Test
    fun `openFile on an undecodable IMAGE-type file synchronously sets isDecodingFallback before the VLC callback resolves`() {
        val file = File.createTempFile("appstate-heic-fallback-test", ".heic")
        file.deleteOnExit()
        file.writeBytes(ByteArray(300)) // garbage — Skia's Image.makeFromEncoded will return null

        val appState = AppState()
        appState.openFile(file)

        val tab = appState.tabs.single()
        assertEquals(MediaType.IMAGE, tab.type)
        assertEquals(null, tab.imageForensic?.bitmap)
        assertEquals(true, tab.imageForensic?.isDecodingFallback)
    }

    @Test
    fun `openFile on a VIDEO-type file never sets isDecodingFallback, even though its Skia decode also fails`() {
        val file = File.createTempFile("appstate-video-no-fallback-test", ".mp4")
        file.deleteOnExit()
        file.writeBytes(ByteArray(300)) // garbage — Skia's Image.makeFromEncoded will also return null here

        val appState = AppState()
        appState.openFile(file)

        val tab = appState.tabs.single()
        assertEquals(MediaType.VIDEO, tab.type)
        assertEquals(null, tab.imageForensic?.bitmap)
        assertEquals(false, tab.imageForensic?.isDecodingFallback)
    }
}
