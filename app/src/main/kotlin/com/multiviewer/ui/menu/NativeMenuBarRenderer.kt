package com.multiviewer.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar

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
            Menu(menu.label) {
                menu.entries.forEach { entry ->
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
                    }
                }
            }
        }
    }
}
