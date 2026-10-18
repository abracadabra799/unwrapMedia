package com.multiviewer.ui

import java.util.prefs.Preferences

private val panelPreferences: Preferences = Preferences.userNodeForPackage(AppColors::class.java)

private const val KEY_LEFT_PANEL_WIDTH = "panel_width_left"
private const val KEY_RIGHT_PANEL_WIDTH = "panel_width_right"
private const val KEY_VERTICAL_SPLIT = "panel_vertical_split"

fun loadLeftPanelWidth(default: Float = 330f): Float =
    panelPreferences.getFloat(KEY_LEFT_PANEL_WIDTH, default).coerceIn(180f, 800f)

fun saveLeftPanelWidth(width: Float) {
    try {
        panelPreferences.putFloat(KEY_LEFT_PANEL_WIDTH, width)
    } catch (_: Throwable) {}
}

fun loadRightPanelWidth(default: Float = 430f): Float =
    panelPreferences.getFloat(KEY_RIGHT_PANEL_WIDTH, default).coerceIn(240f, 1000f)

fun saveRightPanelWidth(width: Float) {
    try {
        panelPreferences.putFloat(KEY_RIGHT_PANEL_WIDTH, width)
    } catch (_: Throwable) {}
}

fun loadVerticalSplit(default: Float = 0.60f): Float =
    panelPreferences.getFloat(KEY_VERTICAL_SPLIT, default).coerceIn(0.2f, 0.85f)

fun saveVerticalSplit(split: Float) {
    try {
        panelPreferences.putFloat(KEY_VERTICAL_SPLIT, split)
    } catch (_: Throwable) {}
}
