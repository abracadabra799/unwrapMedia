package com.multiviewer.ui

import java.util.prefs.Preferences

// Uses java.util.prefs (JDK standard library, no new dependency) as this app's first-ever
// persisted preference -- backed by the OS's native preference store (plist on macOS, registry
// on Windows), a single small key read/write with no measurable cost at startup.
private val themePreferences: Preferences = Preferences.userNodeForPackage(AppColors::class.java)
private const val THEME_MODE_KEY = "themeMode"

fun loadThemeMode(): ThemeMode =
    if (themePreferences.get(THEME_MODE_KEY, ThemeMode.DARK.name) == ThemeMode.LIGHT.name) {
        ThemeMode.LIGHT
    } else {
        ThemeMode.DARK
    }

fun saveThemeMode(mode: ThemeMode) {
    themePreferences.put(THEME_MODE_KEY, mode.name)
}
