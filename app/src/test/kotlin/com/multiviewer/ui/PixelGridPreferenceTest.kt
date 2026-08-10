package com.multiviewer.ui

import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PixelGridPreferenceTest {
    // Same Preferences node ThemePreferenceTest.kt already reaches into -- both this key and
    // "themeMode" live side by side under AppColors's package node.
    private val prefs = Preferences.userNodeForPackage(AppColors::class.java)

    @Test
    fun `defaults to false when nothing has been saved`() {
        prefs.remove("showPixelGrid")
        assertFalse(loadShowPixelGrid())
    }

    @Test
    fun `round-trips true`() {
        saveShowPixelGrid(true)
        assertTrue(loadShowPixelGrid())
    }

    @Test
    fun `round-trips false`() {
        saveShowPixelGrid(false)
        assertFalse(loadShowPixelGrid())
    }
}
