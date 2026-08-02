package com.multiviewer.ui

import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemePreferenceTest {
    // Matches ThemePreference.kt's own THEME_MODE_KEY value -- kept as a literal here (rather
    // than importing the private constant) since tests intentionally exercise the public
    // loadThemeMode/saveThemeMode functions, only reaching into the raw Preferences node to set
    // up a known "nothing saved yet" starting state.
    private val prefs = Preferences.userNodeForPackage(AppColors::class.java)

    @Test
    fun `defaults to DARK when nothing has been saved`() {
        prefs.remove("themeMode")
        assertEquals(ThemeMode.DARK, loadThemeMode())
    }

    @Test
    fun `round-trips LIGHT`() {
        saveThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, loadThemeMode())
    }

    @Test
    fun `round-trips DARK`() {
        saveThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, loadThemeMode())
    }
}
