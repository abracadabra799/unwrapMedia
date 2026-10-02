package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvmLocatorTest {
    @Test
    fun `non Windows never resolves an AVM helper`() {
        val oldOs = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Linux")
            assertEquals(AvmDecoderLocation.UnsupportedPlatform, AvmLocator.decoderLocation())
        } finally {
            if (oldOs == null) System.clearProperty("os.name") else System.setProperty("os.name", oldOs)
        }
    }

    @Test
    fun `Windows resolves helper from packaged bin directory`() {
        val oldOs = System.getProperty("os.name")
        val resources = File.createTempFile("avm-locator-test-", "").apply { delete(); mkdirs() }
        val helper = File(resources, "bin/avmdec.exe").apply { parentFile.mkdirs(); writeText("fake") }
        try {
            System.setProperty("os.name", "Windows 11")
            System.setProperty("compose.application.resources.dir", resources.absolutePath)
            val location = AvmLocator.decoderLocation()
            assertTrue(location is AvmDecoderLocation.Packaged)
            assertEquals(helper.absolutePath, (location as AvmDecoderLocation.Packaged).file.absolutePath)
        } finally {
            if (oldOs == null) System.clearProperty("os.name") else System.setProperty("os.name", oldOs)
            System.clearProperty("compose.application.resources.dir")
            resources.deleteRecursively()
        }
    }
}
