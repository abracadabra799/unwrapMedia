package com.multiviewer.ui

import java.util.prefs.Preferences

private val pixelGridPreferences: Preferences = Preferences.userNodeForPackage(AppColors::class.java)
private const val SHOW_PIXEL_GRID_KEY = "showPixelGrid"

fun loadShowPixelGrid(): Boolean = pixelGridPreferences.getBoolean(SHOW_PIXEL_GRID_KEY, false)

fun saveShowPixelGrid(show: Boolean) {
    pixelGridPreferences.putBoolean(SHOW_PIXEL_GRID_KEY, show)
}
