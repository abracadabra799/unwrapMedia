package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppLifecycleStressTest {

    @Test
    fun testTabRapidOpenAndCloseStress() {
        val appState = AppState()
        val tempFiles = (1..30).map { i ->
            val f = File.createTempFile("stress_$i", ".mp4")
            f.deleteOnExit()
            f.writeBytes(ByteArray(64))
            f
        }

        try {
            // Rapid open and close cycle
            for (f in tempFiles) {
                appState.openFile(f)
            }
            assertTrue(appState.tabs.size <= 20)

            while (appState.tabs.isNotEmpty()) {
                appState.closeTab(0)
            }
            assertEquals(0, appState.tabs.size)
        } finally {
            tempFiles.forEach { it.delete() }
        }
    }
}
