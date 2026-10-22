package com.multiviewer.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope

private fun AppKeyShortcut.toNativeKeyShortcut(): KeyShortcut = KeyShortcut(key, meta = meta, shift = shift)

/**
 * Renders [menus] via the OS's own native menu bar (Cocoa NSMenu on macOS) -- today's exact
 * behavior, just driven by the shared [AppMenu] data instead of hand-written Menu/Item calls.
 * macOS only; see CustomMenuBar.kt for the Windows/Linux renderer.
 */
@Composable
fun FrameWindowScope.NativeAppMenuBar(menus: List<AppMenu>) {
    MenuBar {
        menus.forEach { menu ->
            Menu(menu.label) { NativeEntries(menu.entries) }
        }
    }
}

@Composable
private fun MenuScope.NativeEntries(entries: List<MenuEntry>) {
    entries.forEach { entry ->
        when (entry) {
            is MenuEntry.Item -> Item(
                entry.label,
                enabled = entry.enabled,
                shortcut = entry.shortcut?.toNativeKeyShortcut(),
                onClick = entry.onClick,
            )
            is MenuEntry.Checkbox -> CheckboxItem(
                entry.label,
                checked = entry.checked,
                onCheckedChange = entry.onCheckedChange,
            )
            MenuEntry.Separator -> Separator()
            // A native NSMenu submenu; the DSL guarantees its entries hold no further Submenu.
            is MenuEntry.Submenu -> Menu(entry.label, enabled = entry.enabled) { NativeEntries(entry.entries) }
        }
    }
}
